import { describe, it, expect, vi } from 'vitest';
import { Agent } from '../../src/core/loop.js';
import { History } from '../../src/core/history.js';
import { LLMProvider, Tool, PermissionGuard, ToolCall } from '../../src/core/types.js';

const calls: ToolCall[] = [
  { id: 'first', name: 'test_tool', arguments: {} },
  { id: 'second', name: 'test_tool', arguments: {} },
];

function setup(provider: LLMProvider, tool?: Tool, guard?: PermissionGuard) {
  const history = new History();
  history.addMessage({ role: 'user', content: 'Run the tools' });
  const agent = new Agent(
    { maxContextTokens: 10000, maxIterations: 3 },
    provider,
    tool ? [tool] : [],
    history,
    guard || { check: () => false, askUser: async () => true },
  );
  return { agent, history };
}

function createTool(execute: Tool['execute']): Tool {
  return {
    name: 'test_tool',
    definition: { name: 'test_tool', description: 'Test', parameters: { type: 'object', properties: {} } },
    validateArgs() {},
    execute,
  };
}

function expectPairedCalls(history: History) {
  const replies = history.getMessages().filter(message => message.role === 'tool');
  expect(replies.map(message => message.tool_call_id)).toEqual(['first', 'second']);
}

describe('Agent cancellation', () => {
  it('does not contact the provider when already cancelled', async () => {
    const controller = new AbortController();
    controller.abort();
    const chat = vi.fn();
    const { agent } = setup({ id: 'test', chat });
    for await (const _ of agent.run(controller.signal)) {}
    expect(chat).not.toHaveBeenCalled();
  });

  it('cancels a pending provider request without reporting an API error', async () => {
    const controller = new AbortController();
    const { agent } = setup({
      id: 'test',
      chat: ({ signal }) => new Promise((_resolve, reject) => {
        signal!.addEventListener('abort', () => reject(new DOMException('Cancelled', 'AbortError')), { once: true });
        controller.abort();
      }),
    });
    const events = [];
    for await (const event of agent.run(controller.signal)) events.push(event);
    expect(events).toEqual([]);
  });

  it('stops an active response stream without starting another request', async () => {
    const controller = new AbortController();
    const chat = vi.fn();
    const { agent, history } = setup({
      id: 'test',
      chat,
      async *chatStream() {
        yield { type: 'content_delta', delta: 'First part' };
        controller.abort();
        yield { type: 'content_delta', delta: 'Must not appear' };
        return { content: 'Complete response' };
      },
    });
    const events = [];
    for await (const event of agent.run(controller.signal)) events.push(event);
    expect(events).toEqual([{ type: 'text_delta', delta: 'First part' }]);
    expect(chat).not.toHaveBeenCalled();
    expect(history.getMessages()).toHaveLength(1);
  });

  it('passes cancellation to the running tool and pairs remaining calls', async () => {
    const controller = new AbortController();
    const execute = vi.fn<Tool['execute']>(async (_args, _config, context) => {
      expect(context?.signal).toBe(controller.signal);
      return new Promise(resolve => {
        context!.signal!.addEventListener('abort', () => resolve('[Operation cancelled by user]'), { once: true });
        controller.abort();
      });
    });
    const chat = vi.fn(async () => ({ content: '', toolCalls: calls }));
    const { agent, history } = setup({ id: 'test', chat }, createTool(execute));
    for await (const _ of agent.run(controller.signal)) {}
    expect(execute).toHaveBeenCalledTimes(1);
    expect(chat).toHaveBeenCalledTimes(1);
    expectPairedCalls(history);
  });

  it('does not execute after cancellation during permission confirmation', async () => {
    const controller = new AbortController();
    const execute = vi.fn(async () => 'done');
    const { agent, history } = setup(
      { id: 'test', chat: async () => ({ content: '', toolCalls: calls }) },
      createTool(execute),
      { check: () => true, askUser: async () => { controller.abort(); return true; } },
    );
    for await (const _ of agent.run(controller.signal)) {}
    expect(execute).not.toHaveBeenCalled();
    expectPairedCalls(history);
  });

  it.each(['tool_start', 'tool_end'])('keeps history valid when the UI stops at %s', async (stopAt) => {
    const execute = vi.fn(async () => 'done');
    const { agent, history } = setup(
      { id: 'test', chat: async () => ({ content: '', toolCalls: calls }) },
      createTool(execute),
    );
    for await (const event of agent.run()) {
      if (event.type === stopAt) break;
    }
    expectPairedCalls(history);
    expect(execute).toHaveBeenCalledTimes(stopAt === 'tool_start' ? 0 : 1);
  });
});

describe('Agent and background news', () => {
  const answer = (content: string) => ({ content });

  it('puts the news in the conversation before the next request and says so', async () => {
    const seen: string[][] = [];
    const provider: LLMProvider = {
      id: 'mock',
      chat: async (request) => {
        seen.push(request.messages.map(m => m.content));
        return answer('ok');
      },
    };
    const { agent, history } = setup(provider);
    const news = ['[devx · background tasks]\nbg1 "server" failed (exit code 1) after 3s.'];
    const events: string[] = [];
    for await (const event of agent.run(undefined, { takeNotes: () => news.shift() ?? '' })) events.push(event.type);

    expect(events).toContain('background_note');
    expect(seen[0].some(c => c.includes('bg1 "server" failed'))).toBe(true);
    expect(history.getMessages().filter(m => m.role === 'user').map(m => m.content)).toContain('[devx · background tasks]\nbg1 "server" failed (exit code 1) after 3s.');
  });

  it('does not end the turn while news waits: the model answers it', async () => {
    let requests = 0;
    // The news arrives while the model is writing its answer, after the step began.
    let waiting = false;
    const provider: LLMProvider = { id: 'mock', chat: async () => { requests++; if (requests === 1) waiting = true; return answer('ok'); } };
    const { agent } = setup(provider);
    let text = '';
    for await (const _ of agent.run(undefined, {
      hasNotes: () => waiting,
      takeNotes: () => { if (!waiting) return ''; waiting = false; return 'news'; },
    })) { /* drain */ }
    text = String(requests);
    expect(text).toBe('2');
  });

  it('nothing changes without news', async () => {
    let requests = 0;
    const provider: LLMProvider = { id: 'mock', chat: async () => { requests++; return answer('ok'); } };
    const { agent } = setup(provider);
    for await (const _ of agent.run(undefined, { takeNotes: () => '', hasNotes: () => false })) { /* drain */ }
    expect(requests).toBe(1);
  });
});
