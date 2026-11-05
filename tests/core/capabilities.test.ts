import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import fs from 'fs/promises';
import os from 'os';
import path from 'path';
import {
  Catalog,
  CapabilityStore,
  bundledCatalog,
  capabilityStore,
  catalogInfo,
  dataFromModelEntry,
  dataFromOptions,
  installCapabilities,
  loadCapabilityCache,
  lookupReasoningData,
  normalizeModelKey,
  probeEndpoint,
  refreshCapabilities,
  refreshCatalog,
  setCatalogs,
  slimCatalog,
} from '../../src/core/capabilities.js';
import { effectiveReasoning, setReasoningDataLookup } from '../../src/core/reasoning.js';

const api = {
  openai: {
    models: {
      o3: { reasoning: true, reasoning_options: [{ type: 'effort', values: ['low', 'medium', 'high'] }] },
      'o4-mini': { reasoning_options: [{ type: 'effort', values: ['low', 'medium', 'high'] }] }, // same shape as o3
      'gpt-5.2': { reasoning_options: [{ type: 'effort', values: ['none', 'low', 'medium', 'high', 'xhigh'] }] },
      'gpt-4o': { reasoning: false },
      'GPT-Mixed-Case': { reasoning_options: [{ type: 'toggle' }] },
    },
  },
  anthropic: { models: { 'claude-sonnet-4-5': { reasoning_options: [{ type: 'budget_tokens', min: 1024 }] } } },
  google: {
    models: {
      'gemini-2.5-flash': { reasoning_options: [{ type: 'toggle' }, { type: 'budget_tokens', min: 0, max: 24576 }] },
      'junk-model': { reasoning_options: [{ type: 'telepathy' }, 'nonsense', null, { type: 'effort' }] },
    },
  },
  openrouter: { models: { 'anthropic/claude-sonnet-4.5': { reasoning_options: [{ type: 'toggle' }] } } },
  gateway: { models: { o3: { reasoning_options: [{ type: 'toggle' }] } } },
};

let tmp: string;
beforeEach(async () => {
  tmp = await fs.mkdtemp(path.join(os.tmpdir(), 'devx-cap-'));
  capabilityStore.clear();
});
afterEach(async () => {
  vi.unstubAllGlobals();
  setReasoningDataLookup(null);
  setCatalogs(null, null);
  await fs.rm(tmp, { recursive: true, force: true });
});

describe('slimCatalog', () => {
  it('keeps the reasoning options of each model and shares identical shapes', () => {
    const file = slimCatalog(api, new Date('2026-11-05T12:00:00Z'));
    expect(file.version).toBe(1);
    expect(file.generated).toBe('2026-11-05');
    expect(file.source).toBe('https://models.dev/api.json');
    expect(file.models.openai.o3).toBe(file.models.openai['o4-mini']); // one shape for both
    expect(file.shapes[file.models.openai.o3]).toEqual([{ type: 'effort', values: ['low', 'medium', 'high'] }]);
    expect(file.models.google['gemini-2.5-flash']).toBeDefined();
  });

  it('leaves out models without options and options it does not understand', () => {
    const file = slimCatalog(api);
    expect(file.models.openai['gpt-4o']).toBeUndefined();
    expect(file.models.google['junk-model']).toBeUndefined();
    expect(slimCatalog({}).shapes).toEqual([]);
    expect(slimCatalog(null as any).models).toEqual({});
  });

  it('stores model ids in lower case', () => {
    expect(slimCatalog(api).models.openai['gpt-mixed-case']).toBeDefined();
  });
});

describe('model keys', () => {
  it('make different spellings of one model equal', () => {
    const key = 'claude-sonnet-4-5';
    for (const id of ['claude-sonnet-4.5', 'anthropic/claude-sonnet-4.5', 'claude-sonnet-4-5-20250929', 'Claude-Sonnet-4-5-latest', 'anthropic/claude-sonnet-4.5:thinking']) {
      expect(normalizeModelKey(id), id).toBe(key);
    }
    expect(normalizeModelKey('gpt-5.2')).toBe('gpt-5-2');
    expect(normalizeModelKey('gpt-5')).not.toBe(normalizeModelKey('gpt-5.2'));
    expect(normalizeModelKey('')).toBe('');
  });
});

