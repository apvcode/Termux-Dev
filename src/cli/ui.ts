import pc from 'picocolors';
import os from 'os';
import path from 'path';
import { getCurrentTheme } from './theme.js';
import { t } from '../core/i18n.js';
import { CostState, formatMoney } from '../core/cost.js';
import {
  codePointWidth,
  displayWidth,
  padEndWidth,
  stripAnsi,
  truncateWidth,
  wrapText,
  wrapWidth,
  wrapWords,
} from './width.js';

/**
 * devx visual language.
 *
 *  - one accent colour (the active theme), everything else is a quiet grey ramp;
 *  - three modes with their own colour: agent (accent), plan (violet), yolo (orange);
 *  - rounded frames, a prompt that carries the status (mode, model, context, cost) in its
 *    borders, a `●` gutter for the assistant and `◆` lines for tool calls;
 *  - every width is measured in terminal cells and every layout has a narrow variant
 *    (Termux in portrait is ~40 columns).
 *
 * Everything here is a pure function of its inputs (plus the active theme) so it can be
 * unit-tested without a terminal.
 */

// ───────────────────────── palette ─────────────────────────

const colorOn = () => pc.isColorSupported;
const tokens = () => getCurrentTheme().tokens;

/** Every colour of the interface comes from the active theme (see theme.ts). */
export const palette = {
  text: (s: string) => tokens().text(s),
  muted: (s: string) => tokens().muted(s),
  faint: (s: string) => tokens().faint(s),
  rule: (s: string) => tokens().rule(s),
  ok: (s: string) => tokens().ok(s),
  warn: (s: string) => tokens().warn(s),
  err: (s: string) => tokens().err(s),
  plan: (s: string) => tokens().plan(s),
  yolo: (s: string) => tokens().yolo(s),
  shade: (s: string) => tokens().shade(s),
  accent: (s: string) => tokens().accent(s),
  bold: (s: string) => (colorOn() ? `\x1b[1m${s}\x1b[22m` : s),
};

export type UiMode = 'agent' | 'plan' | 'yolo';

export interface ModeStyle {
  label: string;
  glyph: string;
  color: (s: string) => string;
}

export function resolveMode(planMode: boolean, yolo: boolean): UiMode {
  if (planMode) return 'plan';
  return yolo ? 'yolo' : 'agent';
}

export function modeStyle(mode: UiMode): ModeStyle {
  switch (mode) {
    case 'plan':
      return { label: 'plan', glyph: '◆', color: palette.plan };
    case 'yolo':
      return { label: 'yolo', glyph: '▲', color: palette.yolo };
    default:
      return { label: 'agent', glyph: '●', color: palette.accent };
  }
}

// ───────────────────────── small helpers ─────────────────────────

