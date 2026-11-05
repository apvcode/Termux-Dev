import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(__dirname, '..');

const files = [
  ['src/core/model-limits.json', 'dist/core/model-limits.json'],
  ['src/core/price-catalog.json', 'dist/core/price-catalog.json'],
  ['src/core/vision-catalog.json', 'dist/core/vision-catalog.json'],
  ['src/core/reasoning-catalog.json', 'dist/core/reasoning-catalog.json']
];

for (const [src, dest] of files) {
  const srcPath = path.join(root, src);
  const destPath = path.join(root, dest);
  if (fs.existsSync(srcPath)) {
    fs.mkdirSync(path.dirname(destPath), { recursive: true });
    fs.copyFileSync(srcPath, destPath);
    console.log(`Copied ${src} -> ${dest}`);
  }
}
