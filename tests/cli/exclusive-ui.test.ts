import { describe, expect, it } from 'vitest';
import { exclusiveUi } from '../../src/cli/ui.js';

const later = (ms: number) => new Promise((r) => setTimeout(r, ms));

describe('exclusiveUi', () => {
  it('starts at once when nothing else is open', () => {
    let started = false;
    const done = exclusiveUi(async () => { started = true; });
    expect(started).toBe(true);
    return done;
  });

  it('one panel at a time, in the order they were asked for', async () => {
    const log: string[] = [];
    const a = exclusiveUi(async () => { log.push('a start'); await later(30); log.push('a end'); });
    const b = exclusiveUi(async () => { log.push('b start'); await later(5); log.push('b end'); });
    const c = exclusiveUi(async () => { log.push('c start'); });
    await Promise.all([a, b, c]);
    expect(log).toEqual(['a start', 'a end', 'b start', 'b end', 'c start']);
  });

  it('a panel that fails does not block the next one', async () => {
    const failed = exclusiveUi(async () => { throw new Error('boom'); });
    await expect(failed).rejects.toThrow('boom');
    let ran = false;
    await exclusiveUi(async () => { ran = true; });
    expect(ran).toBe(true);
  });
});
