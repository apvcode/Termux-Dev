import crypto from 'crypto';
import fs from 'fs';
import path from 'path';
import { imageMimeForPath } from '../core/images.js';

/**
 * Pictures and files that go from the computer to the phone's chat: the pictures the agent looked at (read_file on an
 * image) and the files it was asked to hand over (send_file). They are found again from the text a tool returned, so a
 * chat that is opened later shows them too, and the phone fetches the bytes in chunks (file.get).
 */
export interface MediaRef {
  id: string;
  name: string;
  mime: string;
  size: number;
  /** Absolute path on this computer (never sent to the phone). */
  path: string;
  by: 'look' | 'send';
}

/** The largest file that goes to the phone. */
export const MAX_MEDIA_BYTES = 25 * 1024 * 1024;

const MIMES: Record<string, string> = {
  '.pdf': 'application/pdf', '.zip': 'application/zip', '.gz': 'application/gzip', '.tgz': 'application/gzip', '.tar': 'application/x-tar',
  '.7z': 'application/x-7z-compressed', '.rar': 'application/vnd.rar', '.apk': 'application/vnd.android.package-archive',
  '.txt': 'text/plain', '.md': 'text/markdown', '.csv': 'text/csv', '.json': 'application/json', '.html': 'text/html', '.xml': 'application/xml',
  '.js': 'text/javascript', '.ts': 'text/plain', '.py': 'text/x-python', '.log': 'text/plain',
  '.doc': 'application/msword', '.docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  '.xls': 'application/vnd.ms-excel', '.xlsx': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  '.ppt': 'application/vnd.ms-powerpoint', '.pptx': 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
  '.mp3': 'audio/mpeg', '.wav': 'audio/wav', '.ogg': 'audio/ogg', '.mp4': 'video/mp4', '.mov': 'video/quicktime', '.webm': 'video/webm',
  '.svg': 'image/svg+xml', '.bmp': 'image/bmp',
};

export function mimeFor(file: string): string {
  return imageMimeForPath(file) ?? MIMES[path.extname(file).toLowerCase()] ?? 'application/octet-stream';
}

export function mediaId(abs: string, size: number): string {
  return crypto.createHash('sha1').update(`${abs}:${size}`).digest('hex').slice(0, 12);
}

const LOOKED = /^\[Image (.+?) \((image\/[a-z0-9.+-]+), [\d.]+ KB\) is attached to this result/;
const SENT = /^\[File (.+?) \(([^,()]+), (\d+) bytes\) was sent to the phone/;

/** The picture or file that a tool's result is about, when it is one (and still on the disk). */
export function mediaFromResult(toolName: string, result: string, cwd: string): MediaRef | null {
  const first = result.split('\n', 1)[0] ?? '';
  let by: MediaRef['by'];
  let file: string;
  let mime: string | undefined;
  const sent = toolName === 'send_file' ? SENT.exec(first) : null;
  const looked = sent ? null : LOOKED.exec(first);
  if (sent) { by = 'send'; file = sent[1]; mime = sent[2]; }
  else if (looked) { by = 'look'; file = looked[1]; mime = looked[2]; }
  else return null;
  const abs = path.resolve(cwd, file);
  let size: number;
  try {
    const st = fs.statSync(abs);
    if (!st.isFile() || st.size > MAX_MEDIA_BYTES) return null;
    size = st.size;
  } catch { return null; }
  return { id: mediaId(abs, size), name: path.basename(abs), mime: mime ?? mimeFor(abs), size, path: abs, by };
}

/** What the phone is told about a file (not where it is on the computer). */
export function publicMedia(m: MediaRef) {
  return { id: m.id, name: m.name, mime: m.mime, size: m.size, by: m.by };
}

/** A piece of a file as base64, for the phone. `len` is cut to 48 KB. */
export async function readMediaChunk(m: MediaRef, offset: number, len: number): Promise<{ id: string; name: string; mime: string; size: number; offset: number; data: string }> {
  const size = fs.statSync(m.path).size;
  const start = Math.max(0, Math.min(Math.floor(offset) || 0, size));
  const n = Math.max(0, Math.min(Math.floor(len) || 32768, 48 * 1024, size - start));
  const buf = Buffer.alloc(n);
  const fd = await fs.promises.open(m.path, 'r');
  try { await fd.read(buf, 0, n, start); } finally { await fd.close(); }
  return { id: m.id, name: m.name, mime: m.mime, size, offset: start, data: buf.toString('base64') };
}
