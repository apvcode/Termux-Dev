import path from 'path';
import { devxDir, readJsonFile } from './catalog-util.js';
import { ensureSecureDir, writeSecureFile } from './secure-file.js';
import { isLocalBaseUrl } from './cost.js';
import { detectRoute, Route, setRouteResolver } from './reasoning.js';

/**
 * Two wire formats exist: OpenAI (`/v1/chat/completions`) and Anthropic (`/v1/messages`).
 * A gateway often serves both with ONE key and gives each format its own list of models
 * (Claude models only in the Anthropic list). devx finds out by itself:
 *
 *   - a base URL that clearly is Anthropic's (api.anthropic.com, a `/anthropic/` path) uses the
 *     Anthropic format;
 *   - for any other custom gateway the model list is requested twice, once as an OpenAI client
 *     and once as an Anthropic client. The models that only the second list has are sent
 *     through the Anthropic format; everything else stays on the OpenAI format.
 *
 * Only the model ids are remembered (`~/.devx/formats.json`); the key is never written there.
 * `"apiFormat": "openai" | "anthropic"` in the config forces one format for everything.
 */

export type ApiFormat = 'openai' | 'anthropic';

export const API_FORMATS: ApiFormat[] = ['openai', 'anthropic'];

export function isApiFormat(value: unknown): value is ApiFormat {
  return value === 'openai' || value === 'anthropic';
}

/** The format a base URL names by itself, if it does. */
export function formatFromBaseUrl(baseUrl: string | undefined): ApiFormat | null {
  if (!baseUrl) return null;
  try {
    const u = new URL(baseUrl);
    if (u.hostname.toLowerCase() === 'api.anthropic.com') return 'anthropic';
    if (u.pathname.toLowerCase().split('/').includes('anthropic')) return 'anthropic';
  } catch {}
  return null;
}

/**
 * The part of a base URL that both formats share: "https://host/v1" and "https://host" give
 * "https://host". Returns null for URLs with another path (a gateway with its own layout),
 * which are not probed for a second format.
 */
export function gatewayRoot(baseUrl: string): string | null {
  try {
    const u = new URL(baseUrl);
    const rest = u.pathname.replace(/\/+$/, '');
    if (rest !== '' && rest !== '/v1') return null;
    return u.origin;
  } catch {
    return null;
  }
}

export interface FormatRecord {
  at: number;
  /** Models of the OpenAI-format list. */
  openai: string[];
  /** Models that only the Anthropic-format list has (empty when the gateway has one format). */
  anthropic: string[];
}

const POSITIVE_TTL_MS = 24 * 60 * 60 * 1000;
const NEGATIVE_TTL_MS = 6 * 60 * 60 * 1000;

export class FormatStore {
  private entries = new Map<string, FormatRecord>();

  static key(root: string): string {
    return root.replace(/\/+$/, '').toLowerCase();
  }

  get(root: string): FormatRecord | undefined {
    return this.entries.get(FormatStore.key(root));
  }

  set(root: string, record: FormatRecord): void {
    this.entries.set(FormatStore.key(root), record);
  }

  clear(): void {
    this.entries.clear();
  }

  isFresh(root: string, now: number = Date.now()): boolean {
    const record = this.get(root);
    if (!record) return false;
    return now - record.at < (record.anthropic.length > 0 ? POSITIVE_TTL_MS : NEGATIVE_TTL_MS);
  }

  toJSON(): { version: 1; gateways: Record<string, FormatRecord> } {
    return { version: 1, gateways: Object.fromEntries(this.entries) };
  }

  load(raw: any): void {
    if (!raw || raw.version !== 1 || typeof raw.gateways !== 'object') return;
    for (const [key, r] of Object.entries<any>(raw.gateways)) {
      if (r && typeof r.at === 'number' && Array.isArray(r.openai) && Array.isArray(r.anthropic)) {
        this.entries.set(key, { at: r.at, openai: r.openai.map(String), anthropic: r.anthropic.map(String) });
      }
    }
  }
}

export const formatStore = new FormatStore();

export interface FormatConfig {
  baseUrl?: string;
  model?: string;
  apiFormat?: string;
}

const lower = (s: string) => s.toLowerCase().trim();

/**
 * Which format a request for this model uses. Decided without network access, from the base
 * URL and what discovery remembered.
 */
export function resolveFormat(config: FormatConfig): ApiFormat {
  if (isApiFormat(config.apiFormat)) return config.apiFormat;
  const named = formatFromBaseUrl(config.baseUrl);
  if (named) return named;

  const root = config.baseUrl ? gatewayRoot(config.baseUrl) : null;
  const record = root ? formatStore.get(root) : undefined;
  const model = lower(config.model || '');
  if (!record || !model) return 'openai';

  const inOpenAI = record.openai.some((id) => lower(id) === model);
  const inAnthropic = record.anthropic.some((id) => lower(id) === model);
  if (inAnthropic && !inOpenAI) return 'anthropic';
  if (inAnthropic && inOpenAI) return /claude/.test(model) ? 'anthropic' : 'openai';
  if (inOpenAI) return 'openai';

  // A model typed by hand: Claude models of a gateway that lists them only in the Anthropic format.
  if (/^claude/.test(model) && record.anthropic.length > 0 && !record.openai.some((id) => /claude/i.test(id))) return 'anthropic';
  return 'openai';
}