describe('Catalog', () => {
  const catalog = new Catalog(slimCatalog(api));

  it('finds a model on the given provider first', () => {
    expect(catalog.lookup('openai', 'o3')?.efforts).toEqual(['low', 'medium', 'high']);
    expect(catalog.lookup('gateway', 'o3')).toMatchObject({ toggle: true, source: 'catalog' }); // the gateway's own entry
    expect(catalog.lookup('openrouter', 'anthropic/claude-sonnet-4.5')?.toggle).toBe(true);
  });

  it('finds it by a normalised name on the same provider', () => {
    expect(catalog.lookup('anthropic', 'claude-sonnet-4-5-20250929')?.budget).toEqual({ min: 1024, max: undefined });
    expect(catalog.lookup('openai', 'GPT-5.2')?.efforts).toContain('xhigh');
  });

  it('falls back to other providers and prefers the maker of the model', () => {
    // "custom" is not in the catalogue: o3 exists on openai and gateway, openai is the maker
    expect(catalog.lookup('custom', 'o3')?.efforts).toEqual(['low', 'medium', 'high']);
    expect(catalog.lookup(undefined, 'o3')?.efforts).toBeDefined();
    expect(catalog.lookup('custom', 'claude-sonnet-4.5')?.budget).toBeDefined(); // found under anthropic
  });

  it('knows nothing about unknown models, and survives bad input', () => {
    expect(catalog.lookup('openai', 'no-such-model')).toBeUndefined();
    expect(catalog.lookup('openai', '')).toBeUndefined();
    expect(new Catalog(null).lookup('openai', 'o3')).toBeUndefined();
    expect(new Catalog({ version: 2 } as any).size).toBe(0);
    expect(new Catalog({ version: 1, generated: '', source: '', shapes: [], models: { x: { y: 5 } } }).size).toBe(0);
    expect(catalog.size).toBeGreaterThan(5);
  });

  it('turns options into data', () => {
    expect(dataFromOptions([{ type: 'effort', values: ['low'] }, { type: 'effort', values: ['low', 'high'] }, { type: 'toggle' }, { type: 'budget_tokens', min: 1, max: 9 }]))
      .toEqual({ source: 'catalog', efforts: ['low', 'high'], toggle: true, budget: { min: 1, max: 9 } });
  });
});

describe('the catalogue shipped with devx', () => {
  it('is there and knows the well known models', () => {
    const shipped = bundledCatalog();
    expect(shipped.size).toBeGreaterThan(1000);
    expect(shipped.generated).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(shipped.lookup('openai', 'o3')?.efforts).toContain('high');
    expect(shipped.lookup('openai', 'gpt-5')?.efforts).toBeDefined();
    expect(shipped.lookup('anthropic', 'claude-sonnet-4-5')?.budget).toBeDefined();
    expect(shipped.lookup('openai', 'gpt-4o')).toBeUndefined(); // does not reason
  });
});

