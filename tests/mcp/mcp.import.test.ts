import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import fs from 'fs/promises';
import fsSync from 'fs';
import os from 'os';
import path from 'path';
import { readClaudeServers, saveUserServers } from '../../src/mcp/manager.js';

let dir: string;
const originalHome = process.env.HOME;
beforeEach(async () => {
  dir = await fs.mkdtemp(path.join(os.tmpdir(), 'devx-imp-'));
  process.env.HOME = dir;
  process.env.USERPROFILE = dir;
});
afterEach(async () => {
  if (originalHome === undefined) delete process.env.HOME; else process.env.HOME = originalHome;
  delete process.env.USERPROFILE;
  await fs.rm(dir, { recursive: true, force: true, maxRetries: 10, retryDelay: 150 }).catch((e) => { if (process.platform !== 'win32') throw e; });
});

describe('servers of Claude Code', () => {
  it('takes the global ones and those of the folder and its parents, local ones as they are, remote ones through mcp-remote', async () => {
    await fs.writeFile(path.join(dir, '.claude.json'), JSON.stringify({
      mcpServers: { notion: { type: 'stdio', command: 'npx', args: ['-y', 'mcp-remote', 'https://mcp.notion.com/mcp'] }, hosted: { type: 'http', url: 'https://example.com/mcp' } },
      projects: {
        [dir]: { mcpServers: { roblox: { command: '/bin/rbxmcp', args: ['serve'], env: { A: '1' } } } },
        [path.join(dir, 'other')]: { mcpServers: { unrelated: { command: 'x' } } },
        [path.join(dir, 'work')]: { mcpServers: { here: { command: 'y' } } },
      },
    }));
    const found = readClaudeServers(path.join(dir, 'app'));
    expect(Object.keys(found).sort()).toEqual(['hosted', 'notion', 'roblox']);
    expect(found.roblox).toEqual({ command: '/bin/rbxmcp', args: ['serve'], env: { A: '1' } });
    expect(found.hosted).toEqual({ command: 'npx', args: ['-y', 'mcp-remote', 'https://example.com/mcp'] });
    expect(Object.keys(readClaudeServers(path.join(dir, 'work'))).sort()).toEqual(['here', 'hosted', 'notion', 'roblox']);
  });

  it('is empty when there is no Claude config or it is broken', async () => {
    expect(readClaudeServers(dir)).toEqual({});
    await fs.writeFile(path.join(dir, '.claude.json'), '{ nope');
    expect(readClaudeServers(dir)).toEqual({});
  });
});

describe('saveUserServers', () => {
  const file = () => path.join(dir, '.devx', 'mcp.json');

  it('writes ~/.devx/mcp.json for the owner only, keeps what is there and does not overwrite by default', () => {
    expect(saveUserServers({ a: { command: 'one' } })).toEqual(['a']);
    expect(saveUserServers({ a: { command: 'two' }, b: { command: 'three' } })).toEqual(['b']);
    const saved = JSON.parse(fsSync.readFileSync(file(), 'utf8'));
    expect(saved.mcpServers).toEqual({ a: { command: 'one' }, b: { command: 'three' } });
    if (process.platform !== 'win32') expect(fsSync.statSync(file()).mode & 0o777).toBe(0o600);
    expect(saveUserServers({ a: { command: 'two' } }, true)).toEqual(['a']);
    expect(JSON.parse(fsSync.readFileSync(file(), 'utf8')).mcpServers.a.command).toBe('two');
  });
});
