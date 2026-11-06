import { EventEmitter } from 'events';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import fs from 'fs/promises';
import os from 'os';
import path from 'path';
import { MCPManager } from '../../src/mcp/manager.js';
import { MCPServerStatus } from '../../src/mcp/types.js';
import { PanelView, actionsFor, orderedServers, renderMcpPanel, rowCount, runMcpPanel } from '../../src/cli/mcp-panel.js';
import { setActiveTheme } from '../../src/cli/theme.js';
import { setLanguage } from '../../src/core/i18n.js';
import { displayWidth, stripAnsi } from '../../src/cli/width.js';

// A fake MCP server with annotations and capabilities, so the panel has something to show.
const SERVER_SOURCE = `
const send = (m) => process.stdout.write(JSON.stringify(m) + '\\n');
let buf = '';
process.stdin.on('data', (d) => {
  buf += d;
  const lines = buf.split('\\n'); buf = lines.pop();
  for (const line of lines) {
    if (!line.trim()) continue;
    const msg = JSON.parse(line);
    if (msg.method === 'initialize') send({ jsonrpc: '2.0', id: msg.id, result: { protocolVersion: '2024-11-05', capabilities: { tools: {}, resources: {} }, serverInfo: { name: 'fake', version: '1' } } });
    else if (msg.method === 'tools/list') send({ jsonrpc: '2.0', id: msg.id, result: { tools: [
      { name: 'search', description: 'Search the workspace', annotations: { readOnlyHint: true, openWorldHint: true }, inputSchema: { type: 'object', properties: { query: { type: 'string', description: 'What to look for' }, limit: { type: 'number' } }, required: ['query'] } },
      { name: 'delete-page', description: 'Delete a page', annotations: { destructiveHint: true }, inputSchema: { type: 'object', properties: {} } },
      { name: 'plain', inputSchema: { type: 'object' } },
    ] } });
  }
});
`;

let dir: string;
let serverFile: string;
const original = process.cwd();
const originalHome = process.env.HOME;

beforeEach(async () => {
  setActiveTheme('cyan');
  setLanguage('en');
  dir = await fs.mkdtemp(path.join(os.tmpdir(), 'devx-mcpp-'));
  serverFile = path.join(dir, 'server.cjs');
  await fs.writeFile(serverFile, SERVER_SOURCE);
  const home = path.join(dir, 'home');
  await fs.mkdir(path.join(home, '.devx'), { recursive: true });
  process.env.HOME = home;
  process.env.USERPROFILE = home;
  process.chdir(dir);
  (MCPManager as any).instance = undefined;
});
afterEach(async () => {
  MCPManager.getInstance().stopAll();
  (MCPManager as any).instance = undefined;
  process.chdir(original);
  if (originalHome === undefined) delete process.env.HOME; else process.env.HOME = originalHome;
  delete process.env.USERPROFILE;
  setLanguage('en');
  await fs.rm(dir, { recursive: true, force: true });
});

const writeUserConfig = (servers: Record<string, any>) =>
  fs.writeFile(path.join(process.env.HOME!, '.devx', 'mcp.json'), JSON.stringify({ mcpServers: servers }));
const fake = { command: process.execPath, args: ['serve'] };

