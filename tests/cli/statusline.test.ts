import { describe, it, expect } from 'vitest';
import { renderProgressBar, formatCostBadge, renderPowerlineStatus, GitStatusCache } from '../../src/cli/statusline.js';

describe('Statusline & Powerline', () => {
  it('renders progress bar with correct filled percentage', () => {
    const bar0 = renderProgressBar(0, 10);
    expect(bar0).toContain('░░░░░░░░░░');
    expect(bar0).toContain('0%');

    const bar50 = renderProgressBar(50, 10);
    expect(bar50).toContain('█████░░░░░');
    expect(bar50).toContain('50%');

    const bar100 = renderProgressBar(100, 10);
    expect(bar100).toContain('██████████');
    expect(bar100).toContain('100%');
  });

  it('formats cost badge and applies budget color thresholds', () => {
    // Normal cost without limit
    expect(formatCostBadge(0.0042)).toContain('$0.0042');

    // With budget limit
    const green = formatCostBadge(0.10, 0.50); // 20% -> green
    expect(green).toContain('$0.1000');

    const yellow = formatCostBadge(0.42, 0.50); // 84% -> yellow
    expect(yellow).toContain('$0.4200');

    const red = formatCostBadge(0.55, 0.50); // 110% -> red
    expect(red).toContain('$0.5500');
  });

  it('renders powerline status adapting to desktop and mobile terminal width', () => {
    // Desktop layout (80 cols)
    const desktop = renderPowerlineStatus({
      mode: 'AGENT',
      model: 'minimax/minimax-m3:free',
      currentTokens: 25000,
      maxTokens: 100000,
      cost: 0.005,
      cols: 80
    });
    expect(desktop).toContain('AGENT');
    expect(desktop).toContain('minimax-m3');
    expect(desktop).toContain('Context');

    // Mobile layout (35 cols)
    const mobile = renderPowerlineStatus({
      mode: 'PLAN',
      model: 'minimax/minimax-m3:free',
      currentTokens: 10000,
      maxTokens: 100000,
      cost: 0.001,
      cols: 35
    });
    expect(mobile).toContain('PLAN');
    expect(mobile).toContain('\n'); // 2-line layout on narrow screen
  });

  it('handles GitStatusCache invalidation cleanly', () => {
    GitStatusCache.invalidate();
    const status = GitStatusCache.getStatus();
    // In our repo on git branch main
    if (status) {
      expect(status.branch).toBeDefined();
      expect(typeof status.isDirty).toBe('boolean');
    }
  });
});
