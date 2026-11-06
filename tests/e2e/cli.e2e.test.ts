import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { FakeProvider, KEY, Sandbox, canRunE2E, makeSandbox, runDevx, startFakeProvider } from './helpers.js';

// End-to-end: the real CLI (bin/devx.js, built into dist) in a real pseudo-terminal, talking to a fake
// provider. Everything unit tests cannot see: keys, redraws, the process ending. Needs `npm run build`
// and python3; it is skipped where there is no python3 (Windows).
describe.skipIf(!canRunE2E)('devx in a terminal', () => {
  let provider: FakeProvider;
  let box: Sandbox;
  beforeEach(async () => { provider = await startFakeProvider(0); box = await makeSandbox(provider); });
  afterEach(async () => { await provider.close(); await box.cleanup(); });

  it('starts, answers a message and ends cleanly after the exit question', async () => {
    const r = await runDevx(box, [
      { wait: 'Ask or describe', timeout: 30 },
      { ms: 300, send: 'hello there' },                                      // text and Enter apart: one chunk with a newline is a paste
      { ms: 200, send: KEY.enter },
      { wait: 'FAKE-ANSWER', timeout: 20, send: KEY.ctrlC },               // (the stream is not a screen: words are interleaved with redraws, so wait for one word)
      { wait: 'Exit devx', timeout: 10, send: KEY.up },                      // from "No" to "Yes"
      { ms: 200, send: KEY.enter },
    ], { exitWait: 6 });
    expect(r.steps.map((s) => s.ok)).toEqual([true, true, true, true, true, true]);
    expect(r.plain).toContain('FAKE-ANSWER');
    expect(JSON.stringify(provider.requests[0].messages)).toContain('hello there');
    // the second request is the one that names the chat
    expect(JSON.stringify(provider.requests[1]?.messages ?? '')).toContain('You name chats');
    expect(r.plain).toContain('Goodbye');
    expect(r.exited, 'the process must end after the exit is confirmed').toBe(true);
    expect(r.code).toBe(0);
    expect(r.output).toContain('\x1b[23;0t');          // the title the terminal had is given back
  }, 90000);

  it('"No" keeps devx running', async () => {
    const r = await runDevx(box, [
      { wait: 'Ask or describe', timeout: 30, send: KEY.ctrlC },
      { wait: 'Exit devx', timeout: 10, send: KEY.enter },                 // the default is "No"
      { wait: 'Ask or describe', timeout: 10, ms: 200 },
    ], { exitWait: 1 });
    expect(r.steps.map((s) => s.ok)).toEqual([true, true, true]);
    expect(r.plain).not.toContain('Goodbye');
    expect(r.exited).toBe(false);
  }, 60000);

  it('a message typed while the agent works waits in the queue, and the down arrow takes it back to edit', async () => {
    await provider.close();
    provider = await startFakeProvider(5000);                         // a slow answer: time to type
    await box.cleanup();
    box = await makeSandbox(provider);
    const r = await runDevx(box, [
      { wait: 'Ask or describe', timeout: 30 },
      { ms: 300, send: 'first task' },
      { ms: 200, send: KEY.enter },
      { wait: 'ctrl\\+c to stop', timeout: 10 },                      // the agent is working
      { ms: 300, send: 'second thought' },
      { ms: 200, send: KEY.enter },
      { wait: 'queued: second thought', timeout: 10 },
      { ms: 200, send: KEY.down },
      { wait: '❯ second thought', timeout: 10 },                        // back in the input line
    ], { exitWait: 1 });
    expect(r.steps.map((s) => s.ok)).toEqual([true, true, true, true, true, true, true, true, true]);
    expect(r.plain).toContain('ctrl+enter sends now');                 // the hint next to the waiting message
  }, 90000);

  it('stops the task when the session reaches its cost limit, and asks before the next message', async () => {
    await box.cleanup();
    // 15 tokens at a million dollars per million tokens: the first answer already costs $15
    box = await makeSandbox(provider, { maxCostPerSession: 1, prices: { fake: { input: 1000000, output: 1000000 } } });
    const r = await runDevx(box, [
      { wait: 'Ask or describe', timeout: 30 },
      { ms: 300, send: 'expensive question' },
      { ms: 200, send: KEY.enter },
      { wait: 'Cost limit of', timeout: 20 },
      { wait: 'Ask or describe', timeout: 10 },
      { ms: 300, send: 'one more' },
      { ms: 200, send: KEY.enter },
      { wait: 'Spend more\\?', timeout: 10, send: KEY.enter },          // the default is "No"
      { wait: 'back in the input line', timeout: 10 },
    ], { exitWait: 1 });
    expect(r.steps.map((s) => s.ok)).toEqual([true, true, true, true, true, true, true, true, true]);
    expect(provider.requests.filter((q) => JSON.stringify(q.messages).includes('one more'))).toHaveLength(0);   // nothing was sent
  }, 90000);
});
