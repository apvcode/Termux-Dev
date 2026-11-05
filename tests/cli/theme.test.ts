import { describe, expect, it } from 'vitest';
import { contrastRatio, relativeLuminance } from '../../src/cli/colors.js';
import {
  THEMES,
  THEME_IDS,
  buildThemeFor,
  findTheme,
  getCurrentTheme,
  getTheme,
  listThemes,
  renderSwatch,
  renderThemePreview,
  setActiveTheme,
  ThemeColors,
} from '../../src/cli/theme.js';

const BACKGROUND = '#0a0a0c'; // the terminal background devx sets
const HEX = /^#[0-9a-f]{6}$/i;
const KEYS: Array<keyof ThemeColors> = ['accent', 'plan', 'ok', 'warn', 'err', 'yolo', 'text', 'muted', 'faint', 'rule', 'shade'];

describe('theme catalogue', () => {
  it('offers a real choice of themes and keeps the original six', () => {
    expect(listThemes().length).toBeGreaterThanOrEqual(18);
    for (const id of ['cyan', 'purple', 'matrix', 'amber', 'crimson', 'monochrome']) expect(THEMES[id]).toBeDefined();
    for (const id of ['nord', 'dracula', 'tokyonight', 'gruvbox', 'catppuccin', 'solarized', 'rosepine', 'monokai', 'github', 'ocean', 'sunset', 'forest']) {
      expect(THEMES[id], id).toBeDefined();
    }
    expect(THEME_IDS).toEqual(listThemes().map((t) => t.id));
  });

  it('gives every theme a unique id, name, accent colour and description', () => {
    const themes = listThemes();
    expect(new Set(themes.map((t) => t.id)).size).toBe(themes.length);
    expect(new Set(themes.map((t) => t.name)).size).toBe(themes.length);
    expect(new Set(themes.map((t) => t.colors.accent.toLowerCase())).size).toBe(themes.length);
    for (const t of themes) {
      expect(t.desc.length, t.id).toBeGreaterThan(10);
      expect(t.symbol).toBe('●');
      expect(t.hex).toBe(t.colors.accent);
    }
  });

  it('defines the complete palette for each theme with a paint function per colour', () => {
    for (const t of listThemes()) {
      for (const key of KEYS) {
        expect(t.colors[key], `${t.id}.${key}`).toMatch(HEX);
        expect(typeof t.tokens[key], `${t.id}.${key}`).toBe('function');
        expect(t.tokens[key]('x')).toContain('x');
      }
    }
  });

  it('keeps every theme readable on the dark terminal background', () => {
    const minimum: Record<string, number> = {
      text: 9, muted: 6.5, faint: 4.5, rule: 2.6, accent: 5, plan: 4.5, ok: 4.5, warn: 4.5, err: 4.5, yolo: 4.5,
    };
    for (const t of listThemes()) {
      for (const [key, min] of Object.entries(minimum)) {
        const ratio = contrastRatio(t.colors[key as keyof ThemeColors], BACKGROUND);
        expect(ratio, `${t.id}.${key} ${t.colors[key as keyof ThemeColors]} contrast ${ratio.toFixed(2)}`).toBeGreaterThanOrEqual(min);
      }
      // the shaded block behind the echoed message: still dark, text on it still readable
      expect(relativeLuminance(t.colors.shade), `${t.id}.shade`).toBeLessThan(0.05);
      expect(contrastRatio(t.colors.text, t.colors.shade), `${t.id} text on shade`).toBeGreaterThanOrEqual(7);
    }
  });

  it('keeps the text hierarchy ordered: text brighter than muted, muted brighter than faint', () => {
    for (const t of listThemes()) {
      expect(relativeLuminance(t.colors.text), t.id).toBeGreaterThan(relativeLuminance(t.colors.muted));
      expect(relativeLuminance(t.colors.muted), t.id).toBeGreaterThan(relativeLuminance(t.colors.faint));
      expect(relativeLuminance(t.colors.faint), t.id).toBeGreaterThan(relativeLuminance(t.colors.rule));
    }
  });

  it('separates the mode colours so plan, agent and yolo never look alike', () => {
    for (const t of listThemes()) {
      const modes = [t.colors.accent, t.colors.plan, t.colors.yolo].map((c) => c.toLowerCase());
      expect(new Set(modes).size, t.id).toBe(3);
    }
  });
});

