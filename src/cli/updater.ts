import { spawn } from 'child_process';
import path from 'path';
import os from 'os';
import fs from 'fs/promises';
import { fileURLToPath } from 'url';

const GITHUB_REPO_URL = 'https://github.com/apvcode/Termux-Dev';
// Where to look for the newest version. Overridable (a mirror, a test server).
const REMOTE_PKG_URL = process.env.DEVX_UPDATE_PKG_URL || 'https://raw.githubusercontent.com/apvcode/Termux-Dev/main/package.json';
const REMOTE_RELEASE_URL = process.env.DEVX_UPDATE_RELEASE_URL || 'https://api.github.com/repos/apvcode/Termux-Dev/releases/latest';

export interface UpdateCheckResult {
  updateAvailable: boolean;
  currentVersion: string;
  latestVersion: string;
  releaseNotes?: string;
  /** When the release was published (ISO date), if GitHub said so. */
  publishedAt?: string;
  error?: string;
}

// Compare semantic versions (e.g. 1.1.0 > 1.0.0)
export function isNewerVersion(remote: string, current: string): boolean {
  const clean = (v: string) => v.replace(/^v/, '').trim();
  const rParts = clean(remote).split('.').map(n => parseInt(n, 10) || 0);
  const cParts = clean(current).split('.').map(n => parseInt(n, 10) || 0);

  for (let i = 0; i < Math.max(rParts.length, cParts.length); i++) {
    const r = rParts[i] || 0;
    const c = cParts[i] || 0;
    if (r > c) return true;
    if (r < c) return false;
  }
  return false;
}

export async function getCurrentVersion(): Promise<string> {
  try {
    const __filename = fileURLToPath(import.meta.url);
    const __dirname = path.dirname(__filename);
    const pkgPath = path.resolve(__dirname, '../../package.json');
    const data = await fs.readFile(pkgPath, 'utf8');
    const parsed = JSON.parse(data);
    return parsed.version || '1.0.0';
  } catch {
    return '1.0.0';
  }
}

export async function checkForUpdates(timeoutMs = 10000): Promise<UpdateCheckResult> {
  const currentVersion = await getCurrentVersion();
  // One deadline for the whole check. The signal is passed to EVERY request: on a flaky
  // mobile connection a stalled second request used to block devx at startup forever.
  const signal = AbortSignal.timeout(timeoutMs);

  try {
    // The release is asked at the same time as the package.json, not after it: the check takes as long
    // as the slower of the two, not both. A failure of the release request is not an error of the check.
    const releaseRequest = fetch(`${REMOTE_RELEASE_URL}?t=${Date.now()}`, {
      signal,
      headers: { 'User-Agent': 'devx-updater' }
    }).catch(() => null);

    // 1. Try fetching latest package.json with cache-busting timestamp
    const cacheBusterUrl = `${REMOTE_PKG_URL}?t=${Date.now()}`;
    const res = await fetch(cacheBusterUrl, {
      signal,
      headers: {
        'User-Agent': 'devx-updater',
        'Cache-Control': 'no-cache, no-store, must-revalidate',
        'Pragma': 'no-cache'
      }
    });

    let latestVersion = '';
    let releaseNotes = '';
    let publishedAt = '';

    if (res.ok) {
      const pkg = await res.json();
      latestVersion = (pkg.version || '').replace(/^v/, '').trim();
    }

    // 2. Also check latest GitHub release tag
    try {
      const relRes = await releaseRequest;
      if (relRes?.ok) {
        const relData = await relRes.json();
        const relTag = (relData.tag_name || relData.name || '').replace(/^v/, '').trim();
        if (relTag && (!latestVersion || isNewerVersion(relTag, latestVersion))) {
          latestVersion = relTag;
        }
        releaseNotes = relData.body || '';
        publishedAt = relData.published_at || '';
      }
    } catch {}

    if (latestVersion && isNewerVersion(latestVersion, currentVersion)) {
      return {
        updateAvailable: true,
        currentVersion,
        latestVersion,
        releaseNotes,
        publishedAt: publishedAt || undefined
      };
    }

    return {
      updateAvailable: false,
      currentVersion,
      latestVersion: latestVersion || currentVersion
    };
  } catch (err: any) {
    const timedOut = err.name === 'AbortError' || err.name === 'TimeoutError';
    return {
      updateAvailable: false,
      currentVersion,
      latestVersion: currentVersion,
      error: timedOut ? `Timeout (${Math.round(timeoutMs / 1000)}s)` : err.message
    };
  }
}

