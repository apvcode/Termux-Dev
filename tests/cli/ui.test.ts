import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import os from 'os';
import path from 'path';
import {
  COMPACT_COLS,
  GutterWriter,
  UiSpinner,
  easeToward,
  buildPromptFrame,
  contentWidth,
  formatCost,
  formatDuration,
  formatTokens,
  frameWidth,
  isCompact,
  parseAction,
  pickTip,
  renderHeader,
  renderMeter,
  renderNotice,
  renderResultBlock,
  renderResultLine,
  renderTaskCard,
  renderStatusLine,
  renderToolLine,
  renderTurnSummary,
  renderUserEcho,
  resolveMode,
  shortenPath,
  summarizeToolResult,
  visibleWindow,
  TIPS,
} from '../../src/cli/ui.js';
import { displayWidth, stripAnsi } from '../../src/cli/width.js';
import { setActiveTheme } from '../../src/cli/theme.js';

const plain = (lines: string | string[]) => (Array.isArray(lines) ? lines.map(stripAnsi) : stripAnsi(lines));
const WIDTHS = [26, 30, 38, 40, 46, 60, 80, 120, 200];

describe('formatters', () => {
  it('formats tokens, cost and durations', () => {
    expect(formatTokens(950)).toBe('950');
    expect(formatTokens(12_340)).toBe('12.3k');
    expect(formatTokens(2_500_000)).toBe('2.5M');
    expect(formatCost(0.0042)).toBe('$0.0042');
    expect(formatCost(12.3456)).toBe('$12.35');
    expect(formatCost(0.0042, { estimated: true })).toBe('~$0.0042');
    expect(formatCost(0.0042, { priced: 1, unpriced: 1 })).toBe('$0.0042+');
    expect(formatCost(0, { priced: 0, unpriced: 2 })).toBe('n/a');
    expect(formatCost(0)).toBe('$0.0000');
    expect(formatDuration(900)).toBe('0.9s');
    expect(formatDuration(42_000)).toBe('42s');
    expect(formatDuration(125_000)).toBe('2m 5s');
  });

  it('resolves the displayed mode: plan wins over yolo, yolo over agent', () => {
    expect(resolveMode(false, false)).toBe('agent');
    expect(resolveMode(false, true)).toBe('yolo');
    expect(resolveMode(true, true)).toBe('plan');
    expect(resolveMode(true, false)).toBe('plan');
  });

  it('shortens paths from the left and abbreviates the home directory', () => {
    const home = '/home/dev';
    expect(shortenPath('/home/dev', 20, home)).toBe('~');
    expect(shortenPath('/home/dev/projects/app', 40, home)).toBe('~/projects/app');
    const shortened = shortenPath('/home/dev/very/deep/nested/project/folder', 20, home);
    expect(shortened.startsWith('…/')).toBe(true);
    expect(shortened.endsWith('folder')).toBe(true);
    expect(displayWidth(shortened)).toBeLessThanOrEqual(20);
    expect(displayWidth(shortenPath('/x/' + 'y'.repeat(60), 12, home))).toBeLessThanOrEqual(12);
  });

  it('draws a context meter of constant width', () => {
    for (const pct of [0, 1, 33, 50, 99, 100, 140, -5]) {
      expect(displayWidth(renderMeter(pct, 8))).toBe(8);
    }
    expect(plain(renderMeter(50, 8))).toBe('━━━━────');
    expect(plain(renderMeter(0, 6))).toBe('──────');
    expect(plain(renderMeter(100, 6))).toBe('━━━━━━');
  });

  it('rotates tips by day', () => {
    expect(pickTip(0)).toBe(TIPS[0]);
    expect(pickTip(86_400_000)).toBe(TIPS[1]);
    expect(pickTip(86_400_000 * TIPS.length)).toBe(TIPS[0]);
  });
});

describe('header card', () => {
  const info = {
    version: '1.7.0',
    model: 'deepseek/deepseek-v4.1-flash',
    provider: 'openrouter',
    cwd: path.join(os.homedir(), 'Projects', 'termux-dev'),
    branch: 'main',
    dirty: true,
    yolo: true,
    tip: 'Press Tab to switch between plan and agent mode',
  };

  it.each(WIDTHS)('never overflows a %i column terminal', (cols) => {
    for (const line of renderHeader(info, cols)) {
      expect(displayWidth(line)).toBeLessThanOrEqual(cols - 1);
    }
  });

  it.each(WIDTHS.filter((c) => contentWidth(c) >= 46))('draws an aligned frame at %i columns', (cols) => {
    const lines = plain(renderHeader(info, cols));
    const box = lines.filter((l) => /^[╭│╰]/.test(l));
    expect(box.length).toBeGreaterThanOrEqual(6);
    expect(new Set(box.map((l) => displayWidth(l))).size).toBe(1);
    const text = lines.join('\n');
    expect(text).toContain('devx');
    expect(text).toContain('v1.7.0');
    expect(text).toContain('/model');
    // hints stay next to what they belong to instead of hugging the far edge of the card
    expect(lines.find((l) => l.includes('model '))).toMatch(/deepseek\/deepseek-v4\.1-flash {2}\/model/);
    expect(lines.find((l) => l.includes('cwd'))).toMatch(/termux-dev {2}git:main\*/);
    expect(lines.find((l) => l.includes('devx'))).toMatch(/v1\.7\.0 {2}▲ auto-approve/);
    expect(text).toContain('git:main*');
    expect(text).toContain('~/Projects/termux-dev');
  });

  it('falls back to a frameless layout on a phone in portrait', () => {
    const lines = plain(renderHeader(info, 38));
    expect(lines.join('\n')).not.toMatch(/[╭╮╰╯]/);
    expect(lines[0]).toContain('devx');
    expect(lines[0]).toContain('auto-approve');
    expect(lines.join('\n')).toContain('deepseek');
  });

  it('shows the auto-approve flag only when it is on', () => {
    expect(plain(renderHeader({ ...info, yolo: false }, 80)).join('\n')).not.toContain('auto-approve');
    expect(plain(renderHeader(info, 80)).join('\n')).toContain('auto-approve');
  });

  it('omits the provider row when unknown and the tip when not given', () => {
    const text = plain(renderHeader({ ...info, provider: undefined, tip: undefined }, 80)).join('\n');
    expect(text).not.toContain('provider');
    expect(text).not.toContain('tip');
  });
});

