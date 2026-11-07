import { EventEmitter } from 'events';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderPlanCard } from '../../src/cli/ui.js';
import { toolResultRows } from '../../src/cli/transcript.js';
import { askPlanDecision } from '../../src/cli/confirm.js';
import { planReadyTool, lastPlanReady, resetPlanReady } from '../../src/tools/plan.js';
import { buildSystemPrompt } from '../../src/prompts/builder.js';
import { setActiveTheme } from '../../src/cli/theme.js';
import { setLanguage } from '../../src/core/i18n.js';
import { displayWidth, stripAnsi } from '../../src/cli/width.js';

beforeEach(() => { setActiveTheme('cyan'); setLanguage('en'); });

describe('the plan card', () => {
  it('a small plan is a few rows', () => {
    const rows = renderPlanCard({ summary: 'Fix the score counter', files: ['src/score.ts'], steps: ['read the counter', 'reset it on death'] }, 80).map(stripAnsi);
    expect(rows.length).toBeLessThanOrEqual(9);
    expect(rows[0]).toMatch(/^ {4}╭─ Plan ─+╮$/);
    expect(rows.join('\n')).toContain('Fix the score counter');
    expect(rows.join('\n')).toMatch(/Files\s+src\/score\.ts/);
    expect(rows.join('\n')).toContain('1. read the counter');
    expect(rows.join('\n')).toContain('2. reset it on death');
    expect(rows[rows.length - 1]).toMatch(/^ {4}╰─+╯$/);
  });

  it('a big plan grows with its steps and wraps long ones; a plan without files or steps is just the summary', () => {
    const steps = Array.from({ length: 12 }, (_, i) => `step number ${i + 1} ${'with a long description '.repeat(4)}`);
    const big = renderPlanCard({ summary: 'Rebuild the whole combat system', files: Array.from({ length: 20 }, (_, i) => `src/f${i}.ts`), steps }, 80).map(stripAnsi);
    expect(big.length).toBeGreaterThan(25);
    expect(big.join('\n')).toContain('12. step number 12');
    expect(big.join('\n')).toMatch(/\+12/);       // files beyond the first 8
    const only = renderPlanCard({ summary: 'Just a summary' }, 80).map(stripAnsi);
    expect(only).toHaveLength(3);
  });

  it('every row has the same width and fits the terminal, in any language', () => {
    for (const lang of ['en', 'ru', 'zh', 'de']) {
      setLanguage(lang);
      for (const cols of [100, 60, 40]) {
        const rows = renderPlanCard({ summary: 'Исправить счётчик очков и сохранение', files: ['a.ts', 'b.ts'], steps: ['первый шаг подлиннее чтобы он перенёсся на вторую строку', 'второй'] }, cols).map(stripAnsi);
        expect(new Set(rows.map((r) => displayWidth(r))).size, `${lang} ${cols}`).toBe(1);
        expect(displayWidth(rows[0])).toBeLessThanOrEqual(cols - 1);
      }
    }
  });
});

describe('plan_ready', () => {
  it('stores the plan and answers in JSON that devx draws as the card', async () => {
    resetPlanReady();
    const out = await planReadyTool.execute({ summary: 'Fix it', files: ['a.ts'], steps: ['one'] }, {} as any, {} as any);
    expect(JSON.parse(out)).toMatchObject({ type: 'plan_ready', summary: 'Fix it', files: ['a.ts'], steps: ['one'] });
    expect(lastPlanReady?.summary).toBe('Fix it');
    const rows = toolResultRows('plan_ready', out, { cols: 80, expanded: false }).map(stripAnsi);
    expect(rows[0]).toContain('╭─ Plan');
    expect(rows.join('\n')).toContain('1. one');
    resetPlanReady();
  });
});

describe('the plan prompt', () => {
  it('asks for a small plan for a small task and the full one for a big task, and for the approval devx shows', async () => {
    const prompt = await buildSystemPrompt(true);
    expect(prompt).toContain('Size the plan to the task');
    expect(prompt).toMatch(/SMALL task[^.]*no headings/);
    expect(prompt).toContain('LARGER task');
    expect(prompt).toContain('Цель и архитектурный обзор');
    expect(prompt).toContain("do not ask for approval yourself");
    expect(prompt).toContain("Do not use 'todo_list' in PLAN mode");
    expect(await buildSystemPrompt(false)).not.toContain('Size the plan to the task');
  });
});

describe('asking what to do with the plan', () => {
  class FakeStdin extends EventEmitter { isTTY = true; isRaw = false; setRawMode(m: boolean) { this.isRaw = m; return this; } resume() { return this; } }
  const real = process.stdin;
  let out: string[];
  beforeEach(() => {
    out = [];
    Object.defineProperty(process, 'stdin', { value: new FakeStdin(), configurable: true });
    vi.spyOn(process.stdout, 'write').mockImplementation(((c: any) => { out.push(String(c)); return true; }) as any);
    vi.spyOn(console, 'log').mockImplementation(((...a: any[]) => { out.push(a.join(' ') + '\n'); }) as any);
  });
  afterEach(() => { vi.restoreAllMocks(); Object.defineProperty(process, 'stdin', { value: real, configurable: true }); });
  const press = (k: string) => process.stdin.emit('data', Buffer.from(k));
  const text = () => stripAnsi(out.join(''));

  it('Enter starts the work (Go is the first choice)', async () => {
    const done = askPlanDecision();
    expect(text()).toContain('The plan is ready. Start working on it?');
    expect(text()).toContain('Go: switch to AGENT mode and start');
    expect(text()).toContain('Change the plan (stay in PLAN mode)');
    press('\r');
    expect(await done).toBe('go');
  });

  it('the second choice or Esc keeps the plan in PLAN mode', async () => {
    let done = askPlanDecision();
    press('\x1b[B');
    press('\r');
    expect(await done).toBe('edit');
    done = askPlanDecision();
    press('\x1b');
    expect(await done).toBe('edit');
  });
});

describe('plan_ready with arguments in the wrong shape', () => {
  it('steps and files sent as text still give a plan card', async () => {
    const args = { summary: 'Patch the check', files: 'a.smali', steps: '1. read it\n2. patch it' };
    planReadyTool.validateArgs(args);
    const rows = toolResultRows('plan_ready', await planReadyTool.execute(args, {} as any, {} as any), { cols: 80, expanded: false }).map(stripAnsi).join('\n');
    expect(rows).toContain('Patch the check');
    expect(rows).toContain('1. read it');
    expect(rows).toContain('2. patch it');
    expect(rows).toMatch(/Files\s+a\.smali/);
  });

  it('a call without a summary is refused, so the model calls it again', () => {
    expect(() => planReadyTool.validateArgs({ plan: 'text' })).toThrow(/summary/);
    expect(() => planReadyTool.validateArgs({ summary: '  ' })).toThrow(/summary/);
  });
});
