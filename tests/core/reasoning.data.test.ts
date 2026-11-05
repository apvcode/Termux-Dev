import { describe, expect, it } from 'vitest';
import { ReasoningData, resolveReasoning } from '../../src/core/reasoning.js';

const OPENAI = 'https://api.openai.com/v1';
const ANTHROPIC = 'https://api.anthropic.com/v1';
const OPENROUTER = 'https://openrouter.ai/api/v1';
const GOOGLE = 'https://generativelanguage.googleapis.com/v1beta/openai/';
const DASHSCOPE = 'https://dashscope-intl.aliyuncs.com/compatible-mode/v1';
const GENERIC = 'http://127.0.0.1:8000/v1';

const catalog = (data: Omit<ReasoningData, 'source'>): ReasoningData => ({ source: 'catalog', ...data });
const endpoint = (data: Omit<ReasoningData, 'source'>): ReasoningData => ({ source: 'endpoint', ...data });

describe('effort values reported by a source', () => {
  it('become levels: "minimal" stands in for "off" where nothing is lower', () => {
    const caps = resolveReasoning('mystery-model', GENERIC, undefined, catalog({ efforts: ['minimal', 'low', 'medium', 'high'] }));
    expect(caps.levels).toEqual(['off', 'low', 'normal', 'high']);
    expect(caps.build('off')).toEqual({ reasoning_effort: 'minimal' });
    expect(caps.build('normal')).toEqual({ reasoning_effort: 'medium' });
    expect(caps.source).toBe('catalog');
    expect(caps.confidence).toBe('known');
  });

  it('can reach xhigh and max when the API accepts them', () => {
    const caps = resolveReasoning('mystery-model', OPENAI, undefined, catalog({ efforts: ['none', 'low', 'medium', 'high', 'xhigh', 'max'] }));
    expect(caps.levels).toEqual(['off', 'low', 'normal', 'high', 'xhigh', 'max']);
    expect(caps.build('off')).toEqual({ reasoning_effort: 'none' });
    expect(caps.build('xhigh')).toEqual({ reasoning_effort: 'xhigh' });
  });

  it('are authoritative over the hand-written rules: a new generation adds levels', () => {
    expect(resolveReasoning('o3', OPENAI).levels).toEqual(['low', 'normal', 'high']);
    const caps = resolveReasoning('o3', OPENAI, undefined, catalog({ efforts: ['low', 'medium', 'high', 'xhigh'] }));
    expect(caps.levels).toEqual(['low', 'normal', 'high', 'xhigh']);
  });

  it('are sent as reasoning.effort on OpenRouter', () => {
    const caps = resolveReasoning('vendor/new-model', OPENROUTER, undefined, endpoint({ efforts: ['low', 'medium', 'high'], mandatory: true }));
    expect(caps.build('high')).toEqual({ reasoning: { effort: 'high' } });
    expect(caps.source).toBe('endpoint');
  });

  it('that mean nothing to us fall back to the rules, or to "unknown"', () => {
    expect(resolveReasoning('o3', OPENAI, undefined, catalog({ efforts: ['default', 'turbo'] })).levels).toEqual(['low', 'normal', 'high']);
    expect(resolveReasoning('o3', OPENAI, undefined, catalog({ efforts: ['default'] })).source).toBe('rules');
    expect(resolveReasoning('mystery-model', OPENAI, undefined, catalog({ efforts: ['default'] })).supported).toBe(false);
  });
});

describe('token budgets reported by a source', () => {
  it('give five levels spread over the allowed range, without duplicates', () => {
    const caps = resolveReasoning('mystery-model', ANTHROPIC, undefined, catalog({ budget: { min: 1024 } }));
    // no toggle and a minimum above zero: it cannot be switched off
    expect(caps.levels).toEqual(['low', 'normal', 'high', 'xhigh', 'max']);

    const flash = resolveReasoning('mystery-model', GOOGLE, undefined, catalog({ toggle: true, budget: { min: 0, max: 24576 } }));
    expect(flash.levels).toEqual(['off', 'low', 'normal', 'high', 'xhigh']); // max would equal xhigh: dropped
    expect(flash.build('off')).toEqual({ extra_body: { google: { thinking_config: { thinking_budget: 0, include_thoughts: true } } } });
    expect(flash.build('high')).toEqual({ extra_body: { google: { thinking_config: { thinking_budget: 16384, include_thoughts: true } } } });
  });

  it('respect the minimum and the maximum of the source', () => {
    const caps = resolveReasoning('mystery-model', ANTHROPIC, undefined, catalog({ budget: { min: 4096, max: 10000 } }));
    const budgets = caps.levels.map((l) => (caps.build(l) as any).thinking.budget_tokens);
    for (const b of budgets) {
      expect(b).toBeGreaterThanOrEqual(4096);
      expect(b).toBeLessThanOrEqual(10000);
    }
    expect(new Set(budgets).size).toBe(budgets.length);
    expect(Math.max(...budgets)).toBe(10000);
  });

  it('are sent the way the route wants them', () => {
    const data = catalog({ toggle: true, budget: { min: 0, max: 32768 } });
    expect(resolveReasoning('mystery-model', ANTHROPIC, undefined, data).build('high')).toMatchObject({ thinking: { type: 'enabled', budget_tokens: 16384 } });
    expect(resolveReasoning('mystery-model', OPENROUTER, undefined, data).build('high')).toEqual({ reasoning: { max_tokens: 16384 } });
    expect(resolveReasoning('mystery-model', DASHSCOPE, undefined, data).build('high')).toEqual({ enable_thinking: true, thinking_budget: 16384 });
    expect(resolveReasoning('mystery-model', DASHSCOPE, undefined, data).build('off')).toEqual({ enable_thinking: false });
    // an endpoint nobody knows gets the three coarse reasoning_effort steps instead
    const generic = resolveReasoning('mystery-model', GENERIC, undefined, data);
    expect(generic.levels).toEqual(['off', 'low', 'normal', 'high']);
    expect(generic.confidence).toBe('guess');
  });

  it('keep the hand-written budgets when a rule knows the vendor limits', () => {
    const caps = resolveReasoning('claude-sonnet-4-5', ANTHROPIC, undefined, catalog({ budget: { min: 1024 } }));
    expect(caps.build('high')).toMatchObject({ thinking: { type: 'enabled', budget_tokens: 16000 } }); // the rule's number
    expect(caps.levels).toContain('max');
    // while the source decides the rest
    expect(caps.source).toBe('catalog');
  });
});