// ───────────────────────── remembering the check ─────────────────────────

/** An answer is good for this long; after it devx asks again. */
const CHECK_FRESH_MS = 6 * 60 * 60 * 1000;
/** A failed check (offline, a stalled connection) is not repeated at every start: that would stall every start. */
const CHECK_FAILED_MS = 30 * 60 * 1000;

const checkFile = () => path.join(os.homedir(), '.devx', 'update-check.json');

interface RememberedCheck {
  checkedAt: number;
  latestVersion?: string;
  releaseNotes?: string;
  publishedAt?: string;
  error?: string;
}

/** The last check, when it is recent enough to be trusted at start-up; undefined when devx has to ask again. */
export async function readCachedCheck(now: number = Date.now()): Promise<UpdateCheckResult | undefined> {
  try {
    const saved: RememberedCheck = JSON.parse(await fs.readFile(checkFile(), 'utf8'));
    const age = now - Number(saved.checkedAt);
    // A clock that went backwards makes the age negative: such an answer is not trusted.
    if (!Number.isFinite(age) || age < 0) return undefined;
    const currentVersion = await getCurrentVersion();
    if (saved.error) {
      return age < CHECK_FAILED_MS ? { updateAvailable: false, currentVersion, latestVersion: currentVersion, error: saved.error } : undefined;
    }
    if (age >= CHECK_FRESH_MS || !saved.latestVersion) return undefined;
    // Judged against the version that runs now: after an update the old answer no longer says "update".
    return {
      updateAvailable: isNewerVersion(saved.latestVersion, currentVersion),
      currentVersion,
      latestVersion: saved.latestVersion,
      releaseNotes: saved.releaseNotes || undefined,
      publishedAt: saved.publishedAt || undefined,
    };
  } catch {
    return undefined;
  }
}

/** Keeps the answer of a check for the next start-ups. Never throws: this is only a shortcut. */
export async function rememberCheck(result: UpdateCheckResult, now: number = Date.now()): Promise<void> {
  try {
    const saved: RememberedCheck = result.error
      ? { checkedAt: now, error: result.error }
      : { checkedAt: now, latestVersion: result.latestVersion, releaseNotes: result.releaseNotes, publishedAt: result.publishedAt };
    await fs.mkdir(path.dirname(checkFile()), { recursive: true, mode: 0o700 });
    await fs.writeFile(checkFile(), JSON.stringify(saved));
  } catch {}
}

/** `checkForUpdates`, and the answer is kept for the next start-ups (the start-up check reads it, see readCachedCheck). */
export async function checkForUpdatesRemembered(timeoutMs = 10000): Promise<UpdateCheckResult> {
  const result = await checkForUpdates(timeoutMs);
  await rememberCheck(result);
  return result;
}

// ───────────────────────── installing ─────────────────────────

export type InstallMethod = 'npm' | 'git';

/** How this devx was installed: from the npm package, or from a git clone (a developer's copy). */
export async function detectInstallMethod(): Promise<{ method: InstallMethod; root: string }> {
  const here = fileURLToPath(import.meta.url);
  const root = path.resolve(path.dirname(here), '../../');
  // DEVX_UPDATE_METHOD forces one of them (a copy in a git folder that is installed with npm, a test).
  const forced = (process.env.DEVX_UPDATE_METHOD || '').toLowerCase();
  if (forced === 'npm' || forced === 'git') return { method: forced, root };
  try {
    await fs.stat(path.join(root, '.git'));
    return { method: 'git', root };
  } catch {
    return { method: 'npm', root };
  }
}

/** What a step of the update says when it fails. `not-published`: the version is on GitHub but not in npm yet. */
export class UpdateError extends Error {
  constructor(message: string, public kind: 'failed' | 'not-published' = 'failed') {
    super(message);
    this.name = 'UpdateError';
  }
}

export interface RunResult {
  code: number;
  /** The end of what the command wrote (stdout and stderr together). */
  output: string;
}

/** Runs a program and waits for it. Replaceable, so the steps can be tested without installing anything. */
export type CommandRunner = (command: string, args: string[], options?: { cwd?: string }) => Promise<RunResult>;

const OUTPUT_KEEP = 4000;

