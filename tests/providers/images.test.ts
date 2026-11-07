import { afterEach, describe, expect, it, vi } from 'vitest';
import { OpenAIProvider } from '../../src/providers/openai.js';
import { toAnthropicMessages } from '../../src/providers/anthropic.js';
import { imagesToSend } from '../../src/providers/shared.js';
import type { Message } from '../../src/core/types.js';

const img = (name: string) => ({ path: name, dataUrl: `data:image/png;base64,${name.toUpperCase()}` });

/** The model read two pictures in one step (parallel tool calls). */
const twoReads: Message[] = [
  { role: 'user', content: 'compare the two screenshots' },
  { role: 'assistant', content: '', tool_calls: [{ id: 'a', name: 'read_file', arguments: { path: 'a.png' } }, { id: 'b', name: 'read_file', arguments: { path: 'b.png' } }] },
  { role: 'tool', content: '[Image a.png attached]', tool_call_id: 'a', name: 'read_file', images: [img('a.png')] },
  { role: 'tool', content: '[Image b.png attached]', tool_call_id: 'b', name: 'read_file', images: [img('b.png')] },
];

describe('imagesToSend', () => {
  it('keeps only the newest pictures; a user picture and an older step become notes', () => {
    const messages: Message[] = [
      { role: 'user', content: 'see', images: [img('mine.png')] },
      { role: 'assistant', content: '', tool_calls: [{ id: 'a', name: 'read_file', arguments: {} }] },
      { role: 'tool', content: 'ok', tool_call_id: 'a', images: [img('old.png')] },
      { role: 'assistant', content: '', tool_calls: [{ id: 'b', name: 'read_file', arguments: {} }] },
      { role: 'tool', content: 'ok', tool_call_id: 'b', images: [img('new.png')] },
    ];
    expect([...imagesToSend(messages)]).toEqual([4]);
  });

  it('keeps every picture of the same step of parallel tool calls', () => {
    expect([...imagesToSend(twoReads)].sort()).toEqual([2, 3]);
  });

  it('sends nothing when there are no pictures', () => {
    expect(imagesToSend([{ role: 'user', content: 'hi' }]).size).toBe(0);
  });
});

describe('Anthropic format', () => {
  it('puts the pictures inside the tool results', () => {
    const { messages: out } = toAnthropicMessages(twoReads, { cache: false });
    const results = out[out.length - 1].content;
    expect(results).toHaveLength(2);
    for (const r of results) {
      expect(r.type).toBe('tool_result');
      expect(r.content.map((b: any) => b.type)).toEqual(['text', 'image']);
      expect(r.content[1].source).toMatchObject({ type: 'base64', media_type: 'image/png' });
    }
    expect(results[0].content[1].source.data).toBe('A.PNG');
    expect(results[1].content[1].source.data).toBe('B.PNG');
  });

  it('turns an older picture into a note and keeps the tool result a plain string', () => {
    const later: Message[] = [
      ...twoReads,
      { role: 'assistant', content: '', tool_calls: [{ id: 'c', name: 'read_file', arguments: {} }] },
      { role: 'tool', content: '[Image c.png attached]', tool_call_id: 'c', images: [img('c.png')] },
    ];
    const { messages: out } = toAnthropicMessages(later, { cache: false });
    const first = out[2].content[0];
    expect(first.content).toBe('[Image a.png attached]\n[Attached Image: a.png]');
    expect(out[out.length - 1].content[0].content[1].type).toBe('image');
  });

  it('says the picture was not sent when the model cannot read images', () => {
    const { messages: out } = toAnthropicMessages(twoReads, { cache: false, images: false });
    const block = out[out.length - 1].content[0];
    expect(typeof block.content).toBe('string');
    expect(block.content).toContain('not sent');
  });
});

describe('OpenAI format', () => {
  afterEach(() => vi.unstubAllGlobals());

  async function bodyFor(messages: Message[], options: { images?: boolean } = {}) {
    let body: any;
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: { body: string }) => {
      body = JSON.parse(init.body);
      return new Response(JSON.stringify({ choices: [{ message: { content: 'ok' } }] }), { status: 200 });
    }));
    await new OpenAIProvider('http://x/v1', 'k', 'm', undefined, options as any).chat({ messages });
    return body;
  }

  it('keeps the tool messages together, then shows the pictures in one message of the user', async () => {
    const body = await bodyFor(twoReads);
    expect(body.messages.map((m: any) => m.role)).toEqual(['user', 'assistant', 'tool', 'tool', 'user']);
    expect(typeof body.messages[2].content).toBe('string');
    const pics = body.messages[4].content;
    expect(pics[0].text).toContain('a.png, b.png');
    expect(pics.slice(1).map((p: any) => p.image_url.url)).toEqual([img('a.png').dataUrl, img('b.png').dataUrl]);
  });

  it('puts the pictures after the last tool message when more messages follow', async () => {
    const body = await bodyFor([...twoReads, { role: 'assistant', content: 'they differ' }]);
    expect(body.messages.map((m: any) => m.role)).toEqual(['user', 'assistant', 'tool', 'tool', 'user', 'assistant']);
  });

  it('adds no extra message for an older picture, only a note in its tool message', async () => {
    const later: Message[] = [
      ...twoReads,
      { role: 'assistant', content: '', tool_calls: [{ id: 'c', name: 'read_file', arguments: {} }] },
      { role: 'tool', content: 'text', tool_call_id: 'c', images: [img('c.png')] },
    ];
    const body = await bodyFor(later);
    expect(body.messages[2].content).toBe('[Image a.png attached]\n[Attached Image: a.png]');
    expect(body.messages.filter((m: any) => m.role === 'user')).toHaveLength(2);
  });

  it('sends no picture when the model cannot read images', async () => {
    const body = await bodyFor(twoReads, { images: false });
    expect(body.messages.map((m: any) => m.role)).toEqual(['user', 'assistant', 'tool', 'tool']);
    expect(body.messages[2].content).toContain('not sent');
  });
});
