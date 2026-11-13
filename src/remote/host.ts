import crypto from 'crypto';
import fs from 'fs/promises';
import fsSync from 'fs';
import os from 'os';
import path from 'path';
import { fork, ChildProcess } from 'child_process';
import { fileURLToPath } from 'url';
import { DEVX_VERSION } from '../core/types.js';
import type { TerminalWorker } from './terminal-worker.js';
import { SessionManager } from '../core/session.js';
import { clearChatIndexCache, loadChatIndex, searchEntries } from '../core/chat-search.js';
import { ALL_PROVIDERS } from '../cli/providers.js';
import { addCustomProvider, checkBaseUrl, customProviders, removeCustomProvider } from '../core/custom-providers.js';
import { anthropicOnlyModels, formatFromBaseUrl, refreshFormats } from '../core/formats.js';
import { hostIdOf } from './crypto.js';
import { Link, Peer, acceptLink } from './secure.js';
import { DirectServer, listenDirect } from './links.js';
import { RelayHost } from './relay.js';
import { RemoteStore } from './store.js';
import { CONFIG_PATH, patchGlobalConfig } from './config.js';
import type { RtEvent } from './runtime.js';
import { createWorkerApi } from './worker-api.js';

// ───────────────────────── workers ─────────────────────────

/** One running session, wherever it lives (a child process; in tests, this process). */
export interface Worker {
  call(m: string, p?: any, timeoutMs?: number): Promise<any>;
  onEvent: (e: RtEvent) => void;
  onRpc: (method: string, params: any) => Promise<any>;
  onExit: () => void;
  kill(): void;
}

export type WorkerFactory = (cwd: string) => Promise<Worker>;

const workerEntry = () => process.env.DEVX_WORKER_ENTRY || fileURLToPath(new URL('./worker-main.js', import.meta.url));

export const spawnProcessWorker: WorkerFactory = (cwd) => new Promise((resolve, reject) => {
  const proc: ChildProcess = fork(workerEntry(), [], { cwd, stdio: ['ignore', 'ignore', 'ignore', 'ipc'], env: { ...process.env, DEVX_REMOTE_WORKER: '1' } });
  let next = 0;
  const waiting = new Map<number, { resolve: (v: any) => void; reject: (e: Error) => void; timer: NodeJS.Timeout }>();
  const w: Worker = {
    onEvent: () => {}, onRpc: async () => { throw new Error('no host'); }, onExit: () => {},
    call: (m, p, timeoutMs = 60_000) => new Promise((res, rej) => {
      if (!proc.connected) return rej(new Error('the session process is gone'));
      const id = ++next;
      const timer = setTimeout(() => { waiting.delete(id); rej(new Error(`${m}: no answer`)); }, timeoutMs);
      waiting.set(id, { resolve: res, reject: rej, timer });
      proc.send({ id, m, p });
    }),
    kill: () => { try { proc.kill(); } catch {} },
  };
  let ready = false;
  proc.on('message', (msg: any) => {
    if (msg?.ready) { ready = true; return resolve(w); }
    if (msg?.ev) return w.onEvent(msg.ev);
    if (typeof msg?.rpc === 'number') {
      w.onRpc(msg.m, msg.p).then((r) => proc.send({ rpcRes: msg.rpc, ok: true, r }), (e) => proc.send({ rpcRes: msg.rpc, ok: false, err: e.message }));
      return;
    }
    if (typeof msg?.id === 'number') {
      const c = waiting.get(msg.id);
      if (!c) return;
      waiting.delete(msg.id);
      clearTimeout(c.timer);
      msg.ok ? c.resolve(msg.r) : c.reject(new Error(msg.err));
    }
  });
  proc.on('exit', () => {
    for (const c of waiting.values()) { clearTimeout(c.timer); c.reject(new Error('the session process ended')); }
    waiting.clear();
    if (!ready) reject(new Error('the session process did not start'));
    w.onExit();
  });
  proc.on('error', (e) => { if (!ready) reject(e); });
});

/** For tests: a session that runs in this process with a fake model. */
export function inProcessWorkerFactory(providerFactory: (config: any, light?: boolean) => any): WorkerFactory {
  return async () => {
    const w: Worker = { onEvent: () => {}, onRpc: async () => '', onExit: () => {}, call: async () => ({}), kill: () => {} };
    const api = createWorkerApi({ emit: (e) => w.onEvent(e), rpc: (m, p) => w.onRpc(m, p), providerFactory });
    w.call = (m, p) => api.call(m, p ?? {});
    return w;
  };
}

