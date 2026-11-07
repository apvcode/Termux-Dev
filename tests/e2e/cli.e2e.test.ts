import fs from 'node:fs/promises';
import path from 'node:path';
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
      { wait: 'esc to stop', timeout: 10 },                           // the agent is working
      { ms: 300, send: 'second thought' },
      { ms: 200, send: KEY.enter },
      { wait: 'queued: second thought', timeout: 10 },
      { ms: 200, send: KEY.down },
      { wait: '❯ second thought', timeout: 10 },                        // back in the input line
    ], { exitWait: 1 });
    expect(r.steps.map((s) => s.ok)).toEqual([true, true, true, true, true, true, true, true, true]);
    expect(r.plain).toContain('↓ edit');                               // the hint next to the waiting message
  }, 90000);

  it('Esc stops the answer the agent is writing, keeps what was typed for the next prompt, and nothing else happens', async () => {
    await provider.close();
    provider = await startFakeProvider(8000);                         // a slow answer: time to press Esc
    await box.cleanup();
    box = await makeSandbox(provider);
    const started = Date.now();
    const r = await runDevx(box, [
      { wait: 'Ask or describe', timeout: 30 },
      { ms: 300, send: 'long task' },
      { ms: 200, send: KEY.enter },
      { wait: 'esc to stop', timeout: 10 },                           // the agent is working
      { ms: 300, send: 'my next thought' },                            // typed, not sent
      { ms: 300, send: KEY.esc },
      { wait: 'connection cancelled|stopped', timeout: 5 },
      { wait: '❯ my next thought', timeout: 10 },                      // the draft is back in the prompt
    ], { exitWait: 1 });
    expect(r.steps.map((s) => s.ok)).toEqual(r.steps.map(() => true));
    expect(Date.now() - started).toBeLessThan(8000 + 10000);
    expect(r.plain).not.toContain('FAKE-ANSWER');                    // it never got to answer
    expect(r.exited).toBe(false);
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

  it('/rewind goes back to one of your messages: the chat after it is forgotten and the message comes back to edit', async () => {
    const r = await runDevx(box, [
      { wait: 'Ask or describe', timeout: 30 },
      { ms: 300, send: 'first message' },
      { ms: 200, send: KEY.enter },
      { wait: 'FAKE-ANSWER', timeout: 20 },
      { wait: 'Ask or describe', timeout: 15 },
      { ms: 300, send: 'second message' },
      { ms: 200, send: KEY.enter },
      { wait: 'FAKE-ANSWER', timeout: 20 },
      { wait: 'Ask or describe', timeout: 15 },
      { ms: 300, send: '/rewind' },
      { ms: 300, send: KEY.enter },
      { wait: 'rewind', timeout: 10 },
      { ms: 300, send: KEY.up },                                         // from "(current)" to the second message
      { ms: 200, send: KEY.up },                                         // to the first one
      { ms: 200, send: KEY.enter },
      { wait: 'Back at your message', timeout: 10 },
      { wait: 'first message', timeout: 10 },                            // it is in the input line again
      { ms: 300, send: ' edited' },
      { ms: 200, send: KEY.enter },
      { wait: 'FAKE-ANSWER', timeout: 20 },
    ], { exitWait: 1 });
    expect(r.steps.every((s) => s.ok), JSON.stringify(r.steps)).toBe(true);
    // The request after the rewind has the edited first message and no trace of the second one.
    const answers = provider.requests.filter((q) => !JSON.stringify(q.messages).includes('You name chats'));
    const last = JSON.stringify(answers[answers.length - 1].messages);
    expect(last).toContain('first message edited');
    expect(last).not.toContain('second message');
    expect(last).not.toContain('FAKE-ANSWER to: first message"');
  }, 120000);
});