describe('"cannot be switched off" and other partial information', () => {
  it('mandatory removes "off" from a rule', () => {
    const optional = resolveReasoning('claude-sonnet-4-5', ANTHROPIC, undefined, endpoint({ supported: true, budget: {} }));
    expect(optional.levels).toContain('off');
    const mandatory = resolveReasoning('claude-sonnet-4-5', ANTHROPIC, undefined, endpoint({ supported: true, budget: {}, mandatory: true }));
    expect(mandatory.levels).not.toContain('off');
    expect(mandatory.alwaysOn).toBe(true);
  });

  it('only "it can be toggled": off and on for an unknown model', () => {
    const caps = resolveReasoning('mystery-model', OPENROUTER, undefined, catalog({ toggle: true }));
    expect(caps.levels).toEqual(['off', 'normal']);
    expect(caps.build('off')).toEqual({ reasoning: { enabled: false } });
    expect(resolveReasoning('mystery-model', OPENROUTER, undefined, catalog({ toggle: true, mandatory: true })).levels).toEqual(['normal']);
  });

  it('only "it reasons", for a model with a rule: the rule says how', () => {
    const caps = resolveReasoning('o3', OPENAI, undefined, endpoint({ supported: true, mandatory: true }));
    expect(caps.levels).toEqual(['low', 'normal', 'high']);
    expect(caps.source).toBe('endpoint');
  });

  it('only "it reasons", for an unknown model: a cautious reasoning_effort guess', () => {
    const caps = resolveReasoning('mystery-model', GENERIC, undefined, endpoint({ supported: true }));
    expect(caps.levels).toEqual(['off', 'low', 'normal', 'high']);
    expect(caps.confidence).toBe('guess');
    expect(caps.build('normal')).toEqual({ reasoning_effort: 'medium' });
    expect(caps.build('off')).toEqual({ reasoning_effort: 'none' });
    const mandatory = resolveReasoning('mystery-model', GENERIC, undefined, endpoint({ supported: true, mandatory: true }));
    expect(mandatory.levels).toEqual(['low', 'normal', 'high']);
  });
});

describe('a source that says there is no reasoning', () => {
  it('wins even over a rule, and says who said so', () => {
    const caps = resolveReasoning('claude-sonnet-4-5', ANTHROPIC, undefined, catalog({ none: true }));
    expect(caps.supported).toBe(false);
    expect(caps.levels).toEqual([]);
    expect(caps.source).toBe('catalog');
    expect(caps.note).toBe('The models.dev catalogue reports that this model has no reasoning mode.');
    expect(resolveReasoning('x', GENERIC, undefined, endpoint({ none: true })).note).toBe('The endpoint reports that this model has no reasoning mode.');
  });
});

describe('where the levels come from', () => {
  it('is reported for every way of resolving a model', () => {
    expect(resolveReasoning('o3', OPENAI).source).toBe('rules');
    expect(resolveReasoning('o3', OPENAI, undefined, catalog({ efforts: ['low', 'high'] })).source).toBe('catalog');
    expect(resolveReasoning('o3', OPENAI, undefined, endpoint({ efforts: ['low', 'high'] })).source).toBe('endpoint');
    expect(resolveReasoning('unknown-model', OPENAI).source).toBe('none');
    expect(resolveReasoning('deepseek-reasoner', OPENAI).source).toBe('rules');
    const profile = { 'my-model': { levels: ['high' as const], param: 'x', values: { high: 'y' } } };
    expect(resolveReasoning('my-model', GENERIC, profile).source).toBe('profile');
  });

  it('a profile of the user beats any data', () => {
    const profile = { o3: { levels: ['high' as const], param: 'thinking_mode', values: { high: 'deep' } } };
    const caps = resolveReasoning('o3', OPENAI, profile, catalog({ efforts: ['low', 'medium', 'high'] }));
    expect(caps.levels).toEqual(['high']);
    expect(caps.build('high')).toEqual({ thinking_mode: 'deep' });
  });

  it('data can describe a model the rules call "always on"', () => {
    const caps = resolveReasoning('deepseek-r1-future', GENERIC, undefined, catalog({ efforts: ['low', 'medium', 'high'] }));
    expect(caps.supported).toBe(true);
    expect(caps.levels).toEqual(['low', 'normal', 'high']);
  });
});
