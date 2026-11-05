import pc from 'picocolors';
import { ColorOptions, bgHex, fgHex } from './colors.js';

/**
 * A theme is a full palette, not just an accent colour. Every theme is designed for the
 * dark background devx sets (#0a0a0c) and is checked for readable contrast in the tests.
 */
export interface ThemeColors {
  /** Brand colour: agent mode, selection, links, the spinner. */
  accent: string;
  /** Second colour: plan mode and secondary highlights. */
  plan: string;
  ok: string;
  warn: string;
  err: string;
  /** YOLO mode / dangerous actions. */
  yolo: string;
  /** Body text, secondary text, hints, borders. */
  text: string;
  muted: string;
  faint: string;
  rule: string;
  /** Background of the echoed user message. */
  shade: string;
}

export type ThemeTokens = { [K in keyof ThemeColors]: (text: string) => string };

export interface ThemeDef {
  id: string;
  name: string;
  desc: string;
  /** A plain symbol used as the theme's marker in lists (drawn in the accent colour). */
  symbol: string;
  hex: string;
  colors: ThemeColors;
  tokens: ThemeTokens;
  colorFn: (s: string) => string;
  boldFn: (s: string) => string;
  accentFn: (s: string) => string;
  badgeFn: (s: string) => string;
  diffAddBg: (s: string) => string;
  diffRemoveBg: (s: string) => string;
}

interface ThemeSpec {
  id: string;
  name: string;
  desc: string;
  aliases?: string[];
  colors: ThemeColors;
}

