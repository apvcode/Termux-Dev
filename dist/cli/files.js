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
let cachedFiles = [];
let lastScanTime = 0;
export async function scanProjectFiles(force = false) {
    const now = Date.now();
    if (!force && cachedFiles.length > 0 && (now - lastScanTime < 5000)) {
        return cachedFiles;
    }
    const results = [];
    async function walk(dir, depth = 0) {
        if (depth > 6 || results.length > 300)
            return;
        try {
            const entries = await fs.readdir(dir, { withFileTypes: true });
            for (const entry of entries) {
                if (entry.name.startsWith('.') && entry.name !== '.env')
                    continue;
                if (IGNORED_NAMES.has(entry.name))
                    continue;
                const fullPath = path.join(dir, entry.name);
                const rel = path.relative(process.cwd(), fullPath).replace(/\\/g, '/');
                if (entry.isDirectory()) {
                    results.push(`${rel}/`);
                    await walk(fullPath, depth + 1);
                }
                else if (entry.isFile()) {
                    results.push(rel);
                }
            }
        }
        catch { }
    }
    await walk(process.cwd());
    cachedFiles = results;
    lastScanTime = now;
    return results;
}
const IMAGE_EXTS = {
    '.png': 'image/png',
    '.jpg': 'image/jpeg',
    '.jpeg': 'image/jpeg',
    '.webp': 'image/webp',
    '.gif': 'image/gif',
    '.svg': 'image/svg+xml'
};
export async function resolveAtMentions(text) {
    const mentionRegex = /@([a-zA-Z0-9_\-./\u0400-\u04FF]+)/g;
    const attachments = [];
    const images = [];
    const seenPaths = new Set();
    let match;
    while ((match = mentionRegex.exec(text)) !== null) {
        const rawPath = match[1];
        const resolved = path.resolve(process.cwd(), rawPath);
        if (seenPaths.has(resolved))
            continue;
        seenPaths.add(resolved);
        if (fsSync.existsSync(resolved)) {
            try {
                const stat = fsSync.statSync(resolved);
                const ext = path.extname(resolved).toLowerCase();
                if (stat.isFile()) {
                    if (IMAGE_EXTS[ext]) {
                        const buf = await fs.readFile(resolved);
                        const b64 = buf.toString('base64');
                        const dataUrl = `data:${IMAGE_EXTS[ext]};base64,${b64}`;
                        images.push({ path: rawPath, dataUrl });
                    }
                    else if (stat.size < 50000) {
                        const content = await fs.readFile(resolved, 'utf8');
                        attachments.push({ path: rawPath, content });
                    }
                }
            }
            catch { }
        }
    }
    return { text, attachments, images };
}
