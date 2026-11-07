import { describe, expect, it } from 'vitest';
import os from 'os';
import { buildPromptFrame, inputCells, maxInputRows, moveCursorRow, wrapInput, COMPACT_COLS } from '../../src/cli/ui.js';
import { displayWidth, stripAnsi } from '../../src/cli/width.js';

const text = (input: string, rows: Array<{ start: number; end: number }>) => {
  const cps = Array.from(input);
  return rows.map((r) => cps.slice(r.start, r.end).join(''));
};

describe('wrapInput', () => {
  it('keeps a short text on one row, and an empty one too', () => {
    expect(wrapInput('hello', 20)).toEqual([{ start: 0, end: 5 }]);
    expect(wrapInput('', 20)).toEqual([{ start: 0, end: 0 }]);
  });

  it('breaks between words and loses no character', () => {
    const input = 'one two three four five six seven';
    const rows = text(input, wrapInput(input, 12));
    expect(rows.join('')).toBe(input);
    for (const r of rows) expect(r.length).toBeLessThanOrEqual(12);
    expect(rows[0]).toBe('one two ');
  });

  it('cuts a word longer than the row', () => {
    expect(text('x'.repeat(25), wrapInput('x'.repeat(25), 10))).toEqual(['x'.repeat(10), 'x'.repeat(10), 'x'.repeat(5)]);
  });

  it('counts wide characters as two cells', () => {
    const input = '日本語日本語日本語';
    const rows = text(input, wrapInput(input, 8));
    expect(rows.every((r) => displayWidth(r) <= 8)).toBe(true);
    expect(rows.join('')).toBe(input);
  });

  it('never splits an attachment tag that fits on a row', () => {
    const input = 'before text here [Pasted text #1 +22 lines] after';
    const rows = text(input, wrapInput(input, 30));
    expect(rows.join('')).toBe(input);
    expect(rows.some((r) => r.includes('[Pasted text #1 +22 lines]'))).toBe(true);
  });
});

describe('moveCursorRow', () => {
  const cols = 40;
  const cells = inputCells(cols);
  const input = 'a'.repeat(cells * 3);

  it('moves between the rows of a wrapped text, keeping the column', () => {
    expect(moveCursorRow(input, cells * 2 + 5, cols, -1)).toBe(cells + 5);
    expect(moveCursorRow(input, cells + 5, cols, -1)).toBe(5);
    expect(moveCursorRow(input, 5, cols, 1)).toBe(cells + 5);
  });

  it('says there is no row above the first or under the last (history takes over)', () => {
    expect(moveCursorRow(input, 3, cols, -1)).toBeNull();
    expect(moveCursorRow(input, input.length, cols, 1)).toBeNull();
    expect(moveCursorRow('short', 2, cols, -1)).toBeNull();
    expect(moveCursorRow('short', 2, cols, 1)).toBeNull();
  });

  it('goes to the end of a shorter row', () => {
    const short = 'word '.repeat(12) + 'x';
    const to = moveCursorRow(short, short.length, cols, -1)!;
    expect(to).toBeLessThan(short.length);
    expect(to).toBeGreaterThan(0);
  });
});

