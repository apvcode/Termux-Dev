import { afterEach, describe, expect, it, vi } from 'vitest';
import { AnthropicProvider, anthropicRoot, toAnthropicMessages } from '../../src/providers/anthropic.js';
import { PriceCatalog, setPriceCatalogs, slimPrices } from '../../src/core/prices.js';
import { Message } from '../../src/core/types.js';

const tool = { name: 'read_file', description: 'Reads', parameters: { type: 'object' as const, properties: { path: { type: 'string' } } } };

function mockFetch(...responses: Array<() => Response>) {
  const calls: Array<{ url: string; headers: Record<string, string>; body: any }> = [];
  let i = 0;
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: { headers: Record<string, string>; body: string }) => {
    calls.push({ url, headers: init.headers, body: JSON.parse(init.body) });
    return responses[Math.min(i++, responses.length - 1)]();
  }));
  return calls;
}

const json = (body: unknown, status = 200) => () => new Response(JSON.stringify(body), { status });
const fail = (status: number, message: string) => json({ type: 'error', error: { type: 'invalid_request_error', message } }, status);
const events = (...list: Array<Record<string, unknown> | string>) => () =>
  new Response(list.map((e) => (typeof e === 'string' ? e : `event: ${(e as any).type}\ndata: ${JSON.stringify(e)}\n\n`)).join(''), { status: 200 });

async function drain(stream: AsyncGenerator<any, any, unknown>) {
  const chunks: any[] = [];
  for await (const c of stream) chunks.push(c);
  return chunks;
}

afterEach(() => {
  vi.unstubAllGlobals();
  setPriceCatalogs(null, null);
});

const user: Message[] = [{ role: 'system', content: 'be brief' }, { role: 'user', content: 'hi' }];

describe('the request', () => {
  it('goes to /v1/messages with the Anthropic headers, wherever /v1 was written', async () => {
    const calls = mockFetch(json({ content: [{ type: 'text', text: 'ok' }], usage: { input_tokens: 1, output_tokens: 1 } }));
    await new AnthropicProvider('https://gw.example.com', 'sk-test', 'claude-x').chat({ messages: user });
    await new AnthropicProvider('https://gw.example.com/v1/', 'sk-test', 'claude-x').chat({ messages: user });
    expect(calls[0].url).toBe('https://gw.example.com/v1/messages');
    expect(calls[1].url).toBe('https://gw.example.com/v1/messages');
    expect(calls[0].headers['x-api-key']).toBe('sk-test');
    expect(calls[0].headers['anthropic-version']).toBe('2023-06-01');
    expect(anthropicRoot('https://a.b/v1/')).toBe('https://a.b');
  });

  it('sends the key as a Bearer token too, except to Anthropic itself', async () => {
    const calls = mockFetch(json({ content: [{ type: 'text', text: 'ok' }] }));
    await new AnthropicProvider('https://gw.example.com', 'k', 'm').chat({ messages: user });
    await new AnthropicProvider('https://api.anthropic.com', 'k', 'm').chat({ messages: user });
    expect(calls[0].headers['Authorization']).toBe('Bearer k');
    expect(calls[1].headers['Authorization']).toBeUndefined();
  });

  it('puts the system prompt at the top, requires max_tokens and converts the tools', async () => {
    const calls = mockFetch(json({ content: [{ type: 'text', text: 'ok' }] }));
    await new AnthropicProvider('https://gw.example.com', 'k', 'm').chat({ messages: user, tools: [tool] });
    const body = calls[0].body;
    expect(body.system[0]).toMatchObject({ type: 'text', text: 'be brief' });
    expect(body.messages.every((m: any) => m.role !== 'system')).toBe(true);
    expect(body.max_tokens).toBeGreaterThan(0);
    expect(body.tools[0]).toMatchObject({ name: 'read_file', input_schema: { type: 'object' } });
    expect(body.tools[0].parameters).toBeUndefined();
  });

  it('merges the reasoning setting but never lets it overwrite the model, messages, stream or tools', async () => {
    const calls = mockFetch(json({ content: [{ type: 'text', text: 'ok' }] }));
    const extra = { thinking: { type: 'enabled', budget_tokens: 8000 }, max_tokens: 24000, model: 'evil', messages: [], stream: true, tools: [], system: 'evil' };
    await new AnthropicProvider('https://gw.example.com', 'k', 'real', extra).chat({ messages: user, tools: [tool] });
    const body = calls[0].body;
    expect(body.thinking).toEqual({ type: 'enabled', budget_tokens: 8000 });
    expect(body.max_tokens).toBe(24000);
    expect(body.model).toBe('real');
    expect(body.stream).toBe(false);
    expect(body.tools).toHaveLength(1);
    expect(body.system[0].text).toBe('be brief');
  });
});

