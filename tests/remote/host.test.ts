import crypto from 'crypto';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { LLMProvider } from '../../src/core/types.js';

const work = fs.mkdtempSync(path.join(os.tmpdir(), 'devx-host-'));
process.env.DEVX_SESSIONS_DIR = path.join(work, 'sessions');
process.env.DEVX_CONFIG_PATH = path.join(work, 'config.json');

import { RemoteHost, inProcessWorkerFactory } from '../../src/remote/host.js';
import { RemoteClient } from '../../src/remote/client.js';
import { dialDirect } from '../../src/remote/links.js';
import { dialRelay } from '../../src/remote/relay.js';
import { newIdentity } from '../../src/remote/crypto.js';
import { localBroker, tempStore } from '../helpers/remote-env.js';

const usage = { promptTokens: 10, completionTokens: 5, totalTokens: 15, cost: 0 };

beforeAll(() => {
  fs.writeFileSync(process.env.DEVX_CONFIG_PATH!, JSON.stringify({ provider: 'fake', model: 'm1', baseUrl: 'http://127.0.0.1:1/v1', apiKey: 'k', maxContextTokens: 50000 }));
});

const closers: Array<() => Promise<void> | void> = [];
afterEach(async () => { for (const c of closers.splice(0).reverse()) await c(); });

function echoProvider(): LLMProvider {
  return {
    id: 'echo',
    async chat(req) {
      const last = [...req.messages].reverse().find((m) => m.role === 'user');
      return { content: `echo: ${last?.content.split('\n\n--- Attached')[0]}`, usage };
    },
  };
}

async function setup(opts: { relay?: boolean; provider?: () => LLMProvider } = {}) {
  const store = tempStore();
  const broker = opts.relay ? await localBroker() : null;
  if (broker) closers.push(() => broker.close());
  const host = new RemoteHost(store, { workerFactory: inProcessWorkerFactory(() => (opts.provider ? opts.provider() : echoProvider())), port: 0, bindHost: '127.0.0.1', brokers: broker ? [broker.url] : [] });
  host.onPairRequest = async () => true;
  const { port } = await host.start();
  closers.push(() => host.stop());

  const phone = newIdentity();
  const did = crypto.randomBytes(16).toString('hex');
  const pair = host.beginPairing();
  const payload = JSON.parse(Buffer.from(pair.uri.slice(6), 'base64url').toString());
  const token = Buffer.from(payload.t, 'base64url');
  const link = await dialDirect(`ws://127.0.0.1:${port}`);
  const client = await RemoteClient.connect(link, { identity: phone, did, hostPub: store.identity.pub, pair: { token, name: 'Test phone' } });
  closers.push(() => client.close());
  return { store, host, port, phone, did, client, broker, hostId: host.hostId };
}

