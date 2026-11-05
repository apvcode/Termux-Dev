import { afterEach, describe, expect, it } from 'vitest';
import { VisionCatalog, setVisionCatalogs, slimVision } from '../../src/core/vision.js';
import { getModelContextInfo, getModelContextLimit } from '../../src/core/models.js';

const API = {
  anthropic: { models: { 'claude-x-1': { limit: { context: 200000 }, modalities: { input: ['text', 'image'] } } } },
  reseller: { models: { 'claude-x-1': { limit: { context: 32000 } }, 'open-model': { limit: { context: 65536 } } } },
  reseller2: { models: { 'open-model': { limit: { context: 131072 } } } },
  reseller3: { models: { 'open-model': { limit: { context: 131072 } }, 'tiny': { limit: { context: 100 } } } },
};

afterEach(() => setVisionCatalogs(null, null));

describe('context windows from the catalogue', () => {
  it("takes the maker's own number over a reseller's cap, and the most common value when only resellers know the model", () => {
    const file = slimVision(API);
    expect(file.context!['claude-x-1']).toBe(200000);
    expect(file.context!['open-model']).toBe(131072);
  });

  it('ignores nonsense values', () => {
    expect(slimVision(API).context!['tiny']).toBeUndefined();
  });

  it('is asked first, then the table and the rules, then the default', () => {
    setVisionCatalogs(new VisionCatalog(slimVision(API)));
    expect(getModelContextInfo('claude-x-1')).toEqual({ limit: 200000, source: 'catalogue' });
    expect(getModelContextInfo('claude-x.1')).toEqual({ limit: 200000, source: 'catalogue' });   // written differently
    expect(getModelContextInfo('gemini-9-pro')).toEqual({ limit: 1048576, source: 'rule' });
    expect(getModelContextInfo('something-never-seen')).toEqual({ limit: 128000, source: 'default' });
    expect(getModelContextLimit('claude-x-1')).toBe(200000);
  });

  it('the shipped snapshot knows recent models that the old table and rules got wrong', () => {
    setVisionCatalogs(null, null);
    for (const model of ['deepseek-v4.1-flash', 'gpt-6.1-terra', 'mimo-v2.6-flash', 'qwen3.8-flash']) {
      const info = getModelContextInfo(model);
      expect(info.source, model).toBe('catalogue');
      expect(info.limit, model).toBeGreaterThan(500_000);
    }
  });
});
