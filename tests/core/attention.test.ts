import { afterEach, describe, expect, it, vi } from 'vitest';
import { approvalNotice, notifyAttention, questionNotice, setFocusTracking, setTerminalFocused } from '../../src/core/attention.js';

afterEach(() => {
  setFocusTracking(false);
  setTerminalFocused(true);
});

const spies = () => ({ desktop: vi.fn(() => 'notify-send' as const), device: vi.fn() });

describe('notifyAttention', () => {
  it('says nothing while the terminal is in front', () => {
    const d = spies();
    setFocusTracking(true);
    expect(notifyAttention('t', 'b', { ...d, termux: false })).toBe(false);
    expect(d.desktop).not.toHaveBeenCalled();
  });

  it('sends a desktop notification once the terminal has lost the focus', () => {
    const d = spies();
    setFocusTracking(true);
    setTerminalFocused(false);
    expect(notifyAttention('devx · question for you', 'Which database?', { ...d, termux: false })).toBe(true);
    expect(d.desktop).toHaveBeenCalledWith('devx · question for you', 'Which database?');
    expect(d.device).not.toHaveBeenCalled();
    // and again after the focus comes back: quiet
    setTerminalFocused(true);
    expect(notifyAttention('t', 'b', { ...d, termux: false })).toBe(false);
  });

  it('stays quiet when devx cannot know where the person is (no focus reports)', () => {
    const d = spies();
    setTerminalFocused(false);   // a stale value must not matter without tracking
    expect(notifyAttention('t', 'b', { ...d, termux: false })).toBe(false);
    expect(d.desktop).not.toHaveBeenCalled();
  });

  it('rings the terminal bell when there is no notification service', () => {
    const d = { desktop: vi.fn(() => 'none' as const), device: vi.fn() };
    setFocusTracking(true);
    setTerminalFocused(false);
    expect(notifyAttention('t', 'b', { ...d, termux: false })).toBe(true);
    expect(d.device).toHaveBeenCalledWith('t', 'b');
  });

  it('on Termux always notifies, with no focus report needed', () => {
    const d = spies();
    expect(notifyAttention('devx · approval needed', 'bash: npm test', { ...d, termux: true })).toBe(true);
    expect(d.device).toHaveBeenCalledWith('devx · approval needed', 'bash: npm test');
    expect(d.desktop).not.toHaveBeenCalled();
  });

  it('a new task starts in front: the focus is reset when tracking is switched on', () => {
    const d = spies();
    setFocusTracking(true);
    setTerminalFocused(false);
    setFocusTracking(false);
    setFocusTracking(true);
    expect(notifyAttention('t', 'b', { ...d, termux: false })).toBe(false);
  });

  it('keeps the text to one short plain line and never throws', () => {
    const d = spies();
    setFocusTracking(true);
    setTerminalFocused(false);
    notifyAttention('t', '**Bold** `code`\nline two ' + 'word '.repeat(80), { ...d, termux: false });
    const body = (d.desktop.mock.calls[0] as unknown as string[])[1];
    expect(body.length).toBeLessThanOrEqual(141);
    expect(body).not.toMatch(/[*`\n]/);
    expect(notifyAttention('t', 'b', { termux: false, desktop: () => { throw new Error('boom'); } })).toBe(false);
  });
});

describe('what the notice says', () => {
  it('for an approval: what is asked, and that it is dangerous when it is', () => {
    expect(approvalNotice('bash', ['npm test', 'more'])).toEqual({ title: 'devx · approval needed', body: 'bash: npm test' });
    expect(approvalNotice('bash', ['', '  rm -rf build'], true)).toEqual({ title: 'devx · dangerous action, approval needed', body: 'bash: rm -rf build' });
    expect(approvalNotice('write_file', [])).toEqual({ title: 'devx · approval needed', body: 'write_file' });
  });

  it('for questions: the first one, and how many more come', () => {
    expect(questionNotice([{ question: 'Which database?' }])).toEqual({ title: 'devx · question for you', body: 'Which database?' });
    expect(questionNotice([{ question: 'A?' }, { question: 'B?' }, { question: 'C?' }]).body).toBe('A? (+2 more)');
    expect(questionNotice([]).body).toBe('The agent has a question');
  });
});
