import { frame } from './finder.js';
import { LiveBlock, BlockOutput } from './liveblock.js';
import { runPicker } from './picker.js';
import { palette, renderNotice } from './ui.js';
import { displayWidth, padEndWidth, truncateWidth } from './width.js';
import { getLanguage, t } from '../core/i18n.js';
import {
  CommandRunner,
  InstallMethod,
  StepId,
  UpdateCheckResult,
  UpdateError,
  buildUpdateSteps,
  checkForUpdates,
  detectInstallMethod,
  getCurrentVersion,
  restartDevx,
  runUpdateSteps,
} from './updater.js';

/**
 * The update screens, in the style of the rest of devx:
 *
 *   checking   one line, the text with a bright band sweeping across it
 *   card       what is new (from the notes of the release), how it will be installed
 *   choice     Update now / Later / Skip this version
 *   progress   the steps with a spinner and the time of each, and a bar that fills as they go
 *   result     installed (the bar drains while devx restarts) or what failed and how to do it by hand
 *
 * Every screen is a pure function of its state and the animation tick, so it can be tested without
 * a terminal; `presentUpdate` only adds the timer, the keyboard and the install.
 */

export const SPINNER_FRAMES = ['⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏'];
const TICK_MS = 80;

// ───────────────────────── animation pieces ─────────────────────────

/** The text with a bright band moving across it; `tick` is the frame of the animation. */
export function shimmer(text: string, tick: number): string {
  const chars = Array.from(text);
  const position = ((tick * 1.2) % (chars.length + 10)) - 5;
  return chars
    .map((ch, i) => {
      if (ch === ' ') return ch;
      const d = Math.abs(i - position);
      if (d < 1.2) return palette.bold(palette.text(ch));
      if (d < 3.5) return palette.accent(ch);
      return palette.muted(ch);
    })
    .join('');
}

export type BarTone = 'busy' | 'done' | 'failed';

/** A bar of exactly `width` cells. While busy a bright spot runs along the filled part. */
export function renderBar(width: number, progress: number, tick: number, tone: BarTone = 'busy'): string {
  const w = Math.max(4, width);
  const filled = Math.max(0, Math.min(w, Math.round(progress * w)));
  const color = tone === 'done' ? palette.ok : tone === 'failed' ? palette.err : palette.accent;
  const spot = tone === 'busy' && filled > 0 ? ((tick * 1.5) % (filled + 6)) - 3 : -99;
  let out = '';
  for (let i = 0; i < w; i++) {
    if (i < filled) out += Math.abs(i - spot) < 1.5 ? palette.bold(palette.text('━')) : color('━');
    else if (i === filled && tone === 'busy') out += color('╸');
    else out += palette.rule('─');
  }
  return out;
}

/** How long each step weighs in the bar: the install is nearly all of the wait. */
const WEIGHT: Record<StepId, number> = { registry: 0.1, install: 0.75, verify: 0.15, pull: 0.15, build: 0.75, link: 0.1 };

/**
 * How full the bar is. The real progress of an install is not known, so the step that is running
 * fills asymptotically: it keeps moving, and it never reaches the end before the step is done.
 */
export function estimateProgress(ids: StepId[], doneCount: number, runningMs: number, expectedMs = 15000): number {
  const total = ids.reduce((sum, id) => sum + WEIGHT[id], 0);
  if (doneCount >= ids.length) return 1;
  const done = ids.slice(0, doneCount).reduce((sum, id) => sum + WEIGHT[id], 0);
  const running = WEIGHT[ids[doneCount]] * (1 - Math.exp(-runningMs / expectedMs)) * 0.92;
  return Math.min(0.99, (done + running) / total);
}

const seconds = (ms: number): string => `${(ms / 1000).toFixed(1)}s`;

// ───────────────────────── what is new ─────────────────────────

const SECTION_NAMES: Record<string, RegExp> = {
  en: /english/i, ru: /русск/i, uk: /україн/i, de: /deutsch/i, es: /español/i, fr: /français/i, pt: /português/i, zh: /中文/, ja: /日本語/,
};

