import crypto from 'crypto';
import { describe, expect, it, afterEach } from 'vitest';
import { acceptLink, connectLink, DeviceRecord, HostHooks, Link, Peer } from '../../src/remote/secure.js';
import { Opener, Sealer, hostIdOf, newIdentity, sign, verify } from '../../src/remote/crypto.js';
import { dialDirect, listenDirect } from '../../src/remote/links.js';
import { Chunker, RelayHost, dialRelay } from '../../src/remote/relay.js';
import { localBroker, tempStore } from '../helpers/remote-env.js';

/** Two ends of a pipe, in memory. */
function pipe(kind: Link['kind'] = 'direct'): [Link, Link] {
  const mk = (): Link => ({ kind, send: () => {}, close: () => {}, onMessage: () => {}, onClose: () => {} });
  const a = mk();
  const b = mk();
  a.send = (d) => queueMicrotask(() => b.onMessage(Buffer.from(d)));
  b.send = (d) => queueMicrotask(() => a.onMessage(Buffer.from(d)));
  a.close = () => queueMicrotask(() => b.onClose());
  b.close = () => queueMicrotask(() => a.onClose());
  return [a, b];
}

function makeHost(opts: { approve?: boolean } = {}) {
  const identity = newIdentity();
  const devices = new Map<string, DeviceRecord>();
  const tokens = new Set<string>();
  const peers: Peer[] = [];
  const hooks: HostHooks = {
    identity,
    devices: { get: (d) => devices.get(d) },
    checkToken: (t) => tokens.has(t.toString('hex')),
    consumeToken: (t) => void tokens.delete(t.toString('hex')),
    askPair: async () => opts.approve !== false,
    addDevice: (d) => void devices.set(d.did, d),
    hostInfo: () => ({ name: 'pc' }),
    onPeer: (p) => void peers.push(p),
  };
  return { identity, devices, tokens, peers, hooks };
}

describe('crypto', () => {
  it('signs and verifies with raw P-256 keys', () => {
    const id = newIdentity();
    const sig = sign(id, Buffer.from('hi'));
    expect(verify(id.pub, Buffer.from('hi'), sig)).toBe(true);
    expect(verify(id.pub, Buffer.from('ho'), sig)).toBe(false);
    expect(verify(newIdentity().pub, Buffer.from('hi'), sig)).toBe(false);
  });

  it('opens its own frames, and nothing else', () => {
    const key = crypto.randomBytes(32);
    const s = new Sealer(key);
    const o = new Opener(key);
    const f1 = s.seal(Buffer.from('one'));
    const f2 = s.seal(Buffer.from('two'));
    expect(o.open(f1)?.toString()).toBe('one');
    expect(o.open(f1)).toBeNull(); // a repeat
    expect(o.open(f2)?.toString()).toBe('two');
    const bad = Buffer.from(s.seal(Buffer.from('three')));
    bad[10] ^= 1;
    expect(o.open(bad)).toBeNull();
    expect(new Opener(crypto.randomBytes(32)).open(s.seal(Buffer.from('x')))).toBeNull();
  });

  it('cuts big messages into chunks and joins them, in any order', () => {
    const c = new Chunker(crypto.randomBytes(8));
    const data = crypto.randomBytes(50_000);
    const parts = c.split(data);
    expect(parts.length).toBeGreaterThan(3);
    let whole: Buffer | null = null;
    for (const p of [...parts].reverse()) whole = c.join(p) ?? whole;
    expect(whole?.equals(data)).toBe(true);
  });
});

