import net from 'net';
import os from 'os';
import path from 'path';
import { remoteDir } from './store.js';
import type { RtEvent } from './runtime.js';

/** The same socket `devx remote` talks to the host over (see control.ts; this file avoids loading the host itself). */
function controlPath(): string {
  if (process.platform === 'win32') return `\\\\.\\pipe\\devx-remote-${os.userInfo().username}`;
  return path.join(remoteDir(), 'control.sock');
}

const clip = (text: string, max: number) => (text.length > max ? `${text.slice(0, max)}\n… (${text.length - max} more characters)` : text);
const TOOL_RESULT_CAP = 6000;
const TOOL_ARGS_CAP = 1500;

export interface TerminalChatState {
  id: string;
  title: string;
  cwd: string;
  model: string;
  provider?: string;
  plan: boolean;
  yolo: boolean;
  extra: boolean;
  cost: number;
  costEstimated: boolean;
  ctxUsed: number;
  ctxLimit: number;
}

export interface TerminalLinkOptions {
  /** The chat as the phone draws it. */
  items: () => any[];
  state: () => TerminalChatState;
}

/**
 * The terminal's side of a shared chat. While a host (`devx remote start`) runs, the terminal tells it which chat it works in
 * and passes on what the agent does; a phone that opens that chat sees it live, and what the phone writes arrives here as if it
 * had been typed. Without a host nothing happens: this keeps trying quietly, and costs nothing.
 */
export class TerminalLink {
  /** A message from the phone: it is answered as soon as the agent is free (or joins the turn that runs). */
  private inbox: string[] = [];
  /** Phone messages the agent has taken but not yet echoed: the echo says "queued_taken" for them. */
  private taken: string[] = [];
  private socket: net.Socket | null = null;
  private retry: NodeJS.Timeout | null = null;
  private closed = false;
  private registered: { id: string; title: string } | null = null;
  private running = false;
  private live = { text: '', reasoning: '', tools: new Map<string, RtEvent>(), startedAt: 0 };

  /** Called when the phone sends a message while the prompt waits (the prompt is closed so that the message is taken up). */
  onWake: (() => void) | null = null;
  /** The phone pressed stop. */
  onStop: (() => void) | null = null;

  constructor(private opts: TerminalLinkOptions) {}

  start(): void { this.connect(); }

  close(): void {
    this.closed = true;
    if (this.retry) clearTimeout(this.retry);
    this.socket?.destroy();
    this.socket = null;
  }

  private connect(): void {
    if (this.closed || this.socket) return;
    const socket = net.connect(controlPath());
    let buf = '';
    socket.setEncoding('utf8');
    socket.once('connect', () => {
      this.socket = socket;
      // A link to a host must never keep the terminal alive on its own.
      socket.unref();
      if (this.registered) this.hello(this.registered.id, this.registered.title);
    });
    socket.on('data', (d: string) => {
      buf += d;
      let i: number;
      while ((i = buf.indexOf('\n')) >= 0) {
        const raw = buf.slice(0, i);
        buf = buf.slice(i + 1);
        if (raw.trim()) { try { this.onHost(JSON.parse(raw)); } catch {} }
      }
    });
    const lost = () => {
      if (this.socket === socket) this.socket = null;
      socket.destroy();
      if (!this.closed && !this.retry) this.retry = setTimeout(() => { this.retry = null; this.connect(); }, 8000);
      this.retry?.unref();
    };
    socket.on('error', lost);
    socket.on('close', lost);
  }

  private write(msg: object): void {
    if (this.socket && !this.socket.destroyed) this.socket.write(JSON.stringify(msg) + '\n');
  }

  private hello(id: string, title: string): void {
    const s = this.opts.state();
    this.write({ t: 'hello', id, cwd: s.cwd, title });
    this.pushState();
  }

  /** The chat the terminal is in now (call it often: it only says something when the chat changed). A chat with no message yet stays private. */
  sync(id: string, title: string, hasMessages: boolean): void {
    if (!hasMessages) return;
    if (this.registered?.id === id && this.registered.title === title) return;
    const idChanged = this.registered?.id !== id;
    this.registered = { id, title };
    if (idChanged) this.hello(id, title);
    else this.pushState();
  }

  pushState(): void {
    const s = this.opts.state();
    this.event({
      e: 'state', id: s.id, title: s.title, cwd: s.cwd, running: this.running, plan: s.plan, yolo: s.yolo, extra: s.extra,
      model: s.model, provider: s.provider ?? '', effort: { supported: false, levels: [], level: null, alwaysOn: false },
      cost: s.cost, costEstimated: s.costEstimated, ctx: { used: s.ctxUsed, limit: s.ctxLimit }, trusted: true, mcpPending: 0, queued: this.inbox.length, terminal: true,
    });
  }

