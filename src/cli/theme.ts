import pc from 'picocolors';

export interface ThemeDef {
  id: string;
  name: string;
  desc: string;
  emoji: string;
  colorFn: (s: string) => string;
  boldFn: (s: string) => string;
  accentFn: (s: string) => string;
  badgeFn: (s: string) => string;
  diffAddBg: (s: string) => string;
  diffRemoveBg: (s: string) => string;
  hex: string;
}

export const THEMES: Record<string, ThemeDef> = {
  cyan: {
    id: 'cyan',
    name: 'Cyan Cyber',
    desc: 'Electric neon cyan & obsidian (Default)',
    emoji: '⚡',
    colorFn: (s) => pc.cyan(s),
    boldFn: (s) => pc.bold(pc.cyan(s)),
    accentFn: (s) => pc.blue(s),
    badgeFn: (s) => pc.bgCyan(pc.black(` ${s} `)),
    diffAddBg: (s) => pc.bgCyan(pc.black(s)),
    diffRemoveBg: (s) => pc.bgBlue(pc.white(s)),
    hex: '#00f2fe'
  },
  purple: {
    id: 'purple',
    name: 'Synthwave Purple',
    desc: 'Vibrant neon magenta & violet retro',
    emoji: '🟣',
    colorFn: (s) => pc.magenta(s),
    boldFn: (s) => pc.bold(pc.magenta(s)),
    accentFn: (s) => pc.blue(s),
    badgeFn: (s) => pc.bgMagenta(pc.black(` ${s} `)),
    diffAddBg: (s) => pc.bgMagenta(pc.black(s)),
    diffRemoveBg: (s) => pc.bgBlue(pc.white(s)),
    hex: '#d946ef'
  },
  matrix: {
    id: 'matrix',
    name: 'Matrix Hacker',
    desc: 'Classic bright phosphor green terminal',
    emoji: '🟢',
    colorFn: (s) => pc.green(s),
    boldFn: (s) => pc.bold(pc.green(s)),
    accentFn: (s) => pc.cyan(s),
    badgeFn: (s) => pc.bgGreen(pc.black(` ${s} `)),
    diffAddBg: (s) => pc.bgGreen(pc.black(s)),
    diffRemoveBg: (s) => pc.bgRed(pc.white(s)),
    hex: '#22c55e'
  },
  amber: {
    id: 'amber',
    name: 'Solar Amber',
    desc: 'Warm vintage CRT amber gold',
    emoji: '🟡',
    colorFn: (s) => pc.yellow(s),
    boldFn: (s) => pc.bold(pc.yellow(s)),
    accentFn: (s) => pc.red(s),
    badgeFn: (s) => pc.bgYellow(pc.black(` ${s} `)),
    diffAddBg: (s) => pc.bgYellow(pc.black(s)),
    diffRemoveBg: (s) => pc.bgRed(pc.white(s)),
    hex: '#f59e0b'
  },
  crimson: {
    id: 'crimson',
    name: 'Ruby Crimson',
    desc: 'Aggressive cyberpunk scarlet red',
    emoji: '🔴',
    colorFn: (s) => pc.red(s),
    boldFn: (s) => pc.bold(pc.red(s)),
    accentFn: (s) => pc.magenta(s),
    badgeFn: (s) => pc.bgRed(pc.white(` ${s} `)),
    diffAddBg: (s) => pc.bgRed(pc.white(s)),
    diffRemoveBg: (s) => pc.bgMagenta(pc.white(s)),
    hex: '#ef4444'
  },
  monochrome: {
    id: 'monochrome',
    name: 'Pure Monochrome',
    desc: 'Crisp minimal pure white & gray',
    emoji: '⚪',
    colorFn: (s) => pc.white(s),
    boldFn: (s) => pc.bold(pc.white(s)),
    accentFn: (s) => pc.dim(s),
    badgeFn: (s) => pc.bgWhite(pc.black(` ${s} `)),
    diffAddBg: (s) => pc.bgWhite(pc.black(s)),
    diffRemoveBg: (s) => pc.bgBlack(pc.white(s)),
    hex: '#ffffff'
  }
};

const THEME_ALIASES: Record<string, string> = {
  '1': 'cyan',
  '2': 'purple',
  '3': 'matrix',
  '4': 'amber',
  '5': 'crimson',
  '6': 'monochrome',
  'blue': 'cyan',
  'neon': 'cyan',
  'cyber': 'cyan',
  'magenta': 'purple',
  'violet': 'purple',
  'pink': 'purple',
  'synthwave': 'purple',
  'green': 'matrix',
  'hacker': 'matrix',
  'terminal': 'matrix',
  'yellow': 'amber',
  'gold': 'amber',
  'solar': 'amber',
  'orange': 'amber',
  'red': 'crimson',
  'ruby': 'crimson',
  'cyberpunk': 'crimson',
  'white': 'monochrome',
  'mono': 'monochrome',
  'gray': 'monochrome',
  'grey': 'monochrome'
};

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
