import crypto from 'crypto';
import {
  CLIENT_SIG_LABEL, HOST_SIG_LABEL, Identity, Opener, PROTOCOL_VERSION, Sealer, deriveKeys, newEphemeral, sameSecret,
  sha256, sign, verify,
} from './crypto.js';

/**
 * A Link carries whole messages (bytes) between the phone and the PC and knows nothing about them:
 * a WebSocket on the local network or direct address, or the public relay. Everything that matters
 * (who is who, what is said) is decided one layer up, by the handshake and the encrypted frames below.
 */
export interface Link {
  readonly kind: 'direct' | 'relay';
  send(data: Buffer): void;
  close(): void;
  onMessage: (data: Buffer) => void;
  onClose: () => void;
}

export const PKT_HELLO = 0x01;
export const PKT_HELLO2 = 0x02;
export const PKT_DATA = 0x03;
export const PKT_DENY = 0x04;

export interface DeviceRecord {
  did: string;
  name: string;
  /** Raw public key, base64. */
  pub: string;
  addedAt: number;
  lastSeen: number;
}

export interface Peer {
  did: string;
  name: string;
  link: Link;
  /** Sends one message (an object) through the encrypted channel. */
  send(msg: object): void;
  onMessage: (msg: any) => void;
  onClose: () => void;
  close(): void;
}

export interface HostHooks {
  identity: Identity;
  devices: { get(did: string): DeviceRecord | undefined };
  /** True when `token` is a pairing token that is still valid; it is used up by `consumeToken`. */
  checkToken(token: Buffer): boolean;
  consumeToken(token: Buffer): void;
  /** A new phone with a valid token asks to be added: the person on the PC decides. */
  askPair(request: { did: string; name: string; pub: Buffer; sas: string; via: Link['kind'] }): Promise<boolean>;
  addDevice(device: DeviceRecord): void;
  hostInfo(): object;
  onPeer(peer: Peer): void;
  /** Something odd happened on a link (for the log), never with secrets. */
  log?(line: string): void;
}

const IDLE_MS = 45_000;
const PING_MS = 15_000;
const HANDSHAKE_MS = 20_000;
const pkt = (type: number, body: Buffer | string) => Buffer.concat([Buffer.from([type]), Buffer.isBuffer(body) ? body : Buffer.from(body)]);

