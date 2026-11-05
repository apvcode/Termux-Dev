import { afterEach, describe, expect, it } from 'vitest';
import fs from 'fs/promises';
import os from 'os';
import path from 'path';
import { ensureSecureDir, writeSecureFile } from '../../src/core/secure-file.js';
import { checkForUpdates } from '../../src/cli/updater.js';
import { vi } from 'vitest';

const posix = process.platform !== 'win32';
const dirs: string[] = [];
afterEach(async () => {
  vi.unstubAllGlobals();
  await Promise.all(dirs.splice(0).map(d => fs.rm(d, { recursive: true, force: true })));
});

const tmp = async () => {
  const d = await fs.mkdtemp(path.join(os.tmpdir(), 'devx-secure-'));
  dirs.push(d);
  return d;
};

describe.skipIf(!posix)('secure file helpers', () => {
  it('creates secret files readable by the owner only', async () => {
    const file = path.join(await tmp(), 'config.json');
    await writeSecureFile(file, '{"apiKey":"sk-test"}');
    expect((await fs.stat(file)).mode & 0o777).toBe(0o600);
    expect(await fs.readFile(file, 'utf8')).toBe('{"apiKey":"sk-test"}');
  });

  it('tightens permissions of a file that already exists with a loose mode', async () => {
    const file = path.join(await tmp(), 'config.json');
    await fs.writeFile(file, 'old', { mode: 0o644 });
    await fs.chmod(file, 0o644);
    await writeSecureFile(file, 'new');
    expect((await fs.stat(file)).mode & 0o777).toBe(0o600);
  });

  it('creates private directories and tightens existing ones', async () => {
    const dir = path.join(await tmp(), 'sessions');
    await fs.mkdir(dir, { mode: 0o755 });
    await fs.chmod(dir, 0o755);
    await ensureSecureDir(dir);
    expect((await fs.stat(dir)).mode & 0o777).toBe(0o700);
    const nested = path.join(dir, 'a', 'b');
    await ensureSecureDir(nested);
    expect((await fs.stat(nested)).mode & 0o777).toBe(0o700);
  });
});

describe('update check', () => {
  it('gives up when the second request stalls instead of blocking startup forever', async () => {
    let call = 0;
    vi.stubGlobal('fetch', vi.fn((_url: string, init?: { signal?: AbortSignal }) => {
      call++;
      if (call === 1) {
        return Promise.resolve(new Response(JSON.stringify({ version: '1.6.0' }), { status: 200 }));
      }
      // the releases API accepts the connection and then never answers
      return new Promise((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => reject(init.signal!.reason ?? new DOMException('aborted', 'AbortError')));
      });
    }));

    const started = Date.now();
    const result = await checkForUpdates(400);
    expect(Date.now() - started).toBeLessThan(3000);
    expect(call).toBe(2);
    expect(result.updateAvailable).toBe(false);
  });

  it('reports a timeout when the first request stalls', async () => {
    vi.stubGlobal('fetch', vi.fn((_url: string, init?: { signal?: AbortSignal }) =>
      new Promise((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => reject(init.signal!.reason));
      })
    ));
    const result = await checkForUpdates(300);
    expect(result.error).toMatch(/Timeout/);
  });
});
