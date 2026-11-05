import fs from 'fs/promises';
import os from 'os';
import path from 'path';
import { afterEach, describe, expect, it } from 'vitest';
import { bashTool, DEFAULT_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS, normalizeTimeout } from '../../src/tools/bash.js';

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

  it('shortens long output in the middle but lets the command finish', async () => {
    const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'devx-output-'));
    directories.push(directory);
    const marker = path.join(directory, 'finished');
    const command = nodeCommand(
      `process.stdout.write('x'.repeat(30000)); require('fs').writeFileSync(${JSON.stringify(marker)}, 'done');`
    );
    const result = await bashTool.execute({ command });
    // head (6000) + tail (14000) are kept, the rest is reported
    expect(result).toContain('[... 10000 characters omitted ...]');
    expect(result).toContain('[Exit code: 0]');
    expect(result.length).toBeLessThan(21000);
    // the old behaviour killed the command at the output limit
    expect(await fs.readFile(marker, 'utf8')).toBe('done');
  });

  it('keeps the END of the output where build errors are printed', async () => {
    const command = nodeCommand(
      "for (let i = 0; i < 5000; i++) console.log('line ' + i); console.log('FINAL ERROR SUMMARY');"
    );
    const result = await bashTool.execute({ command });
    expect(result).toContain('omitted');
    expect(result).toContain('FINAL ERROR SUMMARY');
    expect(result).toContain('line 0');
  });

  it('does not corrupt multi-byte characters split across chunks', async () => {
    const command = nodeCommand("process.stdout.write(Buffer.from('Привет мир \u{1F30D}'));");
    expect(await bashTool.execute({ command })).toContain('Привет мир \u{1F30D}');
  });

  it('kills a command that exceeds timeout_seconds and says how to extend it', async () => {
    const command = nodeCommand('setInterval(() => {}, 1000);');
    const started = Date.now();
    const result = await bashTool.execute({ command, timeout_seconds: 1 });
    expect(Date.now() - started).toBeLessThan(6000);
    expect(result).toContain('timeout after 1s');
    expect(result).toContain('timeout_seconds');
  }, 10000);

  it('has a default limit that is much longer than 30 seconds and a bounded maximum', () => {
    expect(DEFAULT_TIMEOUT_SECONDS).toBeGreaterThanOrEqual(120);
    expect(normalizeTimeout(undefined)).toBe(DEFAULT_TIMEOUT_SECONDS);
    expect(normalizeTimeout(-5)).toBe(DEFAULT_TIMEOUT_SECONDS);
    expect(normalizeTimeout('300')).toBe(300);
    expect(normalizeTimeout(999999)).toBe(MAX_TIMEOUT_SECONDS);
  });

  it('closes stdin so interactive commands fail fast instead of hanging', async () => {
    const command = nodeCommand(
      "let n = 0; const t = setTimeout(() => console.log('stdin still open'), 3000); process.stdin.on('data', d => n += d.length); process.stdin.on('end', () => { clearTimeout(t); console.log('stdin closed, bytes=' + n); });"
    );
    const started = Date.now();
    const result = await bashTool.execute({ command });
    expect(result).toContain('stdin closed, bytes=0');
    expect(Date.now() - started).toBeLessThan(2500);
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
