import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import fs from 'fs/promises';
import os from 'os';
import path from 'path';
import { MCPManager } from '../../src/mcp/manager.js';
import { createMcpSearchTool } from '../../src/tools/mcp-search.js';
import { getTools, getToolSource } from '../../src/tools/index.js';
import { Agent } from '../../src/core/loop.js';
import { History } from '../../src/core/history.js';
import { buildSystemPrompt } from '../../src/prompts/builder.js';
import { toolSchemaTokens } from '../../src/core/context-stats.js';

/** A server with the given tools (name, description); `big` pads the schemas so the server weighs a lot. */
const serverSource = (tools: Array<{ name: string; description: string }>, big: boolean) => `
const send = (m) => process.stdout.write(JSON.stringify(m) + '\\n');
const tools = ${JSON.stringify(tools)}.map((t) => ({ ...t, inputSchema: { type: 'object', properties: { text: { type: 'string', description: ${big ? "'x'.repeat(900)" : "'t'"} } }, required: ['text'] } }));
let buf = '';
process.stdin.on('data', (d) => { buf += d; const ls = buf.split('\\n'); buf = ls.pop();
  for (const l of ls) { if (!l.trim()) continue; const m = JSON.parse(l);
    if (m.method === 'initialize') send({ jsonrpc: '2.0', id: m.id, result: { capabilities: { tools: {} } } });
    else if (m.method === 'tools/list') send({ jsonrpc: '2.0', id: m.id, result: { tools } });
    else if (m.method === 'tools/call') send({ jsonrpc: '2.0', id: m.id, result: { content: [{ type: 'text', text: 'ran ' + m.params.name }] } }); } });
`;

const NOTION = [
  { name: 'notion-search', description: 'Search pages and databases in the workspace' },
  { name: 'notion-create-pages', description: 'Create new pages with content' },
  { name: 'notion-update-page', description: 'Update the properties of a page' },
  ...Array.from({ length: 30 }, (_, i) => ({ name: `notion-extra-${i}`, description: `Something else number ${i}` })),
];
const SMALL = [{ name: 'ping', description: 'Check that the server is alive' }];

let dir: string;
const originalHome = process.env.HOME;
const original = process.cwd();

async function start(servers: Record<string, any>) {
  await fs.mkdir(path.join(dir, '.devx'), { recursive: true });
  await fs.writeFile(path.join(dir, '.devx', 'mcp.json'), JSON.stringify({ mcpServers: servers }));
  await MCPManager.getInstance().init();
}
const node = (file: string, extra: object = {}) => ({ command: process.execPath, args: [path.join(dir, file)], ...extra });
const names = (tools: Array<{ name: string }>) => tools.map((t) => t.name);
const mcpOnly = (list: string[]) => list.filter((n) => n === 'mcp_search' || n.startsWith('mcp__'));

