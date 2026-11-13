import crypto from 'crypto';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { afterEach, describe, expect, it } from 'vitest';

const work = fs.mkdtempSync(path.join(os.tmpdir(), 'devx-term-'));
process.env.DEVX_SESSIONS_DIR = path.join(work, 'sessions');
process.env.DEVX_CONFIG_PATH = path.join(work, 'config.json');
fs.writeFileSync(process.env.DEVX_CONFIG_PATH, JSON.stringify({ provider: 'fake', model: 'm1', baseUrl: 'http://127.0.0.1:1/v1', apiKey: 'k', maxContextTokens: 50000 }));

import { RemoteHost, inProcessWorkerFactory } from '../../src/remote/host.js';
import { serveControl } from '../../src/remote/control.js';
import { RemoteClient } from '../../src/remote/client.js';
import { dialDirect } from '../../src/remote/links.js';
import { newIdentity } from '../../src/remote/crypto.js';
import { TerminalLink } from '../../src/remote/terminal-link.js';
import { tempStore } from '../helpers/remote-env.js';

const closers: Array<() => Promise<void> | void> = [];
afterEach(async () => { for (const c of closers.splice(0).reverse()) await c(); });
const until = async (check: () => boolean, ms = 3000) => { for (let i = 0; i < ms / 20 && !check(); i++) await new Promise((r) => setTimeout(r, 20)); };

/** A host with its control socket, a phone paired to it, and a terminal that works in a chat. */
async function setup() {
  const store = tempStore();
  process.env.DEVX_REMOTE_DIR = store.dir;
  const host = new RemoteHost(store, { workerFactory: inProcessWorkerFactory(() => ({ id: 'x', async chat() { return { content: 'ok', usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2, cost: 0 } }; } })), port: 0, bindHost: '127.0.0.1', brokers: [] });
  const { port } = await host.start();
  const control = serveControl(host, () => {}, store.dir);
  host.onPairRequest = async () => true;
  closers.push(() => host.stop(), () => control.close());

  const pair = host.beginPairing();
  const payload = JSON.parse(Buffer.from(pair.uri.slice(6), 'base64url').toString());
  const link = await dialDirect(`ws://127.0.0.1:${port}`);
  const client = await RemoteClient.connect(link, { identity: newIdentity(), did: crypto.randomBytes(16).toString('hex'), hostPub: store.identity.pub, pair: { token: Buffer.from(payload.t, 'base64url'), name: 'Test phone' } });
  closers.push(() => client.close());

  const chat = { id: 'sess_terminal_1', title: 'From the terminal', items: [{ k: 'user', text: 'hello from the keyboard', files: [], images: 0 }, { k: 'assistant', text: 'hi there' }] };
  const terminal = new TerminalLink({
    items: () => chat.items,
    state: () => ({ id: chat.id, title: chat.title, cwd: work, model: 'm1', plan: false, yolo: false, extra: false, cost: 0, costEstimated: false, ctxUsed: 10, ctxLimit: 1000 }),
  });
  terminal.start();
  closers.push(() => terminal.close());
  return { host, client, terminal, chat };
}

describe('a chat that runs in a terminal', () => {
  it('shows up for the phone with its history, live events, and takes what the phone writes', async () => {
    const { host, client, terminal, chat } = await setup();
    await until(() => terminal['socket'] !== null);
    terminal.sync(chat.id, chat.title, true);
    await until(() => host.openSessions().length === 1);
    expect(host.openSessions()[0]).toMatchObject({ id: chat.id });

    const list = await client.request('sessions.list');
    const row = list.sessions.find((s: any) => s.id === chat.id);
    expect(row).toMatchObject({ open: true, terminal: true });

    // The phone opens it: the history is the terminal's own.
    const events: any[] = [];
    client.onEvent = (s, n, e) => events.push({ s, n, ...e });
    const opened = await client.request('session.open', { id: chat.id });
    expect(opened.items.map((i: any) => i.text)).toEqual(['hello from the keyboard', 'hi there']);
    expect(opened.state.terminal).toBe(true);

    // The terminal's agent works: the phone sees it as it happens.
    terminal.turnStart('second question');
    terminal.agentEvent({ type: 'text_delta', delta: 'The ' });
    terminal.agentEvent({ type: 'text_delta', delta: 'answer' });
    terminal.agentEvent({ type: 'tool_start', id: 't1', name: 'bash', actionDesc: 'Run: ls', argsRaw: '{"command":"ls"}' });
    terminal.agentEvent({ type: 'tool_end', id: 't1', name: 'bash', result: 'a.txt' });
    terminal.turnEnd({ ms: 50, tools: 1, cost: 0, aborted: false });
    await until(() => events.some((e) => e.e === 'turn_end'));
    expect(events.some((e) => e.e === 'user' && e.text === 'second question')).toBe(true);
    expect(events.filter((e) => e.e === 'text').map((e) => e.d).join('')).toBe('The answer');
    expect(events.some((e) => e.e === 'tool_start' && e.name === 'bash')).toBe(true);
    expect(events.some((e) => e.e === 'tool_end' && e.result === 'a.txt')).toBe(true);

    // The phone writes: the terminal gets it as if typed, and is woken if it waits at the prompt.
    let woken = 0;
    terminal.onWake = () => { woken++; };
    await client.request('session.send', { s: chat.id, text: 'hello from the phone' });
    await until(() => terminal.hasPending());
    expect(terminal.takeFirst()).toBe('hello from the phone');
    expect(woken).toBe(1);

    // Stop from the phone reaches the terminal.
    let stopped = false;
    terminal.onStop = () => { stopped = true; };
    await client.request('session.stop', { s: chat.id });
    await until(() => stopped);
    expect(stopped).toBe(true);
  });

  it('is not kept when the terminal goes away, and a chat with no message stays private', async () => {
    const { host, terminal, chat } = await setup();
    await until(() => terminal['socket'] !== null);
    terminal.sync(chat.id, chat.title, false);
    await new Promise((r) => setTimeout(r, 150));
    expect(host.openSessions().length).toBe(0);
    terminal.sync(chat.id, chat.title, true);
    await until(() => host.openSessions().length === 1);
    terminal.close();
    await until(() => host.openSessions().length === 0);
    expect(host.openSessions().length).toBe(0);
  });
});