describe('MCP manager: what the panel needs', () => {
  it('reports where a server comes from, what it can do and what its tools are like', async () => {
    await writeUserConfig({ notion: { command: process.execPath, args: [serverFile] } });
    await MCPManager.getInstance().init();
    const s = MCPManager.getInstance().getStatus('notion')!;
    expect(s.status).toBe('connected');
    expect(s.scope).toBe('user');
    expect(s.source).toBe('~/.devx/mcp.json');
    expect(s.capabilities).toEqual(['tools', 'resources']);
    expect(s.toolInfo!.map((t) => t.name)).toEqual(['search', 'delete-page', 'plain']);
    const search = s.toolInfo![0];
    expect(search).toMatchObject({ readOnly: true, openWorld: true, destructive: false });
    expect(search.params).toEqual([
      { name: 'query', type: 'string', required: true, description: 'What to look for' },
      { name: 'limit', type: 'number', required: false, description: '' },
    ]);
    expect(s.toolInfo![1]).toMatchObject({ destructive: true, readOnly: false });
    expect(s.toolInfo![2]).toMatchObject({ readOnly: false, destructive: false, openWorld: false, description: '', params: [] });
  });

  it('marks servers of the project as project servers, with the file they come from', async () => {
    await fs.mkdir(path.join(dir, '.devx'));
    await fs.writeFile(path.join(dir, '.devx', 'mcp.json'), JSON.stringify({ mcpServers: { local: { command: process.execPath, args: [serverFile] } } }));
    await MCPManager.getInstance().init(undefined, true);
    expect(MCPManager.getInstance().getStatus('local')).toMatchObject({ scope: 'project', source: path.join('.devx', 'mcp.json'), status: 'connected' });
  });

  it('switching a server off stops it, takes its tools from the agent and survives a restart; the config file stays untouched', async () => {
    const configFile = path.join(process.env.HOME!, '.devx', 'mcp.json');
    await writeUserConfig({ notion: { command: process.execPath, args: [serverFile] } });
    const before = await fs.readFile(configFile, 'utf8');
    const m = MCPManager.getInstance();
    await m.init();
    expect(m.getTools().map((t) => t.name)).toContain('mcp__notion__search');

    const off = await m.setEnabled('notion', false);
    expect(off!.status).toBe('disabled');
    expect(m.getTools()).toEqual([]);
    expect(await fs.readFile(configFile, 'utf8')).toBe(before);

    // a new session: still off
    m.stopAll();
    (MCPManager as any).instance = undefined;
    const m2 = MCPManager.getInstance();
    await m2.init();
    expect(m2.getStatus('notion')!.status).toBe('disabled');
    expect(m2.getTools()).toEqual([]);

    const on = await m2.setEnabled('notion', true);
    expect(on!.status).toBe('connected');
    expect(m2.getTools().map((t) => t.name)).toContain('mcp__notion__search');
  });

  it('a server that is disabled in its own config can be switched on from the panel', async () => {
    await writeUserConfig({ notion: { command: process.execPath, args: [serverFile], disabled: true } });
    const m = MCPManager.getInstance();
    await m.init();
    expect(m.getStatus('notion')!.status).toBe('disabled');
    expect((await m.setEnabled('notion', true))!.status).toBe('connected');
  });

  it('reconnect brings a failed server back without duplicating its tools', async () => {
    await writeUserConfig({ notion: { command: process.execPath, args: [serverFile] }, broken: { command: process.execPath, args: ['-e', 'process.exit(1)'] } });
    const m = MCPManager.getInstance();
    await m.init();
    expect(m.getStatus('broken')!.status).toBe('failed');
    expect(m.getStatus('broken')!.error).toBeTruthy();
    const count = m.getTools().length;
    const again = await m.reconnect('notion');
    expect(again!.status).toBe('connected');
    expect(m.getTools()).toHaveLength(count);
    expect(await m.reconnect('nope')).toBeUndefined();
  });
});

const status = (over: Partial<MCPServerStatus>): MCPServerStatus => ({
  name: 'notion', command: 'npx -y mcp-remote https://mcp.notion.com/mcp', status: 'connected', toolsCount: 3, tools: ['a', 'b', 'c'],
  source: '~/.devxrc.json', scope: 'user', capabilities: ['tools', 'resources', 'prompts'],
  toolInfo: [
    { name: 'notion-search', description: 'Search the workspace', readOnly: true, destructive: false, openWorld: true, params: [{ name: 'query', type: 'string', required: true, description: 'What to look for' }] },
    { name: 'notion-delete-pages', description: 'Delete pages', readOnly: false, destructive: true, openWorld: false, params: [] },
    { name: 'notion-create-pages', description: '', readOnly: false, destructive: false, openWorld: false, params: [] },
  ],
  ...over,
});
const statuses = [
  status({}),
  status({ name: 'roblox-studio', toolsCount: 35 }),
  status({ name: 'mine-local', scope: 'project', source: '.devx/mcp.json', status: 'failed', toolsCount: 0, error: 'spawn ENOENT' }),
  status({ name: 'old', status: 'disabled', toolsCount: 0, toolInfo: [] }),
];
const view = (over: Partial<PanelView>): PanelView => ({ statuses, screen: { kind: 'servers' }, focus: 0, ...over });
const plain = (v: PanelView, cols = 100, rows = 40) => renderMcpPanel(v, cols, rows).map(stripAnsi);