describe('visibleWindow', () => {
  it('returns the whole input when it fits', () => {
    const w = visibleWindow('hello', 3, 20);
    expect(w).toMatchObject({ text: 'hello', leftEllipsis: false, rightEllipsis: false, cursorCells: 3 });
  });

  const long = 'the quick brown fox jumps over the lazy dog '.repeat(8);
  it.each([0, 1, 40, 100, 200, long.length])('keeps the cursor inside the window (cursor at %i)', (cursor) => {
    const cells = 30;
    const w = visibleWindow(long, cursor, cells);
    const used = displayWidth(w.text) + (w.leftEllipsis ? 1 : 0) + (w.rightEllipsis ? 1 : 0);
    expect(used).toBeLessThanOrEqual(cells);
    const cursorColumn = (w.leftEllipsis ? 1 : 0) + w.cursorCells;
    expect(cursorColumn).toBeGreaterThanOrEqual(0);
    expect(cursorColumn).toBeLessThanOrEqual(cells);
  });

  it('shows ellipses on the hidden sides', () => {
    const middle = visibleWindow(long, 150, 30);
    expect(middle.leftEllipsis).toBe(true);
    expect(middle.rightEllipsis).toBe(true);
    const end = visibleWindow(long, long.length, 30);
    expect(end.leftEllipsis).toBe(true);
    expect(end.rightEllipsis).toBe(false);
    const start = visibleWindow(long, 0, 30);
    expect(start.leftEllipsis).toBe(false);
    expect(start.rightEllipsis).toBe(true);
  });

  it('measures wide characters in cells and never splits an emoji', () => {
    const text = 'привет \u{1F600} мир 日本語 ' .repeat(6);
    for (const cursor of [0, 10, 25, 60, text.length]) {
      const w = visibleWindow(text, cursor, 24);
      expect(displayWidth(w.text) + (w.leftEllipsis ? 1 : 0) + (w.rightEllipsis ? 1 : 0)).toBeLessThanOrEqual(24);
      expect(w.text).not.toMatch(/[\ud800-\udbff](?![\udc00-\udfff])/); // no lone surrogate
    }
    const w = visibleWindow('a\u{1F600}b', 3, 20); // cursor after the emoji (UTF-16 index 3)
    expect(w.cursorCells).toBe(3);
  });
});

describe('prompt frame', () => {
  const base = {
    mode: 'agent' as const,
    model: 'deepseek-v4.1-flash',
    cwd: path.join(os.homedir(), 'Projects', 'termux-dev'),
    branch: 'main',
    dirty: false,
    contextPercent: 42,
    cost: 0.0123,
    input: '',
    cursorPos: 0,
    placeholder: 'Ask or describe a task…',
  };

  it.each(WIDTHS.filter((c) => !isCompact(c)))('spans the full terminal width and keeps the borders aligned at %i columns', (cols) => {
    for (const input of ['', 'short', 'x'.repeat(300), 'привет \u{1F600} мир 日本語 '.repeat(15), '[Pasted text #1 +40 lines] hi']) {
      const frame = buildPromptFrame({ ...base, cols, input, cursorPos: Math.floor(input.length / 2) });
      const [top, row, bottom] = plain(frame.lines);
      const w = displayWidth(top);
      expect(displayWidth(row), `input row for ${JSON.stringify(input.slice(0, 12))}`).toBe(w);
      expect(displayWidth(bottom)).toBe(w);
      expect(w).toBe(cols - 1); // the whole width, not a capped box
      expect(top.startsWith('╭')).toBe(true);
      expect(row.startsWith('│') && row.endsWith('│')).toBe(true);
      expect(bottom.startsWith('╰') && bottom.endsWith('╯')).toBe(true);
      expect(frame.cursorRow).toBe(1);
      expect(frame.cursorCol).toBeGreaterThanOrEqual(4);
      expect(frame.cursorCol).toBeLessThanOrEqual(w - 2);
    }
  });

  it('carries the status in the borders', () => {
    const [top, , bottom] = plain(buildPromptFrame({ ...base, cols: 100 }).lines);
    expect(top).toContain('● agent');
    expect(top).toContain('deepseek-v4.1-flash');
    expect(bottom).toContain('~/Projects/termux-dev');
    expect(bottom).toContain('git:main');
    expect(bottom).toContain('42% · $0.0123');
    expect(bottom).toContain('ctx');
    expect(plain(buildPromptFrame({ ...base, cols: 100, branch: 'dev', dirty: true }).lines)[2]).toContain('git:dev*');
  });

  it('labels each mode', () => {
    expect(plain(buildPromptFrame({ ...base, cols: 80, mode: 'plan' }).lines)[0]).toContain('◆ plan');
    expect(plain(buildPromptFrame({ ...base, cols: 80, mode: 'yolo' }).lines)[0]).toContain('▲ yolo');
  });

  it('drops optional details on a medium screen instead of breaking', () => {
    const [top, , bottom] = plain(buildPromptFrame({ ...base, cols: COMPACT_COLS }).lines);
    expect(top).toContain('agent');
    expect(bottom).toContain('42%');
    expect(displayWidth(top)).toBe(COMPACT_COLS - 1);
  });

  it('places the cursor after the typed text, counting wide characters as two cells', () => {
    const ascii = buildPromptFrame({ ...base, cols: 80, input: 'hello', cursorPos: 5 });
    expect(ascii.cursorCol).toBe(4 + 5);
    const wide = buildPromptFrame({ ...base, cols: 80, input: 'a\u{1F600}日', cursorPos: 'a\u{1F600}日'.length });
    expect(wide.cursorCol).toBe(4 + 1 + 2 + 2);
    const middle = buildPromptFrame({ ...base, cols: 80, input: 'hello', cursorPos: 2 });
    expect(middle.cursorCol).toBe(4 + 2);
    expect(buildPromptFrame({ ...base, cols: 80 }).cursorCol).toBe(4); // empty input: on the placeholder start
  });

  it('shows the placeholder only while the input is empty', () => {
    expect(plain(buildPromptFrame({ ...base, cols: 80 }).lines)[1]).toContain('Ask or describe a task…');
    expect(plain(buildPromptFrame({ ...base, cols: 80, input: 'x', cursorPos: 1 }).lines)[1]).not.toContain('Ask or describe');
  });

  it('turns control characters into spaces so a stray CR cannot tear the frame', () => {
    const frame = buildPromptFrame({ ...base, cols: 80, input: 'abc\rdef\n', cursorPos: 8 });
    expect(frame.lines.join('')).not.toMatch(/[\r\n]/);
    const [top, row] = plain(frame.lines);
    expect(displayWidth(row)).toBe(displayWidth(top));
  });

  it('shows key hints, adapting the list to the width and the next mode', () => {
    const wide = plain(buildPromptFrame({ ...base, cols: 100 }).lines).slice(3).join('\n');
    expect(wide).toContain('tab plan mode');
    expect(wide).toContain('ctrl+c stop');
    expect(plain(buildPromptFrame({ ...base, cols: 100, mode: 'plan' }).lines)[3]).toContain('tab agent mode');
    const narrow = plain(buildPromptFrame({ ...base, cols: COMPACT_COLS }).lines)[3];
    expect(displayWidth(narrow)).toBeLessThanOrEqual(COMPACT_COLS - 1);
    expect(narrow).toContain('tab');
  });

  it('replaces the hints with suggestions while a list is open', () => {
    const frame = buildPromptFrame({ ...base, cols: 80, below: ['› /model   Switch model', '  /memory  Project memory'] });
    expect(frame.lines).toHaveLength(5);
    expect(plain(frame.lines)[3]).toBe('  › /model   Switch model');
    expect(plain(frame.lines).join('\n')).not.toContain('ctrl+c stop');
  });
});

