import { describe, expect, it, vi } from 'vitest';
import { addProviderFlow, askKey, manageProviderFlow, type ProviderUi } from '../../src/cli/provider-manager.js';
import { NO_KEY, addCustomProvider } from '../../src/core/custom-providers.js';

/** A user who answers the cards one after the other: a value is the answer, `undefined` is Esc. */
function user(answers: unknown[]) {
  const calls: Array<{ kind: 'text' | 'picker' | 'confirm'; options: any }> = [];
  const next = () => {
    if (answers.length === 0) throw new Error('the flow asked for more than the test answered');
    return answers.shift();
  };
  const ui = {
    text: async (options: any) => { calls.push({ kind: 'text', options }); return next(); },
    picker: async (options: any) => { calls.push({ kind: 'picker', options }); return next(); },
    confirm: async (options: any) => { calls.push({ kind: 'confirm', options }); return next(); },
  } as unknown as ProviderUi;
  return { ui, calls, left: () => answers.length };
}
const ESC = undefined;
const deps = (ui: ProviderUi) => {
  const saved: any[] = [];
  const said: string[] = [];
  return { ui, save: vi.fn(async (c: any) => { saved.push(JSON.parse(JSON.stringify(c))); }), say: (_k: string, text: string) => said.push(text), saved, said };
};

describe('adding a provider', () => {
  it('asks the name, the address and the key, saves at once, and tells it', async () => {
    const { ui, calls, left } = user(['My gateway', 'https://gw.example.com/v1/', 'sk-secret-key-1234']);
    const d = deps(ui);
    const config: any = { apiKeys: {} };
    const id = await addProviderFlow(config, d);
    expect(id).toBe('custom-my-gateway');
    expect(config.customProviders[id]).toEqual({ name: 'My gateway', baseUrl: 'https://gw.example.com/v1' });
    expect(config.apiKeys[id]).toBe('sk-secret-key-1234');
    expect(d.save).toHaveBeenCalledTimes(1);
    expect(d.saved[0].customProviders[id].name).toBe('My gateway');
    expect(d.said).toEqual(['Provider My gateway saved']);
    expect(left()).toBe(0);
    expect(calls.map((c) => c.options.subtitle)).toEqual(['1/3', '2/3', '3/3']);
    expect(calls[2].options.secret).toBe(true);           // the key is typed hidden
  });

  it('asks again with the reason when the name is empty, too long or already taken, and keeps what was typed', async () => {
    const config: any = {};
    addCustomProvider(config, { name: 'Taken', baseUrl: 'https://t.example.com/v1', apiKey: 'k' });
    const { ui, calls } = user(['taken', 'x'.repeat(50), 'Fresh', 'https://f.example.com/v1', 'sk-key-0000000']);
    const id = await addProviderFlow(config, deps(ui));
    expect(id).toBe('custom-fresh');
    expect(calls[0].options.notes).toBeUndefined();
    expect(calls[1].options.notes).toEqual(['A provider with this name already exists.']);
    expect(calls[1].options.initial).toBe('taken');
    expect(calls[2].options.notes).toEqual(['The name is too long (40 characters at most).']);
    expect(calls[3].options.title).toBe('address');        // the name was accepted: on to the address
  });

  it('asks again when the address is not one, and says what is wrong', async () => {
    const { ui, calls } = user(['Gw', 'api.example.com', 'ftp://example.com', 'https://api.example.com/v1', 'sk-key-0000000']);
    await addProviderFlow({}, deps(ui));
    const urlCards = calls.filter((c) => c.options.title === 'address');
    expect(urlCards).toHaveLength(3);
    expect(urlCards[1].options.notes[0]).toContain('not an address');
    expect(urlCards[1].options.initial).toBe('api.example.com');
    expect(urlCards[2].options.notes[0]).toContain('http://');
  });

  it('Esc goes one step back, with the earlier answers still in the fields; Esc on the first step leaves and saves nothing', async () => {
    const config: any = {};
    const { ui, calls } = user(['Gw', ESC, 'Gw 2', 'https://a.example.com/v1', ESC, ESC, ESC]);
    const d = deps(ui);
    expect(await addProviderFlow(config, d)).toBeNull();
    // name -> url -> (Esc) name again, with the name typed before
    expect(calls[2].options.initial).toBe('Gw');
    expect(calls[2].options.title).toBe('new provider');
    // the key step (4): Esc goes back to the address (5) with its text, Esc again to the name (6)
    expect(calls[4].options.title).toBe('api key');
    expect(calls[5].options.initial).toBe('https://a.example.com/v1');
    expect(calls[6].options.initial).toBe('Gw 2');
    expect(d.save).not.toHaveBeenCalled();
    expect(config.customProviders).toBeUndefined();
  });

  it('a server on this machine can be used without a key; with "Enter a key" the key is asked', async () => {
    const none = user(['Local', 'http://localhost:1234/v1', 'none']);
    const config: any = {};
    const id = (await addProviderFlow(config, deps(none.ui)))!;
    expect(config.apiKeys[id]).toBe(NO_KEY);
    expect(none.calls.filter((c) => c.kind === 'text')).toHaveLength(2);   // name, address: no key field

    const key = user(['Local 2', 'http://127.0.0.1:8000/v1', 'key', 'sk-local-key-111']);
    const id2 = (await addProviderFlow(config, deps(key.ui)))!;
    expect(config.apiKeys[id2]).toBe('sk-local-key-111');
  });

  it('a remote address always asks for a key', async () => {
    const { ui, calls } = user(['Remote', 'https://api.example.com/v1', 'sk-remote-key-22']);
    await addProviderFlow({}, deps(ui));
    expect(calls.some((c) => c.kind === 'picker')).toBe(false);
  });

  it('while the first-run setup is going nothing is written to disk', async () => {
    const { ui } = user(['Gw', 'https://gw.example.com/v1', 'sk-key-0000000']);
    const config: any = {};
    const d = deps(ui);
    const id = await addProviderFlow(config, { ui, say: d.say });
    expect(id).toBe('custom-gw');
  });

  it('trims the key', async () => {
    const config: any = {};
    const { ui } = user(['Gw', 'https://gw.example.com/v1', '  sk-padded-key-33  ']);
    const id = (await addProviderFlow(config, deps(ui)))!;
    expect(config.apiKeys[id]).toBe('sk-padded-key-33');
  });
});

