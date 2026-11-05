import fs from 'fs/promises';
import fsSync from 'fs';
import path from 'path';

const IGNORED_NAMES = new Set([
  'node_modules',
  '.git',
  '.next',
  'dist',
  'build',
  'coverage',
  '.turbo',
  '.cache',
  'vendor',
  '.tempmediaStorage'
]);

let cachedFiles: string[] = [];
let lastScanTime = 0;

export async function scanProjectFiles(force = false): Promise<string[]> {
  const now = Date.now();
  if (!force && cachedFiles.length > 0 && (now - lastScanTime < 5000)) {
    return cachedFiles;
  }

  const results: string[] = [];

  async function walk(dir: string, depth = 0) {
    if (depth > 6 || results.length > 300) return;
    try {
      const entries = await fs.readdir(dir, { withFileTypes: true });
      for (const entry of entries) {
        if (entry.name.startsWith('.') && entry.name !== '.env') continue;
        if (IGNORED_NAMES.has(entry.name)) continue;

        const fullPath = path.join(dir, entry.name);
        const rel = path.relative(process.cwd(), fullPath).replace(/\\/g, '/');

        if (entry.isDirectory()) {
          results.push(`${rel}/`);
          await walk(fullPath, depth + 1);
        } else if (entry.isFile()) {
          results.push(rel);
        }
      }
    } catch {}
  }

  await walk(process.cwd());
  cachedFiles = results;
  lastScanTime = now;
  return results;
}

// Formats the image APIs accept (SVG is text, not an image for a model).
const IMAGE_EXTS: Record<string, string> = {
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

export interface ResolvedMentions {
  text: string;
  attachments: Array<{ path: string; content: string }>;
  images: Array<{ path: string; dataUrl: string }>;
  /** Things the user should know: an image that is too large, for example. */
  warnings: string[];
}

export async function resolveAtMentions(text: string): Promise<ResolvedMentions> {
  // @path, or @"path with spaces" for a path that has unusual characters
  const mentionRegex = /@(?:"([^"\n]+)"|([a-zA-Z0-9_\-./\u0400-\u04FF]+))/g;
  const attachments: Array<{ path: string; content: string }> = [];
  const images: Array<{ path: string; dataUrl: string }> = [];
  const warnings: string[] = [];
  const seenPaths = new Set<string>();

  let match;
  while ((match = mentionRegex.exec(text)) !== null) {
    const rawPath = match[1] ?? match[2];
    const resolved = path.resolve(process.cwd(), rawPath);

    if (seenPaths.has(resolved)) continue;
    seenPaths.add(resolved);

    if (fsSync.existsSync(resolved)) {
      try {
        const stat = fsSync.statSync(resolved);
        const ext = path.extname(resolved).toLowerCase();

        if (stat.isFile()) {
          if (IMAGE_EXTS[ext]) {
            const name = path.basename(rawPath);
            if (stat.size > IMAGE_MAX_BYTES) {
              warnings.push(`${name} is ${(stat.size / 1048576).toFixed(0)} MB: too large, it was not sent.`);
              continue;
            }
            if (stat.size > IMAGE_WARN_BYTES) {
              warnings.push(`${name} is ${(stat.size / 1048576).toFixed(1)} MB: some providers refuse images above 5 MB.`);
            }
            const buf = await fs.readFile(resolved);
            const b64 = buf.toString('base64');
            const dataUrl = `data:${IMAGE_EXTS[ext]};base64,${b64}`;
            images.push({ path: rawPath, dataUrl });
          } else if (stat.size < 50000) {
            const content = await fs.readFile(resolved, 'utf8');
            attachments.push({ path: rawPath, content });
          }
        }
      } catch {}
    }
  }

  return { text, attachments, images, warnings };
}