describe('message conversion', () => {
  it('turns tool calls and results into tool_use and tool_result blocks, merging results into one user message', () => {
    const messages: Message[] = [
      { role: 'user', content: 'do it' },
      {
        role: 'assistant', content: 'reading',
        tool_calls: [{ id: 'a', name: 'read_file', arguments: { path: 'x' } }, { id: 'b', name: 'read_file', arguments: { path: 'y' } }],
      },
      { role: 'tool', content: 'X', tool_call_id: 'a', name: 'read_file' },
      { role: 'tool', content: 'Y', tool_call_id: 'b', name: 'read_file' },
    ];
    const { messages: out } = toAnthropicMessages(messages, { cache: false });
    expect(out.map((m) => m.role)).toEqual(['user', 'assistant', 'user']);
    expect(out[1].content).toEqual([
      { type: 'text', text: 'reading' },
      { type: 'tool_use', id: 'a', name: 'read_file', input: { path: 'x' } },
      { type: 'tool_use', id: 'b', name: 'read_file', input: { path: 'y' } },
    ]);
    expect(out[2].content).toEqual([
      { type: 'tool_result', tool_use_id: 'a', content: 'X' },
      { type: 'tool_result', tool_use_id: 'b', content: 'Y' },
    ]);
  });

  it('puts saved reasoning blocks first in the assistant message, unchanged', () => {
    const messages: Message[] = [
      { role: 'user', content: 'q' },
      {
        role: 'assistant', content: 'a',
        thinking_blocks: [{ type: 'thinking', thinking: 'hmm', signature: 'SIG' }, { type: 'redacted_thinking', data: 'ZZ' }],
        tool_calls: [{ id: 't', name: 'read_file', arguments: {} }],
      },
    ];
    const { messages: out } = toAnthropicMessages(messages, { cache: false });
    expect(out[1].content.map((b) => b.type)).toEqual(['thinking', 'redacted_thinking', 'text', 'tool_use']);
    expect(out[1].content[0]).toEqual({ type: 'thinking', thinking: 'hmm', signature: 'SIG' });
  });

  it('keeps only the latest image as an image block', () => {
    const png = 'data:image/png;base64,AAAA';
    const messages: Message[] = [
      { role: 'user', content: 'old', images: [{ path: 'a.png', dataUrl: png }] },
      { role: 'assistant', content: 'seen' },
      { role: 'user', content: 'new', images: [{ path: 'b.png', dataUrl: png }] },
    ];
    const { messages: out } = toAnthropicMessages(messages, { cache: false });
    expect(out[0].content).toEqual([{ type: 'text', text: 'old\n[Attached Image: a.png]' }]);
    expect(out[2].content[0]).toEqual({ type: 'image', source: { type: 'base64', media_type: 'image/png', data: 'AAAA' } });
  });

  it('never sends an empty content block', () => {
    const { messages: out } = toAnthropicMessages([{ role: 'user', content: 'q' }, { role: 'assistant', content: '' }, { role: 'tool', content: '', tool_call_id: 'x' }], { cache: false });
    for (const m of out) for (const b of m.content) {
      if (b.type === 'text') expect(b.text).not.toBe('');
      if (b.type === 'tool_result') expect(b.content).not.toBe('');
    }
  });

  it('marks the end of the conversation for caching', () => {
    const { messages: out } = toAnthropicMessages(user, { cache: true });
    const last = out[out.length - 1].content;
    expect(last[last.length - 1].cache_control).toEqual({ type: 'ephemeral' });
  });
});

