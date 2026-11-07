import { afterEach, describe, expect, it } from 'vitest';
import os from 'os';
import { BackgroundManager } from '../../src/core/background.js';
import { backgroundSummary, noteRows, renderTaskList, renderTaskOutput, runTasksCommand } from '../../src/cli/background-ui.js';
import { backgroundBadge, buildPromptFrame, summarizeToolResult, COMPACT_COLS } from '../../src/cli/ui.js';
import { displayWidth, stripAnsi } from '../../src/cli/width.js';

const managers: BackgroundManager[] = [];
const make = () => { const m = new BackgroundManager(); managers.push(m); return m; };
afterEach(async () => { await Promise.all(managers.splice(0).map((m) => m.stopAll())); });

const node = (script: string) => `"${process.execPath}" -e "eval(Buffer.from('${Buffer.from(script).toString('base64')}','base64').toString())"`;
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
async function until(check: () => boolean, ms = 4000) {
  const end = Date.now() + ms;
  while (Date.now() < end) { if (check()) return; await sleep(20); }
  throw new Error('condition not reached');
}
const plain = (rows: string[]) => rows.map(stripAnsi);

describe('background badge', () => {
  it('is empty when nothing runs, counts tasks, and shows failures', () => {
    expect(backgroundBadge(undefined)).toBe('');
    expect(backgroundBadge({ running: 0 })).toBe('');
    expect(stripAnsi(backgroundBadge({ running: 2 }))).toBe('◈ 2 bg');
    expect(stripAnsi(backgroundBadge({ running: 1, failed: 1 }))).toBe('◈ 1 bg · 1 failed');
    expect(stripAnsi(backgroundBadge({ running: 0, failed: 2 }))).toBe('◈ 0 bg · 2 failed');
    expect(stripAnsi(backgroundBadge({ running: 3 }, true))).toBe('◈3');
  });
});

describe('prompt frame with background tasks', () => {
  const base = {
    mode: 'agent' as const, model: 'kimi-k2', cwd: os.homedir() + '/Projects/app', branch: 'main', dirty: false,
    contextPercent: 10, cost: 0.01, input: '', cursorPos: 0, placeholder: 'Ask',
  };

  it('shows the count in the bottom border and keeps the borders aligned', () => {
    for (const cols of [60, 80, 120]) {
      const lines = plain(buildPromptFrame({ ...base, cols, background: { running: 2 } }).lines);
      expect(lines[2]).toContain('◈ 2 bg');
      expect(displayWidth(lines[0])).toBe(displayWidth(lines[2]));
    }
  });

  it('shows nothing without tasks', () => {
    expect(plain(buildPromptFrame({ ...base, cols: 100 }).lines)[2]).not.toContain('bg');
    expect(plain(buildPromptFrame({ ...base, cols: 100, background: { running: 0 } }).lines)[2]).not.toContain('bg');
  });

  it('fits a phone: the compact row carries a short count', () => {
    const cols = COMPACT_COLS - 1;
    const lines = plain(buildPromptFrame({ ...base, cols, background: { running: 2 } }).lines);
    expect(lines[0]).toContain('◈2');
    expect(displayWidth(lines[0])).toBeLessThanOrEqual(cols);
  });
});

