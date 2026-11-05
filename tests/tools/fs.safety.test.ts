import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import fs from 'fs/promises';
import os from 'os';
import path from 'path';
import { editFileTool, readFileTool, writeFileTool, MAX_READ_CHARS } from '../../src/tools/fs.js';

describe('read_file limits', () => {
  let dir: string;
  beforeEach(async () => {
    dir = await fs.mkdtemp(path.join(os.tmpdir(), 'devx-read-'));
  });
  afterEach(async () => {
    await fs.rm(dir, { recursive: true, force: true });
  });

  const bigFile = async () => {
    const file = path.join(dir, 'big.txt');
    const lines = Array.from({ length: 20000 }, (_, i) => `line ${i + 1}: ${'x'.repeat(40)}`);
    await fs.writeFile(file, lines.join('\n'));
    return { file, lines };
  };

  it('returns small files unchanged, without any header', async () => {
    const file = path.join(dir, 'small.txt');
    await fs.writeFile(file, 'a\nb\nc');
    expect(await readFileTool.execute({ path: file })).toBe('a\nb\nc');
  });

  it('truncates a huge file and tells the model where to continue', async () => {
    const { file } = await bigFile();
    const result = await readFileTool.execute({ path: file });
    expect(result.length).toBeLessThan(MAX_READ_CHARS + 300);
    expect(result).toMatch(/^\[Lines 1-\d+ of 20000 - output truncated, continue with startLine=\d+\]/);
    expect(result).toContain('line 1:');
    expect(result).not.toContain('line 20000:');
  });

  it('continues exactly where the previous part stopped', async () => {
    const { file, lines } = await bigFile();
    const first = await readFileTool.execute({ path: file });
    const next = Number(first.match(/startLine=(\d+)/)![1]);
    const second = await readFileTool.execute({ path: file, startLine: next });
    const firstBody = first.split('\n').slice(1);
    const secondBody = second.split('\n').slice(1);
    expect(firstBody[firstBody.length - 1]).toBe(lines[next - 2]);
    expect(secondBody[0]).toBe(lines[next - 1]);
  });

  it('reads an explicit line range', async () => {
    const file = path.join(dir, 'range.txt');
    await fs.writeFile(file, ['one', 'two', 'three', 'four', 'five'].join('\n'));
    const result = await readFileTool.execute({ path: file, startLine: 2, endLine: 4 });
    expect(result).toBe('[Lines 2-4 of 5 - file continues, use startLine=5 to read more]\ntwo\nthree\nfour');
  });

  it('does not dump binary files into the context', async () => {
    const file = path.join(dir, 'image.bin');
    await fs.writeFile(file, Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x00, 0x01, 0x02]));
    expect(await readFileTool.execute({ path: file })).toContain('[Binary file');
  });

  it('refuses directories and absurdly large files', async () => {
    await expect(readFileTool.execute({ path: dir })).rejects.toThrow(/directory/);
    const huge = path.join(dir, 'huge.bin');
    const handle = await fs.open(huge, 'w');
    await handle.truncate(11 * 1024 * 1024); // sparse, costs nothing on disk
    await handle.close();
    await expect(readFileTool.execute({ path: huge })).rejects.toThrow(/too large/);
  });

  it('validates the line arguments', () => {
    expect(() => readFileTool.validateArgs({ path: 'a', startLine: 0 })).toThrow(/positive integer/);
    expect(() => readFileTool.validateArgs({ path: 'a', startLine: 1.5 })).toThrow(/positive integer/);
    expect(() => readFileTool.validateArgs({ path: 'a', startLine: 5, endLine: 2 })).toThrow(/endLine/);
    expect(() => readFileTool.validateArgs({ path: 'a', startLine: 2, endLine: 9 })).not.toThrow();
  });
});

describe('edit_file empty target', () => {
  let dir: string;
  beforeEach(async () => {
    dir = await fs.mkdtemp(path.join(os.tmpdir(), 'devx-edit-'));
  });
  afterEach(async () => {
    await fs.rm(dir, { recursive: true, force: true });
  });

  it('is rejected by validateArgs and by execute, and never touches the file', async () => {
    const file = path.join(dir, 'a.txt');
    await writeFileTool.execute({ path: file, content: 'hello' });

    expect(() => editFileTool.validateArgs({ path: file, target: '', replacement: 'X' })).toThrow(/must not be empty/);
    await expect(
      editFileTool.execute({ path: file, target: '', replacement: 'X', replaceAll: true })
    ).rejects.toThrow(/must not be empty/);

    expect(await fs.readFile(file, 'utf8')).toBe('hello');
  });
});
