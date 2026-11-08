import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../src/core/attention.js', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../src/core/attention.js')>()),
  notifyAttention: vi.fn(),
}));

import { notifyAttention } from '../../src/core/attention.js';
import { CLIConsoleGuard } from '../../src/permissions/guard.js';

const ttyBefore = process.stdin.isTTY;
beforeEach(() => { (process.stdin as any).isTTY = true; vi.mocked(notifyAttention).mockClear(); });
afterEach(() => { (process.stdin as any).isTTY = ttyBefore; });

describe('the approval card tells the person who is not looking', () => {
  it('sends what is asked, before the card waits for an answer', async () => {
    const order: string[] = [];
    vi.mocked(notifyAttention).mockImplementation(() => { order.push('notified'); return true; });
    const guard = new CLIConsoleGuard(false, [], { decide: async () => { order.push('card'); return 'allow'; } });
    expect(await guard.askUser('bash', { command: 'npm run deploy' })).toBe(true);
    expect(order).toEqual(['notified', 'card']);
    expect(notifyAttention).toHaveBeenCalledWith('devx · approval needed', expect.stringContaining('npm run deploy'));
  });

  it('says so when the request is dangerous', async () => {
    const guard = new CLIConsoleGuard(false, [], { decide: async () => 'deny' });
    expect(await guard.askUser('bash', { command: 'rm -rf /' })).toBe(false);
    expect(notifyAttention).toHaveBeenCalledWith('devx · dangerous action, approval needed', expect.any(String));
  });

  it('does not notify in a pipe, where nobody can answer anyway', async () => {
    (process.stdin as any).isTTY = false;
    const err = vi.spyOn(console, 'error').mockImplementation(() => {});
    expect(await new CLIConsoleGuard(false).askUser('bash', { command: 'ls' })).toBe(false);
    expect(notifyAttention).not.toHaveBeenCalled();
    err.mockRestore();
  });
});
