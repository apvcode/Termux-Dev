import fs from 'fs/promises';
import fsSync from 'fs';
import path from 'path';
import os from 'os';

export const HISTORY_FILE = path.join(os.homedir(), '.devx', 'history.jsonl');
const MAX_HISTORY_LINES = 500;

export class PromptHistoryNavigator {
  private history: string[] = [];
  private cursor: number = -1;
  private savedDraft: string = '';
  private filterQuery: string = '';
  private fuzzy: boolean = false;

  constructor(initialHistory: string[] = [], fuzzy = false) {
    this.history = [...initialHistory];
    this.cursor = this.history.length;
    this.fuzzy = fuzzy;
  }

  public static async loadPersistent(): Promise<string[]> {
    try {
      if (!fsSync.existsSync(HISTORY_FILE)) {
        return [];
      }
      const raw = await fs.readFile(HISTORY_FILE, 'utf8');
      const lines = raw
        .split('\n')
        .map((l) => l.trim())
        .filter(Boolean)
        .map((l) => {
          try {
            const parsed = JSON.parse(l);
            return parsed.prompt || parsed.text || l;
          } catch {
            return l;
          }
        });
      return lines.slice(-MAX_HISTORY_LINES);
    } catch {
      return [];
    }
  }

  public static async appendPersistent(prompt: string): Promise<void> {
    try {
      const dir = path.dirname(HISTORY_FILE);
      if (!fsSync.existsSync(dir)) {
        await fs.mkdir(dir, { recursive: true });
      }
      const entry = JSON.stringify({ prompt, timestamp: Date.now() }) + '\n';
      await fs.appendFile(HISTORY_FILE, entry, 'utf8');
    } catch {}
  }

  public add(entry: string): void {
    const trimmed = entry.trim();
    if (!trimmed) return;
    if (this.history.length > 0 && this.history[this.history.length - 1] === trimmed) {
      return;
    }
    this.history.push(trimmed);
    if (this.history.length > MAX_HISTORY_LINES) {
      this.history.shift();
    }
    this.cursor = this.history.length;
    this.filterQuery = '';
    PromptHistoryNavigator.appendPersistent(trimmed).catch(() => {});
  }

  public getHistory(): string[] {
    return [...this.history];
  }

  public navigateUp(currentText: string): string | null {
    if (this.history.length === 0) return null;

    if (this.cursor === this.history.length) {
      this.savedDraft = currentText;
      this.filterQuery = currentText.toLowerCase().trim();
    }

    let nextCursor = this.cursor - 1;
    while (nextCursor >= 0) {
      const item = this.history[nextCursor];
      if (!this.fuzzy || !this.filterQuery || item.toLowerCase().includes(this.filterQuery)) {
        this.cursor = nextCursor;
        return item;
      }
      nextCursor--;
    }

    return null;
  }

  public navigateDown(): string | null {
    if (this.cursor >= this.history.length) return null;

    let nextCursor = this.cursor + 1;
    while (nextCursor < this.history.length) {
      const item = this.history[nextCursor];
      if (!this.fuzzy || !this.filterQuery || item.toLowerCase().includes(this.filterQuery)) {
        this.cursor = nextCursor;
        return item;
      }
      nextCursor++;
    }

    // Reached bottom, restore draft
    this.cursor = this.history.length;
    return this.savedDraft;
  }

  public reset(): void {
    this.cursor = this.history.length;
    this.savedDraft = '';
    this.filterQuery = '';
  }
}
