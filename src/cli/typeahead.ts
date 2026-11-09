import fs from 'fs';
import os from 'os';
import path from 'path';
import { extendedKeysOff, extendedKeysOn, isNewlineKey, newlineKeyLabel, normalizeKey, splitKeys } from './keys.js';
import { FrameInput, buildPromptFrame, palette } from './ui.js';
import { columnAfter, displayWidth, truncateWidth } from './width.js';
import { renderDropdown } from './dropdown.js';
import { CommandItem, filterCommandPalette } from './prompt_palette.js';
import { PromptHistoryNavigator } from './history_search.js';
import { promptHistory, recordPrompt } from './prompt_history.js';
import { pathExists, startsWithPath } from './slash.js';
import { t } from '../core/i18n.js';

/**
 * Typing while the agent works.
 *
 * During a turn the prompt frame (the same one the prompt uses: mode, model, folder, context,
 * cost) stays right under the last line of the output and follows it down as the output grows,
 * instead of jumping to the bottom of the screen. A message typed there is queued and handed
 * to the agent at the next step.
 *
 * Editing is a pure function (`applyKey`), drawing is `renderComposer`, and `TurnComposer`
 * adds the terminal handling around them.
 */

export interface DraftState {
  text: string;
  /** Position in code points, 0..length. */
  cursor: number;
}

export interface KeyResult {
  state: DraftState;
  /** The text to queue when Enter was pressed on a non-empty line. */
  submit?: string;
}

