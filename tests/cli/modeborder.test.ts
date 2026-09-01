import { describe, it, expect } from 'vitest';
import { getModeIndicator, formatPromptHeader } from '../../src/cli/modeborder.js';

describe('Mode Indicator & Border Accents', () => {
  it('returns green indicator for AGENT mode', () => {
    const ind = getModeIndicator(false, false);
    expect(ind.mode).toBe('AGENT');
    expect(ind.badge).toContain('AGENT');
  });

  it('returns cyan indicator for PLAN mode', () => {
    const ind = getModeIndicator(true, false);
    expect(ind.mode).toBe('PLAN');
    expect(ind.badge).toContain('PLAN');
  });

  it('returns strictly RED indicator for YOLO mode', () => {
    const ind = getModeIndicator(false, true);
    expect(ind.mode).toBe('YOLO');
    expect(ind.badge).toContain('YOLO');
  });

  it('formats prompt header with icon and target toggle hint', () => {
    const planHeader = formatPromptHeader(true, false);
    expect(planHeader).toContain('Tab = AGENT');

    const agentHeader = formatPromptHeader(false, false);
    expect(agentHeader).toContain('Tab = PLAN');
  });
});
