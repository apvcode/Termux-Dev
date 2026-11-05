import fsp from 'fs/promises';
import path from 'path';
import { fileURLToPath } from 'url';
import { NATIVE_PROVIDERS, devxDir, normalizeModelKey, readJsonFile } from './catalog-util.js';
import { ensureSecureDir } from './secure-file.js';
import {
  CostSource, PriceInfo, Rates, UsageCounts, computeCost, isFreeModel, reportedCost,
} from './cost.js';

/**
 * Prices of models: where they come from and how a request is priced.
 *
 * The models.dev catalogue lists the price (USD per 1M tokens: input, output, cache read and
 * write, long-context tiers) of thousands of models. A snapshot ships with devx and
 * `/effort refresh` downloads a fresh one together with the reasoning data. The user's own
 * provider is asked too (see capabilities.ts: model lists of OpenRouter and LiteLLM carry prices).
 */

export const PRICE_URL = 'https://models.dev/api.json';

/** [input, output, cacheRead, cacheWrite, reasoning, tierOver, tierInput, tierOutput, tierCacheRead, tierCacheWrite] */
export type PriceRow = Array<number | null>;

export interface PriceFile {
  version: 1;
  generated: string;
  source: string;
  /** Distinct prices; a model refers to one of them by index. */
  rows: PriceRow[];
  models: Record<string, Record<string, number>>;
}

const num = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) && v >= 0 ? v : null);

function rowFromCost(cost: any): PriceRow | null {
  if (!cost || typeof cost !== 'object') return null;
  const input = num(cost.input);
  const output = num(cost.output);
  if (input === null || output === null) return null;
  const row: PriceRow = [input, output, num(cost.cache_read), num(cost.cache_write), num(cost.reasoning)];

  const tier = Array.isArray(cost.tiers)
    ? cost.tiers.find((t: any) => t?.tier?.type === 'context' && num(t.tier.size) !== null)
    : null;
  if (tier) {
    const tierInput = num(tier.input);
    const tierOutput = num(tier.output);
    if (tierInput !== null && tierOutput !== null) {
      row.push(tier.tier.size, tierInput, tierOutput, num(tier.cache_read), num(tier.cache_write));
    }
  }
  while (row.length > 2 && row[row.length - 1] === null) row.pop();
  return row;
}

/** Reduces the models.dev API response to the prices of each model. */
export function slimPrices(api: any, now: Date = new Date()): PriceFile {
  const rows: PriceRow[] = [];
  const rowIndex = new Map<string, number>();
  const models: Record<string, Record<string, number>> = {};

  for (const [providerId, provider] of Object.entries<any>(api || {})) {
    for (const [modelId, model] of Object.entries<any>(provider?.models || {})) {
      const row = rowFromCost(model?.cost);
      if (!row) continue;
      const key = JSON.stringify(row);
      let index = rowIndex.get(key);
      if (index === undefined) {
        index = rows.length;
        rows.push(row);
        rowIndex.set(key, index);
      }
      (models[providerId] ||= {})[modelId.toLowerCase()] = index;
    }
  }
  return { version: 1, generated: now.toISOString().slice(0, 10), source: PRICE_URL, rows, models };
}

function ratesFromRow(row: PriceRow): Rates {
  const rates: Rates = { input: row[0] ?? 0, output: row[1] ?? 0 };
  if (row[2] != null) rates.cacheRead = row[2];
  if (row[3] != null) rates.cacheWrite = row[3];
  if (row[4] != null) rates.reasoning = row[4];
  if (row[5] != null && row[6] != null && row[7] != null) {
    rates.tier = { over: row[5], input: row[6], output: row[7] };
    if (row[8] != null) rates.tier.cacheRead = row[8];
    if (row[9] != null) rates.tier.cacheWrite = row[9];
  }
  return rates;
}

export interface PriceHit {
  rates: Rates;
  /** The catalogue provider the price comes from. */
  provider: string;
  /** True when it is the price list of the provider asked for (not of another one selling the same model). */
  own: boolean;
}

export class PriceCatalog {
  private byProvider = new Map<string, Map<string, Rates>>();
  private normalized = new Map<string, Map<string, Rates>>();
  private byName = new Map<string, Array<{ provider: string; rates: Rates }>>();
  readonly generated: string;
  readonly size: number;