/** The reasoning parameters are written for the route; the Anthropic format always uses its own. */
export function routeFor(config: FormatConfig): Route | undefined {
  return resolveFormat(config) === 'anthropic' ? 'anthropic' : undefined;
}

function idsOf(body: any): string[] {
  const items: any[] = Array.isArray(body) ? body : Array.isArray(body?.data) ? body.data : Array.isArray(body?.models) ? body.models : [];
  return items.map((m) => (typeof m === 'string' ? m : m?.id ?? m?.name)).filter((id): id is string => typeof id === 'string' && id.length > 0);
}

export interface DiscoverInput {
  baseUrl: string;
  apiKey?: string;
  fetchImpl?: typeof fetch;
  timeoutMs?: number;
}

/**
 * Asks the gateway for its model list in both formats (two small GET requests to the user's own
 * base URL, with the user's own key). Returns null when the URL is not a plain gateway.
 */
export async function discoverFormats(input: DiscoverInput): Promise<FormatRecord | null> {
  const root = gatewayRoot(input.baseUrl);
  if (!root) return null;
  const doFetch = input.fetchImpl ?? fetch;
  const timeout = input.timeoutMs ?? 4000;
  const url = `${root}/v1/models`;

  const list = async (headers: Record<string, string>): Promise<string[] | null> => {
    try {
      const res = await doFetch(url, { headers, signal: AbortSignal.timeout(timeout) });
      return res.ok ? idsOf(await res.json()) : null;
    } catch {
      return null;
    }
  };

  const key = input.apiKey && input.apiKey !== 'ollama' ? input.apiKey : '';
  const [asOpenAI, asAnthropic] = await Promise.all([
    list(key ? { Authorization: `Bearer ${key}` } : {}),
    list({ 'anthropic-version': '2023-06-01', ...(key ? { 'x-api-key': key } : {}) }),
  ]);
  if (asOpenAI === null && asAnthropic === null) return null;

  const openai = asOpenAI ?? [];
  const known = new Set(openai.map(lower));
  // A gateway that ignores the Anthropic headers answers with the same list: no second format.
  const only = (asAnthropic ?? []).filter((id) => !known.has(lower(id)));
  const anthropic = asOpenAI === null ? (asAnthropic ?? []) : only;
  return { at: Date.now(), openai, anthropic };
}

/** Models that are reachable only through the Anthropic format (for the model picker). */
export function anthropicOnlyModels(baseUrl: string | undefined): string[] {
  const root = baseUrl ? gatewayRoot(baseUrl) : null;
  return (root ? formatStore.get(root)?.anthropic : undefined) ?? [];
}

// ───────────────────────── remembering the answers ─────────────────────────

export function loadFormatCache(dir: string = devxDir()): void {
  formatStore.load(readJsonFile(path.join(dir, 'formats.json')));
}

async function saveFormatCache(dir: string): Promise<void> {
  try {
    await ensureSecureDir(dir);
    await writeSecureFile(path.join(dir, 'formats.json'), JSON.stringify(formatStore.toJSON()));
  } catch {}
}

const inflight = new Map<string, Promise<FormatRecord | null>>();

export interface FormatRefreshOptions {
  force?: boolean;
  fetchImpl?: typeof fetch;
  dir?: string;
  persist?: boolean;
  timeoutMs?: number;
}

/**
 * Makes sure a custom gateway has been asked about its formats (cached for a day). Vendors with
 * a single format, local servers and `"probeEndpoints": false` are never asked.
 */
export async function refreshFormats(
  config: FormatConfig & { apiKey?: string; probeEndpoints?: boolean },
  options: FormatRefreshOptions = {}
): Promise<FormatRecord | null> {
  const baseUrl = config.baseUrl || '';
  if (!baseUrl || config.probeEndpoints === false || isApiFormat(config.apiFormat)) return null;
  if (formatFromBaseUrl(baseUrl) || isLocalBaseUrl(baseUrl) || detectRoute(baseUrl) !== 'generic') return null;
  const root = gatewayRoot(baseUrl);
  if (!root) return null;

  if (!options.force && formatStore.isFresh(root)) return formatStore.get(root) ?? null;
  const pending = inflight.get(root);
  if (pending) return pending;

  const job = (async () => {
    const record = await discoverFormats({ baseUrl, apiKey: config.apiKey, fetchImpl: options.fetchImpl, timeoutMs: options.timeoutMs });
    if (!record) return null;
    formatStore.set(root, record);
    if (options.persist !== false) await saveFormatCache(options.dir ?? devxDir());
    return record;
  })().finally(() => inflight.delete(root));
  inflight.set(root, job);
  return job;
}

/** Loads what was learned earlier and lets the reasoning settings follow the format. */
export function installFormats(dir: string = devxDir()): void {
  loadFormatCache(dir);
  setRouteResolver((config) => routeFor(config));
}