/** The down arrow in every form a terminal may send it: `ESC [ B`, `ESC O B` (application mode), `ESC [ 1 ; mods B` (with modifier fields). */
const DOWN_ARROW = /^\x1b(?:\[|O)(?:1;[\d:]+)?B$/;
const UP_ARROW = /^\x1b(?:\[|O)(?:1;[\d:]+)?A$/;

/** DEVX_DEBUG_KEYS=1: what the terminal sends is written to ~/.devx/keys.log, to find out why a key does nothing. */
function logKeys(chunk: string, queued: number, draft: string): void {
  try {
    const file = path.join(os.homedir(), '.devx', 'keys.log');
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.appendFileSync(file, JSON.stringify({ keys: chunk, queued, draftEmpty: draft === '', at: new Date().toISOString() }) + '\n');
  } catch {}
}

const chars = (text: string): string[] => Array.from(text);

const isWordChar = (c: string) => /[\p{L}\p{N}_]/u.test(c);

/** One key press applied to the draft. The input state is never modified. */
export function applyKey(state: DraftState, key: string): KeyResult {
  const cs = chars(state.text);
  const at = Math.max(0, Math.min(cs.length, state.cursor));
  const set = (next: string[], cursor: number): KeyResult => ({ state: { text: next.join(''), cursor } });

  const wordBackspace = (chars: string[], pos: number): KeyResult => {
    let start = pos;
    while (start > 0 && !isWordChar(chars[start - 1])) start--;
    while (start > 0 && isWordChar(chars[start - 1])) start--;
    return start === pos ? { state } : set([...chars.slice(0, start), ...chars.slice(pos)], start);
  };

  // Ctrl+Enter, Alt+Enter, Shift+Enter: a new paragraph, the line is not sent.
  if (isNewlineKey(key)) return set([...cs.slice(0, at), '\n', ...cs.slice(at)], at + 1);

  switch (key) {
    case '\r':
    case '\n': {
      // A backslash before the cursor makes Enter a line break instead (for terminals that cannot
      // tell Ctrl+Enter from Enter, a phone keyboard for one).
      if (at > 0 && cs[at - 1] === '\\') return set([...cs.slice(0, at - 1), '\n', ...cs.slice(at)], at);
      const text = state.text.trim();
      return text ? { state: { text: '', cursor: 0 }, submit: text } : { state };
    }
    case '\b': // Ctrl+Backspace in most terminals (plain Backspace is 0x7f): erase a word
      return wordBackspace(cs, at);
    case '\x7f':
      return at > 0 ? set([...cs.slice(0, at - 1), ...cs.slice(at)], at - 1) : { state };
    case '\x1b[3~':
      return at < cs.length ? set([...cs.slice(0, at), ...cs.slice(at + 1)], at) : { state };
    case '\x1b[D':
    case '\x1bOD':
    case '\x02':
      return { state: { text: state.text, cursor: Math.max(0, at - 1) } };
    case '\x1b[C':
    case '\x1bOC':
    case '\x06':
      return { state: { text: state.text, cursor: Math.min(cs.length, at + 1) } };
    case '\x1b[H':
    case '\x1b[1~':
    case '\x1bOH':
    case '\x01':
      return { state: { text: state.text, cursor: 0 } };
    case '\x1b[F':
    case '\x1b[4~':
    case '\x1bOF':
    case '\x05':
      return { state: { text: state.text, cursor: cs.length } };
    case '\x15': // Ctrl+U: everything before the cursor
      return set(cs.slice(at), 0);
    case '\x0b': // Ctrl+K: everything after the cursor
      return set(cs.slice(0, at), at);
    case '\x17': // Ctrl+W, Alt+Backspace: the word before the cursor
      return wordBackspace(cs, at);
    case '\x1b': // Esc: throw the line away
      return state.text ? { state: { text: '', cursor: 0 } } : { state };
    default:
      break;
  }

  // Any other escape sequence or control character is not text.
  if (key.startsWith('\x1b') || key.length === 0) return { state };
  const code = key.codePointAt(0)!;
  if (code < 32 || code === 127) return { state };
  const typed = chars(key);
  return set([...cs.slice(0, at), ...typed, ...cs.slice(at)], at + typed.length);
}

/** What the frame needs besides the line itself (it changes while the turn runs). */
export type FrameBase = Omit<FrameInput, 'cols' | 'input' | 'cursorPos' | 'placeholder' | 'below'>;

export interface ComposerInput {
  state: DraftState;
  queued: string[];
  cols: number;
  base: FrameBase;
  /** Rows of the list of commands that is open under the line (it takes the place of the hint row). */
  menu?: string[];
}

// A stand-in for the cursor while the frame is laid out; it is swapped for a highlighted cell.
const CURSOR_MARK = '\uE000';
const CURSOR_CELL = '\x1b[7m \x1b[27m';

/**
 * The prompt frame with the line being typed, and under it a row that says what Enter does
 * or what is already queued. The real cursor is busy marking where the output goes, so the
 * cursor of the line is drawn as a highlighted cell.
 */
export function renderComposer({ state, queued, cols, base, menu }: ComposerInput): string[] {
  const cs = Array.from(state.text);
  const at = Math.max(0, Math.min(cs.length, state.cursor));
  const withMark = state.text === '' ? '' : cs.slice(0, at).join('') + CURSOR_MARK + cs.slice(at).join('');

  let note: string;
  const room = Math.max(8, cols - 5);
  if (queued.length > 0) {
    const last = queued[queued.length - 1].replace(/\s+/g, ' ');
    const label = queued.length === 1 ? t('composer.queued') : t('composer.queuedMany', { n: queued.length });
    const prefix = palette.warn('↳ ' + label) + palette.faint(': ');
    // What can be done with it: the down arrow takes it back to edit; the key for a new line is named too.
    const edit = t('composer.queuedHint');
    const newline = `${newlineKeyLabel()} ${t('composer.newline')}`;
    const fits = (hint: string) => room - displayWidth(prefix) - displayWidth(' · ' + hint) >= 12;
    const send = t('composer.sendNow');
    const hint = [`${edit} · ${send} · ${newline}`, `${edit} · ${send}`, edit].find(fits) ?? '';
    const suffixWidth = hint ? displayWidth(' · ' + hint) : 0;
    const message = truncateWidth(last, Math.max(4, room - displayWidth(prefix) - suffixWidth));
    note = prefix + palette.muted(message) + (hint ? palette.faint(' · ') + palette.text(hint) : '');
  } else {
    const variants = [`${t('composer.hint1')} · ${newlineKeyLabel()} ${t('composer.newline')}`, t('composer.hint1'), t('composer.hint2'), t('composer.hint3'), t('composer.hint4')];
    const text = variants.find((v) => displayWidth(v) <= room) ?? '';
    note = palette.faint(text);
  }

  const frame = buildPromptFrame({
    ...base,
    cols,
    input: withMark,
    cursorPos: withMark === '' ? 0 : at,
    placeholder: CURSOR_MARK + t('composer.placeholder'),
    below: menu && menu.length > 0 ? menu : [note],
  });
  return frame.lines.map((line) => line.split(CURSOR_MARK).join(CURSOR_CELL));
}

export interface ComposerOut {
  write(chunk: any, encoding?: any, cb?: any): unknown;
  columns?: number;
  rows?: number;
  isTTY?: boolean;
}

const MIN_ROWS = 10;
const MIN_COLS = 30;

/**
 * Keeps the prompt frame under the output while a turn runs.
 *
 * Every write to the terminal goes through here for the length of the turn: the frame is wiped,
 * the text is written, the frame is drawn again below the new last line, all in one write, so
 * the screen does not flicker. The frame needs no fixed place: it is always "the rows under
 * the cursor", and the cursor is put back where the text goes on. Rows are made with ESC D
 * (index), which scrolls the screen when the cursor is near the bottom and keeps the column.
 *
 * Safe to use anywhere: on a terminal that is too small, or not a terminal at all, it does
 * nothing and `start()` returns false.
 */
export class TurnComposer {
  private active = false;
  private suspended = false;
  private shown = false;
  private state: DraftState = { text: '', cursor: 0 };
  private queue: string[] = [];
  private lines: string[] = [];
  /** The column the output cursor is in: the frame is drawn under it and the cursor comes back to this column. */
  private col = 0;
  private original: ComposerOut['write'] | null = null;
  private timer: ReturnType<typeof setInterval> | null = null;

  /** Called on ctrl+p (thinking) and ctrl+o (tool output). */
  onToggle?: (kind: 'thinking' | 'output') => void;
  /** Enter on an empty line while a message waits: the agent stops where it is and the waiting message goes now. */
  onSendNow?: () => void;
  /** The line of live reasoning shown above the working line while thinking is collapsed; empty for none. */
  live?: () => string;
  /** The commands offered when the line starts with a slash. */
  commands?: () => CommandItem[];
  /**
   * A command was sent from the line. Returns true when it was taken (run at once, or kept for the end of
   * the turn); false sends it to the agent like any other message.
   */
  onCommand?: (text: string) => boolean;
  private menuIndex = 0;
  private history = new PromptHistoryNavigator(promptHistory());

  constructor(
    private out: ComposerOut = process.stdout,
    private frameBase: () => FrameBase,
    private onQueue?: (text: string) => void,
    /** The working line shown above the frame for the whole turn; called on every animation tick. */
    private status?: () => string,
  ) {}

  get enabled(): boolean {
    return this.active;
  }

  /** An approval card or a question has the keyboard. */
  get isSuspended(): boolean {
    return this.suspended;
  }

  get draft(): string {
    return this.state.text;
  }

  get queued(): string[] {
    return [...this.queue];
  }

  hasQueued(): boolean {
    return this.queue.length > 0;
  }

  /** Hands over the queued messages (oldest first) and empties the queue. */
  takeQueued(): string[] {
    const taken = this.queue;
    this.queue = [];
    // The "queued" note is part of the frame: it has to be built again, or it stays on screen.
    this.lines = this.render();
    this.redraw();
    return taken;
  }

  /** Shows the frame under the cursor and takes over the terminal output. Returns false when that is not possible here. */
  start(initialDraft = ''): boolean {
    const rows = this.out.rows ?? 0;
    const cols = this.out.columns ?? 0;
    if (!this.out.isTTY || rows < MIN_ROWS || cols < MIN_COLS) return false;

    this.state = { text: initialDraft, cursor: Array.from(initialDraft).length };
    this.queue = [];
    this.menuIndex = 0;
    this.history = new PromptHistoryNavigator(promptHistory());
    this.lines = this.render();
    // A frame that takes half the screen is not worth it.
    if (this.lines.length > Math.floor(rows / 2)) return false;

    this.active = true;
    this.suspended = false;
    this.shown = false;
    this.col = 0;
    this.original = this.out.write.bind(this.out);
    this.out.write = ((chunk: any, encoding?: any, cb?: any) => this.through(chunk, encoding, cb)) as ComposerOut['write'];
    this.write(extendedKeysOn() + this.drawSeq());
    // The working line animates: its time and counter are redrawn while the output is quiet.
    if (this.status) {
      this.timer = setInterval(() => this.tick(), 60);
      this.timer.unref?.();
    }
    return true;
  }

  /** Gives the terminal back and returns what was left unsent. */
  stop(): { draft: string; queued: string[] } {
    const result = { draft: this.state.text, queued: this.queue };
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
    if (this.active) {
      this.write(this.eraseSeq() + extendedKeysOff() + '\x1b[?25h');
      if (this.original) this.out.write = this.original;
      this.original = null;
    }
    this.active = false;
    this.suspended = false;
    this.shown = false;
    this.queue = [];
    return result;
  }

  /** Hides the frame while something else (an approval card, a question) needs the keyboard and the screen. */
  suspend(): void {
    if (!this.active || this.suspended) return;
    // The card or question that takes over reads plain keys: the extended encodings go off for it.
    this.write(this.eraseSeq() + extendedKeysOff());
    this.suspended = true;
  }

  resume(): void {
    if (!this.active || !this.suspended) return;
    this.suspended = false;
    this.write(extendedKeysOn() + this.drawSeq());
  }

  /** Builds the frame again (the context percentage or the cost changed) and draws it. */
  refresh(): void {
    this.lines = this.render();
    this.redraw();
  }

  /** The terminal changed size. */
  onResize(): void {
    this.refresh();
  }

  /** Raw terminal input. Ignored while suspended. */
  handleData(chunk: string): void {
    if (!this.active || this.suspended) return;
    if (process.env.DEVX_DEBUG_KEYS) logKeys(chunk, this.queue.length, this.state.text);
    for (const raw of splitKeys(chunk)) {
      const key = normalizeKey(raw);
      // Ctrl+C is the app's (it stops the generation); Ctrl+D does nothing.
      if (key === '\x03' || key === '\x04') continue;
      const items = this.menuItems();
      if (items.length > 0) {
        // The list of commands is open: the arrows walk through it, Tab completes, Enter takes the marked one.
        if (UP_ARROW.test(key)) { this.menuIndex = Math.max(0, this.menuIndex - 1); continue; }
        if (DOWN_ARROW.test(key)) { this.menuIndex = Math.min(items.length - 1, this.menuIndex + 1); continue; }
        const chosen = items[Math.min(this.menuIndex, items.length - 1)].cmd;
        if (key === '\t') {
          this.setText(chosen + ' ');
          continue;
        }
        if ((key === '\r' || key === '\n') && this.state.text.trim().toLowerCase() !== chosen.toLowerCase()) {
          this.state = { text: chosen, cursor: Array.from(chosen).length };
        }
      } else if ((key === '\r' || key === '\n') && this.state.text === '' && this.queue.length > 0 && this.onSendNow) {
        this.onSendNow();
        continue;
      } else if (UP_ARROW.test(key)) {
        // Up and down bring back what was sent before: in this chat and in the earlier ones.
        const older = this.history.navigateUp(this.state.text);
        if (older !== null) this.setText(older, true);
        continue;
      } else if (DOWN_ARROW.test(key)) {
        if (this.history.isBrowsing()) {
          const newer = this.history.navigateDown();
          if (newer !== null) this.setText(newer, true);
          continue;
        }
        // Down arrow with nothing typed: the message that waits comes back into the line to be edited.
        if (this.queue.length > 0 && this.state.text === '') {
          const back = this.queue.pop()!;
          this.state = { text: back, cursor: Array.from(back).length };
        }
        continue;
      }
      if (key === '\x10') { this.onToggle?.('thinking'); continue; }   // ctrl+p
      if (key === '\x0f') { this.onToggle?.('output'); continue; }     // ctrl+o
      const before = this.state.text;
      const result = applyKey(this.state, key);
      this.state = result.state;
      if (this.state.text !== before) {
        this.menuIndex = 0;
        this.history.reset();
      }
      if (result.submit) this.send(result.submit);
    }
    this.lines = this.render();
    this.redraw();
  }

  /** A line was sent: it is remembered, and goes to the commands or to the queue of messages. */
  private send(text: string): void {
    recordPrompt(text);
    this.history = new PromptHistoryNavigator(promptHistory());
    this.menuIndex = 0;
    const isCommand = text.startsWith('/') && !startsWithPath(text, pathExists);
    if (isCommand && this.onCommand?.(text)) return;
    this.queue.push(text);
    this.onQueue?.(text);
  }

  /** Puts a text on the line (the cursor goes to its end). `fromHistory` keeps the place in the history. */
  private setText(text: string, fromHistory = false): void {
    this.state = { text, cursor: Array.from(text).length };
    this.menuIndex = 0;
    if (!fromHistory) this.history.reset();
  }

  /** The commands that fit what is typed after the slash: only while the first word is being typed. */
  private menuItems(): CommandItem[] {
    const text = this.state.text;
    if (!this.commands || !text.startsWith('/') || /\s/.test(text) || startsWithPath(text)) return [];
    return filterCommandPalette(text, this.commands());
  }

  // ── terminal output

  /** The write that replaces the terminal's own for the length of the turn. */
  private through(chunk: any, encoding?: any, cb?: any): unknown {
    const raw = this.original!;
    const text = typeof chunk === 'string' ? chunk : Buffer.from(chunk).toString('utf8');
    if (!this.active || this.suspended) {
      this.advanceColumn(text);
      return raw(chunk, encoding, cb);
    }
    // The old frame is wiped from the column the cursor is in now; the new one comes back to the column the text ends in.
    const erase = this.eraseSeq();
    this.advanceColumn(text);
    if (typeof chunk === 'string') {
      // The callback form `write(chunk, cb)` has the callback in the second place.
      return raw(erase + chunk + this.drawSeq(), encoding, cb);
    }
    raw(erase);
    const result = raw(chunk, encoding, cb);
    raw(this.drawSeq());
    return result;
  }

  /** Where the cursor is after this text (also while a card has the screen, so that the frame comes back to the right column). */
  private advanceColumn(text: string): void {
    this.col = columnAfter(this.col, text, this.out.columns ?? 80);
  }

  private write(text: string): void {
    if (text) (this.original ?? this.out.write.bind(this.out))(text);
  }

  private redraw(): void {
    if (!this.active || this.suspended) return;
    this.write(this.eraseSeq() + this.drawSeq());
  }

  /** Redraws the working line only (the frame does not change between ticks). */
  private tick(): void {
    if (!this.active || this.suspended || this.lines.length < 3) return;
    this.lines[1] = this.oneRow(this.live?.() ?? '');
    this.lines[2] = this.oneRow(this.status?.() ?? '');
    this.redraw();
  }

  /**
   * The rows under the output: an empty row, the working line, an empty row and the frame.
   * Without a working line only one empty row separates the frame from the output.
   */
  private render(): string[] {
    const items = this.menuItems();
    const menu = items.length > 0
      ? renderDropdown(items.map((i) => ({ label: i.cmd, desc: i.desc })), Math.min(this.menuIndex, items.length - 1), this.out.columns ?? 80, Math.max(12, (this.out.rows ?? 24) - 8))
      : undefined;
    const frame = renderComposer({ state: this.state, queued: this.queue, cols: this.out.columns ?? 80, base: this.frameBase(), menu });
    // the live reasoning, then the working line right above the frame (always in the same place), and a blank row
    return this.status ? ['', this.oneRow(this.live?.() ?? ''), this.oneRow(this.status()), '', ...frame] : ['', ...frame];
  }

  /** The drawing code moves down one row per line: a line with a line break inside would shift the whole frame. */
  private oneRow(line: string): string {
    return line.replace(/[\r\n\x00-\x08\x0b-\x1a\x1c-\x1f]+/g, ' ');
  }

  /** Puts the cursor back in the column of the output, on the row it is on. */
  private backToColumn(): string {
    const cols = Math.max(1, this.out.columns ?? 80);
    return `\x1b[${Math.min(this.col, cols - 1) + 1}G`;
  }

  /**
   * Wipes the frame: everything under the cursor row. The cursor stays on its text.
   *
   * Only relative moves are used, no saved cursor (ESC 7 / ESC 8): the saved position is a screen row,
   * and when the window changes size in the middle of a write (the keyboard of a phone comes and goes)
   * some terminals do not move it with the text, and the cursor comes back to the wrong row.
   */
  private eraseSeq(): string {
    if (!this.shown) return '';
    this.shown = false;
    // The erase starts from plain attributes: a style left on by the text above (an underline, a
    // background) would be painted into every row it wipes (stripes across the screen).
    return '\x1b[0m\x1b[1B\r\x1b[J\x1b[1A' + this.backToColumn();
  }

  /** Draws the frame under the cursor row and puts the cursor back where the text goes on. */
  private drawSeq(): string {
    const lines = this.lines;
    if (lines.length === 0) return '';
    this.shown = true;
    const n = lines.length;
    // The real cursor only marks where the output goes on. Whatever showed it in the meantime
    // (an approval card, a spinner that stopped) must not leave it blinking under the output.
    // ESC D moves down (scrolling at the bottom edge) and keeps the column; then back up.
    let seq = '\x1b[?25l' + '\x1bD'.repeat(n) + `\x1b[${n}A`;
    for (const line of lines) seq += `\x1b[0m\x1b[1B\r\x1b[2K${line}\x1b[0m`;
    return seq + `\x1b[${n}A` + this.backToColumn();
  }
}
