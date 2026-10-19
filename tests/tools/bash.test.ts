import fs from 'fs/promises';
import os from 'os';
import path from 'path';
import { afterEach, describe, expect, it } from 'vitest';
import { bashTool } from '../../src/tools/bash.js';

const controllers: AbortController[] = [];
const directories: string[] = [];

afterEach(async () => {
  for (const controller of controllers.splice(0)) controller.abort();
  await Promise.all(directories.splice(0).map(dir => fs.rm(dir, { recursive: true, force: true })));
});

function nodeCommand(script: string): string {
  const encoded = Buffer.from(script).toString('base64');
  return `"${process.execPath}" -e "eval(Buffer.from('${encoded}','base64').toString())"`;
}

async function waitForFile(file: string) {
  const deadline = Date.now() + 4000;
  while (Date.now() < deadline) {
    try { return await fs.readFile(file, 'utf8'); } catch {}
    await new Promise(resolve => setTimeout(resolve, 20));
  }
  throw new Error('Command did not start');
}

describe('bash tool', () => {
  it('returns output and the exit code', async () => {
    const result = await bashTool.execute({ command: nodeCommand("console.log('hello'); process.exitCode = 7;") });
    expect(result).toContain('hello');
    expect(result).toContain('[Exit code: 7]');
  });

  it('does not start an already cancelled command', async () => {
    const controller = new AbortController();
    controller.abort();
    const result = await bashTool.execute({ command: 'this-command-must-not-run' }, {}, { signal: controller.signal });
    expect(result).toBe('[Operation cancelled by user]');
  });

  it('limits output and stops the command when the limit is exceeded', async () => {
    const command = nodeCommand("process.stdout.write('x'.repeat(30000)); setInterval(() => {}, 1000);");
    const result = await bashTool.execute({ command });
    expect(result).toBe('x'.repeat(20000) + '\n[Output truncated]');
  });

  it('stops a running command and its child before returning', async () => {
    const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'devx-abort-'));
    directories.push(directory);
    const ready = path.join(directory, 'ready');
    const heartbeat = path.join(directory, 'heartbeat');
    const controller = new AbortController();
    controllers.push(controller);
    const childScript = `
      const fs = require('fs');
      process.on('SIGTERM', () => {});
      fs.writeFileSync(${JSON.stringify(ready)}, 'ready');
      setInterval(() => fs.appendFileSync(${JSON.stringify(heartbeat)}, '.'), 30);
    `;
    const script = `
      const { spawn } = require('child_process');
      spawn(process.execPath, ['-e', ${JSON.stringify(childScript)}], { stdio: 'inherit' });
      setInterval(() => {}, 1000);
    `;
    const execution = bashTool.execute({ command: nodeCommand(script) }, {}, { signal: controller.signal });
    await waitForFile(ready);
    await waitForFile(heartbeat);
    controller.abort();
    expect(await execution).toContain('[Operation cancelled by user]');
    const before = await fs.readFile(heartbeat, 'utf8');
    await new Promise(resolve => setTimeout(resolve, 150));
    expect(await fs.readFile(heartbeat, 'utf8')).toBe(before);
  }, 8000);
});
