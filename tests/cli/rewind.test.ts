import { beforeEach, describe, expect, it } from 'vitest';
import { REWIND_CURRENT, messageDraft, messageLabel, rewindItems, timeAgo, userEntries } from '../../src/cli/rewind.js';
import { renderPicker } from '../../src/cli/picker.js';
import { History } from '../../src/core/history.js';
import { setLanguage } from '../../src/core/i18n.js';
import { setActiveTheme } from '../../src/cli/theme.js';
import { displayWidth, stripAnsi } from '../../src/cli/width.js';
import { Message } from '../../src/core/types.js';

beforeEach(() => { setActiveTheme('cyan'); setLanguage('en'); });

const NOW = 1_700_000_000_000;
const chat: Message[] = [
  { role: 'system', content: 'system prompt' },
  { role: 'user', content: 'first question\n\n--- Attached Files ---\n[Attached file: a.ts]\n```\nx\n```', at: NOW - 2 * 86400_000 },
  { role: 'assistant', content: 'answer one' },
  { role: 'user', content: '   \n  second one with a very long first line that goes on and on and on and on and never stops', at: NOW - 3 * 3600_000 },
  { role: 'assistant', content: 'answer two', tool_calls: [{ id: 't', name: 'bash', arguments: {} }] as any },
  { role: 'tool', content: 'out', tool_call_id: 't', name: 'bash' },
  { role: 'user', content: 'third', at: NOW - 90_000 },
  { role: 'assistant', content: 'answer three' },
];

describe('time ago', () => {
  it('says it in the chosen language, and nothing for a message without a time', () => {
    expect(timeAgo(NOW - 5000, NOW)).toBe('just now');
    expect(timeAgo(NOW - 5 * 60_000, NOW)).toBe('5m ago');
    expect(timeAgo(NOW - 3 * 3600_000, NOW)).toBe('3h ago');
    expect(timeAgo(NOW - 2 * 86400_000, NOW)).toBe('2d ago');
    expect(timeAgo(undefined, NOW)).toBe('');
    setLanguage('ru');
    expect(timeAgo(NOW - 3 * 3600_000, NOW)).toBe('3 ч назад');
  });
});

describe('the messages of the menu', () => {
  it('shows the first line of what was typed, never the attached files', () => {
    expect(messageLabel(chat[1].content)).toBe('first question');
    expect(messageLabel(chat[3].content, 30).length).toBeLessThanOrEqual(30);
    expect(messageLabel(chat[3].content)).toContain('second one');
  });

  it('the draft that returns to the input line is the typed text, without the attached files', () => {
    expect(messageDraft(chat[1].content)).toBe('first question');
    expect(messageDraft('  plain text  ')).toBe('plain text');
  });

  it('lists your messages oldest first, with the time and how many came after, and "(current)" last', () => {
    expect(userEntries(chat).map((e) => e.index)).toEqual([1, 3, 6]);
    const items = rewindItems(chat, NOW);
    expect(items.map((i) => i.value)).toEqual([1, 3, 6, REWIND_CURRENT]);
    expect(items[0].detail).toBe('2d ago · 2 after it');
    expect(items[1].detail).toBe('3h ago · 1 after it');
    expect(items[2].detail).toBe('2m ago');
    expect(items[3].label).toBe('(current: stay here)');
  });
});

describe('forgetting the conversation after a message', () => {
  it('keeps the system prompt and everything before the message, drops the rest', () => {
    const h = new History();
    for (const m of chat) h.addMessage({ ...m });
    h.rewindBefore(3);
    expect(h.getMessages().map((m) => m.role)).toEqual(['system', 'user', 'assistant']);
    h.rewindBefore(0);                                   // never the system prompt
    expect(h.getMessages().map((m) => m.role)).toEqual(['system']);
  });
});

describe('the picker with a long list', () => {
  const items = Array.from({ length: 60 }, (_, i) => ({ value: i, label: `message ${i}`, detail: 'x' }));
  const draw = (focus: number, rows: number) => renderPicker({ title: 'rewind', items, labelWidth: 20 }, focus, 100, rows).map(stripAnsi);

  it('shows the rows around the focus with how many are hidden, and fits the screen', () => {
    for (const focus of [0, 25, 59]) {
      const lines = draw(focus, 20);
      expect(lines.length).toBeLessThanOrEqual(19);
      expect(lines.join('\n')).toContain(`message ${focus}`);
    }
    expect(draw(0, 20).join('\n')).toMatch(/↓ \d+ more/);
    expect(draw(0, 20).join('\n')).not.toMatch(/↑ \d+ more/);
    expect(draw(30, 20).join('\n')).toMatch(/↑ \d+ more/);
    expect(draw(59, 20).join('\n')).not.toMatch(/↓ \d+ more/);
  });

  it('keeps the box straight', () => {
    const lines = draw(30, 24).filter((l) => /^[╭│╰]/.test(l));
    expect(new Set(lines.map((l) => displayWidth(l))).size).toBe(1);
  });

  it('a short list is drawn whole, as before', () => {
    const lines = renderPicker({ title: 't', items: items.slice(0, 4) }, 0, 100, 30).map(stripAnsi).join('\n');
    expect(lines).toContain('message 3');
    expect(lines).not.toMatch(/more/);
  });
});
