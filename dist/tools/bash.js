import { spawn } from 'child_process';
import { StringDecoder } from 'string_decoder';
export const DEFAULT_TIMEOUT_SECONDS = 120;
export const MAX_TIMEOUT_SECONDS = 1800;
// Output is kept as "head + tail": build/test tools print their summary and
// their errors at the very end, so the tail must never be the part we drop.
const HEAD_CHARS = 6000;
const TAIL_CHARS = 14000;
class OutputBuffer {
    head = '';
    tail = '';
    dropped = 0;
    push(text) {
        if (this.head.length < HEAD_CHARS) {
            const room = HEAD_CHARS - this.head.length;
            this.head += text.slice(0, room);
            text = text.slice(room);
        }
        if (!text)
            return;
        this.tail += text;
        // Trim lazily (at 2x) so a chatty command does not cause a slice per chunk.
        if (this.tail.length > TAIL_CHARS * 2) {
            const cut = this.tail.length - TAIL_CHARS;
            this.dropped += cut;
            this.tail = this.tail.slice(cut);
        }
    }
    toString() {
        let tail = this.tail;
        let dropped = this.dropped;
        if (tail.length > TAIL_CHARS) {
            dropped += tail.length - TAIL_CHARS;
            tail = tail.slice(tail.length - TAIL_CHARS);
        }
        if (dropped === 0)
            return this.head + tail;
        return `${this.head}\n[... ${dropped} characters omitted ...]\n${tail}`;
    }
}
export function normalizeTimeout(value) {
    const n = typeof value === 'string' ? Number(value) : value;
    if (typeof n !== 'number' || !Number.isFinite(n) || n <= 0)
        return DEFAULT_TIMEOUT_SECONDS;
    return Math.min(Math.max(n, 1), MAX_TIMEOUT_SECONDS);
}
/**
 * Runs a shell command, killing the whole process tree on cancel / timeout.
 * stdin is closed so commands that wait for input fail fast instead of hanging.
 */
export function runShell(command, options = {}) {
    const { signal } = options;
    const timeoutSeconds = normalizeTimeout(options.timeoutSeconds);
    return new Promise((resolve, reject) => {
        const isWin = process.platform === 'win32';
        const proc = spawn(command, {
            shell: true,
            detached: !isWin,
            stdio: ['ignore', 'pipe', 'pipe'],
        });
        const output = new OutputBuffer();
        const stdoutDecoder = new StringDecoder('utf8');
        const stderrDecoder = new StringDecoder('utf8');
        let stopReason = '';
        let killTimer;
        const killProcessTree = (force) => {
            if (!proc.pid)
                return;
            if (isWin) {
                // Killing the shell alone leaves its child commands running on Windows.
                const killer = spawn('taskkill', ['/pid', String(proc.pid), '/T', '/F'], {
                    windowsHide: true,
                    stdio: 'ignore',
                });
                killer.on('error', () => proc.kill());
                return;
            }
            try {
                process.kill(-proc.pid, force ? 'SIGKILL' : 'SIGTERM');
            }
            catch (err) {
                if (err.code !== 'ESRCH')
                    proc.kill(force ? 'SIGKILL' : 'SIGTERM');
            }
        };
        const stop = (reason) => {
            if (stopReason)
                return;
            stopReason = reason;
            killProcessTree(false);
            // Commands can ignore SIGTERM. Do not leave them running after cancellation.
            killTimer = setTimeout(() => killProcessTree(true), 1000);
        };
        const onAbort = () => stop('[Operation cancelled by user]');
        const timeout = setTimeout(() => stop(`[Process killed due to timeout after ${timeoutSeconds}s. ` +
            `Pass a larger "timeout_seconds" (max ${MAX_TIMEOUT_SECONDS}) for long-running commands.]`), timeoutSeconds * 1000);
        signal?.addEventListener('abort', onAbort, { once: true });
        if (signal?.aborted)
            onAbort();
        proc.stdout.on('data', (data) => {
            if (!stopReason)
                output.push(stdoutDecoder.write(data));
        });
        proc.stderr.on('data', (data) => {
            if (!stopReason)
                output.push(stderrDecoder.write(data));
        });
        const cleanup = () => {
            clearTimeout(timeout);
            clearTimeout(killTimer);
            signal?.removeEventListener('abort', onAbort);
        };
        proc.on('close', (code) => {
            if (stopReason)
                killProcessTree(true);
            output.push(stdoutDecoder.end() + stderrDecoder.end());
            cleanup();
            resolve({ output: output.toString(), code, stopReason });
        });
        proc.on('error', (err) => {
            cleanup();
            reject(new Error(`Failed to start process: ${err.message}`));
        });
    });
}
export const bashTool = {
    name: 'bash',
    definition: {
        name: 'bash',
        description: `Execute a bash command. stdin is closed (no interactive input). ` +
            `Commands are stopped after ${DEFAULT_TIMEOUT_SECONDS}s by default; ` +
            `for long builds/installs pass "timeout_seconds" (max ${MAX_TIMEOUT_SECONDS}). ` +
            `Very long output is shortened in the middle, the end is always kept.`,
        parameters: {
            type: 'object',
            properties: {
                command: { type: 'string' },
                timeout_seconds: {
                    type: 'number',
                    description: `Optional time limit in seconds (default ${DEFAULT_TIMEOUT_SECONDS}, max ${MAX_TIMEOUT_SECONDS})`,
                },
            },
            required: ['command']
        }
    },
    validateArgs(args) {
        if (!args.command || typeof args.command !== 'string')
            throw new Error('command is required');
    },
    async execute(args, _config, context) {
        const signal = context?.signal;
        if (signal?.aborted)
            return '[Operation cancelled by user]';
        const { output, code, stopReason } = await runShell(args.command, {
            signal,
            timeoutSeconds: args.timeout_seconds,
        });
        return `${output}\n${stopReason || `[Exit code: ${code}]`}`;
    }
};
