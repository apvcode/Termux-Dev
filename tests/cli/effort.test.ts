import { describe, expect, it } from 'vitest';
import { buildEffortPicker, chooseEffort, effortCommand, sentText } from '../../src/cli/effort.js';

const OPENAI = 'https://api.openai.com/v1';
const ANTHROPIC = 'https://api.anthropic.com/v1';

const o3 = () => ({ model: 'o3', baseUrl: OPENAI, effort: {} as Record<string, string> });
const claude = () => ({ model: 'claude-sonnet-4-5', baseUrl: ANTHROPIC, effort: {} as Record<string, string> });

describe('/effort without an argument: the picker', () => {
  it('offers auto plus exactly the levels of the current model', () => {
    const picker = buildEffortPicker(o3());
    expect(picker.title).toBe('effort');
    expect(picker.subtitle).toBe('o3');
    expect(picker.items.map((i) => i.value)).toEqual(['auto', 'low', 'normal', 'high']);
    expect(picker.items[0].detail).toMatch(/nothing is sent/);
    expect(picker.items[3].detail).toBe('deep reasoning (effort: high)');

    expect(buildEffortPicker(claude()).items.map((i) => i.value)).toEqual(['auto', 'off', 'low', 'normal', 'high', 'xhigh', 'max']);
    expect(buildEffortPicker({ model: 'claude-opus-4-1', baseUrl: ANTHROPIC }).items.map((i) => i.value)).not.toContain('max');
    expect(buildEffortPicker({ model: 'gemini-2.5-flash', baseUrl: 'https://generativelanguage.googleapis.com/v1beta/openai/' }).items.map((i) => i.value))
      .toEqual(['auto', 'off', 'low', 'normal', 'high', 'max']);
  });

  it('says what the model does not offer', () => {
    const notes = buildEffortPicker(o3()).notes!.join(' ');
    expect(notes).toContain('Not offered by this model: off, xhigh, max.');
    expect(notes).toContain('cannot switch reasoning off');
    expect(buildEffortPicker({ model: 'claude-opus-4-1', baseUrl: ANTHROPIC }).notes!.join(' ')).toContain('Not offered by this model: max.');
  });

  it('marks the level in force as current, auto when none is set', () => {
    expect(buildEffortPicker(o3()).current).toBe('auto');
    expect(buildEffortPicker({ ...o3(), effort: { o3: 'high' } }).current).toBe('high');
    // a saved level the model does not offer is not "current"
    expect(buildEffortPicker({ ...o3(), effort: { o3: 'max' } }).current).toBe('auto');
  });

  it('is not influenced by the one-off --effort override: it shows what is saved', () => {
    expect(buildEffortPicker({ ...o3(), effort: { o3: 'low' }, effortOverride: 'high' }).current).toBe('low');
  });

  it('warns when the levels were inferred', () => {
    const notes = buildEffortPicker({ model: 'deepseek-v4.1-flash', baseUrl: 'http://127.0.0.1:8000/v1' }).notes!.join(' ');
    expect(notes).toContain('How the setting is sent to this endpoint is inferred');
    expect(notes).toContain('Levels inferred from the model name.');
  });
});

