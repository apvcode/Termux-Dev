import { describe, expect, it } from 'vitest';
import {
  LEVELS,
  ReasoningProfile,
  detectRoute,
  effectiveReasoning,
  effortLabel,
  thinkLabel,
  isLevel,
  parseEffortArg,
  resolveReasoning,
  withEffort,
} from '../../src/core/reasoning.js';

const OPENROUTER = 'https://openrouter.ai/api/v1';
const OPENAI = 'https://api.openai.com/v1';
const ANTHROPIC = 'https://api.anthropic.com/v1/';
const GOOGLE = 'https://generativelanguage.googleapis.com/v1beta/openai/';
const DASHSCOPE = 'https://dashscope-intl.aliyuncs.com/compatible-mode/v1';
const GENERIC = 'http://127.0.0.1:11434/v1';

describe('routes', () => {
  it('recognises the API from the base URL', () => {
    expect(detectRoute(OPENROUTER)).toBe('openrouter');
    expect(detectRoute(OPENAI)).toBe('openai');
    expect(detectRoute(ANTHROPIC)).toBe('anthropic');
    expect(detectRoute(GOOGLE)).toBe('google');
    expect(detectRoute(DASHSCOPE)).toBe('dashscope');
    expect(detectRoute('https://api.z.ai/api/paas/v4')).toBe('zai');
    expect(detectRoute('https://open.bigmodel.cn/api/paas/v4')).toBe('zai');
    expect(detectRoute('https://api.deepseek.com')).toBe('deepseek');
    expect(detectRoute('http://localhost:1234/v1')).toBe('generic');
    expect(detectRoute('')).toBe('generic');
    expect(detectRoute('not a url')).toBe('generic');
  });
});

describe('levels', () => {
  it('has the six levels in ascending order', () => {
    expect(LEVELS).toEqual(['off', 'low', 'normal', 'high', 'xhigh', 'max']);
    expect(isLevel('high')).toBe(true);
    expect(isLevel('extreme')).toBe(false);
    expect(isLevel(3)).toBe(false);
  });

  it('parses what the user types, with natural aliases', () => {
    expect(parseEffortArg('high')).toEqual({ kind: 'level', level: 'high' });
    expect(parseEffortArg('  MEDIUM ')).toEqual({ kind: 'level', level: 'normal' });
    expect(parseEffortArg('none')).toEqual({ kind: 'level', level: 'off' });
    expect(parseEffortArg('x-high')).toEqual({ kind: 'level', level: 'xhigh' });
    expect(parseEffortArg('maximum')).toEqual({ kind: 'level', level: 'max' });
    expect(parseEffortArg('auto')).toEqual({ kind: 'auto' });
    expect(parseEffortArg('reset')).toEqual({ kind: 'auto' });
    expect(parseEffortArg('banana')).toEqual({ kind: 'invalid', text: 'banana' });
  });
});