interface OpenSession {
  worker: Worker;
  id: string;
  cwd: string;
  seq: number;
  peers: Set<PeerCtx>;
  lastActive: number;
  /** Something was sent in it (or it is an old chat): a new chat nobody has written in is not kept. */
  used: boolean;
  /** A permission, a question or a plan waits for an answer (orange on the phone). */
  pending: Set<string>;
  /** The chat runs in a terminal; the host only passes things along. */
  terminal?: boolean;
  /** Deltas waiting to be sent: many small pieces of text become one message every few milliseconds. */
  batch: { e: 'text' | 'reasoning'; d: string } | null;
  flush: NodeJS.Timeout | null;
  state: RtEvent | null;
}

interface PeerCtx {
  peer: Peer;
  did: string;
  name: string;
  /** What the phone says about its owner (name, and whether its memory may be read). */
  profile: { name?: string; memory?: boolean };
  attached: Set<OpenSession>;
  rpcWaiting: Map<string, { resolve: (v: any) => void; reject: (e: Error) => void; timer: NodeJS.Timeout }>;
}

interface Upload {
  uid: string;
  name: string;
  mime: string;
  size: number;
  file: string;
  got: number;
  owner: string;
  at: number;
}

const MAX_UPLOAD = 25 * 1024 * 1024;
const IDLE_WORKER_MS = 10 * 60_000;

export interface HostOptions {
  workerFactory?: WorkerFactory;
  log?: (line: string) => void;
  /** Brokers by id or full address; the phone is told the ids (see relay.ts). */
  brokers?: Array<number | string>;
  /** Port 0 picks a free one (tests). */
  port?: number;
  bindHost?: string;
}

export interface PairInfo {
  uri: string;
  expiresAt: number;
}

export interface PairRequest {
  did: string;
  name: string;
  sas: string;
  via: Link['kind'];
}

/** The program that holds the phones' connections and the sessions they work in. */
export class RemoteHost {
  readonly store: RemoteStore;
  private opts: HostOptions;
  private direct: DirectServer | null = null;
  private relay: RelayHost | null = null;
  private peers = new Set<PeerCtx>();
  private sessions = new Set<OpenSession>();
  private uploads = new Map<string, Upload>();
  private tokens = new Map<string, number>();
  private reaper: NodeJS.Timeout | null = null;
  private startedAt = Date.now();
  private workerFactory: WorkerFactory;
  /** Who decides about a new phone (the terminal that ran `devx remote pair`). Nobody: refuse. */
  onPairRequest: (r: PairRequest) => Promise<boolean> = async () => false;
  /** Something changed that the screens of `devx remote` may want to show. */
  onChange: () => void = () => {};

  constructor(store: RemoteStore, opts: HostOptions = {}) {
    this.store = store;
    this.opts = opts;
    this.workerFactory = opts.workerFactory ?? spawnProcessWorker;
    try {
      const raw = JSON.parse(fsSync.readFileSync(path.join(this.store.dir, 'marks.json'), 'utf8'));
      for (const [id, kind] of Object.entries<any>(raw)) if (kind === 'unread' || kind === 'error') this.marks.set(id, kind);
    } catch {}
  }

  /**
   * What each chat tells the phone: "unread" (the agent finished and nobody has looked), "error" (the provider failed).
   * Kept in a small file, so that it survives the host. A chat that waits for an answer is "ask" while it is open.
   */
  private marks = new Map<string, 'unread' | 'error'>();

  private setMark(id: string, kind: 'unread' | 'error' | null): void {
    if (!id || (this.marks.get(id) ?? null) === kind) return;
    if (kind) this.marks.set(id, kind); else this.marks.delete(id);
    try { fsSync.writeFileSync(path.join(this.store.dir, 'marks.json'), JSON.stringify(Object.fromEntries(this.marks)), { mode: 0o600 }); } catch {}
    this.broadcastGlobal({ e: 'sessions' });
  }

  private statusOf(id: string): 'unread' | 'error' | 'ask' | null {
    const mark = this.marks.get(id);
    if (mark === 'error') return 'error';
    const open = [...this.sessions].find((x) => x.id === id);
    if (open && open.pending.size > 0) return 'ask';
    return mark ?? null;
  }

  /** How many chats want a look: the number on the phone's button. */
  private attention() {
    const ids = new Set<string>([...this.marks.keys(), ...[...this.sessions].filter((x) => x.pending.size > 0).map((x) => x.id)]);
    let unread = 0, ask = 0, error = 0;
    for (const id of ids) { const st = this.statusOf(id); if (st === 'unread') unread++; else if (st === 'ask') ask++; else if (st === 'error') error++; }
    return { unread, ask, error, total: unread + ask + error };
  }

  private log(line: string) { this.opts.log?.(line); }

  get hostId(): string { return hostIdOf(this.store.identity.pub); }

  // ───────────────────────── life ─────────────────────────

