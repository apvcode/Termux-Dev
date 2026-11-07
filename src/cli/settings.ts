import { LiveBlock } from './liveblock.js';
import { splitKeys } from './keys.js';
import { PermissionInput, PermissionOutput } from './permission.js';
import { THEME_IDS, getTheme, renderSwatch, renderThemePreview } from './theme.js';
import { palette } from './ui.js';
import { displayWidth, padEndWidth, stripAnsi, truncateWidth, wrapText, wrapWords } from './width.js';
import { LANGUAGE_IDS, languageName, t } from '../core/i18n.js';

export { wrapWords };

/**
 * The settings screen: one panel that is redrawn in place (nothing piles up in the
 * scrollback), moves with the arrow keys without wrapping around at the ends, and applies
 * every change immediately, so a theme can be judged on the panel itself.
 *
 * Rendering is a pure function of the state; `runSettings` only adds the keyboard.
 */

export const ITERATION_STEPS = [10, 20, 30, 50, 100, 200, 500, 9999];
/** Limits for the cost of a session, in USD; 0 is no limit. */
export const COST_LIMIT_STEPS = [0, 0.5, 1, 2, 5, 10, 20, 50];

export interface SettingsState {
  themeId: string;
  layout: 'clean' | 'classic';
  pureBlack: boolean;
  autoApprove: boolean;
  /** Extra: with YOLO on, nothing is asked at all (dangerous commands and credential files too). */
  extra?: boolean;
  maxIterations: number;
  memory: boolean;
  updates: boolean;
  /** The most a session may cost, USD; 0 for no limit. */
  costLimit: number;
  /** Interface language id (see core/i18n.ts). */
  language: string;
}

export type SettingKey = 'theme' | 'layout' | 'pureBlack' | 'autoApprove' | 'extra' | 'maxIterations' | 'memory' | 'updates' | 'costLimit' | 'language' | 'about';

type SectionKey = 'appearance' | 'agent' | 'general' | 'about';

interface RowDef {
  key: SettingKey;
  sectionKey: SectionKey;
  /** The texts follow the interface language, so they are read when drawn, not when loaded. */
  readonly section: string;
  readonly label: string;
  readonly short: string;
  kind: 'choice' | 'toggle' | 'info';
}

function row(key: SettingKey, sectionKey: SectionKey, kind: RowDef['kind']): RowDef {
  return {
    key,
    sectionKey,
    kind,
    get section() { return t(`settings.section.${sectionKey}` as const); },
    get label() { return t(`settings.row.${key}` as const); },
    get short() { return t(`settings.short.${key}` as const); },
  };
}

export const ROWS: RowDef[] = [
  row('theme', 'appearance', 'choice'),
  row('layout', 'appearance', 'choice'),
  row('pureBlack', 'appearance', 'toggle'),
  row('autoApprove', 'agent', 'toggle'),
  row('extra', 'agent', 'toggle'),
  row('maxIterations', 'agent', 'choice'),
  row('memory', 'agent', 'toggle'),
  row('updates', 'agent', 'toggle'),
  row('costLimit', 'agent', 'choice'),
  row('language', 'general', 'choice'),
  row('about', 'about', 'info'),
];

/** The rows on screen: Extra is there only while YOLO is on. */
export function visibleRows(state: SettingsState): RowDef[] {
  return ROWS.filter((r) => r.key !== 'extra' || state.autoApprove);
}

const hintFor = (key: SettingKey): string => t(`settings.hint.${key}` as const);

// ───────────────────────── state changes (pure) ─────────────────────────

export type Direction = 1 | -1;

function cycle<T>(items: T[], current: T, direction: Direction): T {
  const index = items.indexOf(current);
  if (index === -1) return items[direction === 1 ? 0 : items.length - 1];
  return items[(index + direction + items.length) % items.length];
}

/** Closest step of the iteration list, for values set by hand in the config file. */
function nearestStep(value: number): number {
  return ITERATION_STEPS.reduce((best, step) => (Math.abs(step - value) < Math.abs(best - value) ? step : best), ITERATION_STEPS[0]);
}

/**
 * Applies one key press to a setting. Left/right move through the values of a choice;
 * toggles flip whichever way you press. The input state is never modified.
 */
