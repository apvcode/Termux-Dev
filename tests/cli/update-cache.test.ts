import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import fs from 'fs/promises';
import os from 'os';
import path from 'path';

let home: string;
const H = 60 * 60 * 1000;

async function load() {
  vi.resetModules();
  return import('../../src/cli/updater.js');
}
const file = () => path.join(home, '.devx', 'update-check.json');

beforeEach(async () => {
  home = await fs.mkdtemp(path.join(os.tmpdir(), 'devx-upd-'));
  vi.stubEnv('HOME', home);
  vi.stubEnv('USERPROFILE', home);
});
afterEach(async () => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
  await fs.rm(home, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
});

describe('the remembered update check', () => {
  it('has nothing before the first check', async () => {
    const { readCachedCheck } = await load();
    expect(await readCachedCheck()).toBeUndefined();
  });

  it('is used for six hours, judged against the version that runs now', async () => {
    const { readCachedCheck, rememberCheck, getCurrentVersion } = await load();
    const current = await getCurrentVersion();
    const now = Date.now();
    await rememberCheck({ updateAvailable: true, currentVersion: '0.0.1', latestVersion: '99.0.0', releaseNotes: 'notes', publishedAt: '2026-01-01' }, now);
    expect(await readCachedCheck(now + 5 * H)).toMatchObject({ updateAvailable: true, currentVersion: current, latestVersion: '99.0.0', releaseNotes: 'notes' });
    expect(await readCachedCheck(now + 7 * H)).toBeUndefined();

    // After an update the old answer no longer says "update".
    await rememberCheck({ updateAvailable: true, currentVersion: '0.0.1', latestVersion: current }, now);
    expect((await readCachedCheck(now + H))!.updateAvailable).toBe(false);
  });

  it('keeps a failed check for thirty minutes only, so an offline start-up is not slow every time', async () => {
    const { readCachedCheck, rememberCheck } = await load();
    const now = Date.now();
    await rememberCheck({ updateAvailable: false, currentVersion: '1', latestVersion: '1', error: 'Timeout (6s)' }, now);
    expect(await readCachedCheck(now + 10 * 60 * 1000)).toMatchObject({ updateAvailable: false, error: 'Timeout (6s)' });
    expect(await readCachedCheck(now + 40 * 60 * 1000)).toBeUndefined();
  });

  it('does not trust an answer from the future (the clock went back) or a broken file', async () => {
    const { readCachedCheck, rememberCheck } = await load();
    await rememberCheck({ updateAvailable: false, currentVersion: '1', latestVersion: '1.0.0' }, Date.now() + 10 * H);
    expect(await readCachedCheck()).toBeUndefined();
    await fs.writeFile(file(), '{nope');
    expect(await readCachedCheck()).toBeUndefined();
  });

  it('is written by the start-up check, and the two requests go out together', async () => {
    const { checkForUpdatesRemembered, readCachedCheck } = await load();
    const started: string[] = [];
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      started.push(String(url).includes('api.github.com') ? 'release' : 'package');
      await gate;
      return String(url).includes('api.github.com')
        ? { ok: true, json: async () => ({ tag_name: 'v99.1.0', body: 'the notes', published_at: '2026-02-02' }) }
        : { ok: true, json: async () => ({ version: '99.0.0' }) };
    }));
    const pending = checkForUpdatesRemembered(5000);
    await new Promise((r) => setTimeout(r, 20));
    // Both are in flight before either has answered.
    expect(started.sort()).toEqual(['package', 'release']);
    release();
    const result = await pending;
    expect(result).toMatchObject({ updateAvailable: true, latestVersion: '99.1.0', releaseNotes: 'the notes' });
    expect(await readCachedCheck()).toMatchObject({ updateAvailable: true, latestVersion: '99.1.0' });
  });

  it('a failing release request does not fail the check', async () => {
    const { checkForUpdates } = await load();
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (String(url).includes('api.github.com')) throw new Error('rate limited');
      return { ok: true, json: async () => ({ version: '99.0.0' }) };
    }));
    expect(await checkForUpdates(5000)).toMatchObject({ updateAvailable: true, latestVersion: '99.0.0' });
  });

  it('at start-up a fresh answer means no request, no line on the screen and no wait', async () => {
    const updater = await load();
    const { runStartupUpdateCheck } = await import('../../src/cli/update-screen.js');
    await updater.rememberCheck({ updateAvailable: false, currentVersion: '1', latestVersion: await updater.getCurrentVersion() });
    const fetchSpy = vi.fn();
    vi.stubGlobal('fetch', fetchSpy);
    let written = '';
    const out: any = { isTTY: true, columns: 80, write: (s: string) => { written += s; return true; } };
    const t0 = Date.now();
    await runStartupUpdateCheck({}, { output: out });
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(written).toBe('');
    expect(Date.now() - t0).toBeLessThan(300);
  });
});
