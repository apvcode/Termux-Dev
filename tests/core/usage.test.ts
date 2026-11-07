import { describe, it, expect, beforeEach } from 'vitest';
import { UsageTracker } from '../../src/core/usage.js';

describe('UsageTracker', () => {
  let tracker: UsageTracker;

  beforeEach(() => {
    tracker = UsageTracker.getInstance();
  });

  it('records requests, responses and tokens', () => {
    tracker.recordRequest(500);
    tracker.recordResponseChunk(1500);
    tracker.recordTokens(100, 50, 0.005);

    const summary = tracker.getSummary();
    expect(summary.bytesSent).toBeGreaterThanOrEqual(500);
    expect(summary.bytesReceived).toBeGreaterThanOrEqual(1500);
    expect(summary.totalTokens).toBeGreaterThanOrEqual(150);
    expect(summary.totalCost).toBeGreaterThanOrEqual(0.005);
  });

  it('formats bytes and tokens cleanly', () => {
    expect(UsageTracker.formatBytes(500)).toBe('500 B');
    expect(UsageTracker.formatBytes(1536)).toBe('1.5 KB');
    expect(UsageTracker.formatBytes(2097152)).toBe('2.00 MB');

    expect(UsageTracker.formatTokens(500)).toBe('500');
    expect(UsageTracker.formatTokens(1500)).toBe('1.5k');
    expect(UsageTracker.formatTokens(2500000)).toBe('2.50M');
  });
});

describe('UsageTracker cost honesty', () => {
  const plain = (s: string) => s.replace(/\x1b\[[0-9;]*m/g, '');

  it('shows an unpriced session as n/a, an estimated one with ~, a partial one with +', () => {
    const tracker = UsageTracker.getInstance();
    tracker.reset();
    tracker.recordUsage({ promptTokens: 10, completionTokens: 5, totalTokens: 15 });
    expect(plain(tracker.renderUsageCard())).toContain('n/a');
    expect(plain(tracker.renderUsageCard())).toContain('1 request');

    tracker.reset();
    tracker.recordUsage({ promptTokens: 10, completionTokens: 5, totalTokens: 15, cost: 0.01, costSource: 'estimated', cacheReadTokens: 4 });
    const card = plain(tracker.renderUsageCard());
    expect(card).toContain('~$0.0100');
    expect(card).toContain('catalogue (estimate)');
    expect(card).toContain('Cached input');

    tracker.recordUsage({ promptTokens: 1, completionTokens: 1, totalTokens: 2 });
    expect(plain(tracker.renderUsageCard())).toContain('~$0.0100+');
    tracker.reset();
  });

  it('keeps the plain numeric API working', () => {
    const tracker = UsageTracker.getInstance();
    tracker.reset();
    tracker.recordTokens(10, 10, 0.5);
    expect(tracker.getSummary().totalCost).toBeCloseTo(0.5);
    expect(plain(tracker.renderUsageCard())).toContain('$0.5000');
    tracker.reset();
  });
});

describe('UsageTracker breakdown', () => {
  const parts = (input: number, cacheRead: number, cacheWrite: number, output: number) => ({ input, cacheRead, cacheWrite, output });

  it('splits input into ordinary, cached and written, and the money by the same lines', () => {
    const tracker = UsageTracker.getInstance();
    tracker.reset();
    tracker.recordUsage({ promptTokens: 1000, completionTokens: 100, totalTokens: 1100, cacheReadTokens: 600, cacheWriteTokens: 100, cost: 0.01, costSource: 'estimated', costParts: parts(0.004, 0.001, 0.002, 0.003) });
    tracker.recordUsage({ promptTokens: 2000, completionTokens: 50, totalTokens: 2050, cacheReadTokens: 1800, cost: 0.02, costSource: 'estimated', costParts: parts(0.001, 0.005, 0, 0.014) });
    const s = tracker.getSummary();
    expect(s.uncachedTokens).toBe(300 + 200);
    expect(s.cacheReadTokens).toBe(2400);
    expect(s.cacheWriteTokens).toBe(100);
    expect(s.promptTokens).toBe(3000);
    expect(s.costParts!.input).toBeCloseTo(0.005, 9);
    expect(s.costParts!.cacheRead).toBeCloseTo(0.006, 9);
    expect(s.costParts!.output).toBeCloseTo(0.017, 9);
    const sum = s.costParts!.input + s.costParts!.cacheRead + s.costParts!.cacheWrite + s.costParts!.output;
    expect(sum).toBeCloseTo(s.totalCost, 9);
    tracker.reset();
  });

  it('shows no money split when a priced request could not be split (but still counts tokens)', () => {
    const tracker = UsageTracker.getInstance();
    tracker.reset();
    tracker.recordUsage({ promptTokens: 100, completionTokens: 10, totalTokens: 110, cost: 0.5, costSource: 'reported' });
    const s = tracker.getSummary();
    expect(s.costParts).toBeUndefined();
    expect(s.uncachedTokens).toBe(100);
    tracker.reset();
  });

  it('starts from zero after reset', () => {
    const tracker = UsageTracker.getInstance();
    tracker.recordUsage({ promptTokens: 100, completionTokens: 10, totalTokens: 110, cacheReadTokens: 50, cost: 0.1, costSource: 'reported', costParts: parts(0.05, 0.02, 0, 0.03) });
    tracker.reset();
    const s = tracker.getSummary();
    expect([s.uncachedTokens, s.cacheReadTokens, s.cacheWriteTokens]).toEqual([0, 0, 0]);
    expect(s.costParts).toBeUndefined();
  });
});

describe('UsageTracker after a chat was resumed', () => {
  it('starts from what the chat cost before, so /usage agrees with the frame', () => {
    const tracker = UsageTracker.getInstance();
    tracker.recordUsage({ promptTokens: 5000, completionTokens: 100, totalTokens: 5100, cost: 9, costSource: 'reported', costParts: { input: 8, cacheRead: 0, cacheWrite: 0, output: 1 } });
    // another chat was open: its numbers must not leak into the resumed one
    tracker.restoreCost(0.5, true);
    let s = tracker.getSummary();
    expect(s.totalCost).toBeCloseTo(0.5, 9);
    expect(s.costState.estimated).toBe(true);
    expect(s.restoredCost).toBeCloseTo(0.5, 9);
    expect(s.promptTokens).toBe(0);
    expect(s.costParts).toBeUndefined(); // a split that does not add up to the total is not shown

    tracker.recordUsage({ promptTokens: 1000, completionTokens: 10, totalTokens: 1010, cost: 0.1, costSource: 'estimated', costParts: { input: 0.09, cacheRead: 0, cacheWrite: 0, output: 0.01 } });
    s = tracker.getSummary();
    expect(s.totalCost).toBeCloseTo(0.6, 9);
    expect(s.promptTokens).toBe(1000);

    tracker.reset();
    expect(tracker.getSummary().restoredCost).toBeUndefined();
    expect(tracker.getSummary().totalCost).toBe(0);
  });

  it('a chat that cost nothing has nothing to restore', () => {
    const tracker = UsageTracker.getInstance();
    tracker.restoreCost(0);
    expect(tracker.getSummary().restoredCost).toBeUndefined();
    tracker.reset();
  });
});
