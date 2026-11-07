import { beforeEach, describe, expect, it } from 'vitest';
import { EventEmitter } from 'events';
import { UsagePanelData, renderBar, renderUsagePanel, runUsagePanel } from '../../src/cli/usage-panel.js';
import { setActiveTheme } from '../../src/cli/theme.js';
import { displayWidth, stripAnsi } from '../../src/cli/width.js';
import { contextBreakdown } from '../../src/core/context-stats.js';

const plain = (lines: string[]) => lines.map(stripAnsi);

const tool = { name: 'read_file', description: 'Reads a file', parameters: { type: 'object' as const, properties: { path: { type: 'string' } } } };
const context = contextBreakdown(
  [
    { role: 'system', content: 'x'.repeat(9000) },
    { role: 'user', content: 'hello there' },
    { role: 'assistant', content: 'hi', tool_calls: [{ id: '1', name: 'read_file', arguments: { path: 'a.ts' } }] },
    { role: 'tool', content: 'y'.repeat(3000), tool_call_id: '1', name: 'read_file' },
  ],
  [tool],
  128000
);

const data: UsagePanelData = {
  model: 'deepseek-v4.1-flash',
  format: 'openai',
  effort: 'max',
  context,
  compactions: 0,
  dropped: 0,
  session: {
    requestsCount: 3, bytesSent: 61_000, bytesReceived: 165_000, totalBytes: 226_000,
    promptTokens: 19_900, completionTokens: 667, totalTokens: 20_567, totalCost: 0.0058,
    costState: { estimated: true, priced: 3, unpriced: 0 }, cacheReadTokens: 4000, cacheWriteTokens: 0, uncachedTokens: 15_900,
    costParts: { input: 0.0030, cacheRead: 0.0004, cacheWrite: 0, output: 0.0024 }, lastPromptTokens: 7000,
  },
};

describe('contextBreakdown', () => {
  it('splits the context by kind and the parts add up', () => {
    expect(context.system).toBeGreaterThan(2500);
    expect(context.tools).toBeGreaterThan(0);
    expect(context.toolCount).toBe(1);
    expect(context.toolResults).toBeGreaterThan(900);
    expect(context.total).toBe(context.system + context.tools + context.user + context.assistant + context.toolResults + context.images);
    expect(context.free).toBe(context.limit - context.total);
    expect(context.compactAt).toBe(Math.floor(128000 * 0.85));
  });

  it('counts images separately', () => {
    const c = contextBreakdown([{ role: 'user', content: 'look', images: [{ path: 'a.png', dataUrl: 'data:image/png;base64,AAAA' }] }], [], 100000);
    expect(c.imageCount).toBe(1);
    expect(c.images).toBeGreaterThan(1000);
    expect(c.user).toBeLessThan(10);
  });
});

describe('the empty chat', () => {
  it('shows 0 until the first message, then everything the next request carries', () => {
    const sys = { role: 'system' as const, content: 'x'.repeat(9000) };
    expect(contextBreakdown([sys], [tool], 128000).inUse).toBe(0);
    const c = contextBreakdown([sys, { role: 'user', content: 'hi' }], [tool], 128000);
    expect(c.inUse).toBe(c.total);
    expect(c.inUse).toBeGreaterThan(c.system);
    expect(c.inUse).toBeGreaterThanOrEqual(c.system + c.tools);
  });
});

describe('the bar', () => {
  const segs = (a: number, b: number) => [
    { key: 'a', label: 'a', tokens: a, color: (s: string) => s },
    { key: 'b', label: 'b', tokens: b, color: (s: string) => s },
  ];

  it('is always as wide as asked and shows a small share with at least one cell', () => {
    for (const [a, b] of [[0, 0], [10, 0], [1, 100000], [50000, 50000], [90000, 20000]]) {
      const bar = stripAnsi(renderBar(segs(a, b), 100000, 30));
      expect(displayWidth(bar), `${a}/${b}`).toBe(30);
    }
    expect(stripAnsi(renderBar(segs(1, 0), 100000, 30)).startsWith('█')).toBe(true);
  });
});

