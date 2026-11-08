// Release sanity checks for CI (run after `npm run build`):
//  1. the version is the same everywhere it is written down;
//  2. the npm package would contain the built program.
import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const read = (rel) => fs.readFileSync(path.join(root, rel), 'utf8');
const problems = [];

const version = JSON.parse(read('package.json')).version;
const lock = JSON.parse(read('package-lock.json'));
const found = {
  'package-lock.json': lock.version,
  'package-lock.json (packages[""])': lock.packages?.['']?.version,
  'src/core/types.ts (DEVX_VERSION)': read('src/core/types.ts').match(/DEVX_VERSION\s*=\s*'([^']+)'/)?.[1],
  'assets/banner.svg': read('assets/banner.svg').match(/>v(\d+\.\d+\.\d+)</)?.[1],
  'assets/preview.svg': read('assets/preview.svg').match(/>v(\d+\.\d+\.\d+)</)?.[1],
};
for (const [where, v] of Object.entries(found)) {
  if (v !== version) problems.push(`${where} says ${v ?? 'nothing'}, package.json says ${version}`);
}

const packed = JSON.parse(execSync('npm pack --dry-run --json', { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }));
// npm 10 prints an array, newer versions an object keyed by the package name.
const entry = Array.isArray(packed) ? packed[0] : Object.values(packed)[0];
const files = new Set((entry?.files ?? []).map((f) => f.path.replace(/\\/g, '/')));
for (const needed of ['bin/devx.js', 'dist/cli/index.js', 'dist/core/types.js', 'dist/tools/mcp-search.js', 'package.json']) {
  if (!files.has(needed)) problems.push(`the npm package would not contain ${needed} (did the build run?)`);
}
for (const leaked of ['src/core/types.ts', '.github/workflows/ci.yml']) {
  if (files.has(leaked)) problems.push(`the npm package would contain ${leaked}`);
}

if (problems.length > 0) {
  console.error('Release check failed:\n- ' + problems.join('\n- '));
  process.exit(1);
}
console.log(`Release check passed: v${version}, ${files.size} files in the package.`);
