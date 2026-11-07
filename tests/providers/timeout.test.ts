import { afterEach, describe, expect, it, vi } from 'vitest';
import { OpenAIProvider } from '../../src/providers/openai.js';
import { AnthropicProvider } from '../../src/providers/anthropic.js';
import { RequestWatchdog } from '../../src/providers/shared.js';
import { createProvider } from '../../src/providers/index.js';
import { isNetworkOrRetryableError } from '../../src/core/network.js';

const messages = [{ role: 'user' as const, content: 'hi' }];
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
const abortError = () => Object.assign(new Error('This operation was aborted'), { name: 'AbortError' });

/** A response whose body sends the given chunks, `gap` ms apart, and then goes silent for ever (or ends). */
function slowBody(signal: AbortSignal | undefined, chunks: string[], gap: number, end: boolean) {
  const enc = new TextEncoder();
  let i = 0;
  return new ReadableStream<Uint8Array>({
    start(controller) {
      signal?.addEventListener('abort', () => controller.error(abortError()));
    },
    async pull(controller) {
      if (i < chunks.length) {
        await sleep(gap);
        controller.enqueue(enc.encode(chunks[i++]));
      } else if (end) {
        controller.close();
      } else {
        await new Promise(() => {}); // silent: the connection is dead
      }
    },
  });
}

const sse = (text: string) => `data: ${JSON.stringify({ choices: [{ delta: { content: text } }] })}\n\n`;

function stubFetch(make: (init: { signal?: AbortSignal }) => Promise<Response>) {
  vi.stubGlobal('fetch', vi.fn(async (_url: string, init: { signal?: AbortSignal }) => make(init)));
}

async function drain(stream: AsyncGenerator<any, any, unknown>) {
  const chunks: any[] = [];
  for await (const c of stream) chunks.push(c);
  return chunks;
}

afterEach(() => vi.unstubAllGlobals());

describe('RequestWatchdog', () => {
  it('aborts its signal when nothing happens for the time, and says it was the time', async () => {
    const wd = new RequestWatchdog(40);
    await sleep(90);
    expect(wd.signal.aborted).toBe(true);
    expect(wd.timedOut).toBe(true);
    expect((wd.error() as any).code).toBe('ETIMEDOUT');
    expect(isNetworkOrRetryableError(wd.error())).toBe(true);
    wd.stop();
  });

  it('starts over with every touch', async () => {
    const wd = new RequestWatchdog(60);
    for (let i = 0; i < 4; i++) { await sleep(30); wd.touch(); }
    expect(wd.timedOut).toBe(false);
    wd.stop();
  });

  it('follows the caller\'s own signal without calling that a timeout', () => {
    const parent = new AbortController();
    const wd = new RequestWatchdog(1000, parent.signal);
    parent.abort();
    expect(wd.signal.aborted).toBe(true);
    expect(wd.timedOut).toBe(false);
    wd.stop();
  });

  it('is off with 0', async () => {
    const wd = new RequestWatchdog(0);
    await sleep(40);
    expect(wd.signal.aborted).toBe(false);
    wd.stop();
  });
});

