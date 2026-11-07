import { BackgroundInfo, BackgroundManager, formatDuration } from '../core/background.js';
import { palette } from './ui.js';
import { displayWidth, padEndWidth, truncateWidth } from './width.js';

/**
 * What the prompt frame shows: how many tasks run, and how many ended badly without the model
 * having been told yet. The failure disappears from the frame once the model has the news.
 */
export function backgroundSummary(manager: BackgroundManager): { running: number; failed: number } {
  return {
    running: manager.running().length,
    failed: manager.unreportedFailures(),
  };
}

const STATE_LABEL: Record<BackgroundInfo['status'], string> = {
  running: 'running',
  exited: 'done',
  failed: 'failed',
  stopped: 'stopped',
};

function stateCell(info: BackgroundInfo): string {
  const label = info.status === 'failed' && info.code !== null ? `failed ${info.code}` : STATE_LABEL[info.status];
  const padded = label.padEnd(9);
  if (info.status === 'running') return palette.ok('● ' + padded);
  if (info.status === 'failed') return palette.err('✗ ' + padded);
  return palette.faint('○ ' + padded);
}

/** The list of tasks for `/tasks`: one row each, the newest output line under a running one. */
export function renderTaskList(manager: BackgroundManager, cols: number, now = Date.now()): string[] {
  const tasks = manager.list();
  if (tasks.length === 0) {
    return [`  ${palette.faint('·')} ${palette.muted('No background tasks. The agent starts them for servers, watchers and long runs.')}`];
  }
  const rows: string[] = [];
  const running = tasks.filter((t) => t.status === 'running').length;
  rows.push(`  ${palette.bold('Background tasks')} ${palette.faint(`· ${running} running, ${tasks.length - running} ended`)}`);
  for (const t of tasks) {
    const age = formatDuration((t.endedAt ?? now) - t.startedAt);
    const head = `  ${palette.accent(padEndWidth(t.id, 4))} ${stateCell(t)} ${palette.faint(padEndWidth(age, 7))} `;
    rows.push(head + palette.text(truncateWidth(t.label, Math.max(8, cols - displayWidth(head) - 1))));
    const last = lastLine(manager, t.id);
    if (last) rows.push(`  ${' '.repeat(4)} ${palette.faint('╰')} ${palette.muted(truncateWidth(last, Math.max(8, cols - 14)))}`);
  }
  rows.push('');
  rows.push(`  ${palette.faint(truncateWidth('/tasks <id> output · /tasks stop <id|all> · /tasks clear', Math.max(8, cols - 4)))}`);
  return rows;
}

function lastLine(manager: BackgroundManager, id: string): string {
  try {
    const { text } = manager.read(id, { mode: 'tail', lines: 3 });
    const lines = text.split('\n').map((l) => l.trim()).filter(Boolean);
    return lines[lines.length - 1] ?? '';
  } catch {
    return '';
  }
}

/** The output of one task for `/tasks <id>`. */
export function renderTaskOutput(manager: BackgroundManager, id: string, cols: number, lines = 40): string[] {
  const info = manager.get(id);
  if (!info) return [`  ${palette.err('✗')} ${palette.err(`No task "${id}". Type /tasks to see them.`)}`];
  const { text, omitted } = manager.read(id, { mode: 'tail', lines });
  const rows = [`  ${palette.accent(info.id)} ${stateCell(info)} ${palette.text(truncateWidth(info.label, Math.max(8, cols - 22)))}`];
  rows.push(`  ${palette.faint(truncateWidth(info.command, Math.max(8, cols - 4)))}`);
  if (omitted > 0) rows.push(`  ${palette.faint(`… ${omitted} earlier line${omitted === 1 ? '' : 's'}`)}`);
  if (!text) rows.push(`  ${palette.faint('(no output yet)')}`);
  for (const line of text.split('\n')) if (text) rows.push('  ' + palette.muted(truncateWidth(line, Math.max(8, cols - 4))));
  return rows;
}

/** The notes the model received, short: one row per note (the first line of each). */
export function noteRows(content: string): string[] {
  const body = content.replace(/^\[devx · background tasks\]\n?/, '');
  return body
    .split(/\n\n(?=bg\d+ )/)
    .map((note) => note.split('\n')[0].trim())
    .filter(Boolean);
}

/** `/tasks ...`: returns what to print. Stopping is asynchronous, so this is too. */
export async function runTasksCommand(args: string[], manager: BackgroundManager, cols: number): Promise<string[]> {
  const [first, second] = args;
  const word = (first ?? '').toLowerCase();

  if (!first || word === 'list' || word === 'ls') {
    return renderTaskList(manager, cols);
  }
  if (word === 'stop' || word === 'kill') {
    if (!second) return [`  ${palette.warn('!')} ${palette.warn('Usage: /tasks stop <id|all>')}`];
    if (second === 'all') {
      const n = await manager.stopAll();
      return [`  ${palette.ok('✓')} ${palette.text(n === 0 ? 'Nothing was running' : `Stopped ${n} task${n === 1 ? '' : 's'}`)}`];
    }
    try {
      const info = await manager.stop(second);
      return [`  ${palette.ok('✓')} ${palette.text(`${info.id} ${STATE_LABEL[info.status]}`)}`];
    } catch (err: any) {
      return [`  ${palette.err('✗')} ${palette.err(err.message)}`];
    }
  }
  if (word === 'clear') {
    const n = manager.clearFinished();
    return [`  ${palette.ok('✓')} ${palette.text(n === 0 ? 'No ended tasks to remove' : `Removed ${n} ended task${n === 1 ? '' : 's'}`)}`];
  }
  // `/tasks bg1`, `/tasks output bg1`, `/tasks logs bg1`
  const id = ['output', 'logs', 'log', 'show'].includes(word) ? second : first;
  if (!id) return [`  ${palette.warn('!')} ${palette.warn('Usage: /tasks output <id>')}`];
  return renderTaskOutput(manager, id, cols);
}