describe('reading one entry of a model list', () => {
  const read = (entry: any) => dataFromModelEntry(entry);

  it('understands the OpenRouter schema', () => {
    expect(read({ id: 'openai/gpt-5', reasoning: { mandatory: true, supported_efforts: ['high', 'medium', 'low', 'minimal'], default_effort: 'medium' } }))
      .toEqual({ source: 'endpoint', supported: true, efforts: ['high', 'medium', 'low', 'minimal'], mandatory: true });
    expect(read({ reasoning: { mandatory: true } })).toEqual({ source: 'endpoint', supported: true, mandatory: true });
    expect(read({ reasoning: { mandatory: false } })).toEqual({ source: 'endpoint', supported: true, toggle: true });
    expect(read({ reasoning: { mandatory: false, supports_max_tokens: true } })).toEqual({ source: 'endpoint', supported: true, budget: {} });
  });

  it('understands models.dev style options', () => {
    expect(read({ reasoning_options: [{ type: 'effort', values: ['low', 'high'] }] })).toEqual({ source: 'endpoint', efforts: ['low', 'high'] });
  });

  it('reads supported_parameters', () => {
    expect(read({ supported_parameters: ['tools', 'reasoning_effort'] })).toMatchObject({ supported: true, efforts: ['low', 'medium', 'high'] });
    expect(read({ supported_parameters: ['tools', 'reasoning', 'include_reasoning'] })).toEqual({ source: 'endpoint', supported: true });
    expect(read({ supported_parameters: ['tools', 'max_tokens', 'temperature'] })).toEqual({ source: 'endpoint', none: true });
    expect(read({ supported_parameters: [] })).toBeNull(); // an empty list says nothing
  });

  it('reads capability lists (Ollama, LM Studio, gateways)', () => {
    expect(read({ capabilities: ['completion', 'tools', 'thinking'] })).toEqual({ source: 'endpoint', supported: true, toggle: true });
    expect(read({ capabilities: ['completion', 'tools'] })).toEqual({ source: 'endpoint', none: true });
    expect(read({ capabilities: { reasoning: true, vision: false } })).toMatchObject({ supported: true });
    expect(read({ capabilities: { vision: true } })).toEqual({ source: 'endpoint', none: true });
    expect(read({ capabilities: [] })).toBeNull();
  });

  it('reads plain flags', () => {
    expect(read({ supports_reasoning: true })).toEqual({ source: 'endpoint', supported: true });
    expect(read({ supports_reasoning: false })).toEqual({ source: 'endpoint', none: true });
    expect(read({ reasoning: true })).toEqual({ source: 'endpoint', supported: true });
    expect(read({ thinking: false })).toEqual({ source: 'endpoint', none: true });
  });

  it('says nothing for entries without reasoning information, or for garbage', () => {
    expect(read({ id: 'm', object: 'model', owned_by: 'me' })).toBeNull();
    for (const junk of [null, undefined, 5, 'text', [], true]) expect(read(junk as any), String(junk)).toBeNull();
  });
});

/** A fake server: maps "METHOD path" to a JSON answer; everything else is a 404. */
function fakeServer(routes: Record<string, unknown>) {
  const calls: Array<{ url: string; method: string; headers: Record<string, string>; body?: string }> = [];
  const impl = vi.fn(async (url: string, init: any = {}) => {
    const method = init.method || 'GET';
    calls.push({ url, method, headers: init.headers || {}, body: init.body });
    const key = `${method} ${new URL(url).pathname}`;
    if (key in routes) return new Response(JSON.stringify(routes[key]), { status: 200 });
    return new Response('not found', { status: 404 });
  });
  return { impl: impl as unknown as typeof fetch, calls };
}

