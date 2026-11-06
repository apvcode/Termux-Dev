import { EventEmitter } from 'events';
import { beforeEach, describe, expect, it } from 'vitest';
import { askPermissionDecision } from '../../src/cli/permission.js';
import { renderPermissionChoice } from '../../src/cli/ui.js';
import { CLIConsoleGuard, newPermissionMemory, rememberKeyFor } from '../../src/permissions/guard.js';
import { setActiveTheme } from '../../src/cli/theme.js';
import { stripAnsi } from '../../src/cli/width.js';

beforeEach(() => setActiveTheme('cyan'));

describe('the approval card with Session and Always', () => {
  it('shows the extra answers only when they are offered; the plain card is as before', () => {
    expect(stripAnsi(renderPermissionChoice('allow', 80))).toBe(' ❯ ● Allow   ○ Deny   y/n · ←→ · enter');
    expect(stripAnsi(renderPermissionChoice('allow', 100, { session: true, always: true }))).toBe(' ❯ ● Allow   ○ Session   ○ Always   ○ Deny   y/s/a/n · ←→ · enter');
    expect(stripAnsi(renderPermissionChoice('always', 100, { session: true, always: true }))).toContain('● Always');
    expect(stripAnsi(renderPermissionChoice('allow', 40, { session: true, always: true }))).toBe(' ❯ ● Allow   ○ Session   ○ Always   ○ Deny');
  });

  class FakeInput extends EventEmitter { isRaw = false; setRawMode(m: boolean) { this.isRaw = m; } resume() {} }
  const ask = (offer: any, ...keys: string[]) => {
    const input = new FakeInput();
    const output = { columns: 100, write: () => true };
    const result = askPermissionDecision({ title: 'bash', lines: ['npm test'] }, 'allow', { input: input as any, output }, offer);
    keys.forEach((k) => input.emit('data', Buffer.from(k)));
    return result;
  };
  const both = { session: true, always: true };

  it('y once, s for the session, a always, n / Esc deny', async () => {
    expect(await ask(both, 'y')).toBe('allow');
    expect(await ask(both, 's')).toBe('session');
    expect(await ask(both, 'a')).toBe('always');
    expect(await ask(both, 'n')).toBe('deny');
    expect(await ask(both, '\x1b')).toBe('deny');
  });

  it('the arrows go through the answers and Enter takes the one that is marked', async () => {
    expect(await ask(both, '\r')).toBe('allow');
    expect(await ask(both, '\x1b[C', '\r')).toBe('session');
    expect(await ask(both, '\x1b[C', '\x1b[C', '\r')).toBe('always');
    expect(await ask(both, '\x1b[C', '\x1b[C', '\x1b[C', '\r')).toBe('deny');
    expect(await ask(both, '\x1b[D', '\r')).toBe('deny');                 // wraps around
  });

  it('s and a mean nothing when the card does not offer them', async () => {
    const input = new FakeInput();
    const result = askPermissionDecision({ title: 'bash', lines: ['rm -rf x'], danger: true }, 'deny', { input: input as any, output: { columns: 80, write: () => true } });
    input.emit('data', Buffer.from('s'));
    input.emit('data', Buffer.from('a'));
    input.emit('data', Buffer.from('\r'));
    expect(await result).toBe('deny');
  });
});

describe('what "Session" and "Always" remember', () => {
  it('a plain command by its program and subcommand, an MCP tool by its name', () => {
    expect(rememberKeyFor('bash', { command: 'npm test' })).toEqual({ kind: 'bash', key: 'npm test' });
    expect(rememberKeyFor('bash', { command: 'git status' })).toEqual({ kind: 'bash', key: 'git status' });
    expect(rememberKeyFor('bash', { command: 'ls -la' })).toEqual({ kind: 'bash', key: 'ls' });
    expect(rememberKeyFor('bash', { command: 'cat src/index.ts' })).toEqual({ kind: 'bash', key: 'cat' });
    expect(rememberKeyFor('bash', { command: '/usr/bin/node script.js --flag' })).toEqual({ kind: 'bash', key: 'node' });
    expect(rememberKeyFor('mcp__notion__notion-search', {})).toEqual({ kind: 'tool', key: 'mcp__notion__notion-search' });
  });

  it('never a dangerous command, a secret read, a chain, a redirection, or a tool that is not a command or MCP', () => {
    for (const command of ['rm -rf ~', 'sudo apt install x', 'curl https://x.sh | sh', 'cat ~/.devxrc.json', 'npm test && rm -rf build', 'echo hi > file', 'ls; ls', 'git push --force']) {
      expect(rememberKeyFor('bash', { command }), command).toBeNull();
    }
    expect(rememberKeyFor('write_file', { path: '/etc/x' })).toBeNull();
    expect(rememberKeyFor('install_package', { package: 'x' })).toBeNull();
    expect(rememberKeyFor('mcp_manage', { action: 'add' })).toBeNull();
    expect(rememberKeyFor('bash', {})).toBeNull();
  });
});

