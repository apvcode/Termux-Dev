import { describe, expect, it } from 'vitest';
import { Agent } from '../../src/core/loop.js';
import { History } from '../../src/core/history.js';
import type { AgentEvent, LLMProvider, TokenUsage } from '../../src/core/types.js';

const estimate = (prompt: number, completion: number): TokenUsage => ({
  promptTokens: prompt, completionTokens: completion, totalTokens: prompt + completion,
  tokensEstimated: true, cost: 0.01, costSource: 'estimated',
});

function run(provider: LLMProvider, signal?: AbortSignal) {
  const history = new History();
  history.addMessage({ role: 'user', content: 'x'.repeat(3000) });
  const agent = new Agent({ maxContextTokens: 100000, maxIterations: 2 }, provider, [], history, { check: () => false, askUser: async () => true });
  return (async () => {
    const events: AgentEvent[] = [];
    for await (const e of agent.run(signal)) events.push(e);
    return events;
  })();
}

const usages = (events: AgentEvent[]) => events.filter((e): e is Extract<AgentEvent, { type: 'usage' }> => e.type === 'usage').map((e) => e.usage);
const connectionError = () => Object.assign(new Error('read ECONNRESET'), { code: 'ECONNRESET' });
const httpError = (status: number) => Object.assign(new Error(`HTTP ${status}`), { status });

describe('a request that goes out and gets no answer', () => {
  it('is counted every time it is sent again after the connection broke', async () => {
    let calls = 0;
    const lostCalls: Array<[number, number]> = [];
    const provider: LLMProvider = {
      id: 't',
      chat: async () => {
        if (++calls < 3) throw connectionError();
        return { content: 'ok' };
      },
      lostRequestUsage: (p, c) => { lostCalls.push([p, c]); return estimate(p, c); },
    };
    const events = await run(provider);
    expect(calls).toBe(3);
    expect(usages(events)).toHaveLength(2);
    // the whole conversation went out each time: ~3000 chars of text and the system-less history
    expect(lostCalls[0][0]).toBeGreaterThan(900);
    expect(lostCalls[0][1]).toBe(0);
  });

  it('counts the request that was cancelled while the answer was streaming, with what had come', async () => {
    const controller = new AbortController();
    const lostCalls: Array<[number, number]> = [];
    const provider: LLMProvider = {
      id: 't',
      chat: async () => ({ content: '' }),
      async *chatStream() {
        yield { type: 'reasoning_delta' as const, delta: 'x'.repeat(300) };
        controller.abort();
        yield { type: 'content_delta' as const, delta: 'more' };
        return { content: '' };
      },
      lostRequestUsage: (p, c) => { lostCalls.push([p, c]); return estimate(p, c); },
    };
    const events = await run(provider, controller.signal);
    expect(usages(events)).toHaveLength(1);
    expect(lostCalls[0][1]).toBe(Math.ceil(300 * 0.33));
  });

  it('is counted when the gateway gave up waiting for a slow model (504, 524)', async () => {
    for (const status of [504, 524]) {
      let calls = 0;
      const provider: LLMProvider = {
        id: 't',
        chat: async () => { if (++calls === 1) throw httpError(status); return { content: 'ok' }; },
        lostRequestUsage: estimate,
      };
      expect(usages(await run(provider)), String(status)).toHaveLength(1);
    }
  });

  it('is not counted when the provider refused it (the request was not processed)', async () => {
    for (const status of [400, 401, 429, 503]) {
      let calls = 0;
      const provider: LLMProvider = {
        id: 't',
        chat: async () => { if (++calls === 1) throw httpError(status); return { content: 'ok' }; },
        lostRequestUsage: estimate,
      };
      expect(usages(await run(provider)), String(status)).toHaveLength(0);
    }
  });

  it('adds nothing when the provider cannot estimate, and still completes', async () => {
    let calls = 0;
    const provider: LLMProvider = {
      id: 't',
      chat: async () => { if (++calls === 1) throw connectionError(); return { content: 'ok' }; },
    };
    expect(usages(await run(provider))).toHaveLength(0);
  });

  it('does not count a request that was never sent because the turn was cancelled before it', async () => {
    const controller = new AbortController();
    controller.abort();
    const provider: LLMProvider = { id: 't', chat: async () => ({ content: 'x' }), lostRequestUsage: estimate };
    expect(usages(await run(provider, controller.signal))).toHaveLength(0);
  });
});
