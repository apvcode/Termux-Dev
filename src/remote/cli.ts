import fs from 'fs';
import os from 'os';
import path from 'path';
import readline from 'readline';
import { spawn } from 'child_process';
import { fileURLToPath } from 'url';
import pc from 'picocolors';
import qrcode from 'qrcode-terminal';
import { ControlClient } from './control.js';
import { RemoteStore, remoteDir } from './store.js';
import { autostartOn, autostartPlan, disableAutostart, enableAutostart } from './autostart.js';

const HELP = `
${pc.bold('devx remote')}: control devx on this computer from the devx app on your phone

  devx remote start        start the host in the background (it keeps running when the terminal is closed)
  devx remote pair         add a phone: shows a QR code
  devx remote status       is it running, who is connected, which sessions are open
  devx remote devices      the phones that may connect
  devx remote revoke <n>   remove a phone (by name or id)
  devx remote name <name>  how this computer is called on the phone
  devx remote stop         stop the host
  devx remote logs         the end of the host's log
  devx remote service      start the host with the computer (service on | off | status)
  devx remote daemon       run the host in this terminal (what start does in the background)
`;

const ago = (t: number) => {
  const s = Math.max(0, Math.round((Date.now() - t) / 1000));
  if (s < 60) return 'just now';
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  if (s < 86400) return `${Math.round(s / 3600)} h ago`;
  return `${Math.round(s / 86400)} d ago`;
};

const dur = (ms: number) => {
  const m = Math.round(ms / 60000);
  return m < 60 ? `${m} min` : m < 1440 ? `${Math.round(m / 60)} h` : `${Math.round(m / 1440)} d`;
};

function ask(question: string): Promise<string> {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  return new Promise((resolve) => rl.question(question, (a) => { rl.close(); resolve(a.trim()); }));
}

/** Starts the host in the background and waits until it answers. */
export async function ensureHost(): Promise<ControlClient> {
  const running = await ControlClient.connect();
  if (running) return running;
  const entry = fileURLToPath(new URL('../../bin/devx.js', import.meta.url));
  const child = spawn(process.execPath, [entry, 'remote', 'daemon'], { detached: true, stdio: 'ignore', env: process.env });
  child.unref();
  for (let i = 0; i < 60; i++) {
    await new Promise((r) => setTimeout(r, 150));
    const c = await ControlClient.connect();
    if (c) return c;
  }
  throw new Error(`The host did not start. See ${path.join(remoteDir(), 'host.log')}`);
}

