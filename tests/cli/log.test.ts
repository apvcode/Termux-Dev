import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { log, note } from '../../src/cli/log.js';
import { getActionDescription } from '../../src/core/loop.js';
import { setActiveTheme } from '../../src/cli/theme.js';
import { stripAnsi } from '../../src/cli/width.js';

let out: string[];
beforeEach(() => {
  setActiveTheme('cyan');
  out = [];
  vi.spyOn(console, 'log').mockImplementation(((...a: any[]) => { out.push(a.join(' ')); }) as any);
});
afterEach(() => vi.restoreAllMocks());

describe('log', () => {
  it('prints one-line notices in the devx style, with no box drawing bars', () => {
    log.info('hello');
    log.success('done');
    log.warn('No saved sessions found.');
    log.error('broken');
    expect(out.map(stripAnsi)).toEqual(['  · hello', '  ✓ done', '  ! No saved sessions found.', '  ✗ broken']);
    expect(out.join('')).not.toMatch(/[│▲◆◇]/);
  });

  it('drops colours of the message: the kind decides', () => {
    log.warn('\x1b[31mred\x1b[39m words');
    expect(stripAnsi(out[0])).toBe('  ! red words');
    expect(out[0]).not.toContain('\x1b[31m');
  });

  it('a titled note keeps its rows', () => {
    note('/plan - one\n/agent - two', 'Commands');
    const text = out.map(stripAnsi).join('\n');
    expect(text).toContain('Commands');
    expect(text).toContain('    /plan - one');
    expect(text).toContain('    /agent - two');
    expect(text).not.toMatch(/[│▲◆◇]/);
  });
});

describe('the line of an MCP call', () => {
  it('names the server, the tool and the arguments', () => {
    expect(getActionDescription('mcp__notion__notion-search', { query: 'plans' })).toBe('→ MCP: notion · notion-search {"query":"plans"}');
    expect(getActionDescription('mcp__roblox-studio__execute_luau', {})).toBe('→ MCP: roblox-studio · execute_luau');
    expect(getActionDescription('mcp__a__b', { text: 'x'.repeat(300) }).length).toBeLessThan(160);
  });
});
