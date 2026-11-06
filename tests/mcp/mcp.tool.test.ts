import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import fs from 'fs/promises';
import os from 'os';
import path from 'path';
import { mcpManageTool } from '../../src/tools/mcp.js';
import { getTools } from '../../src/tools/index.js';
import { MCPManager } from '../../src/mcp/manager.js';
import { CLIConsoleGuard, buildPermissionRequest } from '../../src/permissions/guard.js';
import { buildSystemPrompt } from '../../src/prompts/builder.js';

const SERVER = `
const send = (m) => process.stdout.write(JSON.stringify(m) + '\\n');
let buf = '';
process.stdin.on('data', (d) => { buf += d; const ls = buf.split('\\n'); buf = ls.pop();
  for (const l of ls) { if (!l.trim()) continue; const m = JSON.parse(l);
    if (m.method === 'initialize') send({ jsonrpc: '2.0', id: m.id, result: { capabilities: { tools: {} } } });
    else if (m.method === 'tools/list') send({ jsonrpc: '2.0', id: m.id, result: { tools: [{ name: 'ping', inputSchema: { type: 'object' } }] } }); } });
`;
let dir: string;
const originalHome = process.env.HOME;
const original = process.cwd();

beforeEach(async () => {
  dir = await fs.mkdtemp(path.join(os.tmpdir(), 'devx-mcpt-'));
  await fs.writeFile(path.join(dir, 'server.cjs'), SERVER);
  process.env.HOME = dir;
  process.env.USERPROFILE = dir;
  process.chdir(dir);
  (MCPManager as any).instance = undefined;
});
afterEach(async () => {
  MCPManager.getInstance().stopAll();
  (MCPManager as any).instance = undefined;
  process.chdir(original);
  if (originalHome === undefined) delete process.env.HOME; else process.env.HOME = originalHome;
  delete process.env.USERPROFILE;
  await fs.rm(dir, { recursive: true, force: true, maxRetries: 10, retryDelay: 150 }).catch((e) => { if (process.platform !== 'win32') throw e; });
});

describe('the agent sets up MCP', () => {
  it('knows the tool in agent mode (not in plan mode) and the prompt explains how MCP works', async () => {
    expect(getTools(false).map((t) => t.name)).toContain('mcp_manage');
    expect(getTools(true).map((t) => t.name)).not.toContain('mcp_manage');
    const prompt = await buildSystemPrompt(false);
    expect(prompt).toContain('~/.devx/mcp.json');
    expect(prompt).toContain('mcp_manage');
    expect(prompt).toContain('mcp-remote');
    expect(prompt).toContain('Secrets go into env');
    expect(await buildSystemPrompt(true)).not.toContain("use 'mcp_manage'");
  });

  it('adds a server, starts it, saves it for the next sessions and reports the result', async () => {
    await MCPManager.getInstance().init();
    const out = JSON.parse(await mcpManageTool.execute({ action: 'add', name: 'fake', command: process.execPath, args: [path.join(dir, 'server.cjs')] }, {} as any, {} as any));
    expect(out.servers).toEqual([expect.objectContaining({ name: 'fake', status: 'connected', tools: 1, sample_tools: ['ping'] })]);
    expect(MCPManager.getInstance().getTools().map((t) => t.name)).toEqual(['mcp__fake__ping']);
    expect(JSON.parse(await fs.readFile(path.join(dir, '.devx', 'mcp.json'), 'utf8')).mcpServers.fake.command).toBe(process.execPath);
    expect(JSON.parse(await mcpManageTool.execute({ action: 'list' }, {} as any, {} as any)).servers).toHaveLength(1);
  });

  it('reports a server that does not start instead of pretending', async () => {
    await MCPManager.getInstance().init();
    const out = JSON.parse(await mcpManageTool.execute({ action: 'add', name: 'broken', command: process.execPath, args: ['-e', 'process.exit(1)'] }, {} as any, {} as any));
    expect(out.servers[0].status).toBe('failed');
    expect(out.servers[0].error).toBeTruthy();
  });

  it('rejects bad names and missing commands', () => {
    expect(() => mcpManageTool.validateArgs({ action: 'add', name: 'bad name!', command: 'x' })).toThrow();
    expect(() => mcpManageTool.validateArgs({ action: 'add', name: 'ok' })).toThrow();
    expect(() => mcpManageTool.validateArgs({ action: 'nope' })).toThrow();
    expect(() => mcpManageTool.validateArgs({ action: 'list' })).not.toThrow();
  });

  it('always asks the user before adding a server, also in YOLO; listing needs no question', () => {
    for (const yolo of [false, true]) {
      const guard = new CLIConsoleGuard(yolo);
      expect(guard.check('mcp_manage', { action: 'add', name: 'x', command: 'npx' })).toBe(true);
      expect(guard.check('mcp_manage', { action: 'import_claude' })).toBe(true);
      expect(guard.check('mcp_manage', { action: 'list' })).toBe(false);
    }
    const card = buildPermissionRequest('mcp_manage', { action: 'add', name: 'notion', command: 'npx', args: ['-y', 'mcp-remote', 'https://mcp.notion.com/mcp'], env: { TOKEN: 'secret' } });
    expect(card.title).toBe('mcp · add server notion');
    expect(card.lines.join('\n')).toContain('npx -y mcp-remote https://mcp.notion.com/mcp');
    expect(card.lines.join('\n')).toContain('env: TOKEN');
    expect(card.lines.join('\n')).not.toContain('secret');
  });
});

describe('running devx from the home folder', () => {
  it('the user files are not mistaken for a project: no approval, and the server starts', async () => {
    await fs.mkdir(path.join(dir, '.devx'), { recursive: true });
    await fs.writeFile(path.join(dir, '.devx', 'mcp.json'), JSON.stringify({ mcpServers: { fake: { command: process.execPath, args: [path.join(dir, 'server.cjs')] } } }));
    const { discoverProjectMcpServers } = await import('../../src/mcp/manager.js');
    expect(discoverProjectMcpServers(dir)).toEqual([]);          // cwd is the home folder
    await MCPManager.getInstance().init(undefined, false);       // project not approved
    expect(MCPManager.getInstance().getStatus('fake')).toMatchObject({ status: 'connected', scope: 'user' });
  });
});
