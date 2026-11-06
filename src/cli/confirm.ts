import { runPicker } from './picker.js';
import { palette, renderNotice } from './ui.js';
import { wrapWords } from './width.js';
import { renderAnswerRow } from '../tools/questions.js';
import { t } from '../core/i18n.js';

export interface ConfirmOptions {
  /** Short label in the border of the card: `workspace trust`, `exit`. */
  title: string;
  question: string;
  /** Muted lines between the question and the card (a folder, a command). */
  details?: string[];
  /** Labels of the two choices; defaults to Yes / No. */
  yes?: string;
  no?: string;
  /** What Enter picks when nothing was moved. */
  initial?: boolean;
  /** Keep the answer on the screen (default). Leave it out for a question whose answer is obvious from what follows. */
  record?: boolean;
}

/** A yes / no question in the style of the rest of devx: the question, a small card with two choices. Esc means "no". */
export async function askConfirm(options: ConfirmOptions): Promise<boolean> {
  const cols = process.stdout.columns || 80;
  console.log();
  for (const line of wrapWords(options.question, Math.max(20, cols - 8))) console.log('  ' + palette.bold(palette.text(line)));
  for (const line of options.details ?? []) console.log('  ' + palette.faint(line));

  const yes = options.yes ?? t('confirm.yes');
  const no = options.no ?? t('confirm.no');
  const answer = await runPicker<boolean>({
    title: options.title,
    items: [
      { value: true, label: yes },
      { value: false, label: no },
    ],
    current: options.initial === true,
  });
  const result = answer === true;
  if (options.record !== false) console.log(renderAnswerRow(options.question, result ? yes : no, false, cols));
  return result;
}

/** A closing line such as "Goodbye": quiet, in the same style as the other notices. */
export function printFarewell(message: string, kind: 'info' | 'ok' | 'warn' = 'info'): void {
  console.log();
  console.log(renderNotice(kind, message));
  console.log();
}

/** The plan is ready: start on it (Go) or change it. Esc means "change": nothing starts by accident. */
export async function askPlanDecision(): Promise<'go' | 'edit'> {
  const cols = process.stdout.columns || 80;
  console.log();
  for (const line of wrapWords(t('plan.ready'), Math.max(20, cols - 8))) console.log('  ' + palette.bold(palette.text(line)));
  const answer = await runPicker<'go' | 'edit'>({
    title: 'plan',
    items: [
      { value: 'go', label: t('plan.go') },
      { value: 'edit', label: t('plan.edit') },
    ],
    current: 'go',
  });
  return answer === 'go' ? 'go' : 'edit';
}
