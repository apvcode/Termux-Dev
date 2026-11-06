import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import fs from 'fs/promises';
import os from 'os';
import path from 'path';
import { copyToClipboard, osc52Sequence } from '../../src/cli/clipboard-write.js';
import { MEMORY_PROMPT_MAX_CHARS, memoryForPrompt } from '../../src/core/memory.js';
import { PLAN_FRESH_MS, loadPlanForPrompt, planToMarkdown, savePlanFile } from '../../src/core/planfile.js';
import { buildSystemPrompt } from '../../src/prompts/builder.js';

describe('copyToClipboard', () => {
  const ctx = (platform: NodeJS.Platform, env: NodeJS.ProcessEnv, works: string[]) => {
    const tried: string[] = [];
    const out: string[] = [];
    return {
      tried, out,
      ctx: { platform, env, write: (t: string) => out.push(t), run: async (cmd: string, _a: string[], input: string) => { tried.push(cmd + ':' + input); return works.includes(cmd); } },
    };
  };

  it('uses wl-copy on Wayland, xclip then xsel on X11, pbcopy on macOS, termux-clipboard-set on Termux', async () => {
    let c = ctx('linux', { WAYLAND_DISPLAY: 'wayland-0' }, ['wl-copy']);
    expect(await copyToClipboard('hello', c.ctx)).toBe('wl-copy');
    expect(c.tried).toEqual(['wl-copy:hello']);
    c = ctx('linux', {}, ['xsel']);
    expect(await copyToClipboard('hi', c.ctx)).toBe('xsel');
    expect(c.tried.map((t) => t.split(':')[0])).toEqual(['xclip', 'xsel']);
    c = ctx('darwin', {}, ['pbcopy']);
    expect(await copyToClipboard('mac', c.ctx)).toBe('pbcopy');
    c = ctx('linux', { TERMUX_VERSION: '1' }, ['termux-clipboard-set']);
    expect(await copyToClipboard('t', c.ctx)).toBe('termux');
  });

  it('falls back to the terminal (OSC 52) when no tool works, and says when nothing can be done', async () => {
    const c = ctx('linux', {}, []);
    expect(await copyToClipboard('привет мир', c.ctx)).toBe('osc52');
    expect(c.out[0]).toBe(osc52Sequence('привет мир'));
    expect(Buffer.from(c.out[0].slice('\x1b]52;c;'.length, -1), 'base64').toString('utf8')).toBe('привет мир');
    const big = ctx('linux', {}, []);
    expect(await copyToClipboard('x'.repeat(200000), big.ctx)).toBe('none');
    expect(big.out).toEqual([]);
  });

  it('sends the whole text, every character', async () => {
    const text = '# Title\n\n**bold** `code`\n\n' + 'line\n'.repeat(500) + 'конец';
    const c = ctx('linux', { WAYLAND_DISPLAY: 'w' }, ['wl-copy']);
    await copyToClipboard(text, c.ctx);
    expect(c.tried[0]).toBe('wl-copy:' + text);
  });
});

describe('memoryForPrompt', () => {
  const header = '# Project Memory Bank\n\n## Established Rules & Architecture';
  it('gives a small memory whole', () => {
    const text = `${header}\n• one\n• two\n`;
    expect(memoryForPrompt(text)).toEqual({ text, omitted: 0 });
  });

  it('keeps the newest facts that fit and says how many were left out', () => {
    const facts = Array.from({ length: 200 }, (_, i) => `• fact number ${i} ${'x'.repeat(40)}`);
    const text = `${header}\n${facts.join('\n')}\n`;
    const r = memoryForPrompt(text, 1500);
    expect(r.text.length).toBeLessThanOrEqual(1500);
    expect(r.omitted).toBeGreaterThan(100);
    expect(r.text).toContain('fact number 199');
    expect(r.text).not.toContain('fact number 0 ');
    expect(r.text).toContain(`${r.omitted} older facts left out`);
    expect(r.text.startsWith('# Project Memory Bank')).toBe(true);
    expect(MEMORY_PROMPT_MAX_CHARS).toBeGreaterThan(1000);
  });

  it('always keeps at least the newest fact, however long', () => {
    const r = memoryForPrompt(`${header}\n• old\n• ${'y'.repeat(5000)}\n`, 500);
    expect(r.text).toContain('yyyy');
    expect(r.omitted).toBe(1);
  });
});

describe('the approved plan file', () => {
  let dir: string;
  const original = process.cwd();
  beforeEach(async () => { dir = await fs.mkdtemp(path.join(os.tmpdir(), 'devx-plan-')); });
  afterEach(async () => { process.chdir(original); await fs.rm(dir, { recursive: true, force: true }); });

  it('is written as markdown with the summary, the files and the steps', async () => {
    const file = await savePlanFile(dir, { summary: 'Fix the counter', files: ['src/a.ts'], steps: ['read it', 'fix it'] }, new Date('2026-11-06T04:40:00Z'));
    expect(file).toBe(path.join(dir, '.devx', 'plan.md'));
    const text = await fs.readFile(file, 'utf8');
    expect(text).toBe('# Plan\n\n_Approved 2026-11-06 04:40_\n\nFix the counter\n\n## Files\n\n- src/a.ts\n\n## Steps\n\n1. read it\n2. fix it\n');
    expect(planToMarkdown({ summary: 'Only this' }, new Date('2026-01-01T00:00:00Z'))).toContain('Only this');
  });

  it('a plan the model wrote as text is kept as it is', async () => {
    await savePlanFile(dir, '**Цель и архитектурный обзор**\nsomething', new Date('2026-11-06T04:40:00Z'));
    expect(await fs.readFile(path.join(dir, '.devx', 'plan.md'), 'utf8')).toContain('**Цель и архитектурный обзор**\nsomething');
  });

  it('goes into the prompt of AGENT mode while it is recent, never into PLAN mode', async () => {
    await savePlanFile(dir, { summary: 'Rebuild the combat system', steps: ['step one'] });
    process.chdir(dir);
    const agent = await buildSystemPrompt(false);
    expect(agent).toContain('Approved plan (.devx/plan.md)');
    expect(agent).toContain('Rebuild the combat system');
    expect(agent).toContain('if the user now asks for something else, ignore it');
    expect(await buildSystemPrompt(true)).not.toContain('Rebuild the combat system');
  });

  it('an old plan is left out, a long one is cut', async () => {
    await savePlanFile(dir, { summary: 'Old plan' });
    expect(loadPlanForPrompt(dir, Date.now() + PLAN_FRESH_MS + 60000)).toBe('');
    expect(loadPlanForPrompt(dir)).toContain('Old plan');
    await savePlanFile(dir, { summary: 'Big', steps: Array.from({ length: 300 }, (_, i) => `step ${i} ${'z'.repeat(30)}`) });
    const cut = loadPlanForPrompt(dir);
    expect(cut.length).toBeLessThan(3200);
    expect(cut).toContain('the rest is in .devx/plan.md');
    expect(loadPlanForPrompt(path.join(dir, 'nowhere'))).toBe('');
  });
});
