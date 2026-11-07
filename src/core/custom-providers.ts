import { isLocalBaseUrl } from './cost.js';

/**
 * Providers the user added by hand: any OpenAI- or Anthropic-compatible address with a name of
 * the user's choice and its own key. They live in `~/.devxrc.json`:
 *
 *   "customProviders": { "custom-my-gateway": { "name": "My gateway", "baseUrl": "https://gw.example.com/v1" } }
 *   "apiKeys":         { "custom-my-gateway": "sk-..." }       (the key store every provider uses)
 *   "baseUrls":        { "custom-my-gateway": "https://..." }  (a mirror, kept in step with the address)
 *
 * The functions here only change the config object they are given; saving it is up to the caller.
 */

export interface CustomProvider {
  name: string;
  baseUrl: string;
}

export interface CustomProviderEntry extends CustomProvider {
  id: string;
  /** The saved key ('' when there is none). */
  apiKey: string;
}

type Config = Record<string, any>;

/** The key stored for a server that needs none (a local one): the OpenAI client wants some value and falls back to $OPENAI_API_KEY for an empty one. */
export const NO_KEY = 'no-key';

/** The id the old single "Custom" entry of the provider list had. */
const LEGACY_ID = 'custom';

const MAX_NAME = 40;

export function customProviders(config: Config): Record<string, CustomProvider> {
  const own = config.customProviders;
  return own && typeof own === 'object' && !Array.isArray(own) ? own : {};
}

/** The saved providers, in the order they were added. */
export function listCustomProviders(config: Config): CustomProviderEntry[] {
  return Object.entries(customProviders(config))
    .filter(([, p]) => p && typeof p.baseUrl === 'string')
    .map(([id, p]) => ({ id, name: String(p.name || id), baseUrl: p.baseUrl, apiKey: config.apiKeys?.[id] ?? '' }));
}

export function isCustomProvider(config: Config, id: string | undefined): boolean {
  return !!id && !!customProviders(config)[id];
}

/** The name to show for a provider id: the user's name for a custom one, the label of a listed one, else the id. */
export function providerDisplayName(config: Config, id: string | undefined, listed: Array<{ value: string; label: string }> = []): string {
  if (!id) return '';
  const own = customProviders(config)[id];
  if (own?.name) return own.name;
  return listed.find((p) => p.value === id)?.label ?? id;
}

/**
 * The single "Custom" entry older versions had becomes a saved provider named Custom, so that
 * whoever used it finds it in the list (with its address and key). Returns true when it did.
 */
export function migrateLegacyCustom(config: Config): boolean {
  if (customProviders(config)[LEGACY_ID]) return false;
  const url = config.baseUrls?.[LEGACY_ID] || (config.provider === LEGACY_ID ? config.baseUrl : '');
  if (!url) return false;
  config.customProviders = { ...customProviders(config), [LEGACY_ID]: { name: 'Custom', baseUrl: url } };
  return true;
}

// ───────────────────────── checking what the user typed ─────────────────────────

export type NameProblem = 'empty' | 'long' | 'taken';

/** Is this name usable? A name must be unique (without regard to case), but a provider may keep its own. */
export function nameProblem(config: Config, name: string, exceptId?: string): NameProblem | null {
  const clean = name.trim();
  if (!clean) return 'empty';
  if (Array.from(clean).length > MAX_NAME) return 'long';
  const taken = listCustomProviders(config).some((p) => p.id !== exceptId && p.name.trim().toLowerCase() === clean.toLowerCase());
  return taken ? 'taken' : null;
}

export type UrlResult = { ok: true; url: string } | { ok: false; reason: 'empty' | 'invalid' | 'scheme' };

