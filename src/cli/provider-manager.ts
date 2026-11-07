import { runPicker } from './picker.js';
import { runTextInput } from './finder.js';
import { askConfirm } from './confirm.js';
import { renderNotice } from './ui.js';
import { displayWidth } from './width.js';
import { t } from '../core/i18n.js';
import {
  NO_KEY,
  addCustomProvider,
  checkBaseUrl,
  customProviders,
  hostOfUrl,
  keyHint,
  nameProblem,
  removeCustomProvider,
  updateCustomProvider,
  usuallyNeedsNoKey,
} from '../core/custom-providers.js';

/**
 * The screens for providers the user adds by hand: a name, an address and a key, saved in the
 * config; changed and deleted later from the same list. Every screen is one of the small cards
 * of the rest of devx (a field, or a short list), Esc goes one step back.
 */

export interface ProviderUi {
  text: typeof runTextInput;
  picker: typeof runPicker;
  confirm: typeof askConfirm;
}

const realUi: ProviderUi = { text: runTextInput, picker: runPicker, confirm: askConfirm };

export interface ProviderFlowDeps {
  /** Writes the config to disk. Left out while the first-run setup is still going (it saves at its end). */
  save?: (config: any) => Promise<unknown>;
  /** Ids of the providers of the list, so that a new id never equals one of them. */
  listedIds?: string[];
  ui?: ProviderUi;
  say?: (kind: 'ok' | 'info' | 'warn', text: string) => void;
}

const defaultSay = (kind: 'ok' | 'info' | 'warn', text: string) => console.log(renderNotice(kind, text));

const problemText = {
  empty: () => t('provider.name.empty'),
  long: () => t('provider.name.long'),
  taken: () => t('provider.name.taken'),
};

/** The name of the provider; asked again with the reason when it cannot be used. undefined: Esc. */
export async function askName(config: any, ui: ProviderUi, opts: { initial?: string; exceptId?: string; subtitle?: string } = {}): Promise<string | undefined> {
  let value = opts.initial ?? '';
  let warning = '';
  for (;;) {
    const typed = await ui.text({
      title: t('provider.new'),
      subtitle: opts.subtitle,
      label: t('provider.name.label'),
      initial: value,
      placeholder: t('provider.name.placeholder'),
      notes: warning ? [warning] : undefined,
    });
    if (typed === undefined) return undefined;
    const problem = nameProblem(config, typed, opts.exceptId);
    if (!problem) return typed.trim();
    value = typed;
    warning = problemText[problem]();
  }
}

/** The address of the API; asked again with the reason when it is not one. undefined: Esc. */
export async function askUrl(ui: ProviderUi, opts: { initial?: string; subtitle?: string } = {}): Promise<string | undefined> {
  let value = opts.initial ?? '';
  let warning = '';
  for (;;) {
    const typed = await ui.text({
      title: t('onboard.baseUrl.title'),
      subtitle: opts.subtitle,
      label: t('onboard.baseUrl.label'),
      initial: value,
      placeholder: t('onboard.baseUrl.placeholder'),
      notes: [...(warning ? [warning] : []), t('provider.url.note')],
    });
    if (typed === undefined) return undefined;
    const checked = checkBaseUrl(typed);
    if (checked.ok) return checked.url;
    value = typed;
    warning = checked.reason === 'scheme' ? t('provider.url.scheme') : t('provider.url.invalid');
  }
}

/**
 * The key of the provider. A server on this machine or network may need none: that is offered
 * first. Returns the key, NO_KEY for "none", or undefined for Esc.
 */
export async function askKey(ui: ProviderUi, opts: { name: string; baseUrl: string; subtitle?: string }): Promise<string | undefined> {
  if (usuallyNeedsNoKey(opts.baseUrl)) {
    const choice = await ui.picker<'none' | 'key'>({
      title: t('onboard.key.title'),
      subtitle: opts.name,
      items: [
        { value: 'none', label: t('provider.key.none'), detail: t('provider.key.noneDetail') },
        { value: 'key', label: t('provider.key.enter') },
      ],
      current: 'none',
    });
    if (choice === undefined) return undefined;
    if (choice === 'none') return NO_KEY;
  }
  const key = await ui.text({
    title: t('onboard.key.title'),
    subtitle: opts.subtitle ?? opts.name,
    label: t('onboard.key.label', { provider: opts.name }),
    secret: true,
    notes: [t('onboard.key.note')],
  });
  return key === undefined ? undefined : key.trim();
}

