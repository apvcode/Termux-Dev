import fs from 'fs';
import os from 'os';
import path from 'path';
import { describe, expect, it } from 'vitest';
import { mediaFromResult, readMediaChunk, publicMedia } from '../../src/remote/media.js';
import { sendFileTool } from '../../src/tools/send-file.js';
import { snapshotItems } from '../../src/remote/runtime.js';

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'devx-media-'));
const png = Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), Buffer.alloc(100000, 7)]);
fs.writeFileSync(path.join(dir, 'shot.png'), png);
fs.writeFileSync(path.join(dir, 'notes.zip'), Buffer.alloc(5000, 1));

describe('media for the phone', () => {
  it('finds a picture the agent looked at', () => {
    const ref = mediaFromResult('read_file', `[Image shot.png (image/png, 98 KB) is attached to this result: look at it]`, dir)!;
    expect(ref.by).toBe('look');
    expect(ref.name).toBe('shot.png');
    expect(ref.mime).toBe('image/png');
    expect(ref.size).toBe(png.length);
    expect(publicMedia(ref)).not.toHaveProperty('path');
  });

  it('finds a file that was sent, and ignores other tools and missing files', async () => {
    const result = await sendFileTool.execute({ path: path.join(dir, 'notes.zip') });
    const ref = mediaFromResult('send_file', result, dir)!;
    expect(ref.by).toBe('send');
    expect(ref.mime).toBe('application/zip');
    expect(mediaFromResult('bash', result, dir)).toBeNull();
    expect(mediaFromResult('send_file', '[File /nope/none.txt (text/plain, 4 bytes) was sent to the phone as "none.txt"]', dir)).toBeNull();
  });

  it('send_file refuses what is not a file or is empty', async () => {
    expect(await sendFileTool.execute({ path: dir })).toMatch(/not a file/);
    expect(await sendFileTool.execute({ path: path.join(dir, 'missing') })).toMatch(/no such file/);
  });

  it('reads a file in chunks that add up to the file', async () => {
    const ref = mediaFromResult('read_file', `[Image shot.png (image/png, 98 KB) is attached to this result: look at it]`, dir)!;
    const parts: Buffer[] = [];
    let offset = 0;
    while (offset < ref.size) {
      const c = await readMediaChunk(ref, offset, 32768);
      const bytes = Buffer.from(c.data, 'base64');
      expect(bytes.length).toBeLessThanOrEqual(32768);
      parts.push(bytes);
      offset += bytes.length;
    }
    expect(Buffer.concat(parts).equals(png)).toBe(true);
  });

  it('shows media in the chat items right after the tool, for chats opened later', () => {
    const seen: string[] = [];
    const items = snapshotItems([
      { role: 'assistant', content: '', tool_calls: [{ id: 'c1', name: 'read_file', arguments: { path: 'shot.png' } }] } as any,
      { role: 'tool', tool_call_id: 'c1', name: 'read_file', content: '[Image shot.png (image/png, 98 KB) is attached to this result: look at it]' } as any,
    ], dir, (r) => seen.push(r.id));
    expect(items.map((i) => i.k)).toEqual(['tool', 'media']);
    expect(items[1].name).toBe('shot.png');
    expect(seen).toHaveLength(1);
    // Without a folder (a terminal chat) nothing is added.
    expect(snapshotItems([{ role: 'tool', tool_call_id: 'x', name: 'read_file', content: '[Image shot.png (image/png, 98 KB) is attached to this result: look at it]' } as any]).length).toBe(0);
  });
});

describe('pictures named by path in an answer', () => {
  it('registers a real file and refuses folders, missing and empty files', async () => {
    const { mediaForPath } = await import('../../src/remote/media.js');
    expect(mediaForPath('shot.png', dir)?.mime).toBe('image/png');
    expect(mediaForPath('nope.png', dir)).toBeNull();
    expect(mediaForPath('.', dir)).toBeNull();
    fs.writeFileSync(path.join(dir, 'empty.png'), '');
    expect(mediaForPath('empty.png', dir)).toBeNull();
  });
});
