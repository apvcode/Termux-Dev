import type { Worker } from './host.js';
import type { RtEvent } from './runtime.js';

/**
 * A chat that lives in a terminal (`devx` in a shell), seen by the host as a session like any other. The terminal does the
 * work; the host only passes things along: the phone's messages go to the terminal, what the terminal's agent does comes back
 * as the same events a background session would give. So one chat, with one history, is on both screens.
 */
export class TerminalWorker implements Worker {
  onEvent: (e: RtEvent) => void = () => {};
  onRpc: (method: string, params: any) => Promise<any> = async () => ({});
  onExit: () => void = () => {};
  /** The terminal says whether its agent is working: the phone's messages join the turn or start one. */
  running = false;

  private next = 0;
  private waiting = new Map<number, { resolve: (v: any) => void; reject: (e: Error) => void; timer: NodeJS.Timeout }>();

  /** [send] writes one message to the terminal. */
  constructor(private send: (msg: object) => void, readonly meta: { id: string; cwd: string; title: string }) {}

  /** A line from the terminal. */
  handle(msg: any): void {
    if (msg.t === 'event' && msg.e) {
      if (msg.e.e === 'turn_start') this.running = true;
      if (msg.e.e === 'turn_end') this.running = false;
      if (msg.e.e === 'state' && typeof msg.e.running === 'boolean') this.running = msg.e.running;
      this.onEvent(msg.e);
    } else if (msg.t === 'reply' && typeof msg.id === 'number') {
      const w = this.waiting.get(msg.id);
      if (w) { this.waiting.delete(msg.id); clearTimeout(w.timer); msg.ok ? w.resolve(msg.r) : w.reject(new Error(msg.err || 'failed')); }
    }
  }

  private ask(what: string, params: object = {}, timeoutMs = 10_000): Promise<any> {
    return new Promise((resolve, reject) => {
      const id = ++this.next;
      const timer = setTimeout(() => { this.waiting.delete(id); reject(new Error('The terminal does not answer.')); }, timeoutMs);
      this.waiting.set(id, { resolve, reject, timer });
      this.send({ t: 'ask', id, what, ...params });
    });
  }

  async call(m: string, p: any = {}): Promise<any> {
    switch (m) {
      case 'snapshot': return this.ask('snapshot', { limit: p?.limit });
      case 'send': {
        const queued = this.running;
        this.send({ t: 'send', text: String(p?.text ?? ''), queued });
        return { queued };
      }
      case 'stop': this.send({ t: 'stop' }); return {};
      case 'profile': return {};
      case 'busy': return { running: this.running, background: false };
      case 'close': return {};
      case 'commands': return [];
      case 'expand': return { text: null };
      case 'tasks': return [];
      case 'info': return this.ask('info');
      default: throw new Error('This chat is open in the terminal: change that there.');
    }
  }

  kill(): void {
    // The terminal is not ours to stop; its connection simply ends.
    for (const w of this.waiting.values()) { clearTimeout(w.timer); w.reject(new Error('closed')); }
    this.waiting.clear();
  }
}