beforeEach(async () => {
  dir = await fs.mkdtemp(path.join(os.tmpdir(), 'devx-lazy-'));
  await fs.writeFile(path.join(dir, 'big.cjs'), serverSource(NOTION, true));
  await fs.writeFile(path.join(dir, 'small.cjs'), serverSource(SMALL, false));
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

describe('MCP tools on demand', () => {
  it('sends a small server at once and keeps a big one back until the agent loads it', async () => {
    await start({ small: node('small.cjs'), notion: node('big.cjs') });
    const m = MCPManager.getInstance();
    expect(names(m.getTools())).toEqual(['mcp__small__ping']);
    expect(m.hasLazy()).toBe(true);
    expect(m.catalog().map((c) => [c.name, c.lazy, c.total, c.loaded])).toEqual([['small', false, 1, 1], ['notion', true, 33, 0]]);
    // The agent sees the search tool and one small tool instead of 34 schemas.
    const sent = mcpOnly(names(getTools(false)));
    expect(sent).toEqual(['mcp_search', 'mcp__small__ping']);
    expect(toolSchemaTokens(m.getTools().map((t) => t.definition))).toBeLessThan(500);
  });

  it('honours "lazy" in the config in both directions', async () => {
    await start({ small: node('small.cjs', { lazy: true }), notion: node('big.cjs', { lazy: false }) });
    const m = MCPManager.getInstance();
    expect(m.getTools()).toHaveLength(33);
    expect(m.catalog().find((c) => c.name === 'small')!.lazy).toBe(true);
  });

  it('offers no search tool when nothing is lazy', async () => {
    await start({ small: node('small.cjs') });
    expect(names(getTools(false))).not.toContain('mcp_search');
    expect(MCPManager.getInstance().hasLazy()).toBe(false);
  });

  it('lists the servers in the search tool, and a query loads the best matches', async () => {
    await start({ notion: node('big.cjs') });
    const m = MCPManager.getInstance();
    const search = createMcpSearchTool(m);
    expect(search.definition.description).toContain('notion (33 tools, not loaded)');
    expect(search.definition.description).toContain('notion-search');

    const out = await search.execute({ query: 'create page' });
    expect(out).toMatch(/^Loaded \d+ tools?/);
    expect(out).toContain('mcp__notion__notion-create-pages');
    expect(out).toContain('params: text*');
    expect(names(m.getTools())[0]).toBe('mcp__notion__notion-create-pages');
    expect(m.getTools().length).toBeLessThanOrEqual(5);
    expect(search.definition.description).toMatch(/notion \(33 tools, \d of 33 loaded\)/);
  });

  it('loads by name (full, or the server\'s own name), reports unknown names, and lists a server', async () => {
    await start({ notion: node('big.cjs') });
    const m = MCPManager.getInstance();
    const search = createMcpSearchTool(m);
    const out = await search.execute({ names: ['mcp__notion__notion-search', 'notion/notion-update-page', 'nope'] });
    expect(out).toContain('Loaded 2 tools');
    expect(out).toContain('Not found: nope');
    expect(names(m.getTools())).toEqual(['mcp__notion__notion-search', 'mcp__notion__notion-update-page']);
    const listing = await search.execute({ server: 'Notion' });
    expect(listing).toContain('33 tools of Notion');
    expect(listing).toContain('mcp__notion__notion-search (loaded)');
    expect(await search.execute({ server: 'ghost' })).toContain("No MCP server 'ghost'");
    expect(await search.execute({ query: 'zzzz' })).toContain('No tool matches');
  });

  it('loads a tool the agent calls without having loaded it, and tells it to call again', async () => {
    await start({ notion: node('big.cjs') });
    const m = MCPManager.getInstance();
    const hint = m.loadOnMiss('mcp__notion__notion-search');
    expect(hint).toContain('call it again');
    expect(hint).toContain('params: text*');
    expect(names(m.getTools())).toEqual(['mcp__notion__notion-search']);
    expect(m.loadOnMiss('mcp__notion__notion-search')).toBeUndefined();
    expect(m.loadOnMiss('mcp__nothing__x')).toBeUndefined();
  });

  it('keeps what was loaded across a reload, forgets it on reset, restores it from a saved chat', async () => {
    await start({ notion: node('big.cjs') });
    const m = MCPManager.getInstance();
    m.load([m.findTool('notion-search')!]);
    await m.reload();
    expect(names(m.getTools())).toEqual(['mcp__notion__notion-search']);
    m.resetLoaded();
    expect(m.getTools()).toEqual([]);
    m.restoreLoaded([{ role: 'assistant', content: '', tool_calls: [{ id: '1', name: 'mcp__notion__notion-update-page', arguments: {} }] }]);
    expect(names(m.getTools())).toEqual(['mcp__notion__notion-update-page']);
  });

  it('the agent finds a tool with mcp_search and calls it in the next step', async () => {
    await start({ notion: node('big.cjs') });
    const seen: string[][] = [];
    let step = 0;
    const provider: any = {
      id: 't',
      chat: async (req: any) => {
        seen.push(mcpOnly(names(req.tools)));
        step++;
        if (step === 1) return { content: '', toolCalls: [{ id: 'a', name: 'mcp_search', arguments: { query: 'search pages' } }] };
        if (step === 2) return { content: '', toolCalls: [{ id: 'b', name: 'mcp__notion__notion-search', arguments: { text: 'hi' } }] };
        return { content: 'done' };
      },
    };
    const history = new History();
    history.addMessage({ role: 'system', content: 's' });
    history.addMessage({ role: 'user', content: 'find it' });
    const guard = { check: () => false, askUser: async () => true };
    const agent = new Agent({ maxContextTokens: 100000, maxIterations: 10 } as any, provider, getToolSource(false), history, guard as any);
    const results: string[] = [];
    for await (const e of agent.run()) if (e.type === 'tool_end') results.push(e.result);

    expect(seen[0]).toEqual(['mcp_search']);
    expect(seen[1]).toContain('mcp__notion__notion-search');
    expect(results[1]).toBe('ran notion-search');
  });

  it('the agent can call a tool it never loaded: it gets it loaded and a hint instead of "not found"', async () => {
    await start({ notion: node('big.cjs') });
    let step = 0;
    const provider: any = {
      id: 't',
      chat: async () => {
        step++;
        if (step === 1) return { content: '', toolCalls: [{ id: 'a', name: 'mcp__notion__notion-search', arguments: {} }] };
        if (step === 2) return { content: '', toolCalls: [{ id: 'b', name: 'mcp__notion__notion-search', arguments: { text: 'x' } }] };
        return { content: 'done' };
      },
    };
    const history = new History();
    history.addMessage({ role: 'user', content: 'go' });
    const agent = new Agent({ maxContextTokens: 100000, maxIterations: 10 } as any, provider, getToolSource(false), history, { check: () => false, askUser: async () => true } as any);
    const results: string[] = [];
    for await (const e of agent.run()) if (e.type === 'tool_end') results.push(e.result);
    expect(results[0]).toContain('call it again');
    expect(results[1]).toBe('ran notion-search');
  });

  it('the prompt tells how to reach tools that wait, only when some do', async () => {
    await start({ notion: node('big.cjs') });
    expect(await buildSystemPrompt(false)).toContain("'mcp_search'");
    (MCPManager as any).instance = undefined;
    await start({ small: node('small.cjs') });
    expect(await buildSystemPrompt(false)).not.toContain('not loaded, to save context');
  });
});

describe('MCP on demand: edge cases', () => {
  it('names with a server given may be full names', async () => {
    await start({ notion: node('big.cjs') });
    const out = await createMcpSearchTool(MCPManager.getInstance()).execute({ server: 'notion', names: ['mcp__notion__notion-search'] });
    expect(out).toContain('Loaded 1 tool');
  });

  it('a tool called in the same reply as the search that loads it is found', async () => {
    await start({ notion: node('big.cjs') });
    let step = 0;
    const provider: any = {
      id: 't',
      chat: async () => {
        step++;
        if (step === 1) return { content: '', toolCalls: [
          { id: 'a', name: 'mcp_search', arguments: { names: ['notion-search'] } },
          { id: 'b', name: 'mcp__notion__notion-search', arguments: { text: 'x' } },
        ] };
        return { content: 'done' };
      },
    };
    const history = new History();
    history.addMessage({ role: 'user', content: 'go' });
    const agent = new Agent({ maxContextTokens: 100000, maxIterations: 10 } as any, provider, getToolSource(false), history, { check: () => false, askUser: async () => true } as any);
    const results: string[] = [];
    for await (const e of agent.run()) if (e.type === 'tool_end') results.push(e.result);
    expect(results[1]).toBe('ran notion-search');
  });

  it('finds tools whose own name has a colon or a slash', async () => {
    await fs.writeFile(path.join(dir, 'odd.cjs'), serverSource([{ name: 'gh:create', description: 'Create an issue' }, ...NOTION], true));
    await start({ odd: node('odd.cjs') });
    expect(MCPManager.getInstance().findTool('gh:create')?.name).toBe('mcp__odd__gh_create');
    expect(MCPManager.getInstance().findTool('odd/gh:create')?.name).toBe('mcp__odd__gh_create');
  });

  it('a tool the agent already used stays in the requests when its server turns lazy', async () => {
    await start({ small: node('small.cjs') });
    const m = MCPManager.getInstance();
    await m.getTools()[0].execute({ text: 'x' });
    await fs.writeFile(path.join(dir, '.devx', 'mcp.json'), JSON.stringify({ mcpServers: { small: node('small.cjs', { lazy: true }) } }));
    await m.reload();
    expect(names(m.getTools())).toEqual(['mcp__small__ping']);
  });
});
