import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { FakeProvider, KEY, Sandbox, canRunE2E, makeSandbox, runDevx, startFakeProvider } from './helpers.js';

// A long message wraps onto more rows in the prompt frame (it does not scroll sideways), at the
// prompt and while the agent works, and arrives whole.
describe.skipIf(!canRunE2E)('a long message in the prompt frame', () => {
  let provider: FakeProvider;
  let box: Sandbox;
  beforeEach(async () => { provider = await startFakeProvider(0); box = await makeSandbox(provider); });
  afterEach(async () => { await provider.close(); await box.cleanup(); });

  const long = Array.from({ length: 40 }, (_, i) => `slovo${i}`).join(' ');   // 40 words, far wider than a row
  // Typed in small pieces: one big chunk would be taken for a paste and shown as a tag.
  const typing = Array.from({ length: 20 }, (_, i) => ({ ms: 40, send: `slovo${2 * i} slovo${2 * i + 1}${i < 19 ? ' ' : ''}` }));

  it('draws continuation rows at the prompt, moves with the arrows and sends the whole text', async () => {
    const r = await runDevx(box, [
      { wait: 'Ask or describe', timeout: 30 },
      ...typing,
      { ms: 400, send: KEY.up },                                   // one row up inside the text, not the history
      { ms: 300, send: KEY.down },
      { ms: 300, send: KEY.enter },
      { wait: 'FAKE-ANSWER', timeout: 20, send: KEY.ctrlC },
      { wait: 'Exit devx', timeout: 10, send: KEY.up },
      { ms: 200, send: KEY.enter },
    ], { exitWait: 6 });
    expect(r.exited).toBe(true);
    // a continuation row: the border, three spaces where the marker would be, then text
    expect(r.plain).toMatch(/│ {3}\S/);
    expect(r.plain).not.toContain('…  slovo');                      // nothing was cut off at the left
    expect(JSON.stringify(provider.requests[0].messages)).toContain(long);
  }, 90000);

  it('while the agent works the typed text wraps too and is queued whole', async () => {
    await provider.close();
    provider = await startFakeProvider(4000);
    await box.cleanup();
    box = await makeSandbox(provider);
    const r = await runDevx(box, [
      { wait: 'Ask or describe', timeout: 30 },
      { ms: 300, send: 'first task' },
      { ms: 200, send: KEY.enter },
      { wait: 'ctrl\\+c to stop', timeout: 10 },
      ...typing,
      { ms: 500, send: KEY.enter },
      { wait: 'queued:', timeout: 10 },
      { wait: 'FAKE-ANSWER', timeout: 30 },
      { ms: 6000 },
      { send: KEY.ctrlC },
      { wait: 'Exit devx', timeout: 10, send: KEY.up },
      { ms: 200, send: KEY.enter },
    ], { exitWait: 6 });
    expect(r.exited).toBe(true);
    expect(r.plain).toMatch(/│ {3}\S/);
    const all = JSON.stringify(provider.requests.map((q) => q.messages));
    expect(all).toContain(long);
  }, 120000);
});
