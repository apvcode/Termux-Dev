import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import fs from 'fs';
import os from 'os';
import path from 'path';
import {
  FormatStore, anthropicOnlyModels, discoverFormats, formatFromBaseUrl, formatStore, gatewayRoot, installFormats, refreshFormats, resolveFormat,
} from '../../src/core/formats.js';
import { effectiveReasoning, setRouteResolver } from '../../src/core/reasoning.js';
import { createProvider } from '../../src/providers/index.js';
import { AnthropicProvider } from '../../src/providers/anthropic.js';
import { OpenAIProvider } from '../../src/providers/openai.js';

const GATEWAY = 'https://gw.example.com/v1';
const OPENAI_LIST = { data: [{ id: 'gpt-x' }, { id: 'glm-x' }] };
const ANTHROPIC_LIST = { data: [{ id: 'claude-a' }, { id: 'claude-b' }] };

/** A gateway that gives the OpenAI list to Bearer clients and the Anthropic list to clients that send anthropic-version. */
function gateway(opts: { anthropic?: boolean } = {}) {
  const requests: Array<{ url: string; headers: Record<string, string> }> = [];
  const impl = vi.fn(async (url: string, init: { headers?: Record<string, string> } = {}) => {
    const headers = init.headers ?? {};
    requests.push({ url, headers });
    const anthropicClient = 'anthropic-version' in headers;
    const body = anthropicClient && opts.anthropic !== false ? ANTHROPIC_LIST : OPENAI_LIST;
    return new Response(JSON.stringify(body), { status: 200 });
  });
  return { impl: impl as unknown as typeof fetch, requests };
}

let tmp: string;
beforeEach(() => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'devx-formats-'));
  formatStore.clear();
});
afterEach(() => {
  fs.rmSync(tmp, { recursive: true, force: true });
  formatStore.clear();
  setRouteResolver(null);
  vi.unstubAllGlobals();
});

describe('formats named by the URL', () => {
  it('Anthropic itself and /anthropic/ paths speak the Anthropic format', () => {
    expect(formatFromBaseUrl('https://api.anthropic.com')).toBe('anthropic');
    expect(formatFromBaseUrl('https://api.anthropic.com/v1')).toBe('anthropic');
    expect(formatFromBaseUrl('https://api.minimax.io/anthropic/v1')).toBe('anthropic');
    expect(formatFromBaseUrl('https://open.bigmodel.cn/api/anthropic')).toBe('anthropic');
    expect(formatFromBaseUrl('https://api.openai.com/v1')).toBeNull();
    expect(formatFromBaseUrl('https://gw.example.com/v1')).toBeNull();
  });

  it('only plain gateways (root or /v1) are probed', () => {
    expect(gatewayRoot('https://gw.example.com')).toBe('https://gw.example.com');
    expect(gatewayRoot('https://gw.example.com/v1/')).toBe('https://gw.example.com');
    expect(gatewayRoot('https://gw.example.com/openai/v1')).toBeNull();
    expect(gatewayRoot('nonsense')).toBeNull();
  });
});