describe('probeEndpoint', () => {
  const base = 'https://gateway.example.com/v1';

  it('reads the entry of the model in the list the endpoint already serves', async () => {
    const { impl, calls } = fakeServer({
      'GET /v1/models': { data: [{ id: 'other' }, { id: 'vendor/Fancy-Model', reasoning: { mandatory: false, supported_efforts: ['low', 'high'] } }] },
    });
    const data = await probeEndpoint({ baseUrl: base, apiKey: 'sk-test', model: 'vendor/fancy-model', fetchImpl: impl });
    expect(data).toMatchObject({ source: 'endpoint', efforts: ['low', 'high'] });
    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe('https://gateway.example.com/v1/models');
    expect(calls[0].headers.Authorization).toBe('Bearer sk-test');
  });

  it('copes with the different shapes of a model list and with a "models/" prefix', async () => {
    for (const body of [
      [{ id: 'm', supports_reasoning: true }],
      { data: [{ id: 'models/m', supports_reasoning: true }] },
      { models: [{ name: 'm', supports_reasoning: true }] },
    ]) {
      const { impl } = fakeServer({ 'GET /v1/models': body });
      expect(await probeEndpoint({ baseUrl: base, model: 'm', fetchImpl: impl }), JSON.stringify(body)).toMatchObject({ supported: true });
    }
  });

  it('sends no Authorization header without a key or with the Ollama placeholder', async () => {
    const { impl, calls } = fakeServer({});
    await probeEndpoint({ baseUrl: base, model: 'm', fetchImpl: impl });
    await probeEndpoint({ baseUrl: base, model: 'm', apiKey: 'ollama', fetchImpl: impl });
    for (const call of calls) expect(call.headers.Authorization).toBeUndefined();
  });

  it('asks a local Ollama for the capabilities of the model', async () => {
    const yes = fakeServer({ 'GET /v1/models': { data: [{ id: 'qwen3:8b' }] }, 'POST /api/show': { capabilities: ['completion', 'tools', 'thinking'] } });
    expect(await probeEndpoint({ baseUrl: 'http://127.0.0.1:11434/v1', model: 'qwen3:8b', fetchImpl: yes.impl }))
      .toEqual({ source: 'endpoint', supported: true, toggle: true });
    expect(JSON.parse(yes.calls.find((c) => c.method === 'POST')!.body!)).toEqual({ model: 'qwen3:8b' });

    const no = fakeServer({ 'GET /v1/models': { data: [{ id: 'llama3.2' }] }, 'POST /api/show': { capabilities: ['completion'] } });
    expect(await probeEndpoint({ baseUrl: 'http://localhost:11434/v1', model: 'llama3.2', fetchImpl: no.impl })).toEqual({ source: 'endpoint', none: true });
  });

  it('does not call Ollama endpoints on a remote host (only the model list, and a LiteLLM check)', async () => {
    const { impl, calls } = fakeServer({});
    await probeEndpoint({ baseUrl: base, model: 'm', fetchImpl: impl });
    expect(calls.map((c) => `${c.method} ${new URL(c.url).pathname}`)).toEqual(['GET /v1/models', 'GET /model/info']);
  });

  it('recognises a LiteLLM proxy', async () => {
    const { impl } = fakeServer({
      'GET /model/info': { data: [{ model_name: 'gpt-x', model_info: { supports_reasoning: true } }, { model_name: 'other', model_info: { supports_reasoning: false } }] },
    });
    expect(await probeEndpoint({ baseUrl: 'https://proxy.example.com/v1', model: 'gpt-x', fetchImpl: impl })).toEqual({ source: 'endpoint', supported: true });
    expect(await probeEndpoint({ baseUrl: 'https://proxy.example.com/v1', model: 'other', fetchImpl: impl })).toBeNull();
  });

  it('only ever talks to the origin of the configured base URL', async () => {
    const { impl, calls } = fakeServer({});
    await probeEndpoint({ baseUrl: 'http://192.168.1.50:8000/v1', apiKey: 'secret', model: 'm', fetchImpl: impl });
    await probeEndpoint({ baseUrl: base, apiKey: 'secret', model: 'm', fetchImpl: impl });
    for (const call of calls) {
      expect(['http://192.168.1.50:8000', 'https://gateway.example.com']).toContain(new URL(call.url).origin);
    }
  });

  it('gives up quietly when the server is down, slow or answers nonsense', async () => {
    const down = vi.fn(async () => { throw new TypeError('fetch failed'); }) as unknown as typeof fetch;
    expect(await probeEndpoint({ baseUrl: base, model: 'm', fetchImpl: down })).toBeNull();
    const junk = vi.fn(async () => new Response('<html>oops</html>', { status: 200 })) as unknown as typeof fetch;
    expect(await probeEndpoint({ baseUrl: base, model: 'm', fetchImpl: junk })).toBeNull();
    expect(await probeEndpoint({ baseUrl: 'not a url', model: 'm', fetchImpl: down })).toBeNull();
    const slow = vi.fn((_url: string, init: any) => new Promise((_resolve, reject) => {
      init.signal.addEventListener('abort', () => reject(new DOMException('timeout', 'TimeoutError')));
    })) as unknown as typeof fetch;
    const started = Date.now();
    expect(await probeEndpoint({ baseUrl: base, model: 'm', fetchImpl: slow, timeoutMs: 80 })).toBeNull();
    expect(Date.now() - started).toBeLessThan(3000);
  });
});

