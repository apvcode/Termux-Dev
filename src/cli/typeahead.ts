import { splitKeys } from './keys.js';
import { FrameInput, buildPromptFrame, palette } from './ui.js';
import { displayWidth, truncateWidth } from './width.js';

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

const chars = (text: string): string[] => Array.from(text);

const isWordChar = (c: string) => /[\p{L}\p{N}_]/u.test(c);

/** One key press applied to the draft. The input state is never modified. */
export function applyKey(state: DraftState, key: string): KeyResult {
  const cs = chars(state.text);
  const at = Math.max(0, Math.min(cs.length, state.cursor));
  const set = (next: string[], cursor: number): KeyResult => ({ state: { text: next.join(''), cursor } });

  switch (key) {
    case '\r':
    case '\n': {
      const text = state.text.trim();
      return text ? { state: { text: '', cursor: 0 }, submit: text } : { state };
    }
    case '\x7f':
    case '\b':
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
    case '\x17': { // Ctrl+W: the word before the cursor
      let start = at;
      while (start > 0 && !isWordChar(cs[start - 1])) start--;
      while (start > 0 && isWordChar(cs[start - 1])) start--;
      return set([...cs.slice(0, start), ...cs.slice(at)], start);
    }
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
}

// A stand-in for the cursor while the frame is laid out; it is swapped for a highlighted cell.
const CURSOR_MARK = '\uE000';
const CURSOR_CELL = '\x1b[7m \x1b[27m';

/**
 * The prompt frame with the line being typed, and under it a row that says what Enter does
 * or what is already queued. The real cursor is busy marking where the output goes, so the
 * cursor of the line is drawn as a highlighted cell.
 */
export function renderComposer({ state, queued, cols, base }: ComposerInput): string[] {
  const cs = Array.from(state.text);
  const at = Math.max(0, Math.min(cs.length, state.cursor));
  const withMark = state.text === '' ? '' : cs.slice(0, at).join('') + CURSOR_MARK + cs.slice(at).join('');

  let note: string;
  const room = Math.max(8, cols - 5);
  if (queued.length > 0) {
    const last = queued[queued.length - 1].replace(/\s+/g, ' ');
    const label = queued.length === 1 ? 'queued' : `${queued.length} queued`;
    const prefix = palette.warn('↳ ' + label) + palette.faint(': ');
    note = prefix + palette.muted(truncateWidth(last, Math.max(4, room - displayWidth(prefix))));
  } else {
    const variants = [
      'enter queues it for the agent · ctrl+c stop',
      'enter queues it · ctrl+c stop',
      'enter queues · ctrl+c stop',
      'enter queues',
    ];
    note = palette.faint(variants.find((v) => displayWidth(v) <= room) ?? '');
  }

  const frame = buildPromptFrame({
    ...base,
    cols,
    input: withMark,
    cursorPos: withMark === '' ? 0 : at,
    placeholder: CURSOR_MARK + 'Ask or describe a task…',
    below: [note],
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
  private original: ComposerOut['write'] | null = null;
  private timer: ReturnType<typeof setInterval> | null = null;

  constructor(
    private out: ComposerOut = process.stdout,
    private frameBase: () => FrameBase,
    private onQueue?: (text: string) => void,
    /** The working line shown above the frame for the whole turn; called on every animation tick. */
    private status?: () => string
  ) {}

  get enabled(): boolean {
    return this.active;
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
    this.lines = this.render();
    // A frame that takes half the screen is not worth it.
    if (this.lines.length > Math.floor(rows / 2)) return false;

    this.active = true;
    this.suspended = false;
    this.shown = false;
    this.original = this.out.write.bind(this.out);
    this.out.write = ((chunk: any, encoding?: any, cb?: any) => this.through(chunk, encoding, cb)) as ComposerOut['write'];
    this.write('\x1b[?25l' + this.drawSeq());
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
      this.write(this.eraseSeq() + '\x1b[?25h');
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
    this.write(this.eraseSeq());
    this.suspended = true;
  }

  resume(): void {
    if (!this.active || !this.suspended) return;
    this.suspended = false;
    this.write(this.drawSeq());
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
    for (const key of splitKeys(chunk)) {
      // Ctrl+C is the app's (it stops the generation); Ctrl+D does nothing.
      if (key === '\x03' || key === '\x04') continue;
      const result = applyKey(this.state, key);
      this.state = result.state;
      if (result.submit) {
        this.queue.push(result.submit);
        this.onQueue?.(result.submit);
      }
    }
    this.lines = this.render();
    this.redraw();
  }

  // ── terminal output

  /** The write that replaces the terminal's own for the length of the turn. */
  private through(chunk: any, encoding?: any, cb?: any): unknown {
    const raw = this.original!;
    if (!this.active || this.suspended) return raw(chunk, encoding, cb);
    if (typeof chunk === 'string') {
      // The callback form `write(chunk, cb)` has the callback in the second place.
      return raw(this.eraseSeq() + chunk + this.drawSeq(), encoding, cb);
    }
    raw(this.eraseSeq());
    const result = raw(chunk, encoding, cb);
    raw(this.drawSeq());
    return result;
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
    this.lines[1] = this.status?.() ?? '';
    this.redraw();
  }

  /**
   * The rows under the output: an empty row, the working line, an empty row and the frame.
   * Without a working line only one empty row separates the frame from the output.
   */
  private render(): string[] {
    const frame = renderComposer({ state: this.state, queued: this.queue, cols: this.out.columns ?? 80, base: this.frameBase() });
    return this.status ? ['', this.status(), '', ...frame] : ['', ...frame];
  }

  /** Wipes the frame: everything under the cursor row. The cursor stays on its text. */
  private eraseSeq(): string {
    if (!this.shown) return '';
    this.shown = false;
    return '\x1b7\x1b[1B\r\x1b[J\x1b8';
  }

  /** Draws the frame under the cursor row and puts the cursor back where the text goes on. */
  private drawSeq(): string {
    const lines = this.lines;
    if (lines.length === 0) return '';
    this.shown = true;
    const n = lines.length;
    // ESC D moves down (scrolling at the bottom edge) and keeps the column; then back up.
    let seq = '\x1bD'.repeat(n) + `\x1b[${n}A` + '\x1b7';
    for (const line of lines) seq += `\x1b[1B\r\x1b[2K${line}\x1b[0m`;
    return seq + '\x1b8';
  }
}
