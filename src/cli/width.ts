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

/** Cuts a word into pieces of at most `width` cells, never splitting a character. */
export function splitWord(word: string, width: number): string[] {
  if (width <= 0) return [word];
  const pieces: string[] = [];
  let current = '';
  let cells = 0;
  for (const ch of word) {
    const w = codePointWidth(ch.codePointAt(0)!);
    if (cells + w > width && current !== '') {
      pieces.push(current);
      current = '';
      cells = 0;
    }
    current += ch;
    cells += w;
  }
  if (current !== '') pieces.push(current);
  return pieces;
}

/**
 * Words are put on lines one after another. A word longer than a whole line is dealt with by
 * `cutLong`: false shortens it with an ellipsis, true cuts it into pieces (the text after it goes on
 * right behind its last piece). A word that fits on a line is never cut.
 */
function wrapWordList(text: string, width: number, cutLong: boolean): string[] {
  const lines: string[] = [];
  let current = '';
  for (const word of text.split(/\s+/).filter(Boolean)) {
    if (displayWidth(word) > width) {
      if (current) lines.push(current);
      if (cutLong) {
        const pieces = splitWord(word, width);
        lines.push(...pieces.slice(0, -1));
        current = pieces[pieces.length - 1] ?? '';
      } else {
        lines.push(truncateWidth(word, width));
        current = '';
      }
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

/** Wraps text at word boundaries (a word longer than the line is cut). */
export function wrapWords(text: string, width: number): string[] {
  return wrapWordList(text, width, false);
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

/**
 * Wraps at word boundaries. Only a word that is longer than the line is cut (Chinese and Japanese
 * have no spaces, so a whole sentence is such a word); the words around it stay whole.
 */
export function wrapText(text: string, width: number): string[] {
  return wrapWordList(text, width, true);
}

/**
 * Wraps text for showing it back (the message of the user): explicit newlines, blank lines, the
 * indentation and the spaces inside a line stay as they were. A line is broken at a space, where the
 * space is dropped; a word longer than a whole line goes on a row of its own and is cut by cells.
 */
export function wrapKeepSpaces(text: string, width: number): string[] {
  const rows: string[] = [];
  for (const raw of text.split(/\r\n|\r|\n/)) {
    const line = raw.replace(/\s+$/, '');
    if (line === '') {
      rows.push('');
      continue;
    }
    let current = '';
    let cells = 0;
    let gap = '';
    for (const token of line.match(/\S+|\s+/g) ?? []) {
      if (/^\s/.test(token)) {
        // Indentation stays; the space between two words waits to see whether the next word fits.
        if (current === '') {
          current = token;
          cells = displayWidth(token);
        } else gap = token;
        continue;
      }
      const w = displayWidth(token);
      const gapCells = displayWidth(gap);
      if (current.trim() !== '' && cells + gapCells + w <= width) {
        current += gap + token;
        cells += gapCells + w;
      } else if (current.trim() === '' && cells + w <= width) {
        current += token;
        cells += w;
      } else if (w <= width) {
        if (current.trim() !== '') rows.push(current);
        current = token;
        cells = w;
      } else {
        if (current.trim() !== '') rows.push(current);
        const pieces = splitWord(token, width);
        rows.push(...pieces.slice(0, -1));
        current = pieces[pieces.length - 1];
        cells = displayWidth(current);
      }
      gap = '';
    }
    rows.push(current);
  }
  return rows;
}

/**
 * How many rows up the top of a block is from the cursor, once the terminal is `cols` wide.
 *
 * A block drawn at one width is re-wrapped by the terminal when the window is resized: a line that
 * was as wide as the old window now takes several rows, so counting the lines of the block is not
 * enough to get back to its top (the top stays on screen and the block shows up twice).
 *
 * @param lineWidths visible width of each line of the block as it was drawn
 * @param cursorRow  the line the cursor is on
 * @param cursorCol  the column of the cursor in that line
 */
export function rowsToTop(lineWidths: number[], cursorRow: number, cursorCol: number, cols: number): number {
  const width = Math.max(1, cols);
  let rows = 0;
  for (let i = 0; i < cursorRow && i < lineWidths.length; i++) rows += Math.max(1, Math.ceil(lineWidths[i] / width));
  return rows + Math.floor(Math.max(0, cursorCol) / width);
}

/**
 * The column (0-based, in cells) the cursor is in after `text` is written starting at column `col`
 * of a terminal `cols` wide. Escape sequences take no room; a line break and a carriage return go
 * to column 0; a character that does not fit wraps to the next row. A cursor that has just filled
 * the last column is reported as `cols` (the terminal holds it there until the next character).
 */
export function columnAfter(col: number, text: string, cols: number): number {
  const width = Math.max(1, cols);
  let at = Math.max(0, Math.min(col, width));
  const plain = text.replace(ANSI, '').replace(/\x1b[0-9A-Za-z=>]/g, '');
  for (const ch of plain) {
    if (ch === '\n' || ch === '\r') { at = 0; continue; }
    if (ch === '\b') { at = Math.max(0, at - 1); continue; }
    if (ch === '\t') { at = Math.min(width - 1, (Math.floor(at / 8) + 1) * 8); continue; }
    const w = codePointWidth(ch.codePointAt(0)!);
    if (w === 0) continue;
    if (at + w > width) at = 0;
    at += w;
  }
  return at;
}
