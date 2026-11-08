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

/**
 * What a terminal sends for Ctrl+Enter once the extended keyboard modes below are on. Ctrl+Enter
 * and Alt+Enter break the line (a new paragraph) instead of sending it, like Shift+Enter.
 */
export const CTRL_ENTER = '\x1b[13;5u';

/** Shift+Enter in the two extended encodings (a plain terminal sends the same byte as for Enter). */
const SHIFT_ENTER = /^\x1b\[(?:13;2u|27;2;13~)$/;

/** Does this key break the line instead of sending it? Ctrl+Enter, Alt+Enter and Shift+Enter, as far as the terminal tells them from Enter. */
export function isNewlineKey(key: string): boolean {
  return normalizeKey(key) === CTRL_ENTER || SHIFT_ENTER.test(key);
}

/**
 * Asks the terminal to tell Ctrl+Enter and Shift+Enter apart from Enter (a plain terminal sends
 * the same byte for all of them). Two protocols are requested, a terminal answers the one it knows:
 * the kitty keyboard protocol (kitty, foot, ghostty, wezterm, alacritty, ...) and xterm's
 * modifyOtherKeys. While on, the keys arrive as `CSI … u`: run them through `normalizeKey`.
 *
 * Only sent to terminals that are known to understand them (`extendedKeysSupported`): a terminal
 * that does not parses `CSI > 1 u` as "restore the cursor" and `CSI > 4 ; 1 m` as "underline, bold",
 * which throws the prompt to the top of the screen and draws lines under every row (Termux, UserLAnd).
 */
const EXTENDED_KEYS_ON_SEQ = '\x1b[>1u\x1b[>4;1m';
const EXTENDED_KEYS_OFF_SEQ = '\x1b[<u\x1b[>4;0m';

const KEYBOARD_PROTOCOL_PROGRAMS = new Set(['ghostty', 'wezterm', 'iterm.app', 'kitty', 'foot', 'alacritty', 'rio', 'contour']);

/**
 * Does this terminal understand the extended keyboard modes? Decided from the environment, and
 * only for terminals known to: a wrong guess in the other direction costs Ctrl+Enter (Alt+Enter
 * and a backslash before Enter still break the line), a wrong guess in this one wrecks the screen.
 * `DEVX_EXTENDED_KEYS=1` forces it on, `DEVX_EXTENDED_KEYS=0` off.
 */
export function extendedKeysSupported(env: NodeJS.ProcessEnv = process.env, platform: string = process.platform): boolean {
  const forced = (env.DEVX_EXTENDED_KEYS ?? '').trim().toLowerCase();
  if (['1', 'on', 'true', 'yes'].includes(forced)) return true;
  if (['0', 'off', 'false', 'no'].includes(forced)) return false;
  // Android: Termux, UserLAnd and other terminals built on the same emulator.
  if (platform === 'android' || env.TERMUX_VERSION || env.PREFIX?.includes('com.termux') || env.ANDROID_ROOT || env.ANDROID_DATA) return false;
  // Multiplexers sit between devx and the real terminal: they decide for themselves.
  if (env.TMUX || env.STY) return false;
  const term = (env.TERM ?? '').toLowerCase();
  if (term.startsWith('screen') || term.startsWith('tmux') || term === 'linux' || term === 'dumb') return false;
  if (env.KITTY_WINDOW_ID || env.GHOSTTY_RESOURCES_DIR || env.WEZTERM_EXECUTABLE) return true;
  if (/kitty|ghostty|foot|alacritty|wezterm/.test(term)) return true;
  return KEYBOARD_PROTOCOL_PROGRAMS.has((env.TERM_PROGRAM ?? '').toLowerCase());
}

/** What to write to turn the extended keyboard modes on (nothing on a terminal that does not know them). */
export function extendedKeysOn(): string {
  return extendedKeysSupported() ? EXTENDED_KEYS_ON_SEQ : '';
}

/** What to write to turn them off again. */
export function extendedKeysOff(): string {
  return extendedKeysSupported() ? EXTENDED_KEYS_OFF_SEQ : '';
}

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

/**
 * The key that breaks the line, as it is written in a hint. Shift+Enter where the terminal tells it from
 * Enter; elsewhere (Termux, a multiplexer) a backslash before Enter, which works in every terminal.
 */
export function newlineKeyLabel(env: NodeJS.ProcessEnv = process.env, platform: string = process.platform): string {
  return extendedKeysSupported(env, platform) ? 'shift+enter' : '\\ enter';
}
