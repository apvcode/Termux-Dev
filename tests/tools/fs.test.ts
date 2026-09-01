import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import fs from 'fs/promises';
import path from 'path';
import os from 'os';
import { readFileTool, writeFileTool, editFileTool, listDirTool, mkdirTool } from '../../src/tools/fs.js';

describe('FS Tools', () => {
  let tmpDir: string;

  beforeEach(async () => {
    tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), 'devx-fs-test-'));
  });

  afterEach(async () => {
    await fs.rm(tmpDir, { recursive: true, force: true });
  });

  describe('write_file & read_file', () => {
    it('creates a new file and reads it back', async () => {
      const filePath = path.join(tmpDir, 'hello.txt');
      const writeResult = await writeFileTool.execute({ path: filePath, content: 'Hello DevX!' });
      const parsed = JSON.parse(writeResult);
      expect(parsed.status).toBe('success');
      expect(parsed.addedCount).toBe(1);

      const content = await readFileTool.execute({ path: filePath });
      expect(content).toBe('Hello DevX!');
    });

    it('creates parent directories automatically', async () => {
      const filePath = path.join(tmpDir, 'nested', 'sub', 'test.txt');
      await writeFileTool.execute({ path: filePath, content: 'Nested content' });
      const content = await readFileTool.execute({ path: filePath });
      expect(content).toBe('Nested content');
    });
  });

  describe('list_dir & mkdir', () => {
    it('creates a directory and lists contents', async () => {
      const subDir = path.join(tmpDir, 'mydir');
      await mkdirTool.execute({ path: subDir });
      await writeFileTool.execute({ path: path.join(subDir, 'file1.txt'), content: 'abc' });

      const list = await listDirTool.execute({ path: tmpDir });
      expect(list).toContain('[DIR] mydir');

      const subList = await listDirTool.execute({ path: subDir });
      expect(subList).toContain('[FILE] file1.txt');
    });
  });

  describe('edit_file 2.0', () => {
    it('replaces a unique target block accurately', async () => {
      const filePath = path.join(tmpDir, 'code.ts');
      const initial = 'const a = 1;\nconst b = 2;\nconst c = 3;\n';
      await writeFileTool.execute({ path: filePath, content: initial });

      const res = await editFileTool.execute({
        path: filePath,
        target: 'const b = 2;',
        replacement: 'const b = 42;'
      });
      const parsed = JSON.parse(res);
      expect(parsed.status).toBe('success');

      const updated = await readFileTool.execute({ path: filePath });
      expect(updated).toBe('const a = 1;\nconst b = 42;\nconst c = 3;\n');
    });

    it('throws when target text is not found', async () => {
      const filePath = path.join(tmpDir, 'code.ts');
      await writeFileTool.execute({ path: filePath, content: 'const a = 1;\n' });

      await expect(
        editFileTool.execute({
          path: filePath,
          target: 'const nonExistent = 99;',
          replacement: 'const b = 2;'
        })
      ).rejects.toThrow('Target text to replace was not found');
    });

    it('throws with line numbers when multiple matches occur without replaceAll', async () => {
      const filePath = path.join(tmpDir, 'duplicate.txt');
      const content = [
        'item: value',   // Line 1
        'other line',    // Line 2
        'item: value',   // Line 3
        'footer'         // Line 4
      ].join('\n');
      await writeFileTool.execute({ path: filePath, content });

      await expect(
        editFileTool.execute({
          path: filePath,
          target: 'item: value',
          replacement: 'item: new_value'
        })
      ).rejects.toThrow(/matches 2 occurrences.*lines: \[1, 3\]/);
    });

    it('replaces all occurrences when replaceAll is true or string "true"', async () => {
      const filePath = path.join(tmpDir, 'duplicate.txt');
      const content = 'item: value\nother line\nitem: value\nfooter';
      await writeFileTool.execute({ path: filePath, content });

      const res = await editFileTool.execute({
        path: filePath,
        target: 'item: value',
        replacement: 'item: updated',
        replaceAll: 'true' as any
      });
      const parsed = JSON.parse(res);
      expect(parsed.status).toBe('success');

      const updated = await readFileTool.execute({ path: filePath });
      expect(updated).toBe('item: updated\nother line\nitem: updated\nfooter');
    });

    it('rejects duplicate match when replaceAll is string "false"', async () => {
      const filePath = path.join(tmpDir, 'duplicate_false.txt');
      const content = 'item: value\nother line\nitem: value\nfooter';
      await writeFileTool.execute({ path: filePath, content });

      await expect(
        editFileTool.execute({
          path: filePath,
          target: 'item: value',
          replacement: 'item: updated',
          replaceAll: 'false' as any
        })
      ).rejects.toThrow(/matches 2 occurrences/);
    });

    it('preserves CRLF line endings when editing', async () => {
      const filePath = path.join(tmpDir, 'crlf.txt');
      const content = 'line1\r\nline2\r\nline3\r\n';
      await fs.writeFile(filePath, content, 'utf8');

      await editFileTool.execute({
        path: filePath,
        target: 'line2',
        replacement: 'line2_edited'
      });

      const updated = await fs.readFile(filePath, 'utf8');
      expect(updated).toBe('line1\r\nline2_edited\r\nline3\r\n');
    });
  });
});
