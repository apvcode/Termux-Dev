import { spawn } from 'child_process';
import { Tool } from '../core/types.js';

export const bashTool: Tool = {
  name: 'bash',
  definition: {
    name: 'bash',
    description: 'Execute a bash command',
    parameters: {
      type: 'object',
      properties: {
        command: { type: 'string' }
      },
      required: ['command']
    }
  },
  validateArgs(args: any) {
    if (!args.command || typeof args.command !== 'string') throw new Error('command is required');
  },
  async execute(args, _config, context) {
    const signal = context?.signal;
    if (signal?.aborted) return '[Operation cancelled by user]';

    return new Promise<string>((resolve, reject) => {
      const isWin = process.platform === 'win32';
      const proc = spawn(args.command, { shell: true, detached: !isWin });
      let output = '';
      let stopReason = '';
      let killTimer: ReturnType<typeof setTimeout> | undefined;

      const killProcessTree = (force: boolean) => {
        if (!proc.pid) return;
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
        } catch (err: any) {
          if (err.code !== 'ESRCH') proc.kill(force ? 'SIGKILL' : 'SIGTERM');
        }
      };

      const stop = (reason: string) => {
        if (stopReason) return;
        stopReason = reason;
        killProcessTree(false);
        // Commands can ignore SIGTERM. Do not leave them running after cancellation.
        killTimer = setTimeout(() => killProcessTree(true), 1000);
      };

      const onAbort = () => stop('[Operation cancelled by user]');
      const timeout = setTimeout(() => stop('[Process killed due to timeout]'), 30000);
      signal?.addEventListener('abort', onAbort, { once: true });
      if (signal?.aborted) onAbort();

      const collectOutput = (data: Buffer) => {
        if (stopReason) return;
        output += data.toString();
        if (output.length > 20000) {
          output = output.substring(0, 20000);
          stop('[Output truncated]');
        }
      };
      proc.stdout.on('data', collectOutput);
      proc.stderr.on('data', collectOutput);

      const cleanup = () => {
        clearTimeout(timeout);
        clearTimeout(killTimer);
        signal?.removeEventListener('abort', onAbort);
      };

      proc.on('close', (code) => {
        if (stopReason) killProcessTree(true);
        cleanup();
        resolve(`${output}\n${stopReason || `[Exit code: ${code}]`}`);
      });
      proc.on('error', (err) => {
        cleanup();
        reject(new Error(`Failed to start process: ${err.message}`));
      });
    });
  }
};