describe('the /mcp panel screens', () => {
  it('lists the servers by scope, with their state and tool counts', () => {
    const text = plain(view({})).join('\n');
    expect(text).toMatch(/^╭─ MCP servers ─+ servers: 4 ─╮/);
    expect(text).toContain('User servers');
    expect(text).toContain('Project servers');
    expect(text).toMatch(/❯ ● notion\s+tools: 3/);
    expect(text).toMatch(/● roblox-studio\s+tools: 35/);
    expect(text).toMatch(/× mine-local\s+failed/);
    expect(text).toMatch(/○ old\s+disabled/);
    // the user's servers come before the project's
    expect(text.indexOf('User servers')).toBeLessThan(text.indexOf('Project servers'));
    expect(orderedServers(statuses).map((s) => s.name)).toEqual(['notion', 'roblox-studio', 'old', 'mine-local']);
  });

  it('says how to add servers when there are none', () => {
    const text = plain(view({ statuses: [] })).join('\n');
    expect(text).toContain('No MCP servers configured.');
    expect(text).toContain('/mcp import');
  });

  it('one server: status, command, config file, capabilities and the actions that fit its state', () => {
    const text = plain(view({ screen: { kind: 'server', name: 'notion' } })).join('\n');
    expect(text).toContain('Status:');
    expect(text).toContain('connected');
    expect(text).toContain('npx -y mcp-remote https://mcp.notion.com/mcp');
    expect(text).toContain('~/.devxrc.json');
    expect(text).toContain('tools · resources · prompts');
    expect(text).toMatch(/❯ 1\. View tools/);
    expect(text).toContain('2. Reconnect');
    expect(text).toContain('3. Disable');

    const failed = plain(view({ screen: { kind: 'server', name: 'mine-local' } })).join('\n');
    expect(failed).toContain('spawn ENOENT');
    expect(failed).not.toContain('View tools');
    expect(plain(view({ screen: { kind: 'server', name: 'old' } })).join('\n')).toMatch(/1\. Enable/);
    expect(actionsFor(statuses[3])).toEqual(['enable']);
    expect(actionsFor(statuses[2])).toEqual(['reconnect', 'disable']);
  });

  it('the tools of a server with their tags, and one tool with its parameters', () => {
    const list = plain(view({ screen: { kind: 'tools', name: 'notion' } })).join('\n');
    expect(list).toMatch(/Tools of notion/);
    expect(list).toMatch(/notion-search\s+read-only · open-world/);
    expect(list).toMatch(/notion-delete-pages\s+destructive/);
    expect(list).toMatch(/notion-create-pages\s*│/);

    const one = plain(view({ screen: { kind: 'tool', name: 'notion', tool: 'notion-search' } })).join('\n');
    expect(one).toContain('Search the workspace');
    expect(one).toMatch(/query\s+string\s+required/);
    expect(one).toContain('What to look for');
  });

  it('long lists are windowed around the focus and say how much is hidden', () => {
    const many = status({ toolInfo: Array.from({ length: 60 }, (_, i) => ({ name: `tool-${i}`, description: '', readOnly: false, destructive: false, openWorld: false, params: [] })) });
    const rows = 20;
    const top = plain(view({ statuses: [many], screen: { kind: 'tools', name: 'notion' }, focus: 0 }), 100, rows);
    expect(top.length).toBeLessThanOrEqual(rows - 1);
    expect(top.join('\n')).toMatch(/↓ \d+ more/);
    expect(top.join('\n')).not.toMatch(/↑ \d+ more/);
    const middle = plain(view({ statuses: [many], screen: { kind: 'tools', name: 'notion' }, focus: 30 }), 100, rows);
    expect(middle.join('\n')).toContain('❯ tool-30');
    expect(middle.join('\n')).toMatch(/↑ \d+ more/);
    const end = plain(view({ statuses: [many], screen: { kind: 'tools', name: 'notion' }, focus: 59 }), 100, rows);
    expect(end.join('\n')).toContain('❯ tool-59');
    expect(end.join('\n')).not.toMatch(/↓ \d+ more/);
    for (const focus of [0, 17, 30, 59]) {
      expect(plain(view({ statuses: [many], screen: { kind: 'tools', name: 'notion' }, focus }), 100, rows).join('\n')).toContain(`❯ tool-${focus}`);
    }
  });

  it('every screen fits the width of the terminal, framed and on a phone', () => {
    const screens: PanelView['screen'][] = [
      { kind: 'servers' }, { kind: 'server', name: 'notion' }, { kind: 'server', name: 'mine-local' },
      { kind: 'tools', name: 'notion' }, { kind: 'tool', name: 'notion', tool: 'notion-search' },
    ];
    for (const cols of [100, 76, 60, 50, 40, 30]) {
      for (const screen of screens) {
        const lines = plain(view({ screen }), cols, 30);
        for (const line of lines) expect(displayWidth(line), `${cols} ${JSON.stringify(screen)}: ${line}`).toBeLessThanOrEqual(cols - 1);
        if (cols >= 60) {
          const box = lines.filter((l) => /^[╭│╰]/.test(l));
          expect(new Set(box.map((l) => displayWidth(l))).size, `${cols} ${JSON.stringify(screen)}`).toBe(1);
        }
      }
    }
  });

  it('is translated, and CJK text keeps the box straight', () => {
    for (const id of ['ru', 'de', 'zh', 'ja']) {
      setLanguage(id);
      for (const screen of [{ kind: 'servers' }, { kind: 'server', name: 'notion' }, { kind: 'tools', name: 'notion' }] as PanelView['screen'][]) {
        const lines = plain(view({ screen }), 90, 30);
        const box = lines.filter((l) => /^[╭│╰]/.test(l));
        expect(new Set(box.map((l) => displayWidth(l))).size, `${id} ${screen.kind}`).toBe(1);
      }
    }
    setLanguage('ru');
    expect(plain(view({})).join('\n')).toContain('MCP-серверы');
  });

  it('counts the rows of each screen for the keyboard', () => {
    expect(rowCount(view({}))).toBe(4);
    expect(rowCount(view({ screen: { kind: 'server', name: 'notion' } }))).toBe(3);
    expect(rowCount(view({ screen: { kind: 'tools', name: 'notion' } }))).toBe(3);
    expect(rowCount(view({ screen: { kind: 'tool', name: 'notion', tool: 'x' } }))).toBe(0);
  });
});

