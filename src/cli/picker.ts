import { LiveBlock } from './liveblock.js';
import { splitKeys } from './keys.js';
import { PermissionInput, PermissionOutput } from './permission.js';
import { palette } from './ui.js';
import { displayWidth, padEndWidth, truncateWidth, wrapWords } from './width.js';

/**
 * A small list to choose one value from, drawn in place like the settings panel:
 * arrow keys move without wrapping around, Enter selects, Esc cancels.
 */

export interface PickerItem<T> {
  value: T;
  label: string;
  /** What choosing it means, shown after the label. */
  detail?: string;
}

export interface PickerOptions<T> {
  title: string;
  /** Shown on the right of the title, e.g. the model name. */
  subtitle?: string;
  items: PickerItem<T>[];
  /** The value in force now: marked with a filled dot, and focused first. */
  current?: T;
  /** Muted lines under the list. */
  notes?: string[];
  /** Width of the label column (default: the longest label, at most 10 cells). */
  labelWidth?: number;
}

function hints(width: number): string {
  const variants: Array<Array<[string, string]>> = [
    [['↑↓', 'move'], ['enter', 'select'], ['esc', 'cancel']],
    [['↑↓', ''], ['enter', ''], ['esc', '']],
    [['enter', ''], ['esc', '']],
  ];
  for (const items of variants) {
    const text = items.map(([k, label]) => palette.text(k) + (label ? ' ' + palette.faint(label) : '')).join(palette.faint(items[0][1] ? ' · ' : ' '));
    if (displayWidth(text) <= width) return text;
  }
  return '';
}

/** Of a long list only the rows around the focus are shown, with "↑ N more" / "↓ N more" at the cut edges. */
function windowItems<T>(rows: Array<{ text: string; index: number }>, focus: number, max: number, width: number): Array<{ text: string; index: number }> {
  if (rows.length <= max) return rows;
  const start = Math.max(0, Math.min(rows.length - max, focus - Math.floor(max / 2)));
  const out = rows.slice(start, start + max);
  const above = start;
  const below = rows.length - (start + max);
  if (above > 0) out[0] = { text: ' ' + palette.faint(truncateWidth(`↑ ${above + 1} more`, width)), index: -1 };
  if (below > 0) out[out.length - 1] = { text: ' ' + palette.faint(truncateWidth(`↓ ${below + 1} more`, width)), index: -1 };
  return out;
}