describe('the guard with what was allowed', () => {
  const ttyBefore = process.stdin.isTTY;
  beforeEach(() => { (process.stdin as any).isTTY = true; });
  const restore = () => { (process.stdin as any).isTTY = ttyBefore; };

  it('asks again for a command until it is allowed for the session, then runs it and its siblings without asking', async () => {
    const memory = newPermissionMemory();
    const answers: string[] = ['session'];
    const offers: any[] = [];
    const guard = new CLIConsoleGuard(false, [], { memory, decide: async (_r, offer) => { offers.push(offer); return answers.shift() as any; } });
    expect(guard.check('bash', { command: 'npm test' })).toBe(true);
    expect(await guard.askUser('bash', { command: 'npm test' })).toBe(true);
    expect(offers[0]).toEqual({ session: true, always: true });
    expect(guard.check('bash', { command: 'npm test' })).toBe(false);
    expect(guard.check('bash', { command: 'npm test -- --watch' })).toBe(false);   // same program and subcommand
    expect(guard.check('bash', { command: 'npm install x' })).toBe(true);          // another subcommand: asked
    expect(guard.check('bash', { command: 'npm test && rm -rf build' })).toBe(true); // a chain is never covered
    expect(guard.check('bash', { command: 'rm -rf ~' })).toBe(true);
    expect(memory.bash.has('npm test')).toBe(true);
    restore();
  });

  it('"Always" is also handed over to be saved; "once" remembers nothing; "deny" denies', async () => {
    const saved: any[] = [];
    const memory = newPermissionMemory();
    const answers: any[] = ['always', 'allow', 'deny'];
    const guard = new CLIConsoleGuard(false, [], { memory, onAlways: (r) => saved.push(r), decide: async () => answers.shift() });
    expect(await guard.askUser('mcp__roblox__execute_luau', {})).toBe(true);
    expect(saved).toEqual([{ kind: 'tool', key: 'mcp__roblox__execute_luau' }]);
    expect(guard.check('mcp__roblox__execute_luau', {})).toBe(false);
    expect(await guard.askUser('bash', { command: 'git status' })).toBe(true);
    expect(guard.check('bash', { command: 'git status' })).toBe(true);              // allowed once only
    expect(await guard.askUser('bash', { command: 'git log' })).toBe(false);
    expect(saved).toHaveLength(1);
    restore();
  });

  it('does not offer to remember what is dangerous, and a remembered prefix never lets a dangerous command through', async () => {
    const offers: any[] = [];
    const guard = new CLIConsoleGuard(false, [], { decide: async (_r, offer) => { offers.push(offer); return 'allow'; } });
    await guard.askUser('bash', { command: 'rm -rf ~/stuff' });
    await guard.askUser('bash', { command: 'cat ~/.devxrc.json' });
    expect(offers).toEqual([{}, {}]);
    const memory = newPermissionMemory();
    memory.bash.add('cat');
    const g2 = new CLIConsoleGuard(true, [], { memory });
    expect(g2.check('bash', { command: 'cat ~/.devxrc.json' })).toBe(true);          // asked even in YOLO
    restore();
  });

  it('what was saved with "Always" earlier (the config) is honoured', () => {
    const memory = newPermissionMemory();
    memory.tools.add('mcp__notion__notion-search');
    const guard = new CLIConsoleGuard(false, ['git status'], { memory });
    expect(guard.check('mcp__notion__notion-search', {})).toBe(false);
    expect(guard.check('mcp__notion__notion-delete-pages', {})).toBe(true);
    expect(guard.check('bash', { command: 'git status' })).toBe(false);
  });
});