describe('CapabilityStore', () => {
  it('matches keys regardless of case and a trailing slash', () => {
    const store = new CapabilityStore();
    store.set('https://Gateway.example.com/v1/', 'Fancy-Model', { at: 1, data: null });
    expect(store.get('https://gateway.example.com/v1', 'fancy-model')).toBeDefined();
    store.delete('https://gateway.example.com/v1', 'FANCY-MODEL');
    expect(store.get('https://gateway.example.com/v1', 'fancy-model')).toBeUndefined();
  });

  it('keeps a positive answer for a day and a negative one for six hours', () => {
    const store = new CapabilityStore();
    const now = 1_000_000_000;
    store.set('http://x/v1', 'a', { at: now, data: { source: 'endpoint', supported: true } });
    store.set('http://x/v1', 'b', { at: now, data: null });
    expect(store.isFresh('http://x/v1', 'a', now + 23 * 3600_000)).toBe(true);
    expect(store.isFresh('http://x/v1', 'a', now + 25 * 3600_000)).toBe(false);
    expect(store.isFresh('http://x/v1', 'b', now + 5 * 3600_000)).toBe(true);
    expect(store.isFresh('http://x/v1', 'b', now + 7 * 3600_000)).toBe(false);
    expect(store.isFresh('http://x/v1', 'missing', now)).toBe(false);
  });

  it('survives a round trip through JSON and ignores a damaged file', () => {
    const a = new CapabilityStore();
    a.set('http://x/v1', 'm', { at: 5, data: { source: 'endpoint', toggle: true } });
    const b = new CapabilityStore();
    b.load(JSON.parse(JSON.stringify(a.toJSON())));
    expect(b.get('http://x/v1', 'm')).toEqual({ at: 5, data: { source: 'endpoint', toggle: true } });
    const c = new CapabilityStore();
    c.load({ version: 9 });
    c.load(null);
    c.load({ version: 1, endpoints: { k: { nope: true } } });
    expect(c.toJSON().endpoints).toEqual({});
  });
});

