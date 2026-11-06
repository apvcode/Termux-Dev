import { EventEmitter } from 'events';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { askConfirm } from '../../src/cli/confirm.js';
import { setActiveTheme } from '../../src/cli/theme.js';
import { setLanguage } from '../../src/core/i18n.js';
import { stripAnsi } from '../../src/cli/width.js';

class FakeStdin extends EventEmitter {
  isTTY = true; isRaw = false;
  setRawMode(m: boolean) { this.isRaw = m; return this; }
  resume() { return this; }
}
let out: string[];
const press = (k: string) => process.stdin.emit('data', Buffer.from(k));
const realStdin = process.stdin;

beforeEach(() => {
  setActiveTheme('cyan'); setLanguage('en');
  out = [];
  Object.defineProperty(process, 'stdin', { value: new FakeStdin(), configurable: true });
  vi.spyOn(process.stdout, 'write').mockImplementation(((c: any) => { out.push(String(c)); return true; }) as any);
  vi.spyOn(console, 'log').mockImplementation(((...a: any[]) => { out.push(a.join(' ') + '\n'); }) as any);
});
afterEach(() => {
  vi.restoreAllMocks();
  Object.defineProperty(process, 'stdin', { value: realStdin, configurable: true });
  setLanguage('en');
});
const text = () => stripAnsi(out.join(''));

describe('askConfirm', () => {
  it('shows the question, the details and a card with two choices, No first by default', async () => {
    const done = askConfirm({ title: 'exit', question: 'Exit devx?', details: ['/home/me/project'] });
    expect(text()).toContain('Exit devx?');
    expect(text()).toContain('/home/me/project');
    expect(text()).toMatch(/╭─ exit/);
    expect(text()).toContain('Yes');
    press('\r');                                 // Enter on the default: No
    expect(await done).toBe(false);
  });

  it('Enter on the default Yes, arrows to switch, Esc means no', async () => {
    let done = askConfirm({ title: 'update', question: 'Update?', initial: true });
    press('\r');
    expect(await done).toBe(true);

    done = askConfirm({ title: 'x', question: 'Q?' });
    press('\x1b[A');                             // from No up to Yes
    press('\r');
    expect(await done).toBe(true);

    done = askConfirm({ title: 'x', question: 'Q?', initial: true });
    press('\x1b');
    expect(await done).toBe(false);
  });

  it('records the answer under the question, unless asked not to', async () => {
    let done = askConfirm({ title: 'x', question: 'Delete all?' });
    press('\r');
    await done;
    expect(text()).toContain('✓ Delete all?');
    expect(text()).toContain('› No');

    out.length = 0;
    done = askConfirm({ title: 'x', question: 'Exit?', record: false });
    press('\r');
    await done;
    expect(text()).not.toContain('✓ Exit?');
  });

  it('speaks the chosen language', async () => {
    setLanguage('ru');
    const done = askConfirm({ title: 'выход', question: 'Выйти из devx?' });
    expect(text()).toContain('Да');
    expect(text()).toContain('Нет');
    press('\r');
    await done;
  });
});