describe('what each model offers', () => {
  const levelsOf = (model: string, baseUrl = GENERIC) => resolveReasoning(model, baseUrl).levels;

  it('OpenAI o-series: three efforts, cannot be switched off', () => {
    for (const model of ['o3', 'o4-mini', 'o3-mini', 'o1', 'openai/o3-2025-04-16']) {
      const caps = resolveReasoning(model, OPENAI);
      expect(caps.levels, model).toEqual(['low', 'normal', 'high']);
      expect(caps.alwaysOn, model).toBe(true);
      expect(caps.confidence).toBe('known');
    }
    expect(levelsOf('o3-pro')).toEqual(['high']);
  });

  it('models of the o1 family without effort control are not offered anything', () => {
    for (const model of ['o1-mini', 'o1-preview']) expect(resolveReasoning(model, OPENAI).supported, model).toBe(false);
  });

  it('GPT-5: "off" means minimal, 5.1 can really switch off, 5.2 adds xhigh', () => {
    expect(levelsOf('gpt-5')).toEqual(['off', 'low', 'normal', 'high']);
    expect(resolveReasoning('gpt-5', OPENAI).build('off')).toEqual({ reasoning_effort: 'minimal' });
    expect(resolveReasoning('gpt-5.1', OPENAI).build('off')).toEqual({ reasoning_effort: 'none' });
    expect(levelsOf('gpt-5.1')).toEqual(['off', 'low', 'normal', 'high']);
    expect(levelsOf('gpt-5.2')).toEqual(['off', 'low', 'normal', 'high', 'xhigh']);
    expect(levelsOf('gpt-5.1-codex-max')).toContain('xhigh');
    expect(levelsOf('gpt-5-mini')).toEqual(['off', 'low', 'normal', 'high']);
    expect(levelsOf('gpt-5-pro')).toEqual(['high']);
    expect(resolveReasoning('gpt-5-chat-latest', OPENAI).supported).toBe(false);
  });

  it('gpt-oss and grok-3-mini', () => {
    expect(levelsOf('gpt-oss-120b')).toEqual(['low', 'normal', 'high']);
    expect(levelsOf('openai/gpt-oss-20b:free', OPENROUTER)).toEqual(['low', 'normal', 'high']);
    expect(levelsOf('gpt-oss:20b')).toEqual(['low', 'normal', 'high']); // Ollama tag
    expect(levelsOf('grok-3-mini')).toEqual(['low', 'high']);
  });

  it('Claude: a budget from off to max; Opus 4 tops out one step earlier', () => {
    expect(levelsOf('claude-sonnet-4-5-20250929', ANTHROPIC)).toEqual(['off', 'low', 'normal', 'high', 'xhigh', 'max']);
    expect(levelsOf('anthropic/claude-3.7-sonnet', OPENROUTER)).toContain('max');
    expect(levelsOf('claude-haiku-4-5', ANTHROPIC)).toContain('max');
    expect(levelsOf('claude-opus-4-1', ANTHROPIC)).toEqual(['off', 'low', 'normal', 'high', 'xhigh']);
    expect(levelsOf('claude-opus-4-5', ANTHROPIC)).toContain('max');
    expect(resolveReasoning('claude-3-5-sonnet-20241022', ANTHROPIC).supported).toBe(false); // no extended thinking
  });

  it('Gemini 2.5 Pro cannot be switched off, Flash can', () => {
    expect(levelsOf('gemini-2.5-pro', GOOGLE)).toEqual(['low', 'normal', 'high', 'max']);
    expect(resolveReasoning('gemini-2.5-pro', GOOGLE).alwaysOn).toBe(true);
    expect(levelsOf('gemini-2.5-flash', GOOGLE)).toEqual(['off', 'low', 'normal', 'high', 'max']);
    expect(levelsOf('google/gemini-2.5-flash-lite', OPENROUTER)).toContain('off');
    expect(levelsOf('gemini-3-pro-preview', GOOGLE)).toEqual(['low', 'high']);
    expect(resolveReasoning('gemini-2.0-flash', GOOGLE).supported).toBe(false);
  });

  it('hybrid models: Qwen3, DeepSeek V3.1+/V4, GLM', () => {
    expect(levelsOf('qwen3-235b-a22b', DASHSCOPE)).toEqual(['off', 'low', 'normal', 'high', 'max']);
    expect(levelsOf('deepseek-v4.1-flash')).toEqual(['off', 'normal']);
    expect(levelsOf('deepseek/deepseek-chat-v3.1', OPENROUTER)).toEqual(['off', 'normal']);
    expect(levelsOf('glm-4.6', 'https://api.z.ai/api/paas/v4')).toEqual(['off', 'normal']);
  });

  it('models that always reason offer nothing, and say so', () => {
    for (const model of ['deepseek-reasoner', 'deepseek/deepseek-r1', 'deepseek-r1-0528', 'qwq-32b', 'qwen3-235b-a22b-thinking-2507', 'grok-4', 'magistral-medium', 'minimax-m2']) {
      const caps = resolveReasoning(model, GENERIC);
      expect(caps.supported, model).toBe(false);
      expect(caps.levels, model).toEqual([]);
      expect(caps.alwaysOn, model).toBe(true);
      expect(caps.note, model).toMatch(/always reasons/);
    }
  });

  it('models nobody described offer nothing instead of a guess', () => {
    for (const model of ['gpt-4o', 'llama-3.3-70b', 'mistral-large', 'some-new-model-7b', '']) {
      const caps = resolveReasoning(model, OPENAI);
      expect(caps.supported, model).toBe(false);
      expect(caps.levels).toEqual([]);
      expect(caps.alwaysOn).toBe(false);
      expect(caps.note).toMatch(/No reasoning setting is known/);
      expect(caps.build('high')).toBeNull();
    }
  });
});