  constructor(file?: PriceFile | null) {
    this.generated = file?.generated ?? '';
    let size = 0;
    if (file && file.version === 1) {
      for (const [provider, models] of Object.entries(file.models)) {
        const raw = new Map<string, Rates>();
        const norm = new Map<string, Rates>();
        for (const [id, rowIndex] of Object.entries(models)) {
          const row = file.rows[rowIndex];
          if (!row) continue;
          const rates = ratesFromRow(row);
          const key = normalizeModelKey(id);
          raw.set(id, rates);
          norm.set(key, rates);
          // A zero price in some provider's list is a free tier of that provider, not the price
          // of the model: it must not be mistaken for the price somewhere else.
          if (rates.input + rates.output > 0) {
            const list = this.byName.get(key) ?? [];
            list.push({ provider, rates });
            this.byName.set(key, list);
          }
          size++;
        }
        this.byProvider.set(provider, raw);
        this.normalized.set(provider, norm);
      }
    }
    this.size = size;
  }

  /** The price of a model: on the user's own provider first, then at its maker, then the middle of the rest. */
  lookup(provider: string | undefined, model: string): PriceHit | undefined {
    const id = model.toLowerCase().trim();
    const key = normalizeModelKey(model);
    if (!key) return undefined;

    if (provider) {
      const exact = this.byProvider.get(provider)?.get(id) ?? this.normalized.get(provider)?.get(key);
      if (exact) return { rates: exact, provider, own: true };
    }
    const candidates = this.byName.get(key);
    if (!candidates || candidates.length === 0) return undefined;

    const rank = (p: string) => {
      const i = NATIVE_PROVIDERS.indexOf(p);
      return i === -1 ? NATIVE_PROVIDERS.length : i;
    };
    const sorted = [...candidates].sort((a, b) => rank(a.provider) - rank(b.provider));
    if (rank(sorted[0].provider) < NATIVE_PROVIDERS.length) {
      return { rates: sorted[0].rates, provider: sorted[0].provider, own: false };
    }
    // Only resellers know the model: take the middle price, not the lowest or the highest.
    const byPrice = [...candidates].sort((a, b) => a.rates.input + a.rates.output - (b.rates.input + b.rates.output));
    const mid = byPrice[Math.floor((byPrice.length - 1) / 2)];
    return { rates: mid.rates, provider: mid.provider, own: false };
  }
}

let bundled: PriceCatalog | null = null;
let fresher: PriceCatalog | null = null;

/** The snapshot shipped with devx (scripts/build-reasoning-catalog.mjs creates it). */
export function bundledPrices(): PriceCatalog {
  if (bundled) return bundled;
  let file: PriceFile | null = null;
  try {
    const here = path.dirname(fileURLToPath(import.meta.url));
    file = readJsonFile<PriceFile>(path.join(here, 'price-catalog.json'));
  } catch {}
  file ??= readJsonFile<PriceFile>(path.join(process.cwd(), 'src/core/price-catalog.json'));
  bundled = new PriceCatalog(file);
  return bundled;
}

/** A catalogue downloaded earlier with /effort refresh; it wins over the shipped snapshot. */
export function loadUserPrices(dir: string = devxDir()): PriceCatalog | null {
  const file = readJsonFile<PriceFile>(path.join(dir, 'price-catalog.json'));
  fresher = file ? new PriceCatalog(file) : null;
  return fresher;
}

/** For tests: replace the catalogues. */
export function setPriceCatalogs(shipped: PriceCatalog | null, downloaded: PriceCatalog | null = null): void {
  bundled = shipped;
  fresher = downloaded;
}

export function priceCatalogInfo(): { generated: string; models: number; downloaded: boolean } {
  const active = fresher && fresher.size > 0 ? fresher : bundledPrices();
  return { generated: active.generated, models: active.size, downloaded: active === fresher };
}

/** Saves a downloaded catalogue and uses it from now on. */
export async function storePrices(file: PriceFile, dir: string = devxDir()): Promise<void> {
  await ensureSecureDir(dir);
  const target = path.join(dir, 'price-catalog.json');
  await fsp.writeFile(target + '.tmp', JSON.stringify(file), 'utf8');
  await fsp.rename(target + '.tmp', target);
  fresher = new PriceCatalog(file);
}

