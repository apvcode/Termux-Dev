import { PermissionGuard } from '../core/types.js';
import pc from 'picocolors';
import path from 'path';
import os from 'os';
import fsSync from 'fs';
import { askPermissionDecision } from '../cli/permission.js';
import { PermissionAnswer, PermissionOffer, PermissionRequest } from '../cli/ui.js';

// ───────────────────────── path helpers ─────────────────────────

function isInside(base: string, target: string): boolean {
  const rel = path.relative(base, target);
  return rel === '' || (rel !== '..' && !rel.startsWith('..' + path.sep) && !path.isAbsolute(rel));
}

/** Resolves a path against cwd, following symlinks of the nearest existing ancestor. */
function realResolve(target: string): string {
  const abs = path.resolve(process.cwd(), target);
  let existing = abs;
  const rest: string[] = [];
  while (!fsSync.existsSync(existing)) {
    const parent = path.dirname(existing);
    if (parent === existing) break;
    rest.unshift(path.basename(existing));
    existing = parent;
  }
  try {
    return path.join(fsSync.realpathSync(existing), ...rest);
  } catch {
    return abs;
  }
}

function realCwd(): string {
  try {
    return fsSync.realpathSync(process.cwd());
  } catch {
    return process.cwd();
  }
}

/**
 * Returns true if the path resolves outside the current working directory
 * (symlinks are followed, so a link inside the project cannot be used to escape it).
 */
export function isPathOutsideCwd(targetPath?: string): boolean {
  if (!targetPath || typeof targetPath !== 'string') return false;
  try {
    return !isInside(realCwd(), realResolve(targetPath));
  } catch {
    return true;
  }
}

// Credential stores that live in the home directory. Reading or writing these is
// always confirmed, even in YOLO mode.
const HOME_SENSITIVE_DIRS = ['.ssh', '.aws', '.gnupg', '.devx', '.kube', '.docker', '.azure', path.join('.config', 'gcloud'), path.join('.config', 'gh')];
const HOME_SENSITIVE_FILES = ['.devxrc.json', '.netrc', '.npmrc', '.pypirc', '.git-credentials', '.pgpass', '.bash_history', '.zsh_history'];

export function isCredentialStore(targetPath?: string): boolean {
  if (!targetPath || typeof targetPath !== 'string') return false;
  try {
    const abs = realResolve(targetPath);
    let home = os.homedir();
    try { home = fsSync.realpathSync(home); } catch {}
    return (
      HOME_SENSITIVE_DIRS.some(d => isInside(path.join(home, d), abs)) ||
      HOME_SENSITIVE_FILES.some(f => abs === path.join(home, f))
    );
  } catch {
    return false;
  }
}

const SECRET_BASENAME = /^(id_(rsa|dsa|ecdsa|ed25519)|\.env(\..+)?|\.netrc|\.npmrc|credentials)$/i;
const ENV_TEMPLATE = /^\.env(\..+)?\.(example|sample|template|dist|defaults?)$/i;
const SECRET_EXT = new Set(['.pem', '.key', '.p12', '.pfx', '.keystore', '.jks']);

/** Private keys and dotenv files, wherever they are. */
export function isSecretFile(targetPath?: string): boolean {
  if (!targetPath || typeof targetPath !== 'string') return false;
  const base = path.basename(targetPath);
  if (ENV_TEMPLATE.test(base)) return false;
  return SECRET_BASENAME.test(base) || SECRET_EXT.has(path.extname(base).toLowerCase());
}

// ───────────────────────── dangerous shell detection ─────────────────────────

type TargetKind = 'root' | 'outside' | 'cwd' | 'inside' | 'unknown';

