import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import fs from 'fs';
import os from 'os';
import path from 'path';

const PNG = Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), Buffer.alloc(200, 1)]);
const JPEG = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(200, 2)]);
const WEBP = Buffer.concat([Buffer.from('RIFF'), Buffer.alloc(4), Buffer.from('WEBP'), Buffer.alloc(100, 3)]);

let home: string;
let clip: typeof import('../../src/cli/clipboard.js');

beforeEach(async () => {
  home = fs.mkdtempSync(path.join(os.tmpdir(), 'devx-clip-'));
  vi.stubEnv('HOME', home);
  vi.stubEnv('USERPROFILE', home);
  vi.resetModules();
  clip = await import('../../src/cli/clipboard.js');
});

afterEach(() => {
  vi.unstubAllEnvs();
  fs.rmSync(home, { recursive: true, force: true });
});

/** A fake system: each command maps to what it prints (null = missing or failing). */
const system = (answers: Record<string, Buffer | string | null>) => {
  const calls: string[] = [];
  const run = async (cmd: string, args: string[]) => {
    const key = `${cmd} ${args.join(' ')}`.trim();
    calls.push(key);
    const hit = answers[key];
    return hit == null ? null : Buffer.isBuffer(hit) ? hit : Buffer.from(hit);
  };
  return { run, calls };
};

const wayland = { platform: 'linux' as NodeJS.Platform, env: { WAYLAND_DISPLAY: 'wayland-1' } as NodeJS.ProcessEnv };
const x11 = { platform: 'linux' as NodeJS.Platform, env: { DISPLAY: ':0' } as NodeJS.ProcessEnv };

describe('recognising an image', () => {
  it('goes by the bytes, not by the name the clipboard gave', () => {
    expect(clip.sniffImage(PNG)?.ext).toBe('png');
    expect(clip.sniffImage(JPEG)?.mime).toBe('image/jpeg');
    expect(clip.sniffImage(WEBP)?.ext).toBe('webp');
    expect(clip.sniffImage(Buffer.from('GIF89a' + 'x'.repeat(40)))?.ext).toBe('gif');
    expect(clip.sniffImage(Buffer.from('<html>not an image at all</html>'))).toBeNull();
    expect(clip.sniffImage(Buffer.alloc(3))).toBeNull();
  });
});

describe('reading the clipboard', () => {
  it('Wayland: asks which types are there and takes the image', async () => {
    const { run, calls } = system({
      'wl-paste --list-types': 'text/html\nimage/png\n',
      'wl-paste --no-newline --type image/png': PNG,
    });
    expect(await clip.readClipboardImageBytes(run, wayland)).toEqual(PNG);
    expect(calls).toEqual(['wl-paste --list-types', 'wl-paste --no-newline --type image/png']);
  });

  it('takes JPEG when that is all the clipboard has', async () => {
    const { run } = system({
      'wl-paste --list-types': 'image/jpeg\n',
      'wl-paste --no-newline --type image/jpeg': JPEG,
    });
    expect(await clip.readClipboardImageBytes(run, wayland)).toEqual(JPEG);
  });

  it('prefers PNG when several formats are offered', async () => {
    const { run, calls } = system({
      'wl-paste --list-types': 'image/webp\nimage/jpeg\nimage/png\n',
      'wl-paste --no-newline --type image/png': PNG,
    });
    await clip.readClipboardImageBytes(run, wayland);
    expect(calls[1]).toContain('image/png');
  });

  it('X11: uses xclip with the target it found', async () => {
    const { run } = system({
      'xclip -selection clipboard -t TARGETS -o': 'TARGETS\nimage/png\n',
      'xclip -selection clipboard -t image/png -o': PNG,
    });
    expect(await clip.readClipboardImageBytes(run, x11)).toEqual(PNG);
  });

  it('returns null when the clipboard holds only text, or nothing', async () => {
    const { run } = system({ 'wl-paste --list-types': 'text/plain\n' });
    expect(await clip.readClipboardImageBytes(run, wayland)).toBeNull();
    expect(await clip.readClipboardImageBytes(system({}).run, wayland)).toBeNull();
  });

  it('Termux cannot read images and does not try', async () => {
    const { run, calls } = system({});
    expect(await clip.readClipboardImageBytes(run, { platform: 'linux', env: { PREFIX: '/data/data/com.termux/files/usr' } })).toBeNull();
    expect(calls).toHaveLength(0);
  });

  it('reads text for a Ctrl+V that the terminal did not paste', async () => {
    const { run } = system({ 'wl-paste --no-newline --type text': 'hello world' });
    expect(await clip.readClipboardText(run, wayland)).toBe('hello world');
    expect(await clip.readClipboardText(system({}).run, wayland)).toBeNull();
  });
});

