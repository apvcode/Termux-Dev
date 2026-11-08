import { describe, expect, it } from 'vitest';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { CommandRunner, UpdateError, buildUpdateSteps, isNewerVersion, runUpdateSteps, tail } from '../../src/cli/updater.js';

describe('versions', () => {
  it('compares them number by number', () => {
    expect(isNewerVersion('1.9.1', '1.9.0')).toBe(true);
    expect(isNewerVersion('1.10.0', '1.9.9')).toBe(true);
    expect(isNewerVersion('v2.0.0', '1.99.99')).toBe(true);
    expect(isNewerVersion('1.9.0', '1.9.0')).toBe(false);
    expect(isNewerVersion('1.8.9', '1.9.0')).toBe(false);
  });
});

describe('tail', () => {
  it('keeps the last non-empty lines', () => {
    expect(tail('a\n\n b \nc\nd\ne\n', 3)).toBe('c\nd\ne');
    expect(tail('')).toBe('');
  });
});

describe('the steps of an update', () => {
  const never: CommandRunner = async () => { throw new Error('must not run yet'); };

  it('are built without running anything', () => {
    expect(buildUpdateSteps('npm', '/r', '1.9.1', never).map((s) => s.id)).toEqual(['registry', 'install', 'verify']);
    expect(buildUpdateSteps('git', '/r', '1.9.1', never).map((s) => s.id)).toEqual(['pull', 'build', 'link']);
  });

  it('stop at the first failure and say which step it was, in order, with their times', async () => {
    const ran: string[] = [];
    const run: CommandRunner = async (command, args) => {
      ran.push(`${command} ${args.join(' ')}`);
      return args[0] === 'install' ? { code: 1, output: 'boom\nlast line' } : { code: 0, output: '1.9.1' };
    };
    const events: string[] = [];
    let clock = 0;
    const outcome = await runUpdateSteps(
      buildUpdateSteps('npm', '/r', '1.9.1', run),
      { onStart: (i) => events.push(`start ${i}`), onDone: (i, ms) => events.push(`done ${i} ${ms}`) },
      () => (clock += 100)
    );
    expect(outcome).toMatchObject({ ok: false, index: 1 });
    if (!outcome.ok) expect(outcome.error.message).toBe('boom\nlast line');
    expect(events).toEqual(['start 0', 'done 0 100', 'start 1']);
    expect(ran).toHaveLength(2);
  });

  it('a registry that has an older version is told apart from other failures', async () => {
    const run: CommandRunner = async () => ({ code: 0, output: '1.9.0\n' });
    const outcome = await runUpdateSteps(buildUpdateSteps('npm', '/r', '1.9.1', run));
    expect(outcome.ok).toBe(false);
    if (!outcome.ok) {
      expect(outcome.error).toBeInstanceOf(UpdateError);
      expect(outcome.error.kind).toBe('not-published');
    }
  });

  it('a version npm answers E404 for is "not published yet" too; other npm failures are plain failures', async () => {
    const notThere: CommandRunner = async () => ({ code: 1, output: 'npm error code E404\nnpm error 404 No match found for version 1.9.1' });
    const a = await runUpdateSteps(buildUpdateSteps('npm', '/r', '1.9.1', notThere));
    expect(a.ok).toBe(false);
    if (!a.ok) expect(a.error.kind).toBe('not-published');
    const offline: CommandRunner = async () => ({ code: 1, output: 'npm error code ENOTFOUND\nnpm error network request failed' });
    const b = await runUpdateSteps(buildUpdateSteps('npm', '/r', '1.9.1', offline));
    expect(b.ok).toBe(false);
    if (!b.ok) {
      expect(b.error.kind).toBe('failed');
      expect(b.error.message).toContain('ENOTFOUND');
    }
  });

  it('turn an error thrown by a step into the same kind of failure', async () => {
    const outcome = await runUpdateSteps([{ id: 'pull', run: async () => { throw new Error('spawn git ENOENT'); } }]);
    expect(outcome).toMatchObject({ ok: false, index: 0 });
    if (!outcome.ok) expect(outcome.error.message).toBe('spawn git ENOENT');
  });

  it('succeed when every step does', async () => {
    const run: CommandRunner = async (_c, args) =>
      args[0] === 'ls' ? { code: 0, output: JSON.stringify({ dependencies: { 'termux-dev': { version: '1.9.1' } } }) } : { code: 0, output: '1.9.1' };
    expect(await runUpdateSteps(buildUpdateSteps('npm', '/r', '1.9.1', run))).toEqual({ ok: true });
  });
});

describe('restartDevx', () => {
  const dist = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', 'dist', 'cli', 'updater.js');

  it.skipIf(!existsSync(dist))('starts the new devx, stops reading the keyboard itself and ends with the code of the new one', () => {
    const dir = mkdtempSync(path.join(os.tmpdir(), 'devx-restart-'));
    try {
      const child = path.join(dir, 'child.mjs');
      writeFileSync(child, "console.log('NEW-DEVX-RAN'); process.exit(7);");
      const parent = path.join(dir, 'parent.mjs');
      writeFileSync(parent, `
        import { restartDevx } from ${JSON.stringify(pathToFileURL(dist).href)};
        process.stdin.resume();
        process.argv = [process.argv[0], ${JSON.stringify(child)}];
        restartDevx();
        console.log('PARENT-PAUSED:' + process.stdin.isPaused() + ' SIGINT-HANDLERS:' + process.listenerCount('SIGINT'));
      `);
      const run = spawnSync(process.execPath, [parent], { encoding: 'utf8', input: '' });
      expect(run.stdout).toContain('NEW-DEVX-RAN');
      expect(run.stdout).toContain('PARENT-PAUSED:true');
      expect(run.status).toBe(7);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});