/** Classifies a path argument of a shell command relative to the project directory. */
function classifyTarget(raw: string): TargetKind {
  let t = raw.trim().replace(/^["']|["']$/g, '');
  if (!t) return 'unknown';
  const home = os.homedir();
  t = t.replace(/^~(?=\/|\\|$)/, home).replace(/^\$\{?HOME\}?(?=\/|$)/, home);
  if (process.env.PREFIX) {
    t = t.replace(/^\$\{?PREFIX\}?(?=\/|$)/, process.env.PREFIX);
  }
  if (/[$`]/.test(t)) return 'unknown'; // unexpanded variable: may be empty or anything

  // For globs, the directory the glob expands in is what matters.
  const globIdx = t.search(/[*?[]/);
  let dirPart = t;
  if (globIdx !== -1) {
    const slash = t.lastIndexOf('/', globIdx);
    dirPart = slash >= 0 ? t.slice(0, slash + 1) || '/' : '.';
  }

  const abs = path.resolve(process.cwd(), dirPart);
  if (abs === path.parse(abs).root || abs === home) return 'root';
  const cwd = process.cwd();
  if (abs === cwd) return 'cwd';
  if (isInside(cwd, abs)) return 'inside';
  const tmp = os.tmpdir();
  if (abs !== tmp && isInside(tmp, abs)) return 'inside';
  return 'outside';
}

const RISKY_FOR_RECURSIVE_DELETE = new Set<TargetKind>(['root', 'outside', 'cwd', 'unknown']);
const RISKY_FOR_RECURSIVE_CHANGE = new Set<TargetKind>(['root', 'outside', 'unknown']);

function tokenize(segment: string): string[] {
  const tokens: string[] = [];
  const re = /"((?:\\.|[^"\\])*)"|'([^']*)'|(\S+)/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(segment)) !== null) {
    tokens.push(m[1] ?? m[2] ?? m[3]);
  }
  return tokens;
}

const hasRecursiveFlag = (flags: string[]) =>
  flags.some(f => f === '--recursive' || /^-[a-zA-Z]*[rR][a-zA-Z]*$/.test(f));

const ALWAYS_DANGEROUS_COMMANDS = new Set([
  'sudo', 'su', 'doas', 'pkexec',
  'mke2fs', 'fdisk', 'sfdisk', 'cfdisk', 'parted', 'wipefs', 'diskpart',
  'shutdown', 'reboot', 'halt', 'poweroff',
]);

function analyzeSegment(tokens: string[]): boolean {
  for (let i = 0; i < tokens.length; i++) {
    const name = path.basename(tokens[i]).toLowerCase().replace(/\.exe$/, '');
    const args = tokens.slice(i + 1);
    const flags = args.filter(a => a.startsWith('-') && a !== '-' && a !== '--');
    const operands = args.filter(a => !a.startsWith('-'));

    if (ALWAYS_DANGEROUS_COMMANDS.has(name) || name === 'mkfs' || name.startsWith('mkfs.')) return true;

    switch (name) {
      case 'rm':
      case 'unlink':
      case 'shred': {
        if (flags.includes('--no-preserve-root')) return true;
        const recursive = hasRecursiveFlag(flags);
        for (const target of operands) {
          const kind = classifyTarget(target);
          if (kind === 'root') return true;
          if (recursive && RISKY_FOR_RECURSIVE_DELETE.has(kind)) return true;
          if (isCredentialStore(target.replace(/^~(?=\/|$)/, os.homedir()))) return true;
        }
        break;
      }
      case 'find': {
        const hasDelete = args.includes('-delete');
        const execsRm = args.some((a, idx) =>
          ['-exec', '-execdir', '-ok'].includes(a) && /^(rm|shred|unlink)$/.test(path.basename(args[idx + 1] || ''))
        );
        if (!hasDelete && !execsRm) break;
        const firstPredicate = args.findIndex(a => a.startsWith('-') || a === '(' || a === '!');
        const roots = firstPredicate === -1 ? args : args.slice(0, firstPredicate);
        const predicates = firstPredicate === -1 ? [] : args.slice(firstPredicate).filter(a => a !== '-delete');
        const effectiveRoots = roots.length ? roots : ['.'];
        for (const root of effectiveRoots) {
          const kind = classifyTarget(root);
          if (RISKY_FOR_RECURSIVE_CHANGE.has(kind)) return true;
          // `find . -delete` with no filter wipes the whole project.
          if (kind === 'cwd' && predicates.length === 0) return true;
        }
        break;
      }
      case 'chmod':
      case 'chown':
      case 'chgrp': {
        if (!hasRecursiveFlag(flags)) break;
        // The first operand is the mode / owner, the rest are paths.
        for (const target of operands.slice(1)) {
          if (RISKY_FOR_RECURSIVE_CHANGE.has(classifyTarget(target))) return true;
        }
        break;
      }
      case 'dd': {
        if (args.some(a => a.startsWith('if=') || a.startsWith('of=/dev/'))) return true;
        break;
      }
      case 'format': {
        if (args.some(a => /^[a-z]:$/i.test(a))) return true;
        break;
      }
      case 'git': {
        if (args.includes('push') && args.some(a => /^(-f|--force|--force-with-lease(=.*)?|--mirror|--delete)$/.test(a) || /^\+\S+/.test(a))) return true;
        if (args.includes('reset') && args.includes('--hard')) return true;
        if (args.includes('clean') && args.some(a => a === '--force' || /^-[a-zA-Z]*f/.test(a))) return true;
        break;
      }
    }
  }
  return false;
}

/**
 * A shell command that names a credentials store or a secrets file (`cat ~/.devxrc.json`,
 * `grep key ~/.ssh/config`, `head .env`). Such a command is asked about even in YOLO mode:
 * its output would reach the model and the chat. Templates such as `.env.example` are fine.
 */
export function shellTouchesSecrets(commandStr?: string): boolean {
  if (!commandStr || typeof commandStr !== 'string') return false;
  const home = os.homedir();
  const tokens = commandStr.split(/[\s;|&<>()`]+/).filter(Boolean);
  return tokens.some((raw) => {
    const t = raw.replace(/^["']+|["']+$/g, '').replace(/^~(?=\/|$)/, home).replace(/^\$\{?HOME\}?(?=\/|$)/, home);
    if (!t || t.startsWith('-') || /[$*?]/.test(t)) return false;
    // `cd ~ && cat .devxrc.json`: the folder is not in the token, so the file name alone counts.
    return isCredentialStore(t) || isSecretFile(t) || HOME_SENSITIVE_FILES.includes(path.basename(t));
  });
}

const SHELL_INTERPRETER = '(?:\\S*\\/)?(?:ba|z|da|k|c|fi|a)?sh';

/**
 * Detects destructive or remote-code-execution shell commands. This is a safety
 * net for YOLO mode, not a sandbox: it works on tokens (so `rm --recursive --force ~`
 * and `find ~ -delete` are caught), but a determined obfuscation can still slip through.
 */
export function isDangerousBashCommand(commandStr?: string): boolean {
  if (!commandStr || typeof commandStr !== 'string') return false;
  const norm = commandStr.replace(/\s+/g, ' ').trim();
  const lower = norm.toLowerCase();

  // Whole-command patterns (these need the pipes / redirections intact).
  const patterns: RegExp[] = [
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
  if (patterns.some(re => re.test(lower))) return true;

  // Per-command analysis on tokens.
  const segments = commandStr.split(/\$\(|`|&&|\|\||[;|&()\n\r]/);
  return segments.some(segment => analyzeSegment(tokenize(segment)));
}

/**
 * Checks if a command contains chaining, redirection, or subshell operators.
 * Allowlist matches MUST be clean, single commands without hidden side-effects.
 */
function hasComplexChainingOrRedirection(commandStr?: string): boolean {
  if (!commandStr) return false;
  return (
    commandStr.includes('&&') ||
    commandStr.includes('||') ||
    commandStr.includes(';') ||
    commandStr.includes('|') ||
    commandStr.includes('>') ||
    commandStr.includes('<') ||
    commandStr.includes('`') ||
    commandStr.includes('$(') ||
    commandStr.includes('\n') ||
    commandStr.includes('\r')
  );
}

// ───────────────────────── guard ─────────────────────────

const SHELL_TOOLS = new Set(['bash', 'background', 'exec', 'run_command', 'execute_command']);
const READ_TOOLS = new Set(['read_file', 'view_file', 'read', 'list_dir', 'list_files', 'ls', 'search', 'grep_search', 'grep', 'diagnose_code']);
const WRITE_TOOLS = new Set(['write_file', 'edit_file', 'make_dir', 'mkdir', 'create_file', 'write_to_file', 'patch']);

function pathArg(args: any): string | undefined {
  const v = args?.path ?? args?.dir ?? args?.targetFile ?? args?.filePath ?? args?.file;
  return typeof v === 'string' ? v : undefined;
}

/** What the user allowed on the approval card for this session (and what was saved with "Always"). */
export interface PermissionMemory {
  /** Command prefixes (`npm test`, `git status`) that run without asking. */
  bash: Set<string>;
  /** Names of MCP tools that run without asking. */
  tools: Set<string>;
}

export function newPermissionMemory(): PermissionMemory {
  return { bash: new Set(), tools: new Set() };
}

export interface RememberKey {
  kind: 'bash' | 'tool';
  /** What is stored: the command prefix, or the tool name. */
  key: string;
}

/**
 * What an "Allow for the session / Always" answer would remember for a request: a plain single
 * command by its program and subcommand (`npm test`, `git status`, `ls`), or an MCP tool by its
 * name. Dangerous commands, commands that read secrets, chains and redirections are never
 * remembered: those are asked every time.
 */
export function rememberKeyFor(toolName: string, args: any): RememberKey | null {
  const t = (toolName || '').toLowerCase();
  if (t.startsWith('mcp__')) return { kind: 'tool', key: toolName };
  if (!SHELL_TOOLS.has(t)) return null;
  const cmd = String(args?.command || args?.cmd || '').trim();
  if (!cmd || isDangerousBashCommand(cmd) || shellTouchesSecrets(cmd) || hasComplexChainingOrRedirection(cmd)) return null;
  const tokens = tokenize(cmd);
  if (tokens.length === 0) return null;
  const program = path.basename(tokens[0]).toLowerCase();
  if (!/^[a-z0-9._+-]+$/.test(program)) return null;
  const sub = tokens[1];
  // The subcommand is a plain word (`test`, `status`); a flag, a path or a value makes the program alone the key.
  const withSub = sub && /^[a-z][a-z0-9:_-]{0,19}$/.test(sub) && tokens.length <= 3 ? `${program} ${sub.toLowerCase()}` : program;
  return { kind: 'bash', key: withSub };
}

export interface GuardOptions {
  memory?: PermissionMemory;
  /** Called when the user answered "Always": store the key for the next sessions. */
  onAlways?: (remember: RememberKey) => void;
  /** For tests: stands in for the approval card. */
  decide?: (request: PermissionRequest, offer: PermissionOffer, danger: boolean) => Promise<PermissionAnswer>;
}

export class CLIConsoleGuard implements PermissionGuard {
  private memory: PermissionMemory;

  constructor(
    private autoApprove: boolean = false,
    private bashAllowlist: string[] = [],
    private options: GuardOptions = {}
  ) {
    this.memory = options.memory ?? newPermissionMemory();
  }

  check(toolName: string, args: any): boolean {
    const t = (toolName || '').toLowerCase();
    const cmd = (args?.command || args?.cmd || '').trim();
    const target = pathArg(args);

    // Critical safety net. These ask for confirmation even in YOLO mode.
    if (SHELL_TOOLS.has(t) && (isDangerousBashCommand(cmd) || shellTouchesSecrets(cmd))) return true;
    if (target && (READ_TOOLS.has(t) || WRITE_TOOLS.has(t)) && isCredentialStore(target)) return true;
    if (target && READ_TOOLS.has(t) && isSecretFile(target)) return true;

    // Adding a server starts a program on this machine every time devx runs: always asked.
    if (t === 'mcp_manage' && (args?.action === 'add' || args?.action === 'import_claude')) return true;

    if (this.autoApprove) return false;

    // Bash allowlist: only for single, clean commands without chaining / redirection.
    if (SHELL_TOOLS.has(t) && cmd) {
      if (!hasComplexChainingOrRedirection(cmd)) {
        const lowerCmd = cmd.toLowerCase();
        const isAllowed = [...this.bashAllowlist, ...this.memory.bash].some(pattern => {
          const pat = pattern.trim().toLowerCase();
          return lowerCmd === pat || lowerCmd.startsWith(pat + ' ');
        });
        if (isAllowed) return false;
      }
      return true; // Requires user confirmation
    }

    if (t === 'install_package' || t === 'packages') return true;
    if (t === 'delete_file' || t === 'remove_file') return true;

    // The preview server listens on all interfaces and serves the project folder.
    if (t === 'serve_preview' && args?.action !== 'stop' && args?.action !== 'status') return true;

    // Anything that touches files outside the project needs approval (reads included).
    if ((WRITE_TOOLS.has(t) || READ_TOOLS.has(t)) && isPathOutsideCwd(target)) return true;

    if (t.startsWith('mcp__')) return !this.memory.tools.has(toolName);

    return false;
  }

  async askUser(toolName: string, args: any): Promise<boolean> {
    // In headless / non-interactive environment without TTY, deny confirmation-requiring actions immediately
    if (!process.stdin.isTTY) {
      console.error(pc.red(`\n[PERMISSION DENIED] Action '${toolName}' requires user confirmation, but no interactive terminal is available. Pass --yolo (-y) to auto-approve regular actions in headless mode (dangerous commands and credential files still need a terminal).`));
      return false;
    }

    const request = buildPermissionRequest(toolName, args);
    // Only requests that are safe to remember offer "Session" and "Always".
    const remember = request.danger ? null : rememberKeyFor(toolName, args);
    const offer: PermissionOffer = remember ? { session: true, always: true } : {};
    // Risky requests start on "Deny": a stray Enter must not approve them.
    const defaultAnswer: PermissionAnswer = request.danger ? 'deny' : 'allow';
    const answer = this.options.decide
      ? await this.options.decide(request, offer, !!request.danger)
      : await askPermissionDecision(request, defaultAnswer, undefined, offer);
    if (answer === 'deny') return false;
    if (remember && (answer === 'session' || answer === 'always')) {
      (remember.kind === 'bash' ? this.memory.bash : this.memory.tools).add(remember.key);
      if (answer === 'always') this.options.onAlways?.(remember);
    }
    return true;
  }
}

/** Describes what the agent wants to do, for the approval card. */
export function buildPermissionRequest(toolName: string, args: any): PermissionRequest {
  const t = (toolName || '').toLowerCase();
  const cmd = String(args?.command || args?.cmd || '');
  const target = pathArg(args);
  const notes: string[] = [];

  const sensitive = !!target && (isCredentialStore(target) || (READ_TOOLS.has(t) && isSecretFile(target)));
  if (sensitive) notes.push('this is a credentials / secrets file');
  else if (target && isPathOutsideCwd(target)) notes.push('outside the project folder');

  if (SHELL_TOOLS.has(t)) {
    const danger = isDangerousBashCommand(cmd) || shellTouchesSecrets(cmd);
    if (isDangerousBashCommand(cmd)) notes.push('looks destructive or runs downloaded code');
    else if (shellTouchesSecrets(cmd)) notes.push('reads a credentials / secrets file: its content would reach the model');
    if (t === 'background') notes.push('keeps running in the background until it ends or you stop it');
    return { title: t === 'bash' ? 'bash' : toolName, danger, lines: cmd.split('\n'), notes };
  }

  if (t === 'mcp_manage') {
    if (args?.action === 'import_claude') {
      return { title: 'mcp · import from Claude Code', lines: ['servers of ~/.claude.json -> ~/.devx/mcp.json'], notes: ['they start as programs on your machine'] };
    }
    const full = args?.url ? String(args.url) : [String(args?.command ?? ''), ...(Array.isArray(args?.args) ? args.args.map(String) : [])].join(' ');
    const envNames = args?.env && typeof args.env === 'object' ? Object.keys(args.env) : [];
    return {
      title: `mcp · add server ${String(args?.name ?? '')}`,
      lines: [full, ...(envNames.length ? [`env: ${envNames.join(', ')}`] : [])],
      notes: ['saved to ~/.devx/mcp.json and started now and at every start of devx'],
    };
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
