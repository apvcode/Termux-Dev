import fs from 'fs';
import fsp from 'fs/promises';
import path from 'path';
import { fileURLToPath } from 'url';
import { ensureSecureDir, writeSecureFile } from './secure-file.js';
import { NATIVE_PROVIDERS, devxDir, normalizeModelKey, readJsonFile } from './catalog-util.js';
import { installFormats } from './formats.js';
import { loadUserVision, slimVision, storeVision } from './vision.js';
import { Rates, priceFromModelEntry } from './cost.js';
import { loadUserPrices, setEndpointPriceLookup, slimPrices, storePrices } from './prices.js';
import { ReasoningConfig, ReasoningData, detectRoute, setReasoningDataLookup } from './reasoning.js';

/**
 * Where devx learns what a model can do with reasoning, without asking the user:
 *
 *   1. The endpoint itself. OpenRouter lists `reasoning` metadata for every model; Ollama
 *      reports a "thinking" capability; LiteLLM and many gateways add flags to their model
 *      list. A custom provider is asked once (a few small requests to ITS OWN base URL,
 *      with the key the user configured) and the answer is cached for a day.
 *   2. The models.dev catalogue: `reasoning_options` for thousands of models of 230 providers.
 *      A snapshot ships with devx; `/effort refresh` downloads a fresh one (the only time
 *      devx contacts models.dev, and only because the user asked).
 *
 * Both only describe WHAT a model offers. How a level is sent depends on the route and is
 * decided in reasoning.ts.
 */

// ───────────────────────── the models.dev catalogue ─────────────────────────

export type RawOption =
  | { type: 'effort'; values: string[] }
  | { type: 'toggle' }
  | { type: 'budget_tokens'; min?: number; max?: number };

export interface CatalogFile {
  version: 1;
  generated: string;
  source: string;
  /** Distinct option lists; a model refers to one of them by index. */
  shapes: RawOption[][];
  models: Record<string, Record<string, number>>;
}

export { devxDir, normalizeModelKey };

const CATALOG_URL = 'https://models.dev/api.json';

function sanitizeOptions(raw: unknown): RawOption[] {
  if (!Array.isArray(raw)) return [];
  const out: RawOption[] = [];
  for (const option of raw) {
    if (!option || typeof option !== 'object') continue;
    const o = option as Record<string, unknown>;
    if (o.type === 'effort' && Array.isArray(o.values)) {
      out.push({ type: 'effort', values: o.values.filter((v): v is string => typeof v === 'string') });
    } else if (o.type === 'toggle') {
      out.push({ type: 'toggle' });
    } else if (o.type === 'budget_tokens') {
      const budget: RawOption = { type: 'budget_tokens' };
      if (typeof o.min === 'number') budget.min = o.min;
      if (typeof o.max === 'number') budget.max = o.max;
      out.push(budget);
    }
  }
  return out;
}

/** Reduces the models.dev API response to the reasoning options of each model. */
export function slimCatalog(api: any, now: Date = new Date()): CatalogFile {
  const shapes: RawOption[][] = [];
  const shapeIndex = new Map<string, number>();
  const models: Record<string, Record<string, number>> = {};

  for (const [providerId, provider] of Object.entries<any>(api || {})) {
    for (const [modelId, model] of Object.entries<any>(provider?.models || {})) {
      const options = sanitizeOptions(model?.reasoning_options);
      if (options.length === 0) continue;
      const key = JSON.stringify(options);
      let index = shapeIndex.get(key);
      if (index === undefined) {
        index = shapes.length;
        shapes.push(options);
        shapeIndex.set(key, index);
      }
      (models[providerId] ||= {})[modelId.toLowerCase()] = index;
    }
  }
  return { version: 1, generated: now.toISOString().slice(0, 10), source: CATALOG_URL, shapes, models };
}

/** What the options of a catalogue entry mean. */
export function dataFromOptions(options: RawOption[], source: 'catalog' | 'endpoint' = 'catalog'): ReasoningData {
  const data: ReasoningData = { source };
  for (const option of options) {
    if (option.type === 'effort') data.efforts = [...new Set([...(data.efforts ?? []), ...option.values])];
    else if (option.type === 'toggle') data.toggle = true;
    else if (option.type === 'budget_tokens' && !data.budget) data.budget = { min: option.min, max: option.max };
  }
  return data;
}