function printStatus(s: any): void {
  console.log(`\n  ${pc.bold('devx remote')} ${pc.green('running')}  ${pc.dim(`pid ${s.pid} · up ${dur(s.uptime)} · devx ${s.version}`)}`);
  console.log(`  ${pc.dim('name')}       ${s.name}`);
  console.log(`  ${pc.dim('direct')}     ${s.port ? s.addresses.map((a: string) => a).join(', ') || `port ${s.port}` : pc.yellow('off (port in use)')}`);
  const relay = s.relay as Array<{ url: string; connected: boolean }>;
  console.log(`  ${pc.dim('relay')}      ${relay.length === 0 ? 'off' : relay.map((r) => `${r.connected ? pc.green('●') : pc.red('○')} ${r.url.replace(/^wss?:\/\//, '')}`).join('  ')}`);
  console.log(`  ${pc.dim('phones')}     ${s.devices.length === 0 ? pc.dim('none yet: devx remote pair') : s.devices.map((d: any) => `${d.name}${s.connected.some((c: any) => c.did === d.did) ? pc.green(' (connected)') : pc.dim(` (${ago(d.lastSeen)})`)}`).join(', ')}`);
  if (s.sessions.length > 0) {
    console.log(`  ${pc.dim('sessions')}`);
    for (const x of s.sessions) console.log(`    ${x.running ? pc.green('●') : pc.dim('○')} ${x.title ?? x.id}  ${pc.dim(x.cwd)}`);
  }
  console.log();
}

async function pair(): Promise<number> {
  const c = await ensureHost();
  const { uri, expiresAt } = await c.call('pair');
  console.log(`\n  ${pc.bold('Add a phone')}\n  On the phone: devx → Devx Remote → Add computer, and scan this code.\n`);
  await new Promise<void>((resolve) => qrcode.generate(uri, { small: true }, (qr: string) => { console.log(qr.split('\n').map((l) => '    ' + l).join('\n')); resolve(); }));
  console.log(`\n  ${pc.dim('No camera? Paste this into the app instead:')}\n  ${pc.cyan(uri)}\n`);
  console.log(`  ${pc.dim(`Waiting for the phone… the code works for ${Math.round((expiresAt - Date.now()) / 60000)} minutes, once.`)}`);

  return new Promise<number>((resolve) => {
    const timer = setTimeout(() => { console.log(pc.yellow('\n  The code expired. Run devx remote pair again.')); c.close(); resolve(1); }, expiresAt - Date.now());
    c.onEvent = async (e) => {
      if (e.ev !== 'pair-request') return;
      const sas = `${e.sas.slice(0, 3)} ${e.sas.slice(3)}`;
      console.log(`\n  ${pc.bold(e.name)} wants to connect ${pc.dim(`(${e.via === 'relay' ? 'through the relay' : 'directly'})`)}`);
      console.log(`  The phone must show this number: ${pc.bold(pc.cyan(sas))}`);
      const answer = await ask('  Is it the same? Allow this phone? [y/N] ');
      const ok = /^y(es)?$/i.test(answer);
      await c.call('pair-decision', { ok });
      clearTimeout(timer);
      console.log(ok ? `\n  ${pc.green('✓')} ${e.name} is paired.\n` : `\n  ${pc.dim('Refused.')}\n`);
      c.close();
      resolve(ok ? 0 : 1);
    };
    c.onClose = () => { clearTimeout(timer); resolve(1); };
  });
}

export async function runRemoteCli(args: string[]): Promise<number> {
  const [sub = 'help', ...rest] = args;
  try {
    switch (sub) {
      case 'daemon': {
        const { runDaemon } = await import('./daemon.js');
        await runDaemon();
        return await new Promise<number>(() => {}); // runs until it is stopped
      }
      case 'start': {
        const c = await ensureHost();
        printStatus(await c.call('status'));
        c.close();
        return 0;
      }
      case 'status': {
        const c = await ControlClient.connect();
        if (!c) { console.log(`\n  devx remote is ${pc.yellow('not running')}. Start it with: devx remote start\n`); return 1; }
        printStatus(await c.call('status'));
        c.close();
        return 0;
      }
      case 'pair': return await pair();
      case 'devices': {
        const c = await ControlClient.connect();
        const list: Array<{ did: string; name: string; addedAt: number; lastSeen: number }> = c ? (await c.call('status')).devices : new RemoteStore().listDevices();
        c?.close();
        if (list.length === 0) { console.log('\n  No phones are paired. Add one with: devx remote pair\n'); return 0; }
        console.log();
        for (const d of list) console.log(`  ${pc.bold(d.name)}  ${pc.dim(d.did.slice(0, 8))}  added ${ago(d.addedAt)}, last seen ${ago(d.lastSeen)}`);
        console.log();
        return 0;
      }
      case 'revoke': {
        const who = rest.join(' ').trim();
        if (!who) { console.log('Say which phone: devx remote revoke <name or id>'); return 1; }
        const c = await ControlClient.connect();
        if (c) { await c.call('revoke', { device: who }); c.close(); console.log(`\n  ${pc.green('✓')} removed ${who}\n`); return 0; }
        const store = new RemoteStore();
        const d = store.removeDevice(who);
        console.log(d ? `\n  ${pc.green('✓')} removed ${d.name}\n` : '\n  No such phone.\n');
        return d ? 0 : 1;
      }
      case 'name': {
        const name = rest.join(' ').trim();
        if (!name) { console.log('Give a name: devx remote name "Work laptop"'); return 1; }
        const c = await ControlClient.connect();
        if (c) { await c.call('rename', { name }); c.close(); } else { const s = new RemoteStore(); s.settings.name = name.slice(0, 40); s.saveSettings(); }
        console.log(`\n  ${pc.green('✓')} this computer is called "${name}" now\n`);
        return 0;
      }
      case 'stop': {
        const c = await ControlClient.connect();
        if (!c) { console.log('\n  devx remote is not running.\n'); return 0; }
        await c.call('stop').catch(() => null);
        c.close();
        console.log(`\n  ${pc.green('✓')} stopped\n`);
        return 0;
      }
      case 'logs': {
        const file = path.join(remoteDir(), 'host.log');
        if (!fs.existsSync(file)) { console.log('\n  No log yet.\n'); return 0; }
        console.log(fs.readFileSync(file, 'utf8').split('\n').slice(-40).join('\n'));
        return 0;
      }
      case 'service': {
        const plan = autostartPlan();
        const what = rest[0] ?? '';
        if (!plan) {
          console.log('\n  Starting with the computer is set up for Linux, macOS and Windows.\n  In Termux: install termux-services and make a service that runs  devx remote daemon.\n');
          return 0;
        }
        if (what === 'off') {
          disableAutostart(plan);
          console.log(`\n  ${pc.green('✓')} devx remote will no longer start with the computer\n`);
          return 0;
        }
        if (what === 'status') {
          console.log(`\n  Starts with the computer: ${autostartOn(plan) ? pc.green('yes') : pc.yellow('no')}\n`);
          return 0;
        }
        enableAutostart(plan);
        console.log(`\n  ${pc.green('✓')} devx remote starts with the computer now (${plan.file})\n  Turn it off with: devx remote service off\n`);
        return 0;
      }
      default:
        console.log(HELP);
        return sub === 'help' || sub === '--help' || sub === '-h' ? 0 : 1;
    }
  } catch (err: any) {
    console.error(pc.red(`\n  ${err.message}\n`));
    return 1;
  }
}