describe('/effort <level>', () => {
  it('sets a level the model offers, saves it per model and shows what is sent', () => {
    const config = o3();
    const result = effortCommand(config, 'high');
    expect(result.type).toBe('say');
    if (result.type !== 'say') return;
    expect(config.effort).toEqual({ o3: 'high' });
    expect(result.say.kind).toBe('ok');
    expect(result.say.text).toBe('effort high for o3');
    expect(result.say.detail).toBe('deep reasoning (effort: high); sends {"reasoning_effort":"high"}');
  });

  it('understands aliases', () => {
    const config = o3();
    effortCommand(config, 'MEDIUM');
    expect(config.effort).toEqual({ o3: 'normal' });
  });

  it('refuses a level the model does not offer and lists what it does', () => {
    const config = o3();
    const result = effortCommand(config, 'max');
    if (result.type !== 'say') throw new Error('expected a message');
    expect(result.say.kind).toBe('warn');
    expect(result.say.text).toBe('o3 does not offer "max".');
    expect(result.say.detail).toBe('It offers: auto, low, normal, high.');
    expect(config.effort).toEqual({}); // nothing changed
    // "off" is a real choice on Claude but not on o3
    const off = effortCommand(o3(), 'off');
    if (off.type !== 'say') throw new Error('expected a message');
    expect(off.say.kind).toBe('warn');
    expect(effortCommand(claude(), 'off').type).toBe('say');
  });

  it('rejects nonsense and lists the valid levels of this model', () => {
    const result = effortCommand(o3(), 'banana');
    if (result.type !== 'say') throw new Error('expected a message');
    expect(result.say.kind).toBe('warn');
    expect(result.say.text).toBe('Unknown level "banana".');
    expect(result.say.detail).toBe('o3 offers: auto, low, normal, high.');
  });

  it('auto removes the saved level of this model only', () => {
    const config = { ...o3(), effort: { o3: 'high', 'claude-sonnet-4-5': 'low' } };
    const result = effortCommand(config, 'auto');
    if (result.type !== 'say') throw new Error('expected a message');
    expect(config.effort).toEqual({ 'claude-sonnet-4-5': 'low' });
    expect(result.say.text).toBe('effort auto for o3');
    expect(result.say.detail).toMatch(/no reasoning setting is sent/);
  });

  it('keeps a separate choice for each model', () => {
    const config: any = { model: 'o3', baseUrl: OPENAI };
    effortCommand(config, 'high');
    config.model = 'claude-sonnet-4-5';
    config.baseUrl = ANTHROPIC;
    effortCommand(config, 'low');
    expect(config.effort).toEqual({ o3: 'high', 'claude-sonnet-4-5': 'low' });
  });
});

describe('/effort on a model without a setting', () => {
  it('explains, and says how to describe an unknown model yourself', () => {
    const config = { model: 'some-new-model', baseUrl: OPENAI, effort: {} };
    const result = effortCommand(config, 'high');
    if (result.type !== 'say') throw new Error('expected a message');
    expect(result.say.kind).toBe('warn');
    expect(result.say.text).toBe('some-new-model has no reasoning setting.');
    expect(result.say.detail).toContain('No reasoning setting is known');
    expect(result.say.detail).toContain('reasoningProfiles');
    expect(effortCommand(config, '').type).toBe('say'); // no picker with nothing to pick
  });

  it('does not suggest a profile for a model that always reasons', () => {
    const result = effortCommand({ model: 'deepseek-reasoner', baseUrl: 'https://api.deepseek.com' }, '');
    if (result.type !== 'say') throw new Error('expected a message');
    expect(result.say.detail).toContain('always reasons');
    expect(result.say.detail).not.toContain('reasoningProfiles');
  });

  it('works with a profile from the config', () => {
    const config = {
      model: 'my-model', baseUrl: 'http://localhost:8000/v1', effort: {} as Record<string, string>,
      reasoningProfiles: { 'my-model': { levels: ['off', 'high'], param: 'thinking_mode', values: { off: 'none', high: 'deep' } } },
    };
    const picker = effortCommand(config, '');
    expect(picker.type).toBe('pick');
    const set = effortCommand(config, 'high');
    if (set.type !== 'say') throw new Error('expected a message');
    expect(set.say.detail).toContain('sends {"thinking_mode":"deep"}');
  });
});

describe('helpers', () => {
  it('chooseEffort works on a config that has no effort map yet', () => {
    const config: any = { model: 'o3', baseUrl: OPENAI };
    expect(chooseEffort(config, 'low').text).toBe('effort low for o3');
    expect(config.effort).toEqual({ o3: 'low' });
    expect(chooseEffort({ model: 'o3' } as any, 'auto').kind).toBe('ok');
  });

  it('describes what is sent', () => {
    expect(sentText(null)).toBe('sends nothing (the model default applies)');
    expect(sentText({ reasoning: { effort: 'high' } })).toBe('sends {"reasoning":{"effort":"high"}}');
  });
});
