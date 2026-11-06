import { LiveBlock } from './liveblock.js';
import { WORD_BACKSPACE, normalizeKey, splitKeys } from './keys.js';
import { PickerIO, windowItems } from './picker.js';
import { palette } from './ui.js';
import { displayWidth, padEndWidth, tailWidth, truncateWidth, wrapWords } from './width.js';
import { t } from '../core/i18n.js';

/**
 * Two small prompts in the style of the pickers: a list that is narrowed by typing (providers,
 * models) and a one-line input (a key, an address). They take the place of the generic prompt
 * library, so the first run and the settings look like the rest of devx.
 */

export interface FinderItem<T> {
  value: T;
  label: string;
  detail?: string;
  /** More text the filter looks in (an id that differs from the label). */
  keywords?: string;
  /** Always shown, above the matches (Back, "type a name"). */
  pinned?: boolean;
}

export interface FinderOptions<T> {
  title: string;
  subtitle?: string;
  items: FinderItem<T>[];
  current?: T;
  placeholder?: string;
  notes?: string[];
  /** The widest label column (default 24): sessions have long titles. */
  labelWidth?: number;
}

/** Every word typed must be found in the label, the id or the keywords; matches at the start of the label come first. */
export function filterItems<T>(items: FinderItem<T>[], query: string): FinderItem<T>[] {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  const pinned = items.filter((i) => i.pinned);
  const rest = items.filter((i) => !i.pinned);
  if (words.length === 0) return [...pinned, ...rest];
  const scored: Array<{ item: FinderItem<T>; score: number }> = [];
  for (const item of rest) {
    const hay = `${item.label} ${item.keywords ?? ''}`.toLowerCase();
    if (!words.every((w) => hay.includes(w))) continue;
    const label = item.label.toLowerCase();
    scored.push({ item, score: label.startsWith(words[0]) ? 0 : label.includes(words[0]) ? 1 : 2 });
  }
  scored.sort((a, b) => a.score - b.score);   // stable: the original order is kept inside a score
  return [...pinned, ...scored.map((s) => s.item)];
}

function hintLine(width: number, typing: boolean): string {
  const variants: Array<Array<[string, string]>> = [
    typing
      ? [['type', t('finder.hint.filter')], ['↑↓', t('picker.hint.move')], ['enter', t('picker.hint.select')], ['esc', t('picker.hint.cancel')]]
      : [['enter', t('picker.hint.confirm')], ['esc', t('picker.hint.cancel')]],
    [['↑↓', ''], ['enter', ''], ['esc', '']],
    [['enter', ''], ['esc', '']],
  ];
  for (const items of variants) {
    const text = items.map(([k, label]) => palette.text(k) + (label ? ' ' + palette.faint(label) : '')).join(palette.faint(items[0][1] ? ' · ' : ' '));
    if (displayWidth(text) <= width) return text;
  }
  return '';
}

/** The card around some content rows; `rows` are already padded to the inner width by the caller's `box`. */
function frame(title: string, subtitle: string | undefined, cols: number, body: (iw: number, box: (c: string, shaded?: boolean) => string) => string[], footer: string): string[] {
  if (cols < 60) {
    const width = Math.max(20, cols - 1);
    const head = ' ' + palette.bold(palette.accent(title)) + (subtitle ? palette.faint(' · ' + truncateWidth(subtitle, Math.max(6, width - displayWidth(title) - 6))) : '');
    const plain = (c: string, shaded = false) => (shaded ? palette.shade(padEndWidth(' ' + c, width)) : ' ' + c);
    return [head, ...body(width - 1, plain), ' ' + footer];
  }
  const W = Math.min(cols - 1, 76);
  const iw = W - 4;
  const rule = palette.rule;
  const box = (content: string, shaded = false) =>
    rule('│') + (shaded ? palette.shade(' ' + padEndWidth(content, iw) + ' ') : ' ' + padEndWidth(content, iw) + ' ') + rule('│');
  const head = ' ' + palette.bold(palette.accent(title)) + ' ';
  const sub = subtitle ? ' ' + palette.muted(truncateWidth(subtitle, Math.max(6, W - 12 - displayWidth(title)))) + ' ' : '';
  return [
    rule('╭─') + head + rule('─'.repeat(Math.max(0, W - 4 - displayWidth(head) - displayWidth(sub)))) + sub + rule('─╮'),
    box(''),
    ...body(iw, box),
    rule('╰' + '─'.repeat(W - 2) + '╯'),
    ' ' + footer,
  ];
}