/** The host's end of one connection: handshake, then an encrypted channel. */
export function acceptLink(link: Link, hooks: HostHooks): void {
  let state: 'hello' | 'auth' | 'open' | 'dead' = 'hello';
  let opener: Opener | null = null;
  let sealer: Sealer | null = null;
  let salt: Buffer = Buffer.alloc(0);
  let sas = '';
  let did = '';
  let peer: Peer | null = null;
  let idle: NodeJS.Timeout | null = null;
  let pinger: NodeJS.Timeout | null = null;
  let authing = false;
  const timer = setTimeout(() => { if (state !== 'open') die('handshake too slow'); }, HANDSHAKE_MS);

  const die = (why = 'closed') => {
    if (state === 'dead') return;
    hooks.log?.(`link ended (${why}) after ${state}`);
    const wasOpen = state === 'open';
    state = 'dead';
    clearTimeout(timer);
    if (idle) clearTimeout(idle);
    if (pinger) clearInterval(pinger);
    try { link.close(); } catch {}
    if (wasOpen) peer?.onClose();
  };
  const deny = (why: string) => {
    try { link.send(pkt(PKT_DENY, JSON.stringify({ k: 'deny', why }))); } catch {}
    setTimeout(die, 50);
    state = 'dead';
  };
  const sendEnc = (msg: object) => link.send(pkt(PKT_DATA, sealer!.seal(Buffer.from(JSON.stringify(msg)))));
  const touch = () => {
    if (idle) clearTimeout(idle);
    idle = setTimeout(() => die('silent for 45 s'), IDLE_MS);
  };

  link.onClose = die;
  link.onMessage = (data) => {
    if (state === 'dead' || data.length < 2) return;
    const type = data[0];
    const body = data.subarray(1);
    try {
      if (state === 'hello' && type === PKT_HELLO) return onHello(body);
      if (state === 'auth' && type === PKT_DATA) {
        if (authing) return;
        authing = true;
        return void onAuth(body).catch(() => die());
      }
      if (state === 'open' && type === PKT_DATA) {
        const plain = opener!.open(body);
        if (!plain) return; // forged or repeated: ignored
        touch();
        const msg = JSON.parse(plain.toString('utf8'));
        if (msg.k === 'ping') return sendEnc({ k: 'pong', t: msg.t });
        if (msg.k === 'pong') return;
        peer!.onMessage(msg);
      }
    } catch (err: any) {
      die(`error: ${err?.message ?? err}`);
    }
  };

  function onHello(body: Buffer) {
    const hello = JSON.parse(body.toString('utf8'));
    if (hello.k !== 'hello' || hello.v !== PROTOCOL_VERSION) return deny('version');
    const eC = Buffer.from(String(hello.e), 'base64');
    if (eC.length !== 65 || typeof hello.did !== 'string' || !/^[0-9a-f]{32}$/.test(hello.did)) return deny('bad hello');
    did = hello.did;
    const eph = newEphemeral();
    const nS = crypto.randomBytes(16);
    const sig = sign(hooks.identity, Buffer.concat([HOST_SIG_LABEL, body, eph.pub, nS]));
    salt = sha256(body, eph.pub, nS);
    const keys = deriveKeys(eph.agree(eC), salt);
    sas = keys.sas;
    opener = new Opener(keys.c2s);
    sealer = new Sealer(keys.s2c);
    link.send(pkt(PKT_HELLO2, JSON.stringify({ k: 'hello2', e: eph.pub.toString('base64'), n: nS.toString('base64'), sig: sig.toString('base64') })));
    state = 'auth';
  }

  async function onAuth(body: Buffer) {
    const plain = opener!.open(body);
    if (!plain) return die();
    const auth = JSON.parse(plain.toString('utf8'));
    if (auth.k !== 'auth') return die();
    const sig = Buffer.from(String(auth.sig), 'base64');
    const signed = Buffer.concat([CLIENT_SIG_LABEL, salt]);
    let name = '';
    let isNew = false;

    const known = hooks.devices.get(did);
    if (known && !auth.pair) {
      if (!verify(Buffer.from(known.pub, 'base64'), signed, sig)) return deny('bad signature');
      name = known.name;
    } else if (auth.pair) {
      const token = Buffer.from(String(auth.pair.tok), 'base64');
      const spk = Buffer.from(String(auth.pair.spk), 'base64');
      if (!hooks.checkToken(token)) { sendEnc({ k: 'denied', why: 'token' }); return setTimeout(die, 50); }
      if (!verify(spk, signed, sig)) return deny('bad signature');
      name = String(auth.pair.name || 'Phone').slice(0, 40);
      // The person on the PC may take a while to decide: the handshake timer is paused.
      clearTimeout(timer);
      const ok = await hooks.askPair({ did, name, pub: spk, sas, via: link.kind });
      if (!hooks.checkToken(token)) { sendEnc({ k: 'denied', why: 'token' }); return setTimeout(die, 50); }
      if (!ok) { sendEnc({ k: 'denied', why: 'refused' }); return setTimeout(die, 50); }
      hooks.consumeToken(token);
      hooks.addDevice({ did, name, pub: spk.toString('base64'), addedAt: Date.now(), lastSeen: Date.now() });
      isNew = true;
    } else {
      return deny('unknown device');
    }

    if ((state as string) === 'dead') return;
    state = 'open';
    clearTimeout(timer);
    peer = {
      did, name, link,
      send: (msg) => { if (state === 'open') sendEnc(msg); },
      onMessage: () => {},
      onClose: () => {},
      close: die,
    };
    sendEnc({ k: 'ready', paired: isNew, host: hooks.hostInfo() });
    touch();
    pinger = setInterval(() => { if (state === 'open') sendEnc({ k: 'ping', t: Date.now() }); }, PING_MS);
    hooks.onPeer(peer);
  }
}

// ───────────────────────── the phone's end (used by tests and `devx remote ping`) ─────────────────────────

export interface ClientOptions {
  identity: Identity;
  did: string;
  hostPub: Buffer;
  pair?: { token: Buffer; name: string };
  /** Called with the six digits while a new phone waits to be accepted. */
  onSas?: (sas: string) => void;
}

export interface ClientChannel {
  send(msg: object): void;
  onMessage: (msg: any) => void;
  onClose: () => void;
  close(): void;
  ready: any;
  sas: string;
}

