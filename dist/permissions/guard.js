import * as p from '@clack/prompts';
import pc from 'picocolors';
import path from 'path';
/**
 * Robust path escape check: returns true if target path resolves outside current working directory
 */
function isPathOutsideCwd(targetPath) {
    if (!targetPath)
        return false;
    try {
        const cwd = process.cwd();
        const resolved = path.resolve(cwd, targetPath);
        const relative = path.relative(cwd, resolved);
        return relative.startsWith('..') || path.isAbsolute(relative);
    }
    catch {
        return true;
    }
}
/**
 * Checks for destructive or dangerous shell commands
 */
function isDangerousBashCommand(commandStr) {
    if (!commandStr)
        return false;
    const lower = commandStr.toLowerCase().trim();
    return (lower.includes('rm -rf /') ||
        lower.includes('rm -rf ~') ||
        lower.includes('rm -rf *') ||
        lower.includes('mkfs') ||
        lower.includes('dd if=') ||
        lower.includes(':(){ :|:& };:') ||
        lower.includes('chmod -r 777 /') ||
        lower.includes('> /dev/sda') ||
        lower.includes('format c:'));
}
export class CLIConsoleGuard {
    autoApprove;
    constructor(autoApprove = false) {
        this.autoApprove = autoApprove;
    }
    check(toolName, args) {
        const t = (toolName || '').toLowerCase();
        const cmd = args?.command || args?.cmd || '';
        // Critical safety net: even in YOLO mode, warn and confirm destructive system commands
        if (this.autoApprove) {
            if (t === 'bash' && isDangerousBashCommand(cmd)) {
                return true;
            }
            return false;
        }
        // 1. Shell commands always require confirmation in safe mode
        if (t === 'bash' || t === 'exec' || t === 'run_command') {
            return true;
        }
        // 2. Package installations require confirmation
        if (t === 'install_package' || t === 'packages') {
            return true;
        }
        // 3. File deletions require confirmation
        if (t === 'delete_file' || t === 'remove_file') {
            return true;
        }
        // 4. File writes, edits, or directory creations outside project CWD require confirmation
        if (t === 'write_file' || t === 'edit_file' || t === 'make_dir' || t === 'mkdir') {
            const target = args?.path || args?.dir || args?.targetFile || args?.filePath;
            if (isPathOutsideCwd(target)) {
                return true;
            }
        }
        return false;
    }
    async askUser(toolName, args) {
        const t = (toolName || '').toLowerCase();
        const cmd = args?.command || args?.cmd || '';
        const isDangerous = t === 'bash' && isDangerousBashCommand(cmd);
        if (isDangerous) {
            p.log.error(pc.bold(pc.red('⚠️  [SECURITY WARNING] Agent requested a potentially dangerous system command!')));
        }
        else {
            p.log.warn(pc.bold(pc.yellow(`🛡️  [PERMISSION GUARD] Agent wants to execute: ${pc.cyan(toolName)}`)));
        }
        // Pretty preview of key arguments
        if (t === 'bash') {
            console.log(`  ${pc.bold('Command:')} ${pc.green(cmd)}`);
        }
        else if (t === 'install_package') {
            console.log(`  ${pc.bold('Packages:')} ${pc.green((args.packages || []).join(', '))}`);
        }
        else if (args?.path || args?.targetFile) {
            const pth = args.path || args.targetFile;
            const isOutside = isPathOutsideCwd(pth);
            console.log(`  ${pc.bold('Target File:')} ${isOutside ? pc.red(pth + ' (OUTSIDE PROJECT)') : pc.green(pth)}`);
        }
        else {
            console.log(`  ${pc.dim('Arguments:')} ${JSON.stringify(args, null, 2)}`);
        }
        const allowed = await p.confirm({
            message: isDangerous ? pc.red('Confirm executing this dangerous command?') : 'Allow execution?',
            initialValue: !isDangerous
        });
        return allowed === true;
    }
}
