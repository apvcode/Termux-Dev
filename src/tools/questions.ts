import { select, input } from '@inquirer/prompts';
import pc from 'picocolors';
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

    const results: Array<{ question: string; answer: string }> = [];
    const total = args.questions.length;

    console.log();

    // Pause raw mode for interactive prompt
    const wasRaw = process.stdin.isTTY && (process.stdin as any).isRaw;
    if (wasRaw) {
      process.stdin.setRawMode(false);
    }

    try {
      for (let i = 0; i < total; i++) {
        const q = args.questions[i];
        const stepHeader = pc.cyan(`‹ ${i + 1} of ${total} ›`);
        const messageTitle = `${pc.bold(q.question)}  ${stepHeader}`;

        const choices: Array<{ name: string; value: string }> = q.options.map((opt, idx) => ({
          name: `${pc.cyan(String(idx + 1))}  ${opt}`,
          value: opt
        }));

        if (q.allowCustom !== false) {
          choices.push({
            name: `${pc.dim('✏️  Type custom answer...')}`,
            value: '__custom__'
          });
        }

        choices.push({
          name: `${pc.dim('⏭️  Skip')}`,
          value: '__skip__'
        });

        try {
          let answer = await select({
            message: messageTitle,
            choices,
            pageSize: Math.min(8, choices.length)
          });

          if (answer === '__custom__') {
            const customAns = await input({
              message: pc.cyan('Your custom answer:'),
              validate: (v) => v.trim().length > 0 || 'Answer cannot be empty'
            });
            answer = customAns.trim();
          } else if (answer === '__skip__') {
            answer = '(Skipped)';
          }

          results.push({
            question: q.question,
            answer
          });
        } catch {
          results.push({
            question: q.question,
            answer: '(Skipped)'
          });
        }
      }
    } finally {
      // Restore raw mode
      if (wasRaw && process.stdin.isTTY) {
        process.stdin.setRawMode(true);
        process.stdin.resume();
      }
    }

    // Print styled Q&A block in console
    console.log('\n' + pc.cyan('────────────────────────────────────────────'));
    const formattedBlocks = results.map(r => {
      console.log(pc.bold(pc.white(`Q: ${r.question}`)));
      console.log(pc.green(`A: ${r.answer}\n`));
      return `Q: ${r.question}\n\nA: ${r.answer}`;
    });
    console.log(pc.cyan('────────────────────────────────────────────\n'));

    return formattedBlocks.join('\n\n');
  }
};
