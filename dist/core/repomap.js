import fs from 'fs/promises';
import path from 'path';
const IGNORED_DIRS = new Set([
    '.git', 'node_modules', 'dist', 'build', 'out', 'coverage',
    '.next', '.nuxt', '.cache', '.gemini', '.antigravity', '.vscode', '.idea'
]);
const EXTENSIONS = new Set(['.ts', '.tsx', '.js', '.jsx', '.py', '.mjs', '.cjs', '.rs', '.go', '.c', '.cpp', '.h', '.java']);
export class RepoMapGenerator {
    /**
     * Generates a compact, compressed tree representation of project files and key symbols
     */
    static async generate(rootDir = process.cwd(), maxFiles = 60) {
        try {
            const files = await this.scanFiles(rootDir, maxFiles);
            if (files.length === 0)
                return '';
            const lines = ['Project Structure & Key Symbols (Repo Map):'];
            for (const file of files) {
                const symbolStr = file.symbols.length > 0 ? ` (${file.symbols.slice(0, 5).join(', ')}${file.symbols.length > 5 ? ', ...' : ''})` : '';
                lines.push(`• ${file.relPath}${symbolStr}`);
            }
            return lines.join('\n');
        }
        catch {
            return '';
        }
    }
    static async scanFiles(dir, maxFiles) {
        const results = [];
        async function walk(currentDir) {
            if (results.length >= maxFiles)
                return;
            try {
                const entries = await fs.readdir(currentDir, { withFileTypes: true });
                for (const entry of entries) {
                    if (results.length >= maxFiles)
                        break;
                    if (entry.isDirectory()) {
                        if (!IGNORED_DIRS.has(entry.name) && !entry.name.startsWith('.')) {
                            await walk(path.join(currentDir, entry.name));
                        }
                    }
                    else if (entry.isFile()) {
                        const ext = path.extname(entry.name).toLowerCase();
                        if (EXTENSIONS.has(ext)) {
                            const fullPath = path.join(currentDir, entry.name);
                            const relPath = path.relative(dir, fullPath).replace(/\\/g, '/');
                            const symbols = await RepoMapGenerator.extractSymbols(fullPath, ext);
                            results.push({ relPath, symbols });
                        }
                    }
                }
            }
            catch { }
        }
        await walk(dir);
        return results;
    }
    static async extractSymbols(filePath, ext) {
        const symbols = [];
        try {
            // Read at most 16KB of each file for super-fast symbol extraction
            const stat = await fs.stat(filePath);
            if (stat.size > 256 * 1024)
                return []; // Skip giant generated files
            const content = await fs.readFile(filePath, 'utf8');
            const lines = content.split('\n').slice(0, 300); // Only examine top 300 lines
            for (const line of lines) {
                const trimmed = line.trim();
                if (ext === '.ts' || ext === '.tsx' || ext === '.js' || ext === '.jsx' || ext === '.mjs') {
                    // JS/TS exports: function, class, interface, type, const
                    const fnMatch = trimmed.match(/^export\s+(?:async\s+)?function\s+([a-zA-Z0-9_$]+)/);
                    if (fnMatch) {
                        symbols.push(`fn ${fnMatch[1]}`);
                        continue;
                    }
                    const classMatch = trimmed.match(/^export\s+(?:abstract\s+)?class\s+([a-zA-Z0-9_$]+)/);
                    if (classMatch) {
                        symbols.push(`class ${classMatch[1]}`);
                        continue;
                    }
                    const ifaceMatch = trimmed.match(/^export\s+interface\s+([a-zA-Z0-9_$]+)/);
                    if (ifaceMatch) {
                        symbols.push(`interface ${ifaceMatch[1]}`);
                        continue;
                    }
                    const typeMatch = trimmed.match(/^export\s+type\s+([a-zA-Z0-9_$]+)/);
                    if (typeMatch) {
                        symbols.push(`type ${typeMatch[1]}`);
                        continue;
                    }
                    const constMatch = trimmed.match(/^export\s+const\s+([a-zA-Z0-9_$]+)/);
                    if (constMatch && !constMatch[1].startsWith('_')) {
                        symbols.push(constMatch[1]);
                        continue;
                    }
                }
                else if (ext === '.py') {
                    // Python classes and functions
                    const pyClass = trimmed.match(/^class\s+([a-zA-Z0-9_]+)/);
                    if (pyClass) {
                        symbols.push(`class ${pyClass[1]}`);
                        continue;
                    }
                    const pyFn = trimmed.match(/^(?:async\s+)?def\s+([a-zA-Z0-9_]+)/);
                    if (pyFn && !pyFn[1].startsWith('__')) {
                        symbols.push(`def ${pyFn[1]}`);
                        continue;
                    }
                }
                else if (ext === '.rs') {
                    const rsFn = trimmed.match(/^pub\s+(?:async\s+)?fn\s+([a-zA-Z0-9_]+)/);
                    if (rsFn) {
                        symbols.push(`fn ${rsFn[1]}`);
                        continue;
                    }
                    const rsStruct = trimmed.match(/^pub\s+struct\s+([a-zA-Z0-9_]+)/);
                    if (rsStruct) {
                        symbols.push(`struct ${rsStruct[1]}`);
                        continue;
                    }
                }
                else if (ext === '.go') {
                    const goFn = trimmed.match(/^func\s+(?:\([^\)]+\)\s+)?([a-zA-Z0-9_]+)/);
                    if (goFn) {
                        symbols.push(`func ${goFn[1]}`);
                        continue;
                    }
                }
            }
        }
        catch { }
        return symbols.slice(0, 8);
    }
}