describe('host', () => {
  it('writes the pairing payload the phone scans', async () => {
    const store = tempStore();
    const host = new RemoteHost(store, { workerFactory: inProcessWorkerFactory(() => echoProvider()), port: 0, brokers: [0, 1] });
    const info = host.beginPairing();
    expect(info.uri.startsWith('devx1:')).toBe(true);
    const p = JSON.parse(Buffer.from(info.uri.slice(6), 'base64url').toString());
    expect(Buffer.from(p.hp, 'base64url').equals(store.identity.pub)).toBe(true);
    expect(p.r).toEqual([0, 1]);
    expect(Buffer.from(p.t, 'base64url').length).toBe(16);
  });

  it('pairs, opens a new chat in a folder, chats and sees the live events', async () => {
    const { client, store } = await setup();
    expect(store.listDevices().map((d) => d.name)).toEqual(['Test phone']);
    await client.request('hello', { name: 'Mega', memory: false });

    const folder = fs.mkdtempSync(path.join(work, 'proj-'));
    const events: any[] = [];
    client.onEvent = (s, n, e) => events.push({ s, n, ...e });
    const opened = await client.request('session.open', { cwd: folder });
    expect(opened.items).toEqual([]);
    expect(opened.state.cwd).toBe(folder);

    await client.request('session.send', { s: opened.s, text: 'ping' });
    for (let i = 0; i < 200 && !events.some((e) => e.e === 'turn_end'); i++) await new Promise((r) => setTimeout(r, 20));
    expect(events.filter((e) => e.e === 'text').map((e) => e.d).join('')).toBe('echo: ping');
    const seqs = events.map((e) => e.n);
    expect(seqs).toEqual([...seqs].sort((a, b) => a - b));
    expect(events.some((e) => e.e === 'user' && e.text === 'ping')).toBe(true);

    const list = await client.request('sessions.list');
    expect(list.sessions[0]).toMatchObject({ id: opened.s, messages: 2, cwd: folder });
    const found = await client.request('sessions.list', { q: 'ping' });
    expect(found.sessions[0].id).toBe(opened.s);
    expect(found.sessions[0].snippet).toContain('ping');
  });

  it('works through the relay too, and the same phone can come back later', async () => {
    const { client, store, host, phone, did, broker, hostId } = await setup({ relay: true });
    await client.request('hello', {});
    client.close();
    await new Promise((r) => setTimeout(r, 100));
    const link = await dialRelay(broker!.url, hostId);
    const again = await RemoteClient.connect(link, { identity: phone, did, hostPub: store.identity.pub });
    closers.push(() => again.close());
    expect(again.ready.paired).toBe(false);
    const hello = await again.request('hello', { name: 'X' });
    expect(hello.host.hostId).toBe(host.hostId);
  });

  it('keeps nothing of a new chat that nobody wrote in', async () => {
    const { client, host } = await setup();
    const folder = fs.mkdtempSync(path.join(work, 'proj-'));
    const empty = await client.request('session.open', { cwd: folder });
    // It is open (all the controls work), but it is not in the list.
    expect((await client.request('sessions.list')).sessions.some((s: any) => s.id === empty.s)).toBe(false);
    expect((await client.request('session.info', { s: empty.s })).state.model).toBeTruthy();
    expect(host.openSessions().length).toBe(1);
    await client.request('session.detach', { s: empty.s });
    expect(host.openSessions().length).toBe(0);

    // One that has a message stays.
    const used = await client.request('session.open', { cwd: folder });
    await client.request('session.send', { s: used.s, text: 'hello' });
    await new Promise((r) => setTimeout(r, 300));
    await client.request('session.detach', { s: used.s });
    expect(host.openSessions().length).toBe(1);
  });

  it('tells which chats want a look: unread, error, a question waiting', async () => {
    const { client, host } = await setup();
    const folder = fs.mkdtempSync(path.join(work, 'proj-'));
    const a = await client.request('session.open', { cwd: folder });
    await client.request('session.send', { s: a.s, text: 'hi' });
    await new Promise((r) => setTimeout(r, 300));
    await client.request('session.detach', { s: a.s });
    expect((await client.request('sessions.list')).sessions.find((x: any) => x.id === a.s).status).toBe(null);

    (host as any).setMark(a.s, 'unread');
    expect((await client.request('sessions.list')).sessions.find((x: any) => x.id === a.s).status).toBe('unread');
    expect(await client.request('attention')).toMatchObject({ unread: 1, ask: 0, error: 0, total: 1 });
    (host as any).setMark(a.s, 'error');
    expect((await client.request('sessions.list')).sessions.find((x: any) => x.id === a.s).status).toBe('error');

    // Looking at it clears the mark.
    await client.request('session.open', { id: a.s });
    expect((await client.request('sessions.list')).sessions.find((x: any) => x.id === a.s).status).toBe(null);
    expect((await client.request('attention')).total).toBe(0);

    // A question that waits makes it orange while it is open.
    const open = [...(host as any).sessions][0];
    open.pending.add('ask');
    expect((await client.request('sessions.list')).sessions.find((x: any) => x.id === a.s).status).toBe('ask');
  });

  it('shows the MCP servers and answers the commands', async () => {
    const { client } = await setup();
    const folder = fs.mkdtempSync(path.join(work, 'proj-'));
    const a = await client.request('session.open', { cwd: folder });
    const r = await client.request('session.mcp', { s: a.s, args: '' });
    expect(r.ok).toBe(true);
    expect(typeof r.text).toBe('string');
    const cmds = await client.request('commands.list', { s: a.s });
    expect(cmds.map((c: any) => c.cmd)).toEqual(expect.arrayContaining(['/mcp', '/mcp import']));
  });

  it('deletes a chat, starts a new one, clears and renames', async () => {
    const { client } = await setup();
    const folder = fs.mkdtempSync(path.join(work, 'proj-'));
    const a = await client.request('session.open', { cwd: folder });
    await client.request('session.send', { s: a.s, text: 'one' });
    await new Promise((r) => setTimeout(r, 300));
    await client.request('session.rename', { s: a.s, title: 'Named' });
    const listed = await client.request('sessions.list');
    expect(listed.sessions.find((s: any) => s.id === a.s).title).toBe('Named');

    const reset = await client.request('session.reset', { s: a.s });
    expect(reset.id).not.toBe(a.s);
    const del = await client.request('session.delete', { id: a.s });
    expect(del.deleted).toBe(true);
    const after = await client.request('sessions.list');
    expect(after.sessions.some((s: any) => s.id === a.s)).toBe(false);
  });

  it('receives a file from the phone and gives it to the agent', async () => {
    const { client } = await setup();
    const folder = fs.mkdtempSync(path.join(work, 'proj-'));
    const a = await client.request('session.open', { cwd: folder });
    const data = Buffer.from('hello from the phone');
    const { uid } = await client.request('upload.begin', { name: 'note.txt', mime: 'text/plain', size: data.length });
    await client.request('upload.chunk', { uid, data: data.subarray(0, 5).toString('base64') });
    await client.request('upload.chunk', { uid, data: data.subarray(5).toString('base64') });
    await client.request('upload.end', { uid });
    let seen = '';
    const events: any[] = [];
    client.onEvent = (_s, _n, e) => events.push(e);
    await client.request('session.send', { s: a.s, text: 'read this', files: [uid] });
    for (let i = 0; i < 200 && !events.some((e) => e.e === 'turn_end'); i++) await new Promise((r) => setTimeout(r, 20));
    expect(fs.readFileSync(path.join(folder, '.devx', 'uploads', 'note.txt'), 'utf8')).toBe('hello from the phone');
    const snap = await client.request('session.snapshot', { s: a.s });
    seen = snap.items.find((i: any) => i.k === 'user').files.join(',');
    expect(seen).toBe('note.txt');
    await expect(client.request('upload.begin', { name: 'big', mime: 'x', size: 999_999_999 })).rejects.toThrow(/up to/);
  });

  it('answers the agent from the phone memory, only when the phone allows it', async () => {
    let step = 0;
    const provider = (): LLMProvider => ({
      id: 'mem',
      async chat(req) {
        step++;
        if (step === 1) return { content: '', toolCalls: [{ id: 't1', name: 'phone_memory', arguments: { action: 'search', query: 'email' } }], usage };
        return { content: 'done', usage };
      },
    });
    const { client } = await setup({ provider });
    const asked: any[] = [];
    client.onRpc = async (m, p) => { asked.push([m, p]); return 'email: me@example.com'; };
    await client.request('hello', { name: 'Mega', memory: true });
    const a = await client.request('session.open', { cwd: fs.mkdtempSync(path.join(work, 'proj-')) });
    const events: any[] = [];
    client.onEvent = (_s, _n, e) => events.push(e);
    await client.request('session.send', { s: a.s, text: 'what is my email?' });
    for (let i = 0; i < 200 && !events.some((e) => e.e === 'turn_end'); i++) await new Promise((r) => setTimeout(r, 20));
    expect(asked).toEqual([['memory', { action: 'search', query: 'email' }]]);
    expect(events.find((e) => e.e === 'tool_end').result).toBe('email: me@example.com');
  });

  it('does not offer the memory tool when the phone does not share it', async () => {
    let tools: string[] = [];
    const provider = (): LLMProvider => ({ id: 'x', async chat(req) { tools = (req.tools ?? []).map((t) => t.name); return { content: 'ok', usage }; } });
    const { client } = await setup({ provider });
    await client.request('hello', { name: 'Mega', memory: false });
    const a = await client.request('session.open', { cwd: fs.mkdtempSync(path.join(work, 'proj-')) });
    const events: any[] = [];
    client.onEvent = (_s, _n, e) => events.push(e);
    await client.request('session.send', { s: a.s, text: 'hi' });
    for (let i = 0; i < 200 && !events.some((e) => e.e === 'turn_end'); i++) await new Promise((r) => setTimeout(r, 20));
    expect(tools.length).toBeGreaterThan(3);
    expect(tools).not.toContain('phone_memory');
  });

  it('lists folders and refuses a folder that does not exist', async () => {
    const { client } = await setup();
    fs.mkdirSync(path.join(work, 'listed-dir'), { recursive: true });
    const dirs = await client.request('dirs.list', { path: work });
    expect(dirs.dirs).toContain('listed-dir');
    await expect(client.request('session.open', { cwd: path.join(work, 'nope') })).rejects.toThrow(/does not exist/);
  });

  it('lists providers and adds one', async () => {
    const { client } = await setup();
    const add = await client.request('provider.add', { name: 'My Gateway', baseUrl: 'https://gw.example.com/v1', apiKey: 'sk-1' });
    expect(add.id).toBe('custom-my-gateway');
    const list = await client.request('providers.list');
    expect(list.providers.find((p: any) => p.id === 'custom-my-gateway')).toMatchObject({ custom: true, hasKey: true });
    await expect(client.request('provider.add', { name: 'x', baseUrl: 'ftp://nope' })).rejects.toThrow(/http/);
    expect(JSON.stringify(list)).not.toContain('sk-1');
  });
});