describe('how a level is sent', () => {
  it('effort models: reasoning_effort, or reasoning.effort on OpenRouter', () => {
    expect(resolveReasoning('o3', OPENAI).build('normal')).toEqual({ reasoning_effort: 'medium' });
    expect(resolveReasoning('o3', OPENAI).build('high')).toEqual({ reasoning_effort: 'high' });
    expect(resolveReasoning('openai/o3', OPENROUTER).build('high')).toEqual({ reasoning: { effort: 'high' } });
    expect(resolveReasoning('gpt-5.2', OPENAI).build('xhigh')).toEqual({ reasoning_effort: 'xhigh' });
    expect(resolveReasoning('grok-3-mini', 'https://api.x.ai/v1').build('low')).toEqual({ reasoning_effort: 'low' });
  });

  it('Claude: thinking budget on Anthropic (with room to answer), reasoning.max_tokens on OpenRouter', () => {
    const direct = resolveReasoning('claude-sonnet-4-5', ANTHROPIC);
    expect(direct.build('high')).toEqual({ thinking: { type: 'enabled', budget_tokens: 16000 }, max_tokens: 32000 });
    expect(direct.build('low')).toEqual({ thinking: { type: 'enabled', budget_tokens: 2048 }, max_tokens: 18048 });
    expect(direct.build('off')).toBeNull(); // thinking is off unless asked for
    const routed = resolveReasoning('anthropic/claude-sonnet-4.5', OPENROUTER);
    expect(routed.build('max')).toEqual({ reasoning: { max_tokens: 63000 } });
    expect(routed.build('off')).toBeNull();
  });

  it('models that think by default need an explicit "off"', () => {
    expect(resolveReasoning('google/gemini-2.5-flash', OPENROUTER).build('off')).toEqual({ reasoning: { enabled: false } });
    expect(resolveReasoning('gemini-2.5-flash', GOOGLE).build('off')).toEqual({
      extra_body: { google: { thinking_config: { thinking_budget: 0, include_thoughts: true } } },
    });
    expect(resolveReasoning('gemini-2.5-pro', GOOGLE).build('max')).toEqual({
      extra_body: { google: { thinking_config: { thinking_budget: 32768, include_thoughts: true } } },
    });
    expect(resolveReasoning('qwen3-235b-a22b', DASHSCOPE).build('off')).toEqual({ enable_thinking: false });
    expect(resolveReasoning('qwen3-235b-a22b', DASHSCOPE).build('high')).toEqual({ enable_thinking: true, thinking_budget: 16384 });
  });

  it('toggle models: reasoning.enabled on OpenRouter, thinking.type for Z.ai / DeepSeek, reasoning_effort elsewhere', () => {
    expect(resolveReasoning('deepseek/deepseek-chat-v3.1', OPENROUTER).build('off')).toEqual({ reasoning: { enabled: false } });
    expect(resolveReasoning('deepseek/deepseek-chat-v3.1', OPENROUTER).build('normal')).toEqual({ reasoning: { enabled: true } });
    expect(resolveReasoning('glm-4.6', 'https://api.z.ai/api/paas/v4').build('off')).toEqual({ thinking: { type: 'disabled' } });
    expect(resolveReasoning('deepseek-v4.1-flash', GENERIC).build('off')).toEqual({ reasoning_effort: 'none' });
    expect(resolveReasoning('deepseek-v4.1-flash', GENERIC).build('normal')).toEqual({ reasoning_effort: 'medium' });
  });

  it('a budget model on an unknown endpoint falls back to three coarse reasoning_effort steps, marked as a guess', () => {
    const caps = resolveReasoning('claude-sonnet-4-5', 'https://proxy.example.com/v1');
    expect(caps.levels).toEqual(['off', 'low', 'normal', 'high']);
    expect(caps.confidence).toBe('guess');
    expect(caps.build('high')).toEqual({ reasoning_effort: 'high' });
    expect(caps.build('off')).toEqual({ reasoning_effort: 'none' });
  });

  it('every offered level produces parameters (or deliberately nothing) without throwing', () => {
    const cases: Array<[string, string]> = [
      ['o3', OPENAI], ['gpt-5.2', OPENAI], ['claude-opus-4-1', ANTHROPIC], ['gemini-2.5-flash', GOOGLE],
      ['qwen3-32b', DASHSCOPE], ['deepseek-v4.1-flash', GENERIC], ['gpt-oss-20b', OPENROUTER],
    ];
    for (const [model, url] of cases) {
      const caps = resolveReasoning(model, url);
      for (const level of caps.levels) {
        expect(() => caps.build(level), `${model} ${level}`).not.toThrow();
        expect(typeof caps.describe(level)).toBe('string');
        expect(caps.describe(level).length).toBeGreaterThan(3);
      }
    }
  });

  it('describes levels for the menu', () => {
    const claude = resolveReasoning('claude-sonnet-4-5', ANTHROPIC);
    expect(claude.describe('off')).toBe('no reasoning');
    expect(claude.describe('high')).toBe('deep reasoning (~16k thinking tokens)');
    expect(resolveReasoning('o3', OPENAI).describe('normal')).toBe('balanced (effort: medium)');
  });
});