export function changeSetting(state: SettingsState, key: SettingKey, direction: Direction): SettingsState {
  switch (key) {
    case 'theme':
      return { ...state, themeId: cycle(THEME_IDS, getTheme(state.themeId).id, direction) };
    case 'layout':
      return { ...state, layout: state.layout === 'clean' ? 'classic' : 'clean' };
    case 'maxIterations': {
      const current = ITERATION_STEPS.includes(state.maxIterations) ? state.maxIterations : nearestStep(state.maxIterations);
      return { ...state, maxIterations: cycle(ITERATION_STEPS, current, direction) };
    }
    case 'pureBlack':
      return { ...state, pureBlack: !state.pureBlack };
    case 'autoApprove':
      // Extra goes with YOLO: without it there is nothing to take the questions away from.
      return { ...state, autoApprove: !state.autoApprove, extra: state.autoApprove ? false : !!state.extra };
    case 'extra':
      return state.autoApprove ? { ...state, extra: !state.extra } : state;
    case 'memory':
      return { ...state, memory: !state.memory };
    case 'updates':
      return { ...state, updates: !state.updates };
    case 'costLimit': {
      const current = COST_LIMIT_STEPS.reduce((best, step) => (Math.abs(step - state.costLimit) < Math.abs(best - state.costLimit) ? step : best), COST_LIMIT_STEPS[0]);
      return { ...state, costLimit: cycle(COST_LIMIT_STEPS, current, direction) };
    }
    case 'language':
      return { ...state, language: cycle(LANGUAGE_IDS, LANGUAGE_IDS.includes(state.language) ? state.language : LANGUAGE_IDS[0], direction) };
    default:
      return state;
  }
}

/** Moves the focus one row. It stops at the first and the last row instead of wrapping. */
export function moveFocus(focus: number, delta: number, count: number = ROWS.length): number {
  return Math.max(0, Math.min(count - 1, focus + delta));
}

// ───────────────────────── rendering ─────────────────────────

const LABEL_WIDTH = 21;
const SHORT_LABEL_WIDTH = 11;

/** A column wide enough for the longest label of the current language (never narrower than the English one). */
const labelColumn = (pick: (r: RowDef) => string, least: number, most: number): number =>
  Math.min(most, Math.max(least, ...ROWS.map((r) => displayWidth(pick(r)))));


function formatIterations(n: number): string {
  return n >= 9999 ? t('settings.value.unlimited') : t('settings.value.steps', { n });
}

function onOff(on: boolean, onColor = palette.ok, glyph = '●'): string {
  return on ? onColor(`${glyph} ${t('settings.value.on')}`) : palette.faint(`○ ${t('settings.value.off')}`);
}

/** The current value of a row, styled; `room` is the number of cells available. */
function valueText(key: SettingKey, state: SettingsState, version: string, room: number, withSwatch: boolean): string {
  switch (key) {
    case 'theme': {
      const theme = getTheme(state.themeId);
      const swatch = withSwatch ? renderSwatch(theme) + '  ' : theme.tokens.accent(theme.symbol) + ' ';
      const used = withSwatch ? 9 : 2;
      return swatch + palette.text(truncateWidth(theme.name, Math.max(4, room - used)));
    }
    case 'layout':
      return palette.text(state.layout === 'clean' ? t('settings.value.clean') : t('settings.value.classic'));
    case 'pureBlack':
      return onOff(state.pureBlack);
    case 'autoApprove':
      return onOff(state.autoApprove, palette.yolo, '▲');
    case 'extra':
      return onOff(!!state.extra, palette.err, '▲');
    case 'maxIterations':
      return palette.text(formatIterations(state.maxIterations));
    case 'memory':
      return onOff(state.memory);
    case 'updates':
      return onOff(state.updates);
    case 'costLimit':
      return state.costLimit > 0 ? palette.text(`$${state.costLimit}`) : palette.faint(`○ ${t('settings.value.off')}`);
    case 'language':
      return palette.text(truncateWidth(languageName(state.language), Math.max(4, room)));
    case 'about':
      return palette.muted(`v${version}`);
  }
}

/** Help text under the list: what the focused setting does, plus a live preview for themes. */
function detailLines(state: SettingsState, key: SettingKey, width: number, maxLines: number, version: string): string[] {
  if (maxLines <= 0) return [];

  if (key === 'theme') {
    const theme = getTheme(state.themeId);
    // The sample lines are styled; on a very narrow screen they are cut (and lose their colour).
    const preview = renderThemePreview(theme).split('\n').map((line) => (displayWidth(line) <= width ? line : truncateWidth(stripAnsi(line), width)));
    return [palette.muted(truncateWidth(`${theme.name}: ${theme.desc}`, width)), ...preview].slice(0, maxLines);
  }

  const hint = key === 'about' ? `devx v${version}. ${hintFor('about')}` : hintFor(key);
  // A warning is never the line that gets cut off: it keeps its row, the explanation gives way.
  const warning = key === 'autoApprove' && state.autoApprove ? t('settings.warnYolo')
    : key === 'extra' && state.extra ? t('settings.warnExtra') : '';
  const hintRows = Math.max(1, maxLines - (warning ? 1 : 0));

  let wrapped = wrapText(hint, width);
  if (wrapped.length > hintRows) {
    wrapped = wrapped.slice(0, hintRows);
    wrapped[hintRows - 1] = truncateWidth(wrapped[hintRows - 1], width - 1).replace(/…$/, '') + '…';
  }
  const out = wrapped.map((line) => palette.muted(line));
  if (warning && out.length < maxLines) out.push((key === 'extra' ? palette.err : palette.yolo)(truncateWidth(warning, width)));
  return out;
}