/** An address of an API: http(s), with a host. The path stays as typed (`/v1`), only a trailing slash goes. */
export function checkBaseUrl(input: string): UrlResult {
  const text = input.trim();
  if (!text) return { ok: false, reason: 'empty' };
  let url: URL;
  try {
    url = new URL(text);
  } catch {
    return { ok: false, reason: 'invalid' };
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return { ok: false, reason: 'scheme' };
  if (!url.hostname) return { ok: false, reason: 'invalid' };
  return { ok: true, url: text.replace(/\/+$/, '') };
}

/** A server on this machine or on the home network usually needs no key. */
export function usuallyNeedsNoKey(baseUrl: string): boolean {
  return isLocalBaseUrl(baseUrl);
}

/** `api.example.com` for the list. */
export const hostOfUrl = (url: string): string => url.replace(/^https?:\/\//, '').replace(/\/.*$/, '');

/** The key as the list shows it. */
export function keyHint(key: string | undefined): string {
  if (!key) return '';
  if (key === NO_KEY) return 'no key';
  if (key.length <= 8) return '****';
  return `${key.slice(0, 4)}...${key.slice(-4)}`;
}

// ───────────────────────── changing the saved providers ─────────────────────────

/** An id from the name that no listed and no saved provider has: `custom-my-gateway`, `custom-my-gateway-2`. */
export function newProviderId(config: Config, name: string, listedIds: Iterable<string> = []): string {
  const slug = name
    .toLowerCase()
    .normalize('NFC')
    .replace(/[^\p{L}\p{N}]+/gu, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 30) || 'provider';
  const taken = new Set<string>([...listedIds, ...Object.keys(customProviders(config))]);
  let id = `custom-${slug}`;
  for (let n = 2; taken.has(id); n++) id = `custom-${slug}-${n}`;
  return id;
}

function ensureMaps(config: Config) {
  const own = config.customProviders;
  if (!own || typeof own !== 'object' || Array.isArray(own)) config.customProviders = {};
  config.apiKeys = config.apiKeys || {};
  config.baseUrls = config.baseUrls || {};
}

/** Adds a provider and returns its id. The name and address are expected to have been checked. */
export function addCustomProvider(
  config: Config,
  input: { name: string; baseUrl: string; apiKey: string },
  listedIds: Iterable<string> = []
): string {
  ensureMaps(config);
  const id = newProviderId(config, input.name, listedIds);
  config.customProviders[id] = { name: input.name.trim(), baseUrl: input.baseUrl };
  config.baseUrls[id] = input.baseUrl;
  config.apiKeys[id] = input.apiKey;
  return id;
}

/**
 * Changes the name, the address or the key of a saved provider. When it is the provider in use,
 * the settings of the running session follow at once.
 */
export function updateCustomProvider(config: Config, id: string, patch: Partial<{ name: string; baseUrl: string; apiKey: string }>): boolean {
  const own = customProviders(config)[id];
  if (!own) return false;
  ensureMaps(config);
  if (patch.name !== undefined) config.customProviders[id] = { ...config.customProviders[id], name: patch.name.trim() };
  if (patch.baseUrl !== undefined) {
    config.customProviders[id] = { ...config.customProviders[id], baseUrl: patch.baseUrl };
    config.baseUrls[id] = patch.baseUrl;
    if (config.provider === id) config.baseUrl = patch.baseUrl;
  }
  if (patch.apiKey !== undefined) {
    config.apiKeys[id] = patch.apiKey;
    if (config.provider === id) config.apiKey = patch.apiKey;
  }
  return true;
}

/**
 * Forgets a saved provider with its key. The provider in use keeps working until another is chosen
 * (its address and key stay in the running settings) but it is no longer a provider of the list:
 * the config forgets its id, so that saving does not write the key and the address back;
 * `wasActive` tells the caller to say so.
 */
export function removeCustomProvider(config: Config, id: string): { removed: boolean; wasActive: boolean } {
  if (!customProviders(config)[id]) return { removed: false, wasActive: false };
  const rest = { ...config.customProviders };
  delete rest[id];
  config.customProviders = rest;
  if (config.apiKeys) delete config.apiKeys[id];
  if (config.baseUrls) delete config.baseUrls[id];
  const wasActive = config.provider === id;
  if (wasActive) delete config.provider;
  return { removed: true, wasActive };
}
