import { describe, expect, it } from 'vitest';
import { buildSystemPrompt } from '../../src/prompts/builder.js';

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
      expect(base.length).toBeLessThan(3500);
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
