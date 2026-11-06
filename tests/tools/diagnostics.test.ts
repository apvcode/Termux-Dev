import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import fs from 'fs/promises';
import fsSync from 'fs';
import os from 'os';
import path from 'path';
import { diagnoseCodeTool, resolveDiagnoseFile } from '../../src/tools/diagnostics.js';

describe('diagnose_code', () => {
  const original = process.cwd();
  let dir: string;

  beforeEach(async () => {
    dir = await fs.mkdtemp(path.join(os.tmpdir(), 'devx-diag-'));
    process.chdir(dir);
  });
  afterEach(async () => {
    process.chdir(original);
    await fs.rm(dir, { recursive: true, force: true, maxRetries: 10, retryDelay: 150 }).catch((e) => { if (process.platform !== 'win32') throw e; });
  });

  it('rejects shell metacharacters in the file name and runs nothing', async () => {
    const payloads = [
      'x"; touch marker_injected; echo ".js',
      'a.js"; touch marker_injected #',
      'a.js$(touch marker_injected)',
      'a.js`touch marker_injected`',
      'a.js\ntouch marker_injected',
      'a.js | touch marker_injected',
      'a.js & touch marker_injected',
    ];
    for (const file of payloads) {
      expect(() => diagnoseCodeTool.validateArgs({ file })).toThrow();
      await expect(diagnoseCodeTool.execute({ file })).rejects.toThrow();
    }
    expect(fsSync.existsSync(path.join(dir, 'marker_injected'))).toBe(false);
  });

  it('rejects files outside the project, option-like names and missing files', () => {
    expect(() => resolveDiagnoseFile('../outside.js')).toThrow(/inside the project/);
    expect(() => resolveDiagnoseFile('/etc/passwd')).toThrow(/inside the project/);
    expect(() => resolveDiagnoseFile('missing.js')).toThrow(/not found/);
    fsSync.writeFileSync('-e.js', '1');
    expect(() => resolveDiagnoseFile('-e.js')).toThrow(/Invalid file name/);
  });

  it('accepts ordinary paths, including spaces and subdirectories', async () => {
    await fs.mkdir('src dir');
    await fs.writeFile(path.join('src dir', 'ok file.js'), 'const a = 1;');
    expect(resolveDiagnoseFile('src dir/ok file.js')).toBe('src dir/ok file.js');
  });

  it('reports success for valid code', async () => {
    await fs.writeFile('ok.js', 'const a = 1;\n');
    expect(await diagnoseCodeTool.execute({ file: 'ok.js' })).toContain('Diagnostics passed');
  });

  it('reports syntax errors with the compiler output', async () => {
    await fs.writeFile('broken.js', 'const = ;\n');
    const result = await diagnoseCodeTool.execute({ file: 'broken.js' });
    expect(result).toContain('Diagnostic errors found');
    expect(result).toMatch(/SyntaxError/);
  });

  it('can be cancelled', async () => {
    await fs.writeFile('hang.js', 'setInterval(() => {}, 1000);');
    await fs.writeFile('package.json', JSON.stringify({ scripts: { test: 'node hang.js' } }));
    const controller = new AbortController();
    const running = diagnoseCodeTool.execute({}, undefined, { signal: controller.signal });
    setTimeout(() => controller.abort(), 300);
    const started = Date.now();
    const result = await running;
    expect(Date.now() - started).toBeLessThan(5000);
    expect(result).toContain('did not finish');
  }, 10000);

  it('says so when there is nothing to check', async () => {
    expect(await diagnoseCodeTool.execute({})).toContain('No automated checker');
  });
});
