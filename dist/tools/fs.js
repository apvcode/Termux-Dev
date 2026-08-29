import fs from 'fs/promises';
import path from 'path';
import { globalSnapshotManager } from '../core/snapshot.js';
export const readFileTool = {
    name: 'read_file',
    definition: {
        name: 'read_file',
        description: 'Read contents of a file',
        parameters: {
            type: 'object',
            properties: {
                path: { type: 'string' }
            },
            required: ['path']
        }
    },
    validateArgs(args) {
        if (!args.path || typeof args.path !== 'string')
            throw new Error('path is required');
    },
    async execute(args) {
        try {
            return await fs.readFile(args.path, 'utf8');
        }
        catch (err) {
            throw new Error(`Failed to read file: ${err.message}`);
        }
    }
};
export const writeFileTool = {
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
    validateArgs(args) {
        if (!args.path || typeof args.path !== 'string')
            throw new Error('path is required');
        if (typeof args.content !== 'string')
            throw new Error('content is required');
    },
    async execute(args) {
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
            }
            catch { }
            await fs.writeFile(args.path, args.content, 'utf8');
            const contentLines = args.content.split('\n');
            const newLines = contentLines.length;
            const diffLines = [];
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
            }
            else {
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
        }
        catch (err) {
            throw new Error(`Failed to write file: ${err.message}`);
        }
    }
};
export const editFileTool = {
    name: 'edit_file',
    definition: {
        name: 'edit_file',
        description: 'Edit a specific block of text in an existing file by replacing old text with new text',
        parameters: {
            type: 'object',
            properties: {
                path: { type: 'string', description: 'Path to the file to edit' },
                target: { type: 'string', description: 'Exact string or block of code to replace' },
                replacement: { type: 'string', description: 'New string or block of code to replace with' }
            },
            required: ['path', 'target', 'replacement']
        }
    },
    validateArgs(args) {
        if (!args.path || typeof args.path !== 'string')
            throw new Error('path is required');
        if (typeof args.target !== 'string')
            throw new Error('target is required');
        if (typeof args.replacement !== 'string')
            throw new Error('replacement is required');
    },
    async execute(args) {
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
                }
                else {
                    throw new Error(`Target text to replace was not found in ${args.path}. Ensure exact indentation and characters match.`);
                }
            }
            const targetIndex = content.indexOf(target);
            const allLines = content.split('\n');
            const startLine = content.slice(0, targetIndex).split('\n').length;
            const removedArr = target.split('\n');
            const endLine = startLine + removedArr.length - 1;
            const contextBefore = allLines.slice(Math.max(0, startLine - 3), startLine - 1);
            const contextAfter = allLines.slice(endLine, Math.min(allLines.length, endLine + 2));
            const addedArr = args.replacement.split('\n');
            const diffLines = [];
            let lineCounter = startLine - contextBefore.length;
            contextBefore.forEach((l) => diffLines.push(`${lineCounter++}    ${l}`));
            removedArr.forEach((l) => diffLines.push(`${lineCounter++} -  ${l}`));
            let addCounter = startLine;
            addedArr.forEach((l) => diffLines.push(`${addCounter++} +  ${l}`));
            contextAfter.forEach((l) => diffLines.push(`${addCounter++}    ${l}`));
            const hasCRLF = oldContent.includes('\r\n');
            let newContent = content.slice(0, targetIndex) + args.replacement + content.slice(targetIndex + target.length);
            if (hasCRLF) {
                newContent = newContent.replace(/\r?\n/g, '\r\n');
            }
            else {
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
        }
        catch (err) {
            throw new Error(`Failed to edit file: ${err.message}`);
        }
    }
};
export const listDirTool = {
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
    validateArgs(args) {
        if (!args.path || typeof args.path !== 'string')
            throw new Error('path is required');
    },
    async execute(args) {
        try {
            const files = await fs.readdir(args.path, { withFileTypes: true });
            const ignores = ['node_modules', '.git', '.next', '.cache', 'dist'];
            const filtered = files.filter(f => !ignores.includes(f.name));
            return filtered.map(f => `${f.isDirectory() ? '[DIR]' : '[FILE]'} ${f.name}`).join('\n');
        }
        catch (err) {
            throw new Error(`Failed to list directory: ${err.message}`);
        }
    }
};
export const mkdirTool = {
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
    validateArgs(args) {
        if (!args.path || typeof args.path !== 'string')
            throw new Error('path is required');
    },
    async execute(args) {
        try {
            await fs.mkdir(args.path, { recursive: true });
            return `Created directory ${args.path}`;
        }
        catch (err) {
            throw new Error(`Failed to create directory: ${err.message}`);
        }
    }
};
