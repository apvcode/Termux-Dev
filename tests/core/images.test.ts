import { describe, expect, it } from 'vitest';
import { imageMimeForPath, sniffImageMime } from '../../src/core/images.js';

const bytes = (...b: number[]) => Uint8Array.from(b);
const ascii = (s: string) => Uint8Array.from([...s].map((c) => c.charCodeAt(0)));

describe('imageMimeForPath', () => {
  it('knows the formats the image APIs take, in any case, and not SVG', () => {
    expect(imageMimeForPath('/a/b/photo.PNG')).toBe('image/png');
    expect(imageMimeForPath('x.jpg')).toBe('image/jpeg');
    expect(imageMimeForPath('x.jpeg')).toBe('image/jpeg');
    expect(imageMimeForPath('x.webp')).toBe('image/webp');
    expect(imageMimeForPath('x.gif')).toBe('image/gif');
    expect(imageMimeForPath('logo.svg')).toBeUndefined();
    expect(imageMimeForPath('README')).toBeUndefined();
    expect(imageMimeForPath('notes.png.txt')).toBeUndefined();
  });
});

describe('sniffImageMime', () => {
  it('reads the format from the first bytes', () => {
    expect(sniffImageMime(bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0))).toBe('image/png');
    expect(sniffImageMime(bytes(0xff, 0xd8, 0xff, 0xe0))).toBe('image/jpeg');
    expect(sniffImageMime(ascii('GIF89a....'))).toBe('image/gif');
    expect(sniffImageMime(ascii('GIF87a....'))).toBe('image/gif');
    expect(sniffImageMime(Uint8Array.from([...ascii('RIFF'), 1, 2, 3, 4, ...ascii('WEBPVP8 ')]))).toBe('image/webp');
  });

  it('refuses everything else, including a text file or a WAV named like an image', () => {
    expect(sniffImageMime(ascii('hello, I am not a picture'))).toBeUndefined();
    expect(sniffImageMime(Uint8Array.from([...ascii('RIFF'), 1, 2, 3, 4, ...ascii('WAVEfmt ')]))).toBeUndefined();
    expect(sniffImageMime(bytes())).toBeUndefined();
    expect(sniffImageMime(bytes(0x89, 0x50))).toBeUndefined();
  });
});
