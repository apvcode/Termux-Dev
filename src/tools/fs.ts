import fs from 'fs/promises';
import path from 'path';
import pc from 'picocolors';
import { Tool } from '../core/types.js';
import { globalSnapshotManager } from '../core/snapshot.js';

// A single read result lands in the model context, so it must stay bounded.
export const MAX_READ_CHARS = 60000;
const MAX_READ_FILE_BYTES = 10 * 1024 * 1024;

function toLineNumber(value: unknown, name: string): number | undefined {
  if (value === undefined || value === null || value === '') return undefined;
  const n = typeof value === 'string' ? Number(value) : (value as number);
  if (typeof n !== 'number' || !Number.isInteger(n) || n < 1) {
    throw new Error(`${name} must be a positive integer`);
  }
  return n;
}

export const readFileTool: Tool = {
  name: 'read_file',
  definition: {
    name: 'read_file',
    description:
      `Read contents of a file. Large files are returned in parts (max ~${MAX_READ_CHARS} characters per call): ` +
      'use startLine/endLine (1-based, inclusive) to read a specific range or to continue reading.',
    parameters: {
      type: 'object',
      properties: {
        path: { type: 'string' },
        startLine: { type: 'number', description: 'First line to read (1-based, optional)' },
        endLine: { type: 'number', description: 'Last line to read (inclusive, optional)' }
      },
      required: ['path']
    }
  },
  validateArgs(args: any) {
    if (!args.path || typeof args.path !== 'string') throw new Error('path is required');
    const start = toLineNumber(args.startLine, 'startLine');
    const end = toLineNumber(args.endLine, 'endLine');
    if (start !== undefined && end !== undefined && end < start) {
      throw new Error('endLine must be greater than or equal to startLine');
    }
  },
  async execute(args: any) {
    try {
      const stat = await fs.stat(args.path);
      if (stat.isDirectory()) {
        throw new Error('Path is a directory, use list_dir instead');
      }
      if (stat.size > MAX_READ_FILE_BYTES) {
        throw new Error(
          `File is too large (${(stat.size / 1024 / 1024).toFixed(1)} MB). Use the search tool, or bash (e.g. head/tail/sed -n) to look at part of it`
        );
      }

      const buffer = await fs.readFile(args.path);
      if (buffer.subarray(0, 8000).includes(0)) {
        return `[Binary file ${args.path} (${stat.size} bytes) - contents not shown]`;
      }

      const text = buffer.toString('utf8');
      const startArg = toLineNumber(args.startLine, 'startLine');
      const endArg = toLineNumber(args.endLine, 'endLine');

      // Small files without a range: return the exact content.
      if (startArg === undefined && endArg === undefined && text.length <= MAX_READ_CHARS) {
        return text;
      }

      const lines = text.split('\n');
      const total = lines.length;
      const start = Math.min(startArg ?? 1, total);
      const end = Math.min(Math.max(endArg ?? total, start), total);

      let selected = lines.slice(start - 1, end);
      let shownEnd = end;
      let joined = selected.join('\n');
      let truncated = false;

      if (joined.length > MAX_READ_CHARS) {
        truncated = true;
        // Cut on a line boundary so the model can continue exactly where we stopped.
        let size = 0;
        let count = 0;
        for (const line of selected) {
          if (size + line.length + 1 > MAX_READ_CHARS && count > 0) break;
          size += line.length + 1;
          count++;
        }
        selected = selected.slice(0, count);
        shownEnd = start + count - 1;
        joined = selected.join('\n');
        if (joined.length > MAX_READ_CHARS) {
          // A single enormous line (e.g. minified bundle).
          joined = joined.slice(0, MAX_READ_CHARS);
        }
      }

      const note = truncated
        ? ` - output truncated, continue with startLine=${shownEnd + 1}`
        : shownEnd < total ? ` - file continues, use startLine=${shownEnd + 1} to read more` : '';
      return `[Lines ${start}-${shownEnd} of ${total}${note}]\n${joined}`;
    } catch (err: any) {
      throw new Error(`Failed to read file: ${err.message}`);
    }
  }
};