describe.skipIf(!canRunE2E)('/compact', () => {
  let provider: FakeProvider;
  let box: Sandbox;
  beforeEach(async () => { provider = await startFakeProvider(0); box = await makeSandbox(provider); });
  afterEach(async () => { await provider.close(); await box.cleanup(); });

  it('says the summary can be opened, and Ctrl+O shows it', async () => {
    const r = await runDevx(box, [
      { wait: 'Ask or describe', timeout: 30 },
      { ms: 300, send: 'remember the number 42' },
      { ms: 200, send: KEY.enter },
      { wait: 'FAKE-ANSWER', timeout: 20, ms: 2500 },                      // let the turn end (the chat is named after it)
      { ms: 300, send: '/compact' },
      { ms: 300, send: KEY.enter },
      { wait: 'ctrl\\+o to expand', timeout: 20, ms: 500, send: '\x0f' },     // Ctrl+O at the prompt: the chat is drawn again, unfolded
    ], { exitWait: 1 });
    expect(r.steps.map((s) => s.ok)).toEqual(r.steps.map(() => true));
    expect(r.plain).toContain('Context compacted');
    expect(r.plain).toContain('what the model wrote');                    // after Ctrl+O the summary itself is on the screen
    expect(r.plain).toContain('FAKE-ANSWER to: Write a short, structured summary');
  }, 90000);
});

describe.skipIf(!canRunE2E)('the first run', () => {
  let provider: FakeProvider;
  let box: Sandbox;
  beforeEach(async () => { provider = await startFakeProvider(0); box = await makeSandbox(provider); await fs.rm(path.join(box.home, '.devxrc.json')); });
  afterEach(async () => { await provider.close(); await box.cleanup(); });

  it('welcomes, asks language, provider, key and model, then saves them and opens the prompt', async () => {
    const r = await runDevx(box, [
      { wait: 'Language', timeout: 30 },                                     // the welcome card and the language list
      { ms: 300, send: KEY.down },                                           // Russian
      { ms: 200, send: KEY.enter },
      { wait: 'Тема', timeout: 10, ms: 300 },                                // the theme list recolours as you move
      { ms: 200, send: KEY.down },
      { ms: 200, send: KEY.enter },
      { wait: 'Провайдер', timeout: 10, ms: 300 },                           // the rest is in the chosen language
      { ms: 200, send: KEY.up },                                             // from the first match to "Add a provider"
      { ms: 200, send: KEY.enter },
      { wait: 'Название провайдера', timeout: 10, ms: 300 },                 // a provider of one's own: name, address, key
      { ms: 100, send: 'Fake gateway' },
      { ms: 300, send: KEY.enter },
      { wait: 'Базовый URL', timeout: 10, ms: 300 },
      { ms: 100, send: provider.baseUrl },
      { ms: 300, send: KEY.enter },
      { wait: 'Ключ не нужен', timeout: 10, ms: 300 },                       // a server on this machine may need no key
      { ms: 200, send: KEY.down },                                           // "Enter a key"
      { ms: 200, send: KEY.enter },
      { wait: 'Вставь API-ключ', timeout: 10, ms: 300 },
      { ms: 100, send: 'secret-key-1' },
      { ms: 300, send: KEY.enter },
      { wait: 'fake', timeout: 15, ms: 300 },
      { ms: 200, send: KEY.enter },
      { wait: 'Готово', timeout: 10, ms: 300 },
      { wait: 'Доверять', timeout: 15, ms: 300, send: KEY.up },
      { ms: 200, send: KEY.enter },
      { wait: 'Ask or describe|Опиши|Спроси', timeout: 30 },
    ], { exitWait: 1 });
    expect(r.steps.map((s) => s.ok)).toEqual(r.steps.map(() => true));
    expect(r.plain).not.toContain('secret-key-1');                           // the key is never echoed
    const saved = JSON.parse(await fs.readFile(path.join(box.home, '.devxrc.json'), 'utf8'));
    expect(saved).toMatchObject({ language: 'ru', onboarded: true, theme: expect.any(String), provider: 'custom-fake-gateway', baseUrl: provider.baseUrl, apiKey: 'secret-key-1', model: 'fake' });
    // and it is kept as a provider of its own, to be chosen again later
    expect(saved.customProviders).toEqual({ 'custom-fake-gateway': { name: 'Fake gateway', baseUrl: provider.baseUrl } });
    expect(saved.apiKeys['custom-fake-gateway']).toBe('secret-key-1');
  }, 120000);
});