export function renderPicker<T>(options: PickerOptions<T>, focus: number, cols: number, termRows = 40): string[] {
  const labelWidth = options.labelWidth ?? Math.min(10, Math.max(...options.items.map((i) => displayWidth(i.label)), 4)) + 2;
  const maxItems = Math.max(3, termRows - 7 - (options.notes?.length ?? 0));

  const row = (item: PickerItem<T>, index: number, width: number): string => {
    const focused = index === focus;
    const isCurrent = options.current !== undefined && item.value === options.current;
    const mark = focused ? palette.accent('❯') : ' ';
    const dot = isCurrent ? palette.accent('●') : palette.faint('○');
    const label = focused ? palette.bold(palette.text(item.label)) : palette.muted(item.label);
    const prefix = ` ${mark} ${dot} ${padEndWidth(label, labelWidth)}`;
    const room = Math.max(0, width - displayWidth(prefix) - 1);
    const detail = item.detail && room > 6 ? palette.faint(truncateWidth(item.detail, room)) : '';
    return prefix + detail;
  };

  // ── phone: no frame
  if (cols < 60) {
    const width = Math.max(20, cols - 1);
    const lines = [' ' + palette.bold(palette.accent(options.title)) + (options.subtitle ? palette.faint(' · ' + truncateWidth(options.subtitle, Math.max(6, width - displayWidth(options.title) - 6))) : '')];
    const all = options.items.map((item, i) => ({ text: row(item, i, width), index: i }));
    for (const r of windowItems(all, focus, maxItems, width)) lines.push(r.index === focus ? palette.shade(padEndWidth(r.text, width)) : r.text);
    for (const note of options.notes ?? []) {
      for (const line of wrapWords(note, width - 2)) lines.push(' ' + palette.faint(line));
    }
    lines.push(' ' + hints(width - 1));
    return lines;
  }

  // ── card
  const W = Math.min(cols - 1, 76);
  const iw = W - 4;
  const rule = palette.rule;
  const box = (content: string, shaded = false) =>
    rule('│') + (shaded ? palette.shade(' ' + padEndWidth(content, iw) + ' ') : ' ' + padEndWidth(content, iw) + ' ') + rule('│');

  const title = ' ' + palette.bold(palette.accent(options.title)) + ' ';
  const subtitle = options.subtitle ? ' ' + palette.muted(truncateWidth(options.subtitle, Math.max(6, W - 12 - displayWidth(options.title)))) + ' ' : '';
  const lines = [rule('╭─') + title + rule('─'.repeat(Math.max(0, W - 4 - displayWidth(title) - displayWidth(subtitle)))) + subtitle + rule('─╮')];
  lines.push(box(''));
  const allRows = options.items.map((item, i) => ({ text: row(item, i, iw), index: i }));
  for (const r of windowItems(allRows, focus, maxItems, iw)) lines.push(box(r.text, r.index === focus));
  const notes = (options.notes ?? []).flatMap((note) => wrapWords(note, iw - 3));
  if (notes.length > 0) {
    lines.push(box(''));
    for (const line of notes) lines.push(box('  ' + palette.faint(line)));
  }
  lines.push(rule('╰' + '─'.repeat(W - 2) + '╯'));
  lines.push(' ' + hints(cols - 2));
  return lines;
}

export interface PickerIO {
  input: PermissionInput;
  output: PermissionOutput;
}

/** Shows the list and resolves with the chosen value, or undefined when cancelled. */
export function runPicker<T>(options: PickerOptions<T>, io: PickerIO = { input: process.stdin, output: process.stdout }): Promise<T | undefined> {
  return new Promise((resolve) => {
    const { input, output } = io;
    const block = new LiveBlock(output);
    const count = options.items.length;
    let focus = Math.max(0, options.items.findIndex((i) => options.current !== undefined && i.value === options.current));
    let done = false;

    const draw = () => block.draw(renderPicker(options, focus, output.columns || 80, (output as any).rows || 40));
    const wasRaw = !!input.isRaw;
    input.setRawMode?.(true);
    input.resume();

    const finish = (value: T | undefined) => {
      done = true;
      input.off('data', onData);
      block.erase();
      if (!wasRaw) input.setRawMode?.(false);
      resolve(value);
    };

    const move = (delta: number) => {
      const next = Math.max(0, Math.min(count - 1, focus + delta));
      if (next !== focus) {
        focus = next;
        draw();
      }
    };

    const handleKey = (key: string) => {
      if (key === '\x1b[A' || key === '\x1bOA' || key === 'k') return move(-1);
      if (key === '\x1b[B' || key === '\x1bOB' || key === 'j') return move(1);
      if (key === '\x1b[H' || key === '\x1b[1~' || key === '\x1bOH' || key === 'g') return move(-count);
      if (key === '\x1b[F' || key === '\x1b[4~' || key === '\x1bOF' || key === 'G') return move(count);
      if (key === '\r' || key === '\n' || key === ' ') return finish(options.items[focus].value);
      if (key >= '1' && key <= '9' && Number(key) <= count) return finish(options.items[Number(key) - 1].value);
      if (key === '\x1b' || key === 'q' || key === '\x03') return finish(undefined);
    };

    const onData = (chunk: Buffer | string) => {
      for (const key of splitKeys(chunk.toString())) {
        if (done) return;
        handleKey(key);
      }
    };

    input.on('data', onData);
    draw();
  });
}