/** The key hints, shortened step by step until they fit `width` cells. */
function keyHints(width: number): string {
  const variants: Array<Array<[string, string]>> = [
    [['↑↓', t('settings.keys.move')], ['←→', t('settings.keys.change')], ['enter', t('settings.keys.toggle')], ['esc', t('settings.keys.close')]],
    [['↑↓', t('settings.keys.move')], ['←→', t('settings.keys.change')], ['esc', t('settings.keys.close')]],
    [['↑↓', ''], ['←→', ''], ['esc', '']],
    [['esc', '']],
  ];
  for (const items of variants) {
    const text = items.map(([k, label]) => palette.text(k) + (label ? ' ' + palette.faint(label) : '')).join(palette.faint(items[0][1] ? ' · ' : ' '));
    if (displayWidth(text) <= width) return text;
  }
  return '';
}

type Density = 'roomy' | 'tight';

function renderCard(state: SettingsState, focus: number, cols: number, density: Density, version: string): string[] {
  const W = Math.min(cols - 1, 76);
  const iw = W - 4;
  const rule = palette.rule;
  const maxDetail = density === 'roomy' ? 5 : 4;
  const lines: string[] = [];

  const box = (content: string, shaded = false) =>
    rule('│') + (shaded ? palette.shade(' ' + padEndWidth(content, iw) + ' ') : ' ' + padEndWidth(content, iw) + ' ') + rule('│');

  const title = ' ' + palette.bold(palette.accent(t('settings.title'))) + ' ';
  const tag = ' ' + palette.faint(`devx v${version}`) + ' ';
  const fill = Math.max(0, W - 4 - displayWidth(title) - displayWidth(tag));
  lines.push(rule('╭─') + title + rule('─'.repeat(fill)) + tag + rule('─╮'));
  if (density === 'roomy') lines.push(box(''));

  const labelWidth = labelColumn((r) => r.label, LABEL_WIDTH, Math.floor(iw / 2));
  const shown = visibleRows(state);
  let section = '';
  shown.forEach((row, index) => {
    if (row.sectionKey !== section) {
      if (section !== '' && density === 'roomy') lines.push(box(''));
      section = row.sectionKey;
      lines.push(box('  ' + palette.faint(row.section.toUpperCase())));
    }
    const focused = index === focus;
    const mark = focused ? palette.accent('❯') : ' ';
    const label = focused ? palette.bold(palette.text(row.label)) : palette.muted(row.label);
    const control = row.kind === 'choice' ? (focused ? palette.accent('‹ ›') : palette.faint('‹ ›')) : '';
    const prefix = ` ${mark} ${padEndWidth(label, labelWidth)}`;
    const room = iw - displayWidth(prefix) - 4;
    const left = prefix + valueText(row.key, state, version, room, true);
    const gap = Math.max(1, iw - displayWidth(left) - displayWidth(control) - 1);
    lines.push(box(left + ' '.repeat(gap) + control, focused));
  });

  if (density === 'roomy') lines.push(box(''));
  lines.push(rule('├' + '─'.repeat(W - 2) + '┤'));
  // The detail area always has the same height, so the panel does not jump while you move.
  const detail = detailLines(state, shown[Math.min(focus, shown.length - 1)].key, iw - 2, maxDetail, version);
  for (let i = 0; i < maxDetail; i++) lines.push(box(detail[i] ? '  ' + detail[i] : ''));
  lines.push(rule('╰' + '─'.repeat(W - 2) + '╯'));
  lines.push(' ' + keyHints(cols - 2));
  return lines;
}