describe('profiles from the config file', () => {
  const profiles: Record<string, ReasoningProfile> = {
    'my-model': { levels: ['off', 'normal', 'high'], param: 'reasoning_effort', values: { off: 'none', normal: 'medium', high: 'high' } },
    'nested-model': { levels: ['low', 'high'], param: 'reasoning.effort', values: { low: 'low', high: 'high' } },
    'flag-model': { levels: ['off', 'normal'], param: 'enable_thinking', values: { off: false, normal: true } },
  };

  it('describe a model nobody else knows', () => {
    const caps = resolveReasoning('My-Model', GENERIC, profiles);
    expect(caps.supported).toBe(true);
    expect(caps.confidence).toBe('custom');
    expect(caps.levels).toEqual(['off', 'normal', 'high']);
    expect(caps.build('high')).toEqual({ reasoning_effort: 'high' });
    expect(caps.alwaysOn).toBe(false);
  });

  it('support nested parameter names and non-string values', () => {
    expect(resolveReasoning('nested-model', GENERIC, profiles).build('low')).toEqual({ reasoning: { effort: 'low' } });
    expect(resolveReasoning('flag-model', GENERIC, profiles).build('off')).toEqual({ enable_thinking: false });
  });

  it('win over the built-in table, and broken profiles are ignored', () => {
    const override: Record<string, ReasoningProfile> = { o3: { levels: ['high'], param: 'x', values: { high: 'yes' } } };
    expect(resolveReasoning('o3', OPENAI, override).levels).toEqual(['high']);
    expect(resolveReasoning('o3', OPENAI, { o3: { nonsense: true } as any }).levels).toEqual(['low', 'normal', 'high']);
  });
});

describe('the level that applies', () => {
  const base = { model: 'claude-sonnet-4-5', baseUrl: ANTHROPIC };

  it('is nothing until the user chooses: the model default is left alone', () => {
    const result = effectiveReasoning(base);
    expect(result.level).toBeUndefined();
    expect(result.params).toBeNull();
    expect(effortLabel(base)).toBe('');
  });

  it('uses the saved choice for this model', () => {
    const config = { ...base, effort: { 'claude-sonnet-4-5': 'high' } };
    const result = effectiveReasoning(config);
    expect(result.level).toBe('high');
    expect(result.params).toMatchObject({ thinking: { type: 'enabled', budget_tokens: 16000 } });
    expect(effortLabel(config)).toBe('high');
  });

  it('the status line says "think auto" by default, the level when set, and nothing without a reasoning setting', () => {
    expect(thinkLabel(base)).toBe('think auto');
    expect(thinkLabel({ ...base, effort: { 'claude-sonnet-4-5': 'high' } })).toBe('think high');
    expect(thinkLabel({ model: 'some-unknown-model', baseUrl: GENERIC })).toBe('');
  });

  it('never sends a stale setting after switching to a model that does not offer it', () => {
    const effort = { 'claude-sonnet-4-5': 'max', 'o3': 'xhigh', 'deepseek-reasoner': 'high' };
    expect(effectiveReasoning({ model: 'claude-opus-4-1', baseUrl: ANTHROPIC, effort: { 'claude-opus-4-1': 'max' } }).level).toBeUndefined();
    expect(effectiveReasoning({ model: 'o3', baseUrl: OPENAI, effort }).level).toBeUndefined();   // o3 has no xhigh
    expect(effectiveReasoning({ model: 'deepseek-reasoner', baseUrl: GENERIC, effort }).level).toBeUndefined();
    expect(effectiveReasoning({ model: 'claude-sonnet-4-5', baseUrl: ANTHROPIC, effort }).level).toBe('max');
  });

  it('lets a one-off override (the --effort flag) win over the saved choice', () => {
    const config = { ...base, effort: { 'claude-sonnet-4-5': 'high' }, effortOverride: 'low' };
    expect(effectiveReasoning(config).level).toBe('low');
    expect(effectiveReasoning({ ...base, effortOverride: 'banana' }).level).toBeUndefined();
  });

  it('keys the saved choice by model id, ignoring case and whitespace', () => {
    expect(effectiveReasoning({ ...base, model: ' Claude-Sonnet-4-5 ', effort: { 'claude-sonnet-4-5': 'low' } }).level).toBe('low');
  });

  it('stores and clears a choice without touching the others', () => {
    const one = withEffort(undefined, 'O3', 'high');
    expect(one).toEqual({ o3: 'high' });
    const two = withEffort(one, 'claude-sonnet-4-5', 'low');
    expect(two).toEqual({ o3: 'high', 'claude-sonnet-4-5': 'low' });
    expect(withEffort(two, 'o3', null)).toEqual({ 'claude-sonnet-4-5': 'low' });
    expect(two).toEqual({ o3: 'high', 'claude-sonnet-4-5': 'low' }); // not mutated
  });
});
