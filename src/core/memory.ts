import fs from 'fs/promises';
import fsSync from 'fs';
import path from 'path';
import { Tool } from './types.js';

// Looked up when used, not when the module loads: the memory belongs to the folder devx works in.
const memoryDir = () => path.join(process.cwd(), '.devx');
const memoryFile = () => path.join(memoryDir(), 'memory.md');

export const MEMORY_PROMPT_MAX_CHARS = 4000;

/**
 * The memory for the system prompt: all of it when it is small, else the newest facts that fit
 * (facts are appended, so the end is the newest) and a line that says how many were left out.
 */
export function memoryForPrompt(text: string, max: number = MEMORY_PROMPT_MAX_CHARS): { text: string; omitted: number } {
  if (text.length <= max) return { text, omitted: 0 };
  const lines = text.split('\n');
  const firstFact = lines.findIndex((l) => l.startsWith('•'));
  const header = firstFact === -1 ? [] : lines.slice(0, firstFact);
  const facts = (firstFact === -1 ? lines : lines.slice(firstFact)).filter((l) => l.trim().length > 0);
  const note = (n: number) => `(${n} older facts left out: /memory shows all of them)`;
  const kept: string[] = [];
  let used = header.join('\n').length + note(facts.length).length + 2;
  for (let i = facts.length - 1; i >= 0; i--) {
    if (used + facts[i].length + 1 > max && kept.length > 0) break;
    kept.unshift(facts[i]);
    used += facts[i].length + 1;
  }
  const omitted = facts.length - kept.length;
  return { text: [...header, ...(omitted > 0 ? [note(omitted)] : []), ...kept].join('\n'), omitted };
}

export class MemoryManager {
  /** The `Project memory` switch of the settings: off means the bank is not put into the prompt. */
  public static enabled = true;

  public static async loadMemory(): Promise<string> {
    if (!fsSync.existsSync(memoryFile())) {
      return '';
    }
    try {
      return await fs.readFile(memoryFile(), 'utf8');
    } catch {
      return '';
    }
  }

  public static async addFact(fact: string): Promise<void> {
    if (!fsSync.existsSync(memoryDir())) {
      await fs.mkdir(memoryDir(), { recursive: true });
    }

    let current = '';
    if (fsSync.existsSync(memoryFile())) {
      current = await fs.readFile(memoryFile(), 'utf8');
    }

    const trimmedFact = fact.trim();
    if (!trimmedFact) return;

    if (!current.includes(trimmedFact)) {
      const entry = `• ${trimmedFact}\n`;
      if (!current) {
        current = `# Project Memory Bank\n\n## Established Rules & Architecture\n`;
      }
      current += entry;
      await fs.writeFile(memoryFile(), current, 'utf8');
    }
  }

  public static async clearMemory(): Promise<void> {
    if (fsSync.existsSync(memoryFile())) {
      await fs.unlink(memoryFile());
    }
  }

  public static isMemoryPresent(): boolean {
    return fsSync.existsSync(memoryFile());
  }
}

export const saveMemoryTool: Tool = {
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
  validateArgs(args: any) {
    if (!args.fact || typeof args.fact !== 'string') throw new Error('fact is required');
  },
  async execute(args: { fact: string }) {
    await MemoryManager.addFact(args.fact);
    return `Saved to project memory: "${args.fact}"`;
  }
};