  async start(): Promise<{ port: number }> {
    const onLink = (link: Link) => acceptLink(link, this.hooks());
    const port = this.opts.port ?? this.store.settings.port;
    // The port is taken (another devx, or a host that is still shutting down): take the next one, so that
    // the QR code never points at somebody else's listener. The relay works regardless.
    for (const candidate of [port, port + 1, port + 2, port + 3, 0]) {
      try {
        this.direct = await listenDirect(candidate, (link) => onLink(link), this.opts.bindHost);
        if (candidate !== port) this.log(`port ${port} is taken: listening on ${this.direct.port}`);
        break;
      } catch (err: any) {
        this.log(`direct listener failed on port ${candidate}: ${err.message}`);
        if (err?.code !== 'EADDRINUSE') break;
      }
    }
    const brokers = this.opts.brokers ?? (this.store.settings.relay ? this.store.settings.brokers : []);
    if (brokers.length > 0) {
      this.relay = new RelayHost(this.hostId, (link) => onLink(link), (line) => this.log(line));
      this.relay.start(brokers);
    }
    this.reaper = setInterval(() => void this.reapIdle(), 60_000);
    this.reaper.unref();
    return { port: this.direct?.port ?? 0 };
  }

  async stop(): Promise<void> {
    if (this.reaper) clearInterval(this.reaper);
    for (const p of this.peers) p.peer.close();
    await this.relay?.stop();
    await this.direct?.close();
    for (const s of this.sessions) { void s.worker.call('close').catch(() => null); setTimeout(() => s.worker.kill(), 500).unref(); }
    this.sessions.clear();
  }

  private hooks() {
    return {
      identity: this.store.identity,
      devices: this.store.devices,
      checkToken: (t: Buffer) => this.tokenValid(t),
      consumeToken: (t: Buffer) => void this.tokens.delete(t.toString('hex')),
      askPair: (r: PairRequest & { pub: Buffer }) => this.onPairRequest({ did: r.did, name: r.name, sas: r.sas, via: r.via }),
      addDevice: (d: any) => { this.store.addDevice(d); this.onChange(); },
      hostInfo: () => this.hostInfo(),
      onPeer: (peer: Peer) => this.attachPeer(peer),
      log: (line: string) => this.log(line),
    };
  }

  hostInfo() {
    return { name: this.store.settings.name, os: `${os.platform()} ${os.arch()}`, version: DEVX_VERSION, home: os.homedir(), hostId: this.hostId, protocol: 1, addresses: this.lanAddresses() };
  }

  // ───────────────────────── pairing ─────────────────────────

  lanAddresses(): string[] {
    const port = this.direct?.port ?? this.store.settings.port;
    const out: string[] = [];
    for (const list of Object.values(os.networkInterfaces())) {
      for (const i of list ?? []) if (i.family === 'IPv4' && !i.internal) out.push(`${i.address}:${port}`);
    }
    return out;
  }

  beginPairing(): PairInfo {
    const token = crypto.randomBytes(16);
    const expiresAt = Date.now() + 5 * 60_000;
    this.tokens.set(token.toString('hex'), expiresAt);
    const payload = {
      v: 1,
      hp: this.store.identity.pub.toString('base64url'),
      t: token.toString('base64url'),
      n: this.store.settings.name,
      a: this.lanAddresses(),
      r: this.opts.brokers?.filter((b): b is number => typeof b === 'number') ?? (this.store.settings.relay ? this.store.settings.brokers : []),
    };
    return { uri: `devx1:${Buffer.from(JSON.stringify(payload)).toString('base64url')}`, expiresAt };
  }

  private tokenValid(t: Buffer): boolean {
    const exp = this.tokens.get(t.toString('hex'));
    if (!exp) return false;
    if (exp < Date.now()) { this.tokens.delete(t.toString('hex')); return false; }
    return true;
  }

  // ───────────────────────── peers ─────────────────────────

  private attachPeer(peer: Peer): void {
    const ctx: PeerCtx = { peer, did: peer.did, name: peer.name, profile: {}, attached: new Set(), rpcWaiting: new Map() };
    this.peers.add(ctx);
    this.store.touch(peer.did);
    this.log(`phone connected: ${peer.name} via ${peer.link.kind}`);
    this.onChange();
    peer.onMessage = (msg) => void this.onMessage(ctx, msg);
    peer.onClose = () => {
      this.peers.delete(ctx);
      for (const s of ctx.attached) { s.peers.delete(ctx); this.dropIfEmpty(s); }
      for (const w of ctx.rpcWaiting.values()) { clearTimeout(w.timer); w.reject(new Error('the phone disconnected')); }
      this.log(`phone left: ${ctx.name}`);
      this.onChange();
    };
  }

  /** Disconnects a phone that was removed. */
  kick(did: string): void {
    for (const p of [...this.peers]) if (p.did === did) p.peer.close();
  }

  connectedDevices(): Array<{ did: string; name: string; via: string }> {
    return [...this.peers].map((p) => ({ did: p.did, name: p.name, via: p.peer.link.kind }));
  }

