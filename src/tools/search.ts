import fs from 'fs/promises';
import path from 'path';
import { Tool } from '../core/types.js';

const IGNORED_DIRS = new Set([
  'node_modules',
  '.git',
  '.next',
  'dist',
  'build',
  'coverage',
  '.turbo',
  '.cache',
  'vendor'
]);

async function searchFiles(
  dir: string,
  query: string,
  results: string[],
  maxResults = 50
): Promise<void> {
  if (results.length >= maxResults) return;

  let entries;
  try {
    entries = await fs.readdir(dir, { withFileTypes: true });
  } catch {
    return;
  }

  for (const entry of entries) {
    if (results.length >= maxResults) break;

    const fullPath = path.join(dir, entry.name);
    const relPath = path.relative(process.cwd(), fullPath) || fullPath;

    if (entry.isDirectory()) {
      if (!IGNORED_DIRS.has(entry.name) && !entry.name.startsWith('.')) {
        await searchFiles(fullPath, query, results, maxResults);
      }
    } else if (entry.isFile()) {
      // Skip large binary extensions
      const ext = path.extname(entry.name).toLowerCase();
      if (['.png', '.jpg', '.jpeg', '.gif', '.ico', '.pdf', '.zip', '.tar', '.gz', '.exe', '.dll', '.so', '.wasm'].includes(ext)) {
        continue;
      }

      try {
        const content = await fs.readFile(fullPath, 'utf8');
        if (content.toLowerCase().includes(query.toLowerCase())) {
          const lines = content.split('\n');
          for (let i = 0; i < lines.length; i++) {
            if (results.length >= maxResults) break;
            if (lines[i].toLowerCase().includes(query.toLowerCase())) {
              const preview = lines[i].trim();
              results.push(`${relPath}:${i + 1}: ${preview}`);
            }
          }
        }
      } catch {}
    }
  }
}

export const searchTool: Tool = {
  name: 'search',
  definition: {
    name: 'search',
    description: 'Search for text or patterns in project files (grep-like)',
    parameters: {
      type: 'object',
      properties: {
        query: { type: 'string', description: 'Text or substring to search for' },
        dir: { type: 'string', description: 'Directory to search in (default: .)' }
      },
      required: ['query']
    }
  },
  validateArgs(args: any) {
    if (!args.query || typeof args.query !== 'string') throw new Error('query is required');
  },
  async execute(args: any) {
    const targetDir = args.dir || args.path || '.';
    const results: string[] = [];

    try {
      await searchFiles(targetDir, args.query, results, 50);

      if (results.length === 0) {
        return `No matches found for "${args.query}" in ${targetDir}.`;
      }

      return results.join('\n');
    } catch (err: any) {
      return `Failed to search: ${err.message}`;
    }
  }
};