describe('theme lookup', () => {
  it('finds themes by id, number, alias and name', () => {
    expect(getTheme('nord').id).toBe('nord');
    expect(getTheme('  DRACULA ').id).toBe('dracula');
    expect(getTheme('7').id).toBe('nord');
    expect(getTheme('18').id).toBe('forest');
    expect(findTheme('tokyo')?.id).toBe('tokyonight');
    expect(findTheme('mocha')?.id).toBe('catppuccin');
    expect(findTheme('gh')?.id).toBe('github');
    expect(findTheme('Rose Pine')?.id).toBe('rosepine');
    expect(findTheme('deep ocean')?.id).toBe('ocean');
    expect(findTheme('forest moss')?.id).toBe('forest');
  });

  it('keeps every alias that worked before', () => {
    const old: Record<string, string> = {
      '1': 'cyan', '2': 'purple', '3': 'matrix', '4': 'amber', '5': 'crimson', '6': 'monochrome',
      blue: 'cyan', neon: 'cyan', cyber: 'cyan', magenta: 'purple', violet: 'purple', pink: 'purple', synthwave: 'purple',
      green: 'matrix', hacker: 'matrix', terminal: 'matrix', yellow: 'amber', gold: 'amber', solar: 'amber', orange: 'amber',
      red: 'crimson', ruby: 'crimson', cyberpunk: 'crimson', white: 'monochrome', mono: 'monochrome', gray: 'monochrome', grey: 'monochrome',
    };
    for (const [alias, id] of Object.entries(old)) expect(getTheme(alias).id, alias).toBe(id);
  });

  it('never lets two themes claim the same alias', () => {
    const seen = new Map<string, string>();
    for (const t of listThemes()) {
      for (const alias of [t.id]) {
        expect(seen.has(alias), `${alias} used by ${seen.get(alias)} and ${t.id}`).toBe(false);
        seen.set(alias, t.id);
      }
    }
    // an alias must not shadow another theme's id
    for (const alias of ['blue', 'solar', 'mono', 'night', 'git', 'rose']) expect(THEMES[alias], alias).toBeUndefined();
  });

  it('falls back to the default theme and returns null for unknown queries', () => {
    expect(getTheme('does-not-exist').id).toBe('cyan');
    expect(findTheme('does-not-exist')).toBeNull();
    expect(findTheme('')).toBeNull();
  });

  it('remembers the active theme', () => {
    setActiveTheme('gruvbox');
    expect(getCurrentTheme().id).toBe('gruvbox');
    setActiveTheme('definitely-unknown');
    expect(getCurrentTheme().id).toBe('cyan');
  });
});

describe('theme painting', () => {
  it('paints with the exact colours of the palette on a truecolor terminal', () => {
    const nord = buildThemeFor('nord', { enabled: true, truecolor: true });
    expect(nord.tokens.accent('x')).toBe('\x1b[38;2;136;192;208mx\x1b[39m');
    expect(nord.colorFn('x')).toBe(nord.tokens.accent('x'));
    expect(nord.tokens.shade('x')).toBe('\x1b[48;2;46;52;64mx\x1b[49m');
    expect(nord.accentFn('x')).toBe(nord.tokens.plan('x'));
  });

  it('degrades to the 256-colour palette', () => {
    const dracula = buildThemeFor('dracula', { enabled: true, truecolor: false });
    expect(dracula.tokens.accent('x')).toMatch(/^\x1b\[38;5;\d+mx\x1b\[39m$/);
    expect(dracula.tokens.shade('x')).toMatch(/^\x1b\[48;5;\d+mx\x1b\[49m$/);
  });

  it('builds bold, badge and diff helpers from the palette', () => {
    const t = buildThemeFor('github', { enabled: true, truecolor: true });
    expect(t.badgeFn('go')).toContain('\x1b[48;2;88;166;255m'); // accent background
    expect(t.badgeFn('go')).toContain('\x1b[38;2;0;0;0m');      // black text on it
    expect(t.diffAddBg('+')).toContain('\x1b[48;2;63;185;80m'); // ok colour
    expect(t.diffRemoveBg('-')).toContain('\x1b[48;2;248;81;73m'); // err colour
  });

  it('is plain text when colours are off', () => {
    const t = buildThemeFor('forest', { enabled: false });
    for (const key of KEYS) expect(t.tokens[key]('plain')).toBe('plain');
    expect(t.badgeFn('plain')).toBe('plain');
    expect(() => buildThemeFor('nope', {})).toThrow(/Unknown theme/);
  });

  it('shows the palette as four dots', () => {
    const plain = renderSwatch(THEMES.nord).replace(/\x1b\[[0-9;]*m/g, '');
    expect(plain).toBe('● ● ● ●');
  });
});

describe('theme preview', () => {
  it('paints a sample screen with the theme colours', () => {
    const t = buildThemeFor('nord', { enabled: true, truecolor: true });
    const preview = renderThemePreview(t);
    const plain = preview.replace(/\x1b\[[0-9;]*m/g, '').split('\n');
    expect(plain).toHaveLength(4);
    expect(plain[0]).toBe('● agent   ◆ plan   ▲ yolo');
    expect(plain[1]).toBe('✓ done   ! careful   ✗ failed');
    expect(plain[3]).toContain('❯ explain this project');
    expect(preview).toContain('38;2;136;192;208'); // accent
    expect(preview).toContain('38;2;180;142;173'); // plan
    expect(preview).toContain('48;2;46;52;64');    // shade behind the message
  });

  it('exists for every theme and has no emoji', () => {
    for (const t of listThemes()) {
      expect(renderThemePreview(t).replace(/\x1b\[[0-9;]*m/g, '')).not.toMatch(/\p{Extended_Pictographic}(?<![✓✗✖❯●◆▲])/u);
    }
  });
});
