import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import fs from 'fs/promises';
import path from 'path';
import { FakeProvider, KEY, Sandbox, canRunE2E, makeSandbox, runDevx, startFakeProvider } from './helpers.js';

// While the agent works the line below the chat takes commands, lists them, and brings back what was sent before.
describe.skipIf(!canRunE2E)('commands while the agent works', () => {
  let provider: FakeProvider;
  let box: Sandbox;
  beforeEach(async () => { provider = await startFakeProvider(5000); box = await makeSandbox(provider); });
  afterEach(async () => { await provider.close(); await box.cleanup(); });

  const typed = (text: string) => ({ ms: 80, send: text });
  const finish = [
    { send: KEY.ctrlC }, { wait: 'Exit devx', timeout: 10, send: KEY.up }, { ms: 200, send: KEY.enter },
  ];

  it('/settings opens its panel while the agent works and the answer still arrives', async () => {
    const r = await runDevx(box, [
      { wait: 'Ask or describe', timeout: 30 },
      typed('first task'), { ms: 200, send: KEY.enter },
      { wait: 'esc to stop', timeout: 15 },
      typed('/settings'), { ms: 300, send: KEY.enter },
      { wait: 'Theme', timeout: 10 },
      { ms: 500, send: KEY.esc },
      { wait: 'FAKE-ANSWER', timeout: 30 },
      { ms: 500 },
      ...finish,
    ], { exitWait: 6 });
    expect(r.plain).toContain('Theme');
    expect(r.plain).toContain('FAKE-ANSWER');
    expect(provider.requests.filter((q) => q.stream)).toHaveLength(1);   // /settings was not sent to the model
  }, 120000);

  it('/compact typed while the agent works waits for the end of the turn and then runs', async () => {
    const r = await runDevx(box, [
      { wait: 'Ask or describe', timeout: 30 },
      typed('first task'), { ms: 200, send: KEY.enter },
      { wait: 'esc to stop', timeout: 15 },
      typed('/compact'), { ms: 300, send: KEY.enter },
      { wait: 'runs when the agent is done', timeout: 10 },
      { wait: 'FAKE-ANSWER', timeout: 30 },
      { wait: 'compact', timeout: 30 },
      { ms: 4000 },
      ...finish,
    ], { exitWait: 6 });
    expect(r.plain).toContain('runs when the agent is done');
    expect(r.plain).toMatch(/Context compacted|already short|Compacting/);
    expect(provider.requests.filter((q) => q.stream)).toHaveLength(1);
  }, 120000);

  it('the list of commands opens on a slash and shows more than the first ones', async () => {
    const r = await runDevx(box, [
      { wait: 'Ask or describe', timeout: 30 },
      typed('first task'), { ms: 200, send: KEY.enter },
      { wait: 'esc to stop', timeout: 15 },
      typed('/'), { ms: 500 },
      { ms: 100, send: KEY.down }, { ms: 100, send: KEY.down }, { ms: 100, send: KEY.down }, { ms: 100, send: KEY.down },
      { ms: 100, send: KEY.down }, { ms: 100, send: KEY.down }, { ms: 100, send: KEY.down }, { ms: 100, send: KEY.down }, { ms: 100, send: KEY.down },
      { ms: 400 },
      { send: KEY.esc }, { ms: 300 },
      ...finish,
    ], { exitWait: 6 });
    expect(r.plain).toContain('/new');
    expect(r.plain).toMatch(/\d+ \/ 3\d · ↑↓/);   // the position in the list: not everything fits, the rest is reached with the arrows
    expect(r.plain).toContain('/usage');         // a command that was far down the list
  }, 120000);

  it('up brings back the line sent before, also from an earlier chat, and the line is kept in a file', async () => {
    // an earlier session left this in the history file
    await fs.mkdir(path.join(box.home, '.devx'), { recursive: true });
    await fs.writeFile(path.join(box.home, '.devx', 'history.jsonl'), JSON.stringify({ prompt: 'a line from an earlier chat', timestamp: 1 }) + '\n');
    const r = await runDevx(box, [
      { wait: 'Ask or describe', timeout: 30 },
      typed('first task'), { ms: 200, send: KEY.enter },
      { wait: 'esc to stop', timeout: 15 },
      { ms: 200, send: KEY.up },
      { ms: 500 },
      { ms: 200, send: KEY.up },
      { ms: 500 },
      ...finish,
    ], { exitWait: 6 });
    expect(r.plain).toContain('first task');
    expect(r.plain).toContain('a line from an earlier chat');
    const saved = await fs.readFile(path.join(box.home, '.devx', 'history.jsonl'), 'utf8');
    expect(saved).toContain('first task');
  }, 120000);
});
