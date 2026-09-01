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