describe('discovery', () => {
  it('asks twice, as an OpenAI client and as an Anthropic client, and keeps the models only the second lists', async () => {
    const { impl, requests } = gateway();
    const record = await discoverFormats({ baseUrl: GATEWAY, apiKey: 'sk-secret', fetchImpl: impl });
    expect(record?.openai).toEqual(['gpt-x', 'glm-x']);
    expect(record?.anthropic).toEqual(['claude-a', 'claude-b']);
    expect(requests).toHaveLength(2);
    expect(requests.every((r) => r.url === 'https://gw.example.com/v1/models')).toBe(true);
    const asAnthropic = requests.find((r) => 'anthropic-version' in r.headers)!;
    expect(asAnthropic.headers['x-api-key']).toBe('sk-secret');
    const asOpenAI = requests.find((r) => !('anthropic-version' in r.headers))!;
    expect(asOpenAI.headers['Authorization']).toBe('Bearer sk-secret');
  });

  it('finds no second format on a gateway that ignores the Anthropic headers', async () => {
    const { impl } = gateway({ anthropic: false });
    const record = await discoverFormats({ baseUrl: GATEWAY, apiKey: 'k', fetchImpl: impl });
    expect(record?.anthropic).toEqual([]);
  });

  it('survives an endpoint that answers with errors or nothing', async () => {
    const failing = (async () => new Response('nope', { status: 500 })) as unknown as typeof fetch;
    expect(await discoverFormats({ baseUrl: GATEWAY, apiKey: 'k', fetchImpl: failing })).toBeNull();
    const down = (async () => { throw new Error('offline'); }) as unknown as typeof fetch;
    expect(await discoverFormats({ baseUrl: GATEWAY, apiKey: 'k', fetchImpl: down })).toBeNull();
  });
});

describe('choosing the format of a model', () => {
  const learn = () => formatStore.set('https://gw.example.com', { at: Date.now(), openai: ['gpt-x', 'both'], anthropic: ['claude-a', 'both'] });

  it('uses OpenAI until something is known', () => {
    expect(resolveFormat({ baseUrl: GATEWAY, model: 'claude-a' })).toBe('openai');
  });

  it('sends models that only the Anthropic list has through the Anthropic format', () => {
    learn();
    expect(resolveFormat({ baseUrl: GATEWAY, model: 'claude-a' })).toBe('anthropic');
    expect(resolveFormat({ baseUrl: GATEWAY, model: 'CLAUDE-A' })).toBe('anthropic');
    expect(resolveFormat({ baseUrl: GATEWAY, model: 'gpt-x' })).toBe('openai');
    expect(resolveFormat({ baseUrl: 'https://gw.example.com', model: 'claude-a' })).toBe('anthropic');
  });

  it('prefers the Anthropic format for Claude models that both lists have, OpenAI for the rest', () => {
    formatStore.set('https://gw.example.com', { at: Date.now(), openai: ['claude-both', 'gpt-both'], anthropic: ['claude-both', 'gpt-both'] });
    expect(resolveFormat({ baseUrl: GATEWAY, model: 'claude-both' })).toBe('anthropic');
    expect(resolveFormat({ baseUrl: GATEWAY, model: 'gpt-both' })).toBe('openai');
  });

  it('guesses for a Claude model typed by hand only when the gateway keeps its Claude models in the Anthropic list', () => {
    learn();
    expect(resolveFormat({ baseUrl: GATEWAY, model: 'claude-brand-new' })).toBe('anthropic');
    expect(resolveFormat({ baseUrl: GATEWAY, model: 'gpt-brand-new' })).toBe('openai');
    formatStore.set('https://gw.example.com', { at: Date.now(), openai: ['claude-old'], anthropic: ['claude-a'] });
    expect(resolveFormat({ baseUrl: GATEWAY, model: 'claude-brand-new' })).toBe('openai');
  });

  it('the config can force a format', () => {
    learn();
    expect(resolveFormat({ baseUrl: GATEWAY, model: 'claude-a', apiFormat: 'openai' })).toBe('openai');
    expect(resolveFormat({ baseUrl: GATEWAY, model: 'gpt-x', apiFormat: 'anthropic' })).toBe('anthropic');
  });

  it('lists the models that are reachable only through the Anthropic format', () => {
    learn();
    expect(anthropicOnlyModels(GATEWAY)).toEqual(['claude-a', 'both']);
  });
});

