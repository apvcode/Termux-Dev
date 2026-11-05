import { afterEach, describe, expect, it } from 'vitest';
import { CostLedger, computeCost, formatMoney, isFreeModel, priceFromModelEntry, reportedCost } from '../../src/core/cost.js';
import {
  PriceCatalog, bundledPrices, priceUsage, resolvePrice, setEndpointPriceLookup, setPriceCatalogs, slimPrices,
} from '../../src/core/prices.js';
import { openAiUsage } from '../../src/providers/shared.js';

const API = {
  anthropic: { models: { 'claude-sonnet-4-5': { cost: { input: 3, output: 15, cache_read: 0.3, cache_write: 3.75 } } } },
  openai: { models: { 'gpt-5': { cost: { input: 1.25, output: 10, cache_read: 0.125 } } } },
  openrouter: { models: { 'vendor/some-model': { cost: { input: 1, output: 2 } } } },
  resellerA: { models: { 'big-model': { cost: { input: 1, output: 1 } } } },
  resellerB: { models: { 'big-model': { cost: { input: 5, output: 5 } } } },
  resellerC: { models: { 'big-model': { cost: { input: 9, output: 9 } } } },
  tiered: {
    models: {
      'long-ctx': {
        cost: { input: 2, output: 6, tiers: [{ input: 4, output: 12, tier: { type: 'context', size: 200000 } }] },
      },
    },
  },
  mirror: { models: { 'gpt-5': { cost: { input: 1.25, output: 10, cache_read: 0.125 } } } },
  freetier: { models: { 'only-free-here': { cost: { input: 0, output: 0 } }, 'big-model': { cost: { input: 0, output: 0 } } } },
  broken: { models: { bad: { cost: { input: 'x' } }, none: {} } },
};

afterEach(() => {
  setPriceCatalogs(null, null);
  setEndpointPriceLookup(null);
});

const use = () => setPriceCatalogs(new PriceCatalog(slimPrices(API)));

describe('computeCost', () => {
  it('prices plain tokens per million', () => {
    expect(computeCost({ input: 3, output: 15 }, { promptTokens: 1000, completionTokens: 500 })).toBeCloseTo(0.0105, 6);
  });

  it('prices cached input at the cache rates, and the rest as normal input', () => {
    const rates = { input: 3, output: 15, cacheRead: 0.3, cacheWrite: 3.75 };
    const cost = computeCost(rates, { promptTokens: 10000, completionTokens: 0, cacheReadTokens: 6000, cacheWriteTokens: 1000 });
    expect(cost).toBeCloseTo((3000 * 3 + 6000 * 0.3 + 1000 * 3.75) / 1e6, 8);
  });

  it('charges cached tokens as input when the model has no cache price', () => {
    const cost = computeCost({ input: 2, output: 4 }, { promptTokens: 1000, completionTokens: 0, cacheReadTokens: 400 });
    expect(cost).toBeCloseTo(0.002, 8);
  });

  it('never counts more cached tokens than there are prompt tokens', () => {
    const cost = computeCost({ input: 1, output: 0, cacheRead: 0.1 }, { promptTokens: 100, completionTokens: 0, cacheReadTokens: 900 });
    expect(cost).toBeCloseTo((100 * 0.1) / 1e6, 10);
  });

  it('uses the long-context tier above its threshold', () => {
    const rates = { input: 2, output: 6, tier: { over: 200000, input: 4, output: 12 } };
    expect(computeCost(rates, { promptTokens: 100000, completionTokens: 1000 })).toBeCloseTo((100000 * 2 + 1000 * 6) / 1e6, 8);
    expect(computeCost(rates, { promptTokens: 300000, completionTokens: 1000 })).toBeCloseTo((300000 * 4 + 1000 * 12) / 1e6, 8);
  });

  it('prices reasoning tokens separately when the model has such a price', () => {
    const cost = computeCost({ input: 1, output: 2, reasoning: 10 }, { promptTokens: 0, completionTokens: 1000, reasoningTokens: 400 });
    expect(cost).toBeCloseTo((600 * 2 + 400 * 10) / 1e6, 8);
  });
});

describe('prices listed by an endpoint', () => {
  it('reads the OpenRouter schema (USD per token, as strings)', () => {
    const rates = priceFromModelEntry({ id: 'x', pricing: { prompt: '0.000003', completion: '0.000015', input_cache_read: '0.0000003' } });
    expect(rates?.input).toBeCloseTo(3, 6);
    expect(rates?.output).toBeCloseTo(15, 6);
    expect(rates?.cacheRead).toBeCloseTo(0.3, 6);
  });

  it('reads the LiteLLM schema', () => {
    const rates = priceFromModelEntry({ model_name: 'x', model_info: { input_cost_per_token: 0.000001, output_cost_per_token: 0.000002 } });
    expect(rates).toMatchObject({ input: 1, output: 2 });
  });

  it('does not treat a variable (negative) or missing price as a price', () => {
    expect(priceFromModelEntry({ pricing: { prompt: '-1', completion: '-1' } })).toBeUndefined();
    expect(priceFromModelEntry({ id: 'x' })).toBeUndefined();
    expect(priceFromModelEntry(null)).toBeUndefined();
  });
});

