import { en, Messages, MsgKey } from './locales/en.js';
import { ru } from './locales/ru.js';
import { uk } from './locales/uk.js';
import { de } from './locales/de.js';
import { es } from './locales/es.js';
import { fr } from './locales/fr.js';
import { pt } from './locales/pt.js';
import { zh } from './locales/zh.js';
import { ja } from './locales/ja.js';

/**
 * Interface language.
 *
 * English is the default and the fallback. A language is chosen in the settings (`language`
 * in the config) and applies at once. Only what devx itself says is translated: messages,
 * hints and the settings screen. Code, file names, tool names and the model's own answers
 * are not touched.
 */

export interface Language {
  id: string;
  /** The name in its own language: what the settings show. */
  name: string;
}

export const LANGUAGES: Language[] = [
  { id: 'en', name: 'English' },
  { id: 'ru', name: 'Русский' },
  { id: 'uk', name: 'Українська' },
  { id: 'de', name: 'Deutsch' },
  { id: 'es', name: 'Español' },
  { id: 'fr', name: 'Français' },
  { id: 'pt', name: 'Português' },
  { id: 'zh', name: '中文' },
  { id: 'ja', name: '日本語' },
];

export const LANGUAGE_IDS = LANGUAGES.map((l) => l.id);
export const DEFAULT_LANGUAGE = 'en';

const CATALOGS: Record<string, Messages> = { en, ru, uk, de, es, fr, pt, zh, ja };

let current = DEFAULT_LANGUAGE;

/** Switches the language. An unknown id (a typo in the config file) means English. */
export function setLanguage(id: string | undefined): string {
  current = id && CATALOGS[id] ? id : DEFAULT_LANGUAGE;
  return current;
}

export function getLanguage(): string {
  return current;
}

export function languageName(id: string): string {
  return LANGUAGES.find((l) => l.id === id)?.name ?? LANGUAGES[0].name;
}

/** The text for a key in the current language, with `{name}` replaced from `vars`. */
export function t(key: MsgKey, vars?: Record<string, string | number>): string {
  const text = CATALOGS[current]?.[key] ?? en[key];
  if (!vars) return text;
  return text.replace(/\{(\w+)\}/g, (whole, name: string) => (name in vars ? String(vars[name]) : whole));
}

export type { MsgKey };
