import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import os from 'node:os';
import path from 'node:path';
import { DraftState, FrameBase, TurnComposer, applyKey, renderComposer } from '../../src/cli/typeahead.js';
import { setActiveTheme } from '../../src/cli/theme.js';
import { displayWidth, stripAnsi } from '../../src/cli/width.js';
import { CTRL_ENTER, normalizeKey, splitKeys } from '../../src/cli/keys.js';
import { SLASH_COMMANDS } from '../../src/cli/prompt.js';
import { promptHistory, resetPromptHistory } from '../../src/cli/prompt_history.js';

const type = (text: string, from: DraftState = { text: '', cursor: 0 }): DraftState => {
  let state = from;
  for (const ch of Array.from(text)) state = applyKey(state, ch).state;
  return state;
};

describe('editing the line', () => {
  it('types, moves the cursor and deletes', () => {
    let s = type('helo');
    s = applyKey(s, '\x1b[D').state;            // left: before "o"
    s = type('l', s);
    expect(s).toEqual({ text: 'hello', cursor: 4 });
    s = applyKey(s, '\x7f').state;              // backspace
    expect(s.text).toBe('helo');
    s = applyKey(s, '\x1b[3~').state;           // delete (the "o")
    expect(s.text).toBe('hel');
    expect(applyKey({ text: '', cursor: 0 }, '\x7f').state.text).toBe('');
  });

  it('Home, End, Ctrl+U, Ctrl+K and Ctrl+W', () => {
    const s = type('fix the header please');
    expect(applyKey(s, '\x1b[H').state.cursor).toBe(0);
    expect(applyKey(applyKey(s, '\x1b[H').state, '\x1b[F').state.cursor).toBe(s.text.length);
    expect(applyKey(s, '\x17').state.text).toBe('fix the header ');
    expect(applyKey({ text: 'abc def', cursor: 3 }, '\x15').state).toEqual({ text: ' def', cursor: 0 });
    expect(applyKey({ text: 'abc def', cursor: 3 }, '\x0b').state).toEqual({ text: 'abc', cursor: 3 });
  });

  it('Ctrl+Backspace and Alt+Backspace erase the text word by word', () => {
    // Ctrl+Backspace is 0x08 in most terminals, Alt+Backspace is ESC DEL, the extended modes use CSI u.
    for (const key of ['\b', normalizeKey('\x1b\x7f'), normalizeKey('\x1b[127;5u'), normalizeKey('\x1b[127;3u'), normalizeKey('\x1b[27;5;127~')]) {
      let s = type('привет большой мир');
      s = applyKey(s, key).state;
      expect(s.text).toBe('привет большой ');
      s = applyKey(s, key).state;
      expect(s.text).toBe('привет ');
      s = applyKey(s, key).state;
      expect(s.text).toBe('');
      expect(applyKey(s, key).state).toEqual({ text: '', cursor: 0 });
    }
    // Only the part before the cursor is touched.
    expect(applyKey({ text: 'one two three', cursor: 7 }, '\b').state).toEqual({ text: 'one  three', cursor: 4 });
    // A plain Backspace still removes one character.
    expect(applyKey(type('abc'), '\x7f').state.text).toBe('ab');
  });

  it('works with Cyrillic and with characters outside the BMP', () => {
    let s = type('привет \u{20BB7}');  // the last character is outside the BMP (a CJK ideograph)
    expect(s.cursor).toBe(8);
    s = applyKey(s, '\x7f').state;
    expect(s.text).toBe('привет ');
    s = applyKey(applyKey(s, '\x1b[D').state, '\x7f').state;
    expect(s.text).toBe('приве ');
  });

  it('Enter submits a non-empty line and clears it; an empty line is not sent', () => {
    const r = applyKey(type('  do it  '), '\r');
    expect(r.submit).toBe('do it');
    expect(r.state).toEqual({ text: '', cursor: 0 });
    expect(applyKey(type('   '), '\r').submit).toBeUndefined();
  });

  it('Ctrl+Enter, Alt+Enter and Shift+Enter break the line at the cursor and send nothing', () => {
    for (const key of [CTRL_ENTER, '\x1b[13;5u', '\x1b[27;5;13~', '\x1b[13;3u', '\x1b\r', '\x1b[13;2u', '\x1b[27;2;13~']) {
      const r = applyKey({ text: 'first second', cursor: 5 }, key);
      expect(r.submit, JSON.stringify(key)).toBeUndefined();
      expect(r.state, JSON.stringify(key)).toEqual({ text: 'first\n second', cursor: 6 });
    }
  });

  it('a break can be made in an empty line and at its end, and Enter then sends everything with the breaks in', () => {
    let s = applyKey(type(''), CTRL_ENTER).state;
    expect(s).toEqual({ text: '\n', cursor: 1 });
    s = applyKey(type('one'), CTRL_ENTER).state;
    s = 'two'.split('').reduce((st, c) => applyKey(st, c).state, s);
    expect(s.text).toBe('one\ntwo');
    const sent = applyKey(s, '\r');
    expect(sent.submit).toBe('one\ntwo');
    expect(sent.state).toEqual({ text: '', cursor: 0 });
  });

  it('a backslash before the cursor turns Enter into a break (for terminals that cannot tell Ctrl+Enter)', () => {
    const r = applyKey(type('one\\'), '\r');
    expect(r.submit).toBeUndefined();
    expect(r.state).toEqual({ text: 'one\n', cursor: 4 });
    // in the middle of the text too, and only right before the cursor
    expect(applyKey({ text: 'a\\b', cursor: 2 }, '\r').state).toEqual({ text: 'a\nb', cursor: 2 });
    expect(applyKey({ text: 'a\\b', cursor: 3 }, '\r').submit).toBe('a\\b');
  });

  it('Esc throws the line away; other escape sequences and control keys are not text', () => {
    expect(applyKey(type('abc'), '\x1b').state.text).toBe('');
    const s = type('abc');
    for (const key of ['\x1b[A', '\x1b[B', '\x1b[1;5C', '\x00', '\t', '\x1bOP']) expect(applyKey(s, key).state).toEqual(s);
  });

  it('never modifies the state it was given', () => {
    const s = Object.freeze({ text: 'abc', cursor: 1 });
    expect(() => applyKey(s, 'x')).not.toThrow();
    expect(s.text).toBe('abc');
  });
});

