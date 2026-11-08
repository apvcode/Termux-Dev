import { describe, expect, it } from 'vitest';
import {
  rowsToTop,
  displayWidth,
  padEndWidth,
  padStartWidth,
  sliceByWidth,
  stripAnsi,
  truncateWidth,
  wrapKeepSpaces,
  wrapText,
  wrapWidth,
} from '../../src/cli/width.js';

describe('displayWidth', () => {
  it('counts cells, not UTF-16 units', () => {
    expect(displayWidth('hello')).toBe(5);
    expect(displayWidth('привет')).toBe(6);        // Cyrillic is one cell per letter
    expect(displayWidth('日本語')).toBe(6);          // CJK: two cells each
    expect(displayWidth('\u{1F600}')).toBe(2);            // emoji: two cells, but length 2
    expect(displayWidth('a\u{1F600}b')).toBe(4);
    expect(displayWidth('é')).toBe(1);       // combining accent takes no cell
    expect(displayWidth('a​b')).toBe(2);      // zero width space
  });

  it('ignores ANSI colour sequences', () => {
    expect(displayWidth('\x1b[31mred\x1b[39m')).toBe(3);
    expect(displayWidth('\x1b[38;5;240m│\x1b[39m')).toBe(1);
    expect(stripAnsi('\x1b[1mbold\x1b[22m plain')).toBe('bold plain');
  });
});

describe('truncateWidth / padEndWidth / padStartWidth', () => {
  it('never exceeds the requested width and never splits a wide character', () => {
    expect(truncateWidth('hello world', 5)).toBe('hell…');
    expect(truncateWidth('hello', 5)).toBe('hello');
    expect(truncateWidth('日本語テキスト', 7)).toBe('日本語…');
    expect(displayWidth(truncateWidth('日本語テキスト', 6))).toBeLessThanOrEqual(6);
    expect(truncateWidth('abc', 0)).toBe('');
    expect(displayWidth(truncateWidth('a\u{1F600}b\u{1F600}c\u{1F600}d', 4))).toBeLessThanOrEqual(4);
  });

  it('pads by cells and leaves colour codes alone', () => {
    expect(padEndWidth('ab', 5)).toBe('ab   ');
    expect(padEndWidth('日本', 6)).toBe('日本  ');
    expect(padStartWidth('ab', 5)).toBe('   ab');
    expect(displayWidth(padEndWidth('\x1b[31mab\x1b[39m', 6))).toBe(6);
    expect(padEndWidth('toolong', 3)).toBe('toolong');
  });
});

describe('sliceByWidth / wrapWidth', () => {
  it('slices from a character index within a cell budget', () => {
    expect(sliceByWidth('abcdef', 2, 3)).toEqual({ text: 'cde', length: 3, cells: 3 });
    const wide = sliceByWidth('a日本b', 1, 3);
    expect(wide.text).toBe('日');            // the next wide char would need 2 more cells
    expect(wide.cells).toBe(2);
  });

  it('wraps by cells and keeps explicit newlines', () => {
    expect(wrapWidth('abcdefgh', 3)).toEqual(['abc', 'def', 'gh']);
    expect(wrapWidth('日本語', 4)).toEqual(['日本', '語']);
    expect(wrapWidth('a\n\nb', 10)).toEqual(['a', '', 'b']);
    for (const line of wrapWidth('привет \u{1F600} мир, это длинная строка', 8)) {
      expect(displayWidth(line)).toBeLessThanOrEqual(8);
    }
  });
});

