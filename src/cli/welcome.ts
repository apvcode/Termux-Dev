import { DEVX_LOGO } from './logo.js';
import { palette } from './ui.js';
import { displayWidth, padEndWidth, truncateWidth, wrapWords } from './width.js';
import { t } from '../core/i18n.js';

const LOGO = DEVX_LOGO;

/** The steps of the first run, in the order they are asked. */
export const SETUP_STEPS = ['welcome.step.language', 'welcome.step.theme', 'welcome.step.provider', 'welcome.step.key', 'welcome.step.model'] as const;

/** The card shown when devx is opened for the first time. `done` steps are ticked, the next one is highlighted. */
export function renderWelcome(version: string, cols: number, done = 0): string[] {
  const steps = SETUP_STEPS.map((key, i) => {
    const label = t(key);
    if (i < done) return palette.ok('✓') + ' ' + palette.muted(label);
    if (i === done) return palette.accent(String(i + 1)) + ' ' + palette.bold(palette.text(label));
    return palette.faint(String(i + 1)) + ' ' + palette.faint(label);
  });

  if (cols < 60) {
    const width = Math.max(20, cols - 2);
    const lines = ['', ...LOGO.map((l) => ' ' + palette.bold(palette.accent(l))), ' ' + palette.faint(`v${version}`), ''];
    for (const line of wrapWords(t('welcome.tagline'), width)) lines.push(' ' + palette.text(line));
    lines.push('');
    for (const s of steps) lines.push(' ' + s);
    lines.push('');
    return lines;
  }

  const W = Math.min(cols - 1, 76);
  const iw = W - 4;
  const rule = palette.rule;
  const box = (content: string) => rule('│') + ' ' + padEndWidth(content, iw) + ' ' + rule('│');
  const head = ' ' + palette.bold(palette.accent('devx')) + ' ';
  const ver = ' ' + palette.faint(`v${version}`) + ' ';
  const lines = [rule('╭─') + head + rule('─'.repeat(Math.max(0, W - 4 - displayWidth(head) - displayWidth(ver)))) + ver + rule('─╮'), box('')];
  for (const l of LOGO) lines.push(box('  ' + palette.bold(palette.accent(l))));
  lines.push(box(''));
  for (const line of wrapWords(t('welcome.tagline'), iw - 4)) lines.push(box('  ' + palette.bold(palette.text(line))));
  for (const line of wrapWords(t('welcome.intro'), iw - 4)) lines.push(box('  ' + palette.muted(line)));
  lines.push(box(''));
  const row = (sep: string) => '  ' + steps.join(palette.faint(sep));
  const sep = [row('  ·  '), row(' · ')].find((r) => displayWidth(r) <= iw);
  if (sep) lines.push(box(sep));
  else for (const step of steps) lines.push(box('  ' + step));
  lines.push(box(''));
  for (const line of wrapWords(t('welcome.keyNote'), iw - 4)) lines.push(box('  ' + palette.faint(truncateWidth(line, iw - 4))));
  lines.push(rule('╰' + '─'.repeat(W - 2) + '╯'));
  return lines;
}