  openSessions(): Array<{ id: string; cwd: string; running: boolean; peers: number; title?: string }> {
    return [...this.sessions].map((s) => ({ id: s.id, cwd: s.cwd, running: !!s.state?.running, peers: s.peers.size, title: s.state?.title }));
  }

  relayStatus() { return this.relay?.status() ?? []; }
  directPort(): number { return this.direct?.port ?? 0; }
  uptime(): number { return Date.now() - this.startedAt; }

  private async onMessage(ctx: PeerCtx, msg: any): Promise<void> {
    if (msg.k === 'rpc.res') {
      const w = ctx.rpcWaiting.get(msg.id);
      if (w) { ctx.rpcWaiting.delete(msg.id); clearTimeout(w.timer); msg.ok ? w.resolve(msg.r) : w.reject(new Error(msg.err || 'failed')); }
      return;
    }
    if (msg.k !== 'req' || typeof msg.id !== 'number' || typeof msg.m !== 'string') return;
    try {
      const r = await this.handle(ctx, msg.m, msg.p ?? {});
      ctx.peer.send({ k: 'res', id: msg.id, ok: true, r });
    } catch (err: any) {
      ctx.peer.send({ k: 'res', id: msg.id, ok: false, err: err?.message ?? String(err) });
    }
  }

  // ───────────────────────── requests ─────────────────────────

  private async handle(ctx: PeerCtx, m: string, p: any): Promise<any> {
    switch (m) {
      case 'hello': {
        ctx.profile = { name: typeof p.name === 'string' ? p.name.slice(0, 60) : undefined, memory: p.memory === true };
        for (const s of ctx.attached) void s.worker.call('profile', this.profileFor(s)).catch(() => null);
        return { host: this.hostInfo(), sessions: this.openSessions() };
      }
      case 'sessions.list': return this.listSessions(p);
      case 'attention': return this.attention();
      case 'session.open': return this.openSession(ctx, p);
      case 'session.detach': { const s = this.byId(p.s); if (s) { s.peers.delete(ctx); ctx.attached.delete(s); this.dropIfEmpty(s); } return {}; }
      case 'session.delete': return this.deleteSession(String(p.id));
      case 'dirs.list': return this.listDirs(p.path);
      case 'models.list': return this.listModels(p.provider);
      case 'providers.list': return this.listProviders();
      case 'provider.add': return this.addProvider(p);
      case 'provider.key': return this.setProviderKey(p);
      case 'provider.remove': return this.removeProvider(String(p.id));
      case 'upload.begin': return this.uploadBegin(ctx, p);
      case 'upload.chunk': return this.uploadChunk(ctx, p);
      case 'upload.end': return this.uploadEnd(ctx, p);
    }
    // Everything else belongs to a session the phone has open.
    const s = this.byId(String(p.s ?? ''));
    if (!s) throw new Error('That session is not open.');
    s.lastActive = Date.now();
    switch (m) {
      case 'session.send': {
        s.used = true;
        this.setMark(s.id, null);
        const files = await this.takeUploads(ctx, s, Array.isArray(p.files) ? p.files : []);
        return s.worker.call('send', { text: String(p.text ?? ''), files });
      }
      case 'session.stop': return s.worker.call('stop');
      case 'session.mode': return s.worker.call('mode', { plan: p.plan, yolo: p.yolo, extra: p.extra });
      case 'session.model': return s.worker.call('model', { provider: p.provider, model: String(p.model) });
      case 'session.effort': return s.worker.call('effort', { level: p.level ?? null });
      case 'session.rename': { await s.worker.call('rename', { title: String(p.title ?? '') }); clearChatIndexCache(); this.broadcastGlobal({ e: 'sessions' }); return {}; }
      case 'session.compact': return s.worker.call('compact', {}, 180_000);
      case 'session.undo': return s.worker.call('undo');
      case 'session.info': return s.worker.call('info');
      case 'session.tasks': return s.worker.call('tasks');
      case 'session.stopTask': return s.worker.call('stopTask', { id: p.id });
      case 'session.trust': return s.worker.call('trust');
      case 'session.mcp': return s.worker.call('mcp', { args: String(p.args ?? '') }, 120_000);
      case 'session.reset': { const r = await s.worker.call('reset'); return r; }
      case 'session.snapshot': return { ...(await s.worker.call('snapshot', { limit: p.limit })), seq: s.seq };
      case 'commands.list': return s.worker.call('commands');
      case 'session.expand': return s.worker.call('expand', { line: String(p.line ?? '') });
      case 'perm.answer': return s.worker.call('perm', { id: p.id, answer: String(p.answer) });
      case 'ask.answer': return s.worker.call('ask', { id: p.id, answers: p.answers ?? [] });
      case 'plan.decide': return s.worker.call('plan', { id: p.id, choice: p.choice });
      default: throw new Error(`Unknown request: ${m}`);
    }
  }