describe('handshake', () => {
  it('pairs a new phone, then lets it back in without a token', async () => {
    const h = makeHost();
    const phone = newIdentity();
    const did = crypto.randomBytes(16).toString('hex');
    const token = crypto.randomBytes(16);
    h.tokens.add(token.toString('hex'));

    const [c1, s1] = pipe();
    acceptLink(s1, h.hooks);
    const ch = await connectLink(c1, { identity: phone, did, hostPub: h.identity.pub, pair: { token, name: 'My phone' } });
    expect(ch.ready.paired).toBe(true);
    expect(h.devices.get(did)?.name).toBe('My phone');
    expect(h.tokens.size).toBe(0);

    // the same token cannot be used again
    const [c2, s2] = pipe();
    acceptLink(s2, h.hooks);
    await expect(connectLink(c2, { identity: newIdentity(), did: crypto.randomBytes(16).toString('hex'), hostPub: h.identity.pub, pair: { token, name: 'x' } })).rejects.toThrow(/denied/);

    // back in with the key
    const [c3, s3] = pipe();
    acceptLink(s3, h.hooks);
    const ch3 = await connectLink(c3, { identity: phone, did, hostPub: h.identity.pub });
    expect(ch3.ready.paired).toBe(false);
    const got: any[] = [];
    h.peers[h.peers.length - 1].onMessage = (m) => got.push(m);
    ch3.send({ k: 'hi', n: 1 });
    await new Promise((r) => setTimeout(r, 20));
    expect(got).toEqual([{ k: 'hi', n: 1 }]);
  });

  it('shows the same six digits on both sides while pairing', async () => {
    const h = makeHost();
    let hostSas = '';
    h.hooks.askPair = async (r) => { hostSas = r.sas; return true; };
    const token = crypto.randomBytes(16);
    h.tokens.add(token.toString('hex'));
    const [c, s] = pipe();
    acceptLink(s, h.hooks);
    let phoneSas = '';
    await connectLink(c, { identity: newIdentity(), did: crypto.randomBytes(16).toString('hex'), hostPub: h.identity.pub, pair: { token, name: 'p' }, onSas: (x) => (phoneSas = x) });
    expect(phoneSas).toMatch(/^\d{6}$/);
    expect(phoneSas).toBe(hostSas);
  });

  it('refuses when the person on the PC says no', async () => {
    const h = makeHost({ approve: false });
    const token = crypto.randomBytes(16);
    h.tokens.add(token.toString('hex'));
    const [c, s] = pipe();
    acceptLink(s, h.hooks);
    await expect(connectLink(c, { identity: newIdentity(), did: crypto.randomBytes(16).toString('hex'), hostPub: h.identity.pub, pair: { token, name: 'p' } })).rejects.toThrow(/refused/);
    expect(h.devices.size).toBe(0);
  });

  it('turns away a device it does not know, and a host that is not the paired one', async () => {
    const h = makeHost();
    const [c, s] = pipe();
    acceptLink(s, h.hooks);
    await expect(connectLink(c, { identity: newIdentity(), did: crypto.randomBytes(16).toString('hex'), hostPub: h.identity.pub })).rejects.toThrow(/denied/);

    const [c2, s2] = pipe();
    acceptLink(s2, h.hooks);
    await expect(connectLink(c2, { identity: newIdentity(), did: crypto.randomBytes(16).toString('hex'), hostPub: newIdentity().pub })).rejects.toThrow(/not who/);
  });

  it('does not accept a stolen device id with another key', async () => {
    const h = makeHost();
    const phone = newIdentity();
    const did = crypto.randomBytes(16).toString('hex');
    h.devices.set(did, { did, name: 'p', pub: phone.pub.toString('base64'), addedAt: 0, lastSeen: 0 });
    const [c, s] = pipe();
    acceptLink(s, h.hooks);
    await expect(connectLink(c, { identity: newIdentity(), did, hostPub: h.identity.pub })).rejects.toThrow();
  });
});

describe('transports', () => {
  const closers: Array<() => Promise<void> | void> = [];
  afterEach(async () => { for (const c of closers.splice(0)) await c(); });

  it('talks over a direct WebSocket', async () => {
    const h = makeHost();
    const server = await listenDirect(0, (link) => acceptLink(link, h.hooks), '127.0.0.1');
    closers.push(() => server.close());
    const phone = newIdentity();
    const did = crypto.randomBytes(16).toString('hex');
    h.devices.set(did, { did, name: 'p', pub: phone.pub.toString('base64'), addedAt: 0, lastSeen: 0 });
    const link = await dialDirect(`ws://127.0.0.1:${server.port}`);
    const ch = await connectLink(link, { identity: phone, did, hostPub: h.identity.pub });
    const reply = new Promise<any>((r) => (ch.onMessage = r));
    h.peers[0].send({ k: 'hello', big: 'x'.repeat(200_000) });
    expect((await reply).big.length).toBe(200_000);
    ch.close();
  });

  it('talks over the relay (a broker in the middle), with big messages', async () => {
    const broker = await localBroker();
    closers.push(() => broker.close());
    const h = makeHost();
    const hostId = hostIdOf(h.identity.pub);
    const relay = new RelayHost(hostId, (link) => acceptLink(link, h.hooks));
    relay.start([broker.url]);
    closers.push(() => relay.stop());
    await new Promise((r) => setTimeout(r, 300));

    const phone = newIdentity();
    const did = crypto.randomBytes(16).toString('hex');
    h.devices.set(did, { did, name: 'p', pub: phone.pub.toString('base64'), addedAt: 0, lastSeen: 0 });
    const link = await dialRelay(broker.url, hostId);
    const ch = await connectLink(link, { identity: phone, did, hostPub: h.identity.pub });
    const got: any[] = [];
    h.peers[0].onMessage = (m) => got.push(m);
    ch.send({ k: 'file', data: 'y'.repeat(100_000) });
    await new Promise((r) => setTimeout(r, 300));
    expect(got[0].data.length).toBe(100_000);
    ch.close();
  });
});
