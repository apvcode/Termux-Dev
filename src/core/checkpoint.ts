import { execFile } from 'child_process';
import fs from 'fs/promises';
import os from 'os';
import path from 'path';

/**
 * A snapshot of the whole working folder before each turn of the agent, taken with git itself
 * (a throw-away index, so the user's index, branches and stashes are never touched). `/undo` can
 * then put back everything that changed during the turn, also what the shell commands of the
 * agent did: files edited, created and deleted. Ignored files (.gitignore) are not part of it.
 *
 * Snapshots are commits chained under `refs/devx/checkpoint`, so git keeps them; they can be
 * removed with `git update-ref -d refs/devx/checkpoint`.
 */

export interface Checkpoint {
  /** The commit that holds the snapshot. */
  sha: string;
  /** The folder of the repository (the top level). */
  root: string;
}

export interface CheckpointDiff {
  /** Changed since the snapshot. */
  modified: string[];
  /** Created since the snapshot (restoring deletes them). */
  created: string[];
  /** Existed in the snapshot and are gone now. */
  deleted: string[];
}

const GIT_TIMEOUT_MS = 10000;
const REF = 'refs/devx/checkpoint';

function git(cwd: string, args: string[], env: NodeJS.ProcessEnv = {}): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    execFile('git', args, { cwd, env: { ...process.env, ...env }, timeout: GIT_TIMEOUT_MS, maxBuffer: 256 * 1024 * 1024, encoding: 'buffer' }, (err, stdout) => {
      if (err) reject(err);
      else resolve(stdout as unknown as Buffer);
    });
  });
}

/** The tree of the working folder as it is now, written through a temporary index. */
async function snapshotTree(root: string): Promise<string> {
  const indexFile = path.join(os.tmpdir(), `devx-index-${process.pid}-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`);
  const env = { GIT_INDEX_FILE: indexFile };
  try {
    await git(root, ['add', '-A', '--', '.'], env);
    return (await git(root, ['write-tree'], env)).toString().trim();
  } finally {
    await fs.rm(indexFile, { force: true }).catch(() => {});
  }
}

/** Takes a snapshot, or returns null when the folder is not in a git repository or git is too slow or missing. */
export async function createCheckpoint(cwd: string, parent?: string): Promise<Checkpoint | null> {
  try {
    const root = (await git(cwd, ['rev-parse', '--show-toplevel'])).toString().trim();
    if (!root) return null;
    const tree = await snapshotTree(root);
    const author = { GIT_AUTHOR_NAME: 'devx', GIT_AUTHOR_EMAIL: 'devx@localhost', GIT_COMMITTER_NAME: 'devx', GIT_COMMITTER_EMAIL: 'devx@localhost' };
    const sha = (await git(root, ['commit-tree', tree, ...(parent ? ['-p', parent] : []), '-m', 'devx checkpoint'], author)).toString().trim();
    await git(root, ['update-ref', REF, sha]);
    return { sha, root };
  } catch {
    return null;
  }
}

/** What changed in the working folder since the snapshot. */
export async function diffCheckpoint(cp: Checkpoint): Promise<CheckpointDiff> {
  const now = await snapshotTree(cp.root);
  const out = (await git(cp.root, ['diff-tree', '-r', '--name-status', '-z', '--no-renames', cp.sha, now])).toString('utf8');
  const parts = out.split('\0').filter((p) => p.length > 0);
  const diff: CheckpointDiff = { modified: [], created: [], deleted: [] };
  for (let i = 0; i + 1 < parts.length; i += 2) {
    const status = parts[i];
    const file = parts[i + 1];
    if (status === 'A') diff.created.push(file);
    else if (status === 'D') diff.deleted.push(file);
    else diff.modified.push(file);
  }
  return diff;
}

/** Puts the folder back to the snapshot: changed and deleted files come back, files created since are removed. */
export async function restoreCheckpoint(cp: Checkpoint, diff?: CheckpointDiff): Promise<CheckpointDiff> {
  const changes = diff ?? (await diffCheckpoint(cp));
  for (const file of [...changes.modified, ...changes.deleted]) {
    const target = path.join(cp.root, file);
    const content = await git(cp.root, ['cat-file', 'blob', `${cp.sha}:${file}`]);
    const mode = (await git(cp.root, ['ls-tree', cp.sha, '--', file])).toString().split(' ')[0];
    await fs.mkdir(path.dirname(target), { recursive: true });
    await fs.writeFile(target, content);
    if (mode === '100755') await fs.chmod(target, 0o755).catch(() => {});
  }
  for (const file of changes.created) {
    await fs.rm(path.join(cp.root, file), { force: true });
    // An empty folder left by it goes too (up to the repository root).
    let dir = path.dirname(path.join(cp.root, file));
    while (dir.startsWith(cp.root + path.sep)) {
      try { await fs.rmdir(dir); } catch { break; }
      dir = path.dirname(dir);
    }
  }
  return changes;
}
