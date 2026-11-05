import { describe, expect, it } from 'vitest';
import {
  displayWidth,
  padEndWidth,
  padStartWidth,
  sliceByWidth,
  stripAnsi,
  truncateWidth,
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
