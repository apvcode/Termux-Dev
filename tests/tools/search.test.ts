import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import fs from 'fs/promises';
import path from 'path';
import os from 'os';
import { searchTool } from '../../src/tools/search.js';

describe('searchTool', () => {
  let tmpDir: string;

  beforeEach(async () => {
    tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), 'devx-search-test-'));
  });

  afterEach(async () => {
    await fs.rm(tmpDir, { recursive: true, force: true });
  });

  it('finds matching query in project files', async () => {
    const file1 = path.join(tmpDir, 'file1.ts');
    await fs.writeFile(file1, 'const secretKey = "XYZ_123";\nconst other = 1;', 'utf8');

    const result = await searchTool.execute({ query: 'secretKey', dir: tmpDir });
    expect(result).toContain('file1.ts:1: const secretKey = "XYZ_123";');
  });

  it('skips files exceeding 512KB to prevent OOM', async () => {
    const largeFile = path.join(tmpDir, 'large.txt');
    // Create 600KB file containing search keyword
    const largeContent = 'A'.repeat(600 * 1024) + '\nTARGET_KEYWORD\n';
    await fs.writeFile(largeFile, largeContent, 'utf8');

    const result = await searchTool.execute({ query: 'TARGET_KEYWORD', dir: tmpDir });
    expect(result).toContain('No matches found');
  });

  it('skips compiled binary files without extension containing null bytes', async () => {
    const binaryFile = path.join(tmpDir, 'my_binary_executable');
    // Binary file (ELF header or null bytes with query inside)
    const binaryBuffer = Buffer.concat([
      Buffer.from([0x7f, 0x45, 0x4c, 0x46, 0x00, 0x01]),
      Buffer.from('TARGET_KEYWORD'),
      Buffer.from([0x00, 0x00, 0x00])
    ]);
    await fs.writeFile(binaryFile, binaryBuffer);

    const result = await searchTool.execute({ query: 'TARGET_KEYWORD', dir: tmpDir });
    expect(result).toContain('No matches found');
  });
});