describe('reportedCost', () => {
  it('finds the cost under the usual names', () => {
    expect(reportedCost({ cost: 0.5 })).toBe(0.5);
    expect(reportedCost({ total_cost: 0.25 })).toBe(0.25);
    expect(reportedCost({ cost: { total: 0.1 } })).toBe(0.1);
    expect(reportedCost({ cost: '0.02' })).toBe(0.02);
  });

  it('ignores anything that is not a usable number', () => {
    expect(reportedCost({ cost: -1 })).toBeUndefined();
    expect(reportedCost({ cost: 'many' })).toBeUndefined();
    expect(reportedCost({ prompt_tokens: 5 })).toBeUndefined();
    expect(reportedCost(undefined)).toBeUndefined();
  });
});

describe('free models', () => {
  it('knows free variants and local servers, and nothing else', () => {
    expect(isFreeModel('vendor/model:free', 'https://openrouter.ai/api/v1')).toBe(true);
    expect(isFreeModel('llama3', 'http://localhost:11434/v1')).toBe(true);
    expect(isFreeModel('llama3', 'http://192.168.1.5:8080/v1')).toBe(true);
    expect(isFreeModel('my-local-finetune', 'https://api.example.com/v1')).toBe(false);
    expect(isFreeModel('deepseek-v4', 'https://api.deepseek.com/v1')).toBe(false);
  });
});

describe('the price catalogue', () => {
  it('keeps one row per distinct price and skips broken entries', () => {
    const file = slimPrices(API);
    expect(Object.keys(file.models)).not.toContain('broken');
    expect(file.rows.length).toBeLessThan(Object.values(file.models).reduce((n, m) => n + Object.keys(m).length, 0));
  });

  it('prefers the maker over resellers and the middle price among resellers only', () => {
    use();
    expect(resolvePrice({ model: 'claude-sonnet-4-5', baseUrl: 'https://gw.example.com/v1' })?.rates.input).toBe(3);
    expect(resolvePrice({ model: 'big-model', baseUrl: 'https://gw.example.com/v1' })?.rates.input).toBe(5);
  });

  it('matches ids written differently', () => {
    use();
    expect(resolvePrice({ model: 'claude-sonnet-4.5', baseUrl: 'https://gw.example.com/v1' })?.rates.output).toBe(15);
    expect(resolvePrice({ model: 'anthropic/claude-sonnet-4-5-20250929', baseUrl: 'https://gw.example.com/v1' })?.rates.output).toBe(15);
  });

  it('does not take a free tier of another provider for the price of the model', () => {
    use();
    expect(resolvePrice({ model: 'only-free-here', baseUrl: 'https://gw.example.com/v1' })).toBeUndefined();
    expect(resolvePrice({ model: 'big-model', baseUrl: 'https://gw.example.com/v1' })?.rates.input).toBe(5);
    expect(resolvePrice({ model: 'only-free-here', baseUrl: 'https://gw.example.com/v1', provider: 'freetier' })?.rates.input).toBe(0);
  });

  it('returns nothing for a model it does not know', () => {
    use();
    expect(resolvePrice({ model: 'unknown-model-xyz', baseUrl: 'https://gw.example.com/v1' })).toBeUndefined();
  });

  it('keeps the long-context tier', () => {
    use();
    expect(resolvePrice({ model: 'long-ctx', baseUrl: 'https://gw.example.com/v1' })?.rates.tier).toMatchObject({ over: 200000, input: 4, output: 12 });
  });

  it('the shipped snapshot has prices for common models', () => {
    const catalogue = bundledPrices();
    expect(catalogue.size).toBeGreaterThan(5000);
    expect(catalogue.lookup(undefined, 'claude-sonnet-4-5')?.rates.input).toBeGreaterThan(0);
    expect(catalogue.lookup(undefined, 'gpt-4o')?.rates.output).toBeGreaterThan(0);
  });
});

