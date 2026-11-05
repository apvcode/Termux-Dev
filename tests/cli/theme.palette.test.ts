import { beforeEach, describe, expect, it, vi } from 'vitest';

// Colours must be switched on before picocolors is loaded.
vi.hoisted(() => {
  process.env.FORCE_COLOR = '1';
  process.env.DEVX_COLOR = 'truecolor';
});

import { setActiveTheme } from '../../src/cli/theme.js';
import { buildPromptFrame, modeStyle, palette, renderUserEcho } from '../../src/cli/ui.js';

describe('the interface follows the active theme', () => {
  beforeEach(() => {
    process.env.DEVX_COLOR = 'truecolor';
  });

  it('repaints every palette colour when the theme changes', () => {
    setActiveTheme('nord');
    expect(palette.accent('x')).toBe('\x1b[38;2;136;192;208mx\x1b[39m');
    expect(palette.text('x')).toContain('38;2;236;239;244');
    expect(palette.shade('x')).toContain('48;2;46;52;64');

    setActiveTheme('dracula');
    expect(palette.accent('x')).toBe('\x1b[38;2;189;147;249mx\x1b[39m');
    expect(palette.err('x')).toContain('38;2;255;110;110');
    expect(palette.shade('x')).toContain('48;2;40;42;54');
  });

  it('colours the modes from the theme', () => {
    setActiveTheme('tokyonight');
    expect(modeStyle('agent').color('x')).toContain('38;2;122;162;247');
    expect(modeStyle('plan').color('x')).toContain('38;2;187;154;247');
    expect(modeStyle('yolo').color('x')).toContain('38;2;255;158;100');
  });

  it('uses the theme in the prompt frame and in the echoed message', () => {
    setActiveTheme('gruvbox');
    const frame = buildPromptFrame({
      cols: 80, mode: 'agent', contextPercent: 10, cost: 0, input: '', cursorPos: 0, placeholder: 'Ask',
    });
    expect(frame.lines[0]).toContain('38;2;250;189;47'); // gruvbox yellow accent on the agent pill
    expect(renderUserEcho('hi', 80)[1]).toContain('48;2;50;48;47'); // gruvbox shade behind the message
  });

  it('falls back to the 256-colour palette when truecolor is not available', () => {
    process.env.DEVX_COLOR = '256';
    setActiveTheme('nord');
    expect(palette.accent('x')).toMatch(/^\x1b\[38;5;\d+mx\x1b\[39m$/);
  });
});
