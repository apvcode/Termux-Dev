import crypto from 'crypto';
import fs from 'fs/promises';
import path from 'path';
import type { Message } from './types.js';
import { ensureSecureDir } from './secure-file.js';

/**
 * Pictures of a saved chat live in files next to it, not inside its JSON. A picture is a data URL
 * of up to several megabytes; written into the chat file it made every save (after every turn)
 * rewrite all of them, and the list of chats (/resume) had to read and parse every one.
 *
 * On disk a message carries `{ path, file }`; the bytes are in `<chat id>.images/<file>`, named by
 * their hash, so the same picture is stored once however often it is saved.
 */

const EXT_BY_MIME: Record<string, string> = {
  'image/png': 'png',
  'image/jpeg': 'jpg',
  'image/webp': 'webp',
  'image/gif': 'gif',
};
const MIME_BY_EXT: Record<string, string> = Object.fromEntries(Object.entries(EXT_BY_MIME).map(([mime, ext]) => [ext, mime]));

export const imagesDirFor = (sessionsDir: string, sessionId: string): string => path.join(sessionsDir, `${sessionId}.images`);

/** Remembers the file of each picture seen, so a big data URL is not hashed on every save. */
export type MediaIndex = Map<string, string>;

type Image = NonNullable<Message['images']>[number];

const DATA_URL = /^data:([^;,]+);base64,(.*)$/s;

/**
 * The messages as they are written to disk: pictures replaced by the name of their file, the
 * files written. Returns the names in use, so the others can be removed. The messages passed in
 * are not changed.
 */
export async function externalizeImages(
  messages: Message[],
  dir: string,
  index: MediaIndex = new Map()
): Promise<{ messages: Message[]; used: Set<string> }> {
  const used = new Set<string>();
  let dirReady = false;

  const store = async (image: Image): Promise<Image> => {
    const match = DATA_URL.exec(image.dataUrl ?? '');
    const ext = match ? EXT_BY_MIME[match[1]] : undefined;
    if (!match || !ext) return image; // not something we can store: keep it as it is

    let file = index.get(image.dataUrl);
    if (!file) {
      file = `${crypto.createHash('sha256').update(image.dataUrl).digest('hex').slice(0, 32)}.${ext}`;
      index.set(image.dataUrl, file);
    }
    used.add(file);
    const target = path.join(dir, file);
    try {
      await fs.access(target);
    } catch {
      if (!dirReady) {
        await ensureSecureDir(dir);
        dirReady = true;
      }
      // Written aside and renamed: a crash must not leave half a picture under the real name.
      const part = `${target}.part`;
      await fs.writeFile(part, Buffer.from(match[2], 'base64'), { mode: 0o600 });
      await fs.rename(part, target);
    }
    return { path: image.path, dataUrl: '', file } as Image;
  };

  const out: Message[] = [];
  for (const message of messages) {
    if (!message.images || message.images.length === 0) {
      out.push(message);
      continue;
    }
    const images: Image[] = [];
    for (const image of message.images) images.push(await store(image));
    out.push({ ...message, images });
  }
  return { messages: out, used };
}

/**
 * The messages of a loaded chat with their pictures read back from the files. A picture whose
 * file is gone is dropped (the text of the message still says what it was); a chat saved by an
 * older devx, with the data inside, is left as it is.
 */
export async function internalizeImages(messages: Message[], dir: string): Promise<Message[]> {
  const out: Message[] = [];
  for (const message of messages) {
    if (!message.images || message.images.length === 0) {
      out.push(message);
      continue;
    }
    const images: Image[] = [];
    for (const image of message.images) {
      const file = (image as any).file as string | undefined;
      if (!file) {
        if (image.dataUrl) images.push(image);
        continue;
      }
      const mime = MIME_BY_EXT[path.extname(file).slice(1).toLowerCase()];
      // A name from a file on disk must not lead out of the folder.
      if (!mime || path.basename(file) !== file) continue;
      try {
        const data = await fs.readFile(path.join(dir, file));
        images.push({ path: image.path, dataUrl: `data:${mime};base64,${data.toString('base64')}` });
      } catch {}
    }
    const { images: _dropped, ...rest } = message;
    out.push(images.length > 0 ? { ...rest, images } : rest);
  }
  return out;
}

/** Removes the picture files that no message uses any more (after a /rewind, for example). */
export async function pruneImages(dir: string, used: Set<string>): Promise<void> {
  let names: string[];
  try {
    names = await fs.readdir(dir);
  } catch {
    return;
  }
  await Promise.all(names.filter((n) => !used.has(n)).map((n) => fs.rm(path.join(dir, n), { force: true }).catch(() => {})));
}
