import { beforeEach, describe, expect, it } from 'vitest';
import { TranscriptItem, gapBetween, messagesToItems, renderTranscript, toolResultRows } from '../../src/cli/transcript.js';
import { setActiveTheme } from '../../src/cli/theme.js';
import { displayWidth, stripAnsi } from '../../src/cli/width.js';

beforeEach(() => setActiveTheme('cyan'));

const items: TranscriptItem[] = [
  { kind: 'user', text: 'check roblox' },
  { kind: 'thought', text: 'First I should look.\nThen run ps.', sec: '0.3', stats: ' · 112 tok' },
  { kind: 'tool', name: 'bash', desc: '→ Run: ps aux | grep roblox', result: 'a\nb\nc\n[Exit code: 0]' },
  { kind: 'tool', name: 'mcp__roblox__list', desc: '→ MCP: roblox · list', result: '{"studios":[]}' },
  { kind: 'thought', text: 'Studio is not visible.', sec: '0.6', stats: '' },
  { kind: 'assistant', text: 'The server is connected.' },
  { kind: 'raw', rows: ['  ✓ 5s · 2 tools'] },
];
const draw = (thinkingExpanded: boolean, outputExpanded: boolean, cols = 100) =>
  renderTranscript(items, { cols, thinkingExpanded, outputExpanded }).map(stripAnsi);

describe('the conversation drawn again', () => {
  it('folded: one line per thought, one row per output, with the hint to unfold', () => {
    const text = draw(false, false).join('\n');
    expect(text).toContain('∴ thought for 0.3s · 112 tok  (ctrl+p to expand)');
    expect(text).not.toContain('First I should look.');
    expect(text).toContain('╰ a');
    expect(text).toContain('… +2 lines (ctrl+o to expand)');
    expect(text).toContain('╰ {"studios":[]}');
  });

  it('unfolding thinking shows every thought of the chat, also the old ones', () => {
    const text = draw(true, false).join('\n');
    expect(text).toContain('First I should look.');
    expect(text).toContain('Then run ps.');
    expect(text).toContain('Studio is not visible.');
    expect(text).toContain('╰ thought for 0.3s · 112 tok');
    expect(text).not.toContain('(ctrl+p to expand)');
    expect(text).toContain('… +2 lines (ctrl+o to expand)');     // output still folded
  });

  it('unfolding output shows the whole command and all of the output of every tool', () => {
    const text = draw(false, true).join('\n');
    expect(text).toMatch(/      a\n      b\n      c/);
    expect(text).not.toContain('(ctrl+o to expand)');
    expect(text).toContain('(ctrl+p to expand)');                // thinking still folded
  });

  it('keeps a blank row between blocks, but not between two tool calls or after the echo of the user', () => {
    const rows = draw(false, false);
    const at = (needle: string) => rows.findIndex((r) => r.includes(needle));
    expect(rows[at('∴ thought for 0.3s') - 1]).toBe('');          // gap above the thought
    expect(rows[at('◆ Run') - 1]).toBe('');                       // thought -> tool: a gap
    expect(rows[at('◆ MCP') - 1]).not.toBe('');                   // tool -> tool: none (the rows of the first tool's output)
    expect(rows[at('∴ thought for 0.6s') - 1]).toBe('');
    expect(rows[at('● The server') - 1]).toBe('');
    expect(gapBetween({ kind: 'tool', name: 'a', desc: 'x' }, { kind: 'tool', name: 'b', desc: 'y' })).toBe(false);
    expect(gapBetween({ kind: 'user', text: 'x' }, { kind: 'thought', text: '', sec: '0', stats: '' })).toBe(false);
    expect(gapBetween(undefined, { kind: 'assistant', text: 'x' })).toBe(false);
  });

  it('every row fits the terminal', () => {
    for (const cols of [100, 60, 40]) for (const on of [false, true]) {
      for (const row of draw(on, on, cols)) expect(displayWidth(row), `${cols} ${on}: ${row}`).toBeLessThanOrEqual(cols);
    }
  });

  it('an unfinished tool call shows only its line', () => {
    const rows = renderTranscript([{ kind: 'tool', name: 'bash', desc: '→ Run: sleep 100' }], { cols: 80, thinkingExpanded: false, outputExpanded: false }).map(stripAnsi);
    expect(rows).toEqual(['  ◆ Run  sleep 100']);
  });
});

