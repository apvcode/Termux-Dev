import fs from 'fs/promises';
import fsSync from 'fs';
import path from 'path';
const MEMORY_DIR = path.join(process.cwd(), '.devx');
const MEMORY_FILE = path.join(MEMORY_DIR, 'memory.md');
export class MemoryManager {
    static async loadMemory() {
        if (!fsSync.existsSync(MEMORY_FILE)) {
            return '';
        }
        try {
            return await fs.readFile(MEMORY_FILE, 'utf8');
        }
        catch {
            return '';
        }
    }
    static async addFact(fact) {
        if (!fsSync.existsSync(MEMORY_DIR)) {
            await fs.mkdir(MEMORY_DIR, { recursive: true });
        }
        let current = '';
        if (fsSync.existsSync(MEMORY_FILE)) {
            current = await fs.readFile(MEMORY_FILE, 'utf8');
        }
        const trimmedFact = fact.trim();
        if (!trimmedFact)
            return;
        if (!current.includes(trimmedFact)) {
            const entry = `• ${trimmedFact}\n`;
            if (!current) {
                current = `# Project Memory Bank\n\n## Established Rules & Architecture\n`;
            }
            current += entry;
            await fs.writeFile(MEMORY_FILE, current, 'utf8');
        }
    }
    static async clearMemory() {
        if (fsSync.existsSync(MEMORY_FILE)) {
            await fs.unlink(MEMORY_FILE);
        }
    }
    static isMemoryPresent() {
        return fsSync.existsSync(MEMORY_FILE);
    }
}
export const saveMemoryTool = {
    name: 'save_memory',
    definition: {
        name: 'save_memory',
        description: 'Save an important project fact, architectural decision, user preference, or coding guideline into long-term project memory (.devx/memory.md) so you will remember it in future sessions.',
        parameters: {
            type: 'object',
            properties: {
                fact: { type: 'string', description: 'The concise fact, decision, or preference to remember' }
            },
            required: ['fact']
        }
    },
    validateArgs(args) {
        if (!args.fact || typeof args.fact !== 'string')
            throw new Error('fact is required');
    },
    async execute(args) {
        await MemoryManager.addFact(args.fact);
        return `Saved to project memory: "${args.fact}"`;
    }
};
