import { describe, it, expect, beforeEach } from 'vitest';
import { LiveTelemetryTracker } from '../../src/cli/telemetry.js';

describe('LiveTelemetryTracker', () => {
  let tracker: LiveTelemetryTracker;

  beforeEach(() => {
    tracker = new LiveTelemetryTracker();
  });

  it('formats token count cleanly (k, M)', () => {
    expect(LiveTelemetryTracker.formatTokenCount(350)).toBe('350');
    expect(LiveTelemetryTracker.formatTokenCount(1420)).toBe('1.4k');
    expect(LiveTelemetryTracker.formatTokenCount(1200000)).toBe('1.2M');
  });

  it('records tokens and estimates tokens from text', () => {
    const tokens = tracker.estimateTokensFromText('Hello world from DevX');
    expect(tokens).toBeGreaterThan(0);

    tracker.start('generating');
    tracker.recordTokens(tokens);
    expect(tracker.getTotalTokens()).toBe(tokens);
  });

  it('formats label with phase, elapsed time and tok/s', () => {
    tracker.start('thinking');
    tracker.recordTokens(100);

    const label = tracker.formatLabel();
    expect(label).toContain('thinking…');
    expect(label).toContain('tok');
  });

  it('resets completely on reset() (used during tool execution, abort, and network reconnecting)', () => {
    tracker.start('generating');
    tracker.recordTokens(500);
    expect(tracker.getTotalTokens()).toBe(500);

    // Simulate network error / reconnecting event
    tracker.reset();
    expect(tracker.getTotalTokens()).toBe(0);
    expect(tracker.getElapsedSeconds()).toBe(0);
    expect(tracker.getTokensPerSecond()).toBe(0);
  });
});
