/**
 * Images the model can be shown: which files count as images, how big they may be, and a check
 * that the bytes really are an image. A file the API refuses (a text file named .png, an
 * oversized photo) would stay in the history and make every following request fail, so it is
 * checked before it is sent.
 */

// Formats the image APIs accept (SVG is text, not an image for a model).
export const IMAGE_EXTS: Record<string, string> = {
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
  '.gif': 'image/gif'
};

/** Above this an API usually refuses the image (Anthropic: 5 MB). */
export const IMAGE_WARN_BYTES = 5 * 1024 * 1024;
/** Above this the image is not sent at all. */
export const IMAGE_MAX_BYTES = 20 * 1024 * 1024;

/** The media type for a file name, when its extension is one of an image. */
export function imageMimeForPath(file: string): string | undefined {
  const dot = file.lastIndexOf('.');
  if (dot === -1) return undefined;
  return IMAGE_EXTS[file.slice(dot).toLowerCase()];
}

/** The media type the first bytes say, or undefined when they are not PNG, JPEG, GIF or WebP. */
export function sniffImageMime(buf: Uint8Array): string | undefined {
  const at = (i: number) => buf[i];
  if (buf.length >= 8 && at(0) === 0x89 && at(1) === 0x50 && at(2) === 0x4e && at(3) === 0x47 && at(4) === 0x0d && at(5) === 0x0a && at(6) === 0x1a && at(7) === 0x0a) {
    return 'image/png';
  }
  if (buf.length >= 3 && at(0) === 0xff && at(1) === 0xd8 && at(2) === 0xff) return 'image/jpeg';
  if (buf.length >= 6) {
    const head = String.fromCharCode(at(0), at(1), at(2), at(3), at(4), at(5));
    if (head === 'GIF87a' || head === 'GIF89a') return 'image/gif';
  }
  if (buf.length >= 12) {
    const riff = String.fromCharCode(at(0), at(1), at(2), at(3));
    const webp = String.fromCharCode(at(8), at(9), at(10), at(11));
    if (riff === 'RIFF' && webp === 'WEBP') return 'image/webp';
  }
  return undefined;
}
