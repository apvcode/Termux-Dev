import { spawn } from 'child_process';
import os from 'os';

/**
 * Puts text on the clipboard: through the tool of the system (wl-copy, xclip, xsel, pbcopy,
 * PowerShell, termux-clipboard-set) and, when none of them works, through the terminal itself
 * (OSC 52: kitty, wezterm, foot, iTerm2, Windows Terminal and others; it also works over ssh).
 */
export type WriteRunner = (command: string, args: string[], input: string) => Promise<boolean>;

export const runWriter: WriteRunner = (command, args, input) =>
  new Promise((resolve) => {
    try {
      const child = spawn(command, args, { stdio: ['pipe', 'ignore', 'ignore'] });
      let settled = false;
      const done = (ok: boolean) => { if (!settled) { settled = true; resolve(ok); } };
      const timer = setTimeout(() => { try { child.kill(); } catch {} done(false); }, 5000);
      child.on('error', () => { clearTimeout(timer); done(false); });
      child.on('close', (code) => { clearTimeout(timer); done(code === 0); });
      child.stdin.on('error', () => {});
      child.stdin.end(input);
    } catch {
      resolve(false);
    }
  });

export interface CopyContext {
  platform?: NodeJS.Platform;
  env?: NodeJS.ProcessEnv;
  /** Where the OSC 52 sequence goes (the terminal). */
  write?: (text: string) => void;
  run?: WriteRunner;
}

export type CopyMethod = 'termux' | 'pbcopy' | 'powershell' | 'wl-copy' | 'xclip' | 'xsel' | 'osc52' | 'none';

/** The OSC 52 sequence for a text: the terminal puts it on the clipboard. */
export function osc52Sequence(text: string): string {
  return `\x1b]52;c;${Buffer.from(text, 'utf8').toString('base64')}\x07`;
}

const OSC52_MAX_BYTES = 74000;   // many terminals refuse more than ~100 KB of base64

export async function copyToClipboard(text: string, ctx: CopyContext = {}): Promise<CopyMethod> {
  const platform = ctx.platform ?? os.platform();
  const env = ctx.env ?? process.env;
  const run = ctx.run ?? runWriter;
  const write = ctx.write ?? ((t: string) => process.stdout.write(t));

  const candidates: Array<[CopyMethod, string, string[]]> = [];
  if (env.TERMUX_VERSION || env.PREFIX?.includes('com.termux')) candidates.push(['termux', 'termux-clipboard-set', []]);
  if (platform === 'darwin') candidates.push(['pbcopy', 'pbcopy', []]);
  else if (platform === 'win32') candidates.push(['powershell', 'powershell', ['-NoProfile', '-Command', 'Set-Clipboard -Value ([Console]::In.ReadToEnd())']]);
  else {
    if (env.WAYLAND_DISPLAY) candidates.push(['wl-copy', 'wl-copy', []]);
    candidates.push(['xclip', 'xclip', ['-selection', 'clipboard']], ['xsel', 'xsel', ['--clipboard', '--input']]);
  }
  for (const [method, command, args] of candidates) {
    if (await run(command, args, text)) return method;
  }
  if (Buffer.byteLength(text, 'utf8') <= OSC52_MAX_BYTES) {
    write(osc52Sequence(text));
    return 'osc52';
  }
  return 'none';
}