const BASE: FrameBase = { mode: 'agent', model: 'glm-5.3-flash · think max', cwd: path.join(os.homedir(), 'Downloads', 'test'), contextPercent: 12, cost: 0.0123 };

describe('the composer frame', () => {
  beforeEach(() => setActiveTheme('cyan'));
  const draw = (text: string, queued: string[] = [], cols = 100, cursor = Array.from(text).length) =>
    renderComposer({ state: { text, cursor }, queued, cols, base: BASE });

  it('names the key for a new line next to the hints and next to a queued message', () => {
    vi.stubEnv('DEVX_EXTENDED_KEYS', '1');
    try {
      const idle = draw('').map(stripAnsi)[3];
      expect(idle).toContain('enter queues');
      expect(idle).toContain('shift+enter new line');
      const queued = draw('', ['also add tests']).map(stripAnsi)[3];
      expect(queued).toContain('↓ edit');
      expect(queued).toContain('shift+enter new line');
    } finally {
      vi.unstubAllEnvs();
    }
  });

  it('names a backslash before Enter where the terminal cannot tell Shift+Enter from Enter, and drops the hint on a narrow row', () => {
    vi.stubEnv('DEVX_EXTENDED_KEYS', '0');
    try {
      expect(draw('').map(stripAnsi)[3]).toContain('\\ enter new line');
      expect(draw('', ['also add tests']).map(stripAnsi)[3]).toContain('↓ edit');
      const narrow = draw('', ['also add tests'], 40).map(stripAnsi).at(-1)!;
      expect(narrow).not.toContain('new line');
      expect(displayWidth(narrow)).toBeLessThanOrEqual(40);
    } finally {
      vi.unstubAllEnvs();
    }
  });

  it('is the prompt frame: mode, model, folder, context and cost, with the placeholder on an empty line', () => {
    const lines = draw('').map(stripAnsi);
    expect(lines).toHaveLength(4);
    expect(lines[0]).toContain('agent');
    expect(lines[0]).toContain('glm-5.3-flash · think max');
    expect(lines[1]).toContain('Ask or describe a task');
    expect(lines[2]).toContain('~/Downloads/test');
    expect(lines[2]).toContain('12% · $0.0123');
    expect(lines[3]).toContain('enter queues');
    expect(lines[3]).toContain('esc stop');
    expect(lines[3]).not.toContain('ctrl+enter');
  });

  it('shows the text and a cursor cell at the right place', () => {
    const raw = draw('use tabs', [], 100, 3);
    const input = raw[1];
    expect(stripAnsi(input)).toContain('use  tabs');             // the cursor cell sits after "use"
    expect(input).toContain('\x1b[7m \x1b[27m');
    expect(draw('use tabs').map(stripAnsi)[1]).toContain('use tabs ');
  });

  it('says that Enter on an empty line sends the waiting message now', () => {
    expect(draw('', ['also add tests']).map(stripAnsi)[3]).toContain('enter send now');
  });

  it('shows what is queued under the frame', () => {
    expect(draw('', ['also add tests']).map(stripAnsi)[3]).toContain('↳ queued: also add tests');
    expect(draw('', ['a', 'second one']).map(stripAnsi)[3]).toContain('↳ 2 queued: second one');
  });

  it('keeps one height for every state, until the text needs more rows', () => {
    const heights = new Set(
      [draw(''), draw('x'.repeat(30)), draw('hi', ['q']), draw('', ['a', 'b'])].map((l) => l.length)
    );
    expect(heights.size).toBe(1);
    // a long text wraps onto more rows (the frame grows) and stops growing at the limit
    const long = draw('word '.repeat(300));
    expect(long.length).toBeGreaterThan(draw('').length);
    expect(long.length).toBeLessThanOrEqual(draw('').length + 7);
  });

  it('never overflows, from a phone to a wide terminal', () => {
    const long = 'x'.repeat(300);
    for (const cols of [30, 40, 59, 60, 100, 200]) {
      for (const text of ['', long]) {
        for (const queued of [[], [long]]) {
          for (const line of draw(text, queued, cols).map(stripAnsi)) {
            expect(displayWidth(line), `${cols} cols: ${line}`).toBeLessThanOrEqual(cols);
          }
        }
      }
    }
  });

  it('shows the colour of the mode: plan and yolo frames are recognisable', () => {
    const plan = renderComposer({ state: { text: '', cursor: 0 }, queued: [], cols: 100, base: { ...BASE, mode: 'plan' } }).map(stripAnsi);
    expect(plan[0]).toContain('plan');
  });
});

