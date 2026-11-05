import { afterEach, describe, expect, it, vi } from 'vitest';
import { OpenAIProvider } from '../../src/providers/openai.js';
import { createProvider } from '../../src/providers/index.js';
import { Agent } from '../../src/core/loop.js';
import { History } from '../../src/core/history.js';

const messages = [{ role: 'user' as const, content: 'hi' }];
const okJson = () => new Response(JSON.stringify({ choices: [{ message: { content: 'ok' } }] }), { status: 200 });
const sse = () =>
  new Response('data: {"choices":[{"delta":{"content":"hello"}}]}\n\ndata: [DONE]\n\n', { status: 200 });
const fail = (status: number, message: string) =>
  new Response(JSON.stringify({ error: { message } }), { status });

function mockFetch(...responses: Array<() => Response>) {
  const bodies: any[] = [];
  let i = 0;
  vi.stubGlobal('fetch', vi.fn(async (_url: string, init: { body: string }) => {
    bodies.push(JSON.parse(init.body));
    return responses[Math.min(i++, responses.length - 1)]();
  }));
  return bodies;
}

async function drain(stream: AsyncGenerator<any, any, unknown>) {
  const chunks: any[] = [];
  for await (const c of stream) chunks.push(c);
  return chunks;
}

afterEach(() => vi.unstubAllGlobals());

describe('reasoning parameters in the request', () => {
  it('are merged into the body, for regular and streamed requests', async () => {
    const bodies = mockFetch(okJson, sse);
    const provider = new OpenAIProvider('http://x/v1', 'k', 'o3', { reasoning_effort: 'high' });
    await provider.chat({ messages });
    await drain(provider.chatStream({ messages }));
    expect(bodies[0]).toMatchObject({ model: 'o3', stream: false, reasoning_effort: 'high' });
    expect(bodies[1]).toMatchObject({ model: 'o3', stream: true, reasoning_effort: 'high', stream_options: { include_usage: true } });
  });

  it('can carry nested objects and max_tokens', async () => {
    const bodies = mockFetch(okJson);
    const extra = { thinking: { type: 'enabled', budget_tokens: 16000 }, max_tokens: 32000 };
    await new OpenAIProvider('http://x/v1', 'k', 'claude', extra).chat({ messages });
    expect(bodies[0].thinking).toEqual({ type: 'enabled', budget_tokens: 16000 });
    expect(bodies[0].max_tokens).toBe(32000);
  });

  it('can never overwrite the model, the messages, the stream flag or the tools', async () => {
    const bodies = mockFetch(okJson);
    const provider = new OpenAIProvider('http://x/v1', 'k', 'real-model', {
      model: 'evil', messages: [], stream: true, tools: [], stream_options: {}, reasoning_effort: 'low',
    });
    await provider.chat({ messages, tools: [{ name: 't', description: 'd', parameters: { type: 'object', properties: {} } }] });
    expect(bodies[0].model).toBe('real-model');
    expect(bodies[0].messages).toHaveLength(1);
    expect(bodies[0].stream).toBe(false);
    expect(bodies[0].tools).toHaveLength(1);
    expect(bodies[0].reasoning_effort).toBe('low');
  });

  it('are absent when nothing was chosen', async () => {
    const bodies = mockFetch(okJson);
    await new OpenAIProvider('http://x/v1', 'k', 'o3').chat({ messages });
    await new OpenAIProvider('http://x/v1', 'k', 'o3', {}).chat({ messages });
    for (const body of bodies) {
      expect(Object.keys(body).sort()).toEqual(['messages', 'model', 'stream']);
    }
  });
});

describe('an endpoint that refuses the reasoning setting', () => {
  const extra = { reasoning_effort: 'high' };

  it('is asked again without it, once, and the user is told', async () => {
    const bodies = mockFetch(() => fail(400, "Unknown parameter: 'reasoning_effort'."), okJson);
    const provider = new OpenAIProvider('http://x/v1', 'k', 'm', extra);
    const result = await provider.chat({ messages });
    expect(result.content).toBe('ok');
    expect(bodies).toHaveLength(2);
    expect(bodies[0].reasoning_effort).toBe('high');
    expect(bodies[1].reasoning_effort).toBeUndefined();
    const notices = provider.drainNotices();
    expect(notices).toHaveLength(1);
    expect(notices[0]).toMatch(/rejected the reasoning setting/);
    expect(notices[0]).toContain("Unknown parameter: 'reasoning_effort'");
    expect(notices[0]).toContain('/effort auto');
    expect(provider.drainNotices()).toEqual([]); // delivered once
  });

  it('does not send the setting again afterwards, so later requests need no second try', async () => {
    const bodies = mockFetch(() => fail(400, 'thinking is not supported'), okJson, okJson);
    const provider = new OpenAIProvider('http://x/v1', 'k', 'm', extra);
    await provider.chat({ messages });
    await provider.chat({ messages });
    expect(bodies).toHaveLength(3); // fail, retry, one plain request
    expect(bodies[2].reasoning_effort).toBeUndefined();
  });

  it('works for streamed requests too', async () => {
    const bodies = mockFetch(() => fail(422, 'extra_body: Extra inputs are not permitted'), sse);
    const provider = new OpenAIProvider('http://x/v1', 'k', 'm', { extra_body: { google: {} } });
    const chunks = await drain(provider.chatStream({ messages }));
    expect(chunks.some((c) => c.type === 'content_delta' && c.delta === 'hello')).toBe(true);
    expect(bodies).toHaveLength(2);
    expect(bodies[1].extra_body).toBeUndefined();
    expect(provider.drainNotices()).toHaveLength(1);
  });

  it('is not mistaken for other errors: context length, auth and rate limits are reported as they are', async () => {
    for (const [status, message] of [[400, "This model's maximum context length is 8192 tokens"], [401, 'Invalid API key'], [429, 'Rate limit reached'], [500, 'Internal error']] as const) {
      const bodies = mockFetch(() => fail(status, message));
      const provider = new OpenAIProvider('http://x/v1', 'k', 'm', extra);
      await expect(provider.chat({ messages }), message).rejects.toMatchObject({ status });
      expect(bodies, message).toHaveLength(1);
      expect(provider.drainNotices()).toEqual([]);
    }
  });

  it('never retries when no setting was sent in the first place', async () => {
    const bodies = mockFetch(() => fail(400, "Unknown parameter: 'foo'"));
    await expect(new OpenAIProvider('http://x/v1', 'k', 'm').chat({ messages })).rejects.toMatchObject({ status: 400 });
    expect(bodies).toHaveLength(1);
  });

  it('reports the error of the second attempt when that fails as well', async () => {
    mockFetch(() => fail(400, 'unknown parameter reasoning_effort'), () => fail(401, 'Invalid API key'));
    await expect(new OpenAIProvider('http://x/v1', 'k', 'm', extra).chat({ messages })).rejects.toMatchObject({ status: 401 });
  });
});

