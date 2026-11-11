import crypto from 'crypto';
import mqtt, { MqttClient } from 'mqtt';
import { Link } from './secure.js';

/**
 * The relay: a public MQTT broker that passes the (already encrypted) packets between the phone and the PC when
 * they cannot reach each other directly (different networks, mobile internet, carrier NAT). It costs nothing and
 * needs no server of ours. The broker sees who talks to which topic and how much, never what is said: the
 * handshake and every frame are encrypted and signed end to end (secure.ts).
 */
export const BROKERS: Array<{ id: number; url: string }> = [
  { id: 0, url: 'wss://broker.hivemq.com:8884/mqtt' },
  { id: 1, url: 'wss://test.mosquitto.org:8081' },
  { id: 2, url: 'wss://broker.emqx.io:8084/mqtt' },
];

export const brokerUrl = (id: number): string | undefined => BROKERS.find((b) => b.id === id)?.url;

const PKT_CHUNK = 0x01;
const PKT_BYE = 0x02;
const CHUNK = 12 * 1024;
const HEADER = 1 + 8 + 4 + 2 + 2;

export const upTopic = (hostId: string) => `devx/v1/${hostId}/up`;
export const downTopic = (hostId: string, conn: string) => `devx/v1/${hostId}/dn/${conn}`;

/** Cuts a message into packets for one connection, and puts them back together. */
export class Chunker {
  private nextId = 0;
  private partial = new Map<string, { parts: Buffer[]; got: number; at: number }>();

  constructor(private conn: Buffer) {}

  split(data: Buffer): Buffer[] {
    const id = this.nextId++ >>> 0;
    const total = Math.max(1, Math.ceil(data.length / CHUNK));
    const out: Buffer[] = [];
    for (let i = 0; i < total; i++) {
      const h = Buffer.alloc(HEADER);
      h[0] = PKT_CHUNK;
      this.conn.copy(h, 1);
      h.writeUInt32BE(id, 9);
      h.writeUInt16BE(i, 13);
      h.writeUInt16BE(total, 15);
      out.push(Buffer.concat([h, data.subarray(i * CHUNK, (i + 1) * CHUNK)]));
    }
    return out;
  }

  /** The whole message when this packet completes one. */
  join(packet: Buffer): Buffer | null {
    if (packet.length < HEADER || packet[0] !== PKT_CHUNK) return null;
    const id = packet.readUInt32BE(9);
    const idx = packet.readUInt16BE(13);
    const total = packet.readUInt16BE(15);
    if (total === 0 || idx >= total || total > 400) return null;
    const body = packet.subarray(HEADER);
    if (total === 1) return body;
    const now = Date.now();
    for (const [k, v] of this.partial) if (now - v.at > 30_000) this.partial.delete(k);
    const key = String(id);
    let p = this.partial.get(key);
    if (!p) this.partial.set(key, (p = { parts: new Array(total), got: 0, at: now }));
    if (p.parts.length !== total) return null;
    if (!p.parts[idx]) { p.parts[idx] = body; p.got++; }
    if (p.got < total) return null;
    this.partial.delete(key);
    return Buffer.concat(p.parts);
  }
}

const connectOptions = (): mqtt.IClientOptions => ({
  clientId: `devx-${crypto.randomBytes(6).toString('hex')}`,
  keepalive: 30,
  reconnectPeriod: 3000,
  connectTimeout: 8000,
  clean: true,
  protocolVersion: 4,
});

export interface RelayStatus {
  id: number;
  url: string;
  connected: boolean;
}

/** The host's side: listens on the broker(s) and turns each phone that calls into a Link. */
export class RelayHost {
  private clients: Array<{ id: number; url: string; client: MqttClient; conns: Map<string, Link & { chunker: Chunker; idle: NodeJS.Timeout }> }> = [];

  constructor(private hostId: string, private onLink: (link: Link, via: string) => void, private log: (line: string) => void = () => {}) {}