describe('a regular response', () => {
  it('reads text, tool calls, reasoning blocks and usage; cached input counts toward the prompt', async () => {
    mockFetch(json({
      content: [
        { type: 'thinking', thinking: 'plan', signature: 'S1' },
        { type: 'text', text: 'hello' },
        { type: 'tool_use', id: 'tu_1', name: 'read_file', input: { path: 'a.txt' } },
      ],
      stop_reason: 'tool_use',
      usage: { input_tokens: 20, output_tokens: 30, cache_read_input_tokens: 70, cache_creation_input_tokens: 10 },
    }));
    const res = await new AnthropicProvider('https://gw.example.com', 'k', 'm').chat({ messages: user });
    expect(res.content).toBe('hello');
    expect(res.toolCalls).toEqual([{ id: 'tu_1', name: 'read_file', arguments: { path: 'a.txt' } }]);
    expect(res.thinkingBlocks).toEqual([{ type: 'thinking', thinking: 'plan', signature: 'S1' }]);
    expect(res.usage).toMatchObject({ promptTokens: 100, completionTokens: 30, cacheReadTokens: 70, cacheWriteTokens: 10 });
  });

  it('prices cached input at the cache rate', async () => {
    setPriceCatalogs(new PriceCatalog(slimPrices({ anthropic: { models: { 'claude-x': { cost: { input: 3, output: 15, cache_read: 0.3, cache_write: 3.75 } } } } })));
    mockFetch(json({ content: [{ type: 'text', text: 'ok' }], usage: { input_tokens: 1000, output_tokens: 1000, cache_read_input_tokens: 9000 } }));
    const res = await new AnthropicProvider('https://gw.example.com', 'k', 'claude-x').chat({ messages: user });
    expect(res.usage?.cost).toBeCloseTo((1000 * 3 + 9000 * 0.3 + 1000 * 15) / 1e6, 8);
    expect(res.usage?.costSource).toBe('estimated');
  });

  it('shows an unknown price as unknown, not as zero', async () => {
    setPriceCatalogs(new PriceCatalog(slimPrices({})));
    mockFetch(json({ content: [{ type: 'text', text: 'ok' }], usage: { input_tokens: 5, output_tokens: 5 } }));
    const res = await new AnthropicProvider('https://gw.example.com', 'k', 'mystery-model').chat({ messages: user });
    expect(res.usage?.cost).toBeUndefined();
  });
});

