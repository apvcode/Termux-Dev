import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import http from 'http';
import fs from 'fs/promises';
import fsSync from 'fs';
import os from 'os';
import path from 'path';
import { HttpMCPClient } from '../../src/mcp/http-client.js';
import { MCPManager, removeUserServer, saveUserServers } from '../../src/mcp/manager.js';
import { mcpManageTool } from '../../src/tools/mcp.js';

interface Seen { method: string; headers: http.IncomingHttpHeaders; body: any }
/** A remote MCP server: JSON answers, or an event stream for tools/call when `stream` is set. */
async function startServer(opts: { stream?: boolean; token?: string; session?: boolean } = {}) {
  const seen: Seen[] = [];
  const server = http.createServer((req, res) => {
    let raw = '';
    req.on('data', (d) => (raw += d));
    req.on('end', () => {
      const body = raw ? JSON.parse(raw) : null;
      seen.push({ method: req.method!, headers: req.headers, body });
      if (opts.token && req.headers.authorization !== `Bearer ${opts.token}`) { res.statusCode = 401; return res.end('no'); }
      if (req.method === 'DELETE') { res.statusCode = 200; return res.end(); }
      if (!body.id) { res.statusCode = 202; return res.end(); }
      const reply = (result: unknown) => {
        const headers: Record<string, string> = {};
        if (opts.session && body.method === 'initialize') headers['mcp-session-id'] = 'sess-1';
        if (opts.stream && body.method === 'tools/call') {
          res.writeHead(200, { ...headers, 'content-type': 'text/event-stream' });
          res.write('event: message\ndata: {"jsonrpc":"2.0","method":"notifications/progress","params":{}}\n\n');
          res.write('event: message\ndata: ' + JSON.stringify({ jsonrpc: '2.0', id: body.id, result }) + '\n\n');
          return res.end();
        }
        res.writeHead(200, { ...headers, 'content-type': 'application/json' });
        res.end(JSON.stringify({ jsonrpc: '2.0', id: body.id, result }));
      };
      if (body.method === 'initialize') return reply({ protocolVersion: '2025-03-26', capabilities: { tools: {} }, serverInfo: { name: 'x', version: '1' } });
      if (body.method === 'tools/list') return reply(body.params?.cursor ? { tools: [{ name: 'second', inputSchema: { type: 'object' } }] } : { tools: [{ name: 'echo', description: 'Echo', annotations: { readOnlyHint: true }, inputSchema: { type: 'object', properties: { text: { type: 'string' } } } }], nextCursor: 'p2' });
      if (body.method === 'tools/call') return reply(body.params.name === 'fail' ? { content: [{ type: 'text', text: 'it failed' }], isError: true } : { content: [{ type: 'text', text: 'echo: ' + body.params.arguments.text }] });
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ jsonrpc: '2.0', id: body.id, error: { code: -32601, message: 'nope' } }));
    });
  });
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
  const url = `http://127.0.0.1:${(server.address() as any).port}/mcp`;
  return { url, seen, close: () => new Promise<void>((r) => { server.closeAllConnections?.(); server.close(() => r()); }) };
}

