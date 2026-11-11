/**
 * The process of one session. The host starts it in the folder of the chat, so everything devx does there
 * (tools, memory, permissions) sees the right working directory, and one chat cannot disturb another.
 * It talks to the host over the IPC channel of child_process.fork.
 */
import { createWorkerApi } from './worker-api.js';
import { installCapabilities } from '../core/capabilities.js';

// What each model offers (reasoning levels, wire formats): the stored answers of the endpoints and the catalogue, as in the terminal.
installCapabilities();

type Call = { id: number; m: string; p: any };

let rpcCounter = 0;
const rpcWaiting = new Map<number, { resolve: (v: any) => void; reject: (e: Error) => void }>();
const send = (msg: object) => { try { process.send?.(msg); } catch {} };

const api = createWorkerApi({
  emit: (e) => send({ ev: e }),
  rpc: (method, params) => new Promise((resolve, reject) => {
    const id = ++rpcCounter;
    const timer = setTimeout(() => { rpcWaiting.delete(id); reject(new Error('no answer')); }, 20_000);
    rpcWaiting.set(id, { resolve: (v) => { clearTimeout(timer); resolve(v); }, reject: (e) => { clearTimeout(timer); reject(e); } });
    send({ rpc: id, m: method, p: params });
  }),
});

process.on('message', async (msg: any) => {
  if (msg && typeof msg.rpcRes === 'number') {
    const w = rpcWaiting.get(msg.rpcRes);
    rpcWaiting.delete(msg.rpcRes);
    if (w) (msg.ok ? w.resolve(msg.r) : w.reject(new Error(msg.err || 'failed')));
    return;
  }
  const call = msg as Call;
  if (!call || typeof call.id !== 'number') return;
  try {
    const r = await api.call(call.m, call.p);
    send({ id: call.id, ok: true, r });
    if (call.m === 'close') setTimeout(() => process.exit(0), 50);
  } catch (err: any) {
    send({ id: call.id, ok: false, err: err?.message ?? String(err) });
  }
});

process.on('uncaughtException', (err: any) => send({ ev: { e: 'error', text: `internal error: ${err?.message ?? err}`, fatal: false } }));
process.on('unhandledRejection', (err: any) => send({ ev: { e: 'error', text: `internal error: ${err?.message ?? err}`, fatal: false } }));
process.on('disconnect', () => process.exit(0));
send({ ready: true });