/** Runs the handshake over `link`; resolves when the host says `ready`, rejects when it refuses. */
export function connectLink(link: Link, o: ClientOptions): Promise<ClientChannel> {
  return new Promise((resolve, reject) => {
    const eph = newEphemeral();
    const nC = crypto.randomBytes(16);
    const helloBody = Buffer.from(JSON.stringify({ k: 'hello', v: PROTOCOL_VERSION, did: o.did, e: eph.pub.toString('base64'), n: nC.toString('base64') }));
    let opener: Opener | null = null;
    let sealer: Sealer | null = null;
    let state: 'wait2' | 'wait-ready' | 'open' | 'dead' = 'wait2';
    let sas = '';
    let idle: NodeJS.Timeout | null = null;
    let pinger: NodeJS.Timeout | null = null;
    const channel: ClientChannel = {
      send: (msg) => { if (state === 'open') link.send(pkt(PKT_DATA, sealer!.seal(Buffer.from(JSON.stringify(msg))))); },
      onMessage: () => {}, onClose: () => {}, close: () => end(), ready: null, sas: '',
    };
    const end = (err?: Error) => {
      if (state === 'dead') return;
      const wasOpen = state === 'open';
      state = 'dead';
      clearTimeout(timer);
      if (idle) clearTimeout(idle);
      if (pinger) clearInterval(pinger);
      try { link.close(); } catch {}
      if (err) reject(err);
      if (wasOpen) channel.onClose();
    };
    // While a new phone waits for the person on the PC, the handshake may take a while.
    const timer = setTimeout(() => end(new Error('timeout')), o.pair ? 150_000 : 12_000);
    const touch = () => { if (idle) clearTimeout(idle); idle = setTimeout(() => end(), IDLE_MS); };

    link.onClose = () => end(new Error('closed'));
    link.onMessage = (data) => {
      if (state === 'dead' || data.length < 2) return;
      const type = data[0];
      const body = data.subarray(1);
      try {
        if (type === PKT_DENY) return end(new Error(`denied: ${JSON.parse(body.toString()).why}`));
        if (state === 'wait2' && type === PKT_HELLO2) {
          const h2 = JSON.parse(body.toString('utf8'));
          const eS = Buffer.from(h2.e, 'base64');
          const nS = Buffer.from(h2.n, 'base64');
          if (!verify(o.hostPub, Buffer.concat([HOST_SIG_LABEL, helloBody, eS, nS]), Buffer.from(h2.sig, 'base64'))) {
            return end(new Error('the host is not who it should be'));
          }
          const salt = sha256(helloBody, eS, nS);
          const keys = deriveKeys(eph.agree(eS), salt);
          sas = keys.sas;
          channel.sas = sas;
          opener = new Opener(keys.s2c);
          sealer = new Sealer(keys.c2s);
          const auth: any = { k: 'auth', sig: sign(o.identity, Buffer.concat([CLIENT_SIG_LABEL, salt])).toString('base64') };
          if (o.pair) auth.pair = { tok: o.pair.token.toString('base64'), spk: o.identity.pub.toString('base64'), name: o.pair.name };
          link.send(pkt(PKT_DATA, sealer.seal(Buffer.from(JSON.stringify(auth)))));
          state = 'wait-ready';
          if (o.pair) o.onSas?.(sas);
          return;
        }
        if (type !== PKT_DATA || !opener) return;
        const plain = opener.open(body);
        if (!plain) return;
        const msg = JSON.parse(plain.toString('utf8'));
        if (state === 'wait-ready') {
          if (msg.k === 'denied') return end(new Error(`denied: ${msg.why}`));
          if (msg.k !== 'ready') return end(new Error('unexpected reply'));
          state = 'open';
          clearTimeout(timer);
          channel.ready = msg;
          touch();
          pinger = setInterval(() => channel.send({ k: 'ping', t: Date.now() }), PING_MS);
          return resolve(channel);
        }
        touch();
        if (msg.k === 'ping') return channel.send({ k: 'pong', t: msg.t });
        if (msg.k === 'pong') return;
        channel.onMessage(msg);
      } catch (err: any) {
        end(new Error(err?.message ?? String(err)));
      }
    };
    link.send(pkt(PKT_HELLO, helloBody));
  });
}

export { sameSecret };