describe('TurnComposer', () => {
  let written: string[];
  let out: { write: (c: any, e?: any, cb?: any) => boolean; columns: number; rows: number; isTTY: boolean };
  let terminalWrite: (c: any) => boolean;
  const all = () => written.join('');

  beforeEach(() => {
    setActiveTheme('cyan');
    written = [];
    terminalWrite = (c: any) => { written.push(String(c)); return true; };
    out = { write: (c: any) => terminalWrite(c), columns: 80, rows: 30, isTTY: true };
  });

  it('draws the frame right under the cursor, without a scroll region or a fixed screen row', () => {
    const composer = new TurnComposer(out, () => BASE);
    expect(composer.start()).toBe(true);
    const text = stripAnsi(all());
    expect(all()).not.toMatch(/\x1b\[\d*;\d*r/);          // no DECSTBM
    expect(all()).not.toMatch(/\x1b\[\d+;\d+H/);          // no absolute positioning
    expect(all()).toContain('\x1bD');                       // rows are made below the cursor
    expect(text).toContain('Ask or describe a task');
    expect(text).toContain('~/Downloads/test');
    expect(composer.enabled).toBe(true);
    composer.stop();
  });

  it('wraps every write of the app: wipe the frame, the text, the frame again, in one write', () => {
    const composer = new TurnComposer(out, () => BASE);
    composer.start();
    written.length = 0;
    out.write('hello\n');
    expect(written).toHaveLength(1);
    const chunk = written[0];
    const wipe = chunk.indexOf('\x1b[J');
    const text = chunk.indexOf('hello\n');
    const draw = chunk.indexOf('Ask or describe a task');
    expect(wipe).toBeGreaterThanOrEqual(0);
    expect(wipe).toBeLessThan(text);
    expect(text).toBeLessThan(draw);
    composer.stop();
  });

  it('writes that are not text (buffers) are wrapped too and returned unchanged', () => {
    const composer = new TurnComposer(out, () => BASE);
    composer.start();
    written.length = 0;
    expect(out.write(Buffer.from('raw bytes'))).toBe(true);
    expect(written.map((w) => w.includes('raw bytes'))).toContain(true);
    expect(written[0]).toContain('\x1b[J');                  // wiped before
    expect(stripAnsi(written[written.length - 1])).toContain('Ask or describe a task'); // drawn after
    composer.stop();
  });

  it('gives the terminal back on stop: the frame is wiped, writes go straight through again', () => {
    const composer = new TurnComposer(out, () => BASE);
    composer.start();
    composer.handleData('half a thought');
    written.length = 0;
    const left = composer.stop();
    expect(left).toEqual({ draft: 'half a thought', queued: [] });
    expect(all()).toContain('\x1b[J');
    expect(all()).toContain('\x1b[?25h');
    written.length = 0;
    out.write('plain');
    expect(written).toEqual(['plain']);
    expect(composer.enabled).toBe(false);
  });

  it('the "queued" note is gone from the screen once the message was handed over', () => {
    const composer = new TurnComposer(out, () => BASE);
    composer.start();
    composer.handleData('also add a river\r');
    expect(stripAnsi(all())).toContain('↳ queued: also add a river');
    written.length = 0;
    composer.takeQueued();
    const after = stripAnsi(all());
    expect(after).not.toContain('queued: also add a river');
    expect(after).toContain('enter queues');
    composer.stop();
  });

  it('hides the real cursor on every draw, also after a card brought it back', () => {
    const composer = new TurnComposer(out, () => BASE);
    composer.start();
    composer.suspend();
    written.length = 0;
    composer.resume();
    expect(all()).toContain('\x1b[?25l');
    written.length = 0;
    out.write('text under the cursor\n');
    expect(all()).toContain('\x1b[?25l');
    composer.stop();
  });

  it('asks the terminal for Ctrl+Enter while it runs and gives plain keys back for cards and on stop', () => {
    vi.stubEnv('DEVX_EXTENDED_KEYS', '1');
    try {
      const composer = new TurnComposer(out, () => BASE);
      composer.start();
      expect(all()).toContain('\x1b[>1u');
      written.length = 0;
      composer.suspend();
      expect(all()).toContain('\x1b[<u');
      written.length = 0;
      composer.resume();
      expect(all()).toContain('\x1b[>1u');
      written.length = 0;
      composer.stop();
      expect(all()).toContain('\x1b[<u');
    } finally {
      vi.unstubAllEnvs();
    }
  });

  it('writes no extended-keyboard sequences on Termux and other terminals that read them as cursor / style codes', () => {
    // `CSI > 1 u` is read as "restore the cursor" and `CSI > 4 ; 1 m` as "underline, bold" there.
    vi.stubEnv('DEVX_EXTENDED_KEYS', '');
    vi.stubEnv('PREFIX', '/data/data/com.termux/files/usr');
    try {
      const composer = new TurnComposer(out, () => BASE);
      composer.start();
      composer.suspend();
      composer.resume();
      out.write('text\n');
      composer.stop();
      expect(all()).not.toMatch(/\x1b\[[<>]/);
    } finally {
      vi.unstubAllEnvs();
    }
  });

  it('wipes and draws the frame from plain attributes, so a style left on above never paints stripes', () => {
    const composer = new TurnComposer(out, () => BASE);
    composer.start();
    written.length = 0;
    out.write('\x1b[4munderlined text left open\n');
    const seq = all();
    // The erase of the old frame and each row of the new one start with a reset.
    expect(seq).toContain('\x1b[0m\x1b[1B\r\x1b[J\x1b[1A');
    expect(seq).toMatch(/\x1b\[0m\x1b\[1B\r\x1b\[2K/);
    composer.stop();
  });

  it('Ctrl+Enter writes a line break into the line that is being typed and queues nothing (kitty, modifyOtherKeys, Alt+Enter)', () => {
    for (const key of ['\x1b[13;5u', '\x1b[27;5;13~', '\x1b\r', '\x1b[13;2u']) {
      const composer = new TurnComposer(out, () => BASE);
      composer.start();
      composer.handleData('first' + key + 'second');
      expect(composer.takeQueued()).toEqual([]);
      composer.handleData('\r');
      expect(composer.takeQueued()).toEqual(['first\nsecond']);
      composer.stop();
    }
  });

  it('the line with a break in it is drawn on two rows of the frame', () => {
    const composer = new TurnComposer(out, () => BASE);
    composer.start();
    written.length = 0;
    composer.handleData('first' + CTRL_ENTER + 'second');
    const frame = all().replace(/\x1b\[[0-9;?]*[A-Za-z]|\x1b[78D]/g, '');
    expect(frame).toMatch(/first\s*\n?[^\n]*second/);
    composer.stop();
  });

  it('typing keeps working when the terminal reports keys as CSI u', () => {
    const composer = new TurnComposer(out, () => BASE);
    composer.start();
    composer.handleData('abc\x1b[97;5u');    // Ctrl+A: Home
    composer.handleData('x');
    expect(composer.draft).toBe('xabc');
    composer.handleData('\x1b[27u');         // Esc: throws the line away
    expect(composer.draft).toBe('');
    composer.stop();
  });

  it('keeps the frame whole when the working line has a line break in it', () => {
    const composer = new TurnComposer(out, () => BASE, undefined, () => 'Run: python3 - <<PY\np=1\r\nfor x in y');
    composer.start();
    composer.handleData('a');
    const drawn = all().split('\x1b[?25l').slice(-1)[0];
    // The three lines of the command are one row of the frame.
    expect(stripAnsi(drawn)).toContain('Run: python3 - <<PY p=1 for x in y');
    composer.stop();
  });

  it('queues a message on Enter, shows it, and hands it over once', () => {
    const queued: string[] = [];
    const composer = new TurnComposer(out, () => BASE, (t) => queued.push(t));
    composer.start();
    composer.handleData('also add tests\r');
    expect(composer.hasQueued()).toBe(true);
    expect(queued).toEqual(['also add tests']);
    expect(stripAnsi(all())).toContain('↳ queued: also add tests');
    expect(composer.takeQueued()).toEqual(['also add tests']);
    expect(composer.hasQueued()).toBe(false);
    expect(composer.takeQueued()).toEqual([]);
    composer.stop();
  });

  it('several messages arrive in order, even from one chunk', () => {
    const composer = new TurnComposer(out, () => BASE);
    composer.start();
    composer.handleData('first\rsecond\r');
    expect(composer.takeQueued()).toEqual(['first', 'second']);
    composer.stop();
  });

  it('leaves Ctrl+C to the caller', () => {
    const composer = new TurnComposer(out, () => BASE);
    composer.start();
    composer.handleData('abc\x03def');
    expect(composer.draft).toBe('abcdef');
    composer.stop();
  });

  it('redraws the live numbers when asked', () => {
    let cost = 0.01;
    const composer = new TurnComposer(out, () => ({ ...BASE, cost }));
    composer.start();
    written.length = 0;
    cost = 0.5;
    composer.refresh();
    expect(stripAnsi(all())).toContain('$0.5000');
    composer.stop();
  });

  it('hides the frame and passes output through while suspended, ignores the keyboard, and brings the frame back', () => {
    const composer = new TurnComposer(out, () => BASE);
    composer.start();
    composer.handleData('abc');
    written.length = 0;
    composer.suspend();
    expect(all()).toContain('\x1b[J');
    written.length = 0;
    out.write('a card');
    expect(written).toEqual(['a card']);
    composer.handleData('zzz\r');
    expect(composer.draft).toBe('abc');
    expect(composer.hasQueued()).toBe(false);
    written.length = 0;
    composer.resume();
    expect(stripAnsi(all())).toContain('abc');
    composer.handleData('d');
    expect(composer.draft).toBe('abcd');
    composer.stop();
  });

  describe('the working line', () => {
    beforeEach(() => vi.useFakeTimers());
    afterEach(() => vi.useRealTimers());

    it('sits between the output and the frame and keeps ticking while the output is quiet', () => {
      let n = 0;
      const composer = new TurnComposer(out, () => BASE, undefined, () => `working ${n}`);
      composer.start();
      const first = stripAnsi(all());
      expect(first.indexOf('working 0')).toBeGreaterThanOrEqual(0);
      expect(first.indexOf('working 0')).toBeLessThan(first.indexOf('Ask or describe a task'));
      written.length = 0;
      n = 5;
      vi.advanceTimersByTime(300);
      expect(written.length).toBeGreaterThanOrEqual(2);
      expect(stripAnsi(all())).toContain('working 5');
      composer.stop();
    });

    it('stays while the app writes: every write brings the frame and the line back', () => {
      const composer = new TurnComposer(out, () => BASE, undefined, () => 'working');
      composer.start();
      written.length = 0;
      out.write('some answer text');
      expect(stripAnsi(written[0])).toContain('working');
      composer.stop();
    });

    it('stops ticking and draws nothing more after stop() or while suspended', () => {
      const composer = new TurnComposer(out, () => BASE, undefined, () => 'working');
      composer.start();
      composer.suspend();
      written.length = 0;
      vi.advanceTimersByTime(500);
      expect(written).toHaveLength(0);
      composer.resume();
      composer.stop();
      written.length = 0;
      vi.advanceTimersByTime(500);
      expect(written).toHaveLength(0);
    });
  });

  it('does nothing on a terminal that is too small or not a terminal, and leaves write alone', () => {
    const original = out.write;
    expect(new TurnComposer({ ...out, rows: 8 }, () => BASE).start()).toBe(false);
    expect(new TurnComposer({ ...out, columns: 20 }, () => BASE).start()).toBe(false);
    const idle = new TurnComposer(Object.assign(out, { isTTY: false }), () => BASE);
    expect(idle.start()).toBe(false);
    expect(out.write).toBe(original);
    idle.handleData('abc\r');
    expect(idle.hasQueued()).toBe(false);
    expect(idle.stop()).toEqual({ draft: '', queued: [] });
  });

  it('draws again on a resize, for the new width', () => {
    const composer = new TurnComposer(out, () => BASE);
    composer.start();
    written.length = 0;
    out.columns = 50;
    composer.onResize();
    expect(written.length).toBe(1);
    expect(stripAnsi(all())).toContain('Ask or describe a task');
    composer.stop();
  });

  it('uses no saved cursor: a window that changes size in the middle of a write cannot send the cursor to a wrong row', () => {
    // ESC 7 / ESC 8 save a screen row; some terminals (Termux) do not move it with the text when the
    // soft keyboard opens or closes, and the text then lands on the rows of the old frame.
    const composer = new TurnComposer(out, () => BASE, undefined, () => 'Waiting for the model');
    composer.start();
    out.write('some output\n');
    composer.handleData('abc');
    composer.suspend();
    composer.resume();
    composer.stop();
    expect(all()).not.toMatch(/\x1b[78]/);
  });

  it('brings the cursor back to the column the output ended in, not to column 0', () => {
    const composer = new TurnComposer(out, () => BASE);
    composer.start();
    written.length = 0;
    out.write('half a line');            // 11 cells, no line break
    expect(written[0].endsWith('\x1b[12G')).toBe(true);
    written.length = 0;
    out.write(' and more\x1b[1m');        // escape sequences take no room
    expect(written[0].endsWith('\x1b[21G')).toBe(true);
    written.length = 0;
    out.write('\n');
    expect(written[0].endsWith('\x1b[1G')).toBe(true);
    composer.stop();
  });

  it('wipes the old frame from the column the cursor is in now, and draws the new one back at the column the text ends in', () => {
    const composer = new TurnComposer(out, () => BASE);
    composer.start();
    out.write('abc');                  // the cursor is in column 3
    written.length = 0;
    out.write('de\nfgh');              // ... and ends in column 3 again, on the next row
    const seq = written[0];
    expect(seq.startsWith('\x1b[0m\x1b[1B\r\x1b[J\x1b[1A\x1b[4G')).toBe(true);   // back to column 3 (CHA is 1-based) after the wipe
    expect(seq).toContain('de\nfgh');
    expect(seq.endsWith('\x1b[4G')).toBe(true);
    composer.stop();
  });

  it('keeps counting the column while a card has the screen', () => {
    const composer = new TurnComposer(out, () => BASE);
    composer.start();
    composer.suspend();
    out.write('card text');
    written.length = 0;
    composer.resume();
    expect(written.join('').endsWith('\x1b[10G')).toBe(true);
    composer.stop();
  });

  it('survives any amount of output and ends with the frame under the last line', () => {
    const composer = new TurnComposer(out, () => BASE);
    composer.start();
    for (let i = 0; i < 200; i++) out.write(`line ${i}\n`);
    expect(written[written.length - 1]).toMatch(/\x1b\[\d+G$/);   // the cursor goes back to the column of the text
    expect(stripAnsi(written[written.length - 1])).toContain('Ask or describe a task');
    composer.stop();
  });
});

describe('extended keys', () => {
  it('splitKeys keeps CSI u sequences and Alt+Enter in one piece', () => {
    expect(splitKeys('a\x1b[13;5ub')).toEqual(['a', '\x1b[13;5u', 'b']);
    expect(splitKeys('\x1b[27;5;13~')).toEqual(['\x1b[27;5;13~']);
    expect(splitKeys('\x1b\r')).toEqual(['\x1b\r']);
    expect(splitKeys('ab\x1b\x7fc')).toEqual(['a', 'b', '\x1b\x7f', 'c']);   // Alt+Backspace
    expect(splitKeys('\x1b[99;5:1u')).toEqual(['\x1b[99;5:1u']);
  });

  it('normalizeKey maps the extended encodings back to the plain keys', () => {
    expect(normalizeKey('\x1b[99;5u')).toBe('\x03');        // Ctrl+C
    expect(normalizeKey('\x1b[27;5;99~')).toBe('\x03');
    expect(normalizeKey('\x1b[27u')).toBe('\x1b');          // Esc
    expect(normalizeKey('\x1b[13u')).toBe('\r');
    expect(normalizeKey('\x1b[13;5u')).toBe(CTRL_ENTER);
    expect(normalizeKey('\x1b[13;3u')).toBe(CTRL_ENTER);     // Alt+Enter
    expect(normalizeKey('\x1b[27;5;13~')).toBe(CTRL_ENTER);
    expect(normalizeKey('\x1b\r')).toBe(CTRL_ENTER);
    expect(normalizeKey('\x1b[13;2u')).toBe('\x1b[13;2u');   // Shift+Enter is left alone
    expect(normalizeKey('\x1b[A')).toBe('\x1b[A');
    expect(normalizeKey('a')).toBe('a');
  });
});

describe('view switches while the agent works', () => {
  let written: string[];
  let out: any;
  beforeEach(() => {
    setActiveTheme('cyan');
    written = [];
    out = { write: (c: any) => { written.push(String(c)); return true; }, columns: 120, rows: 30, isTTY: true };
  });

  it('ctrl+p and ctrl+o call the toggle handler and never reach the typed line', () => {
    const composer = new TurnComposer(out, () => BASE);
    const calls: string[] = [];
    composer.onToggle = (k) => calls.push(k);
    composer.start();
    composer.handleData('ab\x10\x0fc');
    expect(calls).toEqual(['thinking', 'output']);
    expect(composer.draft).toBe('abc');
    composer.handleData('\x1b[112;5u');        // ctrl+p as the extended keyboard modes send it
    expect(calls).toEqual(['thinking', 'output', 'thinking']);
    composer.suspend();
    composer.handleData('\x10');                // a card has the keyboard
    expect(calls).toHaveLength(3);
    composer.stop();
  });

  it('shows the live reasoning line above the working line, with a blank row before the frame', () => {
    const composer = new TurnComposer(out, () => BASE, undefined, () => 'working');
    composer.live = () => '  ∴ the newest words of the thought';
    composer.start();
    const text = stripAnsi(written.join(''));
    expect(text).toContain('∴ the newest words of the thought');
    expect(text).not.toContain('ctrl+p thinking');
    expect(text).not.toContain('ctrl+o output');
    composer.stop();
  });
});

describe('a message that waits for the agent', () => {
  let written: string[];
  let out: any;
  beforeEach(() => {
    setActiveTheme('cyan');
    written = [];
    out = { write: (c: any) => { written.push(String(c)); return true; }, columns: 120, rows: 30, isTTY: true };
  });
  const lastFrame = () => stripAnsi(written.join(''));

  it('says next to it that the down arrow takes it back', () => {
    const composer = new TurnComposer(out, () => BASE);
    composer.start();
    composer.handleData('bro you are the best\r');
    expect(lastFrame()).toContain('↳ queued: bro you are the best · ↓ edit');
    composer.stop();
  });

  it('the hint gives way to the message on a narrow screen', () => {
    const rows = renderComposer({ state: { text: '', cursor: 0 }, queued: ['a fairly long message that waits'], cols: 50, base: BASE }).map(stripAnsi);
    const note = rows[rows.length - 1];
    expect(note).toContain('↳ queued: a fairly long message');
    expect(displayWidth(note)).toBeLessThanOrEqual(49);
  });

  it('the down arrow brings the last waiting message back into the line to edit it', () => {
    const queued: string[] = [];
    const composer = new TurnComposer(out, () => BASE, (t) => queued.push(t));
    composer.start();
    composer.handleData('first\r');
    composer.handleData('second\r');
    expect(composer.queued).toEqual(['first', 'second']);
    composer.handleData('\x1b[B');
    expect(composer.draft).toBe('second');
    expect(composer.queued).toEqual(['first']);
    composer.handleData('!\r');                      // edited and sent again
    expect(composer.queued).toEqual(['first', 'second!']);
    expect(composer.takeQueued()).toEqual(['first', 'second!']);
    composer.stop();
  });

  it('the down arrow does nothing while something is typed or nothing waits', () => {
    const composer = new TurnComposer(out, () => BASE);
    composer.start();
    composer.handleData('\x1b[B');
    expect(composer.draft).toBe('');
    composer.handleData('queued\r');
    composer.handleData('typing');
    composer.handleData('\x1b[B');
    expect(composer.draft).toBe('typing');
    expect(composer.queued).toEqual(['queued']);
    composer.stop();
  });

  it('works with every form of the down arrow a terminal may send', () => {
    for (const key of ['\x1bOB', '\x1b[B', '\x1b[1;1B', '\x1b[1;1:1B']) {
      const composer = new TurnComposer(out, () => BASE);
      composer.start();
      composer.handleData('msg\r');
      composer.handleData(key);
      expect(composer.draft, JSON.stringify(key)).toBe('msg');
      composer.stop();
    }
  });
});

describe('commands, the list of them, and the history on the line under the output', () => {
  let written: string[];
  let out: { write: (c: any, e?: any, cb?: any) => boolean; columns: number; rows: number; isTTY: boolean };
  const all = () => stripAnsi(written.join(''));
  const UP = '\x1b[A';
  const DOWN = '\x1b[B';

  beforeEach(() => {
    setActiveTheme('cyan');
    resetPromptHistory();
    written = [];
    out = { write: (c: any) => { written.push(String(c)); return true; }, columns: 100, rows: 40, isTTY: true };
  });
  afterEach(() => resetPromptHistory());

  const start = (setup?: (c: TurnComposer) => void) => {
    const composer = new TurnComposer(out, () => BASE);
    composer.commands = () => SLASH_COMMANDS;
    setup?.(composer);
    composer.start();
    return composer;
  };

  it('a command goes to onCommand and is not queued; a message is queued', () => {
    const taken: string[] = [];
    const composer = start((c) => { c.onCommand = (text) => { taken.push(text); return true; }; });
    composer.handleData('/settings\r');
    composer.handleData('and a message\r');
    expect(taken).toEqual(['/settings']);
    expect(composer.queued).toEqual(['and a message']);
    composer.stop();
  });

  it('a command that is not taken goes to the agent like a message', () => {
    const composer = start((c) => { c.onCommand = () => false; });
    composer.handleData('/nothing\r');
    expect(composer.queued).toEqual(['/nothing']);
    composer.stop();
  });

  it('a message that begins with a path is a message, not a command', () => {
    const taken: string[] = [];
    const composer = start((c) => { c.onCommand = (text) => { taken.push(text); return true; }; });
    composer.handleData('/home/mega/boot.img flash it\r');
    expect(taken).toEqual([]);
    expect(composer.queued).toEqual(['/home/mega/boot.img flash it']);
    composer.stop();
  });

  it('a slash opens the list of commands, and the arrows reach all of them', () => {
    const composer = start();
    composer.handleData('/');
    expect(all()).toContain(`1 / ${SLASH_COMMANDS.length}`);
    expect(all()).toContain('› /new');
    for (let i = 0; i < SLASH_COMMANDS.length; i++) composer.handleData(DOWN);
    expect(all()).toContain('› /exit');
    composer.stop();
  });

  it('typing narrows the list, Tab completes the marked command', () => {
    const composer = start();
    composer.handleData('/comp');
    expect(all()).toContain('› /compact');
    composer.handleData('\t');
    expect(composer.draft).toBe('/compact ');
    composer.stop();
  });

  it('Enter takes the marked command when only part of it is typed', () => {
    const taken: string[] = [];
    const composer = start((c) => { c.onCommand = (text) => { taken.push(text); return true; }; });
    composer.handleData('/comp\r');
    expect(taken).toEqual(['/compact']);
    composer.stop();
  });

  it('the list closes after the first word: the arguments are typed without it', () => {
    const composer = start();
    composer.handleData('/effort ');
    written.length = 0;
    composer.handleData('h');
    expect(all()).not.toContain('› /');
    composer.stop();
  });

  it('up and down bring back the lines sent before, and the draft comes back at the end', () => {
    const composer = start();
    composer.handleData('first one\r');
    composer.handleData('second one\r');
    composer.handleData('half typed');
    composer.handleData(UP);
    expect(composer.draft).toBe('second one');
    composer.handleData(UP);
    expect(composer.draft).toBe('first one');
    composer.handleData(DOWN);
    expect(composer.draft).toBe('second one');
    composer.handleData(DOWN);
    expect(composer.draft).toBe('half typed');
    composer.stop();
  });

  it('a sent line, a message or a command, is kept for the next chats', () => {
    const composer = start((c) => { c.onCommand = () => true; });
    composer.handleData('a message\r');
    composer.handleData('/model\r');
    expect(promptHistory()).toEqual(['a message', '/model']);
    composer.stop();
  });

  it('down with nothing typed still takes the waiting message back, when no history is open', () => {
    const composer = start();
    composer.handleData('waiting\r');
    composer.handleData(DOWN);
    expect(composer.draft).toBe('waiting');
    expect(composer.queued).toEqual([]);
    composer.stop();
  });
});