describe('createProvider', () => {
  const base = { apiKey: 'k' };

  it('sends the level chosen for this model, translated for this API', async () => {
    const bodies = mockFetch(okJson, okJson, okJson);
    await createProvider({ ...base, model: 'o3', baseUrl: 'https://api.openai.com/v1', effort: { o3: 'high' } }).chat({ messages });
    await createProvider({ ...base, model: 'anthropic/claude-sonnet-4.5', baseUrl: 'https://openrouter.ai/api/v1', effort: { 'anthropic/claude-sonnet-4.5': 'normal' } }).chat({ messages });
    await createProvider({ ...base, model: 'claude-sonnet-4-5', baseUrl: 'https://api.anthropic.com/v1', effort: { 'claude-sonnet-4-5': 'high' } }).chat({ messages });
    expect(bodies[0].reasoning_effort).toBe('high');
    expect(bodies[1].reasoning).toEqual({ max_tokens: 6144 });
    expect(bodies[2].thinking).toEqual({ type: 'enabled', budget_tokens: 16000 });
    expect(bodies[2].max_tokens).toBe(32000);
  });

  it('sends nothing without a choice, or when the model does not offer the chosen level', async () => {
    const bodies = mockFetch(okJson, okJson, okJson, okJson);
    await createProvider({ ...base, model: 'o3', baseUrl: 'https://api.openai.com/v1' }).chat({ messages });
    await createProvider({ ...base, model: 'o3', baseUrl: 'https://api.openai.com/v1', effort: { o3: 'xhigh' } }).chat({ messages });
    await createProvider({ ...base, model: 'gpt-4o', baseUrl: 'https://api.openai.com/v1', effort: { 'gpt-4o': 'high' } }).chat({ messages });
    await createProvider({ ...base, model: 'deepseek-reasoner', baseUrl: 'https://api.deepseek.com', effort: { 'deepseek-reasoner': 'high' } }).chat({ messages });
    for (const body of bodies) expect(Object.keys(body).sort()).toEqual(['messages', 'model', 'stream']);
  });

  it('honours the one-off override of the --effort flag', async () => {
    const bodies = mockFetch(okJson);
    await createProvider({ ...base, model: 'o3', baseUrl: 'https://api.openai.com/v1', effort: { o3: 'high' }, effortOverride: 'low' }).chat({ messages });
    expect(bodies[0].reasoning_effort).toBe('low');
  });

  it('uses a profile from the config for a model nobody knows', async () => {
    const bodies = mockFetch(okJson);
    const config = {
      ...base, model: 'my-model', baseUrl: 'http://localhost:8000/v1', effort: { 'my-model': 'high' },
      reasoningProfiles: { 'my-model': { levels: ['normal', 'high'], param: 'reasoning.effort', values: { normal: 'medium', high: 'high' } } },
    };
    await createProvider(config).chat({ messages });
    expect(bodies[0].reasoning).toEqual({ effort: 'high' });
  });
});

describe('the agent loop', () => {
  it('passes the notices of the provider on to the user', async () => {
    const history = new History();
    history.addMessage({ role: 'user', content: 'hi' });
    const provider = {
      id: 't',
      chat: async () => ({ content: 'done' }),
      drainNotices: () => ['The endpoint rejected the reasoning setting.'],
    };
    const agent = new Agent({ maxContextTokens: 10000, maxIterations: 2 }, provider, [], history, { check: () => false, askUser: async () => true });
    const events: any[] = [];
    for await (const e of agent.run()) events.push(e);
    expect(events).toContainEqual({ type: 'system', message: 'The endpoint rejected the reasoning setting.' });
  });
});