  private byId(id: string): OpenSession | undefined {
    for (const s of this.sessions) if (s.id === id) return s;
    return undefined;
  }

  private profileFor(s: OpenSession): { name?: string; memory?: boolean } {
    const holders = [...s.peers].filter((p) => p.profile.name || p.profile.memory);
    const last = holders[holders.length - 1];
    return { name: last?.profile.name, memory: holders.some((p) => p.profile.memory) };
  }

  // ───────────────────────── sessions ─────────────────────────

  private async listSessions(p: { q?: string; limit?: number }) {
    const entries = await loadChatIndex();
    const open = new Map([...this.sessions].map((s) => [s.id, s]));
    const row = (e: (typeof entries)[number]) => ({
      id: e.id, title: e.title, cwd: e.cwd ?? null, model: e.model, updatedAt: e.updatedAt, createdAt: e.createdAt, messages: e.messageCount,
      open: open.has(e.id), running: !!open.get(e.id)?.state?.running, status: this.statusOf(e.id), terminal: !!open.get(e.id)?.terminal,
    });
    const q = (p.q ?? '').trim();
    if (q) {
      const hits = searchEntries(entries, q, { limit: p.limit ?? 30 });
      return { sessions: hits.map((h) => ({ ...row(h.entry), snippet: h.snippets[0]?.text.slice(0, 200) ?? '' })) };
    }
    const rows = entries.slice(0, p.limit ?? 200).map(row);
    // A chat that was just written in but is not saved yet is still shown; an empty new one is not.
    for (const s of this.sessions) if (s.used && !rows.some((r) => r.id === s.id)) rows.unshift({ id: s.id, title: s.state?.title ?? 'New Session', cwd: s.cwd, model: String(s.state?.model ?? ''), updatedAt: Date.now(), createdAt: Date.now(), messages: 0, open: true, running: !!s.state?.running, status: this.statusOf(s.id), terminal: !!s.terminal });
    return { sessions: rows };
  }

  private async openSession(ctx: PeerCtx, p: { id?: string; cwd?: string; plan?: boolean }) {
    let s = p.id ? this.byId(p.id) : undefined;
    if (!s) {
      let cwd = p.cwd;
      if (p.id) {
        const entries = await loadChatIndex();
        cwd = entries.find((e) => e.id === p.id)?.cwd ?? cwd ?? os.homedir();
      }
      cwd = path.resolve(String(cwd || os.homedir()).replace(/^~(?=\/|$)/, os.homedir()));
      const st = await fs.stat(cwd).catch(() => null);
      if (!st?.isDirectory()) throw new Error(`The folder ${cwd} does not exist on the computer.`);
      s = await this.spawn(cwd, p.id, p.plan);
    }
    s.peers.add(ctx);
    ctx.attached.add(s);
    this.setMark(s.id, null);
    void s.worker.call('profile', this.profileFor(s)).catch(() => null);
    const snap = await s.worker.call('snapshot', {});
    s.state = snap.state;
    return { s: s.id, ...snap, seq: s.seq };
  }

  /**
   * A terminal tells the host it is working in a chat. If a background worker of the host has that chat open, the terminal
   * takes over (the phones that look at it keep looking, now at the terminal's chat): two copies of one chat would drift apart.
   */
  attachTerminal(worker: TerminalWorker): void {
    const { id, cwd, title } = worker.meta;
    const old = this.byId(id);
    const s: OpenSession = { worker, id, cwd, seq: old?.seq ?? 0, peers: old?.peers ?? new Set(), lastActive: Date.now(), used: true, pending: new Set(), terminal: true, batch: null, flush: null, state: null };
    if (old) { this.sessions.delete(old); old.worker.onExit = () => {}; void old.worker.call('close').catch(() => null); setTimeout(() => old.worker.kill(), 500).unref(); }
    worker.onEvent = (e) => this.onSessionEvent(s, e);
    worker.onExit = () => {
      this.sessions.delete(s);
      for (const ctx of s.peers) { ctx.attached.delete(s); ctx.peer.send({ k: 'ev', s: s.id, n: ++s.seq, e: { e: 'gone' } }); }
      this.onChange();
      this.broadcastGlobal({ e: 'sessions' });
    };
    this.sessions.add(s);
    for (const ctx of s.peers) ctx.attached.add(s);
    // The phones that were looking at the old copy read the chat again, now from the terminal.
    if (old && s.peers.size > 0) this.send(s, { e: 'undone' });
    this.onChange();
    clearChatIndexCache();
    this.broadcastGlobal({ e: 'sessions' });
  }

  /** The terminal closed or moved to another chat. */
  detachTerminal(worker: TerminalWorker): void {
    const s = [...this.sessions].find((x) => x.worker === worker);
    if (s) worker.onExit();
  }