// ───────────────────────── resolving the price of a model ─────────────────────────

/** Where the endpoint's own list put the price of a model (registered by capabilities.ts). */
export type EndpointPriceLookup = (baseUrl: string, model: string) => Rates | undefined;
let endpointPrice: EndpointPriceLookup | null = null;

export function setEndpointPriceLookup(lookup: EndpointPriceLookup | null): void {
  endpointPrice = lookup;
}

export interface PriceContext {
  model: string;
  baseUrl?: string;
  /** Provider id as in providers.json; helps to find the model in the catalogue. */
  provider?: string;
  /** Prices written into the config, per model id (USD per 1M tokens). */
  prices?: Record<string, { input?: number; output?: number; cacheRead?: number; cacheWrite?: number }>;
}

// A vendor's own endpoint: the catalogue is its price list, so the number is not a guess.
const FIRST_PARTY_HOSTS: Record<string, string> = {
  'api.openai.com': 'openai',
  'api.anthropic.com': 'anthropic',
  'api.deepseek.com': 'deepseek',
  'generativelanguage.googleapis.com': 'google',
  'openrouter.ai': 'openrouter',
};

function catalogProviderOf(ctx: PriceContext): string | undefined {
  let host = '';
  try {
    host = new URL(ctx.baseUrl || '').hostname.toLowerCase();
  } catch {}
  return FIRST_PARTY_HOSTS[host] ?? (host.endsWith('openrouter.ai') ? 'openrouter' : ctx.provider);
}

function configuredRates(ctx: PriceContext): Rates | undefined {
  const table = ctx.prices;
  if (!table || typeof table !== 'object') return undefined;
  const id = ctx.model.trim();
  const entry = table[id] ?? table[id.toLowerCase()];
  if (!entry || typeof entry.input !== 'number' || typeof entry.output !== 'number') return undefined;
  const rates: Rates = { input: entry.input, output: entry.output };
  if (typeof entry.cacheRead === 'number') rates.cacheRead = entry.cacheRead;
  if (typeof entry.cacheWrite === 'number') rates.cacheWrite = entry.cacheWrite;
  return rates;
}

/** What is known about the price of the model: the user's config, the endpoint, the catalogue. */
export function resolvePrice(ctx: PriceContext): PriceInfo | undefined {
  const configured = configuredRates(ctx);
  if (configured) return { rates: configured, source: 'configured' };

  if (isFreeModel(ctx.model, ctx.baseUrl)) return { rates: { input: 0, output: 0 }, source: 'free' };

  const listed = endpointPrice?.(ctx.baseUrl || '', ctx.model);
  if (listed) return { rates: listed, source: 'listed' };

  const provider = catalogProviderOf(ctx);
  const hit = (fresher?.lookup(provider, ctx.model)) ?? bundledPrices().lookup(provider, ctx.model);
  if (!hit) return undefined;
  const firstParty = !!provider && Object.values(FIRST_PARTY_HOSTS).includes(provider);
  return { rates: hit.rates, source: hit.own && firstParty ? 'listed' : 'estimated' };
}

export interface PricedUsage {
  cost?: number;
  costSource?: CostSource;
}

/**
 * The cost of one request. The provider's own number wins; otherwise the tokens are priced with
 * the best known rates. A reported 0 for a model that is known to cost money is not believed.
 */
export function priceUsage(ctx: PriceContext, counts: UsageCounts, rawUsage?: any): PricedUsage {
  const info = resolvePrice(ctx);
  const reported = reportedCost(rawUsage);
  const tokens = (counts.promptTokens || 0) + (counts.completionTokens || 0);

  if (reported !== undefined) {
    const suspicious = reported === 0 && tokens > 0 && !!info && info.source !== 'free' && info.rates.input + info.rates.output > 0;
    if (!suspicious) return { cost: reported, costSource: reported === 0 && info?.source === 'free' ? 'free' : 'reported' };
  }
  if (!info) return {};
  return { cost: computeCost(info.rates, counts), costSource: info.source };
}
