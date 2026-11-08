import fs from 'fs/promises';
import { PromptHistoryNavigator, historyFile } from './history_search.js';

/**
 * The lines the user sent (messages and commands), oldest first. One list for the prompt and for the line
 * typed while the agent works, and it is kept in a file: the up and down arrows bring back what was typed in
 * earlier chats and earlier sessions too.
 */
const lines: string[] = [];
let loading: Promise<void> | null = null;
/** The writes to the file go one after another: the lines stay in the order they were sent. */
let writing: Promise<void> = Promise.resolve();

const MAX_LINES = 500;
/** A huge paste is sent but not kept: the file is for lines a person would want to bring back. */
const MAX_KEPT_CHARS = 4000;

/** Reads the file once. The lines typed before it was read stay at the end. */
export function loadPromptHistory(): Promise<void> {
  if (!loading) {
    loading = PromptHistoryNavigator.loadPersistent().then((persisted) => {
      const typedMeanwhile = lines.splice(0, lines.length);
      for (const line of [...persisted, ...typedMeanwhile]) {
        if (lines[lines.length - 1] !== line) lines.push(line);
      }
      if (lines.length > MAX_LINES) lines.splice(0, lines.length - MAX_LINES);
    });
  }
  return loading;
}

/** The history as it is now: the array itself, not a copy (the prompt and the line under the output share it). */
export function promptHistory(): string[] {
  return lines;
}

/** Remembers a sent line (not twice in a row) and appends it to the file. */
export function recordPrompt(text: string): void {
  const line = text.trim();
  if (!line) return;
  if (lines[lines.length - 1] === line) return;
  lines.push(line);
  if (lines.length > MAX_LINES) lines.shift();
  if (line.length <= MAX_KEPT_CHARS) writing = writing.then(() => PromptHistoryNavigator.appendPersistent(line)).catch(() => undefined);
}

/** Resolves when every line sent so far is in the file. */
export function flushPromptHistory(): Promise<void> {
  return writing;
}

/** The file keeps the last 500 lines at most: it is cut when devx starts (not on every line). */
export async function trimPromptHistoryFile(): Promise<void> {
  try {
    const raw = await fs.readFile(historyFile(), 'utf8');
    const all = raw.split('\n').filter(Boolean);
    if (all.length > MAX_LINES * 2) await fs.writeFile(historyFile(), all.slice(-MAX_LINES).join('\n') + '\n', 'utf8');
  } catch {}
}

/** For the tests: forget everything that is in memory. */
export function resetPromptHistory(): void {
  lines.length = 0;
  loading = null;
  writing = Promise.resolve();
}
