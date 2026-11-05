import { describe, expect, it } from 'vitest';
import fs from 'fs';
import path from 'path';

// drawLogo() clears the screen AND the scrollback. That is right for /clear, /new and /resume
// (a different conversation starts), but a command that only changes a setting or opens a
// picker must leave the conversation on screen. This guard reads the source because the
// command loop cannot be unit tested.
const source = fs.readFileSync(path.resolve(__dirname, '..', '..', 'src', 'cli', 'index.ts'), 'utf8');

function blockOf(start: string): string {
  const from = source.indexOf(start);
  expect(from, `${start} not found`).toBeGreaterThan(-1);
  // the handler ends with its closing brace after the first `continue;`
  const end = source.indexOf('continue;\n      }', from);
  return source.slice(from, end === -1 ? from + 1500 : end);
}

describe('commands that keep the conversation on screen', () => {
  it.each([
    "if (cmd === '/settings') {",
    "if (cmd === '/plan') {",
    "if (cmd === '/agent') {",
    "if (cmd === '/model') {",
    "if (cmd === '/provider' || cmd === '/providers') {",
    "if (cmd === '/effort') {",
  ])('%s does not clear the screen', (start) => {
    expect(blockOf(start)).not.toContain('drawLogo(');
  });

  it('the theme picker and /theme <name> do not clear the screen', () => {
    const picker = source.slice(source.indexOf('async function handleThemeSelect'), source.indexOf('async function handleSettings'));
    expect(picker).not.toContain('drawLogo(');
    const themeName = source.slice(source.indexOf('const th = setActiveTheme(matched.id);'), source.indexOf('const th = setActiveTheme(matched.id);') + 200);
    expect(themeName).not.toContain('drawLogo(');
  });

  it('handleSettings does not clear the screen either', () => {
    const settings = source.slice(source.indexOf('async function handleSettings'), source.indexOf('export async function main'));
    expect(settings).not.toContain('drawLogo(');
  });

  it('the commands that DO start another conversation still redraw', () => {
    expect(source.slice(source.indexOf('const startFreshSession'), source.indexOf('const announce'))).toContain('drawLogo(');
  });
});
