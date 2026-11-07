import { spawn, ChildProcess } from 'child_process';
import { EventEmitter } from 'events';
import { StringDecoder } from 'string_decoder';

/**
 * Background tasks: commands that keep running while the conversation goes on (dev servers,
 * watchers, long test runs). The model starts them with the `background` tool, looks at their
 * output later, waits for a line to appear, and gets a note when one finishes.
 */

export type BackgroundStatus = 'running' | 'exited' | 'failed' | 'stopped';

export interface BackgroundInfo {
  id: string;
  label: string;
  command: string;
  cwd: string;
  pid?: number;
  status: BackgroundStatus;
  code: number | null;
  startedAt: number;
  endedAt?: number;
  /** Characters of output so far (the tail is kept, see MAX_BUFFER). */
  outputChars: number;
  watching?: string;
}

export const MAX_BACKGROUND_TASKS = 8;
/** What is kept of the output of one task; older text is dropped. */
export const MAX_BUFFER = 200_000;
/** The most a single read gives back to the model. */
export const MAX_READ_CHARS = 8000;
const MAX_NOTES_PER_TASK = 12;
const NOTE_TAIL_CHARS = 1500;
const MATCH_DEBOUNCE_MS = 400;

export interface StartOptions {
  label?: string;
  cwd?: string;
  /** A regular expression: a line that matches it is reported to the model. */
  notifyOn?: string;
  /** Stop the task after this many seconds (0 / missing: it runs until it ends or is stopped). */
  maxRuntimeSeconds?: number;
}

export interface ReadOptions {
  /** `new` (default): what came since the last read. `tail`: the last lines, whatever was read before. */
  mode?: 'new' | 'tail';
  lines?: number;
  /** Only lines that match this regular expression. */
  grep?: string;
}

export type WaitReason = 'match' | 'exit' | 'timeout' | 'aborted';

export interface WaitResult {
  reason: WaitReason;
  /** The line that matched (reason `match`). */
  line?: string;
}

export interface BackgroundNote {
  taskId: string;
  kind: 'exit' | 'match';
  /** Short, for the screen. */
  summary: string;
  /** For the model. */
  text: string;
}

interface Task {
  info: BackgroundInfo;
  proc: ChildProcess;
  buffer: string;
  /** Characters dropped from the front of `buffer` (so cursors stay valid). */
  trimmed: number;
  readCursor: number;
  scanFrom: number;
  pendingLine: string;
  watch?: RegExp;
  notes: number;
  matchTimer?: ReturnType<typeof setTimeout>;
  matchedLines: string[];
  killTimer?: ReturnType<typeof setTimeout>;
  runtimeTimer?: ReturnType<typeof setTimeout>;
  stopRequested: boolean;
  timedOut: boolean;
  /** Resolves when the process has gone and its pipes are closed. */
  done: Promise<void>;
}

/** Strips colour codes and carriage-return redraws so the model reads text, not terminal noise. */
export function cleanOutput(text: string): string {
  return text
    .replace(/\x1b\[[0-9;?]*[ -/]*[@-~]/g, '')
    .replace(/\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)/g, '')
    .replace(/[^\n]*\r(?!\n)/g, '')
    .replace(/\r/g, '');
}

function compileRegex(source: string | undefined): RegExp | undefined {
  if (!source) return undefined;
  try {
    return new RegExp(source, 'i');
  } catch {
    throw new Error(`Invalid regular expression: ${source}`);
  }
}

