import { afterEach, describe, expect, it, vi } from 'vitest';
import fs from 'fs/promises';
import os from 'os';
import path from 'path';
import { History } from '../../src/core/history.js';
import { Agent } from '../../src/core/loop.js';
import { SnapshotManager } from '../../src/core/snapshot.js';
import { LLMProvider, Message, Tool } from '../../src/core/types.js';

describe('History token budget', () => {
  it('counts source code at roughly 3 characters per token', () => {
    const code = 'function add(a: number, b: number) { return a + b; }\n'.repeat(200);
    const tokens = new History().estimateTokens([{ role: 'user', content: code }]);
    expect(tokens / code.length).toBeGreaterThanOrEqual(0.3);
  });

  it('counts attached images', () => {
    const tokens = new History().estimateTokens([
      { role: 'user', content: '', images: [{ path: 'a.png', dataUrl: 'data:image/png;base64,AAAA' }] },
    ]);
    expect(tokens).toBeGreaterThanOrEqual(1000);
  });

  it('shrinks a huge tool result in the newest turn instead of staying over the limit', () => {
    const history = new History();
    history.updateSystemPrompt('system');
    history.addMessage({ role: 'user', content: 'read the big file' });
    history.addMessage({
      role: 'assistant',
      content: '',
      tool_calls: [{ id: 'c1', name: 'read_file', arguments: { path: 'big.js' } }],
    });
    history.addMessage({ role: 'tool', content: 'BEGIN' + 'x'.repeat(400000) + 'END', tool_call_id: 'c1', name: 'read_file' });

    expect(history.getTotalTokens()).toBeGreaterThan(100000);
    expect(history.pruneToLimit(3000)).toBe(true);

    expect(history.getTotalTokens()).toBeLessThanOrEqual(3000);
    const tool = history.getMessages().find(m => m.role === 'tool')!;
    expect(tool.content).toContain('BEGIN');
    expect(tool.content).toContain('END');
    expect(tool.content).toContain('characters removed');
    // the call / result pairing survives
    expect(history.getMessages().filter(m => m.role === 'assistant')).toHaveLength(1);
  });
});