describe('saving an image', () => {
  it('writes it under ~/.devx/media with the right extension, privately, and never into the project', async () => {
    const { run } = system({ 'wl-paste --list-types': 'image/jpeg', 'wl-paste --no-newline --type image/jpeg': JPEG });
    const saved = await clip.saveClipboardImage('image.png', new Set(), run, wayland);
    expect(saved).not.toBeNull();
    expect(saved!.filePath.startsWith(path.join(home, '.devx', 'media'))).toBe(true);
    expect(saved!.fileName).toMatch(/^image-\d{8}-\d{6}\.jpg$/);
    expect(fs.readFileSync(saved!.filePath)).toEqual(JPEG);
    if (process.platform !== 'win32') expect(fs.statSync(saved!.filePath).mode & 0o077).toBe(0);
  });

  it('never overwrites an earlier image', async () => {
    const { run } = system({ 'wl-paste --list-types': 'image/png', 'wl-paste --no-newline --type image/png': PNG });
    const a = await clip.saveClipboardImage('image.png', new Set(), run, wayland);
    const b = await clip.saveClipboardImage('image.png', new Set([a!.fileName]), run, wayland);
    expect(b!.filePath).not.toBe(a!.filePath);
  });

  it('refuses bytes that are not an image', async () => {
    const { run } = system({ 'wl-paste --list-types': 'image/png', 'wl-paste --no-newline --type image/png': Buffer.from('definitely not a picture') });
    expect(await clip.saveClipboardImage('image.png', new Set(), run, wayland)).toBeNull();
  });
});

describe('a pasted file path', () => {
  it('copies the image under a name that survives an @mention', async () => {
    const src = path.join(home, 'My Screenshot (1).png');
    fs.writeFileSync(src, PNG);
    const saved = await clip.processPastedFilePath(`'${src}'`, new Set());
    expect(saved?.fileName).toBe('My_Screenshot_1_.png');
    expect(fs.existsSync(saved!.filePath)).toBe(true);
  });

  it('ignores anything that is not an image the APIs accept', async () => {
    const svg = path.join(home, 'logo.svg');
    fs.writeFileSync(svg, '<svg/>');
    expect(await clip.processPastedFilePath(svg, new Set())).toBeNull();
    expect(await clip.processPastedFilePath(path.join(home, 'missing.png'), new Set())).toBeNull();
  });
});

describe('the badge in the prompt', () => {
  it('is found again by the pattern', () => {
    const tag = clip.imageTag(3, '203kb');
    expect(tag).toBe('[Image #3 203kb]');
    expect(new RegExp(clip.IMAGE_TAG_SOURCE).test(`look at ${tag} please`)).toBe(true);
    expect(new RegExp(clip.IMAGE_TAG_SOURCE).test('[Image of a cat]')).toBe(false);
  });
});

describe('old pasted images', () => {
  it('are deleted after the given number of days, newer ones stay', async () => {
    fs.mkdirSync(clip.MEDIA_DIR, { recursive: true });
    const oldFile = path.join(clip.MEDIA_DIR, 'old.png');
    const newFile = path.join(clip.MEDIA_DIR, 'new.png');
    fs.writeFileSync(oldFile, PNG);
    fs.writeFileSync(newFile, PNG);
    const longAgo = new Date(Date.now() - 30 * 86_400_000);
    fs.utimesSync(oldFile, longAgo, longAgo);
    expect(await clip.pruneMedia(14)).toBe(1);
    expect(fs.existsSync(oldFile)).toBe(false);
    expect(fs.existsSync(newFile)).toBe(true);
  });
});