describe.skipIf(!canRunE2E)('saved providers', () => {
  let provider: FakeProvider;
  let box: Sandbox;
  beforeEach(async () => { provider = await startFakeProvider(0); box = await makeSandbox(provider); });
  afterEach(async () => { await provider.close(); await box.cleanup(); });

  it('the single "Custom" of older versions shows up as a saved provider, and can be deleted from its card', async () => {
    const r = await runDevx(box, [
      { wait: 'Ask or describe', timeout: 30 },
      { ms: 300, send: '/provider' },
      { ms: 300, send: KEY.enter },
      { wait: 'type to find a provider', timeout: 15 },
      { wait: 'Add a provider', timeout: 5 },                                   // the row that adds one of your own
      { ms: 300, send: KEY.enter },                                            // the focus is on the first saved provider: Custom
      { wait: 'Change the name', timeout: 10 },                                // its card: use, rename, address, key, delete
      { ms: 200, send: KEY.down }, { ms: 100, send: KEY.down }, { ms: 100, send: KEY.down }, { ms: 100, send: KEY.down },
      { ms: 200, send: KEY.enter },
      { wait: 'Delete the provider Custom and its key', timeout: 10 },
      { ms: 300, send: KEY.up },                                               // "No" is on by default: move to "Delete"
      { ms: 200, send: KEY.enter },
      { wait: 'Provider Custom deleted', timeout: 10, ms: 300 },
      { wait: 'type to find a provider', timeout: 10 },                        // back to the list, now without it
      { ms: 300, send: KEY.esc },
      { wait: 'Provider selection cancelled', timeout: 10 },
    ], { exitWait: 1 });
    expect(r.steps.map((s) => s.ok)).toEqual(r.steps.map(() => true));
    expect(r.plain).not.toContain('Custom (Any OpenAI');
    const saved = JSON.parse(await fs.readFile(path.join(box.home, '.devxrc.json'), 'utf8'));
    expect(saved.customProviders ?? {}).toEqual({});
    expect(saved.apiKeys?.custom).toBeUndefined();
    expect(saved.provider).toBeUndefined();                                    // it was the one in use: forgotten, though it keeps working
    expect(saved.baseUrl).toBe(provider.baseUrl);
  }, 90000);

  it('a provider added from /provider is saved at once, even when the model step is left', async () => {
    const r = await runDevx(box, [
      { wait: 'Ask or describe', timeout: 30 },
      { ms: 300, send: '/provider' },
      { ms: 300, send: KEY.enter },
      { wait: 'type to find a provider', timeout: 15 },
      { ms: 300, send: KEY.up },                                               // to the pinned "Add a provider"
      { ms: 200, send: KEY.up },
      { ms: 200, send: KEY.enter },
      { wait: 'Name of the provider', timeout: 10, ms: 300 },
      { ms: 100, send: 'Kimi proxy' },
      { ms: 300, send: KEY.enter },
      { wait: 'Base URL of the API', timeout: 10, ms: 300 },
      { ms: 100, send: 'not a url' },                                          // asked again, with the reason
      { ms: 300, send: KEY.enter },
      { wait: 'This is not an address', timeout: 10 },
      { ms: 100, send: '\x15' },                                               // Ctrl+U clears the field
      { ms: 100, send: provider.baseUrl },
      { ms: 300, send: KEY.enter },
      { wait: 'No key needed', timeout: 10, ms: 300 },
      { ms: 200, send: KEY.enter },                                            // no key: a local test server
      { wait: 'Provider Kimi proxy saved', timeout: 10 },
      { wait: 'type to find a model|fake', timeout: 15, ms: 300 },
      { ms: 200, send: KEY.esc },                                              // leave the model step
    ], { exitWait: 1 });
    expect(r.steps.map((s) => s.ok)).toEqual(r.steps.map(() => true));
    const saved = JSON.parse(await fs.readFile(path.join(box.home, '.devxrc.json'), 'utf8'));
    expect(saved.customProviders['custom-kimi-proxy']).toEqual({ name: 'Kimi proxy', baseUrl: provider.baseUrl });
    expect(saved.apiKeys['custom-kimi-proxy']).toBe('no-key');
  }, 90000);
});