describe('which source prices a request', () => {
  it('own prices win, then free, then the endpoint list, then the catalogue', () => {
    use();
    const base = { model: 'claude-sonnet-4-5', baseUrl: 'https://gw.example.com/v1' };
    setEndpointPriceLookup(() => ({ input: 7, output: 8 }));
    expect(resolvePrice(base)).toMatchObject({ source: 'listed', rates: { input: 7 } });
    expect(resolvePrice({ ...base, prices: { 'claude-sonnet-4-5': { input: 1, output: 2 } } })).toMatchObject({ source: 'configured', rates: { input: 1 } });
    expect(resolvePrice({ model: 'claude-sonnet-4-5:free', baseUrl: 'https://openrouter.ai/api/v1' })).toMatchObject({ source: 'free' });
    setEndpointPriceLookup(null);
    expect(resolvePrice(base)).toMatchObject({ source: 'estimated', rates: { input: 3 } });
  });

  it("a vendor's own endpoint is a price list, not an estimate; a gateway is an estimate", () => {
    use();
    expect(resolvePrice({ model: 'claude-sonnet-4-5', baseUrl: 'https://api.anthropic.com' })?.source).toBe('listed');
    expect(resolvePrice({ model: 'gpt-5', baseUrl: 'https://api.openai.com/v1' })?.source).toBe('listed');
    expect(resolvePrice({ model: 'gpt-5', baseUrl: 'https://api.mygateway.com/v1' })?.source).toBe('estimated');
  });
});

describe('priceUsage', () => {
  const ctx = { model: 'claude-sonnet-4-5', baseUrl: 'https://gw.example.com/v1' };
  const counts = { promptTokens: 1000, completionTokens: 1000 };

  it('believes the cost the provider reported', () => {
    use();
    expect(priceUsage(ctx, counts, { cost: 0.123 })).toEqual({ cost: 0.123, costSource: 'reported' });
  });

  it('does not believe a reported 0 for a model that costs money', () => {
    use();
    const out = priceUsage(ctx, counts, { cost: 0 });
    expect(out.costSource).toBe('estimated');
    expect(out.cost).toBeCloseTo(0.018, 6);
  });

  it('leaves the cost undefined when nothing knows the model', () => {
    use();
    expect(priceUsage({ model: 'mystery', baseUrl: 'https://gw.example.com/v1' }, counts)).toEqual({});
  });

  it('costs nothing on a local server', () => {
    use();
    expect(priceUsage({ model: 'claude-sonnet-4-5', baseUrl: 'http://localhost:1234/v1' }, counts)).toEqual({ cost: 0, costSource: 'free' });
  });
});

describe('OpenAI usage', () => {
  it('reads cache and reasoning tokens and prices the cached part cheaper', () => {
    use();
    const usage = openAiUsage(
      {
        prompt_tokens: 10000, completion_tokens: 1000, total_tokens: 11000,
        prompt_tokens_details: { cached_tokens: 8000 },
        completion_tokens_details: { reasoning_tokens: 600 },
      },
      { model: 'claude-sonnet-4-5', baseUrl: 'https://gw.example.com/v1' }
    );
    expect(usage.cacheReadTokens).toBe(8000);
    expect(usage.reasoningTokens).toBe(600);
    expect(usage.cost).toBeCloseTo((2000 * 3 + 8000 * 0.3 + 1000 * 15) / 1e6, 8);
    expect(usage.costSource).toBe('estimated');
  });

  it('marks the numbers as estimated when the endpoint sent no token counts', () => {
    use();
    const usage = openAiUsage(null, { model: 'claude-sonnet-4-5', baseUrl: 'https://api.anthropic.com' }, { prompt: 100, completion: 50 });
    expect(usage.tokensEstimated).toBe(true);
    expect(usage.costSource).toBe('estimated');
  });
});

describe('showing the cost', () => {
  it('says what the number is', () => {
    expect(formatMoney(0.0123, { priced: 1 })).toBe('$0.0123');
    expect(formatMoney(0.0123, { estimated: true, priced: 1 })).toBe('~$0.0123');
    expect(formatMoney(0.0123, { priced: 1, unpriced: 2 })).toBe('$0.0123+');
    expect(formatMoney(0.0123, { estimated: true, priced: 1, unpriced: 1 })).toBe('~$0.0123+');
    expect(formatMoney(0, { unpriced: 3, priced: 0 })).toBe('n/a');
    expect(formatMoney(12.5, { priced: 1 })).toBe('$12.50');
  });

  it('a ledger keeps track of estimates and unpriced requests', () => {
    const ledger = new CostLedger();
    expect(ledger.format()).toBe('$0.0000');
    ledger.add({ cost: undefined });
    expect(ledger.format()).toBe('n/a');
    ledger.add({ cost: 0.5, costSource: 'reported' });
    expect(ledger.format()).toBe('$0.5000+');
    ledger.add({ cost: 0.25, costSource: 'estimated' });
    expect(ledger.format()).toBe('~$0.7500+');
    ledger.reset();
    expect(ledger.format()).toBe('$0.0000');
  });
});
