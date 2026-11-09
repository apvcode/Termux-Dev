import { describe, expect, it } from 'vitest';
import { EventEmitter } from 'node:events';
import { editText, filterItems, firstMatch, renderFinder, renderTextInput, runFinder, runTextInput } from '../../src/cli/finder.js';
import { renderWelcome } from '../../src/cli/welcome.js';
import { setActiveTheme } from '../../src/cli/theme.js';
import { displayWidth, stripAnsi } from '../../src/cli/width.js';
import { setLanguage } from '../../src/core/i18n.js';

setActiveTheme('cyan');
setLanguage('en');

const items = [
  { value: 'custom', label: 'Custom endpoint', pinned: true },
  { value: 'openai', label: 'OpenAI', keywords: 'openai api.openai.com' },
  { value: 'openrouter', label: 'OpenRouter', keywords: 'openrouter openrouter.ai' },
  { value: 'groq', label: 'Groq', keywords: 'groq api.groq.com' },
  { value: 'deepseek', label: 'DeepSeek', keywords: 'deepseek api.deepseek.com' },
];

describe('filterItems', () => {
  it('shows everything for an empty query, the pinned row first', () => {
    expect(filterItems(items, '').map((i) => i.value)).toEqual(['custom', 'openai', 'openrouter', 'groq', 'deepseek']);
  });
  it('needs every word, looks in keywords, and ranks the start of the label first', () => {
    expect(filterItems(items, 'open').map((i) => i.value)).toEqual(['custom', 'openai', 'openrouter']);
    expect(filterItems(items, 'api.groq').map((i) => i.value)).toEqual(['custom', 'groq']);
    expect(filterItems(items, 'seek').map((i) => i.value)).toEqual(['custom', 'deepseek']);
    expect(filterItems(items, 'open xyz').map((i) => i.value)).toEqual(['custom']);
  });
  it('focus starts on the first match, not on the pinned row', () => {
    expect(firstMatch(filterItems(items, ''))).toBe(1);
    expect(firstMatch(filterItems(items, 'zzz'))).toBe(0);
  });
});

describe('editText', () => {
  it('types, erases, erases a word and clears', () => {
    expect(editText('ab', 'c')).toBe('abc');
    expect(editText('abc', '\x7f')).toBe('ab');
    expect(editText('one two', '\x17')).toBe('one');
    expect(editText('abc', '\x15')).toBe('');
    expect(editText('abc', '\x1b[A')).toBeUndefined();
    expect(editText('abc', '\r')).toBeUndefined();
  });
});

describe('the cards', () => {
  it.each([80, 40, 28])('fit %i columns', (cols) => {
    const lines = [
      ...renderFinder({ title: 'provider', items }, 'op', filterItems(items, 'op'), 1, cols),
      ...renderFinder({ title: 'provider', items }, 'zzz', filterItems(items, 'zzz'), 0, cols),
      ...renderTextInput({ title: 'api key', label: 'Paste your API key for OpenAI', secret: true, notes: ['What you type is hidden.'] }, 'sk-secret', cols),
      ...renderWelcome('1.9.11', cols, 1),
    ];
    for (const line of lines) expect(displayWidth(stripAnsi(line))).toBeLessThanOrEqual(cols);
  });

  it('hide a secret and show the typed filter', () => {
    const key = stripAnsi(renderTextInput({ title: 'api key', label: 'x', secret: true }, 'sk-secret', 80).join('\n'));
    expect(key).not.toContain('sk-secret');
    expect(key).toContain('•••••••••');
    const found = stripAnsi(renderFinder({ title: 'provider', items }, 'groq', filterItems(items, 'groq'), 1, 80).join('\n'));
    expect(found).toContain('Groq');
    expect(found).not.toContain('DeepSeek');
    const plain = items.filter((i) => !i.pinned);
    expect(stripAnsi(renderFinder({ title: 'p', items: plain }, 'zzz', filterItems(plain, 'zzz'), 0, 80).join('\n'))).toContain('Nothing matches');
  });

  it('the welcome names the five steps and ticks the finished ones', () => {
    const text = stripAnsi(renderWelcome('1.9.11', 80, 2).join('\n'));
    for (const word of ['Language', 'Theme', 'Provider', 'API key', 'Model', 'v1.9.11']) expect(text).toContain(word);
    expect(text.match(/✓/g)).toHaveLength(2);
  });
});

function fakeIO() {
  const input: any = new EventEmitter();
  input.isRaw = false;
  input.setRawMode = () => input;
  input.resume = () => input;
  const out: string[] = [];
  const output: any = { columns: 80, rows: 30, write: (s: string) => { out.push(s); return true; } };
  return { input, output, out, key: (k: string) => input.emit('data', Buffer.from(k)) };
}

describe('running the prompts', () => {
  it('the finder narrows by typing and Enter picks the first match', async () => {
    const io = fakeIO();
    const result = runFinder({ title: 'provider', items }, io);
    for (const ch of 'gro') io.key(ch);
    io.key('\r');
    expect(await result).toBe('groq');
  });

  it('arrows move, and Esc cancels', async () => {
    const io = fakeIO();
    const picked = runFinder({ title: 'provider', items }, io);
    io.key('\x1b[B');
    io.key('\r');
    expect(await picked).toBe('openrouter');
    const io2 = fakeIO();
    const cancelled = runFinder({ title: 'provider', items }, io2);
    io2.key('\x1b');
    expect(await cancelled).toBeUndefined();
  });

  it('the input ignores an empty answer, trims, and Esc cancels', async () => {
    const io = fakeIO();
    const typed = runTextInput({ title: 'k', label: 'key', secret: true }, io);
    io.key('\r');
    for (const ch of ' sk-1 ') io.key(ch);
    io.key('\r');
    expect(await typed).toBe('sk-1');
    const io2 = fakeIO();
    const cancelled = runTextInput({ title: 'k', label: 'key' }, io2);
    io2.key('\x1b');
    expect(await cancelled).toBeUndefined();
  });
});
