import { describe, it, expect } from 'vitest';
import { History } from '../../src/core/history.js';
import { Message } from '../../src/core/types.js';

describe('History', () => {
  it('adds and retrieves messages', () => {
    const history = new History();
    history.addMessage({ role: 'user', content: 'Hello' });
    history.addMessage({ role: 'assistant', content: 'Hi there' });

    expect(history.getMessages()).toHaveLength(2);
    expect(history.getMessages()[0].content).toBe('Hello');
  });

  it('updates or inserts system prompt correctly', () => {
    const history = new History();
    history.updateSystemPrompt('Initial prompt');
    expect(history.getMessages()[0]).toEqual({ role: 'system', content: 'Initial prompt' });

    history.addMessage({ role: 'user', content: 'Hello' });
    history.updateSystemPrompt('Updated prompt');
    expect(history.getMessages()[0]).toEqual({ role: 'system', content: 'Updated prompt' });
    expect(history.getMessages()).toHaveLength(2);
  });

  it('estimates token count accurately', () => {
    const history = new History();
    const tokens = history.estimateTokens([
      { role: 'user', content: 'Hello world! 12345' }
    ]);
    expect(tokens).toBeGreaterThan(0);
  });

  it('prunes history when exceeding token limit while keeping pinned messages', () => {
    const history = new History();
    history.updateSystemPrompt('System prompt');
    history.addMessage({ role: 'user', content: 'First message (repo map)' });

    // Add intermediate turns
    for (let i = 0; i < 20; i++) {
      history.addMessage({
        role: 'assistant',
        content: `Intermediate assistant turn ${i}`,
        tool_calls: [{ id: `call_${i}`, name: 'read_file', arguments: { path: `file_${i}.ts` } }]
      });
      history.addMessage({
        role: 'tool',
        content: `Very long content from tool result ${i} `.repeat(50),
        tool_call_id: `call_${i}`,
        name: 'read_file'
      });
    }

    history.addMessage({ role: 'user', content: 'Latest query' });
    history.addMessage({ role: 'assistant', content: 'Latest response' });

    const totalBefore = history.getTotalTokens();
    expect(totalBefore).toBeGreaterThan(500);

    const wasPruned = history.pruneToLimit(300);
    expect(wasPruned).toBe(true);

    const messages = history.getMessages();
    // System prompt must be preserved
    expect(messages[0].role).toBe('system');
    // First user message must be preserved
    expect(messages[1].content).toBe('First message (repo map)');
    // Last message must be preserved
    expect(messages[messages.length - 1].content).toBe('Latest response');
  });
});

describe('compaction bookkeeping (for /usage)', () => {
  const big = (n: number) => 'x'.repeat(n);
  const build = () => {
    const h = new History();
    h.addMessage({ role: 'system', content: 'sys' });
    h.addMessage({ role: 'user', content: 'task' });
    for (let i = 0; i < 12; i++) {
      h.addMessage({ role: 'assistant', content: '', tool_calls: [{ id: `c${i}`, name: 'read_file', arguments: { path: `f${i}` } }] });
      h.addMessage({ role: 'tool', content: big(6000), tool_call_id: `c${i}`, name: 'read_file' });
    }
    return h;
  };

  it('counts how often it compacted and how many messages were dropped', () => {
    const h = build();
    expect(h.compactions).toBe(0);
    // (the latest two exchanges are protected, so the target must leave room for them)
    expect(h.pruneToLimit(3000)).toBe(true);
    expect(h.compactions).toBe(1);
    expect(h.droppedMessages).toBeGreaterThan(0);
    expect(h.getTotalTokens()).toBeLessThanOrEqual(3000);
  });

  it('keeps the system prompt, the first task and the latest exchange', () => {
    const h = build();
    h.pruneToLimit(3000);
    const msgs = h.getMessages();
    expect(msgs[0].role).toBe('system');
    expect(msgs[1].content).toBe('task');
    expect(msgs[msgs.length - 1].tool_call_id).toBe('c11');
    // no tool result is left without its call
    const calls = new Set(msgs.flatMap((m) => (m.tool_calls ?? []).map((c) => c.id)));
    for (const m of msgs) if (m.role === 'tool') expect(calls.has(m.tool_call_id!)).toBe(true);
  });

  it('does not count when nothing had to be done, and clear() resets the counters', () => {
    const h = build();
    expect(h.pruneToLimit(10_000_000)).toBe(false);
    expect(h.compactions).toBe(0);
    h.pruneToLimit(3000);
    h.clear();
    expect(h.compactions).toBe(0);
    expect(h.droppedMessages).toBe(0);
  });
});