/** Where the focus starts: on the first match, not on a pinned row such as "type a name". */
export function firstMatch<T>(shown: FinderItem<T>[]): number {
  const i = shown.findIndex((item) => !item.pinned);
  return i === -1 ? 0 : i;
}

export function renderFinder<T>(options: FinderOptions<T>, query: string, shown: FinderItem<T>[], focus: number, cols: number, termRows = 40): string[] {
  const maxItems = Math.max(3, termRows - 9 - (options.notes?.length ?? 0));
  return frame(
    options.title,
    options.subtitle ?? `${shown.filter((i) => !i.pinned).length}/${options.items.filter((i) => !i.pinned).length}`,
    cols,
    (iw, box) => {
      const lines: string[] = [];
      const typed = query ? palette.text(tailWidth(query, Math.max(4, iw - 6))) : palette.faint(truncateWidth(options.placeholder ?? t('finder.placeholder'), Math.max(4, iw - 6)));
      lines.push(box(' ' + palette.accent('/') + ' ' + (query ? typed + palette.accent('█') : palette.accent('█') + typed)));
      lines.push(box(palette.rule('─'.repeat(Math.max(0, iw - 1)))));
      if (shown.length === 0) {
        lines.push(box('  ' + palette.faint(t('finder.empty'))));
      } else {
        const labelWidth = Math.min(Math.min(options.labelWidth ?? 24, Math.max(...shown.map((i) => displayWidth(i.label)), 4)) + 2, Math.max(10, iw - 12));
        const rows = shown.map((item, index) => {
          const focused = index === focus;
          const isCurrent = options.current !== undefined && item.value === options.current;
          const mark = focused ? palette.accent('❯') : ' ';
          const dot = item.pinned ? palette.accent('+') : isCurrent ? palette.accent('●') : palette.faint('○');
          const label = focused ? palette.bold(palette.text(truncateWidth(item.label, labelWidth - 1))) : palette.muted(truncateWidth(item.label, labelWidth - 1));
          const prefix = ` ${mark} ${dot} ${padEndWidth(label, labelWidth)}`;
          const room = Math.max(0, iw - displayWidth(prefix) - 1);
          const detail = item.detail && room > 6 ? palette.faint(truncateWidth(item.detail, room)) : '';
          return { text: prefix + detail, index };
        });
        for (const r of windowItems(rows, focus, maxItems, iw)) lines.push(box(r.text, r.index === focus));
      }
      const notes = (options.notes ?? []).flatMap((n) => wrapWords(n, iw - 3));
      if (notes.length > 0) {
        lines.push(box(''));
        for (const line of notes) lines.push(box('  ' + palette.faint(line)));
      }
      return lines;
    },
    hintLine(cols - 2, true),
  );
}

/** What a key does to a text that is being typed: `undefined` when the key is not an edit. */
export function editText(value: string, key: string): string | undefined {
  if (key === '\x7f' || key === '\b') return Array.from(value).slice(0, -1).join('');
  if (key === WORD_BACKSPACE) return value.replace(/\s*\S*$/, '');
  if (key === '\x15') return '';                                  // Ctrl+U
  if (/^[^\x00-\x1f\x7f\x1b]+$/.test(key)) return value + key;     // printable text, also a pasted chunk
  return undefined;
}

function openIO(io: PickerIO) {
  const { input } = io;
  const wasRaw = !!input.isRaw;
  input.setRawMode?.(true);
  input.resume();
  return () => { if (!wasRaw) input.setRawMode?.(false); };
}