  private async spawn(cwd: string, sessionId?: string, plan?: boolean): Promise<OpenSession> {
    const worker = await this.workerFactory(cwd);
    const s: OpenSession = { worker, id: '', cwd, seq: 0, peers: new Set(), lastActive: Date.now(), used: !!sessionId, pending: new Set(), batch: null, flush: null, state: null };
    worker.onEvent = (e) => this.onSessionEvent(s, e);
    worker.onRpc = (method, params) => this.onWorkerRpc(s, method, params);
    worker.onExit = () => {
      this.sessions.delete(s);
      for (const ctx of s.peers) { ctx.attached.delete(s); ctx.peer.send({ k: 'ev', s: s.id, n: ++s.seq, e: { e: 'gone' } }); }
      this.onChange();
    };
    try {
      const r = await worker.call('init', { cwd, sessionId, planMode: plan }, 120_000);
      s.id = r.id;
    } catch (err) {
      worker.kill();
      throw err;
    }
    this.sessions.add(s);
    this.onChange();
    return s;
  }

  private onSessionEvent(s: OpenSession, e: RtEvent): void {
    s.lastActive = Date.now();
    if (e.e === 'text' || e.e === 'reasoning') {
      if (s.batch && s.batch.e !== e.e) this.flush(s);
      s.batch = s.batch ? { e: e.e, d: s.batch.d + e.d } : { e: e.e, d: e.d };
      if (!s.flush) s.flush = setTimeout(() => this.flush(s), 40);
      return;
    }
    this.flush(s);
    if (e.e === 'perm' || e.e === 'ask' || e.e === 'plan') { s.pending.add(e.e); this.broadcastGlobal({ e: 'sessions' }); }
    if (e.e === 'perm_done' || e.e === 'ask_done' || e.e === 'plan_done') { s.pending.delete(e.e.replace('_done', '')); this.broadcastGlobal({ e: 'sessions' }); }
    if (e.e === 'turn_start') { s.pending.clear(); this.setMark(s.id, null); }
    if (e.e === 'error') this.setMark(s.id, 'error');
    if (e.e === 'turn_end') {
      s.pending.clear();
      if (!e.aborted) {
        if (e.failed) this.setMark(s.id, 'error');
        else if (s.peers.size === 0 && !s.terminal) this.setMark(s.id, 'unread');
      }
    }
    if (e.e === 'state') {
      const changedTitle = s.state?.title !== e.title;
      s.state = e;
      if (e.id && e.id !== s.id) s.id = e.id;
      if (changedTitle || s.state?.running !== e.running) { clearChatIndexCache(); this.broadcastGlobal({ e: 'sessions' }); }
    }
    if (e.e === 'reset' && e.id) { s.id = e.id; s.seq = 0; clearChatIndexCache(); this.broadcastGlobal({ e: 'sessions' }); }
    if (e.e === 'turn_end') { clearChatIndexCache(); this.broadcastGlobal({ e: 'sessions' }); }
    this.send(s, e);
  }

  private flush(s: OpenSession): void {
    if (s.flush) { clearTimeout(s.flush); s.flush = null; }
    if (!s.batch) return;
    const b = s.batch;
    s.batch = null;
    this.send(s, { e: b.e, d: b.d });
  }

  private send(s: OpenSession, e: RtEvent): void {
    const n = ++s.seq;
    for (const ctx of s.peers) ctx.peer.send({ k: 'ev', s: s.id, n, e });
  }

  private broadcastGlobal(e: RtEvent): void {
    for (const ctx of this.peers) ctx.peer.send({ k: 'g', e });
  }

  private async onWorkerRpc(s: OpenSession, method: string, params: any): Promise<any> {
    if (method !== 'phone_memory') throw new Error('unknown');
    const holders = [...s.peers].filter((p) => p.profile.memory);
    const ctx = holders[holders.length - 1];
    if (!ctx) throw new Error('no connected phone shares its memory');
    return new Promise((resolve, reject) => {
      const id = crypto.randomBytes(4).toString('hex');
      const timer = setTimeout(() => { ctx.rpcWaiting.delete(id); reject(new Error('the phone did not answer in time')); }, 15_000);
      ctx.rpcWaiting.set(id, { resolve, reject, timer });
      ctx.peer.send({ k: 'rpc', id, m: 'memory', p: params });
    });
  }

  private async deleteSession(id: string): Promise<{ deleted: boolean }> {
    this.setMark(id, null);
    const open = this.byId(id);
    if (open) {
      await open.worker.call('close').catch(() => null);
      open.worker.kill();
      this.sessions.delete(open);
    }
    const ok = await SessionManager.deleteSession(id);
    clearChatIndexCache();
    this.broadcastGlobal({ e: 'sessions' });
    this.onChange();
    return { deleted: ok };
  }

  /** A new chat that nobody wrote in is gone as soon as the phone leaves it: nothing stays behind on the computer. */
  private dropIfEmpty(s: OpenSession): void {
    if (s.used || s.peers.size > 0) return;
    void s.worker.call('close').catch(() => null);
    setTimeout(() => s.worker.kill(), 500).unref();
    this.sessions.delete(s);
    this.onChange();
  }