describe('compact prompt (phone)', () => {
  const base = {
    mode: 'agent' as const,
    model: 'deepseek-v4.1-flash',
    cwd: path.join(os.homedir(), 'Projects', 'termux-dev'),
    branch: 'main',
    contextPercent: 42,
    cost: 0.0123,
    input: '',
    cursorPos: 0,
    placeholder: 'Ask or describe a task…',
  };
  const NARROW = WIDTHS.filter((c) => isCompact(c));

  it('is a single row: no borders, no hint line', () => {
    for (const cols of NARROW) {
      const frame = buildPromptFrame({ ...base, cols });
      expect(frame.lines, `cols=${cols}`).toHaveLength(1);
      expect(frame.cursorRow).toBe(0);
      expect(plain(frame.lines[0])).not.toMatch(/[╭╮╰╯│]/);
    }
  });

  it.each(NARROW)('never overflows %i columns, whatever is typed', (cols) => {
    for (const input of ['', 'x', 'x'.repeat(300), 'привет \u{1F600} мир 日本語 '.repeat(15)]) {
      const frame = buildPromptFrame({ ...base, cols, input, cursorPos: input.length });
      expect(displayWidth(frame.lines[0])).toBeLessThanOrEqual(cols - 1);
      expect(frame.cursorCol).toBeLessThanOrEqual(cols - 1);
    }
  });

  it('shows the mode as a coloured glyph and the context percentage on the right', () => {
    const row = plain(buildPromptFrame({ ...base, cols: 40 }).lines)[0];
    expect(row.startsWith('● ❯ Ask or describe a task…')).toBe(true);
    expect(row.endsWith('42%')).toBe(true);
    expect(plain(buildPromptFrame({ ...base, cols: 40, mode: 'plan' }).lines)[0].startsWith('◆ ❯')).toBe(true);
    expect(plain(buildPromptFrame({ ...base, cols: 40, mode: 'yolo' }).lines)[0].startsWith('▲ ❯')).toBe(true);
  });

  it('hides the percentage when it would squeeze the input on a very narrow screen', () => {
    expect(plain(buildPromptFrame({ ...base, cols: 21 }).lines)[0]).not.toContain('42%');
  });

  it('puts the cursor after the typed text', () => {
    expect(buildPromptFrame({ ...base, cols: 40, input: 'hello', cursorPos: 5 }).cursorCol).toBe(4 + 5);
    expect(buildPromptFrame({ ...base, cols: 40, input: 'a\u{1F600}', cursorPos: 3 }).cursorCol).toBe(4 + 3);
    expect(buildPromptFrame({ ...base, cols: 40 }).cursorCol).toBe(4);
  });

  it('keeps suggestions under the row', () => {
    const frame = buildPromptFrame({ ...base, cols: 40, input: '/', cursorPos: 1, below: ['› /model  Switch model'] });
    expect(frame.lines).toHaveLength(2);
    expect(plain(frame.lines)[1]).toBe('  › /model  Switch model');
  });

  it('survives a stray carriage return like the full frame', () => {
    const frame = buildPromptFrame({ ...base, cols: 40, input: 'abc\rdef', cursorPos: 7 });
    expect(frame.lines.join('')).not.toMatch(/[\r\n]/);
  });
});

describe('frame width', () => {
  it('uses the whole terminal on desktop and one cell less than the terminal elsewhere', () => {
    expect(frameWidth(200)).toBe(199);
    expect(frameWidth(80)).toBe(79);
    expect(frameWidth(40)).toBe(39);
  });
});

