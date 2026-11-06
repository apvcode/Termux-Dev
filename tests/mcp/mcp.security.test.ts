import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import fs from 'fs/promises';
import os from 'os';
import path from 'path';
import { MCPClient } from '../../src/mcp/client.js';
import { MCPManager, discoverProjectMcpServers, hashProjectMcpServers } from '../../src/mcp/manager.js';

// A tiny MCP server. When DEVX_TEST_PING is set it first sends the client a
// *request* with id 1 (the same id the client uses for `initialize`) and records
// everything the client writes back.
const SERVER_SOURCE = `
const fs = require('fs');
const log = process.env.DEVX_TEST_LOG;
const send = (m) => process.stdout.write(JSON.stringify(m) + '\\n');
if (process.env.DEVX_TEST_PING) send({ jsonrpc: '2.0', id: 1, method: 'ping' });
if (process.env.DEVX_TEST_UNKNOWN) send({ jsonrpc: '2.0', id: 2, method: 'roots/list' });
let buf = '';
process.stdin.on('data', (d) => {
  buf += d;
  const lines = buf.split('\\n'); buf = lines.pop();
  for (const line of lines) {
    if (!line.trim()) continue;
    const msg = JSON.parse(line);
    if (log) fs.appendFileSync(log, line + '\\n');
    if (msg.method === 'initialize') send({ jsonrpc: '2.0', id: msg.id, result: { protocolVersion: '2024-11-05', capabilities: {}, serverInfo: { name: 'fake', version: '1' } } });
    else if (msg.method === 'tools/list') send({ jsonrpc: '2.0', id: msg.id, result: { tools: [{ name: 'hello', description: 'd', inputSchema: { type: 'object', properties: {} } }] } });
  }
});
`;

let dir: string;
let serverFile: string;
const original = process.cwd();
const originalHome = process.env.HOME;

beforeEach(async () => {
  dir = await fs.mkdtemp(path.join(os.tmpdir(), 'devx-mcp-'));
  serverFile = path.join(dir, 'server.cjs');
  await fs.writeFile(serverFile, SERVER_SOURCE);
});
afterEach(async () => {
  MCPManager.getInstance().stopAll();
  (MCPManager as any).instance = undefined;
  process.chdir(original);
  if (originalHome === undefined) delete process.env.HOME; else process.env.HOME = originalHome;
  delete process.env.USERPROFILE;
  await fs.rm(dir, { recursive: true, force: true, maxRetries: 10, retryDelay: 150 }).catch((e) => { if (process.platform !== 'win32') throw e; });
});

describe('MCPClient server-initiated messages', () => {
  it('answers ping and does not mistake a server request for the response to initialize', async () => {
    const log = path.join(dir, 'client-output.log');
    const client = new MCPClient('fake', {
      command: process.execPath,
      args: [serverFile],
      env: { DEVX_TEST_PING: '1', DEVX_TEST_UNKNOWN: '1', DEVX_TEST_LOG: log },
    });
    const tools = await client.start();
    client.close();

    // initialize resolved with the REAL response, so the handshake and tool listing worked
    expect(tools.map(t => t.name)).toEqual(['hello']);

    const written = (await fs.readFile(log, 'utf8')).trim().split('\n').map(l => JSON.parse(l));
    expect(written).toContainEqual({ jsonrpc: '2.0', id: 1, result: {} }); // pong
    expect(written).toContainEqual(expect.objectContaining({ id: 2, error: expect.objectContaining({ code: -32601 }) }));
  });
});

describe('project MCP servers need approval', () => {
  const writeProjectConfig = async (rel: string, servers: Record<string, any>) => {
    const file = path.join(dir, rel);
    await fs.mkdir(path.dirname(file), { recursive: true });
    await fs.writeFile(file, JSON.stringify({ mcpServers: servers }));
  };

  const setup = async () => {
    // isolate from the real ~/.devxrc.json of whoever runs the tests
    const home = path.join(dir, 'home');
    await fs.mkdir(home);
    process.env.HOME = home;
    process.env.USERPROFILE = home;
    process.chdir(dir);
    (MCPManager as any).instance = undefined;
  };

  it('does not start servers declared by the project unless they were approved', async () => {
    await setup();
    const marker = path.join(dir, 'started');
    await writeProjectConfig('.mcp.json', {
      evil: { command: process.execPath, args: ['-e', `require('fs').writeFileSync(${JSON.stringify(marker)}, 'x')`] },
    });
    await writeProjectConfig(path.join('.devx', 'mcp.json'), { fake: { command: process.execPath, args: [serverFile] } });

    const tools = await MCPManager.getInstance().init(); // default: project not trusted
    expect(tools).toEqual([]);
    expect(MCPManager.getInstance().getStatuses()).toEqual([]);
    await new Promise(r => setTimeout(r, 300));
    await expect(fs.access(marker)).rejects.toThrow();
  });

  it('starts them once the user approved the project', async () => {
    await setup();
    await writeProjectConfig('.mcp.json', { fake: { command: process.execPath, args: [serverFile] } });

    const tools = await MCPManager.getInstance().init(undefined, true);
    expect(tools.map(t => t.name)).toEqual(['mcp__fake__hello']);
    expect(MCPManager.getInstance().getStatuses()[0].status).toBe('connected');
  });

  it('still starts servers from the user-level config when the project is untrusted', async () => {
    await setup();
    await fs.writeFile(
      path.join(process.env.HOME!, '.devxrc.json'),
      JSON.stringify({ mcpServers: { mine: { command: process.execPath, args: [serverFile] } } })
    );
    await writeProjectConfig('.mcp.json', { theirs: { command: process.execPath, args: ['-e', ''] } });

    const tools = await MCPManager.getInstance().init();
    expect(tools.map(t => t.name)).toEqual(['mcp__mine__hello']);
  });

  it('fingerprints commands, args and env so any change asks again', async () => {
    await setup();
    await writeProjectConfig('.mcp.json', { a: { command: 'node', args: ['x.js'] } });
    const base = hashProjectMcpServers(discoverProjectMcpServers(dir));
    expect(hashProjectMcpServers(discoverProjectMcpServers(dir))).toBe(base);

    await writeProjectConfig('.mcp.json', { a: { command: 'node', args: ['y.js'] } });
    const changedArgs = hashProjectMcpServers(discoverProjectMcpServers(dir));
    expect(changedArgs).not.toBe(base);

    await writeProjectConfig('.mcp.json', { a: { command: 'node', args: ['y.js'], env: { TOKEN: '1' } } });
    expect(hashProjectMcpServers(discoverProjectMcpServers(dir))).not.toBe(changedArgs);
  });

  it('discovers servers in every project config location', async () => {
    await setup();
    await writeProjectConfig('.mcp.json', { a: { command: 'a' } });
    await writeProjectConfig(path.join('.claude', 'mcp.json'), { b: { command: 'b' } });
    await writeProjectConfig('.devx.json', { c: { command: 'c' } });
    await writeProjectConfig(path.join('.devx', 'mcp.json'), { d: { command: 'd' } });
    expect(discoverProjectMcpServers(dir).map(s => s.name).sort()).toEqual(['a', 'b', 'c', 'd']);
  });
});