export function formatTokens(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`;
  return `${Math.round(n)}`;
}

/** "$0.0123", "~$0.0123" (estimate), "$0.0123+" (some requests unpriced) or "n/a". */
export function formatCost(cost: number, state: CostState = {}): string {
  return formatMoney(cost, { priced: cost > 0 ? 1 : (state.priced ?? 1), ...state });
}

export function formatDuration(ms: number): string {
  const s = ms / 1000;
  if (s < 10) return `${s.toFixed(1)}s`;
  if (s < 60) return `${Math.round(s)}s`;
  const m = Math.floor(s / 60);
  return `${m}m ${Math.round(s - m * 60)}s`;
}

/** `/home/me/projects/app` → `~/projects/app`, shortened from the left to fit `max` cells. */
export function shortenPath(p: string, max: number, home: string = os.homedir()): string {
  let s = p;
  if (s === home) s = '~';
  else if (s.startsWith(home + path.sep)) s = '~' + s.slice(home.length);
  s = s.split(path.sep).join('/');
  if (displayWidth(s) <= max) return s;

  const parts = s.split('/');
  let out = parts[parts.length - 1];
  for (let i = parts.length - 2; i >= 0; i--) {
    const candidate = `${parts[i]}/${out}`;
    if (displayWidth(`…/${candidate}`) > max) break;
    out = candidate;
  }
  return truncateWidth(`…/${out}`, max);
}

/** A thin context meter: `━━━━────`. */
export function renderMeter(percent: number, cells: number): string {
  const clamped = Math.max(0, Math.min(100, percent));
  const filled = Math.round((clamped / 100) * cells);
  const color = clamped >= 90 ? palette.err : clamped >= 75 ? palette.warn : palette.accent;
  return color('━'.repeat(filled)) + palette.faint('─'.repeat(Math.max(0, cells - filled)));
}

const BADGE = /(\[(?:Pasted text #\d+ \+\d+ lines|\d+\.png \d+kb)\])/g;
/** Attachment tags such as `[Pasted text #1 +12 lines]` are drawn muted. */
export function formatBadges(text: string): string {
  return text.replace(BADGE, (m) => palette.muted(m));
}

/** The header card is a card: it stops growing at a comfortable width. */
export function contentWidth(cols: number): number {
  return Math.max(24, Math.min(cols - 1, 88));
}

/** The prompt and the echo of a sent message use the full terminal width. */
export function frameWidth(cols: number): number {
  return Math.max(20, cols - 1);
}

/** Below this width (a phone in portrait) the prompt is a single compact line. */
export const COMPACT_COLS = 60;
export function isCompact(cols: number): boolean {
  return cols < COMPACT_COLS;
}

// ───────────────────────── header card ─────────────────────────

export interface HeaderInfo {
  version: string;
  model: string;
  provider?: string;
  cwd: string;
  branch?: string;
  dirty?: boolean;
  /** Auto-approve is on. The live plan / agent state is shown by the prompt, not here. */
  yolo?: boolean;
  tip?: string;
}

export const TIPS = [
  'Press Tab to switch between plan and agent mode',
  'Type @ to attach a file, or paste a screenshot with Ctrl+V',
  '/undo reverts the last file edits made by the agent',
  '/compact shrinks the context when a session gets long',
  '/serve previews a web app on your phone through a QR code',
  '/memory add <rule> teaches devx the conventions of your project',
  'Use devx -p "task" for one-shot runs in scripts and CI',
  '/model switches the model, /theme changes the colours',
];

export function pickTip(now: number = Date.now()): string {
  return TIPS[Math.floor(now / 86_400_000) % TIPS.length];
}

function brand(version: string): string {
  return palette.accent('▍') + palette.bold('devx') + '  ' + palette.faint(`v${version}`);
}

export function renderHeader(info: HeaderInfo, cols: number): string[] {
  const W = contentWidth(cols);
  const modePill = info.yolo ? palette.yolo(palette.bold('▲ auto-approve')) : '';
  const branch = info.branch
    ? `${palette.faint('git:')}${palette.muted(info.branch)}${info.dirty ? palette.warn('*') : ''}`
    : '';
  const lines: string[] = [];

  // Narrow screens (phone in portrait): no frame, three short lines.
  if (W < 46) {
    const limit = Math.max(8, cols - 1);
    const room = Math.max(6, cols - 3);
    const titleOptions = info.yolo
      ? [`${brand(info.version)}  ${modePill}`, `${brand(info.version)}  ${palette.yolo('▲ yolo')}`, `${brand(info.version)} ${palette.yolo('▲')}`, brand(info.version)]
      : [brand(info.version)];
    lines.push(titleOptions.find((t) => displayWidth(t) <= limit) ?? truncateWidth(stripAnsi(brand(info.version)), limit));
    lines.push(`  ${palette.text(truncateWidth(info.model, room))}`);
    const branchWidth = info.branch ? displayWidth(stripAnsi(branch)) + 2 : 0;
    const showBranch = branch && room - branchWidth >= 8;
    lines.push(`  ${palette.muted(shortenPath(info.cwd, showBranch ? room - branchWidth : room))}${showBranch ? '  ' + branch : ''}`);
    if (info.tip && cols >= 36) {
      lines.push('');
      lines.push(`  ${palette.faint(truncateWidth(info.tip, room))}`);
    }
    return lines;
  }

  const inner = W - 4; // two border cells + one space of padding on each side
  const row = (content: string) =>
    palette.rule('│') + ' ' + padEndWidth(content, inner) + ' ' + palette.rule('│');
  const labelW = 9;
  const field = (label: string, value: string, right = '') => {
    const room = inner - labelW - (right ? displayWidth(right) + 2 : 0);
    // The hint sits right after the value, not at the far edge of the card.
    return palette.faint(label.padEnd(labelW)) + palette.text(truncateWidth(value, Math.max(6, room))) + (right ? '  ' + right : '');
  };

  lines.push(palette.rule('╭' + '─'.repeat(W - 2) + '╮'));
  lines.push(row(brand(info.version) + (modePill ? '  ' + modePill : '')));
  lines.push(row(''));
  lines.push(row(field('model', info.model, palette.faint('/model'))));
  if (info.provider) lines.push(row(field('provider', info.provider)));
  lines.push(row(field('cwd', shortenPath(info.cwd, Math.max(10, inner - labelW - (branch ? 16 : 0))), branch)));
  lines.push(palette.rule('╰' + '─'.repeat(W - 2) + '╯'));

  if (info.tip) {
    const room = W - 7;
    lines.push(` ${palette.accent('tip')}  ${palette.muted(truncateWidth(info.tip, room))}`);
  }
  return lines;
}

// ───────────────────────── prompt frame ─────────────────────────

export interface FrameInput {
  cols: number;
  mode: UiMode;
  model?: string;
  cwd?: string;
  branch?: string;
  dirty?: boolean;
  contextPercent: number;
  cost: number;
  costState?: CostState;
  input: string;
  /** UTF-16 index of the cursor inside `input`. */
  cursorPos: number;
  placeholder: string;
  /** Rows shown under the frame instead of the key hints (command / file suggestions). */
  below?: string[];
}

export interface Frame {
  lines: string[];
  /** Row (0 = top border) on which the cursor rests. */
  cursorRow: number;
  /** Column (0-based) of the cursor on that row. */
  cursorCol: number;
}

interface InputWindow {
  text: string;
  leftEllipsis: boolean;
  rightEllipsis: boolean;
  cursorCells: number;
}

/** The part of the input that fits into `cells`, scrolled so that the cursor stays visible. */
export function visibleWindow(input: string, cursorPos: number, cells: number): InputWindow {
  const cps = Array.from(input);
  const widths = cps.map((c) => codePointWidth(c.codePointAt(0)!));

  let cursorCp = 0;
  let units = 0;
  while (cursorCp < cps.length && units < cursorPos) {
    units += cps[cursorCp].length;
    cursorCp++;
  }
  const cellsBefore = (end: number, from = 0) => widths.slice(from, end).reduce((a, b) => a + b, 0);

  const total = cellsBefore(cps.length);
  if (total <= cells) {
    return { text: input, leftEllipsis: false, rightEllipsis: false, cursorCells: cellsBefore(cursorCp) };
  }

  const build = (start: number) => {
    const left = start > 0 ? 1 : 0;
    const avail = cells - left;
    let end = start;
    let used = 0;
    while (end < cps.length && used + widths[end] <= avail) {
      used += widths[end];
      end++;
    }
    let right = 0;
    if (end < cps.length) {
      right = 1;
      while (end > start && used + 1 > avail) {
        end--;
        used -= widths[end];
      }
    }
    return { start, end, left, right, used };
  };

  // Start with the cursor roughly in the middle of the window.
  const half = Math.floor(cells / 2);
  let start = cursorCp;
  let w = 0;
  while (start > 0 && w + widths[start - 1] <= half) {
    start--;
    w += widths[start];
  }
  let win = build(start);

  // Do not leave empty space at the end when there is text hidden on the left.
  while (win.end >= cps.length && win.start > 0) {
    const trial = build(win.start - 1);
    if (trial.end < cps.length) break;
    win = trial;
  }
  // The cursor must be inside the window.
  let guard = 0;
  while (cursorCp > win.end && guard++ < cps.length) win = build(win.start + 1);

  return {
    text: cps.slice(win.start, win.end).join(''),
    leftEllipsis: win.left === 1,
    rightEllipsis: win.right === 1,
    cursorCells: cellsBefore(Math.min(cursorCp, win.end), win.start),
  };
}

function keyHints(cols: number, mode: UiMode): string {
  const next = mode === 'plan' ? 'agent' : 'plan';
  const all: Array<[string, string]> = [
    ['tab', `${next} mode`],
    ['/', 'commands'],
    ['@', 'files'],
    ['ctrl+c', 'stop'],
  ];
  const compact: Array<[string, string]> = [['tab', 'mode'], ['/', 'cmds'], ['@', 'files']];
  const render = (items: Array<[string, string]>) =>
    items.map(([k, label]) => palette.text(k) + ' ' + palette.faint(label)).join(palette.faint(' · '));

  for (const items of [all, all.slice(0, 3), compact, compact.slice(0, 2)]) {
    const text = render(items);
    if (displayWidth(text) <= cols - 3) return text;
  }
  return '';
}

/**
 * `deepseek-v4.1-flash-abliterated · think max` in `max` cells: a long model name is cut in the
 * middle of the name, never the part after it (the reasoning level), which is what changes.
 */
export function fitModelLabel(label: string, max: number): string {
  if (displayWidth(label) <= max) return label;
  const sep = ' · ';
  const at = label.lastIndexOf(sep);
  if (at > 0) {
    const name = label.slice(0, at);
    const tail = label.slice(at);
    const room = max - displayWidth(tail);
    if (room >= 6) return truncateWidth(name, room) + tail;
  }
  return truncateWidth(label, max);
}

export function buildPromptFrame(input: FrameInput): Frame {
  // Control characters would move the cursor and tear the frame apart. They are shown as
  // spaces; one character stays one character, so cursor positions keep matching.
  const f: FrameInput = { ...input, input: input.input.replace(/[\x00-\x1f\x7f]/g, ' ') };
  if (isCompact(f.cols)) return buildCompactFrame(f);
  const W = frameWidth(f.cols);
  const style = modeStyle(f.mode);
  const rule = palette.rule;

  // ── top border: ╭─ ● agent ───────── model ─╮
  const pill = ` ${style.color(palette.bold(`${style.glyph} ${style.label}`))} `;
  const pillW = displayWidth(pill);
  const topFree = W - 4 - pillW;
  let topRight = '';
  if (f.model && topFree >= 18) {
    topRight = ' ' + palette.muted(fitModelLabel(f.model, Math.min(48, topFree - 4))) + ' ';
  }
  const top = rule('╭─') + pill + rule('─'.repeat(Math.max(0, topFree - displayWidth(topRight)))) + topRight + rule('─╮');

  // ── input row: │ ❯ text │
  const contentCells = W - 6;
  const win = visibleWindow(f.input, f.cursorPos, contentCells);
  const content = f.input
    ? (win.leftEllipsis ? palette.faint('…') : '') +
      formatBadges(win.text) +
      (win.rightEllipsis ? palette.faint('…') : '')
    : palette.muted(truncateWidth(f.placeholder, contentCells));
  const inputRow =
    rule('│') + ' ' + palette.accent('❯') + ' ' + padEndWidth(content, contentCells) + ' ' + rule('│');
  const cursorCol = 4 + (f.input ? (win.leftEllipsis ? 1 : 0) + win.cursorCells : 0);

  // ── bottom border: ╰─ ~/project git:main* ──── ━━━━──── 12% · $0.0123 ─╯
  const bottomFree = W - 4;
  const pct = Math.max(0, Math.min(100, Math.round(f.contextPercent)));
  const stat = `${pct}% · ${formatCost(f.cost, f.costState)}`;
  const meterCells = W >= 60 ? 8 : W >= 44 ? 6 : 0;
  const withMeter = meterCells > 0 && bottomFree >= displayWidth(stat) + meterCells + 8;
  const bottomRight =
    withMeter
      ? ' ' + palette.faint('ctx') + ' ' + renderMeter(pct, meterCells) + ' ' + palette.muted(stat) + ' '
      : bottomFree >= displayWidth(stat) + 4
        ? ' ' + palette.muted(stat) + ' '
        : '';
  const leftRoom = bottomFree - displayWidth(bottomRight) - 2;
  let bottomLeft = '';
  if (f.cwd && leftRoom >= 10) {
    const branch = f.branch ? `${palette.faint('git:')}${palette.muted(f.branch)}${f.dirty ? palette.warn('*') : ''}` : '';
    const branchW = f.branch ? 4 + displayWidth(f.branch) + (f.dirty ? 1 : 0) : 0;
    const showBranch = branch && leftRoom - branchW >= 12;
    const pathRoom = leftRoom - (showBranch ? branchW : 0);
    bottomLeft = ' ' + palette.muted(shortenPath(f.cwd, pathRoom)) + (showBranch ? ' ' + branch : '') + ' ';
  }
  const bottom =
    rule('╰─') +
    bottomLeft +
    rule('─'.repeat(Math.max(0, bottomFree - displayWidth(bottomLeft) - displayWidth(bottomRight)))) +
    bottomRight +
    rule('─╯');

  // ── footer: key hints, or the suggestion list while one is open
  const footer = f.below && f.below.length > 0 ? f.below.map((l) => '  ' + l) : [hintsRow(f.cols, f.mode)];

  return { lines: [top, inputRow, bottom, ...footer], cursorRow: 1, cursorCol };
}

/**
 * Phone layout: one row, no borders, no hints.
 *   ● ❯ your text…                          12%
 * The coloured glyph tells the mode (● agent, ◆ plan, ▲ yolo), the percentage is the
 * context usage and is hidden when it would squeeze the input.
 */
function buildCompactFrame(f: FrameInput): Frame {
  const W = frameWidth(f.cols);
  const style = modeStyle(f.mode);
  const prefix = style.color(style.glyph) + ' ' + palette.accent('❯') + ' ';
  const pct = Math.max(0, Math.min(100, Math.round(f.contextPercent)));
  const statText = `${pct}%`;
  const statColor = pct >= 90 ? palette.err : pct >= 75 ? palette.warn : palette.faint;
  const statWidth = displayWidth(statText) + 1;
  const withStat = W - 4 - statWidth >= 14;
  const cells = W - 4 - (withStat ? statWidth : 0);

  const win = visibleWindow(f.input, f.cursorPos, cells);
  const content = f.input
    ? (win.leftEllipsis ? palette.faint('…') : '') +
      formatBadges(win.text) +
      (win.rightEllipsis ? palette.faint('…') : '')
    : palette.muted(truncateWidth(f.placeholder, cells));
  const row = prefix + padEndWidth(content, cells) + (withStat ? ' ' + statColor(statText) : '');
  const below = (f.below ?? []).map((l) => '  ' + l);

  return {
    lines: [row, ...below],
    cursorRow: 0,
    cursorCol: 4 + (f.input ? (win.leftEllipsis ? 1 : 0) + win.cursorCells : 0),
  };
}

function hintsRow(cols: number, mode: UiMode): string {
  const hints = keyHints(cols, mode);
  return hints ? ' ' + hints : '';
}

// ───────────────────────── conversation ─────────────────────────

/** The user's message as it stays in the scrollback: a shaded block with a `❯` marker. */
export function renderUserEcho(text: string, cols: number): string[] {
  const W = frameWidth(cols);
  const cw = W - 4;
  const rows = wrapWidth(text.trimEnd(), cw);
  const shaded = rows.map((line, i) => {
    const marker = i === 0 ? palette.accent('❯') : ' ';
    return palette.shade(' ' + marker + ' ' + padEndWidth(palette.text(line), cw) + ' ');
  });
  // On a phone every row counts: no blank lines around the message.
  return isCompact(cols) ? shaded : ['', ...shaded, ''];
}

export type NoticeKind = 'info' | 'ok' | 'warn' | 'error';

export function renderNotice(kind: NoticeKind, message: string, cols: number = process.stdout.columns || 80): string {
  const glyph = { info: palette.faint('·'), ok: palette.ok('✓'), warn: palette.warn('!'), error: palette.err('✗') }[kind];
  const color = { info: palette.muted, ok: palette.text, warn: palette.warn, error: palette.err }[kind];
  // A long message continues under its text instead of starting again at the left edge.
  const lines = wrapWords(message.replace(/\s+/g, ' ').trim(), Math.max(10, cols - 6));
  return lines.map((line, i) => (i === 0 ? `  ${glyph} ${color(line)}` : `    ${color(line)}`)).join('\n');
}

export function parseAction(actionDesc: string): { verb: string; target: string } {
  const cleaned = actionDesc.replace(/^→\s*/, '').replace(/\.\.\.$/, '').trim();
  const m = cleaned.match(/^([A-Za-z]+):?\s*([\s\S]*)$/);
  if (!m) return { verb: cleaned, target: '' };
  return { verb: m[1], target: m[2] };
}

/**
 * `  ◆ Read  src/core/loop.ts`
 *
 * A long command is wrapped to two rows at most; a multi-line script shows its first line
 * and `(+N lines)`. The full text is always visible in the permission card.
 */
export function renderToolLine(actionDesc: string, cols: number, expanded = false): string {
  const { verb, target } = parseAction(actionDesc);
  const head = `  ${palette.accent('◆')} ${palette.bold(verb)}`;
  if (!target.trim()) return head;

  // Expanded (ctrl+o): the whole command, every line of a script, wrapped under its first line.
  if (expanded) {
    const pad = ' '.repeat(4 + displayWidth(verb) + 2);
    const room = Math.max(10, cols - 1 - displayWidth(pad));
    const lines = target.replace(/\t/g, '  ').split('\n').map((l) => l.replace(/\s+$/, '')).flatMap((l) => (l === '' ? [''] : wrapWidth(l, room)));
    while (lines.length > 1 && lines[lines.length - 1] === '') lines.pop();
    const MAX = 60;
    const shown = lines.slice(0, MAX);
    const out = shown.map((l, i) => (i === 0 ? head + '  ' : pad) + palette.text(l));
    if (lines.length > MAX) out.push(pad + palette.faint(`… +${lines.length - MAX} lines`));
    return out.join('\n');
  }

  const rawLines = target.split('\n').map((l) => l.trim()).filter(Boolean);
  const first = (rawLines[0] ?? '').replace(/\s+/g, ' ');
  const extra = rawLines.length - 1;
  const suffixPlain = extra > 0 ? ` (+${extra} line${extra === 1 ? '' : 's'})` : '';

  const prefixWidth = 4 + displayWidth(verb) + 2;
  const room = Math.max(10, cols - 1 - prefixWidth);
  // One row: the command as a single line. ctrl+o shows all of it.
  const maxRows = 1;
  const wrapped = wrapWidth(first, room);
  let rows = wrapped.slice(0, maxRows);
  const cut = wrapped.length > maxRows;
  const last = rows.length - 1;
  const lastRoom = room - displayWidth(suffixPlain);
  if (cut || displayWidth(rows[last]) > lastRoom) rows[last] = truncateWidth(rows[last] + (cut ? '…' : ''), Math.max(4, lastRoom));

  const body = rows.map((r) => palette.text(r));
  body[last] += palette.faint(suffixPlain);
  return head + '  ' + body[0] + body.slice(1).map((r) => '\n' + ' '.repeat(prefixWidth) + r).join('');
}

/** `    ╰ text` under a tool line. */
export function renderResultLine(text: string, kind: 'muted' | 'ok' | 'error' = 'muted', cols = 80): string {
  const color = kind === 'error' ? palette.err : kind === 'ok' ? palette.ok : palette.muted;
  return `    ${palette.faint('╰')} ${color(truncateWidth(text, Math.max(8, cols - 8)))}`;
}

export interface PlanTask {
  text: string;
  status: 'pending' | 'in_progress' | 'completed';
}

/**
 * The plan under an `◆ Update Plan & Tasks` line: a closed rounded box in the colours of the
 * theme (same rule colour as the prompt frame), indented like the `╰` result lines.
 *
 *     ╭─ Plan & Tasks ──────────── 1/3 ─╮
 *     │ [✓] read the project             │
 *     │ [•] write the scene              │
 *     │ [ ] check it in a browser        │
 *     ╰──────────────────────────────────╯
 *
 * Long steps wrap under their own text. No blank rows around it: the space between blocks
 * of the chat is made by whoever prints the next block.
 */
export function renderTaskCard(tasks: PlanTask[], cols: number): string[] {
  if (!tasks || tasks.length === 0) return [];
  const indent = '    ';
  const W = Math.max(24, Math.min(cols - 1 - indent.length, 72));
  const inner = W - 4;
  const rule = palette.rule;

  const done = tasks.filter((t) => t.status === 'completed').length;
  const title = ` ${palette.bold(palette.accent('Plan & Tasks'))} `;
  const count = ` ${palette.muted(`${done}/${tasks.length}`)} `;
  const fill = Math.max(1, W - 4 - displayWidth(title) - displayWidth(count));
  const top = rule('╭─') + title + rule('─'.repeat(fill)) + count + rule('─╮');

  const row = (content: string) => rule('│') + ' ' + padEndWidth(content, inner) + ' ' + rule('│');
  const rows: string[] = [];
  for (const task of tasks) {
    const marker =
      task.status === 'completed'
        ? palette.ok('[✓]')
        : task.status === 'in_progress'
          ? palette.accent(palette.bold('[•]'))
          : palette.faint('[ ]');
    const paint =
      task.status === 'completed'
        ? palette.faint
        : task.status === 'in_progress'
          ? (t: string) => palette.bold(palette.text(t))
          : palette.text;
    const lines = wrapWords(String(task.text ?? '').replace(/\s+/g, ' ').trim() || '…', Math.max(8, inner - 4));
    lines.forEach((line, i) => rows.push(row((i === 0 ? marker : '   ') + ' ' + paint(line))));
  }
  const bottom = rule('╰' + '─'.repeat(W - 2) + '╯');
  return [top, ...rows, bottom].map((l) => indent + l);
}

/** Command output as printable rows: no colour codes, tabs or carriage returns, no blank edges. */
function outputRows(text: string): string[] {
  const rows = stripAnsi(text)
    .replace(/\r/g, '')
    .replace(/\t/g, '  ')
    .split('\n')
    .map((l) => l.replace(/\s+$/, ''));
  while (rows.length && rows[0] === '') rows.shift();
  while (rows.length && rows[rows.length - 1] === '') rows.pop();
  return rows;
}

/** A result that may be long (an explanation, a hint): wrapped under its first line, never cut off. */
export function renderResultBlock(text: string, kind: 'muted' | 'ok' | 'error' = 'muted', cols = 80): string {
  const color = kind === 'error' ? palette.err : kind === 'ok' ? palette.ok : palette.muted;
  const lines = wrapWords(text.replace(/\s+/g, ' ').trim(), Math.max(10, cols - 8));
  return lines.map((line, i) => (i === 0 ? `    ${palette.faint('╰')} ${color(line)}` : `      ${color(line)}`)).join('\n');
}

function countLines(text: string): number {
  return text.length === 0 ? 0 : text.replace(/\n$/, '').split('\n').length;
}

/**
 * Turns a tool result into the quiet one-liner shown in the transcript (the model still
 * gets the full result). Returns an empty array when there is nothing worth showing.
 */
/** `      … +12 lines (ctrl+o to expand)` under a folded result. */
function moreHint(n: number): string {
  return '      ' + palette.faint(`${t('view.moreLines', { n })} (${t('view.expandOutput')})`);
}

/**
 * The plan the model finished, as a card under its `plan_ready` call:
 *
 *     ╭─ Plan ───────────────────────────────╮
 *     │ Fix the score counter                 │
 *     │                                       │
 *     │ Files  src/score.ts, src/ui.ts        │
 *     │ 1. read the counter                   │
 *     ╰───────────────────────────────────────╯
 *
 * A small plan is a few rows; a big one grows with its steps (long ones wrap).
 */
export function renderPlanCard(plan: { summary: string; files?: string[]; steps?: string[] }, cols: number): string[] {
  const indent = '    ';
  const W = Math.max(24, Math.min(cols - 1 - indent.length, 84));
  const inner = W - 4;
  const rule = palette.rule;
  const row = (content: string) => rule('│') + ' ' + padEndWidth(content, inner) + ' ' + rule('│');
  const title = ` ${palette.bold(palette.accent(t('plan.cardTitle')))} `;
  const top = rule('╭─') + title + rule('─'.repeat(Math.max(1, W - 4 - displayWidth(title)))) + rule('─╮');

  const rows: string[] = [];
  for (const line of wrapText(plan.summary.replace(/\s+/g, ' ').trim() || '…', inner)) rows.push(row(palette.bold(palette.text(line))));

  const files = (plan.files ?? []).filter(Boolean);
  if (files.length > 0) {
    rows.push(row(''));
    const shown = files.slice(0, 8);
    const label = palette.faint(t('plan.files'));
    wrapText(shown.join(', ') + (files.length > shown.length ? ` +${files.length - shown.length}` : ''), Math.max(10, inner - displayWidth(t('plan.files')) - 2)).forEach((line, i) => {
      rows.push(row((i === 0 ? label + '  ' : ' '.repeat(displayWidth(t('plan.files')) + 2)) + palette.muted(line)));
    });
  }

  const steps = (plan.steps ?? []).filter(Boolean);
  if (steps.length > 0) {
    rows.push(row(''));
    const numW = String(steps.length).length + 2;
    steps.forEach((step, i) => {
      wrapText(step.replace(/\s+/g, ' ').trim(), Math.max(10, inner - numW)).forEach((line, j) => {
        rows.push(row((j === 0 ? palette.accent(`${i + 1}.`.padEnd(numW)) : ' '.repeat(numW)) + palette.text(line)));
      });
    });
  }
  return [top, ...rows, rule('╰' + '─'.repeat(W - 2) + '╯')].map((l) => indent + l);
}

/** Rows of raw output for the expanded view (ctrl+o): all of it, up to a generous cap. */
function fullRows(text: string, cols: number, cap = 300): string[] {
  const room = Math.max(8, cols - 8);
  const rows = outputRows(text).flatMap((l) => (l === '' ? [''] : wrapWidth(l, room)));
  const shown = rows.slice(0, cap).map((l) => '      ' + palette.muted(l));
  if (rows.length > cap) shown.push('      ' + palette.faint(`… +${rows.length - cap} lines`));
  return shown;
}

/**
 * The result of a tool under its `◆` line. Collapsed (the default) it is one quiet row: the first
 * line of the output and how many more there are. Expanded (ctrl+o) it is all of the output.
 */
export function summarizeToolResult(name: string, result: string, cols = 80, expanded = false): string[] {
  const t = name.toLowerCase();
  const text = (result || '').trim();
  if (!text) return [];

  // Failures reported by the agent loop itself, whatever the tool.
  if (/^(Error executing tool:|Error: (?:Tool '|The tool arguments|Tool arguments)|Action denied by user)/.test(text)) {
    return [renderResultLine(text.split('\n')[0], 'error', cols)];
  }

  if (t === 'bash' || t === 'run_command' || t === 'exec') {
    const m = text.match(/\n?\[Exit code: (\d+|null)\]\s*$/);
    const stopped = !m ? text.match(/\n?(\[(?:Operation cancelled|Process killed)[^\]]*\])\s*$/) : null;
    const body = text.replace(/\n?\[(?:Exit code: [^\]]*|Operation cancelled[^\]]*|Process killed[^\]]*)\]\s*$/, '');
    const rows = outputRows(body);
    const room = Math.max(8, cols - 8);

    if (stopped) return [renderResultLine(stopped[1].replace(/^\[|\]$/g, ''), 'error', cols)];
    const code = m ? m[1] : '?';

    if (!expanded) {
      const nonEmpty = rows.filter((l) => l.trim());
      if (code === '0') {
        if (nonEmpty.length === 0) return [renderResultLine('(no output)', 'muted', cols)];
        const rest = nonEmpty.length - 1;
        const row = `    ${palette.faint('╰')} ${palette.muted(truncateWidth(nonEmpty[0].trim(), Math.max(8, cols - 8)))}`;
        return rest > 0 ? [row, moreHint(rest)] : [row];
      }
      const interesting = nonEmpty.find((l) => /error|fail|cannot|can't|not found|no such|denied|exception|✗|×/i.test(l) && !/^\s+at /.test(l)) ?? nonEmpty[nonEmpty.length - 1];
      const head = `    ${palette.faint('╰')} ${palette.err(`exit ${code}`)}`;
      const row = interesting ? head + palette.faint(' · ') + palette.muted(truncateWidth(interesting.trim(), Math.max(6, cols - 8 - displayWidth(`exit ${code} · `)))) : head;
      return nonEmpty.length > 1 ? [row, moreHint(nonEmpty.length - 1)] : [row];
    }

    if (expanded) {
      const head = code === '0' ? (rows.length === 0 ? [renderResultLine('(no output)', 'muted', cols)] : []) : [renderResultLine(`exit ${code}`, 'error', cols)];
      return [...head, ...(rows.length ? fullRows(body, cols) : [])];
    }
  }

  if (expanded && t !== 'bash' && !(t === 'edit_file' || t === 'write_file')) {
    // For these the first line is part of the output, not a summary of it.
    const own = t.startsWith('mcp__') || t === 'web_search' || t === 'fetch_url';
    const first = own ? [] : summarizeToolResult(name, result, cols, false);
    return [...first, ...fullRows(text, cols)];
  }

  if (t === 'read_file' || t === 'view_file' || t === 'read') {
    if (text.startsWith('[Binary file')) return [renderResultLine('binary file', 'muted', cols)];
    const header = text.match(/^\[Lines (\d+)-(\d+) of (\d+)/);
    if (header) return [renderResultLine(`lines ${header[1]}–${header[2]} of ${header[3]}`, 'muted', cols)];
    const n = countLines(text);
    return [renderResultLine(`${n} line${n === 1 ? '' : 's'}`, 'muted', cols)];
  }

  if (t === 'list_dir' || t === 'ls') {
    const n = countLines(text);
    return [renderResultLine(`${n} entr${n === 1 ? 'y' : 'ies'}`, 'muted', cols)];
  }

  if (t === 'search' || t === 'grep_search') {
    if (/^No matches/i.test(text)) return [renderResultLine('no matches', 'muted', cols)];
    const n = countLines(text);
    return [renderResultLine(`${n} match${n === 1 ? '' : 'es'}`, 'muted', cols)];
  }

  if (t === 'diagnose_code') {
    return [text.startsWith('Diagnostics passed') ? renderResultLine('no errors', 'ok', cols) : renderResultLine('errors found', 'error', cols)];
  }

  if (t === 'mcp_manage') {
    try {
      const servers: Array<{ name: string; status: string; tools?: number }> = JSON.parse(text).servers ?? [];
      if (servers.length > 0) return [renderResultLine(servers.map((s) => `${s.name} ${s.status}${s.status === 'connected' ? ` (${s.tools ?? 0} tools)` : ''}`).join(', '), servers.some((s) => s.status === 'failed') ? 'error' : 'ok', cols)];
    } catch {}
  }

  // Output of an MCP tool or a web fetch: the first line and how many more there are.
  if (t.startsWith('mcp__') || t === 'web_search' || t === 'fetch_url') {
    const nonEmpty = outputRows(text).filter((l) => l.trim());
    if (nonEmpty.length === 0) return [renderResultLine('(no output)', 'muted', cols)];
    const rest = nonEmpty.length - 1;
    const row = `    ${palette.faint('╰')} ${palette.muted(truncateWidth(nonEmpty[0].trim(), Math.max(8, cols - 8)))}`;
    return rest > 0 ? [row, moreHint(rest)] : [row];
  }

  return [];
}

export interface TurnSummary {
  ms: number;
  tools: number;
  outputTokens: number;
  /** Input tokens of all requests of the turn (what the model read). */
  inputTokens?: number;
  cost: number;
  costState?: CostState;
  stopped?: boolean;
}

export function renderTurnSummary(s: TurnSummary): string {
  const parts = [formatDuration(s.ms)];
  if (s.tools > 0) parts.push(t(s.tools === 1 ? 'summary.toolOne' : 'summary.toolMany', { n: s.tools }));
  if ((s.inputTokens ?? 0) > 0) parts.push(t('summary.in', { n: formatTokens(s.inputTokens!) }));
  if (s.outputTokens > 0) parts.push(t('summary.out', { n: formatTokens(s.outputTokens) }));
  if (s.cost > 0 || (s.costState?.unpriced ?? 0) > 0) parts.push(formatCost(s.cost, s.costState));
  const glyph = s.stopped ? palette.warn('■') : palette.ok('✓');
  return `  ${glyph} ${palette.faint((s.stopped ? t('summary.stopped') + ' · ' : '') + parts.join(' · '))}`;
}

/**
 * Prints text with a gutter: the first line starts with `firstPrefix`, every following
 * line (including lines created by wrapping) with `restPrefix`.
 *
 * It wraps at word boundaries itself. Without that, the terminal would wrap a streamed
 * line at column 0 and the text would lose its indentation.
 */
export class GutterWriter {
  private atLineStart = true;
  private isFirst = true;
  private col = 0;
  private pending = '';
  // Indentation of the current line (plus the width of a leading list marker): wrapped
  // lines continue under the text, not under the marker.
  private lead = 0;
  private hang = 0;
  private sawWord = false;
  // Spaces between words are held back until the next word is known to fit on the line,
  // so wrapped lines never end with a dangling space.
  private spaceBuf = '';

  constructor(
    private firstPrefix: string,
    private restPrefix: string,
    private sink: (s: string) => void = (s) => void process.stdout.write(s),
    private maxCols: () => number = () => Math.min((process.stdout.columns || 80) - 1, 100)
  ) {}

  write(text: string): void {
    if (!text) return;
    // A message starts at its first visible character: leading blank lines are dropped.
    if (this.isFirst && this.atLineStart && this.pending === '') text = text.replace(/^\n+/, '');
    if (!text) return;

    const parts = (this.pending + text).split(/(\n| +)/);
    this.pending = '';
    // A trailing fragment without whitespace may be half a word (streamed in pieces):
    // keep it until we know where the word ends.
    const last = parts[parts.length - 1];
    if (last !== '' && !/^(\n| +)$/.test(last)) this.pending = parts.pop() as string;

    let out = '';
    for (const part of parts) {
      if (part === '') continue;
      if (part === '\n') {
        out += '\n';
        this.atLineStart = true;
        this.col = 0;
        this.lead = 0;
        this.hang = 0;
        this.sawWord = false;
        this.spaceBuf = '';
        continue;
      }
      const width = displayWidth(part);
      const isSpace = part[0] === ' ';
      const prefixWidth = displayWidth(this.isFirst ? this.firstPrefix : this.restPrefix);

      // Spaces inside a line wait for the word that follows them.
      if (isSpace && !this.atLineStart && this.sawWord) {
        this.spaceBuf += part;
        continue;
      }
      const gap = this.spaceBuf.length;
      if (!this.atLineStart && this.col + gap + width > this.maxCols()) {
        if (isSpace) continue;
        out += '\n' + this.restPrefix + ' '.repeat(this.hang);
        this.col = displayWidth(this.restPrefix) + this.hang;
        this.spaceBuf = '';
      } else if (this.spaceBuf) {
        out += this.spaceBuf;
        this.col += gap;
        this.spaceBuf = '';
      }
      if (this.atLineStart) {
        out += this.isFirst ? this.firstPrefix : this.restPrefix;
        this.col = prefixWidth;
        this.isFirst = false;
        this.atLineStart = false;
      }
      out += part;
      this.col += width;

      if (isSpace && !this.sawWord) {
        this.lead += width;
        this.hang = this.lead;
      } else if (!isSpace && !this.sawWord) {
        this.sawWord = true;
        this.hang = this.lead + (/^(?:[•*-]|\d+[.)])$/.test(stripAnsi(part)) ? width + 1 : 0);
      }
    }
    if (out) this.sink(out);
  }

  /** Writes the buffered partial word (call when a stream ends without a trailing newline). */
  flush(): void {
    if (this.pending) {
      const rest = this.pending;
      this.pending = '';
      this.write(rest + ' ');
      this.spaceBuf = '';
    }
  }

  /** The next printed line gets the first prefix again (a new assistant message). */
  reset(): void {
    this.pending = '';
    this.spaceBuf = '';
    this.isFirst = true;
    this.atLineStart = true;
    this.col = 0;
    this.lead = 0;
    this.hang = 0;
    this.sawWord = false;
  }
}

// ───────────────────────── permission prompt ─────────────────────────

export interface PermissionRequest {
  /** What is asking, e.g. `bash`, `write_file`, `mcp · github / create_issue`. */
  title: string;
  /** Red instead of amber: destructive command or a credentials file. */
  danger?: boolean;
  /** The thing to approve, line by line: a command, a path, arguments. */
  lines: string[];
  /** Short warnings under the card. */
  notes?: string[];
}

const MAX_CARD_LINES = 10;

/** The approval card: what will run, in a frame coloured by how risky it is. */
export function renderPermissionCard(req: PermissionRequest, cols: number): string[] {
  const color = req.danger ? palette.err : palette.warn;
  const label = req.danger ? '✖ dangerous' : '▲ permission';
  const W = frameWidth(cols);

  const all = req.lines.flatMap((l) => l.replace(/\r/g, '').replace(/\t/g, '  ').split('\n'));
  while (all.length && all[all.length - 1].trim() === '') all.pop();
  const shown = all.slice(0, MAX_CARD_LINES);
  const hidden = all.length - shown.length;
  // Every row must stay on one terminal line, otherwise erasing the card later would miss rows.
  const notes = (req.notes ?? []).map((n) => `  ${palette.warn('!')} ${palette.warn(truncateWidth(n, Math.max(8, W - 5)))}`);

  if (isCompact(cols)) {
    const rows = [` ${color(palette.bold(label))} ${palette.faint('·')} ${palette.text(truncateWidth(req.title, Math.max(6, W - displayWidth(label) - 5)))}`];
    for (const l of shown) rows.push('  ' + palette.text(truncateWidth(l, W - 3)));
    if (hidden > 0) rows.push('  ' + palette.faint(`… +${hidden} more line${hidden === 1 ? '' : 's'}`));
    return [...rows, ...notes];
  }

  const inner = W - 4;
  const titleSeg = ` ${color(palette.bold(label))} ${palette.faint('·')} ${palette.text(truncateWidth(req.title, Math.max(6, inner - displayWidth(label) - 6)))} `;
  const top = color('╭─') + titleSeg + color('─'.repeat(Math.max(0, W - 4 - displayWidth(titleSeg)))) + color('─╮');
  const row = (content: string) => color('│') + ' ' + padEndWidth(content, inner) + ' ' + color('│');
  const rows = shown.map((l) => row(palette.text(truncateWidth(l, inner))));
  if (hidden > 0) rows.push(row(palette.faint(`… +${hidden} more line${hidden === 1 ? '' : 's'}`)));
  if (rows.length === 0) rows.push(row(''));
  return [top, ...rows, color('╰' + '─'.repeat(W - 2) + '╯'), ...notes];
}

export type PermissionAnswer = 'allow' | 'session' | 'always' | 'deny';

/** Which extra answers the card offers besides Allow and Deny (only for requests that are safe to remember). */
export interface PermissionOffer {
  session?: boolean;
  always?: boolean;
}

/** `❯ ● Allow   ○ Deny   y/n · ←→ · enter` */
export function renderPermissionChoice(selected: PermissionAnswer, cols: number, offer: PermissionOffer = {}): string {
  if (offer.session || offer.always) {
    const item = (id: PermissionAnswer, label: string, color: (s: string) => string) =>
      selected === id ? `${color('●')} ${palette.bold(color(label))}` : palette.faint(`○ ${label}`);
    const parts = [item('allow', 'Allow', palette.ok)];
    if (offer.session) parts.push(item('session', 'Session', palette.accent));
    if (offer.always) parts.push(item('always', 'Always', palette.accent));
    parts.push(item('deny', 'Deny', palette.err));
    const keys = ['y', offer.session ? 's' : '', offer.always ? 'a' : '', 'n'].filter(Boolean).join('/');
    const hintText = isCompact(cols) ? '' : '   ' + palette.faint(`${keys} · ←→ · enter`);
    return ` ${palette.accent('❯')} ${parts.join('   ')}${hintText}`;
  }
  const allow = selected === 'allow' ? `${palette.ok('●')} ${palette.bold(palette.ok('Allow'))}` : palette.faint('○ Allow');
  const deny = selected === 'deny' ? `${palette.err('●')} ${palette.bold(palette.err('Deny'))}` : palette.faint('○ Deny');
  const hint = isCompact(cols) ? '' : '   ' + palette.faint('y/n · ←→ · enter');
  return ` ${palette.accent('❯')} ${allow}   ${deny}${hint}`;
}

// ───────────────────────── spinner ─────────────────────────

export interface SpinnerOut {
  write(chunk: string): unknown;
  isTTY?: boolean;
  columns?: number;
}

const FRAMES = ['⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏'];

/**
 * One step of an ease-out animation: the value covers a fixed share of the remaining distance
 * per unit of time, so it starts fast and slows down as it nears the target (exponential
 * smoothing, `tauMs` is the time it takes to cover about 63% of the distance). It never
 * overshoots, works the same for any frame rate, and lands exactly on the target.
 */
export function easeToward(current: number, target: number, dtMs: number, tauMs = 400): number {
  const distance = target - current;
  if (Math.abs(distance) < 0.5) return target;
  if (dtMs <= 0) return current;
  return current + distance * (1 - Math.exp(-dtMs / tauMs));
}

export interface StatusLineInput {
  label: string;
  elapsedMs: number;
  /** Extra detail inside the brackets, e.g. `↓ 364 tokens`. */
  meta?: string;
  /** Animation frame number. */
  frame: number;
  columns: number;
}

/**
 * The working line:  `  ⠹ Read src/x.ts  (12s · ↓ 364 tokens · ctrl+c to stop)`.
 * On a narrow screen the details are dropped step by step, the label is cut last.
 */
export function renderStatusLine({ label: rawLabel, elapsedMs, meta = '', frame, columns }: StatusLineInput): string {
  const elapsed = formatDuration(Math.max(0, elapsedMs));
  const glyph = palette.accent(FRAMES[frame % FRAMES.length]);
  // One row, always: a multi-line command (a heredoc) must not tear the frame under it apart.
  const label = stripAnsi(rawLabel).replace(/^→\s*/, '').replace(/[\x00-\x1f\x7f\s]+/g, ' ').trim();
  // Longest detail text that still leaves the label some room; the label is cut first of all last.
  const options = [
    [elapsed, meta, t('status.stop')],
    [elapsed, meta],
    [elapsed, t('status.stop')],
    [elapsed, '^C'],
    [elapsed],
  ].map((parts) => palette.faint(`(${parts.filter(Boolean).join(' · ')})`));
  const budget = columns - 1 - 4; // two leading spaces, glyph and a space
  const GAP = 2;
  // The label has a field of its own (a long command is cut at FIELD cells).
  const FIELD = 28;
  const suffix = options.find((o) => budget - GAP - displayWidth(o) >= Math.min(12, displayWidth(label))) ?? options[options.length - 1];
  const room = Math.max(1, Math.min(FIELD, budget - GAP - displayWidth(suffix)));
  const cut = truncateWidth(label, room);
  // The details sit close to the label, a little further the longer it is: in steps of 8 cells, so they do not shake.
  const field = Math.min(room, Math.max(12, Math.ceil((displayWidth(cut) + 1) / 8) * 8));
  const text = padEndWidth(cut, field);
  return `  ${glyph} ${palette.text(text)}${' '.repeat(GAP)}${suffix}`;
}

/**
 * Animated "working" line:  `  ⠹ Read src/x.ts  (12s · 1.2k tok · ctrl+c to stop)`.
 * Same start / message / stop API as the clack spinner it replaces.
 */
let activeSpinner: UiSpinner | null = null;

/**
 * Silences the running spinner while something else needs the terminal (an approval
 * prompt). Returns a function that brings it back.
 */
export function pauseSpinners(): () => void {
  const resumers = pauseHooks.map((hook) => hook());
  const spinner = activeSpinner;
  if (spinner && spinner.isActive) {
    spinner.suspend();
    resumers.push(() => spinner.resume());
  }
  return () => resumers.forEach((resume) => resume());
}

const pauseHooks: Array<() => () => void> = [];

/**
 * Registers something else that must step aside while an approval prompt owns the terminal
 * (the typing line below the chat, for example). The hook returns the function that brings it back.
 */
export function registerPauseHook(hook: () => () => void): () => void {
  pauseHooks.push(hook);
  return () => {
    const i = pauseHooks.indexOf(hook);
    if (i >= 0) pauseHooks.splice(i, 1);
  };
}

export class UiSpinner {
  private suspended = false;
  private timer: ReturnType<typeof setInterval> | null = null;
  private label = '';
  private meta = '';
  private metaProvider: (() => string) | null = null;
  private startedAt = 0;
  private frame = 0;
  private muted = false;

  constructor(
    private out: SpinnerOut = process.stdout,
    private now: () => number = Date.now,
    private intervalMs = 80
  ) {}

  get isActive(): boolean {
    return this.timer !== null || this.suspended;
  }

  /** The working line is drawn elsewhere (by the typing frame): this spinner draws nothing. */
  mute(on = true): void {
    if (on && this.timer) this.stop();
    this.muted = on;
  }

  start(label = 'Working…'): void {
    this.label = label;
    this.meta = '';
    this.startedAt = this.now();
    this.frame = 0;
    if (!this.out.isTTY || this.muted) return;
    if (this.timer) clearInterval(this.timer);
    this.suspended = false;
    activeSpinner = this;
    this.out.write('\x1b[?25l');
    this.draw();
    this.timer = setInterval(() => this.draw(), this.intervalMs);
    this.timer.unref?.();
  }

  /** Stops drawing and erases the line, but remembers the state (see pauseSpinners). */
  suspend(): void {
    if (!this.timer) return;
    clearInterval(this.timer);
    this.timer = null;
    this.suspended = true;
    this.out.write('\r\x1b[2K\x1b[?25h');
  }

  resume(): void {
    if (!this.suspended || this.muted) return;
    this.suspended = false;
    this.out.write('\x1b[?25l');
    this.draw();
    this.timer = setInterval(() => this.draw(), this.intervalMs);
    this.timer.unref?.();
  }

  message(label: string): void {
    this.label = label;
    if (this.timer) this.draw();
  }

  /** Extra detail shown inside the brackets, e.g. `1.2k tok`. */
  setMeta(meta: string): void {
    this.meta = meta;
  }

  /** Live detail, asked again on every frame (it survives start() and stop()). */
  setMetaProvider(provider: (() => string) | null): void {
    this.metaProvider = provider;
  }

  stop(): void {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
      this.out.write('\r\x1b[2K\x1b[?25h');
    } else if (this.suspended) {
      this.suspended = false;
      this.out.write('\x1b[?25h');
    }
    if (activeSpinner === this) activeSpinner = null;
  }

  /** The text of the current frame (used by the animation and by tests). */
  renderLine(columns = this.out.columns || 80): string {
    return renderStatusLine({
      label: this.label,
      elapsedMs: this.now() - this.startedAt,
      meta: this.metaProvider?.() || this.meta,
      frame: this.frame,
      columns,
    });
  }

  private draw(): void {
    this.out.write('\r\x1b[2K' + this.renderLine());
    this.frame++;
  }
}