  private async reapIdle(): Promise<void> {
    for (const s of [...this.sessions]) {
      if (s.terminal || s.peers.size > 0 || Date.now() - s.lastActive < IDLE_WORKER_MS) continue;
      const busy = await s.worker.call('busy', {}, 5000).catch(() => ({ running: true }));
      if (busy.running || busy.background) { s.lastActive = Date.now(); continue; }
      void s.worker.call('close').catch(() => null);
      setTimeout(() => s.worker.kill(), 500).unref();
      this.sessions.delete(s);
    }
    for (const [k, t] of this.tokens) if (t < Date.now()) this.tokens.delete(k);
    for (const u of this.uploads.values()) if (Date.now() - u.at > 3600_000) { this.uploads.delete(u.uid); void fs.rm(path.dirname(u.file), { recursive: true, force: true }); }
  }

  // ───────────────────────── folders ─────────────────────────

  private async listDirs(raw?: string) {
    const home = os.homedir();
    const dir = path.resolve(String(raw || home).replace(/^~(?=\/|$)/, home));
    const entries = await fs.readdir(dir, { withFileTypes: true }).catch(() => { throw new Error(`Cannot open ${dir}`); });
    const dirs = entries.filter((e) => e.isDirectory() && !e.name.startsWith('.') && e.name !== 'node_modules').map((e) => e.name).sort((a, b) => a.localeCompare(b));
    const recent = [...new Set((await loadChatIndex()).map((e) => e.cwd).filter((c): c is string => !!c))].slice(0, 8);
    const parent = path.dirname(dir);
    return { path: dir, parent: parent === dir ? null : parent, home, dirs, recent };
  }

  // ───────────────────────── providers and models ─────────────────────────

  private async readConfig(): Promise<any> {
    try { return JSON.parse(await fs.readFile(CONFIG_PATH(), 'utf8')); } catch { return {}; }
  }

  private async listProviders() {
    const c = await this.readConfig();
    const custom = customProviders(c);
    const out: any[] = [];
    for (const [id, cp] of Object.entries(custom)) out.push({ id, name: cp.name, baseUrl: cp.baseUrl, hasKey: !!c.apiKeys?.[id], custom: true, current: c.provider === id });
    for (const pr of ALL_PROVIDERS) {
      const hasKey = !!c.apiKeys?.[pr.value];
      if (hasKey || c.provider === pr.value) out.push({ id: pr.value, name: pr.label, baseUrl: c.baseUrls?.[pr.value] || pr.baseUrl, hasKey, custom: false, current: c.provider === pr.value });
    }
    // Providers that are not set up yet, so that one can be added from the list.
    const available = ALL_PROVIDERS.filter((pr) => !c.apiKeys?.[pr.value] && c.provider !== pr.value).map((pr) => ({ id: pr.value, name: pr.label, baseUrl: pr.baseUrl }));
    return { providers: out, available, current: { provider: c.provider, model: c.model } };
  }

  private async listModels(providerId?: string) {
    const c = await this.readConfig();
    const id = providerId || c.provider;
    const listed = ALL_PROVIDERS.find((p) => p.value === id);
    const baseUrl: string = c.baseUrls?.[id] || customProviders(c)[id]?.baseUrl || listed?.baseUrl || c.baseUrl;
    const apiKey: string = c.apiKeys?.[id] ?? (id === c.provider ? c.apiKey : '') ?? '';
    if (!baseUrl) throw new Error('Unknown provider.');
    const headers: Record<string, string> = {};
    if (formatFromBaseUrl(baseUrl) === 'anthropic') {
      headers['anthropic-version'] = '2023-06-01';
      if (apiKey) headers['x-api-key'] = apiKey;
    } else if (apiKey && apiKey !== 'no-key' && apiKey !== 'ollama') headers.Authorization = `Bearer ${apiKey}`;
    const res = await fetch(`${baseUrl.replace(/\/$/, '')}/models`, { headers, signal: AbortSignal.timeout(15_000) }).catch((e) => { throw new Error(`Cannot reach the provider: ${e.message}`); });
    if (!res.ok) throw new Error(res.status === 401 || res.status === 403 ? 'The provider rejected the API key.' : `The provider answered HTTP ${res.status}.`);
    const data: any = await res.json().catch(() => ({}));
    const models: Array<{ id: string; name: string; context: number | null; viaAnthropic?: boolean }> = (Array.isArray(data?.data) ? data.data : []).map((m: any) => ({ id: String(m.id), name: m.name || m.display_name || m.id, context: m.context_length || m.max_context_length || m.limit?.context || null }));
    // A gateway lists its Claude models only in the Anthropic format, with the same key: ask for that list too, as the terminal does.
    if (c.probeEndpoints !== false) {
      await refreshFormats({ baseUrl, apiKey }, { force: true }).catch(() => null);
      const known = new Set(models.map((m) => m.id.toLowerCase()));
      for (const mid of anthropicOnlyModels(baseUrl)) if (!known.has(mid.toLowerCase())) models.push({ id: mid, name: mid, context: null, viaAnthropic: true });
    }
    return { provider: id, models };
  }