const SPECS: ThemeSpec[] = [
  {
    id: 'cyan', name: 'Cyan Cyber', desc: 'Electric neon cyan & obsidian (Default)', aliases: ['blue', 'neon', 'cyber'],
    colors: { accent: '#00f2fe', plan: '#b794f6', ok: '#5fd787', warn: '#ffb454', err: '#ff6b6b', yolo: '#ff8a5c', text: '#dadada', muted: '#bcbcbc', faint: '#8a8a8a', rule: '#767676', shade: '#262626' },
  },
  {
    id: 'purple', name: 'Synthwave Purple', desc: 'Vibrant neon magenta & violet retro', aliases: ['magenta', 'violet', 'pink', 'synthwave'],
    colors: { accent: '#d946ef', plan: '#22d3ee', ok: '#86efac', warn: '#fbbf24', err: '#fb7185', yolo: '#fb923c', text: '#e6e0f0', muted: '#b9b0c9', faint: '#8f869f', rule: '#6d6580', shade: '#221a2e' },
  },
  {
    id: 'matrix', name: 'Matrix Hacker', desc: 'Classic bright phosphor green terminal', aliases: ['green', 'hacker', 'terminal'],
    colors: { accent: '#22c55e', plan: '#a3e635', ok: '#4ade80', warn: '#facc15', err: '#f87171', yolo: '#fb923c', text: '#d4f5dc', muted: '#a7c9b0', faint: '#7fa38a', rule: '#5f7f6a', shade: '#10241a' },
  },
  {
    id: 'amber', name: 'Solar Amber', desc: 'Warm vintage CRT amber gold', aliases: ['yellow', 'gold', 'solar', 'orange'],
    colors: { accent: '#f59e0b', plan: '#fb7185', ok: '#a3e635', warn: '#fde047', err: '#f87171', yolo: '#ef6a3a', text: '#f1e6d0', muted: '#c9b896', faint: '#9f8f70', rule: '#7a6c52', shade: '#2a2012' },
  },
  {
    id: 'crimson', name: 'Ruby Crimson', desc: 'Aggressive cyberpunk scarlet red', aliases: ['red', 'ruby', 'cyberpunk'],
    colors: { accent: '#ef4444', plan: '#f472b6', ok: '#86efac', warn: '#fbbf24', err: '#fca5a5', yolo: '#f59e0b', text: '#f0dede', muted: '#c9b0b0', faint: '#9d8787', rule: '#7a6666', shade: '#2c1618' },
  },
  {
    id: 'monochrome', name: 'Pure Monochrome', desc: 'Crisp minimal pure white & gray', aliases: ['white', 'mono', 'gray', 'grey'],
    colors: { accent: '#f5f5f5', plan: '#a8a8a8', ok: '#bcd9bc', warn: '#e6e6e6', err: '#ff8f8f', yolo: '#ffffff', text: '#e4e4e4', muted: '#b8b8b8', faint: '#8c8c8c', rule: '#6e6e6e', shade: '#262626' },
  },
  {
    id: 'nord', name: 'Nord Frost', desc: 'Arctic frost blues, calm and muted', aliases: ['nordic', 'frost', 'arctic'],
    colors: { accent: '#88c0d0', plan: '#b48ead', ok: '#a3be8c', warn: '#ebcb8b', err: '#e5808b', yolo: '#d08770', text: '#eceff4', muted: '#b6bfd0', faint: '#8791a6', rule: '#616b80', shade: '#2e3440' },
  },
  {
    id: 'dracula', name: 'Dracula', desc: 'Dark purple with vivid pink and green', aliases: ['drac', 'vampire'],
    colors: { accent: '#bd93f9', plan: '#ff79c6', ok: '#50fa7b', warn: '#f1fa8c', err: '#ff6e6e', yolo: '#ffb86c', text: '#f8f8f2', muted: '#c3c7dc', faint: '#8e94b5', rule: '#626a8c', shade: '#282a36' },
  },
  {
    id: 'tokyonight', name: 'Tokyo Night', desc: 'Neon-lit Tokyo night, indigo and blue', aliases: ['tokyo', 'night', 'tokyo-night'],
    colors: { accent: '#7aa2f7', plan: '#bb9af7', ok: '#9ece6a', warn: '#e0af68', err: '#f7768e', yolo: '#ff9e64', text: '#c0caf5', muted: '#a9b1d6', faint: '#7f87b0', rule: '#565f89', shade: '#1a1b26' },
  },
  {
    id: 'gruvbox', name: 'Gruvbox', desc: 'Retro warm earth tones, yellow and orange', aliases: ['gruv', 'retro'],
    colors: { accent: '#fabd2f', plan: '#d3869b', ok: '#b8bb26', warn: '#fe8019', err: '#fb4934', yolo: '#e8742a', text: '#ebdbb2', muted: '#bdae93', faint: '#98897a', rule: '#7c6f64', shade: '#32302f' },
  },
  {
    id: 'catppuccin', name: 'Catppuccin Mocha', desc: 'Soothing pastel mocha, mauve accents', aliases: ['mocha', 'cat', 'catppuccin-mocha'],
    colors: { accent: '#cba6f7', plan: '#89b4fa', ok: '#a6e3a1', warn: '#f9e2af', err: '#f38ba8', yolo: '#fab387', text: '#cdd6f4', muted: '#bac2de', faint: '#9399b2', rule: '#6c7086', shade: '#1e1e2e' },
  },
  {
    id: 'solarized', name: 'Solarized Dark', desc: 'Low-glare teal and olive classic', aliases: ['solarized-dark', 'sol'],
    colors: { accent: '#2aa198', plan: '#8f94dc', ok: '#9bb000', warn: '#d9a21b', err: '#ee6a67', yolo: '#ea7b4b', text: '#bcc8c8', muted: '#9eb0b2', faint: '#7f9aa0', rule: '#586e75', shade: '#0b2a33' },
  },
  {
    id: 'rosepine', name: 'Rose Pine', desc: 'Soft rose and gold on dusky violet', aliases: ['rose', 'pine', 'rose-pine'],
    colors: { accent: '#ebbcba', plan: '#c4a7e7', ok: '#9ccfd8', warn: '#f6c177', err: '#eb6f92', yolo: '#ea9a97', text: '#e0def4', muted: '#c1bed6', faint: '#908caa', rule: '#6e6a86', shade: '#1f1d2e' },
  },
  {
    id: 'monokai', name: 'Monokai', desc: 'Bold lime and magenta editor classic', aliases: ['mono-kai'],
    colors: { accent: '#a6e22e', plan: '#ae81ff', ok: '#a6e22e', warn: '#e6db74', err: '#f92672', yolo: '#fd971f', text: '#f8f8f2', muted: '#cfcfc2', faint: '#9a9a8e', rule: '#75715e', shade: '#272822' },
  },
  {
    id: 'github', name: 'GitHub Dark', desc: 'Clean blue and green, easy on the eyes', aliases: ['gh', 'git'],
    colors: { accent: '#58a6ff', plan: '#bc8cff', ok: '#3fb950', warn: '#d29922', err: '#f85149', yolo: '#e3742f', text: '#e6edf3', muted: '#b1bac4', faint: '#8b949e', rule: '#6e7681', shade: '#161b22' },
  },
  {
    id: 'ocean', name: 'Deep Ocean', desc: 'Deep sea cyan and indigo', aliases: ['sea', 'deepsea'],
    colors: { accent: '#38bdf8', plan: '#818cf8', ok: '#34d399', warn: '#fbbf24', err: '#fb7185', yolo: '#fb923c', text: '#dbeafe', muted: '#a9c3dd', faint: '#7f9dba', rule: '#55708c', shade: '#0c1a2b' },
  },
  {
    id: 'sunset', name: 'Sunset Glow', desc: 'Coral and violet dusk gradient', aliases: ['dusk', 'coral'],
    colors: { accent: '#ff7e67', plan: '#c084fc', ok: '#86efac', warn: '#fcd34d', err: '#f87171', yolo: '#fb923c', text: '#f5e6e0', muted: '#d1b8b0', faint: '#a69089', rule: '#806560', shade: '#2b1621' },
  },
  {
    id: 'forest', name: 'Forest Moss', desc: 'Mossy greens and woodland earth', aliases: ['moss', 'woods'],
    colors: { accent: '#8fbc6a', plan: '#d4a373', ok: '#a3d977', warn: '#e9c46a', err: '#ef8a72', yolo: '#f4a261', text: '#e5e8d8', muted: '#b9c0a6', faint: '#8f987b', rule: '#66705a', shade: '#1c2418' },
  },
];

