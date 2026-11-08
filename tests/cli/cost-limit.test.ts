import { describe, expect, it } from 'vitest';
import { COST_LIMIT_STEPS, ROWS, SettingsState, changeSetting, renderSettings, visibleRows } from '../../src/cli/settings.js';
import { setActiveTheme } from '../../src/cli/theme.js';
import { setLanguage, LANGUAGE_IDS } from '../../src/core/i18n.js';
import { displayWidth, stripAnsi } from '../../src/cli/width.js';
import { formatCost } from '../../src/cli/ui.js';

const base: SettingsState = { themeId: 'dracula', layout: 'clean', pureBlack: true, autoApprove: false, maxIterations: 30, memory: true, updates: false, costLimit: 0, language: 'en' };

describe('the cost limit in the settings', () => {
  it('is a row of the Agent section that goes through the steps and wraps around', () => {
    const row = ROWS.find((r) => r.key === 'costLimit')!;
    expect(row.sectionKey).toBe('agent');
    let state = base;
    const seen = [state.costLimit];
    for (let i = 0; i < COST_LIMIT_STEPS.length - 1; i++) {
      state = changeSetting(state, 'costLimit', 1);
      seen.push(state.costLimit);
    }
    expect(seen).toEqual(COST_LIMIT_STEPS);
    expect(changeSetting(state, 'costLimit', 1).costLimit).toBe(0);                 // after the last: no limit again
    expect(changeSetting(base, 'costLimit', -1).costLimit).toBe(50);
    expect(changeSetting({ ...base, costLimit: 4 }, 'costLimit', 1).costLimit).toBe(10);   // a hand-written value snaps to the nearest step first
  });

  it('shows "off" or the amount, and the hint tells what happens at 80% and at the limit', () => {
    const index = visibleRows(base).findIndex((r) => r.key === 'costLimit');
    const off = renderSettings(base, index, 100, 40, '1.9.5').map(stripAnsi).join('\n');
    expect(off).toMatch(/Cost limit\s+○ off/);
    const on = renderSettings({ ...base, costLimit: 5 }, index, 100, 40, '1.9.5').map(stripAnsi).join('\n');
    expect(on).toMatch(/Cost limit\s+\$5/);
    expect(on).toContain('At 80% devx warns you');
  });

  it('fits every screen in every language with the extra row', () => {
    setActiveTheme('cyan');
    for (const id of LANGUAGE_IDS) {
      setLanguage(id);
      for (const [cols, rows] of [[100, 40], [100, 26], [100, 23], [60, 22], [40, 30]]) {
        for (let focus = 0; focus < visibleRows({ ...base, autoApprove: true }).length; focus++) {
          const lines = renderSettings({ ...base, autoApprove: true, extra: true, costLimit: 2, language: id }, focus, cols, rows, '1.9.5').map(stripAnsi);
          expect(lines.length, `${id} ${cols}x${rows}`).toBeLessThanOrEqual(rows - 1);
          for (const l of lines) expect(displayWidth(l)).toBeLessThanOrEqual(cols - 1);
        }
      }
    }
    setLanguage('en');
  });

  it('amounts are shown as money', () => {
    expect(formatCost(5)).toContain('$5');
    expect(formatCost(0.0123)).toContain('0.0123');
  });
});
