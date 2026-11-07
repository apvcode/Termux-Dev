import { describe, expect, it } from 'vitest';
import {
  NO_KEY, addCustomProvider, checkBaseUrl, customProviders, isCustomProvider, keyHint, listCustomProviders,
  migrateLegacyCustom, nameProblem, newProviderId, providerDisplayName, removeCustomProvider, updateCustomProvider,
  usuallyNeedsNoKey,
} from '../../src/core/custom-providers.js';
import { cloneConfig, mergeConfigChanges } from '../../src/core/config-merge.js';

const gw = { name: 'My gateway', baseUrl: 'https://gw.example.com/v1', apiKey: 'sk-secret-key-1234' };

describe('adding a provider', () => {
  it('stores the name and address, and the key in the key store every provider uses', () => {
    const config: any = {};
    const id = addCustomProvider(config, gw);
    expect(id).toBe('custom-my-gateway');
    expect(config.customProviders[id]).toEqual({ name: 'My gateway', baseUrl: 'https://gw.example.com/v1' });
    expect(config.apiKeys[id]).toBe('sk-secret-key-1234');
    expect(config.baseUrls[id]).toBe('https://gw.example.com/v1');
    expect(listCustomProviders(config)).toEqual([{ id, ...config.customProviders[id], apiKey: 'sk-secret-key-1234' }]);
    expect(isCustomProvider(config, id)).toBe(true);
    expect(isCustomProvider(config, 'openrouter')).toBe(false);
  });

  it('gives every provider an id of its own, also for the same name in another case or script', () => {
    const config: any = {};
    const ids = [
      addCustomProvider(config, { ...gw, name: 'Proxy' }),
      addCustomProvider(config, { ...gw, name: 'proxy!' }),
      addCustomProvider(config, { ...gw, name: 'Мой шлюз' }),
      addCustomProvider(config, { ...gw, name: '???' }),
    ];
    expect(new Set(ids).size).toBe(4);
    expect(ids[0]).toBe('custom-proxy');
    expect(ids[1]).toBe('custom-proxy-2');
    expect(ids[2]).toBe('custom-мой-шлюз');
    expect(ids[3]).toBe('custom-provider');
  });

  it('never takes the id of a provider of the list', () => {
    expect(newProviderId({}, 'x', ['custom-x'])).toBe('custom-x-2');
  });

  it('copes with a config that has junk where the providers should be', () => {
    for (const junk of [null, 'text', [1, 2], 7]) {
      const config: any = { customProviders: junk };
      expect(customProviders(config)).toEqual({});
      expect(addCustomProvider(config, gw)).toBe('custom-my-gateway');
    }
  });
});

describe('checking what was typed', () => {
  it('a name must be there, short, and not used by another provider (case does not matter)', () => {
    const config: any = {};
    const id = addCustomProvider(config, gw);
    expect(nameProblem(config, '   ')).toBe('empty');
    expect(nameProblem(config, 'x'.repeat(41))).toBe('long');
    expect(nameProblem(config, 'x'.repeat(40))).toBeNull();
    expect(nameProblem(config, 'my GATEWAY')).toBe('taken');
    expect(nameProblem(config, 'my GATEWAY', id)).toBeNull(); // its own name is fine when it is edited
    expect(nameProblem(config, 'Another')).toBeNull();
  });

  it('an address must be http(s) with a host; a trailing slash is dropped, the path stays', () => {
    expect(checkBaseUrl('https://api.example.com/v1/')).toEqual({ ok: true, url: 'https://api.example.com/v1' });
    expect(checkBaseUrl('  http://localhost:1234/v1  ')).toEqual({ ok: true, url: 'http://localhost:1234/v1' });
    expect(checkBaseUrl('https://gw.example.com')).toEqual({ ok: true, url: 'https://gw.example.com' });
    expect(checkBaseUrl('')).toEqual({ ok: false, reason: 'empty' });
    expect(checkBaseUrl('api.example.com/v1')).toEqual({ ok: false, reason: 'invalid' });
    expect(checkBaseUrl('ftp://example.com')).toEqual({ ok: false, reason: 'scheme' });
    expect(checkBaseUrl('javascript:alert(1)')).toEqual({ ok: false, reason: 'scheme' });
    expect(checkBaseUrl('https://')).toEqual({ ok: false, reason: 'invalid' });
  });

  it('a local server usually needs no key, a remote one does', () => {
    expect(usuallyNeedsNoKey('http://localhost:1234/v1')).toBe(true);
    expect(usuallyNeedsNoKey('http://192.168.1.20:8000/v1')).toBe(true);
    expect(usuallyNeedsNoKey('https://api.example.com/v1')).toBe(false);
  });

  it('shows a key without giving it away', () => {
    expect(keyHint('sk-secret-key-1234')).toBe('sk-s...1234');
    expect(keyHint('short')).toBe('****');
    expect(keyHint(NO_KEY)).toBe('no key');
    expect(keyHint('')).toBe('');
    expect(keyHint(undefined)).toBe('');
  });
});