describe('user message echo', () => {
  it('is a block of equally wide rows between two blank lines', () => {
    const rows = plain(renderUserEcho('explain this project', 60));
    expect(rows[0]).toBe('');
    expect(rows[rows.length - 1]).toBe('');
    expect(rows[1]).toContain('❯ explain this project');
    expect(new Set(rows.slice(1, -1).map((r) => displayWidth(r))).size).toBe(1);
  });

  it('wraps long and wide text and marks only the first row', () => {
    const rows = plain(renderUserEcho('привет \u{1F600} мир '.repeat(12), 70)).slice(1, -1);
    expect(rows.length).toBeGreaterThan(1);
    expect(rows[0]).toContain('❯');
    for (const row of rows.slice(1)) expect(row).not.toContain('❯');
    for (const row of rows) expect(displayWidth(row)).toBe(69);
  });

  it('spans the whole terminal width on desktop', () => {
    const rows = plain(renderUserEcho('hi', 200)).slice(1, -1);
    expect(displayWidth(rows[0])).toBe(199);
  });

  it('has no blank lines around it on a phone', () => {
    const rows = plain(renderUserEcho('explain this project', 40));
    expect(rows).toHaveLength(1);
    expect(rows[0]).toContain('❯ explain this project');
    expect(displayWidth(rows[0])).toBe(39);
    for (const row of plain(renderUserEcho('привет \u{1F600} мир '.repeat(12), 40))) expect(displayWidth(row)).toBeLessThanOrEqual(39);
  });
});

