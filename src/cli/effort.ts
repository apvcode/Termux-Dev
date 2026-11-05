import {
  LEVELS,
  ReasoningLevel,
  RequestParams,
  effectiveReasoning,
  parseEffortArg,
  withEffort,
} from '../core/reasoning.js';
import { PickerOptions } from './picker.js';

/**
 * The /effort command: which reasoning levels the CURRENT model offers, and setting one.
 * Pure functions on the config object; the caller draws the result and saves the config.
 */

export interface Say {
  kind: 'ok' | 'warn' | 'info';
  text: string;
  /** A second, quieter line. */
  detail?: string;
}

export type EffortChoice = ReasoningLevel | 'auto';

export type EffortCommand =
  | { type: 'say'; say: Say }
  | { type: 'pick'; picker: PickerOptions<EffortChoice> };

const modelName = (config: any) => config.model || 'the current model';

/** Where the levels in the menu come from. */
const SOURCE_NOTE: Record<string, string> = {
  endpoint: 'Levels reported by the endpoint.',
  catalog: 'Levels from the models.dev catalogue.',
  rules: 'Levels inferred from the model name.',
  profile: '',
  none: '',
};

/** What would actually be sent for a level, in one line. */
export function sentText(params: RequestParams | null): string {
  return params ? `sends ${JSON.stringify(params)}` : 'sends nothing (the model default applies)';
}

function offered(levels: ReasoningLevel[]): string {
  return ['auto', ...levels].join(', ');
}

/** Sets the level for the current model (or clears it) and describes what happened. */
export function chooseEffort(config: any, choice: EffortChoice): Say {
  const model = modelName(config);
  if (choice === 'auto') {
    config.effort = withEffort(config.effort, config.model, null);
    return { kind: 'ok', text: `effort auto for ${model}`, detail: 'the model default applies, no reasoning setting is sent' };
  }
  config.effort = withEffort(config.effort, config.model, choice);
  const { caps, params } = effectiveReasoning({ ...config, effortOverride: undefined });
  return { kind: 'ok', text: `effort ${choice} for ${model}`, detail: `${caps.describe(choice)}; ${sentText(params)}` };
}

export function buildEffortPicker(config: any): PickerOptions<EffortChoice> {
  const { caps, level } = effectiveReasoning({ ...config, effortOverride: undefined });
  const missing = LEVELS.filter((l) => !caps.levels.includes(l));

  const notes: string[] = [];
  if (missing.length > 0) notes.push(`Not offered by this model: ${missing.join(', ')}.`);
  if (caps.alwaysOn) notes.push('This model cannot switch reasoning off.');
  const source = SOURCE_NOTE[caps.source];
  if (source) notes.push(source);
  if (caps.confidence === 'guess') notes.push('How the setting is sent to this endpoint is inferred. If the endpoint rejects it, devx retries without it.');
  if (caps.confidence === 'custom') notes.push(caps.note);

  return {
    title: 'effort',
    subtitle: modelName(config),
    current: level ?? 'auto',
    items: [
      { value: 'auto', label: 'auto', detail: 'model default, nothing is sent' },
      ...caps.levels.map((l) => ({ value: l as EffortChoice, label: l, detail: caps.describe(l) })),
    ],
    notes,
  };
}

/**
 * Handles `/effort [argument]`. Without an argument it returns a picker with the levels of
 * the current model; with one it sets it directly. Everything else is an explanation.
 */
export function effortCommand(config: any, arg: string): EffortCommand {
  const model = modelName(config);
  const { caps } = effectiveReasoning({ ...config, effortOverride: undefined });

  if (!caps.supported) {
    const hint = caps.alwaysOn
      ? ''
      : ' If you know how this model takes a reasoning setting, describe it in ~/.devxrc.json under "reasoningProfiles" (see the README).';
    return { type: 'say', say: { kind: 'warn', text: `${model} has no reasoning setting.`, detail: `${caps.note}${hint}` } };
  }

  if (!arg.trim()) return { type: 'pick', picker: buildEffortPicker(config) };

  const parsed = parseEffortArg(arg);
  if (parsed.kind === 'auto') return { type: 'say', say: chooseEffort(config, 'auto') };
  if (parsed.kind === 'invalid') {
    return { type: 'say', say: { kind: 'warn', text: `Unknown level "${parsed.text}".`, detail: `${model} offers: ${offered(caps.levels)}.` } };
  }
  if (!caps.levels.includes(parsed.level)) {
    return { type: 'say', say: { kind: 'warn', text: `${model} does not offer "${parsed.level}".`, detail: `It offers: ${offered(caps.levels)}.` } };
  }
  return { type: 'say', say: chooseEffort(config, parsed.level) };
}