  private async addProvider(p: { name: string; baseUrl: string; apiKey?: string }) {
    const url = checkBaseUrl(String(p.baseUrl ?? ''));
    if (!url.ok) throw new Error(url.reason === 'scheme' ? 'The address must start with http:// or https://' : 'The address is not valid.');
    const name = String(p.name ?? '').trim();
    if (!name) throw new Error('Give the provider a name.');
    let id = '';
    await patchGlobalConfig((c) => { id = addCustomProvider(c, { name, baseUrl: url.url, apiKey: String(p.apiKey ?? '') || 'no-key' }, ALL_PROVIDERS.map((x) => x.value)); });
    return { id };
  }

  private async setProviderKey(p: { id: string; apiKey: string }) {
    const listed = ALL_PROVIDERS.find((x) => x.value === p.id);
    await patchGlobalConfig((c) => {
      c.apiKeys = { ...(c.apiKeys || {}), [p.id]: String(p.apiKey) };
      if (listed && !customProviders(c)[p.id]) c.baseUrls = { ...(c.baseUrls || {}), [p.id]: c.baseUrls?.[p.id] || listed.baseUrl };
      if (c.provider === p.id) c.apiKey = String(p.apiKey);
    });
    return {};
  }

  private async removeProvider(id: string) {
    let removed = false;
    await patchGlobalConfig((c) => { removed = removeCustomProvider(c, id).removed; });
    return { removed };
  }

  // ───────────────────────── files from the phone ─────────────────────────

  private async uploadBegin(ctx: PeerCtx, p: { name: string; mime: string; size: number }) {
    const size = Number(p.size);
    if (!Number.isFinite(size) || size < 0 || size > MAX_UPLOAD) throw new Error(`Files up to ${MAX_UPLOAD / 1024 / 1024} MB can be sent.`);
    const uid = crypto.randomBytes(8).toString('hex');
    const name = safeName(String(p.name || 'file'));
    const dir = path.join(this.store.dir, 'uploads', uid);
    await fs.mkdir(dir, { recursive: true, mode: 0o700 });
    const file = path.join(dir, name);
    await fs.writeFile(file, '');
    this.uploads.set(uid, { uid, name, mime: String(p.mime || 'application/octet-stream'), size, file, got: 0, owner: ctx.did, at: Date.now() });
    return { uid };
  }

  private async uploadChunk(ctx: PeerCtx, p: { uid: string; data: string }) {
    const u = this.uploads.get(p.uid);
    if (!u || u.owner !== ctx.did) throw new Error('Unknown upload.');
    const buf = Buffer.from(String(p.data), 'base64');
    if (u.got + buf.length > u.size) throw new Error('More data than announced.');
    await fs.appendFile(u.file, buf);
    u.got += buf.length;
    return { got: u.got };
  }

  private async uploadEnd(ctx: PeerCtx, p: { uid: string }) {
    const u = this.uploads.get(p.uid);
    if (!u || u.owner !== ctx.did) throw new Error('Unknown upload.');
    if (u.got !== u.size) throw new Error(`The file is incomplete (${u.got} of ${u.size} bytes).`);
    return { name: u.name, size: u.size };
  }

  /** The files the phone sent move into the folder of the chat, where the agent can reach them. */
  private async takeUploads(ctx: PeerCtx, s: OpenSession, uids: string[]) {
    const out: Array<{ name: string; mime: string; path: string }> = [];
    if (uids.length === 0) return out;
    const dest = path.join(s.cwd, '.devx', 'uploads');
    await fs.mkdir(dest, { recursive: true });
    for (const uid of uids) {
      const u = this.uploads.get(uid);
      if (!u || u.owner !== ctx.did || u.got !== u.size) throw new Error('A file is not fully uploaded.');
      let target = path.join(dest, u.name);
      for (let n = 2; fsSync.existsSync(target); n++) target = path.join(dest, `${path.parse(u.name).name}-${n}${path.extname(u.name)}`);
      await fs.copyFile(u.file, target);
      await fs.rm(path.dirname(u.file), { recursive: true, force: true });
      this.uploads.delete(uid);
      out.push({ name: path.basename(target), mime: u.mime, path: target });
    }
    return out;
  }
}

export function safeName(name: string): string {
  const base = path.basename(name).replace(/[\\/:*?"<>|\x00-\x1f]/g, '_').replace(/^\.+/, '');
  return base.slice(0, 120) || 'file';
}