function cleanMarkdown(text: string): string {
  return text
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/\*\*([^*]+)\*\*/g, '$1')
    .replace(/[`*_]/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * The highlights of a release: the top-level bullets under its first `###` heading, in the language
 * of the interface when the notes have a section for it (`## Русский`), else the first section.
 */
export function extractHighlights(body: string | undefined, language: string, max = 4): string[] {
  if (!body) return [];
  const lines = body.replace(/\r/g, '').split('\n');
  const sections: Array<{ name: string; lines: string[] }> = [];
  for (const line of lines) {
    const heading = /^##\s+(.+?)\s*$/.exec(line);
    if (heading) sections.push({ name: heading[1], lines: [] });
    else sections[sections.length - 1]?.lines.push(line);
  }
  const pool = sections.length > 0 ? sections : [{ name: '', lines }];
  const own = SECTION_NAMES[language];
  const chosen = (own && pool.find((s) => own.test(s.name))) || pool.find((s) => SECTION_NAMES.en.test(s.name)) || pool[0];

  const out: string[] = [];
  let seenHeading = false;
  for (const line of chosen.lines) {
    if (/^###\s/.test(line)) {
      // The bullets of the first block only ("Highlights"): the next heading ends them.
      if (seenHeading && out.length > 0) break;
      seenHeading = true;
      continue;
    }
    const bullet = /^-\s+(.+)$/.exec(line);
    if (bullet) {
      const text = cleanMarkdown(bullet[1]);
      if (text) out.push(text);
      if (out.length >= max) break;
    }
  }
  return out;
}

/** `Your own providers. A name, an address…` -> the short head and the rest. */
function splitHighlight(text: string): { head: string; rest: string } {
  const at = text.search(/[.:] /);
  if (at > 0 && at <= 50) return { head: text.slice(0, at + 1), rest: text.slice(at + 2) };
  return { head: text, rest: '' };
}

// ───────────────────────── screens (pure) ─────────────────────────

export interface CardInfo {
  current: string;
  latest: string;
  publishedAt?: string;
  highlights: string[];
  method: InstallMethod;
}

const versions = (current: string, latest: string) => `v${current} → v${latest}`;

/** The line while the check runs. */
export function renderChecking(cols: number, tick: number, current: string): string {
  const limit = Math.max(10, cols - 1);
  const spin = palette.accent(SPINNER_FRAMES[tick % SPINNER_FRAMES.length]);
  // Two cells of indent, the spinner and a space come first; the text is cut to what is left.
  const text = truncateWidth(t('update.checking'), Math.max(4, limit - 4));
  const label = shimmer(text, tick);
  const withTag = `  ${spin} ${label}  ${palette.faint(`v${current}`)}`;
  return displayWidth(withTag) <= limit ? withTag : `  ${spin} ${label}`;
}

/** The card that says a new version is out and what is in it. */
export function renderUpdateCard(info: CardInfo, cols: number): string[] {
  const how = info.method === 'npm' ? t('update.viaNpm') : t('update.viaGit');
  return frame(
    t('update.title'),
    versions(info.current, info.latest),
    cols,
    (iw, box) => {
      const lines: string[] = [];
      lines.push(box(' ' + palette.bold(palette.accent(truncateWidth(t('update.available', { version: info.latest }), iw - 2)))));
      const date = info.publishedAt ? info.publishedAt.slice(0, 10) : '';
      const meta = [t('update.have', { version: info.current }), date ? t('update.released', { date }) : ''].filter(Boolean).join(' · ');
      lines.push(box(' ' + palette.muted(truncateWidth(meta, iw - 2))));
      if (info.highlights.length > 0) {
        lines.push(box(''));
        lines.push(box(' ' + palette.faint(truncateWidth(t('update.whatsNew').toUpperCase(), iw - 2))));
        for (const text of info.highlights) {
          const { head, rest } = splitHighlight(text);
          const room = iw - 4;
          const headText = truncateWidth(head, room);
          const restRoom = room - displayWidth(headText) - 1;
          const tailText = rest && restRoom >= 8 ? ' ' + palette.muted(truncateWidth(rest, restRoom)) : '';
          lines.push(box(' ' + palette.accent('●') + ' ' + palette.bold(palette.text(headText)) + tailText));
        }
      }
      lines.push(box(''));
      return lines;
    },
    palette.faint(truncateWidth(how, Math.max(10, cols - 3)))
  );
}

export type StepStatus = 'wait' | 'run' | 'ok' | 'fail';

export interface ProgressState {
  current: string;
  latest: string;
  steps: Array<{ label: string; status: StepStatus; ms?: number }>;
  progress: number;
  tone: BarTone;
  /** After the install: the line under the bar and how much of the restart is left (1 → 0). */
  restarting?: boolean;
}

/** The steps with a spinner on the one that runs, and the bar. */
export function renderProgressCard(state: ProgressState, cols: number, tick: number): string[] {
  const done = state.tone === 'done';
  return frame(
    done ? t('update.titleDone') : t('update.titleBusy'),
    versions(state.current, state.latest),
    cols,
    (iw, box) => {
      const lines: string[] = [];
      for (const step of state.steps) {
        const icon =
          step.status === 'ok' ? palette.ok('✓')
          : step.status === 'run' ? palette.accent(SPINNER_FRAMES[tick % SPINNER_FRAMES.length])
          : step.status === 'fail' ? palette.err('✗')
          : palette.faint('○');
        const time = step.ms !== undefined ? palette.faint(seconds(step.ms)) : '';
        const room = Math.max(6, iw - 4 - displayWidth(time) - 1);
        const text = truncateWidth(step.label, room);
        const label =
          step.status === 'run' ? palette.bold(palette.text(text))
          : step.status === 'wait' ? palette.faint(text)
          : step.status === 'fail' ? palette.err(text)
          : palette.muted(text);
        const left = ' ' + icon + ' ' + label;
        lines.push(box(left + ' '.repeat(Math.max(1, iw - displayWidth(left) - displayWidth(time) - 1)) + time));
      }
      lines.push(box(''));
      lines.push(box(' ' + renderBar(iw - 2, state.progress, tick, state.tone)));
      if (state.restarting) lines.push(box(' ' + palette.muted(truncateWidth(t('update.restarting'), iw - 2))));
      lines.push(box(''));
      return lines;
    },
    palette.faint(truncateWidth(done ? t('update.installed', { version: state.latest }) : t('update.keepOpen'), Math.max(10, cols - 3)))
  );
}

export interface FailureInfo {
  current: string;
  latest: string;
  step: string;
  message: string;
  notPublished: boolean;
  method: InstallMethod;
}

/** The command that does the update by hand. */
export const manualCommand = (method: InstallMethod): string => (method === 'npm' ? 'npm install -g termux-dev@latest' : 'git pull && npm install && npm run build');

/** What failed, and how to do it by hand. devx goes on with the version it has. */
export function renderFailureCard(info: FailureInfo, cols: number): string[] {
  return frame(
    t('update.titleFailed'),
    versions(info.current, info.latest),
    cols,
    (iw, box) => {
      const lines: string[] = [];
      lines.push(box(' ' + palette.err('✗') + ' ' + palette.err(truncateWidth(t('update.failedStep', { step: info.step }), iw - 4))));
      const detail = info.notPublished
        ? [t('update.notPublished', { version: info.latest })]
        : info.message.split('\n').map((l) => l.trim()).filter(Boolean).slice(-4);
      for (const line of detail) lines.push(box('   ' + palette.muted(truncateWidth(line, iw - 4))));
      lines.push(box(''));
      lines.push(box(' ' + palette.text(truncateWidth(t('update.keepsRunning', { version: info.current }), iw - 2))));
      lines.push(box(''));
      lines.push(box(' ' + palette.faint(truncateWidth(t('update.byHand'), iw - 2))));
      lines.push(box('   ' + palette.bold(palette.text(truncateWidth(manualCommand(info.method), iw - 4)))));
      lines.push(box(''));
      return lines;
    },
    ''
  );
}

// ───────────────────────── the flow ─────────────────────────

export interface ScreenOutput extends BlockOutput {
  isTTY?: boolean;
}

export interface UpdateFlowDeps {
  output?: ScreenOutput;
  picker?: typeof runPicker;
  run?: CommandRunner;
  restart?: () => void;
  now?: () => number;
  /** Where devx is installed from; found out when missing. */
  install?: { method: InstallMethod; root: string };
  /** For tests: the time one frame of the animation takes. */
  tickMs?: number;
  /** For tests: how long the "restarting" bar drains. */
  restartMs?: number;
}

export type UpdateResult = 'updated' | 'later' | 'skipped' | 'failed';

const columnsOf = (out: ScreenOutput) => out.columns || process.stdout.columns || 80;
const write = (out: ScreenOutput, lines: string[]) => out.write(lines.join('\n') + '\n');
const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

/**
 * Shows the card for a new version, asks what to do and, when told to, installs it on a live
 * screen and restarts devx. `offerSkip` adds "Skip this version" (the start-up check, not /update);
 * `save` is told which version was skipped.
 */
export async function presentUpdate(
  info: UpdateCheckResult,
  opts: { offerSkip: boolean; save?: (version: string) => Promise<unknown> },
  deps: UpdateFlowDeps = {}
): Promise<UpdateResult> {
  const out = deps.output ?? process.stdout;
  const now = deps.now ?? Date.now;
  const install = deps.install ?? (await detectInstallMethod());
  const cols = columnsOf(out);

  write(out, [
    '',
    ...renderUpdateCard(
      { current: info.currentVersion, latest: info.latestVersion, publishedAt: info.publishedAt, highlights: extractHighlights(info.releaseNotes, getLanguage()), method: install.method },
      cols
    ),
    '',
  ]);
  // Nobody to ask (a pipe): the card is all there is.
  if (!out.isTTY) return 'later';

  const picker = deps.picker ?? runPicker;
  const labels = [t('update.now'), t('update.later'), t('update.skip')];
  const choice = await picker<'now' | 'later' | 'skip'>({
    title: t('update.title'),
    subtitle: versions(info.currentVersion, info.latestVersion),
    items: [
      { value: 'now', label: t('update.now'), detail: t('update.nowDetail', { version: info.latestVersion }) },
      { value: 'later', label: t('update.later'), detail: t('update.laterDetail') },
      ...(opts.offerSkip ? [{ value: 'skip' as const, label: t('update.skip'), detail: t('update.skipDetail', { version: info.latestVersion }) }] : []),
    ],
    current: 'now',
    labelWidth: Math.max(...labels.slice(0, opts.offerSkip ? 3 : 2).map((l) => displayWidth(l))) + 2,
  });

  if (choice === 'skip') {
    await opts.save?.(info.latestVersion);
    out.write(renderNotice('info', t('update.skipped', { version: info.latestVersion }), cols) + '\n\n');
    return 'skipped';
  }
  if (choice !== 'now') return 'later';

  // ── the install, on a screen that is redrawn in place
  const steps = buildUpdateSteps(install.method, install.root, info.latestVersion, deps.run);
  const ids = steps.map((step) => step.id);
  const state: ProgressState = {
    current: info.currentVersion,
    latest: info.latestVersion,
    steps: ids.map((id, i) => ({ label: t(`update.step.${id}` as const, { version: info.latestVersion }), status: i === 0 ? 'run' : 'wait' })),
    progress: 0,
    tone: 'busy',
  };

  const block = new LiveBlock(out);
  let tick = 0;
  let runningSince = now();
  let restartStart = 0;
  const restartMs = deps.restartMs ?? 1500;
  const draw = () => {
    if (state.restarting) state.progress = Math.max(0, 1 - (now() - restartStart) / restartMs);
    else {
      const doneCount = state.steps.filter((s) => s.status === 'ok').length;
      state.progress = state.tone === 'failed' ? state.progress : estimateProgress(ids, doneCount, now() - runningSince);
    }
    block.draw(renderProgressCard(state, columnsOf(out), tick++));
  };
  draw();
  const timer = setInterval(draw, deps.tickMs ?? TICK_MS);
  timer.unref?.();
  const stopWatching = block.watchResize(draw);

  const outcome = await runUpdateSteps(
    steps,
    {
      onStart: (i) => {
        state.steps[i].status = 'run';
        runningSince = now();
      },
      onDone: (i, ms) => {
        state.steps[i].status = 'ok';
        state.steps[i].ms = ms;
        if (state.steps[i + 1]) state.steps[i + 1].status = 'run';
      },
    },
    now
  );

  if (!outcome.ok) {
    clearInterval(timer);
    stopWatching();
    state.steps[outcome.index].status = 'fail';
    state.tone = 'failed';
    block.erase();
    write(out, renderFailureCard(
      { current: info.currentVersion, latest: info.latestVersion, step: state.steps[outcome.index].label, message: outcome.error.message, notPublished: outcome.error.kind === 'not-published', method: install.method },
      columnsOf(out)
    ));
    out.write('\x1b[?25h\n');
    return 'failed';
  }

  // Installed: the bar turns green and drains while devx restarts.
  state.tone = 'done';
  state.restarting = true;
  restartStart = now();
  await sleep(restartMs);
  clearInterval(timer);
  stopWatching();
  state.progress = 0;
  block.draw(renderProgressCard(state, columnsOf(out), tick));
  block.release();
  (deps.restart ?? restartDevx)();
  return 'updated';
}

// ───────────────────────── the check ─────────────────────────

/** Runs `work` while the "checking" line plays, and takes the line away when it is done. */
async function withCheckingLine<T>(out: ScreenOutput, current: string, work: () => Promise<T>, tickMs = TICK_MS): Promise<T> {
  if (!out.isTTY) return work();
  const block = new LiveBlock(out);
  let tick = 0;
  const draw = () => block.draw([renderChecking(columnsOf(out), tick++, current)]);
  draw();
  const timer = setInterval(draw, tickMs);
  timer.unref?.();
  try {
    return await work();
  } finally {
    clearInterval(timer);
    block.erase();
  }
}

export interface CheckDeps extends UpdateFlowDeps {
  /** Writes the config (the version that was skipped is kept in it). */
  save?: (config: any) => Promise<unknown>;
  check?: (timeoutMs: number) => Promise<UpdateCheckResult>;
}

/** At start-up: look for a new version; say so only when there is one (and it was not skipped). */
export async function runStartupUpdateCheck(config: any, deps: CheckDeps = {}): Promise<void> {
  if (config.checkUpdates === false) return;
  const out = deps.output ?? process.stdout;
  if (!out.isTTY) return;
  const current = await getCurrentVersion();
  const result = await withCheckingLine(out, current, () => (deps.check ?? checkForUpdates)(6000));

  if (result.updateAvailable) {
    if (config.skippedUpdate === result.latestVersion) return;
    await presentUpdate(
      result,
      {
        offerSkip: true,
        save: async (version) => {
          config.skippedUpdate = version;
          await deps.save?.(config);
        },
      },
      deps
    );
    return;
  }
  if (result.error) return;   // offline at start-up is not worth a word
  // Up to date: a short line that goes away by itself.
  out.write(`  ${palette.ok('✓')} ${palette.muted(t('update.upToDate', { version: result.currentVersion }))}\n`);
  await sleep(deps.tickMs !== undefined ? 0 : 450);
  out.write('\x1b[1A\x1b[2K');
}

/** `/update`: the same, but it always answers (also "up to date" and "could not check"). */
export async function runManualUpdate(deps: CheckDeps = {}): Promise<void> {
  const out = deps.output ?? process.stdout;
  const current = await getCurrentVersion();
  const result = await withCheckingLine(out, current, () => (deps.check ?? checkForUpdates)(10000));
  const cols = columnsOf(out);
  if (result.updateAvailable) {
    await presentUpdate(result, { offerSkip: false }, deps);
  } else if (result.error) {
    out.write(renderNotice('warn', t('update.offline', { reason: result.error }), cols) + '\n');
  } else {
    out.write(renderNotice('ok', t('update.upToDate', { version: result.currentVersion }), cols) + '\n');
  }
}

export { UpdateError };
