import { describe, expect, it } from 'vitest';
import { extendedKeysSupported, splitKeys } from '../../src/cli/keys.js';

describe('splitKeys', () => {
  it('returns a single key unchanged', () => {
    expect(splitKeys('a')).toEqual(['a']);
    expect(splitKeys('\r')).toEqual(['\r']);
    expect(splitKeys('\x1b[B')).toEqual(['\x1b[B']);
    expect(splitKeys('\x1b')).toEqual(['\x1b']);
    expect(splitKeys('')).toEqual([]);
  });

  it('separates keys that arrive together (key repeat, batched input)', () => {
    expect(splitKeys('\x1b[B\x1b[B\x1b[B')).toEqual(['\x1b[B', '\x1b[B', '\x1b[B']);
    expect(splitKeys('jjk')).toEqual(['j', 'j', 'k']);
    expect(splitKeys('\x1b[A\x1b[B\r')).toEqual(['\x1b[A', '\x1b[B', '\r']);
  });

  it('knows the common escape sequences', () => {
    expect(splitKeys('\x1b[C\x1b[D')).toEqual(['\x1b[C', '\x1b[D']);
    expect(splitKeys('\x1bOA\x1bOB')).toEqual(['\x1bOA', '\x1bOB']);   // application cursor mode
    expect(splitKeys('\x1b[H\x1b[F')).toEqual(['\x1b[H', '\x1b[F']);
    expect(splitKeys('\x1b[3~\x1b[1;5C')).toEqual(['\x1b[3~', '\x1b[1;5C']); // Delete, Ctrl+Right
  });

  it('keeps a lone Esc apart from the keys around it', () => {
    expect(splitKeys('q\x1b')).toEqual(['q', '\x1b']);
    expect(splitKeys('\x1b\x1b[B')).toEqual(['\x1b', '\x1b[B']);
  });

  it('does not split characters outside the BMP', () => {
    expect(splitKeys('a\u{1F600}b')).toEqual(['a', '\u{1F600}', 'b']);
    expect(splitKeys('привет')).toHaveLength(6);
  });
});

describe('extendedKeysSupported', () => {
  it('is on for terminals that know the keyboard protocols', () => {
    expect(extendedKeysSupported({ TERM: 'xterm-kitty' }, 'linux')).toBe(true);
    expect(extendedKeysSupported({ KITTY_WINDOW_ID: '4', TERM: 'xterm-256color' }, 'linux')).toBe(true);
    expect(extendedKeysSupported({ TERM_PROGRAM: 'WezTerm', TERM: 'xterm-256color' }, 'darwin')).toBe(true);
    expect(extendedKeysSupported({ TERM_PROGRAM: 'ghostty' }, 'darwin')).toBe(true);
    expect(extendedKeysSupported({ TERM_PROGRAM: 'iTerm.app' }, 'darwin')).toBe(true);
    expect(extendedKeysSupported({ TERM: 'alacritty' }, 'linux')).toBe(true);
  });

  it('is off for Termux, UserLAnd (Android) and for terminals nobody knows', () => {
    expect(extendedKeysSupported({ PREFIX: '/data/data/com.termux/files/usr', TERM: 'xterm-256color' }, 'linux')).toBe(false);
    expect(extendedKeysSupported({ TERMUX_VERSION: '0.118.0' }, 'linux')).toBe(false);
    expect(extendedKeysSupported({ TERM: 'xterm-256color' }, 'android')).toBe(false);
    expect(extendedKeysSupported({ ANDROID_ROOT: '/system', TERM: 'xterm-kitty' }, 'linux')).toBe(false);
    expect(extendedKeysSupported({ TERM: 'xterm-256color' }, 'linux')).toBe(false);
    expect(extendedKeysSupported({}, 'linux')).toBe(false);
    expect(extendedKeysSupported({ TERM: 'screen-256color' }, 'linux')).toBe(false);
    expect(extendedKeysSupported({ TMUX: '/tmp/tmux-1000/default,1,0', TERM_PROGRAM: 'ghostty' }, 'linux')).toBe(false);
  });

  it('DEVX_EXTENDED_KEYS forces it either way', () => {
    expect(extendedKeysSupported({ DEVX_EXTENDED_KEYS: '1', PREFIX: '/data/data/com.termux/files/usr' }, 'linux')).toBe(true);
    expect(extendedKeysSupported({ DEVX_EXTENDED_KEYS: '0', TERM: 'xterm-kitty' }, 'linux')).toBe(false);
  });
});
