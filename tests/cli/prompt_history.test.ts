import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import fs from 'fs/promises';
import os from 'os';
import path from 'path';
import { flushPromptHistory, loadPromptHistory, promptHistory, recordPrompt, resetPromptHistory } from '../../src/cli/prompt_history.js';

describe('the history of sent lines', () => {
  let file: string;
  let before: string | undefined;
  beforeEach(async () => {
    before = process.env.DEVX_HISTORY_FILE;
    file = path.join(await fs.mkdtemp(path.join(os.tmpdir(), 'devx-hist-')), 'history.jsonl');
    process.env.DEVX_HISTORY_FILE = file;
    resetPromptHistory();
  });
  afterEach(() => {
    if (before === undefined) delete process.env.DEVX_HISTORY_FILE;
    else process.env.DEVX_HISTORY_FILE = before;
    resetPromptHistory();
  });

  const settle = () => flushPromptHistory();

  it('remembers a line, not twice in a row, and writes it to the file', async () => {
    recordPrompt('first');
    recordPrompt('first');
    recordPrompt('  second  ');
    recordPrompt('   ');
    expect(promptHistory()).toEqual(['first', 'second']);
    await settle();
    const saved = (await fs.readFile(file, 'utf8')).trim().split('\n').map((l) => JSON.parse(l).prompt);
    expect(saved).toEqual(['first', 'second']);
  });

  it('brings back the lines of earlier sessions, the new ones after them', async () => {
    await fs.writeFile(file, [{ prompt: 'old one' }, { prompt: 'old two' }].map((e) => JSON.stringify(e)).join('\n') + '\n');
    recordPrompt('typed before the file was read');
    await loadPromptHistory();
    expect(promptHistory()).toEqual(['old one', 'old two', 'typed before the file was read']);
  });

  it('keeps a message with several lines in one entry', async () => {
    recordPrompt('line one\nline two');
    await settle();
    await loadPromptHistory();
    expect(promptHistory()).toEqual(['line one\nline two']);
  });

  it('does not write a huge paste to the file, but it stays in memory', async () => {
    recordPrompt('x'.repeat(5000));
    await settle();
    expect(promptHistory()).toHaveLength(1);
    await expect(fs.readFile(file, 'utf8')).rejects.toThrow();
  });
});
