import pc from 'picocolors';
import path from 'path';
import os from 'os';
import fsSync from 'fs';
import { askPermission } from '../cli/permission.js';
// ───────────────────────── path helpers ─────────────────────────
function isInside(base, target) {
    const rel = path.relative(base, target);
    return rel === '' || (rel !== '..' && !rel.startsWith('..' + path.sep) && !path.isAbsolute(rel));
}
/** Resolves a path against cwd, following symlinks of the nearest existing ancestor. */
function realResolve(target) {
    const abs = path.resolve(process.cwd(), target);
    let existing = abs;
    const rest = [];
    while (!fsSync.existsSync(existing)) {
        const parent = path.dirname(existing);
        if (parent === existing)
            break;
        rest.unshift(path.basename(existing));
        existing = parent;
    }
    try {
        return path.join(fsSync.realpathSync(existing), ...rest);
    }
    catch {
        return abs;
    }
}
function realCwd() {
    try {
        return fsSync.realpathSync(process.cwd());
    }
    catch {
        return process.cwd();
    }
}
/**
 * Returns true if the path resolves outside the current working directory
 * (symlinks are followed, so a link inside the project cannot be used to escape it).
 */
export function isPathOutsideCwd(targetPath) {
    if (!targetPath || typeof targetPath !== 'string')
        return false;
    try {
        return !isInside(realCwd(), realResolve(targetPath));
    }
    catch {
        return true;
    }
}
// Credential stores that live in the home directory. Reading or writing these is
// always confirmed, even in YOLO mode.
const HOME_SENSITIVE_DIRS = ['.ssh', '.aws', '.gnupg', '.devx', '.kube', '.docker', '.azure', path.join('.config', 'gcloud'), path.join('.config', 'gh')];
const HOME_SENSITIVE_FILES = ['.devxrc.json', '.netrc', '.npmrc', '.pypirc', '.git-credentials', '.pgpass', '.bash_history', '.zsh_history'];
export function isCredentialStore(targetPath) {
    if (!targetPath || typeof targetPath !== 'string')
        return false;
    try {
        const abs = realResolve(targetPath);
        let home = os.homedir();
        try {
            home = fsSync.realpathSync(home);
        }
        catch { }
        return (HOME_SENSITIVE_DIRS.some(d => isInside(path.join(home, d), abs)) ||
            HOME_SENSITIVE_FILES.some(f => abs === path.join(home, f)));
    }
    catch {
        return false;
    }
}
const SECRET_BASENAME = /^(id_(rsa|dsa|ecdsa|ed25519)|\.env(\..+)?|\.netrc|\.npmrc|credentials)$/i;
const ENV_TEMPLATE = /^\.env(\..+)?\.(example|sample|template|dist|defaults?)$/i;
const SECRET_EXT = new Set(['.pem', '.key', '.p12', '.pfx', '.keystore', '.jks']);
/** Private keys and dotenv files, wherever they are. */
export function isSecretFile(targetPath) {
    if (!targetPath || typeof targetPath !== 'string')
        return false;
    const base = path.basename(targetPath);
    if (ENV_TEMPLATE.test(base))
        return false;
    return SECRET_BASENAME.test(base) || SECRET_EXT.has(path.extname(base).toLowerCase());
}
/** Classifies a path argument of a shell command relative to the project directory. */
function classifyTarget(raw) {
    let t = raw.trim().replace(/^["']|["']$/g, '');
    if (!t)
        return 'unknown';
    const home = os.homedir();
    t = t.replace(/^~(?=\/|\\|$)/, home).replace(/^\$\{?HOME\}?(?=\/|$)/, home);
    if (process.env.PREFIX) {
        t = t.replace(/^\$\{?PREFIX\}?(?=\/|$)/, process.env.PREFIX);
    }
    if (/[$`]/.test(t))
        return 'unknown'; // unexpanded variable: may be empty or anything
    // For globs, the directory the glob expands in is what matters.
    const globIdx = t.search(/[*?[]/);
    let dirPart = t;
    if (globIdx !== -1) {
        const slash = t.lastIndexOf('/', globIdx);
        dirPart = slash >= 0 ? t.slice(0, slash + 1) || '/' : '.';
    }
    const abs = path.resolve(process.cwd(), dirPart);
    if (abs === path.parse(abs).root || abs === home)
        return 'root';
    const cwd = process.cwd();
    if (abs === cwd)
        return 'cwd';
    if (isInside(cwd, abs))
        return 'inside';
    const tmp = os.tmpdir();
    if (abs !== tmp && isInside(tmp, abs))
        return 'inside';
    return 'outside';
}
const RISKY_FOR_RECURSIVE_DELETE = new Set(['root', 'outside', 'cwd', 'unknown']);
const RISKY_FOR_RECURSIVE_CHANGE = new Set(['root', 'outside', 'unknown']);
function tokenize(segment) {
    const tokens = [];
    const re = /"((?:\\.|[^"\\])*)"|'([^']*)'|(\S+)/g;
    let m;
    while ((m = re.exec(segment)) !== null) {
        tokens.push(m[1] ?? m[2] ?? m[3]);
    }
    return tokens;
}
const hasRecursiveFlag = (flags) => flags.some(f => f === '--recursive' || /^-[a-zA-Z]*[rR][a-zA-Z]*$/.test(f));
const ALWAYS_DANGEROUS_COMMANDS = new Set([
    'sudo', 'su', 'doas', 'pkexec',
    'mke2fs', 'fdisk', 'sfdisk', 'cfdisk', 'parted', 'wipefs', 'diskpart',
    'shutdown', 'reboot', 'halt', 'poweroff',
]);
function analyzeSegment(tokens) {
    for (let i = 0; i < tokens.length; i++) {
        const name = path.basename(tokens[i]).toLowerCase().replace(/\.exe$/, '');
        const args = tokens.slice(i + 1);
        const flags = args.filter(a => a.startsWith('-') && a !== '-' && a !== '--');
        const operands = args.filter(a => !a.startsWith('-'));
        if (ALWAYS_DANGEROUS_COMMANDS.has(name) || name === 'mkfs' || name.startsWith('mkfs.'))
            return true;
        switch (name) {
            case 'rm':
            case 'unlink':
            case 'shred': {
                if (flags.includes('--no-preserve-root'))
                    return true;
                const recursive = hasRecursiveFlag(flags);
                for (const target of operands) {
                    const kind = classifyTarget(target);
                    if (kind === 'root')
                        return true;
                    if (recursive && RISKY_FOR_RECURSIVE_DELETE.has(kind))
                        return true;
                    if (isCredentialStore(target.replace(/^~(?=\/|$)/, os.homedir())))
                        return true;
                }
                break;
            }
            case 'find': {
                const hasDelete = args.includes('-delete');
                const execsRm = args.some((a, idx) => ['-exec', '-execdir', '-ok'].includes(a) && /^(rm|shred|unlink)$/.test(path.basename(args[idx + 1] || '')));
                if (!hasDelete && !execsRm)
                    break;
                const firstPredicate = args.findIndex(a => a.startsWith('-') || a === '(' || a === '!');
                const roots = firstPredicate === -1 ? args : args.slice(0, firstPredicate);
                const predicates = firstPredicate === -1 ? [] : args.slice(firstPredicate).filter(a => a !== '-delete');
                const effectiveRoots = roots.length ? roots : ['.'];
                for (const root of effectiveRoots) {
                    const kind = classifyTarget(root);
                    if (RISKY_FOR_RECURSIVE_CHANGE.has(kind))
                        return true;
                    // `find . -delete` with no filter wipes the whole project.
                    if (kind === 'cwd' && predicates.length === 0)
                        return true;
                }
                break;
            }
            case 'chmod':
            case 'chown':
            case 'chgrp': {
                if (!hasRecursiveFlag(flags))
                    break;
                // The first operand is the mode / owner, the rest are paths.
                for (const target of operands.slice(1)) {
                    if (RISKY_FOR_RECURSIVE_CHANGE.has(classifyTarget(target)))
                        return true;
                }
                break;
            }
            case 'dd': {
                if (args.some(a => a.startsWith('if=') || a.startsWith('of=/dev/')))
                    return true;
                break;
            }
            case 'format': {
                if (args.some(a => /^[a-z]:$/i.test(a)))
                    return true;
                break;
            }
            case 'git': {
                if (args.includes('push') && args.some(a => /^(-f|--force|--force-with-lease(=.*)?|--mirror|--delete)$/.test(a) || /^\+\S+/.test(a)))
                    return true;
                if (args.includes('reset') && args.includes('--hard'))
                    return true;
                if (args.includes('clean') && args.some(a => a === '--force' || /^-[a-zA-Z]*f/.test(a)))
                    return true;
                break;
            }
        }
    }
    return false;
}
const SHELL_INTERPRETER = '(?:\\S*\\/)?(?:ba|z|da|k|c|fi|a)?sh';
/**
 * Detects destructive or remote-code-execution shell commands. This is a safety
 * net for YOLO mode, not a sandbox: it works on tokens (so `rm --recursive --force ~`
 * and `find ~ -delete` are caught), but a determined obfuscation can still slip through.
 */
export function isDangerousBashCommand(commandStr) {
    if (!commandStr || typeof commandStr !== 'string')
        return false;
    const norm = commandStr.replace(/\s+/g, ' ').trim();
    const lower = norm.toLowerCase();
    // Whole-command patterns (these need the pipes / redirections intact).
    const patterns = [
        /:\s*\(\s*\)\s*\{/, // fork bomb
        new RegExp(`\\|\\s*(?:sudo\\s+(?:-\\S+\\s+)*)?${SHELL_INTERPRETER}\\b`), // anything piped into a shell
        /\b(?:curl|wget|fetch)\b[^|]*\|\s*(?:sudo\s+)?(?:python[0-9.]*|node|perl|ruby|php)\b/, // download | interpreter
        /<\(\s*(?:curl|wget)\b/, // sh <(curl ...)
        /\$\(\s*(?:curl|wget)\b/, // sh -c "$(curl ...)"
        />\s*\/dev\/(?:sd|hd|vd|nvme|mmcblk|disk|rdisk)/, // overwrite a block device
        />>?\s*["']?(?:~|\$\{?home\}?|\/home\/[^/\s]+|\/root|\/users\/[^/\s]+)\/\.(?:ssh|bashrc|zshrc|profile|bash_profile|zprofile|gnupg|aws|netrc)/, // plant keys / shell hooks
        /\b(?:rd|rmdir)\s+(?:\/[a-z]\s+)*\/s\b/, // Windows recursive delete
        /\bdel\b[^&|]*\s\/s\b/,
    ];
    if (patterns.some(re => re.test(lower)))
        return true;
    // Per-command analysis on tokens.
    const segments = commandStr.split(/\$\(|`|&&|\|\||[;|&()\n\r]/);
    return segments.some(segment => analyzeSegment(tokenize(segment)));
}
/**
 * Checks if a command contains chaining, redirection, or subshell operators.
 * Allowlist matches MUST be clean, single commands without hidden side-effects.
 */
function hasComplexChainingOrRedirection(commandStr) {
    if (!commandStr)
        return false;
    return (commandStr.includes('&&') ||
        commandStr.includes('||') ||
        commandStr.includes(';') ||
        commandStr.includes('|') ||
        commandStr.includes('>') ||
        commandStr.includes('<') ||
        commandStr.includes('`') ||
        commandStr.includes('$(') ||
        commandStr.includes('\n') ||
        commandStr.includes('\r'));
}
// ───────────────────────── guard ─────────────────────────
const SHELL_TOOLS = new Set(['bash', 'exec', 'run_command', 'execute_command']);
const READ_TOOLS = new Set(['read_file', 'view_file', 'read', 'list_dir', 'list_files', 'ls', 'search', 'grep_search', 'grep', 'diagnose_code']);
const WRITE_TOOLS = new Set(['write_file', 'edit_file', 'make_dir', 'mkdir', 'create_file', 'write_to_file', 'patch']);
function pathArg(args) {
    const v = args?.path ?? args?.dir ?? args?.targetFile ?? args?.filePath ?? args?.file;
    return typeof v === 'string' ? v : undefined;
}
export class CLIConsoleGuard {
    autoApprove;
    bashAllowlist;
    constructor(autoApprove = false, bashAllowlist = []) {
        this.autoApprove = autoApprove;
        this.bashAllowlist = bashAllowlist;
    }
    check(toolName, args) {
        const t = (toolName || '').toLowerCase();
        const cmd = (args?.command || args?.cmd || '').trim();
        const target = pathArg(args);
        // Critical safety net. These ask for confirmation even in YOLO mode.
        if (SHELL_TOOLS.has(t) && isDangerousBashCommand(cmd))
            return true;
        if (target && (READ_TOOLS.has(t) || WRITE_TOOLS.has(t)) && isCredentialStore(target))
            return true;
        if (target && READ_TOOLS.has(t) && isSecretFile(target))
            return true;
        if (this.autoApprove)
            return false;
        // Bash allowlist: only for single, clean commands without chaining / redirection.
        if (SHELL_TOOLS.has(t) && cmd) {
            if (!hasComplexChainingOrRedirection(cmd)) {
                const lowerCmd = cmd.toLowerCase();
                const isAllowed = this.bashAllowlist.some(pattern => {
                    const pat = pattern.trim().toLowerCase();
                    return lowerCmd === pat || lowerCmd.startsWith(pat + ' ');
                });
                if (isAllowed)
                    return false;
            }
            return true; // Requires user confirmation
        }
        if (t === 'install_package' || t === 'packages')
            return true;
        if (t === 'delete_file' || t === 'remove_file')
            return true;
        // The preview server listens on all interfaces and serves the project folder.
        if (t === 'serve_preview' && args?.action !== 'stop' && args?.action !== 'status')
            return true;
        // Anything that touches files outside the project needs approval (reads included).
        if ((WRITE_TOOLS.has(t) || READ_TOOLS.has(t)) && isPathOutsideCwd(target))
            return true;
        if (t.startsWith('mcp__'))
            return true;
        return false;
    }
    async askUser(toolName, args) {
        // In headless / non-interactive environment without TTY, deny confirmation-requiring actions immediately
        if (!process.stdin.isTTY) {
            console.error(pc.red(`\n[PERMISSION DENIED] Action '${toolName}' requires user confirmation, but no interactive terminal is available. Pass --yolo (-y) to auto-approve regular actions in headless mode (dangerous commands and credential files still need a terminal).`));
            return false;
        }
        const request = buildPermissionRequest(toolName, args);
        // Risky requests start on "Deny": a stray Enter must not approve them.
        return askPermission(request, !request.danger);
    }
}
/** Describes what the agent wants to do, for the approval card. */
export function buildPermissionRequest(toolName, args) {
    const t = (toolName || '').toLowerCase();
    const cmd = String(args?.command || args?.cmd || '');
    const target = pathArg(args);
    const notes = [];
    const sensitive = !!target && (isCredentialStore(target) || (READ_TOOLS.has(t) && isSecretFile(target)));
    if (sensitive)
        notes.push('this is a credentials / secrets file');
    else if (target && isPathOutsideCwd(target))
        notes.push('outside the project folder');
    if (SHELL_TOOLS.has(t)) {
        const danger = isDangerousBashCommand(cmd);
        if (danger)
            notes.push('looks destructive or runs downloaded code');
        return { title: t === 'bash' ? 'bash' : toolName, danger, lines: cmd.split('\n'), notes };
    }
    if (t.startsWith('mcp__')) {
        const parts = toolName.split('__');
        const server = parts[1] || 'mcp';
        const tool = parts.slice(2).join('__');
        const lines = args && Object.keys(args).length > 0 ? JSON.stringify(args, null, 2).split('\n') : ['(no arguments)'];
        return { title: `mcp · ${server} / ${tool}`, lines, notes };
    }
    if (t === 'install_package' || t === 'packages') {
        const manager = args?.manager ? `${args.manager}: ` : '';
        return { title: 'install_package', lines: [`${manager}${String(args?.package ?? '')}${args?.dev ? '  (dev dependency)' : ''}`], notes };
    }
    if (t === 'serve_preview') {
        notes.push('serves the project folder to your local network');
        return { title: 'serve_preview', lines: [`${args?.action ?? 'start'}${args?.port ? ` on port ${args.port}` : ''}`], notes };
    }
    if (target) {
        return { title: toolName, danger: sensitive, lines: [target], notes };
    }
    const lines = args && Object.keys(args).length > 0 ? JSON.stringify(args, null, 2).split('\n') : ['(no arguments)'];
    return { title: toolName, lines, notes };
}
