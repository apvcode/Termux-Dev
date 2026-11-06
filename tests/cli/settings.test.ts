import { beforeEach, describe, expect, it } from 'vitest';
import { EventEmitter } from 'events';
import { LiveBlock } from '../../src/cli/liveblock.js';
import {
  ITERATION_STEPS,
  ROWS,
  SettingsState,
  changeSetting,
  moveFocus,
  renderSettings,
  runSettings,
  wrapWords,
} from '../../src/cli/settings.js';
import { THEME_IDS, setActiveTheme } from '../../src/cli/theme.js';
import { displayWidth, stripAnsi } from '../../src/cli/width.js';

const base: SettingsState = {
  themeId: 'dracula',
  layout: 'clean',
  pureBlack: true,
  autoApprove: false,
  maxIterations: 30,
  memory: true,
  updates: false,
  costLimit: 0,
  language: 'en',
};
const plain = (lines: string[]) => lines.map(stripAnsi);

describe('LiveBlock', () => {
  let chunks: string[];
  let block: LiveBlock;
  beforeEach(() => {
    chunks = [];
    block = new LiveBlock({ write: (c) => chunks.push(c) });
  });

  it('draws rows separated by newlines and hides the cursor', () => {
    block.draw(['one', 'two', 'three']);
    expect(chunks[0]).toBe('\x1b[?25l\r\x1b[2Kone\x1b[0m\n\x1b[2Ktwo\x1b[0m\n\x1b[2Kthree\x1b[0m');
    expect(block.height).toBe(3);
  });

  it('redraws in place: goes back up to the first row instead of printing below', () => {
    block.draw(['a', 'b', 'c']);
    block.draw(['a', 'B', 'c']);
    expect(chunks[1].startsWith('\x1b[?25l\x1b[2A\r')).toBe(true);
    expect(chunks[1]).not.toMatch(/\n\x1b\[2K[^\n]*\n\x1b\[2K[^\n]*\n\x1b\[2K[^\n]*\n/); // no extra rows
  });

  it('wipes leftover rows when the block gets shorter, and returns the cursor', () => {
    block.draw(['a', 'b', 'c', 'd']);
    block.draw(['a', 'b']);
    expect(chunks[1].endsWith('\n\x1b[2K\n\x1b[2K\x1b[2A')).toBe(true);
    expect(block.height).toBe(2);
  });

  it('erases the whole block and shows the cursor', () => {
    block.draw(['a', 'b', 'c']);
    block.erase();
    expect(chunks[chunks.length - 1]).toBe('\r\x1b[2A\x1b[J\x1b[?25h');
    expect(block.height).toBe(0);
    block.erase(); // nothing left, only the cursor is restored
    expect(chunks[chunks.length - 1]).toBe('\x1b[?25h');
  });
});

describe('changing settings', () => {
  it('browses themes in both directions and wraps around at the ends of the value list', () => {
    const next = changeSetting({ ...base, themeId: 'cyan' }, 'theme', 1);
    expect(next.themeId).toBe(THEME_IDS[1]);
    expect(changeSetting({ ...base, themeId: 'cyan' }, 'theme', -1).themeId).toBe(THEME_IDS[THEME_IDS.length - 1]);
    expect(changeSetting({ ...base, themeId: THEME_IDS[THEME_IDS.length - 1] }, 'theme', 1).themeId).toBe('cyan');
  });

  it('flips toggles whichever way they are pressed, and the layout between its two values', () => {
    for (const key of ['pureBlack', 'autoApprove', 'memory', 'updates'] as const) {
      for (const direction of [1, -1] as const) {
        expect(changeSetting(base, key, direction)[key]).toBe(!base[key]);
      }
    }
    expect(changeSetting(base, 'layout', 1).layout).toBe('classic');
    expect(changeSetting({ ...base, layout: 'classic' }, 'layout', -1).layout).toBe('clean');
  });

  it('steps through the iteration limits and handles values written by hand', () => {
    expect(changeSetting({ ...base, maxIterations: 30 }, 'maxIterations', 1).maxIterations).toBe(50);
    expect(changeSetting({ ...base, maxIterations: 30 }, 'maxIterations', -1).maxIterations).toBe(20);
    expect(changeSetting({ ...base, maxIterations: 9999 }, 'maxIterations', 1).maxIterations).toBe(ITERATION_STEPS[0]);
    expect(changeSetting({ ...base, maxIterations: 120 }, 'maxIterations', 1).maxIterations).toBe(200); // nearest step is 100
    expect(changeSetting({ ...base, maxIterations: 120 }, 'maxIterations', -1).maxIterations).toBe(50);
  });

  it('never mutates the state, and leaves it alone for the info row', () => {
    const frozen = Object.freeze({ ...base });
    expect(() => changeSetting(frozen, 'theme', 1)).not.toThrow();
    expect(changeSetting(frozen, 'about', 1)).toBe(frozen);
    expect(frozen.themeId).toBe('dracula');
  });

  it('moves the focus without wrapping around', () => {
    expect(moveFocus(0, -1)).toBe(0);
    expect(moveFocus(ROWS.length - 1, 1)).toBe(ROWS.length - 1);
    expect(moveFocus(3, 1)).toBe(4);
    expect(moveFocus(3, -1)).toBe(2);
    expect(moveFocus(3, -ROWS.length)).toBe(0);
    expect(moveFocus(3, ROWS.length)).toBe(ROWS.length - 1);
  });
});

describe('renderSettings', () => {
  const COLS = [26, 30, 40, 59, 60, 70, 80, 120, 200]; // 26 is the smallest width devx supports
  const ROWS_AVAILABLE = [10, 16, 20, 21, 26, 27, 40];

  it('never overflows the terminal, whatever its size and whichever row has the focus', () => {
    for (const cols of COLS) {
      for (const rows of ROWS_AVAILABLE) {
        for (let focus = 0; focus < ROWS.length; focus++) {
          for (const line of renderSettings(base, focus, cols, rows, '1.7.0')) {
            expect(displayWidth(line), `cols=${cols} rows=${rows} focus=${focus}`).toBeLessThanOrEqual(cols - 1);
          }
        }
      }
    }
  });

  it('keeps its height while you move, so the panel does not jump', () => {
    for (const cols of [40, 80]) {
      for (const rows of ROWS_AVAILABLE) {
        const heights = new Set(ROWS.map((_, focus) => renderSettings(base, focus, cols, rows, '1.7.0').length));
        expect(heights.size, `cols=${cols} rows=${rows}`).toBe(1);
      }
    }
  });

  it('fits the screen it is given', () => {
    for (const cols of COLS) {
      for (const rows of [13, 16, 20, 21, 26, 27, 40]) {
        const height = renderSettings(base, 0, cols, rows, '1.7.0').length;
        expect(height, `cols=${cols} rows=${rows}`).toBeLessThanOrEqual(rows - 1);
      }
    }
  });

  it('draws an aligned frame on a desktop terminal', () => {
    const lines = plain(renderSettings(base, 0, 100, 40, '1.7.0'));
    const frame = lines.filter((l) => /^[╭│├╰]/.test(l));
    expect(new Set(frame.map((l) => displayWidth(l))).size).toBe(1);
    expect(lines[0]).toMatch(/^╭─ settings ─+ devx v1\.7\.0 ─╮$/);
    expect(lines[lines.length - 1]).toBe(' ↑↓ move · ←→ change · enter toggle · esc close');
    expect(lines.join('\n')).toMatch(/APPEARANCE[\s\S]*AGENT[\s\S]*ABOUT/);
  });

  it('is a frameless list on a phone, with short labels', () => {
    const text = plain(renderSettings(base, 0, 40, 30, '1.7.0')).join('\n');
    expect(text).not.toMatch(/[╭╮╰╯│├┤]/);
    expect(text).toContain('Black bg');
    expect(text).toContain('YOLO mode');
    expect(text).toContain('↑↓ move · ←→ change · esc close');
  });

  it('uses the roomy layout only when there is height for it', () => {
    const roomy = renderSettings(base, 0, 100, 40, '1.7.0').length;
    const tight = renderSettings(base, 0, 100, 24, '1.7.0').length;
    const compact = renderSettings(base, 0, 100, 18, '1.7.0').length;
    expect(roomy).toBeGreaterThan(tight);
    expect(tight).toBeGreaterThan(compact);
  });

  it('shows the value of every setting', () => {
    const text = plain(renderSettings({ ...base, themeId: 'nord', layout: 'classic', maxIterations: 9999, updates: true, memory: false }, 0, 100, 40, '1.7.0')).join('\n');
    expect(text).toContain('Nord Frost');
    expect(text).toContain('Classic');
    expect(text).toContain('unlimited');
    expect(text).toMatch(/Black background\s+● on/);
    expect(text).toMatch(/Auto-approve \(YOLO\)\s+○ off/);
    expect(text).toMatch(/Project memory\s+○ off/);
    expect(text).toMatch(/Update check\s+● on/);
    expect(plain(renderSettings({ ...base, autoApprove: true }, 3, 100, 40, '1.7.0')).join('\n')).toMatch(/Auto-approve \(YOLO\)\s+▲ on/);
  });

  it('marks exactly the focused row and explains it', () => {
    ROWS.forEach((row, focus) => {
      const lines = plain(renderSettings(base, focus, 100, 40, '1.7.0'));
      // the theme preview also contains a prompt arrow, so look for the arrow in front of a row label
      const marked = lines.filter((l) => ROWS.some((r) => l.includes('❯ ' + r.label)));
      expect(marked, row.key).toHaveLength(1);
      expect(marked[0]).toContain('❯ ' + row.label);
    });
    const detail = plain(renderSettings(base, 3, 100, 40, '1.7.0')).join('\n');
    expect(detail).toContain('Skips routine permission prompts');
    const warned = plain(renderSettings({ ...base, autoApprove: true }, 3, 100, 40, '1.7.0')).join('\n');
    expect(warned).toContain('On: the agent runs commands and edits files without asking');
  });

  it('previews the theme on the theme row', () => {
    const text = plain(renderSettings(base, 0, 100, 40, '1.7.0')).join('\n');
    expect(text).toContain('Dracula: Dark purple with vivid pink and green');
    expect(text).toContain('● agent   ◆ plan   ▲ yolo');
    expect(text).toContain('❯ explain this project');
    expect(plain(renderSettings(base, 1, 100, 40, '1.7.0')).join('\n')).not.toContain('explain this project');
  });
});

describe('help text under the list', () => {
  it('wraps at word boundaries', () => {
    expect(wrapWords('the quick brown fox jumps', 10)).toEqual(['the quick', 'brown fox', 'jumps']);
    expect(wrapWords('averyveryverylongword short', 8)).toEqual(['averyve…', 'short']);
    expect(wrapWords('', 10)).toEqual([]);
    for (const line of wrapWords('Skips routine permission prompts. Dangerous commands and credential files still ask first.', 30)) {
      expect(displayWidth(line)).toBeLessThanOrEqual(30);
    }
  });

  it('never breaks a word in the middle or doubles the ellipsis on a phone', () => {
    for (let focus = 0; focus < ROWS.length; focus++) {
      const text = plain(renderSettings(base, focus, 40, 28, '1.7.0')).join('\n');
      expect(text, ROWS[focus].key).not.toContain('……');
      expect(text, ROWS[focus].key).not.toMatch(/\b(Dan|gerous|permis|sion)\b/);
    }
    const yolo = plain(renderSettings(base, 3, 40, 28, '1.7.0'));
    expect(yolo.join('\n')).toMatch(/Skips routine permission prompts\./);
  });

  it('keeps the YOLO warning visible even when the explanation has to be cut', () => {
    const lines = plain(renderSettings({ ...base, autoApprove: true }, 3, 40, 28, '1.7.0')).join('\n');
    expect(lines).toContain('On: the agent runs commands');
    const wide = plain(renderSettings({ ...base, autoApprove: true }, 3, 100, 22, '1.7.0')).join('\n');
    expect(wide).toContain('On: the agent runs commands and edits files without asking.');
  });

  it('fills the preview area of a theme in whatever room there is', () => {
    const tight = plain(renderSettings(base, 0, 100, 24, '1.7.0')).join('\n');
    expect(tight).toContain('Dracula: Dark purple');
    expect(tight).toContain('● agent   ◆ plan   ▲ yolo');
    expect(tight).toContain('✓ done   ! careful   ✗ failed');
    const phone = plain(renderSettings(base, 0, 40, 28, '1.7.0')).join('\n');
    expect(phone).toContain('● agent   ◆ plan   ▲ yolo');
  });
});

describe('runSettings', () => {
  class FakeInput extends EventEmitter {
    isRaw = false;
    modes: boolean[] = [];
    setRawMode(mode: boolean) { this.isRaw = mode; this.modes.push(mode); }
    resume() {}
  }
  let input: FakeInput;
  let chunks: string[];
  const output = { columns: 100, rows: 40, write: (c: string) => { chunks.push(c); return true; } };
  const changes: Array<[string, SettingsState]> = [];

  const open = (state: SettingsState = base) =>
    runSettings(state, { version: '1.7.0', io: { input: input as any, output }, onChange: (key, s) => changes.push([key, s]) });
  const press = (...keys: string[]) => keys.forEach((k) => input.emit('data', Buffer.from(k)));
  const DOWN = '\x1b[B', UP = '\x1b[A', RIGHT = '\x1b[C', LEFT = '\x1b[D';

  beforeEach(() => {
    setActiveTheme('cyan');
    input = new FakeInput();
    chunks = [];
    changes.length = 0;
  });

  it('shows the panel and puts the terminal in raw mode', () => {
    void open();
    expect(stripAnsi(chunks[0])).toContain('settings');
    expect(input.modes).toEqual([true]);
    expect(input.listenerCount('data')).toBe(1);
  });

  it('changes the focused setting with the arrows and reports every change', async () => {
    const result = open();
    press(DOWN, RIGHT);                 // Interface -> Classic
    press(DOWN, RIGHT);                 // Black background -> off
    press(DOWN, RIGHT);                 // Auto-approve -> on
    press(DOWN, LEFT);                  // Max iterations 30 -> 20
    press('\x1b');
    const final = await result;
    expect(final).toMatchObject({ layout: 'classic', pureBlack: false, autoApprove: true, maxIterations: 20 });
    expect(changes.map(([key]) => key)).toEqual(['layout', 'pureBlack', 'autoApprove', 'maxIterations']);
    expect(changes[3][1].maxIterations).toBe(20);
    expect(base.layout).toBe('clean'); // the initial state is not touched
  });

  it('applies a theme change before the redraw, so the panel is repainted in the new colours', async () => {
    const seen: string[] = [];
    const result = runSettings(base, {
      version: '1.7.0',
      io: { input: input as any, output },
      onChange: (key, s) => { seen.push(`${key}:${s.themeId}`); },
    });
    press(RIGHT);
    expect(seen).toEqual(['theme:' + THEME_IDS[(THEME_IDS.indexOf('dracula') + 1) % THEME_IDS.length]]);
    press('q');
    await result;
  });

  it('does not wrap around: the first and the last row stop the cursor', async () => {
    const result = open();
    const drawsBefore = chunks.length;
    press(UP, UP, '\x1b[A');            // already on the first row
    expect(chunks.length).toBe(drawsBefore); // nothing to redraw
    for (let i = 0; i < ROWS.length + 5; i++) press(DOWN);
    const lastDrawn = stripAnsi(chunks[chunks.length - 1]);
    expect(lastDrawn).toMatch(/❯ About devx/);
    const drawsAtEnd = chunks.length;
    press(DOWN, DOWN);
    expect(chunks.length).toBe(drawsAtEnd);  // stuck on the last row, not back on the first
    press('\x03');
    await result;
  });

  it('jumps with Home / End and supports vim keys', async () => {
    const result = open();
    press('\x1b[F');
    expect(stripAnsi(chunks[chunks.length - 1])).toMatch(/❯ About devx/);
    press('\x1b[H');
    expect(stripAnsi(chunks[chunks.length - 1])).toMatch(/❯ Theme/);
    press('j', 'j', 'l'); // third row: black background, flip
    press('h');
    press('q');
    const final = await result;
    expect(final.pureBlack).toBe(true); // flipped twice
  });

  it('understands every common Home and End sequence', async () => {
    const result = open();
    for (const [end, home] of [['\x1b[F', '\x1b[H'], ['\x1b[4~', '\x1b[1~'], ['\x1b[8~', '\x1b[7~'], ['\x1bOF', '\x1bOH'], ['G', 'g']]) {
      press(end);
      expect(stripAnsi(chunks[chunks.length - 1]), JSON.stringify(end)).toMatch(/❯ About devx/);
      press(home);
      expect(stripAnsi(chunks[chunks.length - 1]), JSON.stringify(home)).toMatch(/❯ Theme/);
    }
    press('\x1b');
    await result;
  });

  it('flips a toggle with Enter and Space, and ignores them on the info row', async () => {
    const result = open();
    press(DOWN, DOWN, '\r');             // Black background
    press(' ');
    press('\x1b[F', '\r', ' ');          // About: nothing changes
    press('\x1b');
    const final = await result;
    expect(final.pureBlack).toBe(true);  // flipped twice
    expect(changes.map(([k]) => k)).toEqual(['pureBlack', 'pureBlack']);
  });

  it('redraws in place: the screen does not grow however many changes are made', async () => {
    const result = open();
    const height = renderSettings(base, 0, 100, 40, '1.7.0').length;
    for (let i = 0; i < 40; i++) press(RIGHT);
    const redraws = chunks.slice(1);
    expect(redraws.length).toBe(40);
    for (const chunk of redraws) {
      expect(chunk.startsWith(`\x1b[?25l\x1b[${height - 1}A\r`), 'every redraw goes back up').toBe(true);
      expect((chunk.match(/\n/g) || []).length, 'same number of rows each time').toBe(height - 1);
    }
    press('\x1b');
    await result;
  });

  it('closes on Esc, q and Ctrl+C: erases the panel, restores the terminal and detaches', async () => {
    for (const key of ['\x1b', 'q', '\x03']) {
      input = new FakeInput();
      chunks = [];
      const result = open();
      press(key);
      await result;
      expect(chunks[chunks.length - 1], JSON.stringify(key)).toMatch(/\x1b\[J\x1b\[\?25h$/);
      expect(input.listenerCount('data')).toBe(0);
      expect(input.isRaw).toBe(false);
    }
  });

  it('leaves raw mode on when it was already on', async () => {
    input.isRaw = true;
    const result = open();
    press('\x1b');
    await result;
    expect(input.isRaw).toBe(true);
  });

  it('handles several key presses that arrive in one chunk', async () => {
    const result = open();
    press(DOWN + DOWN + DOWN);                      // three Down presses in one read
    expect(stripAnsi(chunks[chunks.length - 1])).toMatch(/❯ Auto-approve/);
    press(RIGHT + RIGHT);                           // flipped twice: back where it was
    press(DOWN + RIGHT + '\x1b' + RIGHT);          // iterations change, then Esc: the last key is ignored
    const final = await result;
    expect(final.autoApprove).toBe(false);
    expect(final.maxIterations).toBe(50);
    expect(input.listenerCount('data')).toBe(0);
  });

  it('ignores other keys', async () => {
    const result = open();
    const draws = chunks.length;
    press('x', '7', '\x1b[3~');
    expect(chunks.length).toBe(draws);
    press('\x1b');
    await result;
  });
});
