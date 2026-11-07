import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import fs from 'fs/promises';
import os from 'os';
import path from 'path';
import { externalizeImages, internalizeImages, pruneImages } from '../../src/core/session-media.js';
import type { Message } from '../../src/core/types.js';
import { PNG_1X1 } from '../helpers/png.js';

const png = (name: string, bytes: Buffer = PNG_1X1) => ({ path: name, dataUrl: `data:image/png;base64,${bytes.toString('base64')}` });
const withImages = (...images: ReturnType<typeof png>[]): Message[] => [
  { role: 'user', content: 'look', images: [images[0]] },
  { role: 'tool', content: '[Image attached]', tool_call_id: 'a', images: images.slice(1) },
];

describe('pictures of a saved chat', () => {
  let dir: string;
  beforeEach(async () => { dir = await fs.mkdtemp(path.join(os.tmpdir(), 'devx-media-')); });
  afterEach(async () => { await fs.rm(dir, { recursive: true, force: true }); });

  it('are written to files named by their content, and the messages carry only the names', async () => {
    const images = path.join(dir, 's1.images');
    const messages = withImages(png('a.png'), png('b.png', Buffer.concat([PNG_1X1, Buffer.from('x')])));
    const { messages: stored, used } = await externalizeImages(messages, images);
    expect(JSON.stringify(stored)).not.toContain('base64');
    const [first] = stored[0].images! as any[];
    expect(first.path).toBe('a.png');
    expect(first.file).toMatch(/^[0-9a-f]{32}\.png$/);
    expect(used.size).toBe(2);
    expect(await fs.readFile(path.join(images, first.file))).toEqual(PNG_1X1);
    // The messages that were passed in are not touched.
    expect(messages[0].images![0].dataUrl).toContain('base64');
  });

  it('are stored once however often they are saved or used', async () => {
    const images = path.join(dir, 's1.images');
    const same = png('same.png');
    const index = new Map<string, string>();
    await externalizeImages(withImages(same, same), images, index);
    await externalizeImages(withImages(same, same), images, index);
    expect(await fs.readdir(images)).toHaveLength(1);
  });

  it('come back from the files exactly as they were', async () => {
    const images = path.join(dir, 's1.images');
    const original = withImages(png('a.png'), png('b.png', Buffer.concat([PNG_1X1, Buffer.from('y')])));
    const { messages: stored } = await externalizeImages(original, images);
    const back = await internalizeImages(JSON.parse(JSON.stringify(stored)), images);
    expect(back).toEqual(original);
  });

  it('a picture whose file is gone is dropped, and the message stays', async () => {
    const images = path.join(dir, 's1.images');
    const { messages: stored, used } = await externalizeImages(withImages(png('a.png')), images);
    for (const name of used) await fs.rm(path.join(images, name));
    const back = await internalizeImages(stored, images);
    expect(back[0].content).toBe('look');
    expect(back[0].images).toBeUndefined();
  });

  it('a chat saved by an older devx, with the data inside, still opens', async () => {
    const old = withImages(png('a.png'));
    expect(await internalizeImages(old, path.join(dir, 'none'))).toEqual(old);
  });

  it('refuses a file name that leads out of the folder', async () => {
    const outside = path.join(dir, 'secret.png');
    await fs.writeFile(outside, PNG_1X1);
    const crafted: Message[] = [{ role: 'user', content: 'x', images: [{ path: 'p', dataUrl: '', file: '../secret.png' } as any] }];
    const back = await internalizeImages(crafted, path.join(dir, 's1.images'));
    expect(back[0].images).toBeUndefined();
  });

  it('keeps what it cannot store (not a data URL) as it is', async () => {
    const odd: Message[] = [{ role: 'user', content: 'x', images: [{ path: 'p', dataUrl: 'https://example.com/a.png' }] }];
    const { messages, used } = await externalizeImages(odd, path.join(dir, 's1.images'));
    expect(messages[0].images![0].dataUrl).toBe('https://example.com/a.png');
    expect(used.size).toBe(0);
  });

  it('removes the files nothing uses any more', async () => {
    const images = path.join(dir, 's1.images');
    const { used } = await externalizeImages(withImages(png('a.png'), png('b.png', Buffer.concat([PNG_1X1, Buffer.from('z')]))), images);
    const [keep] = [...used];
    await pruneImages(images, new Set([keep]));
    expect(await fs.readdir(images)).toEqual([keep]);
    await pruneImages(path.join(dir, 'does-not-exist'), new Set()); // no error
  });
});

describe('SessionManager and pictures', () => {
  let home: string;
  beforeEach(async () => {
    home = await fs.mkdtemp(path.join(os.tmpdir(), 'devx-home-'));
    vi.stubEnv('HOME', home);
    vi.stubEnv('USERPROFILE', home);
    vi.resetModules();
  });
  afterEach(async () => {
    vi.unstubAllEnvs();
    await fs.rm(home, { recursive: true, force: true });
  });

  it('keeps the chat file small, gives the pictures back on load, and deletes them with the chat', async () => {
    const { SessionManager } = await import('../../src/core/session.js');
    const manager = new SessionManager('m', false);
    const big = Buffer.concat([PNG_1X1, Buffer.alloc(300_000, 7)]);
    const messages = withImages(png('a.png', big));
    await manager.save(messages, 0);

    const id = manager.getSession().id;
    const sessions = path.join(home, '.devx', 'sessions');
    const chatFile = await fs.readFile(path.join(sessions, `${id}.json`), 'utf8');
    expect(chatFile.length).toBeLessThan(5000);
    expect(chatFile).not.toContain('base64');

    // The list of chats reads small files.
    expect((await SessionManager.listSessions()).map((s) => s.id)).toContain(id);

    const loaded = await SessionManager.loadSession(id);
    expect(loaded!.messages[0].images![0].dataUrl).toBe(messages[0].images![0].dataUrl);

    // saving again with fewer pictures removes the file that is no longer used
    await manager.save([{ role: 'user', content: 'no pictures now' }], 0);
    expect(await fs.readdir(path.join(sessions, `${id}.images`))).toEqual([]);

    await manager.save(messages, 0);
    expect(await fs.readdir(path.join(sessions, `${id}.images`))).toHaveLength(1);
    expect(await SessionManager.deleteSession(id)).toBe(true);
    await expect(fs.access(path.join(sessions, `${id}.images`))).rejects.toThrow();
  });

  it('deleting all chats takes their pictures too', async () => {
    const { SessionManager } = await import('../../src/core/session.js');
    const manager = new SessionManager('m', false);
    await manager.save(withImages(png('a.png')), 0);
    expect(await SessionManager.deleteAllSessions()).toBe(1);
    expect(await fs.readdir(path.join(home, '.devx', 'sessions'))).toEqual([]);
  });
});