export const writeFileTool: Tool = {
  name: 'write_file',
  definition: {
    name: 'write_file',
    description: 'Write content to a file (creates new file or overwrites existing file)',
    parameters: {
      type: 'object',
      properties: {
        path: { type: 'string' },
        content: { type: 'string' }
      },
      required: ['path', 'content']
    }
  },
  validateArgs(args: any) {
    if (!args.path || typeof args.path !== 'string') throw new Error('path is required');
    if (typeof args.content !== 'string') throw new Error('content is required');
  },
  async execute(args: any) {
    try {
      await globalSnapshotManager.recordFileBeforeChange(args.path);

      const parentDir = path.dirname(args.path);
      if (parentDir && parentDir !== '.' && parentDir !== '/') {
        await fs.mkdir(parentDir, { recursive: true });
      }

      let oldLines = 0;
      let existed = false;
      try {
        const oldContent = await fs.readFile(args.path, 'utf8');
        oldLines = oldContent.split('\n').length;
        existed = true;
      } catch {}

      await fs.writeFile(args.path, args.content, 'utf8');
      const contentLines = args.content.split('\n');
      const newLines = contentLines.length;

      const diffLines: string[] = [];
      const showLines = Math.min(contentLines.length, 6);
      for (let i = 0; i < showLines; i++) {
        diffLines.push(`${i + 1} +  ${contentLines[i]}`);
      }

      if (!existed) {
        return JSON.stringify({
          status: 'success',
          action: 'edit',
          path: args.path,
          addedCount: newLines,
          diffLines,
          summary: `Successfully created ${args.path} (+${newLines} lines)`
        });
      } else {
        const diff = newLines - oldLines;
        const diffStr = diff >= 0 ? `+${diff}` : `${diff}`;
        return JSON.stringify({
          status: 'success',
          action: 'edit',
          path: args.path,
          addedCount: newLines,
          diffLines,
          summary: `Successfully updated ${args.path} (${diffStr} lines, ${newLines} total)`
        });
      }
    } catch (err: any) {
      throw new Error(`Failed to write file: ${err.message}`);
    }
  }
};