describe('tool lines and results', () => {
  it('splits an action description into verb and target', () => {
    expect(parseAction('→ Read src/core/loop.ts')).toEqual({ verb: 'Read', target: 'src/core/loop.ts' });
    expect(parseAction('→ Run: npm test -- --run')).toEqual({ verb: 'Run', target: 'npm test -- --run' });
    expect(parseAction('→ Executing mcp__x__y...')).toEqual({ verb: 'Executing', target: 'mcp__x__y' });
    expect(parseAction('→ Update Plan & Tasks')).toEqual({ verb: 'Update', target: 'Plan & Tasks' });
  });

  it('renders a one-row tool line that fits the screen; ctrl+o shows the whole command', () => {
    expect(plain(renderToolLine('→ Read src/a.ts', 80))).toBe('  ◆ Read  src/a.ts');
    for (const cols of [100, 40]) {
      const row = renderToolLine('→ Run: ' + 'very-long-command '.repeat(20), cols);
      expect(row.split('\n')).toHaveLength(1);
      expect(displayWidth(row)).toBeLessThanOrEqual(cols - 1);
      expect(plain(row)).toContain('…');
    }
    const withLines = renderToolLine('→ Run: cd /x && for d in a; do\n echo $d\ndone', 40);
    expect(withLines.split('\n')).toHaveLength(1);
    expect(plain(withLines)).toMatch(/\(\+2 lines\)$/);

    const full = plain(renderToolLine('→ Run: cd /x && for d in a; do\n  echo $d\ndone', 80, true)).split('\n');
    expect(full).toEqual(['  ◆ Run  cd /x && for d in a; do', '           echo $d', '         done']);
    const long = plain(renderToolLine('→ Run: ' + 'word '.repeat(60), 60, true)).split('\n');
    expect(long.length).toBeGreaterThan(2);
    for (const row of long) expect(displayWidth(row)).toBeLessThanOrEqual(59);
  });


  it('shows the first line of a multi-line script and counts the rest', () => {
    const script = 'cd /home/me && for d in Projects/*/; do\n  if [ -d "$d/.git" ]; then\n    echo $d\n  fi\ndone';
    const line = renderToolLine(`→ Run: ${script}`, 100);
    expect(line.split('\n')).toHaveLength(1);
    expect(plain(line)).toBe('  ◆ Run  cd /home/me && for d in Projects/*/; do (+4 lines)');
    expect(parseAction(`→ Run: ${script}`).target).toBe(script);
  });

  it('renders result lines and notices', () => {
    expect(plain(renderResultLine('2 lines'))).toBe('    ╰ 2 lines');
    expect(displayWidth(renderResultLine('x'.repeat(200), 'muted', 40))).toBeLessThanOrEqual(39);
    expect(plain(renderNotice('error', 'boom'))).toBe('  ✗ boom');
    expect(plain(renderNotice('ok', 'saved'))).toBe('  ✓ saved');
    expect(plain(renderNotice('warn', 'careful'))).toBe('  ! careful');
    expect(plain(renderNotice('info', 'fyi'))).toBe('  · fyi');
  });

  describe('summarizeToolResult', () => {
    const summarize = (name: string, result: string) => plain(summarizeToolResult(name, result, 80));

    const expandedRows = (name: string, result: string, cols = 80) => plain(summarizeToolResult(name, result, cols, true));

    it('collapsed: one row, the first line of the output and how many more there are', () => {
      expect(summarize('bash', 'a\nb\nc\n[Exit code: 0]')).toEqual(['    ╰ a', '      … +2 lines (ctrl+o to expand)']);
      expect(summarize('bash', 'hello\n[Exit code: 0]')).toEqual(['    ╰ hello']);
      expect(summarize('bash', '\n[Exit code: 0]')).toEqual(['    ╰ (no output)']);
      const out = Array.from({ length: 24 }, (_, i) => `line ${i + 1}`).join('\n') + '\n[Exit code: 0]';
      expect(summarize('bash', out)).toEqual(['    ╰ line 1', '      … +23 lines (ctrl+o to expand)']);
    });

    it('expanded (ctrl+o): all of the output', () => {
      expect(expandedRows('bash', 'a\nb\nc\n[Exit code: 0]')).toEqual(['      a', '      b', '      c']);
      const out = Array.from({ length: 24 }, (_, i) => `line ${i + 1}`).join('\n') + '\n[Exit code: 0]';
      expect(expandedRows('bash', out)).toHaveLength(24);
      const huge = Array.from({ length: 500 }, (_, i) => `l${i}`).join('\n') + '\n[Exit code: 0]';
      const rows = expandedRows('bash', huge);
      expect(rows).toHaveLength(301);
      expect(rows[300]).toContain('… +200 lines');
    });

    it('cleans the output: colour codes, tabs, carriage returns, blank edges, long lines', () => {
      const out = '\n\n\x1b[32mgreen\x1b[39m\ttab\r\n' + 'x'.repeat(300) + '\n\n[Exit code: 0]';
      expect(summarize('bash', out)).toEqual(['    ╰ green  tab', '      … +1 lines (ctrl+o to expand)']);
      const lines = expandedRows('bash', out);
      expect(lines[0]).toBe('      green  tab');
      for (const l of lines) expect(displayWidth(l)).toBeLessThanOrEqual(79);
      expect(lines.join('')).not.toContain('\x1b');
    });

    it('a failure: exit code and the line that names the error, all of it when expanded', () => {
      const out = [
        'node:internal/modules/cjs/loader:1',
        "Error: Cannot find module '/x/y.js'",
        '    at Module._resolveFilename (node:internal/a:1:1)',
        "  code: 'MODULE_NOT_FOUND'",
        'Node.js v22',
        '[Exit code: 1]',
      ].join('\n');
      const lines = summarize('bash', out);
      expect(lines).toHaveLength(2);
      expect(lines[1]).toContain('+4 lines (ctrl+o to expand)');
      expect(lines[0]).toContain('exit 1');
      expect(lines[0]).toContain("Error: Cannot find module '/x/y.js'");
      const all = expandedRows('bash', out);
      expect(all[0]).toBe('    ╰ exit 1');
      expect(all.join('\n')).toContain('at Module._resolveFilename');
      expect(all).toHaveLength(6);
    });

    it('a failure without an obvious error line shows the last line', () => {
      const lines = summarize('bash', 'one\ntwo\nthree\nfour\n[Exit code: 2]');
      expect(lines).toHaveLength(2);
      expect(lines[1]).toContain('+3 lines');
      expect(lines[0]).toContain('exit 2');
      expect(lines[0]).toContain('four');
    });

    it('expanded results of other tools show the content under their summary', () => {
      expect(expandedRows('read_file', 'a\nb')).toEqual(['    ╰ 2 lines', '      a', '      b']);
      expect(expandedRows('mcp__x__y', 'result text')).toEqual(['      result text']);
      expect(summarize('mcp__x__y', 'result text')).toEqual(['    ╰ result text']);
    });

    it('reports cancelled and timed out commands', () => {
      expect(summarize('bash', 'partial\n[Operation cancelled by user]')[0]).toContain('Operation cancelled by user');
      expect(summarize('bash', 'x\n[Process killed due to timeout after 120s. Pass a larger "timeout_seconds"]')[0]).toContain('timeout after 120s');
    });

    it('summarises read, list, search and diagnostics', () => {
      expect(summarize('read_file', 'a\nb')).toEqual(['    ╰ 2 lines']);
      expect(summarize('read_file', '[Lines 1-500 of 9000 - output truncated, continue with startLine=501]\n...')).toEqual(['    ╰ lines 1–500 of 9000']);
      expect(summarize('read_file', '[Binary file x.png (100 bytes) - contents not shown]')).toEqual(['    ╰ binary file']);
      expect(summarize('list_dir', '[DIR] a\n[FILE] b')).toEqual(['    ╰ 2 entries']);
      expect(summarize('search', 'a.ts:1: x\nb.ts:2: y')).toEqual(['    ╰ 2 matches']);
      expect(summarize('search', 'No matches found for "x" in .')).toEqual(['    ╰ no matches']);
      expect(summarize('diagnose_code', 'Diagnostics passed')).toEqual(['    ╰ no errors']);
      expect(summarize('diagnose_code', 'Diagnostic errors found:\nTS2322')).toEqual(['    ╰ errors found']);
    });

    it('surfaces tool errors and stays silent otherwise', () => {
      expect(summarize('read_file', 'Error executing tool: Failed to read file: ENOENT')[0]).toContain('Error executing tool');
      expect(summarize('write_file', 'Action denied by user.')[0]).toContain('Action denied by user');
      expect(summarize('write_file', '')).toEqual([]);
      expect(summarize('read_file', '')).toEqual([]);
    });
  });

  it('summarises a finished turn', () => {
    expect(plain(renderTurnSummary({ ms: 6200, tools: 3, outputTokens: 1230, cost: 0.0021 }))).toBe('  ✓ 6.2s · 3 tools · 1.2k out · $0.0021');
    expect(plain(renderTurnSummary({ ms: 6200, tools: 3, inputTokens: 18400, outputTokens: 1230, cost: 0.0021 }))).toBe('  ✓ 6.2s · 3 tools · 18.4k in · 1.2k out · $0.0021');
    expect(plain(renderTurnSummary({ ms: 800, tools: 1, outputTokens: 0, cost: 0 }))).toBe('  ✓ 0.8s · 1 tool');
    expect(plain(renderTurnSummary({ ms: 800, tools: 0, outputTokens: 0, cost: 0.002, costState: { estimated: true, priced: 1 } }))).toBe('  ✓ 0.8s · ~$0.0020');
    expect(plain(renderTurnSummary({ ms: 800, tools: 0, outputTokens: 0, cost: 0, costState: { unpriced: 1, priced: 0 } }))).toBe('  ✓ 0.8s · n/a');
    expect(plain(renderTurnSummary({ ms: 2000, tools: 0, outputTokens: 5, cost: 0, stopped: true }))).toBe('  ■ stopped · 2.0s · 5 out');
  });
});