describe('changing and deleting', () => {
  it('changes the name, the address and the key, keeping the mirror of the address in step', () => {
    const config: any = {};
    const id = addCustomProvider(config, gw);
    expect(updateCustomProvider(config, id, { name: 'Renamed' })).toBe(true);
    expect(updateCustomProvider(config, id, { baseUrl: 'https://new.example.com/v1' })).toBe(true);
    expect(updateCustomProvider(config, id, { apiKey: 'sk-new-key-5678' })).toBe(true);
    expect(config.customProviders[id]).toEqual({ name: 'Renamed', baseUrl: 'https://new.example.com/v1' });
    expect(config.baseUrls[id]).toBe('https://new.example.com/v1');
    expect(config.apiKeys[id]).toBe('sk-new-key-5678');
    expect(updateCustomProvider(config, 'nope', { name: 'x' })).toBe(false);
  });

  it('follows at once in the running session when the provider is the one in use', () => {
    const config: any = {};
    const id = addCustomProvider(config, gw);
    Object.assign(config, { provider: id, baseUrl: gw.baseUrl, apiKey: gw.apiKey });
    updateCustomProvider(config, id, { baseUrl: 'https://new.example.com/v1', apiKey: 'sk-new-key-5678' });
    expect(config.baseUrl).toBe('https://new.example.com/v1');
    expect(config.apiKey).toBe('sk-new-key-5678');

    const other = addCustomProvider(config, { ...gw, name: 'Other' });
    updateCustomProvider(config, other, { baseUrl: 'https://other.example.com/v1' });
    expect(config.baseUrl).toBe('https://new.example.com/v1'); // not touched: another provider is in use
  });

  it('deleting removes the provider with its key and address', () => {
    const config: any = { apiKeys: { openrouter: 'sk-or' } };
    const id = addCustomProvider(config, gw);
    expect(removeCustomProvider(config, id)).toEqual({ removed: true, wasActive: false });
    expect(customProviders(config)).toEqual({});
    expect(config.apiKeys).toEqual({ openrouter: 'sk-or' });
    expect(config.baseUrls).toEqual({});
    expect(removeCustomProvider(config, id)).toEqual({ removed: false, wasActive: false });
  });

  it('deleting the provider in use keeps the running session going but forgets the id, so a save writes nothing back', () => {
    const config: any = {};
    const id = addCustomProvider(config, gw);
    Object.assign(config, { provider: id, baseUrl: gw.baseUrl, apiKey: gw.apiKey });
    expect(removeCustomProvider(config, id)).toEqual({ removed: true, wasActive: true });
    expect(config.provider).toBeUndefined();
    expect(config.baseUrl).toBe(gw.baseUrl);
    expect(config.apiKey).toBe(gw.apiKey);
  });
});