export function formatDuration(ms: number): string {
  const s = Math.max(0, Math.round(ms / 1000));
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ${String(s % 60).padStart(2, '0')}s`;
  return `${Math.floor(m / 60)}h ${String(m % 60).padStart(2, '0')}m`;
}

export class BackgroundManager extends EventEmitter {
  private tasks = new Map<string, Task>();
  private counter = 0;
  private notes: BackgroundNote[] = [];

  // ───────────────────────── starting and stopping ─────────────────────────

  start(command: string, options: StartOptions = {}): BackgroundInfo {
    if (!command || !command.trim()) throw new Error('command is required');
    if (this.running().length >= MAX_BACKGROUND_TASKS) {
      throw new Error(`Too many background tasks running (${MAX_BACKGROUND_TASKS}). Stop one first (action "stop").`);
    }
    const watch = compileRegex(options.notifyOn);
    this.installExitHook();

    const isWin = process.platform === 'win32';
    const cwd = options.cwd || process.cwd();
    const proc = spawn(command, { shell: true, cwd, detached: !isWin, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
    const id = `bg${++this.counter}`;
    const info: BackgroundInfo = {
      id,
      label: (options.label || command).replace(/\s+/g, ' ').trim().slice(0, 60),
      command,
      cwd,
      pid: proc.pid,
      status: 'running',
      code: null,
      startedAt: Date.now(),
      outputChars: 0,
      watching: options.notifyOn || undefined,
    };

    let finish!: () => void;
    const done = new Promise<void>((resolve) => { finish = resolve; });
    const task: Task = {
      info, proc, buffer: '', trimmed: 0, readCursor: 0, scanFrom: 0, pendingLine: '', watch,
      notes: 0, matchedLines: [], stopRequested: false, timedOut: false, done,
    };
    this.tasks.set(id, task);

    const decoders = { out: new StringDecoder('utf8'), err: new StringDecoder('utf8') };
    proc.stdout!.on('data', (d: Buffer) => this.append(task, decoders.out.write(d)));
    proc.stderr!.on('data', (d: Buffer) => this.append(task, decoders.err.write(d)));

    let ended = false;
    const end = (status: BackgroundStatus, code: number | null) => {
      if (ended) return;
      ended = true;
      this.append(task, decoders.out.end() + decoders.err.end());
      this.flushPendingLine(task);
      clearTimeout(task.killTimer);
      clearTimeout(task.runtimeTimer);
      info.status = task.stopRequested ? 'stopped' : status;
      info.code = code;
      info.endedAt = Date.now();
      this.flushMatches(task);
      // A task the model stopped itself needs no note; anything else does.
      if (!task.stopRequested || task.timedOut) this.addNote(task, 'exit', this.exitNote(task));
      this.emit('change', this.snapshot(task));
      finish();
    };
    proc.on('error', () => end('failed', null));
    proc.on('close', (code) => end(code === 0 ? 'exited' : 'failed', code));
    // A child that got away can keep the pipes open for good: do not wait for them for long.
    proc.on('exit', (code) => {
      setTimeout(() => {
        proc.stdout?.destroy();
        proc.stderr?.destroy();
        end(code === 0 ? 'exited' : 'failed', code);
      }, 400).unref();
    });

    if (options.maxRuntimeSeconds && options.maxRuntimeSeconds > 0) {
      task.runtimeTimer = setTimeout(() => { task.timedOut = true; void this.stop(id); }, options.maxRuntimeSeconds * 1000);
      task.runtimeTimer.unref();
    }
    this.emit('change', this.snapshot(task));
    return this.snapshot(task);
  }

  /** Stops a task (SIGTERM, then SIGKILL after a second). Resolves when it is gone. */
  async stop(id: string): Promise<BackgroundInfo> {
    const task = this.must(id);
    if (task.info.status !== 'running') return this.snapshot(task);
    task.stopRequested = true;
    this.killTree(task, false);
    task.killTimer = setTimeout(() => this.killTree(task, true), 1000);
    task.killTimer.unref();
    await Promise.race([task.done, new Promise<void>((r) => setTimeout(r, 2500).unref())]);
    return this.snapshot(task);
  }

  async stopAll(): Promise<number> {
    const running = this.running();
    await Promise.all(running.map((t) => this.stop(t.id).catch(() => null)));
    return running.length;
  }

  /** Forgets finished tasks (their output is gone with them). */
  clearFinished(): number {
    let n = 0;
    for (const [id, task] of this.tasks) {
      if (task.info.status !== 'running') { this.tasks.delete(id); n++; }
    }
    if (n) this.emit('change');
    return n;
  }

  // ───────────────────────── looking at tasks ─────────────────────────

  list(): BackgroundInfo[] {
    return [...this.tasks.values()].map((t) => this.snapshot(t));
  }

  running(): BackgroundInfo[] {
    return this.list().filter((t) => t.status === 'running');
  }

  get(id: string): BackgroundInfo | undefined {
    const task = this.tasks.get(id);
    return task ? this.snapshot(task) : undefined;
  }

  /** The output of a task as text for the model or the screen. */
  read(id: string, options: ReadOptions = {}): { text: string; omitted: number } {
    const task = this.must(id);
    const grep = compileRegex(options.grep);
    let from: number;
    if (options.mode === 'tail') from = task.trimmed;
    else from = Math.max(task.readCursor, task.trimmed);
    let text = task.buffer.slice(from - task.trimmed);
    if (options.mode !== 'tail') task.readCursor = task.trimmed + task.buffer.length;

    let lines = cleanOutput(text).split('\n');
    if (lines.length && lines[lines.length - 1] === '') lines.pop();
    if (grep) lines = lines.filter((l) => grep.test(l));
    const want = Math.max(1, Math.min(Math.floor(options.lines ?? (options.mode === 'tail' ? 40 : 200)), 1000));
    let omitted = 0;
    if (lines.length > want) { omitted = lines.length - want; lines = lines.slice(-want); }
    text = lines.join('\n');
    if (text.length > MAX_READ_CHARS) {
      omitted += text.slice(0, text.length - MAX_READ_CHARS).split('\n').length - 1;
      text = text.slice(-MAX_READ_CHARS).replace(/^[^\n]*\n/, '');
    }
    return { text, omitted };
  }

  /**
   * Waits until the task ends, a line matches `pattern`, the time is up or the turn is cancelled.
   * Lines the model has already read do not count; lines it has not read yet do.
   */
  wait(id: string, options: { pattern?: string; timeoutSeconds?: number; signal?: AbortSignal } = {}): Promise<WaitResult> {
    const task = this.must(id);
    const pattern = compileRegex(options.pattern);
    const timeoutMs = Math.min(Math.max(options.timeoutSeconds ?? 60, 1), 600) * 1000;
    return new Promise<WaitResult>((resolve) => {
      let timer: ReturnType<typeof setTimeout>;
      // Output nobody has read yet counts: a line that came while the model was busy elsewhere is not lost.
      let scan = Math.max(task.readCursor, task.trimmed);
      const finish = (result: WaitResult) => {
        clearTimeout(timer);
        options.signal?.removeEventListener('abort', onAbort);
        this.removeListener(`data:${id}`, onData);
        resolve(result);
      };
      const onAbort = () => finish({ reason: 'aborted' });
      const onClose = () => finish({ reason: 'exit' });
      void task.done.then(onClose);
      const onData = () => {
        if (!pattern) return;
        const start = Math.max(scan, task.trimmed);
        const chunk = cleanOutput(task.buffer.slice(start - task.trimmed));
        const lastNl = chunk.lastIndexOf('\n');
        if (lastNl < 0) return;
        scan = start + lastNl + 1;
        const hit = chunk.slice(0, lastNl).split('\n').find((l) => pattern.test(l));
        if (hit !== undefined) finish({ reason: 'match', line: hit.trim() });
      };
      if (task.info.status !== 'running') return resolve({ reason: 'exit' });
      if (options.signal?.aborted) return resolve({ reason: 'aborted' });
      timer = setTimeout(() => finish({ reason: 'timeout' }), timeoutMs);
      options.signal?.addEventListener('abort', onAbort, { once: true });
      this.on(`data:${id}`, onData);
      onData();
    });
  }

  // ───────────────────────── notes for the model ─────────────────────────

  hasNotes(): boolean {
    return this.notes.length > 0;
  }

  /** The notes since the last call, as one message for the model (or '' when there are none). */
  takeNotes(): string {
    if (this.notes.length === 0) return '';
    const text = this.notes.map((n) => n.text).join('\n\n');
    this.notes = [];
    // What the prompt frame shows about unreported news is gone now: let it redraw.
    this.emit('change');
    return `[devx · background tasks]\n${text}`;
  }

  /**
   * Tasks that ended badly and whose news the model has not received yet. The prompt frame shows
   * this number: it goes away once devx has told the model about the failure.
   */
  unreportedFailures(): number {
    const ids = new Set<string>();
    for (const note of this.notes) {
      if (note.kind === 'exit' && this.tasks.get(note.taskId)?.info.status === 'failed') ids.add(note.taskId);
    }
    return ids.size;
  }

  // ───────────────────────── internals ─────────────────────────

  private must(id: string): Task {
    const task = this.tasks.get(String(id));
    if (!task) {
      const known = [...this.tasks.keys()].join(', ');
      throw new Error(`No background task "${id}"${known ? ` (known: ${known})` : ' (none started)'}`);
    }
    return task;
  }

  private snapshot(task: Task): BackgroundInfo {
    return { ...task.info, outputChars: task.trimmed + task.buffer.length };
  }

  private append(task: Task, text: string) {
    if (!text) return;
    task.buffer += text;
    if (task.buffer.length > MAX_BUFFER * 1.5) {
      const cut = task.buffer.length - MAX_BUFFER;
      task.buffer = task.buffer.slice(cut);
      task.trimmed += cut;
    }
    if (task.watch) this.scanLines(task, text);
    this.emit(`data:${task.info.id}`);
    this.emit('output', task.info.id);
  }

  private scanLines(task: Task, text: string) {
    const parts = cleanOutput(task.pendingLine + text).split('\n');
    task.pendingLine = parts.pop() ?? '';
    for (const line of parts) {
      if (task.watch!.test(line)) this.matched(task, line);
    }
  }

  private flushPendingLine(task: Task) {
    if (task.watch && task.pendingLine && task.watch.test(task.pendingLine)) this.matched(task, task.pendingLine);
    task.pendingLine = '';
  }

  private matched(task: Task, line: string) {
    task.matchedLines.push(line.trim().slice(0, 300));
    if (task.matchedLines.length > 20) task.matchedLines.shift();
    clearTimeout(task.matchTimer);
    task.matchTimer = setTimeout(() => this.flushMatches(task), MATCH_DEBOUNCE_MS);
    task.matchTimer.unref();
  }

  private flushMatches(task: Task) {
    clearTimeout(task.matchTimer);
    if (task.matchedLines.length === 0) return;
    const lines = task.matchedLines.splice(0);
    const shown = lines.slice(-5).map((l) => `  ${l}`).join('\n');
    this.addNote(task, 'match', {
      summary: `${task.info.id} · ${lines[lines.length - 1]}`,
      text: `${task.info.id} "${task.info.label}" printed ${lines.length} line${lines.length === 1 ? '' : 's'} matching /${task.info.watching}/:\n${shown}`,
    });
  }

  private exitNote(task: Task): { summary: string; text: string } {
    const { id, label, code, startedAt, endedAt } = task.info;
    const how = task.timedOut ? 'was stopped: its time limit ran out' : code === 0 ? 'finished (exit code 0)' : code === null ? 'stopped unexpectedly' : `failed (exit code ${code})`;
    const after = formatDuration((endedAt ?? Date.now()) - startedAt);
    const tail = this.tailForNote(task);
    return {
      summary: `${id} ${code === 0 ? 'finished' : code === null ? 'stopped' : `failed · exit ${code}`} · ${after}`,
      text: `${id} "${label}" ${how} after ${after}.${tail ? `\nLast output:\n${tail}` : ''}`,
    };
  }

  private tailForNote(task: Task): string {
    const text = cleanOutput(task.buffer).trimEnd();
    return text.length > NOTE_TAIL_CHARS ? '…' + text.slice(-NOTE_TAIL_CHARS).replace(/^[^\n]*\n/, '') : text;
  }

  private addNote(task: Task, kind: 'exit' | 'match', body: { summary: string; text: string }) {
    task.notes++;
    // A chatty watcher must not flood the conversation: after a dozen notes only the end is reported.
    if (kind === 'match' && task.notes > MAX_NOTES_PER_TASK) return;
    const note: BackgroundNote = { taskId: task.info.id, kind, ...body };
    this.notes.push(note);
    this.emit('note', note);
  }

  private killTree(task: Task, force: boolean) {
    const pid = task.proc.pid;
    if (!pid || task.info.status !== 'running') return;
    if (process.platform === 'win32') {
      const killer = spawn('taskkill', ['/pid', String(pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' });
      killer.on('error', () => task.proc.kill());
      return;
    }
    try {
      process.kill(-pid, force ? 'SIGKILL' : 'SIGTERM');
    } catch (err: any) {
      if (err.code !== 'ESRCH') {
        try { task.proc.kill(force ? 'SIGKILL' : 'SIGTERM'); } catch {}
      }
    }
  }

  /** Nothing started by devx may outlive it. */
  private installExitHook() {
    liveManagers.add(this);
    if (exitHookInstalled) return;
    exitHookInstalled = true;
    process.on('exit', () => {
      for (const manager of liveManagers) manager.killAllNow();
    });
  }

  /** Synchronous: used when the process is exiting. */
  private killAllNow() {
    for (const task of this.tasks.values()) {
      if (task.info.status === 'running') this.killTree(task, true);
    }
  }
}

const liveManagers = new Set<BackgroundManager>();
let exitHookInstalled = false;

let instance: BackgroundManager | null = null;

export function getBackgroundManager(): BackgroundManager {
  if (!instance) {
    instance = new BackgroundManager();
    instance.setMaxListeners(50);
  }
  return instance;
}

/** For tests: a fresh manager. */
export function resetBackgroundManager() {
  void instance?.stopAll();
  instance = null;
}