export class Catalog {
  private byProvider = new Map<string, Map<string, RawOption[]>>();
  private normalized = new Map<string, Map<string, RawOption[]>>();
  private byName = new Map<string, Array<{ provider: string; options: RawOption[] }>>();
  readonly generated: string;
  readonly size: number;

  constructor(file?: CatalogFile | null) {
    this.generated = file?.generated ?? '';
    let size = 0;
    if (file && file.version === 1) {
      for (const [provider, models] of Object.entries(file.models)) {
        const raw = new Map<string, RawOption[]>();
        const norm = new Map<string, RawOption[]>();
        for (const [id, shapeIndex] of Object.entries(models)) {
          const options = file.shapes[shapeIndex];
          if (!options) continue;
          raw.set(id, options);
          norm.set(normalizeModelKey(id), options);
          const key = normalizeModelKey(id);
          const list = this.byName.get(key) ?? [];
          list.push({ provider, options });
          this.byName.set(key, list);
          size++;
        }
        this.byProvider.set(provider, raw);
        this.normalized.set(provider, norm);
      }
    }
    this.size = size;
  }

  /** The entry for a model: first on the user's own provider, then on the model's maker, then anywhere. */
  lookup(provider: string | undefined, model: string): ReasoningData | undefined {
    const id = model.toLowerCase().trim();
    const key = normalizeModelKey(model);
    if (!key) return undefined;

    if (provider) {
      const exact = this.byProvider.get(provider)?.get(id) ?? this.normalized.get(provider)?.get(key);
      if (exact) return dataFromOptions(exact);
    }
    const candidates = this.byName.get(key);
    if (!candidates || candidates.length === 0) return undefined;
    const rank = (p: string) => {
      const i = NATIVE_PROVIDERS.indexOf(p);
      return i === -1 ? NATIVE_PROVIDERS.length : i;
    };
    const best = [...candidates].sort((a, b) => rank(a.provider) - rank(b.provider))[0];
    return dataFromOptions(best.options);
  }
}

let bundled: Catalog | null = null;
let fresher: Catalog | null = null;

/** The snapshot shipped with devx (scripts/build-reasoning-catalog.mjs creates it). */
export function bundledCatalog(): Catalog {
  if (bundled) return bundled;
  let file: CatalogFile | null = null;
  try {
    const here = path.dirname(fileURLToPath(import.meta.url));
    file = readJsonFile<CatalogFile>(path.join(here, 'reasoning-catalog.json'));
  } catch {}
  file ??= readJsonFile<CatalogFile>(path.join(process.cwd(), 'src/core/reasoning-catalog.json'));
  bundled = new Catalog(file);
  return bundled;
}

/** A catalogue downloaded earlier with /effort refresh; it wins over the shipped snapshot. */
export function loadUserCatalog(dir: string = devxDir()): Catalog | null {
  const file = readJsonFile<CatalogFile>(path.join(dir, 'reasoning-catalog.json'));
  fresher = file ? new Catalog(file) : null;
  return fresher;
}

/** For tests: replace the catalogues. */
export function setCatalogs(shipped: Catalog | null, downloaded: Catalog | null = null): void {
  bundled = shipped;
  fresher = downloaded;
}

export function catalogInfo(): { generated: string; models: number; downloaded: boolean } {
  const active = fresher && fresher.size > 0 ? fresher : bundledCatalog();
  return { generated: active.generated, models: active.size, downloaded: active === fresher };
}

function lookupCatalogs(provider: string | undefined, model: string): ReasoningData | undefined {
  return fresher?.lookup(provider, model) ?? bundledCatalog().lookup(provider, model);
}

