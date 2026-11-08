import { input } from '@inquirer/prompts';
import { runPicker, PickerItem } from '../cli/picker.js';
import { palette } from '../cli/ui.js';
import { wrapWords } from '../cli/width.js';
import { t } from '../core/i18n.js';
import { notifyAttention, questionNotice } from '../core/attention.js';
import { Tool } from '../core/types.js';

interface QuestionItem {
  id?: string;
  question: string;
  options: string[];
  allowCustom?: boolean;
}

export const askQuestionsTool: Tool = {
  name: 'ask_questions',
  definition: {
    name: 'ask_questions',
    description: 'Interactive questionnaire modal. MUST BE USED whenever you want to ask the user clarifying questions or get choices (tech stack, game genre, features, UI design, preferences) instead of writing questions in plain markdown text.',
    parameters: {
      type: 'object',
      properties: {
        questions: {
          type: 'array',
          description: 'List of questions to ask the user',
          items: {
            type: 'object',
            properties: {
              id: { type: 'string', description: 'Short identifier or topic' },
              question: { type: 'string', description: 'The question text' },
              options: {
                type: 'array',
                items: { type: 'string' },
                description: '2 to 6 multiple choice options for the user to pick from'
              },
              allowCustom: { type: 'boolean', description: 'Whether to allow typing a custom answer (default true)' }
            },
            required: ['question', 'options']
          }
        }
      },
      required: ['questions']
    }
  },
  validateArgs(args: any) {
    if (!args || !args.questions || !Array.isArray(args.questions) || args.questions.length === 0) {
      throw new Error('questions array is required');
    }
  },
  execute: async (args: { questions: QuestionItem[] }) => {
    if (!process.stdin.isTTY) {
      return JSON.stringify({ note: 'Questions skipped in non-interactive mode', answers: {} });
    }

    if (!args.questions || !Array.isArray(args.questions) || args.questions.length === 0) {
      return 'No questions provided.';
    }

    // The agent waits for the answer: if the terminal is not in front, a notification says so.
    const notice = questionNotice(args.questions);
    notifyAttention(notice.title, notice.body);

    const results: Array<{ question: string; answer: string }> = [];
    const total = args.questions.length;
    const cols = process.stdout.columns || 80;

    console.log();

    // Pause raw mode for the typed answer (the picker handles its own keys)
    const wasRaw = process.stdin.isTTY && (process.stdin as any).isRaw;

    try {
      for (let i = 0; i < total; i++) {
        const q = args.questions[i];
        // The question stays on screen as plain text, the card under it holds the choices.
        for (const line of wrapWords(q.question, Math.max(20, cols - 8))) console.log('  ' + palette.bold(palette.text(line)));

        const items: Array<PickerItem<string>> = q.options.map((opt, idx) => ({ value: opt, label: `${idx + 1}  ${opt}` }));
        if (q.allowCustom !== false) items.push({ value: '__custom__', label: t('questions.custom') });
        items.push({ value: '__skip__', label: t('questions.skip') });

        let answer: string | undefined = await runPicker({
          title: q.id && q.id.length <= 24 ? q.id : t('questions.title'),
          subtitle: total > 1 ? `${i + 1} / ${total}` : undefined,
          items,
        });

        if (answer === '__custom__') {
          if (wasRaw) process.stdin.setRawMode(false);
          try {
            answer = (await input({
              message: palette.accent(t('questions.yourAnswer')),
              validate: (v) => v.trim().length > 0 || t('questions.empty'),
            })).trim();
          } catch {
            answer = undefined;
          } finally {
            if (wasRaw) process.stdin.setRawMode(true);
            process.stdin.resume();
          }
        } else if (answer === '__skip__') {
          answer = undefined;
        }

        const given = answer ?? t('questions.skipped');
        results.push({ question: q.question, answer: given });
        // What was asked and what was answered stays in the conversation, quietly.
        console.log(renderAnswerRow(q.question, given, answer === undefined, cols));
      }
    } finally {
      if (wasRaw && process.stdin.isTTY) {
        process.stdin.setRawMode(true);
        process.stdin.resume();
      }
    }

    console.log();
    return results.map((r) => `Q: ${r.question}\n\nA: ${r.answer}`).join('\n\n');
  }
};

/** `  ✓ question` and the answer under it; a skipped one is muted. */
export function renderAnswerRow(question: string, answer: string, skipped: boolean, cols: number): string {
  const room = Math.max(10, cols - 8);
  const q = wrapWords(question.replace(/\s+/g, ' ').trim(), room);
  const a = wrapWords(answer.replace(/\s+/g, ' ').trim(), room - 2);
  const head = q.map((line, i) => (i === 0 ? `  ${skipped ? palette.faint('-') : palette.ok('✓')} ${palette.muted(line)}` : `    ${palette.muted(line)}`));
  const body = a.map((line, i) => `    ${i === 0 ? palette.accent('›') : ' '} ${skipped ? palette.faint(line) : palette.text(line)}`);
  return [...head, ...body].join('\n');
}
