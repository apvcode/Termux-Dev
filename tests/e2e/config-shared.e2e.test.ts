import fs from 'node:fs/promises';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { FakeProvider, KEY, Sandbox, canRunE2E, makeSandbox, runDevx, startFakeProvider } from './helpers.js';

// Two devx windows share ~/.devxrc.json. A save in one window must not undo what the other one
// wrote to the file meanwhile, and the model chosen for one run with -m must not become the default.
describe.skipIf(!canRunE2E)('devx config shared by several windows', () => {
  let provider: FakeProvider;
  let box: Sandbox;
  beforeEach(async () => { provider = await startFakeProvider(0); box = await makeSandbox(provider); });
  afterEach(async () => { await provider.close(); await box.cleanup(); });

  it('a save keeps what another window wrote, and does not store the -m model', async () => {
    const file = path.join(box.home, '.devxrc.json');
    // "The other window": while this one runs it changes the default model and another setting.
    const other = setTimeout(async () => {
      const cfg = JSON.parse(await fs.readFile(file, 'utf8'));
      await fs.writeFile(file, JSON.stringify({ ...cfg, model: 'deepseek-from-other-window', maxIterations: 7 }));
    }, 4000);

    const r = await runDevx(box, [
      { wait: 'Ask or describe', timeout: 30 },
      { ms: 7000 },                                  // by now the other window has written
      { send: '/theme nord' },
      { ms: 200, send: KEY.enter },
      { ms: 1500 },
      { send: KEY.ctrlC },
      { wait: 'Exit devx', timeout: 10, send: KEY.up },
      { ms: 200, send: KEY.enter },
    ], { exitWait: 6, args: ['-m', 'kimi-for-this-run'] });
    clearTimeout(other);

    expect(r.exited).toBe(true);
    const saved = JSON.parse(await fs.readFile(file, 'utf8'));
    expect(saved.theme).toBe('nord');                          // this window's change is saved
    expect(saved.model).toBe('deepseek-from-other-window');    // the other window's default is kept, -m is not stored
    expect(saved.maxIterations).toBe(7);
    expect(saved.apiKeys).toEqual({ custom: 'k' });
    expect((await fs.readdir(box.home)).filter((f) => f.endsWith('.tmp'))).toEqual([]);
    if (process.platform !== 'win32') expect((await fs.stat(file)).mode & 0o777).toBe(0o600);
  }, 90000);
});