describe('the stream', () => {
  const start = { type: 'message_start', message: { usage: { input_tokens: 12, output_tokens: 1, cache_read_input_tokens: 40 } } };

  it('streams text and reasoning, ignores heartbeats, and keeps the signature', async () => {
    mockFetch(events(
      ': heartbeat\n\n',
      start,
      { type: 'ping' },
      { type: 'content_block_start', index: 0, content_block: { type: 'thinking', thinking: '' } },
      { type: 'content_block_delta', index: 0, delta: { type: 'thinking_delta', thinking: 'let me ' } },
      { type: 'content_block_delta', index: 0, delta: { type: 'thinking_delta', thinking: 'think' } },
      { type: 'content_block_delta', index: 0, delta: { type: 'signature_delta', signature: 'SIG' } },
      { type: 'content_block_stop', index: 0 },
      ': heartbeat\n\n',
      { type: 'content_block_start', index: 1, content_block: { type: 'text', text: '' } },
      { type: 'content_block_delta', index: 1, delta: { type: 'text_delta', text: 'Hel' } },
      { type: 'content_block_delta', index: 1, delta: { type: 'text_delta', text: 'lo' } },
      { type: 'content_block_stop', index: 1 },
      { type: 'message_delta', delta: { stop_reason: 'end_turn' }, usage: { output_tokens: 25 } },
      { type: 'message_stop' },
    ));
    const chunks = await drain(new AnthropicProvider('https://gw.example.com', 'k', 'm').chatStream({ messages: user }));
    expect(chunks.filter((c) => c.type === 'reasoning_delta').map((c) => c.delta).join('')).toBe('let me think');
    expect(chunks.filter((c) => c.type === 'content_delta').map((c) => c.delta).join('')).toBe('Hello');
    const done = chunks[chunks.length - 1];
    expect(done.type).toBe('done');
    expect(done.response.content).toBe('Hello');
    expect(done.response.thinkingBlocks).toEqual([{ type: 'thinking', thinking: 'let me think', signature: 'SIG' }]);
    expect(done.response.usage).toMatchObject({ promptTokens: 52, completionTokens: 25, cacheReadTokens: 40 });
  });

  it('assembles a tool call from partial JSON and reports progress', async () => {
    mockFetch(events(
      start,
      { type: 'content_block_start', index: 0, content_block: { type: 'tool_use', id: 'tu_9', name: 'read_file', input: {} } },
      { type: 'content_block_delta', index: 0, delta: { type: 'input_json_delta', partial_json: '{"path": "src/' } },
      { type: 'content_block_delta', index: 0, delta: { type: 'input_json_delta', partial_json: 'a.ts"}' } },
      { type: 'content_block_stop', index: 0 },
      { type: 'message_delta', delta: { stop_reason: 'tool_use' }, usage: { output_tokens: 9 } },
    ));
    const chunks = await drain(new AnthropicProvider('https://gw.example.com', 'k', 'm').chatStream({ messages: user, tools: [tool] }));
    expect(chunks.some((c) => c.type === 'tool_generating' && c.name === 'read_file')).toBe(true);
    const done = chunks[chunks.length - 1].response;
    expect(done.toolCalls).toEqual([{ id: 'tu_9', name: 'read_file', arguments: { path: 'src/a.ts' } }]);
  });

  it('reports a tool call cut off by the token limit instead of running it', async () => {
    mockFetch(events(
      start,
      { type: 'content_block_start', index: 0, content_block: { type: 'tool_use', id: 'tu_1', name: 'read_file', input: {} } },
      { type: 'content_block_delta', index: 0, delta: { type: 'input_json_delta', partial_json: '{"path": "src/lo' } },
      { type: 'message_delta', delta: { stop_reason: 'max_tokens' }, usage: { output_tokens: 9 } },
    ));
    const chunks = await drain(new AnthropicProvider('https://gw.example.com', 'k', 'm').chatStream({ messages: user, tools: [tool] }));
    expect(chunks[chunks.length - 1].response.toolCalls[0].argsError).toMatch(/not valid JSON/);
  });

  it('keeps a reasoning block the request did not ask for, but drops one without a signature', async () => {
    mockFetch(events(
      start,
      { type: 'content_block_start', index: 0, content_block: { type: 'thinking', thinking: '' } },
      { type: 'content_block_delta', index: 0, delta: { type: 'thinking_delta', thinking: 'unsigned' } },
      { type: 'content_block_start', index: 1, content_block: { type: 'text', text: '' } },
      { type: 'content_block_delta', index: 1, delta: { type: 'text_delta', text: 'ok' } },
      { type: 'message_delta', delta: { stop_reason: 'end_turn' }, usage: { output_tokens: 3 } },
    ));
    const chunks = await drain(new AnthropicProvider('https://gw.example.com', 'k', 'm').chatStream({ messages: user }));
    expect(chunks[chunks.length - 1].response.thinkingBlocks).toBeUndefined();
  });

  it('fails clearly on an error event and on an empty reply', async () => {
    mockFetch(events(start, { type: 'error', error: { type: 'overloaded_error', message: 'Overloaded' } }));
    await expect(drain(new AnthropicProvider('https://gw.example.com', 'k', 'm').chatStream({ messages: user }))).rejects.toThrow(/Overloaded/);
    mockFetch(events(start, { type: 'message_delta', delta: { stop_reason: 'end_turn' }, usage: { output_tokens: 0 } }));
    await expect(drain(new AnthropicProvider('https://gw.example.com', 'k', 'm').chatStream({ messages: user }))).rejects.toThrow(/empty response/);
  });

  it('estimates the tokens and says so when the endpoint reports none', async () => {
    mockFetch(events(
      { type: 'content_block_start', index: 0, content_block: { type: 'text', text: '' } },
      { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: 'hello there' } },
    ));
    const chunks = await drain(new AnthropicProvider('https://gw.example.com', 'k', 'm').chatStream({ messages: user }));
    expect(chunks[chunks.length - 1].response.usage.tokensEstimated).toBe(true);
  });
});