/** Downloads models.dev, keeps the reasoning options and stores them for later runs. */
export async function refreshCatalog(
  options: { fetchImpl?: typeof fetch; dir?: string; url?: string; timeoutMs?: number } = {}
): Promise<{ models: number; providers: number; bytes: number; generated: string; priced: number }> {
  const doFetch = options.fetchImpl ?? fetch;
  const res = await doFetch(options.url ?? CATALOG_URL, { signal: AbortSignal.timeout(options.timeoutMs ?? 30_000) });
  if (!res.ok) throw new Error(`models.dev answered HTTP ${res.status}`);
  const api = await res.json();
  const file = slimCatalog(api);
  const total = Object.values(file.models).reduce((n, m) => n + Object.keys(m).length, 0);
  if (total === 0) throw new Error('models.dev returned no reasoning data');
  const prices = slimPrices(api);

  const dir = options.dir ?? devxDir();
  await ensureSecureDir(dir);
  const text = JSON.stringify(file);
  const target = path.join(dir, 'reasoning-catalog.json');
  await fsp.writeFile(target + '.tmp', text, 'utf8');
  await fsp.rename(target + '.tmp', target);
  fresher = new Catalog(file);
  const priced = Object.values(prices.models).reduce((n, m) => n + Object.keys(m).length, 0);
  if (priced > 0) await storePrices(prices, dir);
  const vision = slimVision(api);
  if (vision.yes.length > 0) await storeVision(vision, dir);
  return { models: total, providers: Object.keys(file.models).length, bytes: text.length, generated: file.generated, priced };
}

// ───────────────────────── asking the endpoint ─────────────────────────

/**
 * Reads reasoning information from one entry of a model list. Understands the OpenRouter
 * schema (`reasoning`, `supported_parameters`), models.dev style `reasoning_options`,
 * capability lists (Ollama, LM Studio, gateways) and plain boolean flags.
 * Returns null when the entry says nothing about reasoning.
 */
export function dataFromModelEntry(entry: any, source: 'endpoint' | 'catalog' = 'endpoint'): ReasoningData | null {
  if (!entry || typeof entry !== 'object') return null;

  const reasoning = entry.reasoning;
  if (reasoning && typeof reasoning === 'object' && !Array.isArray(reasoning)) {
    const efforts = Array.isArray(reasoning.supported_efforts) ? reasoning.supported_efforts.map(String) : [];
    const data: ReasoningData = { source, supported: true };
    if (efforts.length > 0) data.efforts = efforts;
    if (reasoning.mandatory === true) data.mandatory = true;
    if (reasoning.supports_max_tokens === true) data.budget = {};
    else if (efforts.length === 0 && reasoning.mandatory === false) data.toggle = true;
    return data;
  }

  if (Array.isArray(entry.reasoning_options)) {
    const options = sanitizeOptions(entry.reasoning_options);
    if (options.length > 0) return dataFromOptions(options, source);
  }

  const params: string[] | null = Array.isArray(entry.supported_parameters) ? entry.supported_parameters.map(String) : null;
  if (params) {
    if (params.includes('reasoning_effort')) return { source, supported: true, efforts: ['low', 'medium', 'high'] };
    if (params.some((p) => p === 'reasoning' || p === 'include_reasoning' || p === 'thinking')) return { source, supported: true };
    if (params.length > 0) return { source, none: true };
  }

  const caps = entry.capabilities;
  const capList: string[] | null = Array.isArray(caps)
    ? caps.map(String)
    : caps && typeof caps === 'object'
      ? Object.keys(caps).filter((k) => caps[k])
      : null;
  if (capList && capList.length > 0) {
    return capList.some((c) => /reason|think/i.test(c)) ? { source, supported: true, toggle: true } : { source, none: true };
  }

  for (const key of ['supports_reasoning', 'reasoning_supported', 'supports_thinking', 'reasoning', 'thinking']) {
    if (entry[key] === true) return { source, supported: true };
    if (entry[key] === false) return { source, none: true };
  }
  return null;
}

interface ProbeInput {
  baseUrl: string;
  apiKey?: string;
  model: string;
  fetchImpl?: typeof fetch;
  timeoutMs?: number;
}

function isLocalHost(host: string): boolean {
  return (
    host === 'localhost' || host === '::1' || host === '[::1]' ||
    /^127\./.test(host) || /^10\./.test(host) || /^192\.168\./.test(host) || /^172\.(1[6-9]|2\d|3[01])\./.test(host) ||
    host.endsWith('.local') || host === 'ollama.com' || host.endsWith('.ollama.com')
  );
}