describe('Agent context handling', () => {
  const echoTool = (execute: Tool['execute'] = async () => 'ok'): Tool => ({
    name: 'test_tool',
    definition: { name: 'test_tool', description: 'Test', parameters: { type: 'object', properties: {} } },
    validateArgs() {},
    execute,
  });

  async function drain(agent: Agent) {
    const events: any[] = [];
    for await (const event of agent.run()) events.push(event);
    return events;
  }

  it('keeps part of the context window free instead of filling it to the brim', async () => {
    const history = new History();
    history.addMessage({ role: 'user', content: 'start' });
    for (let i = 0; i < 40; i++) {
      history.addMessage({ role: 'assistant', content: 'a'.repeat(300), tool_calls: [{ id: `c${i}`, name: 'test_tool', arguments: {} }] });
      history.addMessage({ role: 'tool', content: 'r'.repeat(300), tool_call_id: `c${i}`, name: 'test_tool' });
    }
    history.addMessage({ role: 'user', content: 'go on' });

    let sent: Message[] = [];
    const provider: LLMProvider = {
      id: 't',
      chat: async (req) => { sent = req.messages; return { content: 'done' }; },
    };
    const limit = 4000;
    await drain(new Agent({ maxContextTokens: limit, maxIterations: 2 }, provider, [], history, { check: () => false, askUser: async () => true }));

    expect(new History().estimateTokens(sent)).toBeLessThanOrEqual(limit * 0.85);
  });

  it('compacts and retries when the provider says the prompt is too long', async () => {
    const history = new History();
    history.addMessage({ role: 'user', content: 'first' });
    for (let i = 0; i < 10; i++) {
      history.addMessage({ role: 'assistant', content: 'a'.repeat(500), tool_calls: [{ id: `c${i}`, name: 'test_tool', arguments: {} }] });
      history.addMessage({ role: 'tool', content: 'r'.repeat(500), tool_call_id: `c${i}`, name: 'test_tool' });
    }
    history.addMessage({ role: 'user', content: 'latest question' });

    const sizes: number[] = [];
    let calls = 0;
    const provider: LLMProvider = {
      id: 't',
      chat: async (req) => {
        sizes.push(req.messages.length);
        if (++calls === 1) {
          throw Object.assign(new Error("This model's maximum context length is 8192 tokens, however you requested 9000"), { status: 400 });
        }
        return { content: 'recovered' };
      },
    };
    const events = await drain(new Agent({ maxContextTokens: 1_000_000, maxIterations: 2 }, provider, [], history, { check: () => false, askUser: async () => true }));

    expect(calls).toBe(2);
    expect(sizes[1]).toBeLessThan(sizes[0]);
    expect(events.some(e => e.type === 'system' && /too long/.test(e.message))).toBe(true);
    expect(events.some(e => e.type === 'error')).toBe(false);
  });

  it('still reports unrelated API errors as fatal', async () => {
    const history = new History();
    history.addMessage({ role: 'user', content: 'hi' });
    const chat = vi.fn(async () => { throw Object.assign(new Error('Invalid API key'), { status: 401 }); });
    const events = await drain(new Agent({ maxContextTokens: 10000, maxIterations: 2 }, { id: 't', chat }, [], history, { check: () => false, askUser: async () => true }));
    expect(chat).toHaveBeenCalledTimes(1);
    expect(events).toContainEqual(expect.objectContaining({ type: 'error', isFatal: true }));
  });

  it('still talks to the provider when the config has no maxIterations', async () => {
    const history = new History();
    history.addMessage({ role: 'user', content: 'hi' });
    const chat = vi.fn(async () => ({ content: 'hello' }));
    const events = await drain(new Agent({ maxContextTokens: 10000 } as any, { id: 't', chat }, [], history, { check: () => false, askUser: async () => true }));
    expect(chat).toHaveBeenCalledTimes(1);
    expect(history.getMessages().at(-1)?.content).toBe('hello');
    expect(events.some(e => e.type === 'error')).toBe(false);
  });

  it('does not execute a tool whose arguments could not be parsed', async () => {
    const history = new History();
    history.addMessage({ role: 'user', content: 'write a file' });
    const execute = vi.fn(async () => 'should not run');
    let n = 0;
    const provider: LLMProvider = {
      id: 't',
      chat: async () =>
        ++n === 1
          ? { content: '', toolCalls: [{ id: 'x', name: 'test_tool', arguments: {}, argsError: 'arguments were cut off' }] }
          : { content: 'ok, retrying smaller' },
    };
    await drain(new Agent({ maxContextTokens: 10000, maxIterations: 3 }, provider, [echoTool(execute)], history, { check: () => false, askUser: async () => true }));

    expect(execute).not.toHaveBeenCalled();
    const reply = history.getMessages().find(m => m.role === 'tool')!;
    expect(reply.tool_call_id).toBe('x');
    expect(reply.content).toContain('arguments were cut off');
  });
});

describe('SnapshotManager binary safety', () => {
  const dirs: string[] = [];
  afterEach(async () => {
    await Promise.all(dirs.splice(0).map(d => fs.rm(d, { recursive: true, force: true })));
  });

  it('restores binary files byte for byte', async () => {
    const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'devx-snap-bin-'));
    dirs.push(dir);
    const file = path.join(dir, 'logo.png');
    const original = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0xff, 0xfe, 0x00, 0x80, 0xc3, 0x28]);
    await fs.writeFile(file, original);

    const manager = new SnapshotManager();
    manager.beginTurn();
    await manager.recordFileBeforeChange(file);
    await fs.writeFile(file, 'overwritten');
    manager.finishTurn();
    await manager.undoLastTurn();

    expect(Buffer.compare(await fs.readFile(file), original)).toBe(0);
  });
});
