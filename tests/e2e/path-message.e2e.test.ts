import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { FakeProvider, KEY, Sandbox, canRunE2E, makeSandbox, runDevx, startFakeProvider } from './helpers.js';

// A message that begins with a path (/home/me/boot.img ...) goes to the model; it is not taken for a command.
describe.skipIf(!canRunE2E)('a message that begins with a path', () => {
  let provider: FakeProvider;
  let box: Sandbox;
  beforeEach(async () => { provider = await startFakeProvider(0); box = await makeSandbox(provider); });
  afterEach(async () => { await provider.close(); await box.cleanup(); });

  it('is sent to the model and opens no command list', async () => {
    const text = '/home/mega/Downloads/recovery_k4.19.img flash it';
    const r = await runDevx(box, [
      { wait: 'Ask or describe', timeout: 30 },
      // typed in small pieces: one big chunk would be taken for a paste
      ...Array.from(text).reduce<string[]>((parts, ch, i) => { if (i % 4 === 0) parts.push(''); parts[parts.length - 1] += ch; return parts; }, []).map((send) => ({ ms: 40, send })),
      { ms: 300, send: KEY.enter },
      { wait: 'FAKE-ANSWER', timeout: 20, send: KEY.ctrlC },
      { wait: 'Exit devx', timeout: 10, send: KEY.up },
      { ms: 200, send: KEY.enter },
    ], { exitWait: 6 });
    expect(r.plain).not.toContain('type to find a command');
    expect(r.plain).not.toContain('Unknown command');
    expect(JSON.stringify(provider.requests[0].messages)).toContain('recovery_k4.19.img flash it');
  }, 90000);
});
