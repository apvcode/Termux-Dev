import { describe, expect, it } from 'vitest';
import fs from 'fs';
import path from 'path';
import { KNOWN_COMMANDS, LIVE_COMMANDS, firstToken, pathExists, startsWithPath } from '../../src/cli/slash.js';
import { SLASH_COMMANDS } from '../../src/cli/prompt.js';

describe('a message that begins with a path is not a command', () => {
  it('takes the first word', () => {
    expect(firstToken('/model glm')).toBe('/model');
    expect(firstToken('  /home/mega/a.img flash it')).toBe('/home/mega/a.img');
    expect(firstToken('')).toBe('');
  });

  it('a first word with another slash in it is a path', () => {
    expect(startsWithPath('/home/mega/Downloads/recovery_k4.19.img поставь этот рекавери')).toBe(true);
    expect(startsWithPath('/etc/hosts what is in it')).toBe(true);
    expect(startsWithPath('/tmp/')).toBe(true);
  });

  it('commands are not paths', () => {
    for (const text of ['/', '/model', '/model glm-5.3', '/session del', '/effort high', '/help']) {
      expect(startsWithPath(text), text).toBe(false);
      expect(startsWithPath(text, () => false), text).toBe(false);
    }
    expect(startsWithPath('fix /home/mega/a.img')).toBe(false);   // the path is not first: it never was a command
  });

  it('a one-part path counts only when it exists (and only when asked)', () => {
    expect(startsWithPath('/tmp check it')).toBe(false);          // while typing only the shape counts
    expect(startsWithPath('/tmp check it', (p) => p === '/tmp')).toBe(true);
    expect(startsWithPath('/tmp check it', () => false)).toBe(false);
    expect(startsWithPath('/', () => true)).toBe(false);          // the bare slash opens the command list
  });

  it('pathExists asks the file system', () => {
    expect(pathExists(path.resolve('package.json'))).toBe(true);
    expect(pathExists('/definitely/not/here')).toBe(false);
  });
});

// The prompt and the command loop cannot be unit tested: this guard reads the source.
describe('the places that look for a command', () => {
  const read = (f: string) => fs.readFileSync(path.resolve(__dirname, '..', '..', 'src', 'cli', f), 'utf8');

  it('the command loop skips a message that begins with a path', () => {
    expect(read('index.ts')).toContain("answer.startsWith('/') && !startsWithPath(answer, pathExists)");
  });

  it('the prompt shows the command list and completes a command only for a command', () => {
    const source = read('prompt.ts');
    expect(source).not.toMatch(/if \(input\.startsWith\('\/'\)\) \{/);
    expect(source.match(/input\.startsWith\('\/'\) && !startsWithPath\(input\)/g)).toHaveLength(3);
  });
});

describe('which commands run while the agent works', () => {
  it('every command that runs at once is a known command', () => {
    for (const cmd of LIVE_COMMANDS) expect(KNOWN_COMMANDS, cmd).toContain(cmd);
  });

  it('the commands that change the conversation wait for the end of the turn', () => {
    for (const cmd of ['/compact', '/new', '/clear', '/resume', '/undo', '/rewind', '/commit', '/plan', '/agent', '/exit', '/update']) {
      expect(LIVE_COMMANDS.has(cmd), cmd).toBe(false);
      expect(KNOWN_COMMANDS, cmd).toContain(cmd);
    }
  });

  it('every command in the list under the prompt is known to the command loop', () => {
    for (const { cmd } of SLASH_COMMANDS) expect(KNOWN_COMMANDS, cmd).toContain(cmd.split(' ')[0]);
  });
});