  private onHost(msg: any): void {
    if (msg.t === 'send') {
      const text = String(msg.text ?? '').trim();
      if (!text) return;
      this.inbox.push(text);
      if (this.running) this.event({ e: 'queued', n: this.inbox.length, text: text.slice(0, 200) });
      this.onWake?.();
    } else if (msg.t === 'stop') {
      this.onStop?.();
    } else if (msg.t === 'ask') {
      const reply = (ok: boolean, r?: any, err?: string) => this.write({ t: 'reply', id: msg.id, ok, r, err });
      try {
        if (msg.what === 'snapshot') {
          const limit = Number(msg.limit) > 0 ? Number(msg.limit) : 120;
          const all = this.opts.items();
          reply(true, {
            e: 'snapshot', state: this.stateEvent(), items: all.slice(-limit), more: Math.max(0, all.length - limit),
            live: this.running ? { text: this.live.text, reasoning: clip(this.live.reasoning, 20000), tools: [...this.live.tools.values()], perm: null, ask: null, plan: null, startedAt: this.live.startedAt } : null,
          });
        } else if (msg.what === 'info') {
          reply(true, { state: this.stateEvent(), usage: {}, context: {} });
        } else reply(false, undefined, 'unknown');
      } catch (err: any) { reply(false, undefined, err?.message ?? 'failed'); }
    }
  }

  private stateEvent(): any {
    const s = this.opts.state();
    return {
      e: 'state', id: s.id, title: s.title, cwd: s.cwd, running: this.running, plan: s.plan, yolo: s.yolo, extra: s.extra,
      model: s.model, provider: s.provider ?? '', effort: { supported: false, levels: [], level: null, alwaysOn: false },
      cost: s.cost, costEstimated: s.costEstimated, ctx: { used: s.ctxUsed, limit: s.ctxLimit }, trusted: true, mcpPending: 0, queued: this.inbox.length, terminal: true,
    };
  }

  // ───────────────────────── the phone's messages ─────────────────────────

  hasPending(): boolean { return this.inbox.length > 0; }

  /** The first message from the phone (the prompt answers it as if it had been typed). */
  takeFirst(): string | undefined { return this.inbox.shift(); }

  /** All of them, for the turn that runs: they join it like messages typed meanwhile. */
  takeAll(): string[] {
    const all = this.inbox.splice(0);
    this.taken.push(...all);
    return all;
  }

  // ───────────────────────── what the agent does ─────────────────────────

  event(e: RtEvent): void {
    this.write({ t: 'event', e });
  }

  /** A turn begins; [userText] is what started it (a message typed here, or one the phone sent). */
  turnStart(userText?: string): void {
    this.running = true;
    this.live = { text: '', reasoning: '', tools: new Map(), startedAt: Date.now() };
    this.event({ e: 'turn_start', at: this.live.startedAt });
    if (userText) this.event({ e: 'user', text: userText.split('\n\n--- Attached Files ---')[0].slice(0, 4000), files: [], images: 0 });
    this.pushState();
  }

  /** One event of the agent (the same ones the terminal draws). */
  agentEvent(event: any): void {
    switch (event.type) {
      case 'reasoning_delta': this.live.reasoning += event.delta; this.event({ e: 'reasoning', d: event.delta }); break;
      case 'text_delta': this.live.text += event.delta; this.event({ e: 'text', d: event.delta }); break;
      case 'text': this.live.text += event.content; this.event({ e: 'text', d: event.content }); break;
      case 'tool_start': {
        // What was said before a tool is a finished paragraph.
        this.live.text = '';
        this.live.reasoning = '';
        const ev: RtEvent = { e: 'tool_start', id: event.id, name: event.name, desc: event.actionDesc, args: clip(String(event.argsRaw ?? ''), TOOL_ARGS_CAP) };
        this.live.tools.set(event.id, ev);
        this.event(ev);
        // A question is answered at the keyboard of the terminal.
        if (event.name === 'ask_questions') this.event({ e: 'note', text: 'The assistant asks a question: it is answered in the terminal.' });
        break;
      }
      case 'tool_end':
        this.live.tools.delete(event.id);
        this.event({ e: 'tool_end', id: event.id, name: event.name, result: clip(String(event.result ?? ''), TOOL_RESULT_CAP), size: String(event.result ?? '').length });
        break;
      case 'user_message': {
        const text = String(event.content ?? '').split('\n\n--- Attached Files ---')[0].slice(0, 4000);
        const at = this.taken.indexOf(text);
        if (at >= 0) { this.taken.splice(at, 1); this.event({ e: 'queued_taken', text: text.slice(0, 400) }); }
        else this.event({ e: 'user', text, files: [], images: 0 });
        break;
      }
      case 'background_note': this.event({ e: 'note', text: event.content }); break;
      case 'reconnecting': this.event({ e: 'reconnecting', attempt: event.attempt, max: event.maxAttempts, reason: event.reason }); break;
      case 'reconnected': this.event({ e: 'reconnected' }); break;
      case 'system': this.event({ e: 'system', text: event.message }); break;
      case 'error': this.event({ e: 'error', text: event.message, fatal: event.isFatal }); break;
    }
  }

  turnEnd(info: { ms: number; tools: number; cost: number; aborted: boolean; failed?: string | null }): void {
    this.running = false;
    this.taken = [];
    this.event({ e: 'turn_end', ms: info.ms, cost: info.cost, tools: info.tools, tokensOut: 0, tokensIn: 0, aborted: info.aborted, failed: info.failed ?? null });
    this.pushState();
  }
}
