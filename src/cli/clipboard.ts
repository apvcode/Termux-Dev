import fs from 'fs/promises';
import fsSync from 'fs';
import path from 'path';
import os from 'os';
import { execFile } from 'child_process';

/**
 * Images and text from the system clipboard, and image files pasted as paths.
 *
 * Pasted images are stored in `~/.devx/media` (not in the project folder, so nothing is left
 * behind in the user's repository). The prompt shows them as `[Image #1 203kb]` and the
 * message carries the file path, which is what resolveAtMentions turns into a real image part.
 */

export const MEDIA_DIR = path.join(os.homedir(), '.devx', 'media');

/** Formats the image APIs accept. */
export const IMAGE_EXTENSIONS = new Set(['.png', '.jpg', '.jpeg', '.webp', '.gif']);

/** The text that stands for an image in the prompt, and how to find it again. */
export const IMAGE_TAG_SOURCE = '\\[Image #\\d+ [^\\]\\n]{1,12}\\]';
export const imageTag = (index: number, sizeStr: string): string => `[Image #${index} ${sizeStr}]`;

export function expandHomePath(p: string): string {
  if (!p) return p;
  const trimmed = p.trim().replace(/^['"]|['"]$/g, '');
  if (trimmed === '~' || trimmed.startsWith('~/') || trimmed.startsWith('~\\')) {
    return path.join(os.homedir(), trimmed.slice(2));
  }
  return trimmed;
}

export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes}b`;
  const kb = Math.round(bytes / 1024);
  if (kb < 1024) return `${kb}kb`;
  const mb = (bytes / (1024 * 1024)).toFixed(1);
  return `${mb}mb`;
}

export interface SavedImage {
  fileName: string;
  filePath: string;
  size: number;
  sizeStr: string;
}

/** The format of an image by its first bytes; the clipboard's own type name is not trusted. */
export function sniffImage(buf: Buffer): { ext: string; mime: string } | null {
  if (buf.length < 12) return null;
  if (buf[0] === 0x89 && buf[1] === 0x50 && buf[2] === 0x4e && buf[3] === 0x47) return { ext: 'png', mime: 'image/png' };
  if (buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff) return { ext: 'jpg', mime: 'image/jpeg' };
  if (buf.toString('latin1', 0, 4) === 'GIF8') return { ext: 'gif', mime: 'image/gif' };
  if (buf.toString('latin1', 0, 4) === 'RIFF' && buf.toString('latin1', 8, 12) === 'WEBP') return { ext: 'webp', mime: 'image/webp' };
  return null;
}

/** A name that is safe in a path typed after "@" and unique across sessions. */
function newImageName(ext: string, taken: Set<string>): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  const stamp = `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`;
  let name = `image-${stamp}.${ext}`;
  for (let i = 2; taken.has(name) || fsSync.existsSync(path.join(MEDIA_DIR, name)); i++) name = `image-${stamp}-${i}.${ext}`;
  return name;
}

/** Letters, digits, dot, dash and underscore only: such a name always survives an "@mention". */
export function safeFileName(name: string): string {
  const cleaned = name.replace(/[^A-Za-z0-9._-]+/g, '_').replace(/^\.+/, '');
  return cleaned || 'image.png';
}

export function getUniqueImageName(baseName: string, existingNames: Set<string>): string {
  const safe = safeFileName(baseName);
  if (!existingNames.has(safe) && !fsSync.existsSync(path.join(MEDIA_DIR, safe))) return safe;
  const ext = path.extname(safe);
  const stem = path.basename(safe, ext);
  for (let i = 2; ; i++) {
    const candidate = `${stem}-${i}${ext}`;
    if (!existingNames.has(candidate) && !fsSync.existsSync(path.join(MEDIA_DIR, candidate))) return candidate;
  }
}

// ───────────────────────── running the clipboard tools ─────────────────────────

/** Runs a program and returns what it printed, or null when it is missing or fails. */
export type Runner = (command: string, args: string[]) => Promise<Buffer | null>;

export const runTool: Runner = (command, args) =>
  new Promise((resolve) => {
    try {
      execFile(command, args, { encoding: 'buffer', maxBuffer: 64 * 1024 * 1024, timeout: 8000 }, (err, stdout) => {
        resolve(err ? null : (stdout as unknown as Buffer));
      });
    } catch {
      resolve(null);
    }
  });

export interface ClipboardEnv {
  platform: NodeJS.Platform;
  env: NodeJS.ProcessEnv;
}

const IMAGE_PREFERENCE = ['image/png', 'image/jpeg', 'image/webp', 'image/gif'];

function pickImageType(types: string[]): string | null {
  const lower = types.map((t) => t.trim().toLowerCase());
  for (const wanted of IMAGE_PREFERENCE) if (lower.includes(wanted)) return wanted;
  // image/jpg, image/x-png and similar spellings
  return lower.find((t) => /^image\/(png|jpe?g|webp|gif)/.test(t)) ?? null;
}

/** The raw bytes of the image on the clipboard, or null when there is none (or no tool to read it). */
export async function readClipboardImageBytes(run: Runner = runTool, ctx: ClipboardEnv = { platform: os.platform(), env: process.env }): Promise<Buffer | null> {
  const { platform, env } = ctx;

  if (platform === 'win32') {
    const script = [
      'Add-Type -AssemblyName System.Windows.Forms',
      'Add-Type -AssemblyName System.Drawing',
      '$img = [System.Windows.Forms.Clipboard]::GetImage()',
      'if ($img -ne $null) { $ms = New-Object System.IO.MemoryStream; $img.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png); [Console]::OpenStandardOutput().Write($ms.ToArray(), 0, $ms.Length) }',
    ].join('; ');
    return run('powershell.exe', ['-NoProfile', '-NonInteractive', '-EncodedCommand', Buffer.from(script, 'utf16le').toString('base64')]);
  }

  if (platform === 'darwin') {
    return run('pngpaste', ['-']);
  }

  // Termux has no way to read an image from the clipboard.
  if (env.PREFIX?.includes('com.termux')) return null;

  const wayland = !!env.WAYLAND_DISPLAY;
  const attempts: Array<() => Promise<Buffer | null>> = [];
  if (wayland) {
    attempts.push(async () => {
      const list = await run('wl-paste', ['--list-types']);
      const type = list ? pickImageType(list.toString('utf8').split('\n')) : null;
      return type ? run('wl-paste', ['--no-newline', '--type', type]) : null;
    });
  }
  attempts.push(async () => {
    const list = await run('xclip', ['-selection', 'clipboard', '-t', 'TARGETS', '-o']);
    const type = list ? pickImageType(list.toString('utf8').split('\n')) : null;
    return type ? run('xclip', ['-selection', 'clipboard', '-t', type, '-o']) : null;
  });
  if (!wayland) {
    attempts.push(async () => {
      const list = await run('wl-paste', ['--list-types']);
      const type = list ? pickImageType(list.toString('utf8').split('\n')) : null;
      return type ? run('wl-paste', ['--no-newline', '--type', type]) : null;
    });
  }

  for (const attempt of attempts) {
    const bytes = await attempt();
    if (bytes && bytes.length > 0) return bytes;
  }
  return null;
}

/** The text on the clipboard, or null. Used when Ctrl+V arrives as a key and the terminal did not paste. */
export async function readClipboardText(run: Runner = runTool, ctx: ClipboardEnv = { platform: os.platform(), env: process.env }): Promise<string | null> {
  const { platform, env } = ctx;
  let out: Buffer | null = null;
  if (platform === 'win32') out = await run('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', 'Get-Clipboard -Raw']);
  else if (platform === 'darwin') out = await run('pbpaste', []);
  else if (env.PREFIX?.includes('com.termux')) out = await run('termux-clipboard-get', []);
  else {
    if (env.WAYLAND_DISPLAY) out = await run('wl-paste', ['--no-newline', '--type', 'text']);
    out ??= await run('xclip', ['-selection', 'clipboard', '-o']);
    out ??= await run('xsel', ['--clipboard', '--output']);
    if (!env.WAYLAND_DISPLAY) out ??= await run('wl-paste', ['--no-newline', '--type', 'text']);
  }
  const text = out?.toString('utf8') ?? '';
  return text.length > 0 ? text : null;
}

async function store(bytes: Buffer, name: string): Promise<SavedImage> {
  await fs.mkdir(MEDIA_DIR, { recursive: true, mode: 0o700 });
  const filePath = path.join(MEDIA_DIR, name);
  await fs.writeFile(filePath, bytes, { mode: 0o600 });
  return { fileName: name, filePath, size: bytes.length, sizeStr: formatFileSize(bytes.length) };
}

/**
 * Saves the image on the clipboard to ~/.devx/media.
 * `_preferredName` is kept for older callers; the file is named by the time and its real format.
 */
export async function saveClipboardImage(_preferredName: string, existingNames: Set<string>, run: Runner = runTool, ctx?: ClipboardEnv): Promise<SavedImage | null> {
  const bytes = await readClipboardImageBytes(run, ctx);
  if (!bytes) return null;
  const kind = sniffImage(bytes);
  if (!kind) return null;
  return store(bytes, newImageName(kind.ext, existingNames));
}

/** Copies an image file (a pasted or dropped path) into ~/.devx/media under a safe name. */
export async function processPastedFilePath(rawPath: string, existingNames: Set<string>): Promise<SavedImage | null> {
  const cleanPath = expandHomePath(rawPath).trim().replace(/^['"&]+|['"]+$/g, '').trim();
  const resolved = path.resolve(process.cwd(), cleanPath);
  const ext = path.extname(resolved).toLowerCase();
  if (!IMAGE_EXTENSIONS.has(ext)) return null;

  try {
    const stat = await fs.stat(resolved);
    if (!stat.isFile()) return null;
    await fs.mkdir(MEDIA_DIR, { recursive: true, mode: 0o700 });
    const name = getUniqueImageName(path.basename(resolved), existingNames);
    await fs.copyFile(resolved, path.join(MEDIA_DIR, name));
    return { fileName: name, filePath: path.join(MEDIA_DIR, name), size: stat.size, sizeStr: formatFileSize(stat.size) };
  } catch {
    return null;
  }
}

/** Deletes pasted images older than `days` days. Never throws. */
export async function pruneMedia(days = 14, now: number = Date.now()): Promise<number> {
  let removed = 0;
  try {
    for (const entry of await fs.readdir(MEDIA_DIR)) {
      const file = path.join(MEDIA_DIR, entry);
      try {
        const stat = await fs.stat(file);
        if (stat.isFile() && now - stat.mtimeMs > days * 86_400_000) {
          await fs.unlink(file);
          removed++;
        }
      } catch {}
    }
  } catch {}
  return removed;
}
