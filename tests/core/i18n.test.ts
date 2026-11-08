import { afterEach, describe, expect, it } from 'vitest';
import { DEFAULT_LANGUAGE, LANGUAGES, LANGUAGE_IDS, getLanguage, languageName, setLanguage, t } from '../../src/core/i18n.js';
import { en } from '../../src/core/locales/en.js';
import { ru } from '../../src/core/locales/ru.js';
import { uk } from '../../src/core/locales/uk.js';
import { de } from '../../src/core/locales/de.js';
import { es } from '../../src/core/locales/es.js';
import { fr } from '../../src/core/locales/fr.js';
import { pt } from '../../src/core/locales/pt.js';
import { zh } from '../../src/core/locales/zh.js';
import { ja } from '../../src/core/locales/ja.js';
import { getHumanReadableNetworkError } from '../../src/core/network.js';
import { renderSettings, ROWS, changeSetting, SettingsState, visibleRows } from '../../src/cli/settings.js';
import { displayWidth, stripAnsi } from '../../src/cli/width.js';
import { renderComposer } from '../../src/cli/typeahead.js';
import { renderStatusLine, renderTurnSummary } from '../../src/cli/ui.js';

const catalogs: Record<string, Record<string, string>> = { en, ru, uk, de, es, fr, pt, zh, ja };
const placeholders = (text: string) => (text.match(/\{\w+\}/g) ?? []).sort().join(',');

afterEach(() => setLanguage(DEFAULT_LANGUAGE));

describe('languages', () => {
  it('English is the default', () => {
    expect(DEFAULT_LANGUAGE).toBe('en');
    expect(getLanguage()).toBe('en');
    expect(t('status.waiting')).toBe('Waiting for the model');
  });

  it('every language in the list has a catalogue, and the other way round', () => {
    expect(Object.keys(catalogs).sort()).toEqual([...LANGUAGE_IDS].sort());
    expect(LANGUAGES.length).toBeGreaterThanOrEqual(9);
  });

  it('every catalogue has exactly the keys of English, with the same {values}', () => {
    for (const [id, catalog] of Object.entries(catalogs)) {
      expect(Object.keys(catalog).sort(), id).toEqual(Object.keys(en).sort());
      for (const key of Object.keys(en)) {
        const text = catalog[key];
        expect(text, `${id}:${key}`).toBeTruthy();
        expect(placeholders(text), `${id}:${key}`).toBe(placeholders((en as Record<string, string>)[key]));
      }
    }
  });

  it('switches at once, fills in values and falls back to English for an unknown id', () => {
    expect(setLanguage('ru')).toBe('ru');
    expect(t('composer.queuedMany', { n: 3 })).toBe('в очереди: 3');
    expect(t('status.thoughtFor', { sec: '1.5' })).toBe('думал 1.5 с');
    expect(setLanguage('klingon')).toBe('en');
    expect(setLanguage(undefined)).toBe('en');
    expect(t('composer.queued')).toBe('queued');
    expect(languageName('de')).toBe('Deutsch');
    expect(languageName('nope')).toBe('English');
  });

  it('a value that is not passed stays visible instead of vanishing', () => {
    expect(t('conn.unstable')).toBe('connection unstable ({reason})');
  });
});

describe('what the interface says follows the language', () => {
  it('network errors are in the chosen language, not always in Russian', () => {
    const err = { message: 'Network timeout' };
    expect(getHumanReadableNetworkError(err)).toBe('Network connection timed out');
    setLanguage('ru');
    expect(getHumanReadableNetworkError(err)).toBe('Таймаут сетевого соединения');
    setLanguage('de');
    expect(getHumanReadableNetworkError(err)).toBe('Zeitüberschreitung der Netzwerkverbindung');
    setLanguage('zh');
    expect(getHumanReadableNetworkError(err)).toBe('网络连接超时');
  });

  it('the typing frame, the working line and the turn summary are translated', () => {
    const base = { mode: 'agent' as const, model: 'm', cwd: '/x', contextPercent: 1, cost: 0 };
    setLanguage('fr');
    const frame = renderComposer({ state: { text: '', cursor: 0 }, queued: ['a'], cols: 100, base }).map(stripAnsi).join('\n');
    expect(frame).toContain('Décrivez une tâche');
    expect(frame).toContain('en file');
    expect(stripAnsi(renderStatusLine({ label: 'x', elapsedMs: 1000, frame: 0, columns: 100 }))).toContain('esc pour arrêter');
    expect(stripAnsi(renderTurnSummary({ ms: 1000, tools: 2, outputTokens: 10, cost: 0 }))).toContain('2 outils');
  });
});

describe('the settings in every language', () => {
  const base: SettingsState = {
    themeId: 'dracula', layout: 'clean', pureBlack: true, autoApprove: true, maxIterations: 30, memory: true, updates: true, costLimit: 0, language: 'en',
  };

  it('the language row cycles through all languages and wraps around', () => {
    let state = base;
    const seen = [state.language];
    for (let i = 0; i < LANGUAGE_IDS.length - 1; i++) {
      state = changeSetting(state, 'language', 1);
      seen.push(state.language);
    }
    expect(seen).toEqual(LANGUAGE_IDS);
    expect(changeSetting(state, 'language', 1).language).toBe('en');
    expect(changeSetting(base, 'language', -1).language).toBe(LANGUAGE_IDS[LANGUAGE_IDS.length - 1]);
  });

  it('shows the language by its own name', () => {
    const index = visibleRows(base).findIndex((r) => r.key === 'language');
    const text = renderSettings({ ...base, language: 'ja' }, index, 100, 40, '1.9.9').map(stripAnsi).join('\n');
    expect(text).toMatch(/Language\s+Japanese/);
  });

  it('every row, hint and value fits its box in every language and size', () => {
    for (const id of LANGUAGE_IDS) {
      setLanguage(id);
      for (const [cols, rows] of [[100, 40], [100, 24], [76, 30], [60, 22], [50, 30], [40, 30], [40, 14]]) {
        for (let focus = 0; focus < visibleRows({ ...base, autoApprove: true }).length; focus++) {
          const lines = renderSettings({ ...base, autoApprove: true, extra: true, language: id }, focus, cols, rows, '1.9.9').map(stripAnsi);
          expect(lines.length, `${id} ${cols}x${rows}`).toBeLessThanOrEqual(rows - 1);
          for (const line of lines) expect(displayWidth(line), `${id} ${cols}x${rows}: ${line}`).toBeLessThanOrEqual(cols - 1);
          // In the framed card every row of the box is exactly as wide as the top border.
          const box = lines.filter((l) => /^[╭│├╰]/.test(l));
          if (box.length > 0) expect(new Set(box.map((l) => displayWidth(l))).size, `${id} ${cols}x${rows}`).toBe(1);
        }
      }
    }
  });

  it('a Chinese hint without spaces is wrapped, not cut to one word', () => {
    setLanguage('zh');
    const index = visibleRows(base).findIndex((r) => r.key === 'pureBlack');
    const text = renderSettings({ ...base, language: 'zh' }, index, 100, 40, '1.9.9').map(stripAnsi).join('\n');
    expect(text).toContain('关闭则保留你自己的背景');
  });
});
