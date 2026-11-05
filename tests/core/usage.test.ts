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