describe('/tasks', () => {
  it('says so when there are none', async () => {
    const rows = plain(await runTasksCommand([], make(), 80));
    expect(rows.join('\n')).toContain('No background tasks');
  });

  it('lists tasks with state, age and the last output line', async () => {
    const m = make();
    m.start(node("console.log('listening on 3000'); setInterval(()=>{},1000)"), { label: 'dev server' });
    m.start(node('process.exit(2)'), { label: 'broken build' });
    await until(() => m.get('bg2')!.status !== 'running' && m.read('bg1', { mode: 'tail' }).text.includes('listening'));
    const text = plain(renderTaskList(m, 80)).join('\n');
    expect(text).toContain('1 running, 1 ended');
    expect(text).toMatch(/bg1\s+● running\s+\d+s\s+dev server/);
    expect(text).toContain('listening on 3000');
    expect(text).toMatch(/bg2\s+✗ failed 2/);
  });

  it('shows a failure in the frame until the model has been told, then drops it', async () => {
    const m = make();
    m.start(node('process.exit(1)'));
    await until(() => m.get('bg1')!.status === 'failed');
    // Looking at the list does not count as telling the model.
    await runTasksCommand([], m, 80);
    expect(backgroundSummary(m)).toEqual({ running: 0, failed: 1 });
    const changes: number[] = [];
    m.on('change', () => changes.push(backgroundSummary(m).failed));
    expect(m.takeNotes()).toContain('bg1');
    expect(backgroundSummary(m)).toEqual({ running: 0, failed: 0 });
    // The frame is told to redraw after the news went out, and sees the failure gone.
    expect(changes).toEqual([0]);
    // The task itself is still listed as failed.
    expect(m.get('bg1')!.status).toBe('failed');
  });

  it('counts only tasks that failed: a task that ended well or was stopped does not light the frame', async () => {
    const m = make();
    m.start(node('process.exit(0)'));
    m.start(node('setInterval(()=>{},1000)'));
    await until(() => m.get('bg1')!.status === 'exited');
    await m.stop('bg2');
    expect(backgroundSummary(m).failed).toBe(0);
  });

  it('shows the output of one task, by id or with "output"', async () => {
    const m = make();
    m.start(node("console.log('line A'); console.log('line B'); setInterval(()=>{},1000)"));
    await until(() => m.read('bg1', { mode: 'tail' }).text.includes('line B'));
    for (const args of [['bg1'], ['output', 'bg1']]) {
      const text = plain(await runTasksCommand(args, m, 80)).join('\n');
      expect(text).toContain('line A');
      expect(text).toContain('line B');
    }
    expect(plain(renderTaskOutput(m, 'bg9', 80)).join('')).toContain('No task "bg9"');
  });

  it('stops one task or all of them, and clears ended ones', async () => {
    const m = make();
    m.start(node('setInterval(()=>{},1000)'));
    m.start(node('setInterval(()=>{},1000)'));
    m.start(node('process.exit(0)'));
    await until(() => m.get('bg3')!.status !== 'running');
    expect(plain(await runTasksCommand(['stop', 'bg1'], m, 80)).join('')).toContain('bg1 stopped');
    expect(plain(await runTasksCommand(['stop', 'all'], m, 80)).join('')).toContain('Stopped 1 task');
    expect(plain(await runTasksCommand(['clear'], m, 80)).join('')).toContain('Removed 3 ended tasks');
    expect(plain(await runTasksCommand(['stop', 'nope'], m, 80)).join('')).toContain('No background task');
    expect(plain(await runTasksCommand(['stop'], m, 80)).join('')).toContain('Usage');
  });

  it('keeps every row inside a narrow terminal', async () => {
    const m = make();
    m.start(node("console.log('x'.repeat(200)); setInterval(()=>{},1000)"), { label: 'a very long label '.repeat(8) });
    await until(() => m.read('bg1', { mode: 'tail' }).text.length > 100);
    for (const row of plain(renderTaskList(m, 40))) expect(displayWidth(row)).toBeLessThanOrEqual(40);
  });
});

describe('notes on the screen', () => {
  it('shows the first line of each note', () => {
    const content = '[devx · background tasks]\nbg1 "server" failed (exit code 1) after 3s.\nLast output:\nboom\n\nbg2 "watch" printed 1 line matching /error/:\n  error in x';
    expect(noteRows(content)).toEqual(['bg1 "server" failed (exit code 1) after 3s.', 'bg2 "watch" printed 1 line matching /error/:']);
  });
});

describe('tool result under the tool line', () => {
  const rows = (text: string, expanded = false) => plain(summarizeToolResult('background', text, 80, expanded));

  it('folds the output under the first line', () => {
    const out = rows('Started bg1 (pid 7): dev server. It runs in the background.\nYou will be told...\nline 1\nline 2');
    expect(out[0]).toContain('Started bg1');
    expect(out[1]).toContain('+3 lines');
  });

  it('shows everything once, expanded', () => {
    const out = rows('Started bg1 (pid 7): dev.\nline 1\nline 2', true);
    expect(out.filter((l) => l.includes('Started bg1'))).toHaveLength(1);
    expect(out.join('\n')).toContain('line 2');
  });

  it('marks failures', () => {
    expect(rows('Error: No background task "bg9" (none started)')[0]).toContain('No background task');
    expect(rows('bg1  failed (exit 1)  1s  x - it ended right after the start.\nboom')[0]).toContain('failed');
  });
});