describe('prompt frame with a long text', () => {
  const base = {
    mode: 'agent' as const, model: 'glm-5.3-flash', cwd: os.homedir() + '/Projects/WatchLink', branch: 'main', dirty: false,
    contextPercent: 9, cost: 1.45, placeholder: 'Ask',
  };
  const long = 'Типо напиши что мы вообще обновили, ну и что бы были прикреплены файлы к релизу, версия та же, просто что мы доабвили '.repeat(3);

  it('grows by rows instead of scrolling sideways; every row stays inside the borders', () => {
    const cols = 100;
    const frame = buildPromptFrame({ ...base, cols, input: long, cursorPos: long.length });
    const lines = frame.lines.map(stripAnsi);
    const bottomAt = lines.findIndex((l) => l.startsWith('╰'));
    expect(bottomAt).toBeGreaterThan(2);                        // top, several text rows, bottom
    const shown = lines.slice(1, bottomAt).map((l) => l.slice(4, -2).trimEnd());
    expect(shown.join(' ').replace(/\s+/g, ' ')).toContain(long.slice(-60).trim());
    expect(shown.join('')).not.toContain('…');                  // nothing is cut off
    expect(lines[1].startsWith('│ ❯ ')).toBe(true);
    expect(lines[2].startsWith('│   ')).toBe(true);              // continuation rows have no marker
    for (const l of lines.slice(0, bottomAt + 1)) expect(displayWidth(l)).toBe(cols - 1);
  });

  it('puts the cursor on the row and column of the cursor position', () => {
    const cols = 80;
    const cells = inputCells(cols);
    const input = 'a'.repeat(cells * 2 + 3);
    const frame = buildPromptFrame({ ...base, cols, input, cursorPos: input.length });
    expect(frame.cursorRow).toBe(3);
    expect(frame.cursorCol).toBe(4 + 3);
    const middle = buildPromptFrame({ ...base, cols, input, cursorPos: cells + 2 });
    expect(middle.cursorRow).toBe(2);
    expect(middle.cursorCol).toBe(4 + 2);
  });

  it('stops growing at the limit and scrolls so that the cursor row is shown', () => {
    const cols = 60;
    const input = Array.from({ length: 60 }, (_, i) => `line${i}`).join(' ');
    const full = buildPromptFrame({ ...base, cols, input, cursorPos: input.length, maxInputRows: 4 });
    const lines = full.lines.map(stripAnsi);
    const bottomAt = lines.findIndex((l) => l.startsWith('╰'));
    expect(bottomAt - 1).toBe(4);
    expect(lines[1].startsWith('│ … ')).toBe(true);              // text is hidden above
    expect(lines[4]).toContain('line59');
    expect(full.cursorRow).toBe(4);
    const top = buildPromptFrame({ ...base, cols, input, cursorPos: 0, maxInputRows: 4 }).lines.map(stripAnsi);
    expect(top[1].startsWith('│ ❯ line0')).toBe(true);
  });

  it('the frame is as tall as before for a text that fits one row', () => {
    const one = buildPromptFrame({ ...base, cols: 100, input: 'short', cursorPos: 5 });
    expect(one.lines.map(stripAnsi).findIndex((l) => l.startsWith('╰'))).toBe(2);
    expect(one.cursorRow).toBe(1);
  });

  it('wraps on a phone too, the count moves to the last row', () => {
    const cols = COMPACT_COLS - 10;
    const input = 'сделай мне красивую страницу с формой входа и регистрации и проверь что всё работает';
    const frame = buildPromptFrame({ ...base, cols, input, cursorPos: input.length, background: { running: 1 } });
    const lines = frame.lines.map(stripAnsi);
    expect(lines.length).toBeGreaterThan(1);
    expect(lines[lines.length - 1]).toContain('◈1');
    for (const l of lines) expect(displayWidth(l)).toBeLessThanOrEqual(cols);
    expect(frame.cursorRow).toBe(lines.length - 1);
  });

  it('the limit follows the terminal height', () => {
    expect(maxInputRows(24)).toBe(6);
    expect(maxInputRows(100)).toBe(8);
    expect(maxInputRows(4)).toBe(1);
  });
});

describe('line breaks in the input', () => {
  const rowsOf = (input: string, cells: number) => text(input, wrapInput(input, cells));

  it('a line break ends the row, and stays at the end of it', () => {
    expect(rowsOf('one\ntwo', 20)).toEqual(['one\n', 'two']);
    expect(rowsOf('a\n\nb', 20)).toEqual(['a\n', '\n', 'b']);
  });

  it('text that ends with a break has an empty row after it, where the cursor waits', () => {
    expect(wrapInput('one\n', 20)).toEqual([{ start: 0, end: 4 }, { start: 4, end: 4 }]);
    expect(rowsOf('\n', 20)).toEqual(['\n', '']);
  });

  it('long paragraphs still wrap inside their own rows', () => {
    const rows = rowsOf('one two three four\nfive', 10);
    expect(rows.join('')).toBe('one two three four\nfive');
    expect(rows[rows.length - 1]).toBe('five');
  });

  it('the frame shows each paragraph on its own row, with the cursor on the right one', () => {
    const frame = buildPromptFrame({
      cols: 100, mode: 'agent', model: 'm', cwd: '/p', contextPercent: 0, cost: 0,
      input: 'one\ntwo', cursorPos: 5, placeholder: 'ask',
    } as any);
    const lines = frame.lines.map(stripAnsi);
    expect(lines[1]).toContain('one');
    expect(lines[1]).not.toContain('two');
    expect(lines[2]).toContain('two');
    expect(frame.cursorRow).toBe(2);               // the top border, the first row, then "two": the cursor is after its "t"
    expect(frame.cursorCol).toBe(4 + 1);
    // a break at the very end: an empty row for the cursor
    const end = buildPromptFrame({
      cols: 100, mode: 'agent', model: 'm', cwd: '/p', contextPercent: 0, cost: 0,
      input: 'one\n', cursorPos: 4, placeholder: 'ask',
    } as any);
    expect(end.cursorRow).toBe(2);
    expect(end.cursorCol).toBe(4);
  });

  it('up and down move between paragraphs', () => {
    const input = 'one\ntwo';
    expect(moveCursorRow(input, 6, 100, -1)).toBe(2);   // "tw|o" -> "on|e"
    expect(moveCursorRow(input, 2, 100, 1)).toBe(6);
    expect(moveCursorRow(input, 2, 100, -1)).toBeNull();
  });
});