describe('rowsToTop', () => {
  // A frame of 3 lines drawn at 100 columns, the cursor on the middle line at column 4.
  const widths = [100, 100, 100];

  it('is the number of lines above the cursor while the width is the same', () => {
    expect(rowsToTop(widths, 1, 4, 100)).toBe(1);
    expect(rowsToTop(widths, 2, 4, 100)).toBe(2);
    expect(rowsToTop(widths, 0, 4, 100)).toBe(0);
  });

  it('counts the rows the terminal made of lines it had to re-wrap in a narrower window', () => {
    // 100 columns -> 40: every line takes 3 rows
    expect(rowsToTop(widths, 1, 4, 40)).toBe(3);
    expect(rowsToTop(widths, 2, 4, 40)).toBe(6);
    // 100 -> 60: two rows each
    expect(rowsToTop(widths, 2, 4, 60)).toBe(4);
  });

  it('adds the rows of a cursor that now sits on a wrapped part of its own line', () => {
    expect(rowsToTop(widths, 1, 90, 40)).toBe(3 + 2);
  });

  it('does not change when the window gets wider (lines stay one row)', () => {
    expect(rowsToTop(widths, 2, 4, 200)).toBe(2);
    expect(rowsToTop([10, 0, 5], 2, 0, 80)).toBe(2); // an empty line is still a row
  });

  it('is safe with nothing drawn or a zero width', () => {
    expect(rowsToTop([], 0, 0, 80)).toBe(0);
    expect(rowsToTop(widths, 1, 0, 0)).toBeGreaterThanOrEqual(0);
  });
});


describe('wrapText: words are not torn apart', () => {
  it('breaks at spaces', () => {
    expect(wrapText('привет мир как дела', 10)).toEqual(['привет мир', 'как дела']);
  });

  it('cuts only the word that is longer than the line, and the words around it stay whole', () => {
    const url = 'https://example.com/a/very/long/path/that/does/not/fit';
    const rows = wrapText(`привет мир ${url} и ещё слова`, 20);
    expect(rows.every((r) => displayWidth(r) <= 20)).toBe(true);
    expect(rows[0]).toBe('привет мир');
    // The pieces of the url put together give the url back, and the short words are all there, whole.
    const flat = rows.join(' ');
    for (const w of ['привет', 'мир', 'и', 'ещё', 'слова']) expect(flat.split(' ')).toContain(w);
    expect(rows.slice(1).join('').startsWith(url)).toBe(true);
  });

  it('still wraps text without spaces anywhere (Chinese, Japanese)', () => {
    expect(wrapText('日本語日本語', 8)).toEqual(['日本語日', '本語']);
  });
});

describe('wrapKeepSpaces', () => {
  it('never cuts a word that fits on a line (проект is not про / ект)', () => {
    const rows = wrapKeepSpaces('ознакомься с проектом привет', 20);
    expect(rows).toEqual(['ознакомься с', 'проектом привет']);
    for (const r of wrapKeepSpaces('привет '.repeat(20).trim(), 17)) expect(r).toMatch(/^(привет ?)+$/);
  });

  it('keeps explicit newlines, blank lines, indentation and inner spacing', () => {
    expect(wrapKeepSpaces('a\n\n  b   c', 20)).toEqual(['a', '', '  b   c']);
  });

  it('drops the space where a line is broken, so no row starts or ends with one', () => {
    for (const r of wrapKeepSpaces('one two three four five six seven eight nine ten', 11)) {
      expect(r).toBe(r.trim());
      expect(displayWidth(r)).toBeLessThanOrEqual(11);
    }
  });

  it('puts a word that is too long for a line on its own, cut by cells, and goes on after it', () => {
    const rows = wrapKeepSpaces(`see ${'x'.repeat(25)} now`, 10);
    expect(rows).toEqual(['see', 'xxxxxxxxxx', 'xxxxxxxxxx', 'xxxxx now']);
  });

  it('counts wide characters as two cells and loses no character', () => {
    const text = 'привет \u{1F600} мир, 日本語日本語日本語 конец';
    const rows = wrapKeepSpaces(text, 9);
    expect(rows.every((r) => displayWidth(r) <= 9)).toBe(true);
    expect(rows.join('').replace(/\s/g, '')).toBe(text.replace(/\s/g, ''));
  });
});