const sameModel = (id: unknown, model: string) => {
  if (typeof id !== 'string') return false;
  const a = id.toLowerCase().replace(/^models\//, '');
  return a === model.toLowerCase().replace(/^models\//, '');
};

/**
 * Asks the endpoint what it knows about one model. Every request goes to the base URL the
 * user configured, with the key the user configured, and has a short timeout.
 *   - GET {base}/models: the list devx already uses for the model picker; its entry for the
 *     model may carry reasoning metadata (OpenRouter and compatible gateways do)
 *   - POST {origin}/api/show on a local machine or Ollama Cloud: "capabilities" list
 *   - GET {origin}/model/info: a LiteLLM proxy
 */
export async function probeEndpoint(input: ProbeInput): Promise<ReasoningData | null> {
  return (await probeEndpointFull(input)).data;
}

export interface ProbeResult {
  data: ReasoningData | null;
  /** The price the endpoint lists for the model (OpenRouter, LiteLLM). */
  price?: Rates;
}

export async function probeEndpointFull(input: ProbeInput): Promise<ProbeResult> {
  const doFetch = input.fetchImpl ?? fetch;
  const timeout = input.timeoutMs ?? 4000;
  const base = input.baseUrl.replace(/\/+$/, '');
  const headers: Record<string, string> = {};
  if (input.apiKey && input.apiKey !== 'ollama') headers.Authorization = `Bearer ${input.apiKey}`;

  const request = async (url: string, init: RequestInit = {}): Promise<any | null> => {
    try {
      const res = await doFetch(url, { ...init, headers: { ...headers, ...(init.headers as object) }, signal: AbortSignal.timeout(timeout) });
      return res.ok ? await res.json() : null;
    } catch {
      return null;
    }
  };

  let origin = '';
  let host = '';
  try {
    const u = new URL(base);
    origin = u.origin;
    host = u.hostname.toLowerCase();
  } catch {
    return { data: null };
  }

  const list = await request(`${base}/models`);
  const items: any[] = Array.isArray(list) ? list : Array.isArray(list?.data) ? list.data : Array.isArray(list?.models) ? list.models : [];
  const entry = items.find((e) => sameModel(e?.id ?? e?.name ?? e?.model, input.model));
  let price = priceFromModelEntry(entry);
  const fromList = dataFromModelEntry(entry);
  if (fromList) return { data: fromList, price };

  if (isLocalHost(host)) {
    const show = await request(`${origin}/api/show`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ model: input.model }),
    });
    if (show && Array.isArray(show.capabilities)) {
      const capabilities = show.capabilities.map(String);
      return {
        data: capabilities.includes('thinking') ? { source: 'endpoint', supported: true, toggle: true } : { source: 'endpoint', none: true },
        price,
      };
    }
  }

  const info = await request(`${origin}/model/info`);
  if (Array.isArray(info?.data)) {
    const hit = info.data.find((m: any) => sameModel(m?.model_name, input.model));
    price ??= priceFromModelEntry(hit);
    if (hit?.model_info?.supports_reasoning === true) return { data: { source: 'endpoint', supported: true }, price };
  }
  return { data: null, price };
}

// ───────────────────────── remembering the answers ─────────────────────────

export interface EndpointRecord {
  at: number;
  data: ReasoningData | null;
  /** The price the endpoint lists for the model, USD per 1M tokens. */
  price?: Rates;
}

const POSITIVE_TTL_MS = 24 * 60 * 60 * 1000;
const NEGATIVE_TTL_MS = 6 * 60 * 60 * 1000;

export class CapabilityStore {
  private entries = new Map<string, EndpointRecord>();

  static key(baseUrl: string, model: string): string {
    return `${baseUrl.replace(/\/+$/, '').toLowerCase()}|${model.toLowerCase().trim()}`;
  }

  get(baseUrl: string, model: string): EndpointRecord | undefined {
    return this.entries.get(CapabilityStore.key(baseUrl, model));
  }

  set(baseUrl: string, model: string, record: EndpointRecord): void {
    this.entries.set(CapabilityStore.key(baseUrl, model), record);
  }

  delete(baseUrl: string, model: string): void {
    this.entries.delete(CapabilityStore.key(baseUrl, model));
  }

  clear(): void {
    this.entries.clear();
  }

  isFresh(baseUrl: string, model: string, now: number = Date.now()): boolean {
    const record = this.get(baseUrl, model);
    if (!record) return false;
    return now - record.at < (record.data || record.price ? POSITIVE_TTL_MS : NEGATIVE_TTL_MS);
  }

  toJSON(): { version: 1; endpoints: Record<string, EndpointRecord> } {
    return { version: 1, endpoints: Object.fromEntries(this.entries) };
  }

