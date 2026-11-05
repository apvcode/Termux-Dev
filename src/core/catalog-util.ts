import fs from 'fs';
import os from 'os';
import path from 'path';

export function devxDir(): string {
  return path.join(os.homedir(), '.devx');
}

export function readJsonFile<T>(file: string): T | null {
  try {
    if (!fs.existsSync(file)) return null;
    return JSON.parse(fs.readFileSync(file, 'utf8')) as T;
  } catch {
    return null;
  }
}

/** The key used to match model ids written differently ("claude-sonnet-4.5" / "claude-sonnet-4-5-20250929"). */
export function normalizeModelKey(model: string): string {
  const parts = model.toLowerCase().trim().split('/');
  return (parts[parts.length - 1] || '')
    .replace(/:.*$/, '')
    .replace(/-latest$/, '')
    .replace(/-\d{4}-?\d{2}-?\d{2}$/, '')
    .replace(/\./g, '-');
}

// Providers that make the models themselves are preferred when several offer the same model.
export const NATIVE_PROVIDERS = ['openai', 'anthropic', 'google', 'deepseek', 'alibaba', 'xai', 'zai', 'moonshotai', 'mistral', 'meta', 'nvidia', 'groq'];
