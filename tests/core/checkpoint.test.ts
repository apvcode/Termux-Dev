import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { execFileSync } from 'child_process';
import fs from 'fs/promises';
import fsSync from 'fs';
import os from 'os';
import path from 'path';
import { createCheckpoint, diffCheckpoint, restoreCheckpoint } from '../../src/core/checkpoint.js';

const hasGit = (() => { try { execFileSync('git', ['--version'], { stdio: 'ignore' }); return true; } catch { return false; } })();
const git = (cwd: string, ...args: string[]) => execFileSync('git', args, { cwd, encoding: 'utf8', env: { ...process.env, GIT_AUTHOR_NAME: 't', GIT_AUTHOR_EMAIL: 't@t', GIT_COMMITTER_NAME: 't', GIT_COMMITTER_EMAIL: 't@t' } });

let dir: string;
beforeEach(async () => { dir = await fs.mkdtemp(path.join(os.tmpdir(), 'devx-cp-')); });
afterEach(async () => { await fs.rm(dir, { recursive: true, force: true }); });

describe.skipIf(!hasGit)('git checkpoints', () => {
  const repo = async () => {
    git(dir, 'init', '-q');
    await fs.writeFile(path.join(dir, 'a.txt'), 'one\n');
    await fs.writeFile(path.join(dir, 'keep.txt'), 'keep\n');
    await fs.writeFile(path.join(dir, '.gitignore'), 'ignored.log\n');
    git(dir, 'add', '-A');
    git(dir, 'commit', '-qm', 'init');
  };

  it('is not available outside a git repository', async () => {
    expect(await createCheckpoint(dir)).toBeNull();
  });

  it('sees nothing changed right after a snapshot', async () => {
    await repo();
    const cp = (await createCheckpoint(dir))!;
    expect(cp.sha).toMatch(/^[0-9a-f]{40}$/);
    expect(await diffCheckpoint(cp)).toEqual({ modified: [], created: [], deleted: [] });
  });

  it('puts back what a shell command did: edited, created and deleted files, also in new folders', async () => {
    await repo();
    await fs.writeFile(path.join(dir, 'dirty.txt'), 'uncommitted work\n');       // not committed: part of the snapshot too
    const cp = (await createCheckpoint(dir))!;

    await fs.writeFile(path.join(dir, 'a.txt'), 'changed by the agent\n');
    await fs.rm(path.join(dir, 'keep.txt'));
    await fs.mkdir(path.join(dir, 'new', 'deep'), { recursive: true });
    await fs.writeFile(path.join(dir, 'new', 'deep', 'made.txt'), 'x');
    await fs.writeFile(path.join(dir, 'dirty.txt'), 'overwritten\n');
    await fs.writeFile(path.join(dir, 'ignored.log'), 'log');                     // ignored files are not touched

    const diff = await diffCheckpoint(cp);
    expect(diff.modified.sort()).toEqual(['a.txt', 'dirty.txt']);
    expect(diff.deleted).toEqual(['keep.txt']);
    expect(diff.created).toEqual(['new/deep/made.txt']);

    await restoreCheckpoint(cp, diff);
    expect(await fs.readFile(path.join(dir, 'a.txt'), 'utf8')).toBe('one\n');
    expect(await fs.readFile(path.join(dir, 'keep.txt'), 'utf8')).toBe('keep\n');
    expect(await fs.readFile(path.join(dir, 'dirty.txt'), 'utf8')).toBe('uncommitted work\n');
    expect(fsSync.existsSync(path.join(dir, 'new'))).toBe(false);                 // the empty folders went too
    expect(fsSync.existsSync(path.join(dir, 'ignored.log'))).toBe(true);
    expect(await diffCheckpoint(cp)).toEqual({ modified: [], created: [], deleted: [] });
  });

  it('never touches the index, the branch or the stash of the user', async () => {
    await repo();
    await fs.writeFile(path.join(dir, 'staged.txt'), 's');
    git(dir, 'add', 'staged.txt');
    const before = { status: git(dir, 'status', '--porcelain'), head: git(dir, 'rev-parse', 'HEAD'), stash: git(dir, 'stash', 'list') };
    const cp = (await createCheckpoint(dir))!;
    await fs.writeFile(path.join(dir, 'a.txt'), 'x');
    await restoreCheckpoint(cp);
    expect(git(dir, 'rev-parse', 'HEAD')).toBe(before.head);
    expect(git(dir, 'stash', 'list')).toBe(before.stash);
    expect(git(dir, 'status', '--porcelain')).toBe(before.status);
    expect(git(dir, 'rev-parse', 'refs/devx/checkpoint').trim()).toBe(cp.sha);
  });

  it('snapshots chain: the earlier one stays reachable and can be restored', async () => {
    await repo();
    const first = (await createCheckpoint(dir))!;
    await fs.writeFile(path.join(dir, 'a.txt'), 'turn 1\n');
    const second = (await createCheckpoint(dir, first.sha))!;
    await fs.writeFile(path.join(dir, 'a.txt'), 'turn 2\n');
    await restoreCheckpoint(second);
    expect(await fs.readFile(path.join(dir, 'a.txt'), 'utf8')).toBe('turn 1\n');
    await restoreCheckpoint(first);
    expect(await fs.readFile(path.join(dir, 'a.txt'), 'utf8')).toBe('one\n');
  });

  it('keeps file names with spaces and the executable bit', async () => {
    await repo();
    await fs.writeFile(path.join(dir, 'run it.sh'), '#!/bin/sh\n', { mode: 0o755 });
    const cp = (await createCheckpoint(dir))!;
    await fs.rm(path.join(dir, 'run it.sh'));
    await restoreCheckpoint(cp);
    expect(await fs.readFile(path.join(dir, 'run it.sh'), 'utf8')).toBe('#!/bin/sh\n');
    if (process.platform !== 'win32') expect(fsSync.statSync(path.join(dir, 'run it.sh')).mode & 0o111).toBeTruthy();
  });
});
