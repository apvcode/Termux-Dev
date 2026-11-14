import fs from 'fs';
import net from 'net';
import os from 'os';
import path from 'path';
import { RemoteHost, PairRequest } from './host.js';
import { controlPipe, remoteDir } from './store.js';
import { TerminalWorker } from './terminal-worker.js';
import { DEVX_VERSION } from '../core/types.js';

/** Where `devx remote ...` talks to the running host: a socket that only this user can open. */
export function controlPath(dir = remoteDir()): string {
  if (process.platform === 'win32') return controlPipe(dir);
  return path.join(dir, 'control.sock');
}

type Line = { id?: number; cmd?: string; ev?: string; ok?: boolean; r?: any; err?: string; [k: string]: any };

function lines(socket: net.Socket, onLine: (l: Line) => void): void {
  let buf = '';
  socket.setEncoding('utf8');
  socket.on('data', (d: string) => {
    buf += d;
    let i: number;
    while ((i = buf.indexOf('\n')) >= 0) {
      const raw = buf.slice(0, i);
      buf = buf.slice(i + 1);
      if (raw.trim()) { try { onLine(JSON.parse(raw)); } catch {} }
    }
  });
}

const write = (socket: net.Socket, msg: Line) => { if (!socket.destroyed) socket.write(JSON.stringify(msg) + '\n'); };

// ───────────────────────── the host side ─────────────────────────

export function serveControl(host: RemoteHost, onStop: () => void, dir = remoteDir()): net.Server {
  const sock = controlPath(dir);
  if (process.platform !== 'win32') { try { fs.unlinkSync(sock); } catch {} }
  let pairing: { socket: net.Socket; decide: ((ok: boolean) => void) | null } | null = null;

  // A phone that tries several ways at once (each address, the relay) asks once, not once per way.
  const asking = new Map<string, Promise<boolean>>();
  host.onPairRequest = (r: PairRequest) => {
    const known = asking.get(r.did);
    if (known) return known;
    const answer = new Promise<boolean>((resolve) => {
      if (!pairing || pairing.socket.destroyed) return resolve(false);
      const p = pairing;
      p.decide = (ok) => { p.decide = null; resolve(ok); };
      write(p.socket, { ev: 'pair-request', did: r.did, name: r.name, sas: r.sas, via: r.via });
      setTimeout(() => p.decide?.(false), 120_000).unref();
    });
    asking.set(r.did, answer);
    void answer.finally(() => setTimeout(() => asking.delete(r.did), 5000).unref());
    return answer;
  };

  const status = () => ({
    pid: process.pid,
    version: DEVX_VERSION,
    name: host.store.settings.name,
    hostId: host.hostId,
    uptime: host.uptime(),
    port: host.directPort(),
    addresses: host.lanAddresses(),
    relay: host.relayStatus(),
    devices: host.store.listDevices().map((d) => ({ did: d.did, name: d.name, addedAt: d.addedAt, lastSeen: d.lastSeen })),
    connected: host.connectedDevices(),
    sessions: host.openSessions(),
    settings: host.store.settings,
  });

  const server = net.createServer((socket) => {
    // A terminal that works in a chat: its messages are the events of that chat, and the phone's go back to it.
    let terminal: TerminalWorker | null = null;
    socket.on('close', () => { if (terminal) host.detachTerminal(terminal); terminal = null; });
    lines(socket, (msg) => {
      if (msg.t === 'hello') {
        // A new hello on the same line means the terminal moved to another chat.
        if (terminal) host.detachTerminal(terminal);
        terminal = new TerminalWorker((m) => write(socket, m as Line), { id: String(msg.id), cwd: String(msg.cwd ?? ''), title: String(msg.title ?? '') });
        host.attachTerminal(terminal);
        return;
      }
      if (msg.t && terminal) return terminal.handle(msg);
      const reply = (ok: boolean, r?: any, err?: string) => write(socket, { id: msg.id, ok, r, err });
      try {
        switch (msg.cmd) {
          case 'status': return reply(true, status());
          case 'pair': {
            const info = host.beginPairing();
            pairing = { socket, decide: null };
            socket.on('close', () => { if (pairing?.socket === socket) { pairing.decide?.(false); pairing = null; } });
            return reply(true, { uri: info.uri, expiresAt: info.expiresAt });
          }
          case 'pair-decision': pairing?.decide?.(msg.ok === true); return reply(true);
          case 'revoke': {
            const d = host.store.removeDevice(String(msg.device ?? ''));
            if (d) host.kick(d.did);
            return reply(!!d, d ? { name: d.name } : undefined, d ? undefined : 'No such device.');
          }
          case 'rename': {
            host.store.settings.name = String(msg.name ?? '').trim().slice(0, 40) || host.store.settings.name;
            host.store.saveSettings();
            return reply(true);
          }
          case 'stop': reply(true); return void setTimeout(onStop, 100);
          default: return reply(false, undefined, `Unknown command ${msg.cmd}`);
        }
      } catch (err: any) {
        reply(false, undefined, err.message);
      }
    });
    socket.on('error', () => {});
  });
  server.listen(sock, () => { if (process.platform !== 'win32') { try { fs.chmodSync(sock, 0o600); } catch {} } });
  return server;
}

// ───────────────────────── the side of `devx remote ...` ─────────────────────────

export class ControlClient {
  private next = 0;
  private waiting = new Map<number, { resolve: (v: any) => void; reject: (e: Error) => void }>();
  onEvent: (l: Line) => void = () => {};
  onClose: () => void = () => {};

  private constructor(private socket: net.Socket) {
    lines(socket, (l) => {
      if (typeof l.id === 'number') {
        const w = this.waiting.get(l.id);
        if (w) { this.waiting.delete(l.id); l.ok ? w.resolve(l.r) : w.reject(new Error(l.err || 'failed')); }
      } else if (l.ev) this.onEvent(l);
    });
    socket.on('close', () => { for (const w of this.waiting.values()) w.reject(new Error('closed')); this.onClose(); });
  }

  /** Connects to the running host, or null when none is running. */
  static connect(dir = remoteDir()): Promise<ControlClient | null> {
    return new Promise((resolve) => {
      const socket = net.connect(controlPath(dir));
      socket.once('connect', () => resolve(new ControlClient(socket)));
      socket.once('error', () => resolve(null));
    });
  }

  call(cmd: string, args: object = {}): Promise<any> {
    return new Promise((resolve, reject) => {
      const id = ++this.next;
      this.waiting.set(id, { resolve, reject });
      write(this.socket, { id, cmd, ...args });
    });
  }

  close(): void { this.socket.destroy(); }
}
