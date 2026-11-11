import fs from 'fs';
import path from 'path';
import { RemoteHost } from './host.js';
import { RemoteStore } from './store.js';
import { ControlClient, serveControl } from './control.js';
import { DEVX_VERSION } from '../core/types.js';
import { installCapabilities } from '../core/capabilities.js';

/** The host as a program: runs until it is told to stop. `devx remote start` runs it in the background. */
export async function runDaemon(): Promise<void> {
  // One host per user: a second one would fight over the port and the sockets.
  const existing = await ControlClient.connect();
  if (existing) {
    existing.close();
    console.error('devx remote is already running.');
    process.exit(0);
  }

  installCapabilities();
  const store = new RemoteStore();
  const logFile = path.join(store.dir, 'host.log');
  const log = (line: string) => {
    try {
      if (fs.existsSync(logFile) && fs.statSync(logFile).size > 512 * 1024) fs.renameSync(logFile, `${logFile}.1`);
      fs.appendFileSync(logFile, `${new Date().toISOString()} ${line}\n`);
    } catch {}
  };
  const host = new RemoteHost(store, { log });
  const { port } = await host.start();
  log(`devx ${DEVX_VERSION} remote host started, port ${port}, relay ${store.settings.relay ? 'on' : 'off'}`);

  let stopping = false;
  const stop = async () => {
    if (stopping) return;
    stopping = true;
    log('stopping');
    control.close();
    await host.stop().catch(() => null);
    process.exit(0);
  };
  const control = serveControl(host, () => void stop());
  fs.writeFileSync(path.join(store.dir, 'host.pid'), String(process.pid), { mode: 0o600 });

  process.on('SIGTERM', () => void stop());
  process.on('SIGINT', () => void stop());
  process.on('SIGHUP', () => {});
  process.on('uncaughtException', (err: any) => { if (err?.code === 'EPIPE' || err?.code === 'EIO') return; log(`uncaught: ${err?.stack ?? err}`); });
  process.on('unhandledRejection', (err: any) => log(`unhandled: ${err?.stack ?? err}`));
}