/** Adds a provider: name, address, key. Returns its id, or null when the user went back out of the first step. */
export async function addProviderFlow(config: any, deps: ProviderFlowDeps = {}): Promise<string | null> {
  const ui = deps.ui ?? realUi;
  const say = deps.say ?? defaultSay;
  let stage: 'name' | 'url' | 'key' = 'name';
  let name = '';
  let baseUrl = '';

  for (;;) {
    if (stage === 'name') {
      const got = await askName(config, ui, { initial: name, subtitle: '1/3' });
      if (got === undefined) return null;
      name = got;
      stage = 'url';
    } else if (stage === 'url') {
      const got = await askUrl(ui, { initial: baseUrl, subtitle: '2/3' });
      if (got === undefined) { stage = 'name'; continue; }
      baseUrl = got;
      stage = 'key';
    } else {
      const got = await askKey(ui, { name, baseUrl, subtitle: '3/3' });
      if (got === undefined) { stage = 'url'; continue; }
      const id = addCustomProvider(config, { name, baseUrl, apiKey: got }, deps.listedIds);
      await deps.save?.(config);
      say('ok', t('provider.saved', { name }));
      return id;
    }
  }
}

/**
 * The card of one saved provider: use it, change its name, address or key, delete it.
 * Returns `use` when the user wants to work with it, `back` for Esc or after a delete.
 */
export async function manageProviderFlow(config: any, id: string, deps: ProviderFlowDeps = {}): Promise<'use' | 'back'> {
  const ui = deps.ui ?? realUi;
  const say = deps.say ?? defaultSay;
  type Action = 'use' | 'rename' | 'url' | 'key' | 'delete';

  for (;;) {
    const own = customProviders(config)[id];
    if (!own) return 'back';
    const key = config.apiKeys?.[id] ?? '';
    const items: Array<{ value: Action; label: string; detail: string }> = [
      { value: 'use', label: t('provider.menu.use'), detail: hostOfUrl(own.baseUrl) },
      { value: 'rename', label: t('provider.menu.rename'), detail: own.name },
      { value: 'url', label: t('provider.menu.url'), detail: own.baseUrl },
      { value: 'key', label: t('provider.menu.key'), detail: keyHint(key) },
      { value: 'delete', label: t('provider.menu.delete'), detail: t('provider.menu.deleteDetail') },
    ];
    const action = await ui.picker<Action>({
      title: t('onboard.provider.title'),
      subtitle: own.name,
      items,
      current: 'use',
      // The labels differ from language to language: the column is as wide as the longest one.
      labelWidth: Math.max(...items.map((i) => displayWidth(i.label))) + 2,
    });
    if (action === undefined) return 'back';
    if (action === 'use') return 'use';

    if (action === 'rename') {
      const name = await askName(config, ui, { initial: own.name, exceptId: id });
      if (name === undefined || name === own.name) continue;
      updateCustomProvider(config, id, { name });
      await deps.save?.(config);
      say('ok', t('provider.updated', { name }));
    } else if (action === 'url') {
      const url = await askUrl(ui, { initial: own.baseUrl });
      if (url === undefined || url === own.baseUrl) continue;
      updateCustomProvider(config, id, { baseUrl: url });
      await deps.save?.(config);
      say('ok', t('provider.updated', { name: own.name }));
    } else if (action === 'key') {
      const got = await askKey(ui, { name: own.name, baseUrl: own.baseUrl });
      if (got === undefined) continue;
      updateCustomProvider(config, id, { apiKey: got });
      await deps.save?.(config);
      say('ok', t('provider.updated', { name: own.name }));
    } else {
      const active = config.provider === id;
      const yes = await ui.confirm({
        title: t('onboard.provider.title'),
        question: t('provider.delete.question', { name: own.name }),
        details: [hostOfUrl(own.baseUrl), ...(active ? [t('provider.delete.active')] : [])],
        yes: t('provider.menu.delete'),
        initial: false,
      });
      if (!yes) continue;
      removeCustomProvider(config, id);
      await deps.save?.(config);
      say('ok', t('provider.deleted', { name: own.name }));
      return 'back';
    }
  }
}
