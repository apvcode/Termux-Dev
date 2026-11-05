import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { IMAGE_WARN_BYTES, resolveAtMentions } from '../../src/cli/files.js';

let dir: string;
let cwd: string;
beforeEach(() => {
  cwd = process.cwd();
  dir = fs.mkdtempSync(path.join(os.tmpdir(), 'devx-files-'));
  process.chdir(dir);
});
afterEach(() => {
  process.chdir(cwd);
  fs.rmSync(dir, { recursive: true, force: true });
});

const PNG = Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47]), Buffer.alloc(64, 1)]);

describe('images in a message', () => {
  it('turns an @mention of an image into a data URL', async () => {
    fs.writeFileSync('shot.png', PNG);
    const r = await resolveAtMentions('what is on @shot.png ?');
    expect(r.images).toHaveLength(1);
    expect(r.images[0].dataUrl.startsWith('data:image/png;base64,')).toBe(true);
    expect(Buffer.from(r.images[0].dataUrl.split(',')[1], 'base64')).toEqual(PNG);
  });

  it('reads a path in quotes, for names with unusual characters', async () => {
    fs.mkdirSync('my dir');
    fs.writeFileSync(path.join('my dir', 'a b.jpg'), PNG);
    const r = await resolveAtMentions(`see @"${path.join(dir, 'my dir', 'a b.jpg')}" now`);
    expect(r.images).toHaveLength(1);
    expect(r.images[0].dataUrl.startsWith('data:image/jpeg;base64,')).toBe(true);
  });

  it('does not send an SVG as an image: it is text', async () => {
    fs.writeFileSync('logo.svg', '<svg xmlns="http://www.w3.org/2000/svg"/>');
    const r = await resolveAtMentions('@logo.svg');
    expect(r.images).toHaveLength(0);
    expect(r.attachments).toHaveLength(1);
  });

  it('warns about a large image but still sends it', async () => {
    fs.writeFileSync('big.png', Buffer.alloc(IMAGE_WARN_BYTES + 10, 1));
    const r = await resolveAtMentions('@big.png');
    expect(r.images).toHaveLength(1);
    expect(r.warnings[0]).toMatch(/5 MB/);
  });
});
