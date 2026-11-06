import { spawn } from 'child_process';

/**
 * Hooks: commands the user runs around the tools of the agent, set in `hooks` of ~/.devxrc.json:
 *
 *   "hooks": {
 *     "PreToolUse":  [{ "matcher": "bash",              "command": "./check-command.sh" }],
 *     "PostToolUse": [{ "matcher": "write_file|edit_file", "command": "npx prettier --write ." }]
 *   }
 *
 * `matcher` is a regular expression for the name of the tool (missing or `*`: every tool). The
 * command gets the call as JSON on its stdin (`event`, `tool_name`, `tool_input`, and for a
 * PostToolUse also `tool_result`) and in the environment (DEVX_HOOK_EVENT, DEVX_TOOL_NAME).
 *
 *  - PreToolUse, exit code 2: the tool does not run; what the command wrote to stderr goes to the
 *    model as the result. Any other failure is only a warning.
 *  - PostToolUse, exit code 2: what the command wrote to stderr is added to the result the model sees.
 *
 * Hooks of a project (`.devx.json`) count only in a workspace the user trusted.
 */
export interface HookEntry {
  matcher?: string;
  command: string;
  /** Seconds; 10 by default. */
  timeout?: number;
}

export interface HooksConfig {
  PreToolUse?: HookEntry[];
  PostToolUse?: HookEntry[];
}

export type HookEvent = keyof HooksConfig;

export interface HookRun {
  code: number | null;
  stdout: string;
  stderr: string;
  timedOut: boolean;
}

export type HookRunner = (entry: HookEntry, payload: Record<string, unknown>, env: Record<string, string>) => Promise<HookRun>;

const DEFAULT_TIMEOUT_S = 10;
const MAX_OUTPUT = 4000;

/** The entries of an event that apply to a tool. A matcher that is not a valid expression matches nothing. */
export function matchingHooks(entries: HookEntry[] | undefined, toolName: string): HookEntry[] {
  if (!Array.isArray(entries)) return [];
  return entries.filter((e) => {
    if (!e || typeof e.command !== 'string' || !e.command.trim()) return false;
    if (!e.matcher || e.matcher === '*') return true;
    try {
      return new RegExp(`^(?:${e.matcher})$`).test(toolName);
    } catch {
      return false;
    }
  });
}

export const runHookProcess: HookRunner = (entry, payload, env) =>
  new Promise((resolve) => {
    const isWindows = process.platform === 'win32';
    const child = spawn(isWindows ? 'cmd' : 'sh', isWindows ? ['/c', entry.command] : ['-c', entry.command], {
      env: { ...process.env, ...env },
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    let stdout = '';
    let stderr = '';
    let timedOut = false;
    let settled = false;
    const finish = (code: number | null) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve({ code, stdout: stdout.slice(0, MAX_OUTPUT), stderr: stderr.slice(0, MAX_OUTPUT), timedOut });
    };
    const timer = setTimeout(() => {
      timedOut = true;
      try { child.kill('SIGKILL'); } catch {}
      finish(null);
    }, (entry.timeout && entry.timeout > 0 ? entry.timeout : DEFAULT_TIMEOUT_S) * 1000);
    child.stdout.on('data', (d) => { if (stdout.length < MAX_OUTPUT) stdout += d; });
    child.stderr.on('data', (d) => { if (stderr.length < MAX_OUTPUT) stderr += d; });
    child.on('error', () => finish(null));
    child.on('close', (code) => finish(code));
    child.stdin.on('error', () => {});
    child.stdin.end(JSON.stringify(payload));
  });

export interface HookOutcome {
  /** A PreToolUse hook said no: the tool must not run; this is what the model is told. */
  blocked?: string;
  /** What a hook wants the model to know (PostToolUse feedback, a timeout, a failed command). */
  notes: string[];
}

export async function runHooks(
  hooks: HooksConfig | undefined,
  event: HookEvent,
  toolName: string,
  toolInput: unknown,
  toolResult?: string,
  run: HookRunner = runHookProcess
): Promise<HookOutcome> {
  const outcome: HookOutcome = { notes: [] };
  for (const entry of matchingHooks(hooks?.[event], toolName)) {
    const payload: Record<string, unknown> = { event, tool_name: toolName, tool_input: toolInput };
    if (toolResult !== undefined) payload.tool_result = toolResult;
    const result = await run(entry, payload, { DEVX_HOOK_EVENT: event, DEVX_TOOL_NAME: toolName });
    const said = (result.stderr || result.stdout).trim();
    if (result.timedOut) {
      outcome.notes.push(`hook "${entry.command}" timed out`);
    } else if (result.code === 2) {
      if (event === 'PreToolUse') {
        outcome.blocked = said || `blocked by the hook "${entry.command}"`;
        return outcome;
      }
      if (said) outcome.notes.push(said);
    } else if (result.code !== 0) {
      outcome.notes.push(`hook "${entry.command}" failed (exit ${result.code ?? 'none'})${said ? ': ' + said : ''}`);
    }
  }
  return outcome;
}