describe('runMcpPanel keyboard', () => {
  class FakeInput extends EventEmitter {
    isRaw = false;
    setRawMode(m: boolean) { this.isRaw = m; }
    resume() {}
  }
  let input: FakeInput;
  let out: string[];
  const output = { columns: 100, rows: 40, write: (c: string) => { out.push(c); return true; } };
  const press = (...keys: string[]) => keys.forEach((k) => input.emit('data', Buffer.from(k)));
  const screenText = () => stripAnsi(out[out.length - 1]);
  const DOWN = '\x1b[B', ENTER = '\r', ESC = '\x1b';
  const tick = () => new Promise((r) => setTimeout(r, 10));

  const calls: string[] = [];
  const controller = (list: MCPServerStatus[]) => ({
    getStatuses: () => list,
    reconnect: async (n: string) => { calls.push('reconnect:' + n); },
    setEnabled: async (n: string, on: boolean) => { calls.push(`${on ? 'enable' : 'disable'}:${n}`); },
    reload: async () => { calls.push('reload'); },
  });

  beforeEach(() => {
    input = new FakeInput();
    out = [];
    calls.length = 0;
  });

  it('goes in with Enter and back with Esc, and closes on Esc from the list', async () => {
    const done = runMcpPanel(controller(statuses), { io: { input: input as any, output } });
    expect(screenText()).toContain('MCP servers');
    press(ENTER);
    expect(screenText()).toContain('Status:');
    press(ENTER);                                     // 1. View tools
    expect(screenText()).toContain('Tools of notion');
    press(DOWN, ENTER);
    expect(screenText()).toContain('notion-delete-pages');
    expect(screenText()).toContain('Delete pages');
    press(ESC, ESC, ESC);                             // tool -> tools -> server -> servers
    expect(screenText()).toContain('MCP servers');
    press(ESC);
    await done;
    expect(input.listenerCount('data')).toBe(0);
    expect(input.isRaw).toBe(false);
  });

  it('runs the actions of a server: reconnect, disable, enable, reload', async () => {
    const done = runMcpPanel(controller(statuses), { io: { input: input as any, output } });
    press(ENTER, DOWN, ENTER);                        // notion -> Reconnect
    await tick();
    press('3');                                       // Disable by number
    await tick();
    press(ESC, 'r');                                  // back to the list, reload all
    await tick();
    expect(calls).toEqual(['reconnect:notion', 'disable:notion', 'reload']);
    press('q');
    await done;
  });

  it('shows "connecting" while an action runs and ignores keys until it is done', async () => {
    let release!: () => void;
    const slow = { ...controller(statuses), reconnect: () => new Promise<void>((r) => { release = r; }) };
    const done = runMcpPanel(slow, { io: { input: input as any, output } });
    press(ENTER, 'r');
    expect(screenText()).toContain('connecting…');
    press(ESC);                                       // ignored: busy
    expect(screenText()).toContain('Status:');
    release();
    await tick();
    expect(screenText()).not.toContain('connecting…');
    press('\x03');
    await done;
  });

  it('opens the focused server of the ordered list (user servers first)', async () => {
    const done = runMcpPanel(controller(statuses), { io: { input: input as any, output } });
    press(DOWN, DOWN, DOWN, ENTER);                   // 4th row of the ordered list: the project server
    expect(screenText()).toContain('mine-local');
    press('\x03');
    await done;
  });
});