describe('the old single Custom entry', () => {
  it('becomes a saved provider named Custom, with the address it had', () => {
    const config: any = { provider: 'custom', baseUrl: 'https://old.example.com/v1', apiKeys: { custom: 'sk-old' }, baseUrls: { custom: 'https://old.example.com/v1' } };
    expect(migrateLegacyCustom(config)).toBe(true);
    expect(config.customProviders.custom).toEqual({ name: 'Custom', baseUrl: 'https://old.example.com/v1' });
    expect(listCustomProviders(config)[0]).toMatchObject({ id: 'custom', name: 'Custom', apiKey: 'sk-old' });
    expect(migrateLegacyCustom(config)).toBe(false); // once
  });

  it('takes the address from the active config when the map has none, and does nothing without any address', () => {
    expect(migrateLegacyCustom({ provider: 'custom', baseUrl: 'https://x.example.com/v1' } as any)).toBe(true);
    expect(migrateLegacyCustom({ provider: 'openrouter', baseUrl: 'https://openrouter.ai/api/v1' } as any)).toBe(false);
    expect(migrateLegacyCustom({} as any)).toBe(false);
  });
});

describe('names for display', () => {
  it('shows the name of a saved provider, the label of a listed one, else the id', () => {
    const config: any = {};
    const id = addCustomProvider(config, gw);
    const listed = [{ value: 'openrouter', label: 'OpenRouter' }];
    expect(providerDisplayName(config, id, listed)).toBe('My gateway');
    expect(providerDisplayName(config, 'openrouter', listed)).toBe('OpenRouter');
    expect(providerDisplayName(config, 'unknown-one', listed)).toBe('unknown-one');
    expect(providerDisplayName(config, undefined)).toBe('');
  });
});

describe('saving next to another devx process (config-merge)', () => {
  const base = { model: 'm', apiKeys: { openrouter: 'sk-or' }, customProviders: { 'custom-a': { name: 'A', baseUrl: 'https://a.example.com/v1' } } };

  it('adds a provider without losing what the other process saved meanwhile', () => {
    const mine = cloneConfig(base) as any;
    addCustomProvider(mine, { ...gw, name: 'B' });
    const disk = { ...cloneConfig(base), theme: 'nord', apiKeys: { openrouter: 'sk-or', groq: 'sk-groq' } };
    const merged = mergeConfigChanges(base, mine, disk) as any;
    expect(Object.keys(merged.customProviders).sort()).toEqual(['custom-a', 'custom-b']);
    expect(merged.theme).toBe('nord');
    expect(merged.apiKeys.groq).toBe('sk-groq');
    expect(merged.apiKeys['custom-b']).toBe(gw.apiKey);
  });

  it('a deleted provider is gone from the file, together with its key, and the others stay', () => {
    const start: any = cloneConfig(base);
    addCustomProvider(start, { ...gw, name: 'B' });
    const mine = cloneConfig(start) as any;
    removeCustomProvider(mine, 'custom-b');
    const merged = mergeConfigChanges(start, mine, cloneConfig(start)) as any;
    expect(Object.keys(merged.customProviders)).toEqual(['custom-a']);
    expect(merged.apiKeys['custom-b']).toBeUndefined();
    expect(merged.baseUrls?.['custom-b']).toBeUndefined();
  });

  it('deleting the provider in use removes `provider` from the file as well', () => {
    const start: any = cloneConfig(base);
    const id = addCustomProvider(start, gw);
    Object.assign(start, { provider: id, baseUrl: gw.baseUrl, apiKey: gw.apiKey });
    const mine = cloneConfig(start) as any;
    removeCustomProvider(mine, id);
    const merged = mergeConfigChanges(start, mine, cloneConfig(start)) as any;
    expect(merged.provider).toBeUndefined();
    expect(merged.customProviders[id]).toBeUndefined();
  });
});