export const editFileTool: Tool = {
  name: 'edit_file',
  definition: {
    name: 'edit_file',
    description: 'Edit a specific block of text in an existing file by replacing old text with new text',
    parameters: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'Path to the file to edit' },
        target: { type: 'string', description: 'Exact string or block of code to replace' },
        replacement: { type: 'string', description: 'New string or block of code to replace with' },
        replaceAll: { type: 'boolean', description: 'If true, replaces all occurrences of target in the file. Default is false.' }
      },
      required: ['path', 'target', 'replacement']
    }
  },
  validateArgs(args: any) {
    if (!args.path || typeof args.path !== 'string') throw new Error('path is required');
    if (typeof args.target !== 'string' || args.target === '') {
      throw new Error('target is required and must not be empty (use write_file to create or overwrite a whole file)');
    }
    if (typeof args.replacement !== 'string') throw new Error('replacement is required');
  },
  async execute(args: any) {
    if (typeof args.target !== 'string' || args.target === '') {
      throw new Error('Failed to edit file: target must not be empty');
    }
    try {
      await globalSnapshotManager.recordFileBeforeChange(args.path);

      const oldContent = await fs.readFile(args.path, 'utf8');
      let target = args.target;
      let content = oldContent;

      if (!content.includes(target)) {
        const normalizedContent = content.replace(/\r\n/g, '\n');
        const normalizedTarget = target.replace(/\r\n/g, '\n');
        if (normalizedContent.includes(normalizedTarget)) {
          content = normalizedContent;
          target = normalizedTarget;
        } else {
          throw new Error(`Target text to replace was not found in ${args.path}. Ensure exact indentation and characters match.`);
        }
      }

      // Find all matching occurrences
      const matches: number[] = [];
      let searchPos = 0;
      while (searchPos < content.length) {
        const idx = content.indexOf(target, searchPos);
        if (idx === -1) break;
        matches.push(idx);
        searchPos = idx + Math.max(1, target.length);
      }

      const isReplaceAll = args.replaceAll === true || args.replaceAll === 'true';

      if (matches.length > 1 && !isReplaceAll) {
        const lineNumbers = matches.map(idx => content.slice(0, idx).split(/\r?\n/).length);
        throw new Error(
          `Target text matches ${matches.length} occurrences in ${args.path} at lines: [${lineNumbers.join(', ')}]. ` +
          `Please provide more surrounding lines/context to make the target block unique, or set replaceAll: true to replace all instances.`
        );
      }

      const hasCRLF = oldContent.includes('\r\n');
      const removedArr = target.split(/\r?\n/);
      const addedArr = args.replacement.split(/\r?\n/);

      if (isReplaceAll && matches.length > 1) {
        let newContent = content.split(target).join(args.replacement);
        if (hasCRLF) {
          newContent = newContent.replace(/\r?\n/g, '\r\n');
        } else {
          newContent = newContent.replace(/\r\n/g, '\n');
        }
        await fs.writeFile(args.path, newContent, 'utf8');

        return JSON.stringify({
          status: 'success',
          action: 'edit',
          path: args.path,
          occurrencesReplaced: matches.length,
          summary: `Successfully replaced all ${matches.length} occurrences in ${args.path}`
        });
      }

      const targetIndex = matches[0];
      const allLines = content.split(/\r?\n/);
      const startLine = content.slice(0, targetIndex).split(/\r?\n/).length;
      const endLine = startLine + removedArr.length - 1;

      const contextBefore = allLines.slice(Math.max(0, startLine - 3), startLine - 1);
      const contextAfter = allLines.slice(endLine, Math.min(allLines.length, endLine + 2));

      const diffLines: string[] = [];
      let lineCounter = startLine - contextBefore.length;
      
      contextBefore.forEach((l: string) => diffLines.push(`${lineCounter++}    ${l}`));
      removedArr.forEach((l: string) => diffLines.push(`${lineCounter++} -  ${l}`));
      
      let addCounter = startLine;
      addedArr.forEach((l: string) => diffLines.push(`${addCounter++} +  ${l}`));
      contextAfter.forEach((l: string) => diffLines.push(`${addCounter++}    ${l}`));

      let newContent = content.slice(0, targetIndex) + args.replacement + content.slice(targetIndex + target.length);
      
      if (hasCRLF) {
        newContent = newContent.replace(/\r?\n/g, '\r\n');
      } else {
        newContent = newContent.replace(/\r\n/g, '\n');
      }
      
      await fs.writeFile(args.path, newContent, 'utf8');

      return JSON.stringify({
        status: 'success',
        action: 'edit',
        path: args.path,
        startLine,
        removedCount: removedArr.length,
        addedCount: addedArr.length,
        diffLines,
        summary: `Successfully edited ${args.path} (+${addedArr.length} -${removedArr.length} lines)`
      });
    } catch (err: any) {
      throw new Error(`Failed to edit file: ${err.message}`);
    }
  }
};

export const listDirTool: Tool = {
  name: 'list_dir',
  definition: {
    name: 'list_dir',
    description: 'List contents of a directory',
    parameters: {
      type: 'object',
      properties: {
        path: { type: 'string' }
      },
      required: ['path']
    }
  },
  validateArgs(args: any) {
    if (!args.path || typeof args.path !== 'string') throw new Error('path is required');
  },
    async execute(args: any) {
    try {
      const files = await fs.readdir(args.path, { withFileTypes: true });
      const ignores = ['node_modules', '.git', '.next', '.cache', 'dist'];
      const filtered = files.filter(f => !ignores.includes(f.name));
      return filtered.map(f => `${f.isDirectory() ? '[DIR]' : '[FILE]'} ${f.name}`).join('\n');
    } catch (err: any) {
      throw new Error(`Failed to list directory: ${err.message}`);
    }
  }
};

export const mkdirTool: Tool = {
  name: 'mkdir',
  definition: {
    name: 'mkdir',
    description: 'Create a directory',
    parameters: {
      type: 'object',
      properties: {
        path: { type: 'string' }
      },
      required: ['path']
    }
  },
  validateArgs(args: any) {
    if (!args.path || typeof args.path !== 'string') throw new Error('path is required');
  },
  async execute(args: any) {
    try {
      await fs.mkdir(args.path, { recursive: true });
      return `Created directory ${args.path}`;
    } catch (err: any) {
      throw new Error(`Failed to create directory: ${err.message}`);
    }
  }
};