describe('errors', () => {
  it('reports an HTTP error with its status and message', async () => {
    mockFetch(fail(401, 'invalid x-api-key'));
    const err: any = await new AnthropicProvider('https://gw.example.com', 'k', 'm').chat({ messages: user }).catch((e) => e);
    expect(err.status).toBe(401);
    expect(err.message).toMatch(/invalid x-api-key/);
  });

  it('retries without the reasoning setting when the endpoint rejects it, and says so once', async () => {
    const calls = mockFetch(fail(400, 'thinking: Extra inputs are not permitted'), json({ content: [{ type: 'text', text: 'ok' }] }), json({ content: [{ type: 'text', text: 'ok' }] }));
    const provider = new AnthropicProvider('https://gw.example.com', 'k', 'm', { thinking: { type: 'enabled', budget_tokens: 2000 } });
    await provider.chat({ messages: user });
    await provider.chat({ messages: user });
    expect(calls[0].body.thinking).toBeDefined();
    expect(calls[1].body.thinking).toBeUndefined();
    expect(calls[2].body.thinking).toBeUndefined();
    expect(provider.drainNotices()).toHaveLength(1);
    expect(provider.drainNotices()).toHaveLength(0);
  });

  it('retries without cache markers when they are refused', async () => {
    const calls = mockFetch(fail(400, 'cache_control is not supported'), json({ content: [{ type: 'text', text: 'ok' }] }));
    await new AnthropicProvider('https://gw.example.com', 'k', 'm').chat({ messages: user, tools: [tool] });
    expect(JSON.stringify(calls[0].body)).toContain('cache_control');
    expect(JSON.stringify(calls[1].body)).not.toContain('cache_control');
  });

  it('retries with the maximum named in the error when max_tokens is too large', async () => {
    const calls = mockFetch(fail(400, 'max_tokens: 16000 > 8192, which is the maximum allowed number of output tokens for m'), json({ content: [{ type: 'text', text: 'ok' }] }));
    await new AnthropicProvider('https://gw.example.com', 'k', 'm').chat({ messages: user });
    expect(calls[1].body.max_tokens).toBe(8192);
  });

  it('retries without saved reasoning blocks when their signature is refused', async () => {
    const calls = mockFetch(fail(400, 'messages.1.content.0: Invalid `signature` in `thinking` block'), json({ content: [{ type: 'text', text: 'ok' }] }));
    const provider = new AnthropicProvider('https://gw.example.com', 'k', 'm');
    await provider.chat({
      messages: [
        { role: 'user', content: 'q' },
        { role: 'assistant', content: 'a', thinking_blocks: [{ type: 'thinking', thinking: 't', signature: 'old' }] },
        { role: 'user', content: 'again' },
      ],
    });
    expect(JSON.stringify(calls[0].body)).toContain('"thinking":"t"');
    expect(JSON.stringify(calls[1].body)).not.toContain('"thinking":"t"');
    expect(provider.drainNotices()).toHaveLength(1);
  });

  it('does not retry an unrelated 400', async () => {
    const calls = mockFetch(fail(400, 'messages: text content blocks must be non-empty'), json({ content: [] }));
    await expect(new AnthropicProvider('https://gw.example.com', 'k', 'm').chat({ messages: user })).rejects.toThrow(/non-empty/);
    expect(calls).toHaveLength(1);
  });
});

describe('a gateway that refuses without saying why, while a reasoning setting is sent', () => {
  const vague = 'The model rejected this conversation or its parameters. Start a new conversation or adjust the request.';
  const ok = json({ content: [{ type: 'text', text: 'ok' }], usage: { input_tokens: 1, output_tokens: 1 } });

  it('is asked again without the setting once, and works from then on', async () => {
    const calls = mockFetch(fail(400, vague), ok, ok);
    const provider = new AnthropicProvider('https://gw.example/anthropic', 'k', 'claude-x', { thinking: { type: 'enabled', budget_tokens: 4000 } });
    expect((await provider.chat({ messages: user })).content).toBe('ok');
    expect(calls).toHaveLength(2);
    expect(calls[0].body.thinking).toBeDefined();
    expect(calls[1].body.thinking).toBeUndefined();
    expect(provider.drainNotices().join(' ')).toMatch(/reasoning setting.*Continuing without it/);
    await provider.chat({ messages: user });
    expect(calls).toHaveLength(3);                      // no second try
    expect(calls[2].body.thinking).toBeUndefined();
  });

  it('when it was not the setting, the real error comes back and the setting is kept', async () => {
    const calls = mockFetch(fail(400, vague), fail(400, vague), fail(400, vague));
    const provider = new AnthropicProvider('https://gw.example/anthropic', 'k', 'claude-x', { thinking: { type: 'enabled', budget_tokens: 4000 } });
    await expect(provider.chat({ messages: user })).rejects.toThrow('Provider HTTP Error 400');
    expect(calls).toHaveLength(2);                      // one try without it, then the error
    expect(provider.drainNotices()).toEqual([]);
    await expect(provider.chat({ messages: user })).rejects.toThrow();
    expect(calls[2].body.thinking).toBeDefined();       // sent again: it was never the cause
  });

  it('without a reasoning setting there is no extra try', async () => {
    const calls = mockFetch(fail(400, vague));
    await expect(new AnthropicProvider('https://gw.example/anthropic', 'k', 'claude-x').chat({ messages: user })).rejects.toThrow();
    expect(calls).toHaveLength(1);
  });

  it('OpenAI-style reasoning keys are never sent in the Anthropic format', async () => {
    const calls = mockFetch(ok);
    await new AnthropicProvider('https://gw.example/anthropic', 'k', 'claude-x', { reasoning_effort: 'high', reasoning: { effort: 'high' } }).chat({ messages: user });
    expect(calls[0].body.reasoning_effort).toBeUndefined();
    expect(calls[0].body.reasoning).toBeUndefined();
  });
});