describe('GutterWriter', () => {
  const make = (maxCols = 20) => {
    const chunks: string[] = [];
    const writer = new GutterWriter('● ', '  ', (s) => chunks.push(s), () => maxCols);
    return { writer, text: () => chunks.join('') };
  };

  it('prefixes the first line with the marker and the others with the indent', () => {
    const { writer, text } = make(80);
    writer.write('hello\nworld\n');
    expect(text()).toBe('● hello\n  world\n');
  });

  it('does not put whitespace on blank lines and drops leading blank lines', () => {
    const { writer, text } = make(80);
    writer.write('\n\nfirst\n\nsecond\n');
    expect(text()).toBe('● first\n\n  second\n');
  });

  it('wraps long lines at word boundaries and keeps the indentation', () => {
    const { writer, text } = make(20);
    writer.write('the quick brown fox jumps over the lazy dog\n');
    const lines = text().split('\n').filter(Boolean);
    expect(lines.length).toBeGreaterThan(2);
    expect(lines[0].startsWith('● ')).toBe(true);
    for (const line of lines.slice(1)) expect(line.startsWith('  ')).toBe(true);
    for (const line of lines) expect(displayWidth(line.trimEnd())).toBeLessThanOrEqual(20);
    expect(lines.join(' ').replace(/\s+/g, ' ').replace('● ', '')).toContain('the quick brown fox jumps over the lazy dog');
  });

  it('keeps words that arrive in pieces together', () => {
    const { writer, text } = make(14);
    for (const piece of ['a very lo', 'ng wor', 'd arrives', ' in pie', 'ces\n']) writer.write(piece);
    expect(text()).not.toMatch(/lo\n\s*ng/);
    expect(text().replace(/\s+/g, ' ')).toContain('a very long word arrives in pieces');
  });

  it('continues list items under the text, not under the marker', () => {
    const { writer, text } = make(24);
    writer.write('  • reading package.json and running tests\n');
    const lines = text().split('\n').filter(Boolean);
    expect(lines.length).toBeGreaterThan(1);
    expect(lines[0]).toBe('●   • reading');
    for (const line of lines) expect(line).toBe(line.trimEnd()); // no dangling space at a wrap point
    for (const line of lines.slice(1)) expect(line.startsWith('      ')).toBe(true); // 2 gutter + 2 indent + marker + space
  });

  it('flushes a partial last word and starts a new message on reset', () => {
    const { writer, text } = make(80);
    writer.write('unfinished');
    expect(text()).toBe('');
    writer.flush();
    expect(text()).toBe('● unfinished');
    writer.write('\n');
    writer.reset();
    writer.write('next message\n');
    expect(text().endsWith('● next message\n')).toBe(true);
  });

  it('does not count colour codes towards the width', () => {
    const { writer, text } = make(12);
    writer.write('\x1b[31mred\x1b[39m \x1b[32mgreen\x1b[39m \x1b[34mblue\x1b[39m\n');
    expect(text().split('\n')[0]).toContain('\x1b[31mred\x1b[39m');
    expect(plain(text()).split('\n')[0]).toBe('● red green');
  });
});

describe('UiSpinner', () => {
  let out: { chunks: string[]; isTTY: boolean; columns: number; write: (c: string) => boolean };
  let clock: number;

  beforeEach(() => {
    vi.useFakeTimers();
    clock = 1_000;
    out = { chunks: [], isTTY: true, columns: 80, write(c: string) { this.chunks.push(c); return true; } };
  });
  afterEach(() => vi.useRealTimers());

  const make = () => new UiSpinner(out, () => clock, 80);

  it('shows live token counts that change from frame to frame and survive a restart', () => {
    let tokens = 100;
    const spinner = make();
    spinner.setMetaProvider(() => `↑ 7.2k ↓ ${tokens} tokens`);
    spinner.start('Waiting for the model');
    expect(plain(out.chunks[1])).toContain('0.0s · ↑ 7.2k ↓ 100 tokens · ctrl+c to stop');
    tokens = 364;
    clock += 8_000;
    vi.advanceTimersByTime(80);
    expect(plain(out.chunks[out.chunks.length - 1])).toContain('8.0s · ↑ 7.2k ↓ 364 tokens');
    spinner.stop();
    out.chunks.length = 0;
    spinner.start('Read src/a.ts');
    expect(plain(out.chunks[1])).toContain('↑ 7.2k ↓ 364 tokens');
    spinner.stop();
  });

  it('drops the token counts before the label on a narrow screen', () => {
    out.columns = 40;
    const spinner = make();
    spinner.setMetaProvider(() => '↑ 18.4k ↓ 1.2k tokens');
    spinner.start('Writing src/components/Header.tsx');
    expect(displayWidth(plain(out.chunks[1]))).toBeLessThanOrEqual(40);
    spinner.stop();
  });

  it('animates a single line with the elapsed time and a stop hint', () => {
    const spinner = make();
    spinner.start('Read src/a.ts');
    expect(spinner.isActive).toBe(true);
    expect(out.chunks[0]).toBe('\x1b[?25l'); // hide cursor
    expect(plain(out.chunks[1])).toContain('Read src/a.ts');
    expect(plain(out.chunks[1])).toContain('0.0s · ctrl+c to stop');

    clock += 3_400;
    vi.advanceTimersByTime(80);
    const last = plain(out.chunks[out.chunks.length - 1]);
    expect(last).toContain('3.4s');
    expect(out.chunks[1].slice(-60)).not.toBe(out.chunks[out.chunks.length - 1].slice(-60)); // frame changed
    spinner.stop();
  });

  it('updates the label and the detail, and cleans up on stop', () => {
    const spinner = make();
    spinner.start('Connecting');
    spinner.message('→ Write hello.js');
    spinner.setMeta('1.2k tok');
    vi.advanceTimersByTime(80);
    const line = plain(out.chunks[out.chunks.length - 1]);
    expect(line).toContain('Write hello.js');
    expect(line).not.toContain('→');
    expect(line).toContain('1.2k tok');

    spinner.stop();
    expect(spinner.isActive).toBe(false);
    expect(out.chunks[out.chunks.length - 1]).toBe('\r\x1b[2K\x1b[?25h'); // erase line, show cursor
    const written = out.chunks.length;
    vi.advanceTimersByTime(500);
    expect(out.chunks.length).toBe(written); // no more frames after stop
    spinner.stop(); // idempotent
    expect(out.chunks.length).toBe(written);
  });

  it('keeps the line within the terminal width', () => {
    const spinner = make();
    out.columns = 30;
    spinner.start('x'.repeat(200));
    expect(displayWidth(spinner.renderLine(30))).toBeLessThanOrEqual(29);
    spinner.stop();
  });

  it('draws nothing when output is not a terminal', () => {
    out.isTTY = false;
    const spinner = make();
    spinner.start('Working');
    vi.advanceTimersByTime(500);
    spinner.stop();
    expect(out.chunks).toEqual([]);
  });
});

