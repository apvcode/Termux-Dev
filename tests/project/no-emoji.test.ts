import { describe, expect, it } from 'vitest';
import fs from 'fs';
import path from 'path';

// Project policy: no emoji anywhere (UI strings, prompts, docs, CI). Plain text and simple
// symbols such as ● ◆ ▲ ✓ ✗ ❯ are fine. Tests that need an emoji as data write it as an
// escape sequence (for example "\u{1F600}") so that no emoji character is stored in a file.
const ROOT = path.resolve(__dirname, '..', '..');

// Characters that look like emoji ranges but are plain text symbols we use on purpose.
const ALLOWED = new Set('✓✗✖❯'.split(''));

function isEmoji(ch: string): boolean {
  if (ALLOWED.has(ch)) return false;
  const cp = ch.codePointAt(0)!;
  return (
    (cp >= 0x1f000 && cp <= 0x1faff) ||
    (cp >= 0x2600 && cp <= 0x27bf) ||
    (cp >= 0x2b00 && cp <= 0x2bff) ||
    (cp >= 0x2300 && cp <= 0x23ff) ||
    cp === 0xfe0f || // emoji presentation selector
    cp === 0x20e3    // keycap
  );
}

function walk(dir: string, out: string[] = []): string[] {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full, out);
    else if (/\.(ts|js|json|md|yml|yaml|svg)$/.test(entry.name)) out.push(full);
  }
  return out;
}

describe('no emoji in the project', () => {
  const files = [
    ...walk(path.join(ROOT, 'src')),
    ...walk(path.join(ROOT, 'tests')),
    ...walk(path.join(ROOT, 'scripts')),
    ...walk(path.join(ROOT, '.github')),
    path.join(ROOT, 'README.md'),
  ];

  it('scans a meaningful number of files', () => {
    expect(files.length).toBeGreaterThan(40);
  });

  it('finds no emoji character in sources, tests, docs or CI config', () => {
    const offenders: string[] = [];
    for (const file of files) {
      const lines = fs.readFileSync(file, 'utf8').split('\n');
      lines.forEach((line, i) => {
        for (const ch of line) {
          if (isEmoji(ch)) {
            offenders.push(`${path.relative(ROOT, file)}:${i + 1}  U+${ch.codePointAt(0)!.toString(16).toUpperCase()}  ${line.trim().slice(0, 80)}`);
            break;
          }
        }
      });
    }
    expect(offenders, `emoji found:\n${offenders.join('\n')}`).toEqual([]);
  });
});