export const runCommand: CommandRunner = (command, args, options = {}) =>
  new Promise((resolve) => {
    let output = '';
    const keep = (chunk: Buffer) => {
      output = (output + chunk.toString('utf8')).slice(-OUTPUT_KEEP);
    };
    // On Windows npm is a .cmd file, which only a shell can start.
    const child = spawn(command, args, { cwd: options.cwd, shell: process.platform === 'win32', stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
    child.stdout?.on('data', keep);
    child.stderr?.on('data', keep);
    child.on('error', (err) => resolve({ code: -1, output: output + String(err.message) }));
    child.on('close', (code) => resolve({ code: code ?? -1, output }));
  });

export type StepId = 'registry' | 'install' | 'verify' | 'pull' | 'build' | 'link';

export interface UpdateStep {
  id: StepId;
  run: () => Promise<void>;
}

/** The last lines of a command's output, for the message of a failure. */
export function tail(output: string, lines = 4): string {
  return output
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean)
    .slice(-lines)
    .join('\n');
}

function must(result: RunResult, what: string): RunResult {
  if (result.code !== 0) throw new UpdateError(tail(result.output) || `${what} failed (exit code ${result.code})`);
  return result;
}

/**
 * The steps of an update, in order. Nothing is run until `run()` of a step is called.
 *
 *  npm: ask the registry whether the version is there (GitHub knows a release before npm has the
 *       package: installing `latest` then would "update" to the old version), install exactly that
 *       version, and read back which version is installed.
 *  git: pull, install and build, link the command.
 */
export function buildUpdateSteps(method: InstallMethod, root: string, version: string, run: CommandRunner = runCommand): UpdateStep[] {
  if (method === 'npm') {
    return [
      {
        id: 'registry',
        run: async () => {
          const res = await run('npm', ['view', `termux-dev@${version}`, 'version', '--prefer-online']);
          if (res.code !== 0) {
            // A version that is not in the registry is an E404, not an older answer.
            if (/E404|No match found/i.test(res.output)) throw new UpdateError(`termux-dev@${version} is not in the npm registry yet`, 'not-published');
            throw new UpdateError(tail(res.output) || `npm view failed (exit code ${res.code})`);
          }
          if (res.output.trim().split('\n').pop()?.trim() !== version) {
            throw new UpdateError(`termux-dev@${version} is not in the npm registry yet`, 'not-published');
          }
        },
      },
      {
        id: 'install',
        run: async () => {
          must(await run('npm', ['install', '-g', `termux-dev@${version}`, '--prefer-online']), 'npm install');
        },
      },
      {
        id: 'verify',
        run: async () => {
          const res = await run('npm', ['ls', '-g', 'termux-dev', '--depth=0', '--json']);
          let installed = '';
          try {
            installed = JSON.parse(res.output.slice(res.output.indexOf('{')))?.dependencies?.['termux-dev']?.version ?? '';
          } catch {}
          if (installed !== version) throw new UpdateError(`npm reports ${installed || 'no version'} instead of ${version}`);
        },
      },
    ];
  }
  return [
    { id: 'pull', run: async () => void must(await run('git', ['pull', '--ff-only', 'origin', 'main'], { cwd: root }), 'git pull') },
    {
      id: 'build',
      run: async () => {
        must(await run('npm', ['install'], { cwd: root }), 'npm install');
        must(await run('npm', ['run', 'build'], { cwd: root }), 'npm run build');
      },
    },
    // Linking is a convenience: a failure here does not undo the update.
    { id: 'link', run: async () => void (await run('npm', ['link'], { cwd: root })) },
  ];
}

export interface StepEvents {
  onStart?: (index: number) => void;
  onDone?: (index: number, ms: number) => void;
}

export type UpdateOutcome = { ok: true } | { ok: false; index: number; error: UpdateError };

/** Runs the steps one after the other and stops at the first that fails. */
export async function runUpdateSteps(steps: UpdateStep[], events: StepEvents = {}, now: () => number = Date.now): Promise<UpdateOutcome> {
  for (let index = 0; index < steps.length; index++) {
    events.onStart?.(index);
    const started = now();
    try {
      await steps[index].run();
    } catch (err: any) {
      return { ok: false, index, error: err instanceof UpdateError ? err : new UpdateError(String(err?.message || err)) };
    }
    events.onDone?.(index, now() - started);
  }
  return { ok: true };
}

/** Starts devx again, with the same arguments, and ends this process when the new one ends. */
export function restartDevx(): void {
  const child = spawn(process.argv[0], process.argv.slice(1), { stdio: 'inherit' });
  child.on('exit', (code) => process.exit(code ?? 0));
  child.on('error', () => process.exit(0));
}