describe('a connection that went silent', () => {
  it('OpenAI format: a stream that stops sending is given up on, as a lost connection', async () => {
    stubFetch(async (init) => new Response(slowBody(init.signal, [sse('he')], 5, false)));
    const provider = new OpenAIProvider('http://x/v1', 'k', 'm', undefined, { timeoutMs: 80 });
    const seen: string[] = [];
    let error: any;
    try {
      for await (const c of provider.chatStream({ messages })) if (c.type === 'content_delta') seen.push(c.delta);
    } catch (e) { error = e; }
    expect(seen).toEqual(['he']);
    expect(error?.code).toBe('ETIMEDOUT');
    expect(error.message).toContain('No response');
    expect(isNetworkOrRetryableError(error)).toBe(true);
  });

  it('OpenAI format: a request that never gets an answer is given up on', async () => {
    stubFetch((init) => new Promise((_, reject) => init.signal?.addEventListener('abort', () => reject(abortError()))));
    const provider = new OpenAIProvider('http://x/v1', 'k', 'm', undefined, { timeoutMs: 60 });
    await expect(provider.chat({ messages })).rejects.toMatchObject({ code: 'ETIMEDOUT' });
    await expect(drain(provider.chatStream({ messages }))).rejects.toMatchObject({ code: 'ETIMEDOUT' });
  });

  it('OpenAI format: a stream that keeps sending is never cut, however long it takes in all', async () => {
    const chunks = Array.from({ length: 8 }, (_, i) => sse(`w${i} `)).concat('data: [DONE]\n\n');
    stubFetch(async (init) => new Response(slowBody(init.signal, chunks, 30, true)));
    const provider = new OpenAIProvider('http://x/v1', 'k', 'm', undefined, { timeoutMs: 120 });
    const out = await drain(provider.chatStream({ messages }));
    const done = out.find((c) => c.type === 'done');
    expect(done.response.content).toBe('w0 w1 w2 w3 w4 w5 w6 w7 ');
  });

  it('a stream cut for silence is not counted as an answer in the session usage', async () => {
    const { UsageTracker } = await import('../../src/core/usage.js');
    UsageTracker.getInstance().reset();
    stubFetch(async (init) => new Response(slowBody(init.signal, [sse('he')], 5, false)));
    const provider = new OpenAIProvider('http://x/v1', 'k', 'm', undefined, { timeoutMs: 60 });
    await expect(drain(provider.chatStream({ messages }))).rejects.toMatchObject({ code: 'ETIMEDOUT' });
    expect(UsageTracker.getInstance().getSummary().promptTokens).toBe(0);
  });

  it('the caller cancelling is still a cancel, not a timeout', async () => {
    stubFetch(async (init) => new Response(slowBody(init.signal, [sse('a')], 5, false)));
    const provider = new OpenAIProvider('http://x/v1', 'k', 'm', undefined, { timeoutMs: 5000 });
    const controller = new AbortController();
    const run = drain(provider.chatStream({ messages, signal: controller.signal }));
    setTimeout(() => controller.abort(), 30);
    const result = await run.then(() => 'ended', (e) => e);
    expect(result?.code).not.toBe('ETIMEDOUT');
  });

  it('Anthropic format: a stream that stops sending is given up on', async () => {
    const ev = (o: any) => `event: ${o.type}\ndata: ${JSON.stringify(o)}\n\n`;
    stubFetch(async (init) => new Response(slowBody(init.signal, [ev({ type: 'message_start', message: { usage: { input_tokens: 5 } } })], 5, false)));
    const provider = new AnthropicProvider('https://api.anthropic.com', 'k', 'claude-sonnet-4-5', undefined, { timeoutMs: 80 });
    await expect(drain(provider.chatStream({ messages }))).rejects.toMatchObject({ code: 'ETIMEDOUT' });
  });
});

describe('the limit from the config', () => {
  it('requestTimeoutSeconds sets it, 0 turns it off, and nonsense keeps the default', async () => {
    const hang = (init: { signal?: AbortSignal }) => new Promise<Response>((_, reject) => init.signal?.addEventListener('abort', () => reject(abortError())));
    const base = { apiKey: 'k', model: 'gpt-4o', baseUrl: 'https://api.openai.com/v1' };

    stubFetch(hang);
    const started = Date.now();
    await expect(createProvider({ ...base, requestTimeoutSeconds: 0.05 }).chat({ messages })).rejects.toMatchObject({ code: 'ETIMEDOUT' });
    expect(Date.now() - started).toBeLessThan(2000);

    // 0 and garbage: not cut within a quarter of a second
    for (const requestTimeoutSeconds of [0, 'soon' as any, -5]) {
      stubFetch(hang);
      const result = await Promise.race([
        createProvider({ ...base, requestTimeoutSeconds }).chat({ messages }).then(() => 'answered', () => 'failed'),
        sleep(250).then(() => 'still waiting'),
      ]);
      expect(result, String(requestTimeoutSeconds)).toBe('still waiting');
    }
  });
});