describe('askKey', () => {
  it('Esc at the "no key" question or at the field goes back', async () => {
    expect(await askKey(user([ESC]).ui, { name: 'L', baseUrl: 'http://localhost:1/v1' })).toBeUndefined();
    expect(await askKey(user([ESC]).ui, { name: 'R', baseUrl: 'https://r.example.com/v1' })).toBeUndefined();
  });
});

describe('a saved provider', () => {
  const setup = () => {
    const config: any = { apiKeys: {}, baseUrls: {} };
    const id = addCustomProvider(config, { name: 'Gw', baseUrl: 'https://gw.example.com/v1', apiKey: 'sk-secret-key-1234' });
    return { config, id };
  };

  it('shows its name, address and key (masked) in a card of five actions; Esc goes back, "use" returns use', async () => {
    const { config, id } = setup();
    const esc = user([ESC]);
    expect(await manageProviderFlow(config, id, deps(esc.ui))).toBe('back');
    const card = esc.calls[0].options;
    expect(card.subtitle).toBe('Gw');
    expect(card.items.map((i: any) => i.value)).toEqual(['use', 'rename', 'url', 'key', 'delete']);
    expect(card.items.find((i: any) => i.value === 'key').detail).toBe('sk-s...1234');
    expect(JSON.stringify(card)).not.toContain('sk-secret-key-1234');
    expect(await manageProviderFlow(config, id, deps(user(['use']).ui))).toBe('use');
  });

  it('renames: its own name is allowed, another provider\'s is not; it is saved and the card shows the new name', async () => {
    const { config, id } = setup();
    addCustomProvider(config, { name: 'Other', baseUrl: 'https://o.example.com/v1', apiKey: 'k' });
    const u = user(['rename', 'other', 'Better name', ESC]);
    const d = deps(u.ui);
    expect(await manageProviderFlow(config, id, d)).toBe('back');
    expect(config.customProviders[id].name).toBe('Better name');
    expect(d.save).toHaveBeenCalledTimes(1);
    expect(d.said).toEqual(['Provider Better name updated']);
    expect(u.calls[1].options.initial).toBe('Gw');
    expect(u.calls[2].options.notes).toEqual(['A provider with this name already exists.']);
    expect(u.calls[3].options.subtitle).toBe('Better name');
  });

  it('a name or address left as it was changes and saves nothing', async () => {
    const { config, id } = setup();
    const u = user(['rename', 'Gw', 'url', 'https://gw.example.com/v1/', ESC]);
    const d = deps(u.ui);
    await manageProviderFlow(config, id, d);
    expect(d.save).not.toHaveBeenCalled();
  });

  it('changes the address and the key', async () => {
    const { config, id } = setup();
    const u = user(['url', 'https://new.example.com/v1', 'key', 'sk-new-key-5678', ESC]);
    const d = deps(u.ui);
    await manageProviderFlow(config, id, d);
    expect(config.customProviders[id].baseUrl).toBe('https://new.example.com/v1');
    expect(config.baseUrls[id]).toBe('https://new.example.com/v1');
    expect(config.apiKeys[id]).toBe('sk-new-key-5678');
    expect(d.save).toHaveBeenCalledTimes(2);
  });

  it('a changed address of a local server offers "no key" again', async () => {
    const { config, id } = setup();
    const u = user(['url', 'http://localhost:11434/v1', 'key', 'none', ESC]);
    await manageProviderFlow(config, id, deps(u.ui));
    expect(config.apiKeys[id]).toBe(NO_KEY);
  });

  it('delete asks first; "no" keeps it, "yes" removes it with its key and saves', async () => {
    const { config, id } = setup();
    const no = user(['delete', false, ESC]);
    const dn = deps(no.ui);
    expect(await manageProviderFlow(config, id, dn)).toBe('back');
    expect(config.customProviders[id]).toBeDefined();
    expect(dn.save).not.toHaveBeenCalled();
    expect(no.calls[1].kind).toBe('confirm');
    expect(no.calls[1].options.question).toBe('Delete the provider Gw and its key?');
    expect(no.calls[1].options.initial).toBe(false);

    const yes = user(['delete', true]);
    const dy = deps(yes.ui);
    expect(await manageProviderFlow(config, id, dy)).toBe('back');
    expect(config.customProviders[id]).toBeUndefined();
    expect(config.apiKeys[id]).toBeUndefined();
    expect(dy.saved[0].customProviders).toEqual({});
    expect(dy.said).toEqual(['Provider Gw deleted']);
  });

  it('deleting the provider in use says it keeps working, and the saved config no longer names it', async () => {
    const { config, id } = setup();
    Object.assign(config, { provider: id, baseUrl: 'https://gw.example.com/v1', apiKey: 'sk-secret-key-1234' });
    const u = user(['delete', true]);
    const d = deps(u.ui);
    await manageProviderFlow(config, id, d);
    expect(u.calls[1].options.details.join(' ')).toContain('keeps working');
    expect(d.saved[0].provider).toBeUndefined();
    expect(config.baseUrl).toBe('https://gw.example.com/v1');
  });

  it('a provider that is gone returns back at once', async () => {
    expect(await manageProviderFlow({}, 'custom-nope', deps(user([]).ui))).toBe('back');
  });
});
