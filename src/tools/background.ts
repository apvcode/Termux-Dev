import { Tool } from '../core/types.js';
import { BackgroundInfo, MAX_BACKGROUND_TASKS, formatDuration, getBackgroundManager } from '../core/background.js';

const ACTIONS = ['start', 'list', 'output', 'wait', 'stop'] as const;
type Action = (typeof ACTIONS)[number];

/** One line per task: `bg1 running 2m 03s  npm run dev`. */
export function describeTask(info: BackgroundInfo, now = Date.now()): string {
  const age = formatDuration((info.endedAt ?? now) - info.startedAt);
  const state =
    info.status === 'running' ? 'running'
    : info.status === 'stopped' ? 'stopped'
    : info.status === 'exited' ? 'finished (exit 0)'
    : `failed (exit ${info.code ?? '?'})`;
  const watch = info.watching && info.status === 'running' ? `  [watching /${info.watching}/]` : '';
  return `${info.id}  ${state}  ${age}  ${info.label}${watch}`;
}

function outputBlock(text: string, omitted: number): string {
  if (!text) return '(no output)';
  return (omitted > 0 ? `[... ${omitted} earlier line${omitted === 1 ? '' : 's'} left out ...]\n` : '') + text;
}

export const backgroundTool: Tool = {
  name: 'background',
  definition: {
    name: 'background',
    description:
      'Run a long-lived command in the background and keep working: dev servers, file watchers, long builds or test runs. ' +
      'Do NOT use it for commands that finish quickly (use bash). Actions: ' +
      '"start" (command, optional label, notify_on, max_runtime_seconds) launches it and returns its id and first output; ' +
      '"list" shows all tasks; ' +
      '"output" (id) reads what the task printed since the last read (mode "tail" gives the last lines instead; "grep" filters lines); ' +
      '"wait" (id, optional pattern, timeout_seconds) blocks until the task ends, a line matches the regex, or the time is up; ' +
      '"stop" (id) ends the task. ' +
      'Set "notify_on" (a regex such as "ready|listening|error|FAIL") to be told when such a line appears, and you are always told when a task ends: ' +
      'then you do not need to poll. Never poll in a loop; wait with a pattern instead. ' +
      `At most ${MAX_BACKGROUND_TASKS} tasks run at once, and all of them are stopped when devx exits.`,
    parameters: {
      type: 'object',
      properties: {
        action: { type: 'string', enum: [...ACTIONS], description: 'What to do' },
        command: { type: 'string', description: 'start: the shell command' },
        label: { type: 'string', description: 'start: a short name shown to the user, e.g. "dev server"' },
        notify_on: { type: 'string', description: 'start: regex; a matching output line is reported to you' },
        max_runtime_seconds: { type: 'number', description: 'start: stop the task after this many seconds (default: no limit)' },
        id: { type: 'string', description: 'output / wait / stop: the task id (bg1, bg2, ...)' },
        mode: { type: 'string', enum: ['new', 'tail'], description: 'output: "new" (default) = since the last read, "tail" = last lines' },
        lines: { type: 'number', description: 'output: how many lines at most (default 200 for new, 40 for tail)' },
        grep: { type: 'string', description: 'output: only lines that match this regex' },
        pattern: { type: 'string', description: 'wait: regex of an output line to wait for (default: wait for the end)' },
        timeout_seconds: { type: 'number', description: 'wait: give up after this long (default 60, max 600)' },
      },
      required: ['action'],
    },
  },
  validateArgs(args: any) {
    if (!args || !ACTIONS.includes(args.action)) throw new Error(`action must be one of: ${ACTIONS.join(', ')}`);
    if (args.action === 'start' && (!args.command || typeof args.command !== 'string')) throw new Error('command is required for "start"');
    if (['output', 'wait', 'stop'].includes(args.action) && !args.id) throw new Error(`id is required for "${args.action}" (see action "list")`);
  },
  async execute(args, _config, context) {
    const manager = getBackgroundManager();
    const action = args.action as Action;
    try {
      if (action === 'start') {
        const info = manager.start(args.command, {
          label: args.label,
          notifyOn: args.notify_on,
          maxRuntimeSeconds: Number(args.max_runtime_seconds) || 0,
        });
        // Give it a moment: a command that dies at once (wrong script, busy port) should be seen now.
        const first = await manager.wait(info.id, { timeoutSeconds: 1.5, signal: context?.signal });
        const now = manager.get(info.id)!;
        const out = manager.read(info.id, { mode: 'new', lines: 30 });
        const head = now.status === 'running'
          ? `Started ${now.id} (pid ${now.pid ?? '?'}): ${now.label}. It runs in the background.`
          : `${describeTask(now)} - it ended right after the start.`;
        const hint = now.status === 'running'
          ? '\nYou will be told when it ends' + (now.watching ? ' or prints a line matching the pattern' : '') + '. Use "output" to read more, "wait" to wait for a line.'
          : '';
        return `${head}${hint}\n${outputBlock(out.text, out.omitted)}${first.reason === 'aborted' ? '\n[Operation cancelled by user]' : ''}`;
      }

      if (action === 'list') {
        const tasks = manager.list();
        return tasks.length === 0 ? 'No background tasks.' : tasks.map((t) => describeTask(t)).join('\n');
      }

      if (action === 'output') {
        const info = manager.get(String(args.id));
        const out = manager.read(String(args.id), {
          mode: args.mode === 'tail' ? 'tail' : 'new',
          lines: args.lines,
          grep: args.grep,
        });
        return `${info ? describeTask(info) : args.id}\n${outputBlock(out.text, out.omitted)}`;
      }

      if (action === 'wait') {
        const result = await manager.wait(String(args.id), {
          pattern: args.pattern,
          timeoutSeconds: Number(args.timeout_seconds) || 60,
          signal: context?.signal,
        });
        const info = manager.get(String(args.id))!;
        const out = manager.read(String(args.id), { mode: 'new', lines: 60 });
        const why =
          result.reason === 'match' ? `A line matched: ${result.line}`
          : result.reason === 'exit' ? 'The task has ended.'
          : result.reason === 'timeout' ? 'Timed out; the task is still running.'
          : '[Operation cancelled by user]';
        return `${why}\n${describeTask(info)}\n${outputBlock(out.text, out.omitted)}`;
      }

      // stop
      const before = manager.get(String(args.id));
      if (before && before.status !== 'running') return `${describeTask(before)} (already ended)`;
      const info = await manager.stop(String(args.id));
      return `Stopped. ${describeTask(info)}`;
    } catch (err: any) {
      return `Error: ${err.message}`;
    }
  },
};