describe('refreshing', () => {
  it('learns a gateway once, stores only model ids, and does not ask again while fresh', async () => {
    const { impl, requests } = gateway();
    await refreshFormats({ baseUrl: GATEWAY, apiKey: 'sk-secret-key' }, { fetchImpl: impl, dir: tmp });
    await refreshFormats({ baseUrl: GATEWAY, apiKey: 'sk-secret-key' }, { fetchImpl: impl, dir: tmp });
    expect(requests).toHaveLength(2);
    const saved = fs.readFileSync(path.join(tmp, 'formats.json'), 'utf8');
    expect(saved).toContain('claude-a');
    expect(saved).not.toContain('sk-secret-key');
    expect(fs.statSync(path.join(tmp, 'formats.json')).mode & 0o077).toBe(0);
  });

  it('is remembered by the next run', async () => {
    const { impl } = gateway();
    await refreshFormats({ baseUrl: GATEWAY, apiKey: 'k' }, { fetchImpl: impl, dir: tmp });
    formatStore.clear();
    installFormats(tmp);
    expect(resolveFormat({ baseUrl: GATEWAY, model: 'claude-a' })).toBe('anthropic');
  });

  it('never asks vendors, local servers, own-layout gateways or when probing is off', async () => {
    const { impl, requests } = gateway();
    for (const baseUrl of ['https://api.openai.com/v1', 'https://openrouter.ai/api/v1', 'https://api.anthropic.com', 'http://localhost:11434/v1', 'https://gw.example.com/openai/v1']) {
      await refreshFormats({ baseUrl, apiKey: 'k' }, { fetchImpl: impl, dir: tmp, persist: false });
    }
    await refreshFormats({ baseUrl: GATEWAY, apiKey: 'k', probeEndpoints: false }, { fetchImpl: impl, dir: tmp, persist: false });
    await refreshFormats({ baseUrl: GATEWAY, apiKey: 'k', apiFormat: 'openai' }, { fetchImpl: impl, dir: tmp, persist: false });
    expect(requests).toHaveLength(0);
  });
});

describe('the provider that is created', () => {
  const config = { baseUrl: GATEWAY, apiKey: 'k', model: 'claude-a' };

  it('is OpenAI for an ordinary model and Anthropic for a model of the Anthropic list', () => {
    formatStore.set('https://gw.example.com', { at: Date.now(), openai: ['gpt-x'], anthropic: ['claude-a'] });
    expect(createProvider({ ...config, model: 'gpt-x' })).toBeInstanceOf(OpenAIProvider);
    expect(createProvider(config)).toBeInstanceOf(AnthropicProvider);
    expect(createProvider({ baseUrl: 'https://api.anthropic.com', apiKey: 'k', model: 'claude-x' })).toBeInstanceOf(AnthropicProvider);
  });

  it('writes the reasoning setting the way the Anthropic API wants it, even on a gateway', () => {
    installFormats(tmp);
    formatStore.set('https://gw.example.com', { at: Date.now(), openai: ['gpt-x'], anthropic: ['claude-sonnet-4-5'] });
    const cfg = { baseUrl: GATEWAY, model: 'claude-sonnet-4-5', effort: { 'claude-sonnet-4-5': 'high' } };
    const { params, caps } = effectiveReasoning(cfg);
    expect(caps.route).toBe('anthropic');
    expect(params).toMatchObject({ thinking: { type: 'enabled' } });
    // Without the format the same model on the same gateway is written for a generic endpoint.
    formatStore.clear();
    expect(effectiveReasoning(cfg).caps.route).toBe('generic');
  });
});

describe('the store', () => {
  it('forgets answers that are too old, sooner when nothing was found', () => {
    const store = new FormatStore();
    store.set('https://a', { at: 0, openai: ['x'], anthropic: ['y'] });
    store.set('https://b', { at: 0, openai: ['x'], anthropic: [] });
    expect(store.isFresh('https://a', 23 * 3600_000)).toBe(true);
    expect(store.isFresh('https://a', 25 * 3600_000)).toBe(false);
    expect(store.isFresh('https://b', 5 * 3600_000)).toBe(true);
    expect(store.isFresh('https://b', 7 * 3600_000)).toBe(false);
  });
});