describe('refreshCapabilities', () => {
  const custom = { baseUrl: 'https://gateway.example.com/v1', model: 'fancy', apiKey: 'k' };
  const entry = { 'GET /v1/models': { data: [{ id: 'fancy', reasoning: { mandatory: false } }] } };

  it('asks a custom endpoint once and remembers the answer', async () => {
    const { impl, calls } = fakeServer(entry);
    const first = await refreshCapabilities(custom, { fetchImpl: impl, dir: tmp });
    expect(first?.data).toMatchObject({ toggle: true });
    const second = await refreshCapabilities(custom, { fetchImpl: impl, dir: tmp });
    expect(second?.data).toMatchObject({ toggle: true });
    expect(calls).toHaveLength(1); // the second call used the stored answer
    await refreshCapabilities(custom, { fetchImpl: impl, dir: tmp, force: true });
    expect(calls).toHaveLength(2);
  });

  it('asks OpenRouter, but never the big vendors (the catalogue is enough there)', async () => {
    const { impl, calls } = fakeServer({});
    for (const baseUrl of ['https://api.openai.com/v1', 'https://api.anthropic.com/v1', 'https://generativelanguage.googleapis.com/v1beta/openai/',
      'https://dashscope-intl.aliyuncs.com/compatible-mode/v1', 'https://api.deepseek.com', 'https://api.z.ai/api/paas/v4']) {
      expect(await refreshCapabilities({ baseUrl, model: 'm' }, { fetchImpl: impl, dir: tmp }), baseUrl).toBeNull();
    }
    expect(calls).toHaveLength(0);
    await refreshCapabilities({ baseUrl: 'https://openrouter.ai/api/v1', model: 'openai/o3' }, { fetchImpl: impl, dir: tmp });
    expect(calls.length).toBeGreaterThan(0);
  });

  it('can be switched off in the config, and does nothing without a model or a URL', async () => {
    const { impl, calls } = fakeServer(entry);
    expect(await refreshCapabilities({ ...custom, probeEndpoints: false }, { fetchImpl: impl, dir: tmp })).toBeNull();
    expect(await refreshCapabilities({ baseUrl: custom.baseUrl }, { fetchImpl: impl, dir: tmp })).toBeNull();
    expect(await refreshCapabilities({ model: 'm' }, { fetchImpl: impl, dir: tmp })).toBeNull();
    expect(calls).toHaveLength(0);
  });

  it('shares one request between simultaneous callers', async () => {
    const { impl, calls } = fakeServer(entry);
    await Promise.all([1, 2, 3, 4].map(() => refreshCapabilities(custom, { fetchImpl: impl, dir: tmp, persist: false })));
    expect(calls.filter((c) => c.url.endsWith('/v1/models'))).toHaveLength(1);
  });

  it('remembers that an endpoint said nothing, so it is not asked again at every start', async () => {
    const { impl, calls } = fakeServer({});
    const record = await refreshCapabilities(custom, { fetchImpl: impl, dir: tmp, persist: false });
    expect(record).toMatchObject({ data: null });
    const before = calls.length;
    await refreshCapabilities(custom, { fetchImpl: impl, dir: tmp, persist: false });
    expect(calls.length).toBe(before);
  });

  it('stores the answers on disk, for the next run', async () => {
    const { impl } = fakeServer(entry);
    await refreshCapabilities(custom, { fetchImpl: impl, dir: tmp });
    const file = JSON.parse(await fs.readFile(path.join(tmp, 'capabilities.json'), 'utf8'));
    expect(file.version).toBe(1);
    capabilityStore.clear();
    loadCapabilityCache(tmp);
    expect(capabilityStore.get(custom.baseUrl, custom.model)?.data).toMatchObject({ toggle: true });
    if (process.platform !== 'win32') {
      expect((await fs.stat(path.join(tmp, 'capabilities.json'))).mode & 0o777).toBe(0o600);
    }
  });
});

describe('lookupReasoningData', () => {
  const config = { model: 'o3', baseUrl: 'https://gateway.example.com/v1', provider: 'openai' };
  beforeEach(() => setCatalogs(new Catalog(slimCatalog(api))));

  it('uses the catalogue when the endpoint has said nothing', () => {
    expect(lookupReasoningData(config)).toMatchObject({ source: 'catalog', efforts: ['low', 'medium', 'high'] });
    expect(lookupReasoningData({ ...config, model: 'unheard-of' })).toBeUndefined();
    expect(lookupReasoningData({ ...config, model: '' })).toBeUndefined();
  });

  it('prefers what the endpoint reports', () => {
    capabilityStore.set(config.baseUrl, 'o3', { at: Date.now(), data: { source: 'endpoint', efforts: ['low', 'high'] } });
    expect(lookupReasoningData(config)).toEqual({ source: 'endpoint', efforts: ['low', 'high'] });
  });

  it('completes an endpoint that only says "it reasons" with the details of the catalogue', () => {
    capabilityStore.set(config.baseUrl, 'o3', { at: Date.now(), data: { source: 'endpoint', supported: true, mandatory: true } });
    expect(lookupReasoningData(config)).toMatchObject({ source: 'catalog', efforts: ['low', 'medium', 'high'], mandatory: true });
  });

  it('lets an explicit "no reasoning" win over the other source', () => {
    capabilityStore.set(config.baseUrl, 'o3', { at: Date.now(), data: { source: 'endpoint', none: true } });
    expect(lookupReasoningData(config)).toEqual({ source: 'endpoint', none: true });
  });

  it('keeps what the endpoint says when the catalogue has never heard of the model', () => {
    capabilityStore.set(config.baseUrl, 'brand-new', { at: Date.now(), data: { source: 'endpoint', supported: true, mandatory: true } });
    expect(lookupReasoningData({ ...config, model: 'brand-new' })).toEqual({ source: 'endpoint', supported: true, mandatory: true });
  });
});