  /** Brokers by id (see BROKERS) or by full ws(s):// address. */
  start(brokers: Array<number | string>): void {
    for (const b of brokers) {
      const url = typeof b === 'string' ? b : brokerUrl(b);
      const id = typeof b === 'string' ? -1 : b;
      if (!url || this.clients.some((c) => c.url === url)) continue;
      const client = mqtt.connect(url, connectOptions());
      const entry = { id, url, client, conns: new Map<string, Link & { chunker: Chunker; idle: NodeJS.Timeout }>() };
      this.clients.push(entry);
      client.on('connect', () => {
        client.subscribe(upTopic(this.hostId), { qos: 0 }, (err) => this.log(err ? `relay ${url}: subscribe failed` : `relay ${url}: listening`));
      });
      client.on('error', (err) => this.log(`relay ${url}: ${err.message}`));
      client.on('offline', () => this.log(`relay ${url}: offline`));
      client.on('message', (_topic, payload) => this.onPacket(entry, payload));
    }
  }

  private onPacket(entry: RelayHost['clients'][number], p: Buffer): void {
    if (p.length < 9) return;
    const connHex = p.subarray(1, 9).toString('hex');
    let link = entry.conns.get(connHex);
    if (p[0] === PKT_BYE) {
      if (link) { entry.conns.delete(connHex); clearTimeout(link.idle); link.onClose(); }
      return;
    }
    if (!link) {
      // A flood of unknown connections costs memory: at most a few dozen at once.
      if (entry.conns.size > 40) return;
      const chunker = new Chunker(p.subarray(1, 9));
      const l: Link & { chunker: Chunker; idle: NodeJS.Timeout } = {
        kind: 'relay',
        chunker,
        idle: setTimeout(() => close(), 120_000),
        send: (data) => { for (const part of chunker.split(data)) entry.client.publish(downTopic(this.hostId, connHex), part, { qos: 0 }); },
        close: () => close(),
        onMessage: () => {},
        onClose: () => {},
      };
      const close = () => {
        if (!entry.conns.delete(connHex)) return;
        clearTimeout(l.idle);
        l.onClose();
      };
      entry.conns.set(connHex, l);
      link = l;
      this.onLink(l, entry.url);
    }
    // A packet that does not belong to a live conversation for two minutes ends it (the phone went away).
    clearTimeout(link.idle);
    link.idle = setTimeout(() => link!.close(), 120_000);
    const whole = link.chunker.join(p);
    if (whole) link.onMessage(whole);
  }

  status(): RelayStatus[] {
    return this.clients.map((c) => ({ id: c.id, url: c.url, connected: c.client.connected }));
  }

  async stop(): Promise<void> {
    await Promise.all(this.clients.map((c) => new Promise<void>((r) => c.client.end(true, {}, () => r()))));
    this.clients = [];
  }
}

/** The phone's way to call the host over a broker (the Android app does the same in Kotlin; tests use this one). */
export function dialRelay(url: string, hostId: string, timeoutMs = 8000): Promise<Link> {
  return new Promise((resolve, reject) => {
    const conn = crypto.randomBytes(8);
    const connHex = conn.toString('hex');
    const chunker = new Chunker(conn);
    const client = mqtt.connect(url, { ...connectOptions(), reconnectPeriod: 0 });
    const timer = setTimeout(() => { client.end(true); reject(new Error('relay timeout')); }, timeoutMs);
    const link: Link = {
      kind: 'relay',
      send: (data) => { for (const part of chunker.split(data)) client.publish(upTopic(hostId), part, { qos: 0 }); },
      close: () => {
        try { client.publish(upTopic(hostId), Buffer.concat([Buffer.from([PKT_BYE]), conn]), { qos: 0 }, () => client.end(true)); } catch {}
        setTimeout(() => client.end(true), 300);
      },
      onMessage: () => {},
      onClose: () => {},
    };
    client.on('message', (_t, payload) => {
      const whole = chunker.join(payload);
      if (whole) link.onMessage(whole);
    });
    client.on('close', () => link.onClose());
    client.on('error', (e) => { clearTimeout(timer); reject(e); });
    client.on('connect', () => {
      client.subscribe(downTopic(hostId, connHex), { qos: 0 }, (err) => {
        clearTimeout(timer);
        if (err) reject(err);
        else resolve(link);
      });
    });
  });
}