function renderCompact(state: SettingsState, focus: number, cols: number, rows: number, version: string): string[] {
  const width = Math.max(20, cols - 1);
  const lines: string[] = [];
  const shortWidth = labelColumn((r) => r.short, SHORT_LABEL_WIDTH, Math.floor(width / 2));
  lines.push(' ' + palette.bold(palette.accent(t('settings.title'))) + palette.faint(` · v${version}`));

  const shown = visibleRows(state);
  shown.forEach((row, index) => {
    const focused = index === focus;
    const mark = focused ? palette.accent('❯') : ' ';
    const label = focused ? palette.bold(palette.text(row.short)) : palette.muted(row.short);
    const prefix = ` ${mark} ${padEndWidth(label, shortWidth)}`;
    const room = Math.max(4, width - displayWidth(prefix) - 1);
    const content = prefix + valueText(row.key, state, version, room, false);
    lines.push(focused ? palette.shade(padEndWidth(content, width)) : content);
  });

  // The title, the rows and the key hints come first; the help text takes what is left of the screen.
  const detailCount = Math.max(0, Math.min(rows >= 16 ? 2 : 1, rows - 1 - (shown.length + 2)));
  const detail = detailLines(state, shown[Math.min(focus, shown.length - 1)].key, width - 2, detailCount, version);
  for (let i = 0; i < detailCount; i++) lines.push(detail[i] ? ' ' + detail[i] : '');
  lines.push(' ' + keyHints(width - 1));
  return lines;
}

/**
 * The whole panel as terminal rows. Wide terminals get a framed card (roomy or tight
 * depending on the height), narrow ones a plain list.
 */
export function renderSettings(state: SettingsState, focus: number, cols: number, rows: number, version: string): string[] {
  // The card needs a line for each row of the list: it is lower when Extra is there.
  const count = visibleRows(state).length;
  if (cols < 60 || rows < count + 13) return renderCompact(state, focus, cols, rows, version);
  return renderCard(state, focus, cols, rows >= count + 19 ? 'roomy' : 'tight', version);
}

// ───────────────────────── keyboard ─────────────────────────

export interface SettingsOptions {
  version: string;
  /** Called after every change, before the redraw: apply the change to the running app here. */
  onChange?: (key: SettingKey, state: SettingsState) => void;
  io?: { input: PermissionInput; output: PermissionOutput & { rows?: number } };
}

/**
 * Shows the panel and resolves with the final state when it is closed (Esc, q or Ctrl+C).
 * The panel is erased on close.
 */
export function runSettings(initial: SettingsState, options: SettingsOptions): Promise<SettingsState> {
  return new Promise((resolve) => {
    const { input, output } = options.io ?? { input: process.stdin, output: process.stdout };
    const block = new LiveBlock(output);
    let state = { ...initial };
    let focus = 0;

    const draw = () => block.draw(renderSettings(state, focus, output.columns || 80, output.rows || 30, options.version));

    const wasRaw = !!input.isRaw;
    input.setRawMode?.(true);
    input.resume();
    const stopWatching = block.watchResize(draw);

    const rowsNow = () => visibleRows(state);
    const change = (direction: Direction) => {
      const key = rowsNow()[Math.min(focus, rowsNow().length - 1)].key;
      const next = changeSetting(state, key, direction);
      if (next === state) return;
      state = next;
      // The Extra row goes away with YOLO: the focus must not be left behind the end of the list.
      focus = Math.min(focus, rowsNow().length - 1);
      options.onChange?.(key, state);
      draw();
    };

    const close = () => {
      input.off('data', onData);
      stopWatching();
      block.erase();
      if (!wasRaw) input.setRawMode?.(false);
      resolve(state);
    };

    let closed = false;
    const handleKey = (key: string) => {
      const move = (delta: number) => {
        const next = moveFocus(focus, delta, rowsNow().length);
        if (next !== focus) {
          focus = next;
          draw();
        }
      };

      if (key === '\x1b[A' || key === '\x1bOA' || key === 'k') return move(-1);
      if (key === '\x1b[B' || key === '\x1bOB' || key === 'j') return move(1);
      // Home / End are sent differently by different terminals.
      if (key === '\x1b[H' || key === '\x1b[1~' || key === '\x1b[7~' || key === '\x1bOH' || key === 'g') return move(-rowsNow().length);
      if (key === '\x1b[F' || key === '\x1b[4~' || key === '\x1b[8~' || key === '\x1bOF' || key === 'G') return move(rowsNow().length);
      if (key === '\x1b[C' || key === '\x1bOC' || key === 'l' || key === ' ' || key === '\r' || key === '\n') return change(1);
      if (key === '\x1b[D' || key === '\x1bOD' || key === 'h') return change(-1);
      if (key === '\x1b' || key === 'q' || key === '\x03') {
        closed = true;
        return close();
      }
    };

    // Several key presses can arrive in one chunk; handle them one by one.
    const onData = (chunk: Buffer | string) => {
      for (const key of splitKeys(chunk.toString())) {
        if (closed) return;
        handleKey(key);
      }
    };

    input.on('data', onData);
    draw();
  });
}
