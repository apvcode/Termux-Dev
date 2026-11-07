import { describe, expect, it } from 'vitest';
import { LiveBlock } from '../../src/cli/liveblock.js';
import { runPicker } from '../../src/cli/picker.js';
import { EventEmitter } from 'events';

function fakeOut(columns = 80) {
  const chunks: string[] = [];
  const emitter = new EventEmitter();
  const out = {
    columns,
    write: (c: string) => { chunks.push(c); return true; },
    on: (e: string, f: () => void) => emitter.on(e, f),
    off: (e: string, f: () => void) => emitter.off(e, f),
    resize(to: number) { out.columns = to; emitter.emit('resize'); },
    listeners: (e: string) => emitter.listenerCount(e),
  };
  return { out, chunks };
}

const line = (n: number) => 'x'.repeat(n);

describe('LiveBlock', () => {
  it('goes up by the number of lines while the window keeps its width', () => {
    const { out, chunks } = fakeOut(80);
    const block = new LiveBlock(out);
    block.draw([line(10), line(10), line(10)]);
    block.draw([line(10), line(10), line(10)]);
    expect(chunks[1].startsWith('\x1b[?25l\x1b[2A\r')).toBe(true);
  });

  it('goes up by the rows the terminal re-wrapped the lines to when the window got narrower', () => {
    const { out, chunks } = fakeOut(100);
    const block = new LiveBlock(out);
    block.draw([line(100), line(100), line(50)]); // 3 rows at 100 columns
    out.resize(40);                                 // 3 + 3 + 2 = 8 rows now
    block.draw([line(30), line(30)]);
    expect(chunks[1].startsWith('\x1b[?25l\x1b[7A\r')).toBe(true);
  });

  it('wipes the rows an old, taller block leaves behind, counted in rows too', () => {
    const { out, chunks } = fakeOut(100);
    const block = new LiveBlock(out);
    block.draw([line(100), line(100)]); // 2 rows
    out.resize(50);                      // 4 rows
    block.draw([line(10), line(10)]);    // 2 rows: 2 left over
    expect(chunks[1].endsWith('\n\x1b[2K\n\x1b[2K\x1b[2A')).toBe(true);
  });

  it('erases the whole block, counted in rows after a resize', () => {
    const { out, chunks } = fakeOut(100);
    const block = new LiveBlock(out);
    block.draw([line(100), line(100), line(100)]);
    out.resize(25);                      // 4 rows each = 12
    block.erase();
    expect(chunks[chunks.length - 1]).toBe('\r\x1b[11A\x1b[J\x1b[?25h');
  });

  it('is unchanged when the window gets wider', () => {
    const { out, chunks } = fakeOut(60);
    const block = new LiveBlock(out);
    block.draw([line(60), line(60)]);
    out.resize(200);
    block.erase();
    expect(chunks[chunks.length - 1]).toBe('\r\x1b[1A\x1b[J\x1b[?25h');
  });

  it('draws again on resize and stops after it was told to stop watching', () => {
    const { out } = fakeOut(80);
    const block = new LiveBlock(out);
    let draws = 0;
    const stop = block.watchResize(() => { draws++; });
    out.resize(60);
    out.resize(70);
    expect(draws).toBe(2);
    stop();
    out.resize(90);
    expect(draws).toBe(2);
    expect(out.listeners('resize')).toBe(0);
  });

  it('does nothing special for an output that cannot report resizes', () => {
    const block = new LiveBlock({ write: () => true });
    const stop = block.watchResize(() => {});
    expect(() => { block.draw(['a']); block.erase(); stop(); }).not.toThrow();
  });
});

describe('panels redraw when the window changes size', () => {
  it('the picker draws again on resize and lets go of the listener when closed', async () => {
    const { out, chunks } = fakeOut(80);
    const input: any = new EventEmitter();
    input.setRawMode = () => {};
    input.resume = () => {};
    const done = runPicker({ title: 'pick', items: [{ value: 'a', label: 'A' }, { value: 'b', label: 'B' }] } as any, { input, output: out as any });
    const initial = chunks.length;
    out.resize(50);
    expect(chunks.length).toBeGreaterThan(initial);
    expect(out.listeners('resize')).toBe(1);
    input.emit('data', Buffer.from('\x1b'));
    await done;
    expect(out.listeners('resize')).toBe(0);
  });
});
