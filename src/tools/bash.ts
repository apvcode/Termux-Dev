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
  async execute(args: any) {
    return new Promise((resolve, reject) => {
      const isWin = process.platform === 'win32';
      const proc = spawn(args.command, { shell: true, detached: !isWin });
      let output = '';
      let isTruncated = false;
      
      const killProc = () => {
        try {
          if (!isWin && proc.pid) {
            process.kill(-proc.pid, 'SIGTERM');
          } else {
            proc.kill();
          }
        } catch {}
      };
      
      const timeout = setTimeout(() => {
        killProc();
        resolve(output + '\n[Process killed due to timeout]');
      }, 30000);

      proc.stdout.on('data', (data) => {
        if (isTruncated) return;
        output += data.toString();
        if (output.length > 20000) {
          isTruncated = true;
          output = output.substring(0, 20000) + '\n[Output truncated]';
          killProc();
        }
      });

      proc.stderr.on('data', (data) => {
        if (isTruncated) return;
        output += data.toString();
        if (output.length > 20000) {
          isTruncated = true;
          output = output.substring(0, 20000) + '\n[Output truncated]';
          killProc();
        }
      });

      proc.on('close', (code) => {
        clearTimeout(timeout);
        resolve(`${output}\n[Exit code: ${code}]`);
      });
      
      proc.on('error', (err) => {
        clearTimeout(timeout);
        reject(new Error(`Failed to start process: ${err.message}`));
      });
    });
  }
};