describe('a remote MCP server over HTTP', () => {
  let srv: Awaited<ReturnType<typeof startServer>>;
  afterEach(async () => { await srv?.close(); });

  it('connects, lists all pages of tools, calls a tool and keeps the session', async () => {
    srv = await startServer({ session: true });
    const client = new HttpMCPClient('remote', { url: srv.url });
    const tools = await client.start();
    expect(tools.map((t) => t.name)).toEqual(['echo', 'second']);
    expect(client.getCapabilities()).toEqual(['tools']);
    expect(await client.callTool('echo', { text: 'hi' })).toBe('echo: hi');
    // after the first answer every request carries the session id
    const later = srv.seen.filter((s) => s.body?.method === 'tools/list');
    expect(later[0].headers['mcp-session-id']).toBe('sess-1');
    expect(srv.seen[0].headers.accept).toContain('text/event-stream');
    client.close();
    await new Promise((r) => setTimeout(r, 100));
    expect(srv.seen.some((s) => s.method === 'DELETE' && s.headers['mcp-session-id'] === 'sess-1')).toBe(true);
  });

  it('reads the answer out of an event stream', async () => {
    srv = await startServer({ stream: true });
    const client = new HttpMCPClient('remote', { url: srv.url });
    await client.start();
    expect(await client.callTool('echo', { text: 'streamed' })).toBe('echo: streamed');
    client.close();
  });

  it('a failing tool is an error with its text', async () => {
    srv = await startServer();
    const client = new HttpMCPClient('remote', { url: srv.url });
    await client.start();
    await expect(client.callTool('fail', {})).rejects.toThrow('it failed');
    client.close();
  });

  it('sends the headers, with ${NAME} read from the environment, and explains a 401', async () => {
    srv = await startServer({ token: 's3cret' });
    process.env.DEVX_TEST_TOKEN = 's3cret';
    const ok = new HttpMCPClient('remote', { url: srv.url, headers: { Authorization: 'Bearer ${DEVX_TEST_TOKEN}' } });
    expect((await ok.start()).length).toBe(2);
    ok.close();
    const bad = new HttpMCPClient('remote', { url: srv.url });
    await expect(bad.start()).rejects.toThrow(/refused the request \(401\).*mcp-remote/s);
    delete process.env.DEVX_TEST_TOKEN;
  });

  it('a server that is not there fails with an error, not a hang', async () => {
    const dead = new HttpMCPClient('gone', { url: 'http://127.0.0.1:9/mcp' });
    await expect(dead.start()).rejects.toThrow();
  });
});

describe('url servers in the manager, and removing a server', () => {
  let dir: string;
  let srv: Awaited<ReturnType<typeof startServer>>;
  const originalHome = process.env.HOME;
  const original = process.cwd();
  beforeEach(async () => {
    dir = await fs.mkdtemp(path.join(os.tmpdir(), 'devx-mcph-'));
    process.env.HOME = dir; process.env.USERPROFILE = dir; process.chdir(dir);
    (MCPManager as any).instance = undefined;
    srv = await startServer();
  });
  afterEach(async () => {
    MCPManager.getInstance().stopAll();
    (MCPManager as any).instance = undefined;
    process.chdir(original);
    if (originalHome === undefined) delete process.env.HOME; else process.env.HOME = originalHome;
    delete process.env.USERPROFILE;
    await srv.close();
    await fs.rm(dir, { recursive: true, force: true, maxRetries: 10, retryDelay: 150 }).catch((e) => { if (process.platform !== 'win32') throw e; });
  });

  it('starts a server given by url, registers its tools and calls them', async () => {
    saveUserServers({ remote: { url: srv.url } });
    const m = MCPManager.getInstance();
    await m.init();
    const status = m.getStatus('remote')!;
    expect(status).toMatchObject({ status: 'connected', toolsCount: 2, command: srv.url, scope: 'user' });
    const tool = m.getTools().find((t) => t.name === 'mcp__remote__echo')!;
    expect(await tool.execute({ text: 'x' }, {} as any, {} as any)).toBe('echo: x');
  });

  it('removes a server from ~/.devx/mcp.json, and says no for one that is not there', async () => {
    saveUserServers({ a: { url: srv.url }, b: { command: 'x' } });
    expect(removeUserServer('a')).toBe(true);
    expect(JSON.parse(fsSync.readFileSync(path.join(dir, '.devx', 'mcp.json'), 'utf8')).mcpServers).toEqual({ b: { command: 'x' } });
    expect(removeUserServer('a')).toBe(false);
    expect(removeUserServer('nope')).toBe(false);
  });

  it('the agent can add a remote server by url', async () => {
    await MCPManager.getInstance().init();
    const out = JSON.parse(await mcpManageTool.execute({ action: 'add', name: 'remote', url: srv.url }, {} as any, {} as any));
    expect(out.servers[0]).toMatchObject({ name: 'remote', status: 'connected', tools: 2 });
    expect(() => mcpManageTool.validateArgs({ action: 'add', name: 'x', url: 'ftp://nope' })).toThrow();
    expect(() => mcpManageTool.validateArgs({ action: 'add', name: 'x', url: 'https://ok.example/mcp' })).not.toThrow();
    expect(() => mcpManageTool.validateArgs({ action: 'add', name: 'x' })).toThrow();
  });
});