  load(raw: any): void {
    if (!raw || raw.version !== 1 || typeof raw.endpoints !== 'object') return;
    for (const [key, record] of Object.entries<any>(raw.endpoints)) {
      if (record && typeof record.at === 'number') {
        const entry: EndpointRecord = { at: record.at, data: record.data ?? null };
        if (record.price && typeof record.price.input === 'number' && typeof record.price.output === 'number') entry.price = record.price;
        this.entries.set(key, entry);
      }
    }
  }
}

export const capabilityStore = new CapabilityStore();

export function loadCapabilityCache(dir: string = devxDir()): void {
  capabilityStore.load(readJsonFile(path.join(dir, 'capabilities.json')));
}

async function saveCapabilityCache(dir: string): Promise<void> {
  try {
    await ensureSecureDir(dir);
    await writeSecureFile(path.join(dir, 'capabilities.json'), JSON.stringify(capabilityStore.toJSON()));
  } catch {}
}

const inflight = new Map<string, Promise<EndpointRecord | null>>();

export interface RefreshOptions {
  /** Ask again even when a recent answer is stored. */
  force?: boolean;
  fetchImpl?: typeof fetch;
  dir?: string;
  persist?: boolean;
  timeoutMs?: number;
}

/**
 * Makes sure the endpoint of the current config has been asked about the current model.
 * Only OpenRouter-style and custom endpoints are asked: for the big vendors the catalogue
 * is enough and their model lists say nothing about reasoning. Turn it off completely with
 * `"probeEndpoints": false` in the config.
 */
export async function refreshCapabilities(
  config: ReasoningConfig & { apiKey?: string; probeEndpoints?: boolean },
  options: RefreshOptions = {}
): Promise<EndpointRecord | null> {
  const baseUrl = config.baseUrl || '';
  const model = config.model || '';
  if (!baseUrl || !model || config.probeEndpoints === false) return null;
  const route = detectRoute(baseUrl);
  if (route !== 'generic' && route !== 'openrouter') return null;

  if (!options.force && capabilityStore.isFresh(baseUrl, model)) return capabilityStore.get(baseUrl, model) ?? null;

  const key = CapabilityStore.key(baseUrl, model);
  const pending = inflight.get(key);
  if (pending) return pending;

  const job = (async () => {
    const { data, price } = await probeEndpointFull({ baseUrl, apiKey: config.apiKey, model, fetchImpl: options.fetchImpl, timeoutMs: options.timeoutMs });
    const record: EndpointRecord = { at: Date.now(), data, ...(price ? { price } : {}) };
    capabilityStore.set(baseUrl, model, record);
    if (options.persist !== false) await saveCapabilityCache(options.dir ?? devxDir());
    return record;
  })().finally(() => inflight.delete(key));
  inflight.set(key, job);
  return job;
}

// ───────────────────────── putting it together ─────────────────────────

function mergeData(live: ReasoningData | undefined, catalog: ReasoningData | undefined): ReasoningData | undefined {
  if (!live) return catalog;
  if (!catalog || live.none || catalog.none) return live;
  const liveHasDetail = !!(live.efforts?.length || live.toggle || live.budget);
  if (liveHasDetail) return live;
  // The endpoint only says "it reasons" (and maybe "cannot be switched off"): the details come from the catalogue.
  return { ...catalog, mandatory: live.mandatory ?? catalog.mandatory };
}

/** What is known about the model of this config: the endpoint's answer, completed by the catalogue. */
export function lookupReasoningData(config: ReasoningConfig): ReasoningData | undefined {
  const model = config.model || '';
  if (!model) return undefined;
  const live = capabilityStore.get(config.baseUrl || '', model)?.data ?? undefined;
  return mergeData(live, lookupCatalogs(config.provider, model));
}

/** Loads the stored answers and connects them to the reasoning resolver. Call once at startup. */
export function installCapabilities(dir: string = devxDir()): void {
  loadUserCatalog(dir);
  loadUserPrices(dir);
  loadUserVision(dir);
  loadCapabilityCache(dir);
  setReasoningDataLookup(lookupReasoningData);
  setEndpointPriceLookup((baseUrl, model) => capabilityStore.get(baseUrl, model)?.price);
  installFormats(dir);
}
