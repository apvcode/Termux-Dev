/**
 * Terminal display width helpers.
 *
 * `string.length` counts UTF-16 units, but a terminal counts cells: CJK characters and
 * emoji take two cells, combining marks take none. Anything drawn inside a box
 * (borders, padding, right-aligned labels) must be measured in cells, otherwise a single
 * emoji in the input shifts the right border and wraps the whole line.
 */

const ANSI = /\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)/g;

export function stripAnsi(text: string): string {
  return text.replace(ANSI, '');
}

/** Width in terminal cells of a single code point. */
export function codePointWidth(cp: number): number {
  if (cp === 0) return 0;
  if (cp < 32 || (cp >= 0x7f && cp < 0xa0)) return 0; // control characters
  if (
    (cp >= 0x0300 && cp <= 0x036f) || // combining diacritics
    (cp >= 0x200b && cp <= 0x200f) || // zero width space / joiners / marks
    (cp >= 0x2060 && cp <= 0x2064) ||
    (cp >= 0xfe00 && cp <= 0xfe0f) || // variation selectors
    cp === 0xfeff ||
    (cp >= 0xe0100 && cp <= 0xe01ef)
  ) {
    return 0;
  }
  if (
    (cp >= 0x1100 && cp <= 0x115f) ||                    // Hangul Jamo
    (cp >= 0x2e80 && cp <= 0x303e) ||                    // CJK radicals, punctuation
    (cp >= 0x3041 && cp <= 0x33ff) ||                    // Hiragana, Katakana, CJK symbols
    (cp >= 0x3400 && cp <= 0x4dbf) ||                    // CJK extension A
    (cp >= 0x4e00 && cp <= 0xa4cf) ||                    // CJK unified, Yi
    (cp >= 0xa960 && cp <= 0xa97f) ||
    (cp >= 0xac00 && cp <= 0xd7a3) ||                    // Hangul syllables
    (cp >= 0xf900 && cp <= 0xfaff) ||                    // CJK compatibility
    (cp >= 0xfe30 && cp <= 0xfe6f) ||
    (cp >= 0xff00 && cp <= 0xff60) ||                    // fullwidth forms
    (cp >= 0xffe0 && cp <= 0xffe6) ||
    (cp >= 0x1f300 && cp <= 0x1f64f) ||                  // emoji: symbols, emoticons
    (cp >= 0x1f680 && cp <= 0x1f6ff) ||                  // transport
    (cp >= 0x1f900 && cp <= 0x1f9ff) ||                  // supplemental symbols
    (cp >= 0x1fa70 && cp <= 0x1faff) ||
    (cp >= 0x20000 && cp <= 0x3fffd)                     // CJK extension B+
  ) {
    return 2;
  }
  return 1;
}

/** Display width of a string in terminal cells (ANSI escape sequences are ignored). */
export function displayWidth(text: string): number {
  let width = 0;
  for (const ch of stripAnsi(text)) {
    width += codePointWidth(ch.codePointAt(0)!);
  }
  return width;
}

/**
 * Cuts plain text (no ANSI) to at most `max` cells, never splitting a character.
 * When it has to cut, the result ends with an ellipsis and still fits in `max` cells.
 */
export function truncateWidth(text: string, max: number): string {
  if (max <= 0) return '';
  if (displayWidth(text) <= max) return text;
  const budget = max - 1; // room for the ellipsis
  let out = '';
  let width = 0;
  for (const ch of text) {
    const w = codePointWidth(ch.codePointAt(0)!);
    if (width + w > budget) break;
    out += ch;
    width += w;
  }
  return out + '…';
}

/** Pads a (possibly colored) string with spaces on the right up to `width` cells. */
export function padEndWidth(text: string, width: number): string {
  const missing = width - displayWidth(text);
  return missing > 0 ? text + ' '.repeat(missing) : text;
}

/** Pads a (possibly colored) string with spaces on the left up to `width` cells. */
export function padStartWidth(text: string, width: number): string {
  const missing = width - displayWidth(text);
  return missing > 0 ? ' '.repeat(missing) + text : text;
}

/**
 * Returns the slice of plain `text` that starts at character index `start` and fits
 * into `maxCells`, together with how many characters were taken.
 */
export function sliceByWidth(text: string, start: number, maxCells: number): { text: string; length: number; cells: number } {
  let out = '';
  let cells = 0;
  let length = 0;
  let index = 0;
  for (const ch of text) {
    const units = ch.length;
    if (index >= start) {
      const w = codePointWidth(ch.codePointAt(0)!);
      if (cells + w > maxCells) break;
      out += ch;
      cells += w;
      length += units;
    }
    index += units;
  }
  return { text: out, length, cells };
}

/** Hard-wraps plain text to lines of at most `width` cells (explicit newlines are kept). */
export function wrapWidth(text: string, width: number): string[] {
  const lines: string[] = [];
  for (const rawLine of text.split(/\r\n|\r|\n/)) {
    if (rawLine === '') {
      lines.push('');
      continue;
    }
    let current = '';
    let cells = 0;
    for (const ch of rawLine) {
      const w = codePointWidth(ch.codePointAt(0)!);
      if (cells + w > width && current !== '') {
        lines.push(current);
        current = '';
        cells = 0;
      }
      current += ch;
      cells += w;
    }
    lines.push(current);
  }
  return lines;
}

/** Wraps text at word boundaries (a word longer than the line is cut). */
export function wrapWords(text: string, width: number): string[] {
  const lines: string[] = [];
  let current = '';
  for (const word of text.split(/\s+/).filter(Boolean)) {
    if (displayWidth(word) > width) {
      if (current) lines.push(current);
      lines.push(truncateWidth(word, width));
      current = '';
    } else if (current === '') {
      current = word;
    } else if (displayWidth(current) + 1 + displayWidth(word) <= width) {
      current += ' ' + word;
    } else {
      lines.push(current);
      current = word;
    }
  }
  if (current) lines.push(current);
  return lines;
}

/** The last `maxCells` cells of a text (what a running line shows: the newest words). */
export function tailWidth(text: string, maxCells: number): string {
  const chars = Array.from(text);
  let cells = 0;
  let start = chars.length;
  while (start > 0) {
    const w = codePointWidth(chars[start - 1].codePointAt(0)!);
    if (cells + w > maxCells) break;
    cells += w;
    start--;
  }
  return chars.slice(start).join('');
}

/** Wraps at word boundaries; text without spaces (Chinese, Japanese) is wrapped anywhere. */
export function wrapText(text: string, width: number): string[] {
  const unbreakable = text.split(/\s+/).some((word) => displayWidth(word) > width);
  return unbreakable ? wrapWidth(text, width) : wrapWords(text, width);
}
