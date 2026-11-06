// One terminal "key": a full escape sequence (arrows, Home, Delete...), Alt+Enter, Alt+Backspace, a lone Esc,
// or a single character. The `u` flag keeps characters outside the BMP in one piece.
const KEY = /\x1b\[[0-9;:?]*[ -/]*[@-~]|\x1bO[\s\S]|\x1b[\r\n\x7f]|\x1b|[\s\S]/gu;

/**
 * Splits raw input into separate key presses.
 *
 * A terminal may deliver several keys in one chunk (holding an arrow key, a fast typist, a
 * slow connection, Termux batching input), for example "\x1b[B\x1b[B\x1b[B" for three
 * presses of Down. Code that compares a whole chunk with one key would miss them all.
 */
export function splitKeys(chunk: string): string[] {
  return chunk.match(KEY) ?? [];
}

/** Erases the word before the cursor (Ctrl+W in a shell); Ctrl+Backspace and Alt+Backspace are mapped to it. */
export const WORD_BACKSPACE = '\x17';

/** What a terminal sends for Ctrl+Enter once the extended keyboard modes below are on. */
export const CTRL_ENTER = '\x1b[13;5u';

/**
 * Asks the terminal to tell Ctrl+Enter apart from Enter (a plain terminal sends the same
 * byte for both). Two protocols are requested, a terminal answers the one it knows:
 * the kitty keyboard protocol (kitty, foot, ghostty, wezterm, alacritty, ...) and xterm's
 * modifyOtherKeys. Terminals that know neither ignore both, and Alt+Enter still works.
 * While on, the keys arrive as `CSI … u`: run them through `normalizeKey`.
 */
export const EXTENDED_KEYS_ON = '\x1b[>1u\x1b[>4;1m';
export const EXTENDED_KEYS_OFF = '\x1b[<u\x1b[>4;0m';

/**
 * Turns the extended encodings back into the keys the rest of the code knows:
 * `CSI 99;5u` and `CSI 27;5;99~` become Ctrl+C (0x03), `CSI 27u` becomes Esc, and every way
 * of sending Ctrl+Enter or Alt+Enter becomes `CTRL_ENTER`. Anything else is returned as is.
 */
export function normalizeKey(key: string): string {
  if (key === '\x1b\r' || key === '\x1b\n') return CTRL_ENTER;
  if (key === '\x1b\x7f') return WORD_BACKSPACE;      // Alt+Backspace
  const m = /^\x1b\[(?:27;(\d+);(\d+)~|(\d+)(?:;(\d+))?(?::\d+)?u)$/.exec(key);
  if (!m) return key;
  // modifyOtherKeys is `27;mods;code`, the kitty protocol is `code;mods`.
  const code = Number(m[2] ?? m[3]);
  const mods = Math.max(0, Number(m[1] ?? m[4] ?? 1) - 1);
  const shift = (mods & 1) !== 0;
  const alt = (mods & 2) !== 0;
  const ctrl = (mods & 4) !== 0;

  // Ctrl+Backspace and Alt+Backspace: erase a word.
  if ((code === 127 || code === 8) && (ctrl || alt)) return WORD_BACKSPACE;
  if (code === 13 || code === 10) return ctrl || alt ? CTRL_ENTER : shift ? key : '\r';
  if (alt) return key;
  if (ctrl) {
    if (code >= 97 && code <= 122) return String.fromCharCode(code - 96);
    if (code >= 65 && code <= 90) return String.fromCharCode(code - 64);
    return key;
  }
  if (code === 27) return '\x1b';
  if (code === 9) return '\t';
  if (code === 127 || code === 8) return String.fromCharCode(code);
  if (code >= 32 && code !== 127 && !shift) return String.fromCodePoint(code);
  return key;
}
