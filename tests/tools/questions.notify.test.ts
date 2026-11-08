import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../src/core/attention.js', () => ({
  notifyAttention: vi.fn(),
  questionNotice: (qs: Array<{ question: string }>) => ({ title: 'devx · question for you', body: qs[0].question }),
}));
vi.mock('../../src/cli/picker.js', () => ({ runPicker: vi.fn(async (o: any) => o.items[0].value) }));

import { notifyAttention } from '../../src/core/attention.js';
import { askQuestionsTool } from '../../src/tools/questions.js';

const ttyBefore = process.stdin.isTTY;
beforeEach(() => { (process.stdin as any).isTTY = true; vi.mocked(notifyAttention).mockClear(); });
afterEach(() => { (process.stdin as any).isTTY = ttyBefore; });

describe('ask_questions', () => {
  it('tells the person that devx is waiting, once, before the first question is shown', async () => {
    vi.spyOn(console, 'log').mockImplementation(() => {});
    const out = await askQuestionsTool.execute({
      questions: [
        { id: 'a', question: 'Which database?', options: ['postgres', 'sqlite'] },
        { id: 'b', question: 'Which port?', options: ['3000', '8080'] },
      ],
    });
    expect(notifyAttention).toHaveBeenCalledTimes(1);
    expect(notifyAttention).toHaveBeenCalledWith('devx · question for you', 'Which database?');
    expect(out).toContain('A: postgres');
  });

  it('does not notify when there is nothing to ask, or nobody to ask (no terminal)', async () => {
    expect(await askQuestionsTool.execute({ questions: [] })).toBe('No questions provided.');
    (process.stdin as any).isTTY = false;
    await askQuestionsTool.execute({ questions: [{ question: 'Q?', options: ['a'] }] });
    expect(notifyAttention).not.toHaveBeenCalled();
  });
});
