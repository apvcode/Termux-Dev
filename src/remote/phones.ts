import { ControlClient } from './control.js';
import { RemoteStore } from './store.js';
import { runPicker } from '../cli/picker.js';
import { askConfirm } from '../cli/confirm.js';
import { renderNotice } from '../cli/ui.js';

export interface PhoneRow { did: string; name: string; addedAt: number; lastSeen: number }

const ago = (t: number): string => {
  if (!t) return 'never';
  const s = Math.max(0, Math.round((Date.now() - t) / 1000));
  if (s < 90) return 'just now';
  if (s < 5400) return `${Math.round(s / 60)} min ago`;
  if (s < 129600) return `${Math.round(s / 3600)} h ago`;
  return `${Math.round(s / 86400)} d ago`;
};

/** The phones that are paired: asked from the running host (it knows who is connected), or read from the files. */
export async function listPhones(): Promise<PhoneRow[]> {
  const c = await ControlClient.connect();
  try {
    if (c) return (await c.call('status')).devices as PhoneRow[];
  } catch { /* fall back to the files */ } finally { c?.close(); }
  return new RemoteStore().listDevices().map((d) => ({ did: d.did, name: d.name, addedAt: d.addedAt, lastSeen: d.lastSeen }));
}

/** Removes a phone: it is disconnected at once and can only come back by being paired again. */
export async function removePhone(did: string): Promise<boolean> {
  const c = await ControlClient.connect();
  if (c) {
    try { await c.call('revoke', { device: did }); return true; } catch { return false; } finally { c.close(); }
  }
  return !!new RemoteStore().removeDevice(did);
}

/** `/phones`: the paired phones; choose one to remove it (it is disconnected and cannot connect again without pairing anew). */
export async function runPhones(): Promise<void> {
  for (;;) {
    const phones = await listPhones();
    if (phones.length === 0) {
      console.log(renderNotice('info', 'No phones are paired. Add one with: devx remote pair'));
      return;
    }
    const picked = await runPicker<string>({
      title: 'Phones',
      subtitle: 'devx Remote',
      items: phones.map((p) => ({ value: p.did, label: p.name, detail: `added ${ago(p.addedAt)}, last seen ${ago(p.lastSeen)}` })),
      notes: ['Enter on a phone removes it: it is disconnected and cannot connect again until it is paired anew.'],
    });
    if (!picked) return;
    const phone = phones.find((p) => p.did === picked);
    if (!phone) return;
    const yes = await askConfirm({
      title: 'remove phone',
      question: `Remove "${phone.name}"?`,
      details: ['It is disconnected now and cannot connect to this computer again until you pair it with: devx remote pair'],
      yes: 'Remove',
      no: 'Keep',
      record: false,
    });
    if (!yes) continue;
    const ok = await removePhone(phone.did);
    console.log(renderNotice(ok ? 'ok' : 'warn', ok ? `"${phone.name}" was removed and disconnected.` : 'The phone could not be removed.'));
  }
}
