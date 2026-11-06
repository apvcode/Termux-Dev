import { afterEach, describe, expect, it } from 'vitest';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { buildSystemPrompt } from '../../src/prompts/builder.js';
import { MemoryManager } from '../../src/core/memory.js';

describe('system prompt', () => {
  for (const planMode of [false, true]) {
    const name = planMode ? 'plan' : 'agent';

    it(`tells the model to answer plainly, and a greeting directly without tools (${name} mode)`, async () => {
      const prompt = await buildSystemPrompt(planMode);
      expect(prompt).toMatch(/plainly and briefly/);
      expect(prompt).toMatch(/no tools/);
      expect(prompt).toMatch(/Never explore the project on your own initiative/);
      expect(prompt).toMatch(/Never use emoji/);
    });

    it(`stays short (${name} mode)`, async () => {
      // The prompt is sent with every request; the project memory and repo map come on top of it.
      const prompt = await buildSystemPrompt(planMode);
      const base = prompt.split('--- ')[0];
      expect(base.length).toBeLessThan(4200);
    });
  }

  it('keeps the plan headings that devx recognises and the plan_ready tool', async () => {
    const prompt = await buildSystemPrompt(true);
    expect(prompt).toContain('Цель и архитектурный обзор');
    expect(prompt).toContain('plan_ready');
  });

  it('agent mode names the tools it relies on', async () => {
    const prompt = await buildSystemPrompt(false);
    for (const tool of ['todo_list', 'diagnose_code', 'install_package', 'serve_preview', 'save_memory']) {
      expect(prompt).toContain(tool);
    }
  });
});

describe('project memory in the prompt', () => {
  const original = process.cwd();
  afterEach(() => {
    process.chdir(original);
    MemoryManager.enabled = true;
  });

  const projectWithMemory = () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'devx-mem-'));
    fs.mkdirSync(path.join(dir, '.devx'));
    fs.writeFileSync(path.join(dir, '.devx', 'memory.md'), '# Project Memory Bank\n• Use pnpm, not npm\n');
    return dir;
  };

  it('puts the saved rules into the prompt', async () => {
    process.chdir(projectWithMemory());
    const prompt = await buildSystemPrompt(false);
    expect(prompt).toContain('Project Knowledge & Memory Bank');
    expect(prompt).toContain('Use pnpm, not npm');
    expect(await buildSystemPrompt(true)).toContain('Use pnpm, not npm');
  });

  it('a rule added with addFact is in the next prompt, and gone after clear', async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'devx-mem-'));
    process.chdir(dir);
    expect(await buildSystemPrompt(false)).not.toContain('Memory Bank');
    await MemoryManager.addFact('Tests live in tests/');
    await MemoryManager.addFact('Tests live in tests/');
    const prompt = await buildSystemPrompt(false);
    expect(prompt).toContain('Tests live in tests/');
    expect(prompt.split('Tests live in tests/').length - 1).toBe(1);
    await MemoryManager.clearMemory();
    expect(await buildSystemPrompt(false)).not.toContain('Memory Bank');
  });

  it('leaves the memory out when the settings switch is off, and does not ask the model to save', async () => {
    const dir = projectWithMemory();
    process.chdir(dir);
    expect(await buildSystemPrompt(false)).toContain('Use pnpm, not npm');   // on: it is there
    MemoryManager.enabled = false;
    const prompt = await buildSystemPrompt(false);
    expect(prompt).not.toContain('Memory Bank');
    expect(prompt).not.toContain('Use pnpm');
    expect(prompt).not.toContain('save_memory');
  });

  it('keeps asking for save_memory while the switch is on', async () => {
    MemoryManager.enabled = true;
    expect(await buildSystemPrompt(false)).toContain('save_memory');
  });
});

describe('rules in the prompt', () => {
  it('both modes forbid reading secrets and say what to do after a denial or a failure', async () => {
    for (const planMode of [false, true]) {
      const prompt = await buildSystemPrompt(planMode);
      expect(prompt).toContain('Never read, print or copy secrets');
      expect(prompt).toContain('~/.devxrc.json');
      expect(prompt).toContain('Action denied by user');
      expect(prompt).toContain('Try one different approach at most');
    }
  });

  it('agent mode has the rules for editing and for long commands, plan mode does not need them', async () => {
    const agent = await buildSystemPrompt(false);
    expect(agent).toContain('Read a file before you edit it');
    expect(agent).toContain('120 s');
    expect(await buildSystemPrompt(true)).not.toContain('Read a file before you edit it');
  });
});
