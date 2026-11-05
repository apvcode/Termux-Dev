import { beforeEach, describe, expect, it } from 'vitest';
import { EventEmitter } from 'events';
import { PickerOptions, renderPicker, runPicker } from '../../src/cli/picker.js';
import { displayWidth, stripAnsi } from '../../src/cli/width.js';

const options: PickerOptions<string> = {
  title: 'effort',
  subtitle: 'claude-sonnet-4-5',
  current: 'normal',
  items: [
    { value: 'auto', label: 'auto', detail: 'model default, nothing is sent' },
    { value: 'off', label: 'off', detail: 'no reasoning' },
    { value: 'normal', label: 'normal', detail: 'balanced (~6k thinking tokens)' },
    { value: 'high', label: 'high', detail: 'deep reasoning (~16k thinking tokens)' },
  ],
  notes: ['Not offered by this model: xhigh, max.'],
};
const plain = (lines: string[]) => lines.map(stripAnsi);

describe('renderPicker', () => {
  it('draws a frame with the title, the model and one row per item', () => {
    const lines = plain(renderPicker(options, 0, 80));
    expect(lines[0]).toMatch(/^╭─ effort ─+ claude-sonnet-4-5 ─╮$/);
    expect(lines.filter((l) => /[○●] (auto|off|normal|high)/.test(l))).toHaveLength(4);
    expect(lines[lines.length - 2]).toMatch(/^╰─+╯$/);
    expect(lines[lines.length - 1]).toBe(' ↑↓ move · enter select · esc cancel');
    expect(new Set(lines.slice(0, -1).map((l) => displayWidth(l))).size).toBe(1);
  });

  it('marks the focused row with an arrow and the current value with a filled dot', () => {
    const lines = plain(renderPicker(options, 3, 80));
    expect(lines.filter((l) => l.includes('❯ '))).toHaveLength(1);
    expect(lines.find((l) => l.includes('❯ '))).toContain('high');
    const dots = lines.filter((l) => l.includes('●'));
    expect(dots).toHaveLength(1);
    expect(dots[0]).toContain('normal');
  });

  it('shows the notes under the list', () => {
    expect(plain(renderPicker(options, 0, 80)).join('\n')).toContain('Not offered by this model: xhigh, max.');
  });

  it('never overflows, and has no frame on a phone', () => {
    for (const cols of [26, 30, 40, 59, 60, 70, 100, 200]) {
      for (let focus = 0; focus < options.items.length; focus++) {
        for (const line of renderPicker(options, focus, cols)) {
          expect(displayWidth(line), `cols=${cols}`).toBeLessThanOrEqual(cols - 1);
        }
      }
    }
    const phone = plain(renderPicker(options, 0, 40)).join('\n');
    expect(phone).not.toMatch(/[╭╮╰╯│]/);
    expect(phone).toContain('effort · claude-sonnet-4-5');
    expect(phone).toContain('Not offered by this model');
  });

  it('keeps its height whichever row is focused', () => {
    expect(new Set(options.items.map((_, f) => renderPicker(options, f, 80).length)).size).toBe(1);
  });
});

describe('runPicker', () => {
  class FakeInput extends EventEmitter {
    isRaw = false;
    setRawMode(mode: boolean) { this.isRaw = mode; }
    resume() {}
  }
  let input: FakeInput;
  let chunks: string[];
  const output = { columns: 80, write: (c: string) => { chunks.push(c); return true; } };
  const open = (o = options) => runPicker(o, { input: input as any, output });
  const press = (...keys: string[]) => keys.forEach((k) => input.emit('data', Buffer.from(k)));
  const DOWN = '\x1b[B', UP = '\x1b[A';

  beforeEach(() => { input = new FakeInput(); chunks = []; });

  it('starts on the current value and selects it with Enter', async () => {
    const result = open();
    expect(stripAnsi(chunks[0])).toMatch(/❯ ● normal/);
    press('\r');
    expect(await result).toBe('normal');
  });

  it('moves with the arrows and selects with Enter or Space', async () => {
    const a = open();
    press(DOWN, '\r');
    expect(await a).toBe('high');
    input = new FakeInput();
    const b = open();
    press(UP, UP, ' ');
    expect(await b).toBe('auto');
  });

  it('does not wrap around at either end', async () => {
    const result = open();
    press(UP, UP, UP, UP, UP, UP);      // way past the top
    expect(stripAnsi(chunks[chunks.length - 1])).toMatch(/❯ ○ auto/);
    const draws = chunks.length;
    press(UP);
    expect(chunks.length).toBe(draws);  // nothing to redraw at the first item
    press(DOWN, DOWN, DOWN, DOWN, DOWN, DOWN, DOWN);
    expect(stripAnsi(chunks[chunks.length - 1])).toMatch(/❯ ○ high/);
    press(DOWN);
    expect(stripAnsi(chunks[chunks.length - 1])).toMatch(/❯ ○ high/); // still the last item
    press('\r');
    expect(await result).toBe('high');
  });

  it('selects by number, and ignores numbers beyond the list', async () => {
    const result = open();
    press('9');
    press('7');
    press('2');
    expect(await result).toBe('off');
  });

  it('cancels with Esc, q and Ctrl+C', async () => {
    for (const key of ['\x1b', 'q', '\x03']) {
      input = new FakeInput();
      const result = open();
      press(key);
      expect(await result, JSON.stringify(key)).toBeUndefined();
    }
  });

  it('understands Home / End and several keys in one chunk', async () => {
    const result = open();
    press('\x1b[F');
    expect(stripAnsi(chunks[chunks.length - 1])).toMatch(/❯ ○ high/);
    press('\x1b[H');
    expect(stripAnsi(chunks[chunks.length - 1])).toMatch(/❯ ○ auto/);
    press(DOWN + DOWN + '\r' + DOWN);   // the keys after Enter are dropped
    expect(await result).toBe('normal');
  });

  it('erases itself, restores the terminal and detaches', async () => {
    const result = open();
    press('\r');
    await result;
    expect(chunks[chunks.length - 1]).toMatch(/\x1b\[J\x1b\[\?25h$/);
    expect(input.listenerCount('data')).toBe(0);
    expect(input.isRaw).toBe(false);
  });

  it('works with a list whose current value is not in it', async () => {
    const result = open({ ...options, current: 'zzz' });
    press('\r');
    expect(await result).toBe('auto'); // focus falls back to the first item
  });
});
