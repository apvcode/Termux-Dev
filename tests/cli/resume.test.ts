import { beforeEach, describe, expect, it } from 'vitest';
import { renderResumedMessages } from '../../src/cli/resume.js';
import { renderAnswerRow } from '../../src/tools/questions.js';
import { setActiveTheme } from '../../src/cli/theme.js';
import { displayWidth, stripAnsi } from '../../src/cli/width.js';
import { Message } from '../../src/core/types.js';

beforeEach(() => setActiveTheme('cyan'));

const messages: Message[] = [
  { role: 'user', content: 'сделай модель бомжа' },
  { role: 'assistant', content: 'Пишу сцену.', tool_calls: [{ id: 'c1', name: 'bash', arguments: { command: 'ls -la' } }] as any },
  { role: 'tool', tool_call_id: 'c1', name: 'bash', content: 'total 4\nbum.html\n[Exit code: 0]' },
  { role: 'assistant', content: '', tool_calls: [{ id: 'c2', name: 'todo_list', arguments: {} }] as any },
  { role: 'tool', tool_call_id: 'c2', name: 'todo_list', content: JSON.stringify({ type: 'todo_list', tasks: [{ text: 'one', status: 'completed' }] }) },
  { role: 'assistant', content: 'Готово.' },
];

describe('a resumed session', () => {
  const rows = renderResumedMessages(messages, { title: 'сделай очень реалистичную модель', shown: 6, total: 58, cols: 100 }).map(stripAnsi);
  const text = rows.join('\n');

  it('looks like a live one: echo of the user, gutter for the assistant, tool lines with their results', () => {
    expect(text).toContain('❯ сделай модель бомжа');
    expect(text).toContain('● Пишу сцену.');
    expect(text).toMatch(/◆ Run\s+ls -la/);
    expect(text).toContain('╰ ');                  // the quiet result under the call
    expect(text).toContain('╰ total 4');
    expect(text).toContain('… +1 lines (ctrl+o to expand)');
    expect(text).toContain('╭─ Plan & Tasks');       // the plan is the card, not a bare name
    expect(text).toContain('● Готово.');
  });

  it('never prints a bare tool name, and says how much of the session is shown', () => {
    expect(rows.some((r) => r.trim() === 'bash')).toBe(false);
    expect(text).toContain('6 / 58');
    expect(text).toContain('resumed');
  });

  it('keeps every row inside the terminal', () => {
    for (const cols of [100, 60, 40]) {
      for (const r of renderResumedMessages(messages, { title: 'x'.repeat(200), shown: 6, total: 58, cols }).map(stripAnsi)) {
        expect(displayWidth(r)).toBeLessThanOrEqual(cols);
      }
    }
  });
});

describe('the answered questions', () => {
  it('shows the question and the answer under it, a skipped one muted', () => {
    const row = stripAnsi(renderAnswerRow('Убрать висящий процесс?', 'Не трогай', false, 80));
    expect(row).toBe('  ✓ Убрать висящий процесс?\n    › Не трогай');
    expect(stripAnsi(renderAnswerRow('Q?', '(skipped)', true, 80))).toBe('  - Q?\n    › (skipped)');
  });

  it('wraps long text instead of running off the screen', () => {
    for (const line of stripAnsi(renderAnswerRow('word '.repeat(40), 'answer '.repeat(30), false, 50)).split('\n')) {
      expect(displayWidth(line)).toBeLessThanOrEqual(50);
    }
  });
});