describe('the panel', () => {
  beforeEach(() => setActiveTheme('cyan'));

  it('says what the money was paid for: input, cached input, output', () => {
    const text = plain(renderUsagePanel(data, 100, 40)).join('\n');
    const row = (label: string) => text.split('\n').find((l) => l.includes(label)) ?? '';
    expect(row('Input')).toMatch(/15\.9k.*~\$0\.0030/);
    expect(row('Cached input')).toMatch(/4\.0k.*~\$0\.0004/);
    expect(row('Output')).toMatch(/667.*~\$0\.0024/);
    expect(text).not.toContain('Cache writes');
  });

  it('says that the total includes what the chat cost before it was resumed', () => {
    const text = plain(renderUsagePanel({ ...data, session: { ...data.session, restoredCost: 0.5, costParts: undefined } }, 100, 40)).join('\n');
    expect(text).toContain('Includes ~$0.5000 from before this chat was resumed');
    expect(plain(renderUsagePanel(data, 100, 40)).join('\n')).not.toContain('resumed');
  });

  it('shows tokens only when the money cannot be split', () => {
    const text = plain(renderUsagePanel({ ...data, session: { ...data.session, costParts: undefined } }, 100, 40)).join('\n');
    const row = text.split('\n').find((l) => l.includes('Cached input')) ?? '';
    expect(row).toContain('4.0k');
    expect(row).not.toContain('$0.0004');
  });

  it('shows the context by kind, the cost and how sure it is, and the traffic', () => {
    const text = plain(renderUsagePanel(data, 100, 40)).join('\n');
    for (const needle of ['usage', 'deepseek-v4.1-flash', 'CONTEXT', 'System prompt', 'Tool schemas (1)', 'Your messages', 'Model replies', 'Tool results', 'Free',
      'SESSION', '~$0.0058', 'catalogue prices, an estimate', 'Cached input', 'TRAFFIC', 'esc close', 'the provider counted 7.0k input']) {
      expect(text, needle).toContain(needle);
    }
  });

  it('says where the size of the window comes from, and warns when it is only a guess', () => {
    const text = (limitSource: any) => plain(renderUsagePanel({ ...data, limitSource }, 100, 40)).join('\n');
    expect(text('catalogue')).toContain('from the models.dev catalogue');
    expect(text('config')).toContain('from contextLimits in your config');
    expect(text('default')).toContain('assumed, the model is unknown');
    expect(text('rule')).toContain('guessed from the model name');
  });

  it('says what the compaction did', () => {
    const text = plain(renderUsagePanel({ ...data, compactions: 2, dropped: 14 }, 100, 40)).join('\n');
    expect(text).toContain('Old messages are dropped above');
    expect(text).toContain('Happened 2x, 14 messages dropped');
  });

  it('warns that an image is not sent to a model without vision', () => {
    const withImage = { ...data, noVision: true, context: { ...context, imageCount: 1, images: 1500 } };
    expect(plain(renderUsagePanel(withImage, 100, 40)).join('\n')).toContain('cannot read images');
  });

  it('shows an unpriced session as such', () => {
    const unpriced = { ...data, session: { ...data.session, totalCost: 0, costState: { unpriced: 3, priced: 0 } } };
    const text = plain(renderUsagePanel(unpriced, 100, 40)).join('\n');
    expect(text).toContain('n/a');
    expect(text).toContain('no price known');
  });

  it('never overflows the width or the height, on a wide screen, a phone and a tiny terminal', () => {
    for (const cols of [30, 40, 59, 60, 80, 120]) {
      for (const rows of [10, 14, 20, 26, 40]) {
        const lines = renderUsagePanel(data, cols, rows);
        expect(lines.length, `${cols}x${rows} height`).toBeLessThanOrEqual(Math.max(rows - 1, 4));
        for (const line of plain(lines)) expect(displayWidth(line), `${cols}x${rows}: ${line}`).toBeLessThanOrEqual(cols);
      }
    }
  });

  it('keeps the essentials on a short screen: the bar, the cost and the key hint', () => {
    const text = plain(renderUsagePanel(data, 50, 12)).join('\n');
    expect(text).toContain('CONTEXT');
    expect(text).toContain('~$0.0058');
    expect(text).toContain('esc');
  });
});

describe('runUsagePanel', () => {
  class FakeInput extends EventEmitter {
    isRaw = false;
    modes: boolean[] = [];
    setRawMode(mode: boolean) { this.isRaw = mode; this.modes.push(mode); }
    resume() {}
  }
  let input: FakeInput;
  let chunks: string[];
  const output = Object.assign(new EventEmitter(), { columns: 100, rows: 40, write(c: string) { (this as any).sink.push(c); return true; }, sink: [] as string[] });

  beforeEach(() => {
    setActiveTheme('cyan');
    input = new FakeInput();
    output.sink = [];
    chunks = output.sink;
  });

  it('draws the panel in raw mode and closes on Esc, erasing itself', async () => {
    const done = runUsagePanel(data, { io: { input: input as any, output: output as any } });
    expect(stripAnsi(chunks[0])).toContain('CONTEXT');
    expect(input.modes).toEqual([true]);
    input.emit('data', Buffer.from('\x1b'));
    await done;
    expect(input.listenerCount('data')).toBe(0);
    expect(input.modes).toEqual([true, false]);
    expect(chunks[chunks.length - 1]).toContain('\x1b[J');
  });

  it('ignores other keys, closes on q, Enter and Ctrl+C', async () => {
    for (const key of ['q', '\r', '\x03']) {
      input = new FakeInput();
      const done = runUsagePanel(data, { io: { input: input as any, output: output as any } });
      input.emit('data', Buffer.from('x'));
      input.emit('data', Buffer.from('\x1b[A'));
      expect(input.listenerCount('data')).toBe(1);
      input.emit('data', Buffer.from(key));
      await done;
      expect(input.listenerCount('data')).toBe(0);
    }
  });

  it('redraws when the terminal is resized and stops listening when closed', async () => {
    const done = runUsagePanel(data, { io: { input: input as any, output: output as any } });
    const before = chunks.length;
    output.emit('resize');
    expect(chunks.length).toBe(before + 1);
    input.emit('data', Buffer.from('\x1b'));
    await done;
    expect(output.listenerCount('resize')).toBe(0);
  });
});