describe('long notices', () => {
  it('continue under their text instead of at the left edge', () => {
    const lines = plain(renderNotice('warn', 'The endpoint rejected the reasoning setting (Unknown parameter). Continuing without it. /effort auto stops sending it for good.', 40).split('\n'));
    expect(lines.length).toBeGreaterThan(2);
    expect(lines[0].startsWith('  ! The endpoint')).toBe(true);
    for (const line of lines.slice(1)) expect(line.startsWith('    ')).toBe(true);
    for (const line of lines) expect(displayWidth(line)).toBeLessThanOrEqual(39);
    expect(lines.join(' ').replace(/\s+/g, ' ')).toContain('Continuing without it.');
  });

  it('stay a single line when short', () => {
    expect(renderNotice('ok', 'saved', 80)).toContain('saved');
    expect(renderNotice('ok', 'saved', 80).includes('\n')).toBe(false);
  });
});

describe('renderResultBlock', () => {
  it('wraps a long explanation under its first line instead of cutting it off', () => {
    const text = 'No reasoning setting is known for this model. If you know how this model takes a reasoning setting, describe it in the config file.';
    const lines = plain(renderResultBlock(text, 'muted', 50).split('\n'));
    expect(lines.length).toBeGreaterThan(2);
    expect(lines[0].startsWith('    ╰ No reasoning')).toBe(true);
    for (const line of lines.slice(1)) expect(line.startsWith('      ')).toBe(true);
    for (const line of lines) expect(displayWidth(line)).toBeLessThanOrEqual(49);
    expect(lines.join(' ').replace(/\s+/g, ' ')).toContain('describe it in the config file.');
    expect(lines.join('')).not.toContain('…');
  });

  it('is one line when it fits', () => {
    expect(plain(renderResultBlock('short', 'ok', 80).split('\n'))).toEqual(['    ╰ short']);
  });
});

describe('easeToward', () => {
  it('starts fast and slows down, never overshoots and lands on the target', () => {
    let value = 0;
    const steps: number[] = [];
    for (let i = 0; i < 80; i++) {
      const next = easeToward(value, 1000, 60);
      steps.push(next - value);
      expect(next).toBeLessThanOrEqual(1000);
      expect(next).toBeGreaterThanOrEqual(value);
      value = next;
    }
    expect(steps[0]).toBeGreaterThan(steps[5]);
    expect(steps[5]).toBeGreaterThan(steps[20]);
    expect(value).toBe(1000);
  });

  it('covers the same distance in the same time at any frame rate', () => {
    let coarse = 0;
    for (let i = 0; i < 5; i++) coarse = easeToward(coarse, 1000, 200);
    let fine = 0;
    for (let i = 0; i < 50; i++) fine = easeToward(fine, 1000, 20);
    expect(Math.abs(coarse - fine)).toBeLessThan(1);
  });

  it('also eases down when the real number turns out smaller, and does not move without time', () => {
    expect(easeToward(500, 100, 60)).toBeLessThan(500);
    expect(easeToward(500, 100, 60)).toBeGreaterThan(100);
    expect(easeToward(5, 100, 0)).toBe(5);
    expect(easeToward(99.8, 100, 60)).toBe(100);
  });
});

describe('renderTaskCard', () => {
  const tasks = [
    { text: 'Собрать сцену: небо, река, берег, туман, свет', status: 'in_progress' as const },
    { text: 'Модель бомжа: голова, борода, одежда, руки, ботинки', status: 'pending' as const },
    { text: 'Проверить в headless-браузере и открыть превью', status: 'completed' as const },
  ];

  beforeEach(() => setActiveTheme('cyan'));

  it('is a closed box: every row has the same width, both borders line up', () => {
    for (const cols of [40, 60, 80, 120]) {
      const rows = renderTaskCard(tasks, cols).map(stripAnsi);
      expect(rows[0].trimStart().startsWith('╭─ Plan & Tasks')).toBe(true);
      expect(rows[rows.length - 1].trimStart().startsWith('╰')).toBe(true);
      const widths = new Set(rows.map((r) => displayWidth(r)));
      expect(widths.size).toBe(1);
      expect([...widths][0]).toBeLessThanOrEqual(cols - 1);
      for (const r of rows.slice(1, -1)) expect(r.trimStart().startsWith('│') && r.endsWith('│')).toBe(true);
    }
  });

  it('has no blank rows around it and shows the progress', () => {
    const rows = renderTaskCard(tasks, 80);
    expect(rows.every((r) => stripAnsi(r).trim() !== '')).toBe(true);
    expect(stripAnsi(rows[0])).toContain('1/3');
    expect(stripAnsi(rows.join('\n'))).toContain('[✓]');
  });

  it('wraps a long step under its own text instead of breaking the box', () => {
    const rows = renderTaskCard([{ text: 'word '.repeat(40), status: 'pending' }], 50).map(stripAnsi);
    expect(rows.length).toBeGreaterThan(3);
    expect(new Set(rows.map((r) => displayWidth(r))).size).toBe(1);
  });

  it('draws nothing for an empty plan', () => {
    expect(renderTaskCard([], 80)).toEqual([]);
  });
});

