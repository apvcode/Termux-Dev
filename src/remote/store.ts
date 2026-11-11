import fs from 'fs';
import os from 'os';
import path from 'path';
import { Identity, newIdentity } from './crypto.js';
import { DeviceRecord } from './secure.js';

/** Where the remote host keeps its keys, the paired phones and its settings. */
export function remoteDir(): string {
  return process.env.DEVX_REMOTE_DIR || path.join(os.homedir(), '.devx', 'remote');
}

export interface RemoteSettings {
  /** What the phone shows as the name of this computer. */
  name: string;
  /** Port of the direct (local network) listener. */
  port: number;
  /** Also listen on the public relay (works from anywhere, e.g. mobile internet). */
  relay: boolean;
  /** Relay brokers by id (see relay.ts); the phone tries them all. */
  brokers: number[];
  /** Folders the phone may start sessions in without being asked more (empty: any folder). */
  startIn?: string;
}

export const DEFAULT_SETTINGS = (): RemoteSettings => ({ name: os.hostname() || 'computer', port: 47821, relay: true, brokers: [0, 1] });

function writePrivate(file: string, data: string) {
  fs.mkdirSync(path.dirname(file), { recursive: true, mode: 0o700 });
  const tmp = `${file}.tmp`;
  fs.writeFileSync(tmp, data, { mode: 0o600 });
  try { fs.chmodSync(tmp, 0o600); } catch {}
  fs.renameSync(tmp, file);
}

function readJson<T>(file: string, fallback: T): T {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8')) as T;
  } catch {
    return fallback;
  }
}

export class RemoteStore {
  readonly dir: string;
  private devicesCache: Map<string, DeviceRecord>;
  identity: Identity;
  settings: RemoteSettings;

  constructor(dir = remoteDir()) {
    this.dir = dir;
    fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
    try { fs.chmodSync(dir, 0o700); } catch {}
    const raw = readJson<{ priv?: string; pub?: string }>(path.join(dir, 'identity.json'), {});
    if (raw.priv && raw.pub) {
      this.identity = { priv: Buffer.from(raw.priv, 'base64'), pub: Buffer.from(raw.pub, 'base64') };
    } else {
      this.identity = newIdentity();
      writePrivate(path.join(dir, 'identity.json'), JSON.stringify({ priv: this.identity.priv.toString('base64'), pub: this.identity.pub.toString('base64') }));
    }
    this.devicesCache = new Map(readJson<DeviceRecord[]>(path.join(dir, 'devices.json'), []).map((d) => [d.did, d]));
    this.settings = { ...DEFAULT_SETTINGS(), ...readJson<Partial<RemoteSettings>>(path.join(dir, 'settings.json'), {}) };
  }

  devices = {
    get: (did: string): DeviceRecord | undefined => this.devicesCache.get(did),
  };

  listDevices(): DeviceRecord[] {
    return [...this.devicesCache.values()].sort((a, b) => b.lastSeen - a.lastSeen);
  }

  /** Re-reads the files: another process (`devx remote revoke`) may have changed them. */
  reload(): void {
    this.devicesCache = new Map(readJson<DeviceRecord[]>(path.join(this.dir, 'devices.json'), []).map((d) => [d.did, d]));
  }

  addDevice(d: DeviceRecord): void {
    this.devicesCache.set(d.did, d);
    this.saveDevices();
  }

  touch(did: string): void {
    const d = this.devicesCache.get(did);
    if (!d) return;
    // Written at most once a minute: a phone that connects every few seconds must not wear the disk.
    if (Date.now() - d.lastSeen < 60_000) return;
    d.lastSeen = Date.now();
    this.saveDevices();
  }

  removeDevice(didOrName: string): DeviceRecord | undefined {
    const d = this.devicesCache.get(didOrName) ?? this.listDevices().find((x) => x.name.toLowerCase() === didOrName.toLowerCase() || x.did.startsWith(didOrName));
    if (!d) return undefined;
    this.devicesCache.delete(d.did);
    this.saveDevices();
    return d;
  }

  saveSettings(): void {
    writePrivate(path.join(this.dir, 'settings.json'), JSON.stringify(this.settings, null, 2));
  }

  private saveDevices(): void {
    writePrivate(path.join(this.dir, 'devices.json'), JSON.stringify([...this.devicesCache.values()], null, 2));
  }
}