describe.skipIf(!canRunE2E)('Extra in the settings', () => {
  let provider: FakeProvider;
  let box: Sandbox;
  beforeEach(async () => { provider = await startFakeProvider(0); box = await makeSandbox(provider); });
  afterEach(async () => { await provider.close(); await box.cleanup(); });

  it('the row appears with YOLO, warns when switched on, is said again when the panel closes, and is never saved', async () => {
    const r = await runDevx(box, [
      { wait: 'Ask or describe', timeout: 30 },
      { ms: 300, send: '/settings' },
      { ms: 300, send: KEY.enter },
      { wait: 'Auto-approve \\(YOLO\\)', timeout: 10 },
      { ms: 200, send: KEY.down }, { ms: 100, send: KEY.down }, { ms: 100, send: KEY.down },     // theme, interface, background, YOLO
      { ms: 200, send: '\x1b[C' },                                          // YOLO on: the row appears under it
      { wait: 'Extra \\(ask nothing\\)', timeout: 10 },
      { ms: 200, send: KEY.down },
      { ms: 200, send: '\x1b[C' },                                          // Extra on
      { wait: 'Warning: nothing is asked', timeout: 10 },
      { ms: 200, send: KEY.esc },
      { wait: 'Extra is on', timeout: 10 },                                  // said once more after the panel closed
      { wait: 'extra', timeout: 10, ms: 300 },                               // the prompt frame says extra, not yolo
    ], { exitWait: 1 });
    expect(r.steps.map((s) => s.ok)).toEqual(r.steps.map(() => true));
    const saved = JSON.parse(await fs.readFile(path.join(box.home, '.devxrc.json'), 'utf8'));
    expect(saved.autoApprove).toBeUndefined();
    expect(saved.autoApproveAll).toBeUndefined();                           // off again at the next start
  }, 90000);

  it('--extra starts with it on, and nothing about it reaches the config file', async () => {
    const r = await runDevx(box, [
      { wait: 'extra', timeout: 30 },
      { wait: 'Ask or describe', timeout: 10, ms: 200 },
    ], { exitWait: 1, args: ['--extra'] });
    expect(r.steps.map((s) => s.ok)).toEqual([true, true]);
    expect(r.plain).toContain('extra: nothing asked');                     // the header card
    const saved = JSON.parse(await fs.readFile(path.join(box.home, '.devxrc.json'), 'utf8'));
    expect(saved.autoApproveAll).toBeUndefined();
  }, 90000);
});

describe.skipIf(!canRunE2E)('Esc at the prompt', () => {
  let provider: FakeProvider;
  let box: Sandbox;
  beforeEach(async () => { provider = await startFakeProvider(0); box = await makeSandbox(provider); });
  afterEach(async () => { await provider.close(); await box.cleanup(); });

  it('cancels a command that is being typed: the list closes and the line is empty again', async () => {
    const r = await runDevx(box, [
      { wait: 'Ask or describe', timeout: 30 },
      { ms: 300, send: '/hel' },
      { wait: '/help', timeout: 10 },                                        // the list of commands
      { ms: 300, send: KEY.esc },
      { wait: 'Ask or describe', timeout: 10 },                              // the placeholder is back: the line is empty
    ], { exitWait: 1 });
    expect(r.steps.map((s) => s.ok)).toEqual(r.steps.map(() => true));
    expect(r.exited).toBe(false);
  }, 60000);
});
