import { spawn } from 'child_process';
import os from 'os';

export interface NotificationOptions {
  vibrate?: boolean;
  sound?: boolean;
}

export function isTermux(): boolean {
  return !!process.env.TERMUX_VERSION || !!process.env.PREFIX?.includes('com.termux');
}

/**
 * Acquires a Termux CPU wake lock to prevent Android from putting the process to sleep
 * when Termux is in the background or the screen is turned off.
 */
export function acquireWakeLock(): void {
  if (isTermux()) {
    try {
      const proc = spawn('termux-wake-lock', [], { stdio: 'ignore', detached: true });
      proc.on('error', () => {});
      proc.unref();
    } catch {}
  }
}

/**
 * Sends a notification and haptic vibration to the device.
 * On Termux: uses termux-notification and termux-vibrate via termux-api.
 * On Desktop: emits terminal bell \u0007.
 */
export function notifyDevice(title: string, message: string, options: NotificationOptions = { vibrate: true, sound: true }): void {
  if (isTermux()) {
    try {
      // 1. Android notification via Termux API
      const notif = spawn('termux-notification', [
        '--title', title,
        '--content', message,
        '--id', 'devx_task_notif',
        '--priority', 'high'
      ], { stdio: 'ignore', detached: true });
      notif.on('error', () => {});
      notif.unref();

      // 2. Haptic vibration (150ms)
      if (options.vibrate !== false) {
        const vib = spawn('termux-vibrate', ['-d', '150'], { stdio: 'ignore', detached: true });
        vib.on('error', () => {});
        vib.unref();
      }
    } catch {}
  } else {
    // Desktop terminal bell
    if (options.sound !== false) {
      try {
        process.stdout.write('\u0007');
      } catch {}
    }
  }
}

// ───────────────────────── desktop notification when the terminal is not in front ─────────────────────────

/** The start of an answer as plain text for a notification: no markdown, code or links, cut at a word. */
export function notificationBody(markdown: string, max = 160): string {
  let text = String(markdown ?? '')
    .replace(/```[\s\S]*?(```|$)/g, ' ')
    .replace(/`([^`]*)`/g, '$1')
    .replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/^\s{0,3}(#{1,6}|>|[-*+]|\d+[.)])\s+/gm, '')
    .replace(/[*_~]{1,3}([^*_~\n]+)[*_~]{1,3}/g, '$1')
    .replace(/[\x00-\x1f\x7f]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  if (Array.from(text).length <= max) return text;
  const cut = Array.from(text).slice(0, max).join('');
  text = cut.replace(/\s+\S*$/, '') || cut;
  return text.replace(/[\s,;:.\-]+$/, '') + '…';
}

/** kitty's own notification protocol (OSC 99): a title, then the body of the same notification. */
export function kittyNotificationSequence(title: string, body: string): string {
  const clean = (s: string) => s.replace(/[\x00-\x1f\x7f]/g, ' ');
  return `\x1b]99;i=devx:d=0;${clean(title)}\x1b\\\x1b]99;i=devx:p=body;${clean(body)}\x1b\\`;
}

export interface DesktopNotifyEnv {
  platform?: NodeJS.Platform;
  env?: NodeJS.ProcessEnv;
  write?: (text: string) => void;
  run?: (command: string, args: string[]) => void;
}

/**
 * Shows a notification on the desktop: through kitty's protocol in kitty, `notify-send` on Linux,
 * `osascript` on macOS. Returns which way was used, or 'none'. Never throws.
 */
export function sendDesktopNotification(title: string, body: string, io: DesktopNotifyEnv = {}): 'kitty' | 'notify-send' | 'osascript' | 'none' {
  const platform = io.platform ?? process.platform;
  const env = io.env ?? process.env;
  const write = io.write ?? ((text: string) => process.stdout.write(text));
  const run = io.run ?? ((command: string, args: string[]) => {
    const child = spawn(command, args, { stdio: 'ignore', detached: true });
    child.on('error', () => {});
    child.unref();
  });
  try {
    if (env.KITTY_WINDOW_ID) {
      write(kittyNotificationSequence(title, body));
      return 'kitty';
    }
    if (platform === 'linux') {
      run('notify-send', ['--app-name=devx', title, body]);
      return 'notify-send';
    }
    if (platform === 'darwin') {
      const q = (s: string) => `"${s.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;
      run('osascript', ['-e', `display notification ${q(body)} with title ${q(title)}`]);
      return 'osascript';
    }
  } catch {}
  return 'none';
}
