import { describe, expect, it } from 'vitest';
import { Agent } from '../../src/core/loop.js';
import { History } from '../../src/core/history.js';
import { LLMProvider, LLMRequest, LLMResponse, Tool } from '../../src/core/types.js';

describe('reasoning blocks in the conversation', () => {
  it('are stored with the assistant message and sent back with the next request', async () => {
    const seen: LLMRequest[] = [];
    let call = 0;
    const provider: LLMProvider = {
      id: 'fake',
      async chat(request: LLMRequest): Promise<LLMResponse> {
        seen.push({ ...request, messages: request.messages.map((m) => ({ ...m })) });
        if (call++ === 0) {
          return {
            content: 'looking',
            toolCalls: [{ id: 't1', name: 'noop', arguments: {} }],
            thinkingBlocks: [{ type: 'thinking', thinking: 'hmm', signature: 'SIG' }],
            usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 },
          };
        }
        return { content: 'done', usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2, cost: 0.001, costSource: 'estimated' } };
      },
    };
    const tool: Tool = {
      name: 'noop',
      definition: { name: 'noop', description: 'x', parameters: { type: 'object', properties: {} } },
      validateArgs() {},
      async execute() { return 'ok'; },
    };
    const history = new History();
    history.addMessage({ role: 'user', content: 'go' });
    const agent = new Agent({ maxContextTokens: 100000, maxIterations: 5 }, provider, [tool], history, { check: () => false, askUser: async () => true });

    const usages: any[] = [];
    for await (const event of agent.run()) if (event.type === 'usage') usages.push(event.usage);

    const assistant = history.getMessages().find((m) => m.role === 'assistant' && m.tool_calls);
    expect(assistant?.thinking_blocks).toEqual([{ type: 'thinking', thinking: 'hmm', signature: 'SIG' }]);
    const second = seen[1].messages.find((m) => m.role === 'assistant');
    expect(second?.thinking_blocks?.[0].type).toBe('thinking');
    expect(usages[1].costSource).toBe('estimated');
  });
});

describe('messages typed while the agent works', () => {
  const tool: Tool = {
    name: 'noop',
    definition: { name: 'noop', description: 'x', parameters: { type: 'object', properties: {} } },
    validateArgs() {},
    async execute() { return 'ok'; },
  };
  const guard = { check: () => false, askUser: async () => true };

  /** A model that calls the tool `toolTurns` times, then answers. It records what it was sent. */
  const scripted = (toolTurns: number) => {
    const seen: string[][] = [];
    let call = 0;
    const provider: LLMProvider = {
      id: 'fake',
      async chat(request: LLMRequest): Promise<LLMResponse> {
        seen.push(request.messages.map((m) => `${m.role}:${m.content}`));
        if (call++ < toolTurns) return { content: '', toolCalls: [{ id: `t${call}`, name: 'noop', arguments: {} }] };
        return { content: 'done' };
      },
    };
    return { provider, seen };
  };

  it('joins the conversation at the next step, after the tool results, and the model sees it', async () => {
    const { provider, seen } = scripted(2);
    const history = new History();
    history.addMessage({ role: 'user', content: 'go' });
    const agent = new Agent({ maxContextTokens: 100000, maxIterations: 10 }, provider, [tool], history, guard);

    let queue: string[] = [];
    const events: any[] = [];
    for await (const event of agent.run(undefined, {
      takePending: () => queue.splice(0).map((content) => ({ role: 'user' as const, content })),
      hasPending: () => queue.length > 0,
    })) {
      events.push(event);
      if (event.type === 'tool_end' && queue.length === 0 && !events.some((e) => e.type === 'user_message')) queue.push('also use tabs');
    }

    expect(events.filter((e) => e.type === 'user_message')).toEqual([{ type: 'user_message', content: 'also use tabs' }]);
    // the second request already contains it, right after the first tool result
    expect(seen[0].join('|')).not.toContain('also use tabs');
    expect(seen[1].join('|')).toContain('also use tabs');
    expect(seen[1].indexOf('user:also use tabs')).toBeGreaterThan(seen[1].findIndex((m) => m.startsWith('tool:')));
    // and the tool call stayed paired with its result
    const msgs = history.getMessages();
    const callIdx = msgs.findIndex((m) => m.tool_calls);
    expect(msgs[callIdx + 1].role).toBe('tool');
  });

  it('a message typed while the model writes its last answer gets an answer instead of ending the turn', async () => {
    const { provider, seen } = scripted(0);
    const history = new History();
    history.addMessage({ role: 'user', content: 'go' });
    const agent = new Agent({ maxContextTokens: 100000, maxIterations: 10 }, provider, [tool], history, guard);

    const queue: string[] = [];
    let answers = 0;
    for await (const event of agent.run(undefined, {
      takePending: () => queue.splice(0).map((content) => ({ role: 'user' as const, content })),
      hasPending: () => queue.length > 0,
    })) {
      if (event.type === 'usage') continue;
      if (event.type === 'text') { answers++; if (answers === 1) queue.push('one more thing'); }
    }
    expect(seen).toHaveLength(2);
    expect(seen[1].join('|')).toContain('user:one more thing');
  });

  it('runs exactly as before when no hooks are given', async () => {
    const { provider, seen } = scripted(1);
    const history = new History();
    history.addMessage({ role: 'user', content: 'go' });
    const agent = new Agent({ maxContextTokens: 100000, maxIterations: 10 }, provider, [tool], history, guard);
    for await (const _ of agent.run()) { /* drain */ }
    expect(seen).toHaveLength(2);
  });
});