describe('refreshCatalog', () => {
  const serve = (body: unknown, status = 200) => vi.fn(async () => new Response(JSON.stringify(body), { status })) as unknown as typeof fetch;

  it('downloads, slims and stores the catalogue, and uses it from then on', async () => {
    setCatalogs(new Catalog(null));
    const result = await refreshCatalog({ fetchImpl: serve(api), dir: tmp });
    expect(result).toMatchObject({ providers: 5, models: 8 });
    expect(result.bytes).toBeGreaterThan(100);
    const saved = JSON.parse(await fs.readFile(path.join(tmp, 'reasoning-catalog.json'), 'utf8'));
    expect(saved.version).toBe(1);
    expect(lookupReasoningData({ model: 'o3', provider: 'openai', baseUrl: 'https://api.openai.com/v1' })?.efforts).toBeDefined();
    expect(catalogInfo().downloaded).toBe(true);
  });

  it('refuses a bad answer and keeps what it had', async () => {
    setCatalogs(new Catalog(slimCatalog(api)));
    await expect(refreshCatalog({ fetchImpl: serve({}, 503), dir: tmp })).rejects.toThrow(/HTTP 503/);
    await expect(refreshCatalog({ fetchImpl: serve({ openai: { models: {} } }), dir: tmp })).rejects.toThrow(/no reasoning data/);
    await expect(fs.stat(path.join(tmp, 'reasoning-catalog.json'))).rejects.toThrow();
    expect(lookupReasoningData({ model: 'o3', provider: 'openai' })).toBeDefined();
    expect(catalogInfo().downloaded).toBe(false);
  });
});

describe('installCapabilities', () => {
  it('connects the stored answers and the catalogue to the reasoning resolver', async () => {
    await fs.writeFile(path.join(tmp, 'reasoning-catalog.json'), JSON.stringify(slimCatalog({
      gateway2: { models: { 'brand-new-model': { reasoning_options: [{ type: 'effort', values: ['low', 'medium', 'high', 'xhigh'] }] } } },
    })));
    await fs.writeFile(path.join(tmp, 'capabilities.json'), JSON.stringify({
      version: 1,
      endpoints: { [CapabilityStore.key('http://127.0.0.1:9000/v1', 'local-model')]: { at: Date.now(), data: { source: 'endpoint', toggle: true } } },
    }));
    installCapabilities(tmp);

    const fromCatalog = effectiveReasoning({ model: 'brand-new-model', baseUrl: 'https://api.example.com/v1', provider: 'gateway2' });
    expect(fromCatalog.caps.levels).toEqual(['low', 'normal', 'high', 'xhigh']);
    expect(fromCatalog.caps.source).toBe('catalog');

    const fromEndpoint = effectiveReasoning({ model: 'local-model', baseUrl: 'http://127.0.0.1:9000/v1', provider: 'custom' });
    expect(fromEndpoint.caps.levels).toEqual(['off', 'normal']);
    expect(fromEndpoint.caps.source).toBe('endpoint');
  });

  it('without it, only the rules are used (nothing is read behind the scenes)', () => {
    expect(effectiveReasoning({ model: 'brand-new-model', baseUrl: 'https://api.example.com/v1' }).caps.supported).toBe(false);
  });
});
