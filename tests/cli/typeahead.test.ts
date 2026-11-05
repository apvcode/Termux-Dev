import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DraftState, FrameBase, TurnComposer, applyKey, renderComposer } from '../../src/cli/typeahead.js';
import { setActiveTheme } from '../../src/cli/theme.js';
import { displayWidth, stripAnsi } from '../../src/cli/width.js';

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

const BASE: FrameBase = { mode: 'agent', model: 'glm-5.3-flash · think max', cwd: '/home/mega/Downloads/test', contextPercent: 12, cost: 0.0123 };

describe('the composer frame', () => {
  beforeEach(() => setActiveTheme('cyan'));
  const draw = (text: string, queued: string[] = [], cols = 100, cursor = Array.from(text).length) =>
    renderComposer({ state: { text, cursor }, queued, cols, base: BASE });

  it('is the prompt frame: mode, model, folder, context and cost, with the placeholder on an empty line', () => {
    const lines = draw('').map(stripAnsi);
    expect(lines).toHaveLength(4);
    expect(lines[0]).toContain('agent');
    expect(lines[0]).toContain('glm-5.3-flash · think max');
    expect(lines[1]).toContain('Ask or describe a task');
    expect(lines[2]).toContain('~/Downloads/test');
    expect(lines[2]).toContain('12% · $0.0123');
    expect(lines[3]).toContain('enter queues it for the agent');
  });

  it('shows the text and a cursor cell at the right place', () => {
    const raw = draw('use tabs', [], 100, 3);
    const input = raw[1];
    expect(stripAnsi(input)).toContain('use  tabs');             // the cursor cell sits after "use"
    expect(input).toContain('\x1b[7m \x1b[27m');
    expect(draw('use tabs').map(stripAnsi)[1]).toContain('use tabs ');
  });

  it('shows what is queued under the frame', () => {
    expect(draw('', ['also add tests']).map(stripAnsi)[3]).toContain('↳ queued: also add tests');
    expect(draw('', ['a', 'second one']).map(stripAnsi)[3]).toContain('↳ 2 queued: second one');
  });

  it('keeps one height for every state, so the output region never moves', () => {
    const heights = new Set(
      [draw(''), draw('x'.repeat(300)), draw('hi', ['q']), draw('', ['a', 'b'])].map((l) => l.length)
    );
    expect(heights.size).toBe(1);
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

  it('survives any amount of output and ends with the frame under the last line', () => {
    const composer = new TurnComposer(out, () => BASE);
    composer.start();
    for (let i = 0; i < 200; i++) out.write(`line ${i}\n`);
    expect(written[written.length - 1]).toContain('\x1b8');   // the cursor goes back to the text
    expect(stripAnsi(written[written.length - 1])).toContain('Ask or describe a task');
    composer.stop();
  });
});
