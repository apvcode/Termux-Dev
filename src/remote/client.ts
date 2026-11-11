import { ClientChannel, ClientOptions, Link, connectLink } from './secure.js';

/** The phone's way of talking to the host, in TypeScript: used by the tests and by `devx remote ping`. */
export class RemoteClient {
  private next = 0;
  private waiting = new Map<number, { resolve: (v: any) => void; reject: (e: Error) => void; timer: NodeJS.Timeout }>();
  onEvent: (session: string, seq: number, event: any) => void = () => {};
  onGlobal: (event: any) => void = () => {};
  onRpc: (method: string, params: any) => Promise<any> = async () => { throw new Error('not supported'); };
  onClose: () => void = () => {};

  private constructor(private channel: ClientChannel) {
    channel.onMessage = (m) => this.dispatch(m);
    channel.onClose = () => {
      for (const w of this.waiting.values()) { clearTimeout(w.timer); w.reject(new Error('disconnected')); }
      this.waiting.clear();
      this.onClose();
    };
  }

  static async connect(link: Link, options: ClientOptions): Promise<RemoteClient> {
    return new RemoteClient(await connectLink(link, options));
  }

  get ready(): any { return this.channel.ready; }

  request<T = any>(m: string, p: object = {}, timeoutMs = 60_000): Promise<T> {
    return new Promise((resolve, reject) => {
      const id = ++this.next;
      const timer = setTimeout(() => { this.waiting.delete(id); reject(new Error(`${m}: timeout`)); }, timeoutMs);
      this.waiting.set(id, { resolve, reject, timer });
      this.channel.send({ k: 'req', id, m, p });
    });
  }

  close(): void { this.channel.close(); }

  private dispatch(msg: any): void {
    if (msg.k === 'res') {
      const w = this.waiting.get(msg.id);
      if (!w) return;
      this.waiting.delete(msg.id);
      clearTimeout(w.timer);
      msg.ok ? w.resolve(msg.r) : w.reject(new Error(msg.err));
    } else if (msg.k === 'ev') this.onEvent(msg.s, msg.n, msg.e);
    else if (msg.k === 'g') this.onGlobal(msg.e);
    else if (msg.k === 'rpc') {
      this.onRpc(msg.m, msg.p).then((r) => this.channel.send({ k: 'rpc.res', id: msg.id, ok: true, r }), (e) => this.channel.send({ k: 'rpc.res', id: msg.id, ok: false, err: e.message }));
    }
  }
}