describe('toolResultRows', () => {
  it('shows a plan as a card and an edit as a summary, whatever the switch says', () => {
    const plan = toolResultRows('todo_list', JSON.stringify({ type: 'todo_list', tasks: [{ text: 'one', status: 'completed' }] }), { cols: 80, expanded: false }).map(stripAnsi);
    expect(plan[0]).toContain('Plan & Tasks');
    expect(toolResultRows('write_file', JSON.stringify({ summary: 'Wrote 3 lines' }), { cols: 80, expanded: true }).map(stripAnsi)).toEqual(['    ╰ Wrote 3 lines']);
  });
});

describe('a resumed session as items', () => {
  it('turns messages into the same items as a live chat', () => {
    const got = messagesToItems([
      { role: 'user', content: 'hi\n\n--- Attached Files ---\nx' },
      { role: 'assistant', content: 'Working.', tool_calls: [{ id: 'c1', name: 'bash', arguments: { command: 'ls' } }] as any },
      { role: 'tool', tool_call_id: 'c1', name: 'bash', content: 'a\n[Exit code: 0]' },
    ]);
    expect(got).toEqual([
      { kind: 'user', text: 'hi' },
      { kind: 'assistant', text: 'Working.' },
      { kind: 'tool', name: 'bash', desc: '→ Run: ls', result: 'a\n[Exit code: 0]' },
    ]);
  });
});

describe('reasoning that is saved with the session', () => {
  const messages: any[] = [
    { role: 'user', content: 'continue' },
    { role: 'assistant', content: 'Writing chapters.', reasoning: { text: 'Plan: chapters 9 to 13.', sec: '70.4', stats: ' · 16.6k tok · 221 tok/s' } },
    { role: 'assistant', content: 'Done.' },
  ];

  it('a resumed chat gets its thoughts back, before the reply they belong to', () => {
    const got = messagesToItems(messages);
    expect(got.map((i) => i.kind)).toEqual(['user', 'thought', 'assistant', 'assistant']);
    expect(got[1]).toEqual({ kind: 'thought', text: 'Plan: chapters 9 to 13.', sec: '70.4', stats: ' · 16.6k tok · 221 tok/s' });
  });

  it('they are folded and unfold with ctrl+p, as in a live chat', async () => {
    const { renderResumedMessages } = await import('../../src/cli/resume.js');
    const folded = renderResumedMessages(messages, { title: 't', shown: 3, total: 3, cols: 100 }).map(stripAnsi).join('\n');
    expect(folded).toContain('∴ thought for 70.4s · 16.6k tok · 221 tok/s  (ctrl+p to expand)');
    expect(folded).not.toContain('Plan: chapters 9 to 13.');
    const open = renderResumedMessages(messages, { title: 't', shown: 3, total: 3, cols: 100, thinkingExpanded: true }).map(stripAnsi).join('\n');
    expect(open).toContain('Plan: chapters 9 to 13.');
  });

  it('survive the session file, and never reach the model or the context count', async () => {
    const saved = JSON.parse(JSON.stringify(messages));
    expect(saved[1].reasoning.text).toBe('Plan: chapters 9 to 13.');

    const { History } = await import('../../src/core/history.js');
    const h = new History();
    const plain = new History();
    for (const m of messages) h.addMessage(m);
    for (const m of messages) plain.addMessage({ ...m, reasoning: undefined });
    expect(h.getTotalTokens()).toBe(plain.getTotalTokens());

    const { toAnthropicMessages } = await import('../../src/providers/anthropic.js');
    expect(JSON.stringify(toAnthropicMessages(messages as any))).not.toContain('Plan: chapters 9 to 13.');
  });
});