/** The list narrowed by typing. Resolves with the chosen value, or undefined when cancelled. */
export function runFinder<T>(options: FinderOptions<T>, io: PickerIO = { input: process.stdin, output: process.stdout }): Promise<T | undefined> {
  return new Promise((resolve) => {
    const { input, output } = io;
    const block = new LiveBlock(output);
    let query = '';
    let shown = filterItems(options.items, query);
    const at = shown.findIndex((i) => options.current !== undefined && i.value === options.current);
    let focus = at >= 0 ? at : firstMatch(shown);
    let done = false;
    const rows = () => (output as any).rows || 40;
    const draw = () => block.draw(renderFinder(options, query, shown, focus, output.columns || 80, rows()));
    const restore = openIO(io);

    const finish = (value: T | undefined) => {
      done = true;
      input.off('data', onData);
      block.erase();
      restore();
      resolve(value);
    };
    const move = (delta: number) => {
      const next = Math.max(0, Math.min(shown.length - 1, focus + delta));
      if (next !== focus) { focus = next; draw(); }
    };
    const handleKey = (raw: string) => {
      const key = normalizeKey(raw);
      if (key === '\x1b[A' || key === '\x1bOA') return move(-1);
      if (key === '\x1b[B' || key === '\x1bOB') return move(1);
      if (key === '\x1b[5~') return move(-8);
      if (key === '\x1b[6~') return move(8);
      if (key === '\r' || key === '\n') return shown.length > 0 ? finish(shown[focus].value) : undefined;
      if (key === '\x1b' || key === '\x03') return finish(undefined);
      const edited = editText(query, key);
      if (edited !== undefined && edited !== query) {
        query = edited;
        shown = filterItems(options.items, query);
        focus = firstMatch(shown);
        draw();
      }
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

export interface TextInputOptions {
  title: string;
  subtitle?: string;
  /** What is asked, above the field. */
  label: string;
  initial?: string;
  /** Shown while the field is empty. */
  placeholder?: string;
  /** Typed characters are drawn as dots (a key). */
  secret?: boolean;
  notes?: string[];
}

export function renderTextInput(options: TextInputOptions, value: string, cols: number): string[] {
  return frame(
    options.title,
    options.subtitle,
    cols,
    (iw, box) => {
      const lines: string[] = [];
      for (const line of wrapWords(options.label, iw - 2)) lines.push(box(' ' + palette.bold(palette.text(line))));
      const shownValue = options.secret ? '•'.repeat(Array.from(value).length) : value;
      const room = Math.max(4, iw - 6);
      const text = value
        ? palette.text(tailWidth(shownValue, room)) + palette.accent('█')
        : palette.accent('█') + palette.faint(truncateWidth(options.placeholder ?? '', room));
      lines.push(box(''));
      lines.push(box(' ' + palette.accent('❯') + ' ' + text, true));
      const notes = (options.notes ?? []).flatMap((n) => wrapWords(n, iw - 3));
      if (notes.length > 0) {
        lines.push(box(''));
        for (const line of notes) lines.push(box('  ' + palette.faint(line)));
      }
      return lines;
    },
    hintLine(cols - 2, false),
  );
}

/** A one-line input. Resolves with the text (never empty), or undefined when cancelled. */
export function runTextInput(options: TextInputOptions, io: PickerIO = { input: process.stdin, output: process.stdout }): Promise<string | undefined> {
  return new Promise((resolve) => {
    const { input, output } = io;
    const block = new LiveBlock(output);
    let value = options.initial ?? '';
    let done = false;
    const draw = () => block.draw(renderTextInput(options, value, output.columns || 80));
    const restore = openIO(io);

    const finish = (result: string | undefined) => {
      done = true;
      input.off('data', onData);
      block.erase();
      restore();
      resolve(result);
    };
    const handleKey = (raw: string) => {
      const key = normalizeKey(raw);
      if (key === '\r' || key === '\n') return value.trim() ? finish(value.trim()) : undefined;
      if (key === '\x1b' || key === '\x03') return finish(undefined);
      const edited = editText(value, key);
      if (edited !== undefined && edited !== value) { value = edited; draw(); }
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