const bold = (s: string) => (pc.isColorSupported ? `\x1b[1m${s}\x1b[22m` : s);

function buildTheme(spec: ThemeSpec, options: ColorOptions = {}): ThemeDef {
  const c = spec.colors;
  const tokens = {} as ThemeTokens;
  for (const key of Object.keys(c) as Array<keyof ThemeColors>) {
    tokens[key] = key === 'shade' ? bgHex(c[key], options) : fgHex(c[key], options);
  }
  const black = fgHex('#000000', options);
  return {
    id: spec.id,
    name: spec.name,
    desc: spec.desc,
    symbol: '●',
    hex: c.accent,
    colors: c,
    tokens,
    colorFn: tokens.accent,
    boldFn: (s) => bold(tokens.accent(s)),
    accentFn: tokens.plan,
    badgeFn: (s) => bgHex(c.accent, options)(black(s)),
    diffAddBg: (s) => bgHex(c.ok, options)(black(s)),
    diffRemoveBg: (s) => bgHex(c.err, options)(black(s)),
  };
}

export const THEMES: Record<string, ThemeDef> = Object.fromEntries(SPECS.map((spec) => [spec.id, buildTheme(spec)]));

const THEME_ALIASES: Record<string, string> = {};
SPECS.forEach((spec, index) => {
  THEME_ALIASES[String(index + 1)] = spec.id; // "1" ... "18"
  for (const alias of spec.aliases ?? []) THEME_ALIASES[alias] = spec.id;
});

let activeThemeId = 'cyan';

export function getTheme(themeId?: string): ThemeDef {
  const id = (themeId || activeThemeId).toLowerCase().trim();
  return THEMES[id] || (THEME_ALIASES[id] && THEMES[THEME_ALIASES[id]]) || THEMES.cyan;
}

export function findTheme(query: string): ThemeDef | null {
  const q = (query || '').toLowerCase().trim();
  if (!q) return null;
  if (THEMES[q]) return THEMES[q];
  if (THEME_ALIASES[q] && THEMES[THEME_ALIASES[q]]) return THEMES[THEME_ALIASES[q]];

  const all = listThemes();
  const found = all.find(t =>
    t.id.toLowerCase() === q ||
    t.id.toLowerCase().startsWith(q) ||
    t.name.toLowerCase().includes(q)
  );
  return found || null;
}

export function setActiveTheme(themeId: string): ThemeDef {
  const theme = getTheme(themeId);
  activeThemeId = theme.id;
  return theme;
}

export function getCurrentTheme(): ThemeDef {
  return THEMES[activeThemeId] || THEMES.cyan;
}

export function listThemes(): ThemeDef[] {
  return Object.values(THEMES);
}

/** Four dots showing the palette at a glance: accent, plan, ok, warn. */
export function renderSwatch(theme: ThemeDef): string {
  return [theme.tokens.accent, theme.tokens.plan, theme.tokens.ok, theme.tokens.warn].map((paint) => paint(theme.symbol)).join(' ');
}

/** A small sample screen painted with the theme, shown while choosing one. */
export function renderThemePreview(theme: ThemeDef): string {
  const k = theme.tokens;
  const modes = [k.accent(`${theme.symbol} agent`), k.plan('◆ plan'), k.yolo('▲ yolo')].join('   ');
  const states = [k.ok('✓ done'), k.warn('! careful'), k.err('✗ failed')].join('   ');
  const text = [k.text('text'), k.muted('muted'), k.faint('faint'), k.rule('─────')].join('  ');
  const message = k.shade(` ${k.accent('❯')} ${k.text('explain this project')}      `);
  return [modes, states, text, message].join('\n');
}

/** Builds a theme with explicit colour options (tests, forced 256-colour output). */
export function buildThemeFor(id: string, options: ColorOptions): ThemeDef {
  const spec = SPECS.find((s) => s.id === id);
  if (!spec) throw new Error(`Unknown theme: ${id}`);
  return buildTheme(spec, options);
}

export const THEME_IDS: string[] = SPECS.map((s) => s.id);
