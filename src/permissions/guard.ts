import { PermissionGuard } from '../core/types.js';
import * as p from '@clack/prompts';
import path from 'path';

export class CLIConsoleGuard implements PermissionGuard {
  constructor(private autoApprove: boolean = false) {}

  check(toolName: string, args: any): boolean {
    if (this.autoApprove) return false;
    if (toolName === 'bash') return true;
    
    if (toolName === 'write_file' || toolName === 'mkdir') {
      const target = args.path || args.dir;
      if (target) {
        const resolved = path.resolve(target);
        const cwd = process.cwd();
        if (!resolved.startsWith(cwd)) {
          return true;
        }
      }
    }
    return false;
  }

  async askUser(toolName: string, args: any): Promise<boolean> {
    if (this.autoApprove) return true;
    p.log.warn(`[GUARD] Agent wants to execute '${toolName}'`);
    p.log.message(`Arguments: ${JSON.stringify(args, null, 2)}`);
    const allowed = await p.confirm({
      message: 'Allow execution?',
      initialValue: true
    });
    return !!allowed;
  }
}