describe('the working line keeps its details in place', () => {
  it('the details sit close to the label, a little further for a longer one, in steps', () => {
    const at = (label: string, columns = 100) => {
      const line = stripAnsi(renderStatusLine({ label, elapsedMs: 5000, meta: '↓ 1.2k tokens', frame: 0, columns }));
      const start = displayWidth(line.slice(0, line.indexOf('(')));
      const labelEnd = displayWidth(line.slice(0, line.indexOf('(')).trimEnd());
      return { start, gap: start - labelEnd };
    };
    const short = at('Thinking');
    const waiting = at('Waiting for the model');
    const long = at('Run: cd /tmp && timeout 90 chromium --headless=new --no-sandbox --disable-gpu ' + 'x'.repeat(80));
    expect(short.gap).toBeGreaterThanOrEqual(2);
    expect(short.gap).toBeLessThanOrEqual(10);
    expect(waiting.gap).toBeGreaterThanOrEqual(2);
    expect(waiting.gap).toBeLessThanOrEqual(10);
    expect(short.start).toBeLessThan(waiting.start);
    expect(at('Reading').start).toBe(at('Writing').start);          // same length class: same column
    expect(long.start).toBeLessThanOrEqual(4 + 28 + 2);
    expect(short.start).toBeLessThan(24);
  });

  it('stays inside a narrow screen', () => {
    for (const columns of [30, 40, 60]) {
      const line = renderStatusLine({ label: 'Run: ' + 'x'.repeat(100), elapsedMs: 5000, meta: '↓ 1.2k tokens', frame: 0, columns });
      expect(displayWidth(stripAnsi(line))).toBeLessThanOrEqual(columns - 1);
    }
  });
});

describe('the working line is one row', () => {
  it('a multi-line command in the label never produces a line break', () => {
    const label = "→ Run: cd /tmp && python3 - <<'PY'\np='bum.html'; s=open(p).read()\ndef rep(a,b):\r\n    global s";
    for (const columns of [40, 80, 150]) {
      const line = renderStatusLine({ label, elapsedMs: 64000, meta: '↓ 3.8k tokens', frame: 0, columns });
      expect(line).not.toMatch(/[\r\n]/);
      expect(displayWidth(stripAnsi(line))).toBeLessThanOrEqual(columns - 1);
    }
  });
});

describe('tailWidth', () => {
  it('keeps the newest words that fit, also with wide characters', async () => {
    const { tailWidth } = await import('../../src/cli/width.js');
    expect(tailWidth('one two three four', 10)).toBe('three four');
    expect(tailWidth('short', 20)).toBe('short');
    expect(tailWidth('日本語のテキスト', 6)).toBe('キスト');
    expect(tailWidth('', 5)).toBe('');
  });
});

describe('output of MCP tools', () => {
  const rows = (name: string, text: string, expanded = false) => summarizeToolResult(name, text, 80, expanded).map(stripAnsi);

  it('shows the first line and how many more, and all of it when expanded', () => {
    const text = '{"results": [1, 2]}\nsecond\nthird';
    expect(rows('mcp__notion__notion-search', text)).toEqual(['    ╰ {"results": [1, 2]}', '      … +2 lines (ctrl+o to expand)']);
    expect(rows('mcp__notion__notion-search', text, true)).toEqual(['      {"results": [1, 2]}', '      second', '      third']);
    expect(rows('mcp__x__y', '   \n')).toEqual([]);
    expect(rows('mcp__x__y', 'x'.repeat(300))[0].length).toBeLessThanOrEqual(80);
  });

  it('a failure of the tool is shown as an error', () => {
    expect(rows('mcp__x__y', 'Error executing tool: MCP error -32602: bad input')[0]).toContain('Error executing tool');
  });

  it('mcp_manage reports the servers in one row', () => {
    const json = JSON.stringify({ servers: [{ name: 'notion', status: 'connected', tools: 77 }, { name: 'bad', status: 'failed', tools: 0 }] });
    expect(rows('mcp_manage', json)).toEqual(['    ╰ notion connected (77 tools), bad failed']);
  });
});

describe('fitModelLabel', () => {
  it('keeps the reasoning level when the model name is long', async () => {
    const { fitModelLabel } = await import('../../src/cli/ui.js');
    expect(fitModelLabel('o3 · think high', 32)).toBe('o3 · think high');
    const cut = fitModelLabel('deepseek-v4.1-flash-abliterated · think max', 32);
    expect(cut.endsWith(' · think max')).toBe(true);
    expect(displayWidth(cut)).toBeLessThanOrEqual(32);
    expect(cut.startsWith('deepseek-v4')).toBe(true);
    expect(cut).toContain('…');
  });

  it('cuts the whole label when there is no room even for the level, or no level at all', async () => {
    const { fitModelLabel } = await import('../../src/cli/ui.js');
    expect(displayWidth(fitModelLabel('very-long-model-name-without-level-here', 20))).toBeLessThanOrEqual(20);
    expect(displayWidth(fitModelLabel('name · a very long reasoning description that cannot fit', 12))).toBeLessThanOrEqual(12);
  });

  it('shows the level in the border of the prompt frame for a long name', () => {
    const frame = buildPromptFrame({ cols: 120, mode: 'agent', model: 'deepseek-v4.1-flash-abliterated · think max', cwd: '/x', contextPercent: 1, cost: 0, input: '', cursorPos: 0, placeholder: 'x' } as any);
    expect(stripAnsi(frame.lines[0])).toContain('think max');
  });
});
