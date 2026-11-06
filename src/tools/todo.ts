import { Tool } from '../core/types.js';
import pc from 'picocolors';
import { displayWidth, padEndWidth, truncateWidth } from '../cli/width.js';

export interface TaskItem {
  text: string;
  status: 'pending' | 'in_progress' | 'completed';
}

export let currentTodoList: TaskItem[] = [];

export function resetTodoList() {
  currentTodoList = [];
}

/**
 * The plan as a closed box (classic layout; the clean layout draws it with the theme, see
 * `renderTaskCard`). Every row has the same width, so both borders line up.
 */
export function formatTodoListCard(tasks: TaskItem[]): string {
  if (!tasks || tasks.length === 0) return '';
  const cols = process.stdout.columns || 80;
  const width = Math.max(30, Math.min(cols - 4, 65));
  const inner = width - 4;

  const title = ' Plan & Tasks ';
  const top = '┌─' + title + '─'.repeat(Math.max(1, width - 3 - displayWidth(title))) + '┐';
  const lines: string[] = [pc.bold(pc.cyan(top))];

  for (const task of tasks) {
    const marker = task.status === 'completed' ? '[✓]' : task.status === 'in_progress' ? '[•]' : '[ ]';
    const text = truncateWidth(String(task.text ?? '').replace(/\s+/g, ' ').trim(), inner - 4);
    const plain = padEndWidth(`${marker} ${text}`, inner);
    const body =
      task.status === 'completed' ? pc.green(plain)
      : task.status === 'in_progress' ? pc.bold(pc.cyan(plain))
      : plain;
    lines.push(pc.cyan('│ ') + body + pc.cyan(' │'));
  }

  lines.push(pc.cyan('└' + '─'.repeat(width - 2) + '┘'));
  return lines.join('\n') + '\n';
}

export const todoListTool: Tool = {
  name: 'todo_list',
  definition: {
    name: 'todo_list',
    description: 'Update the live interactive task checklist. Use this to plan multi-step implementations, mark completed steps as "completed", and set the current active step to "in_progress".',
    parameters: {
      type: 'object',
      properties: {
        tasks: {
          type: 'array',
          items: {
            type: 'object',
            properties: {
              text: {
                type: 'string',
                description: 'Short, clear description of the task step'
              },
              status: {
                type: 'string',
                enum: ['pending', 'in_progress', 'completed'],
                description: 'Current status of the task'
              }
            },
            required: ['text', 'status']
          },
          description: 'The updated list of tasks'
        }
      },
      required: ['tasks']
    }
  },
  validateArgs: (args: any) => {
    if (!args || !Array.isArray(args.tasks)) {
      throw new Error('tasks must be an array of task objects');
    }
  },
  execute: async (args: { tasks: TaskItem[] }) => {
    currentTodoList = args.tasks;
    const card = formatTodoListCard(args.tasks);
    const completedCount = args.tasks.filter(t => t.status === 'completed').length;
    const totalCount = args.tasks.length;
    
    return JSON.stringify({
      status: 'success',
      type: 'todo_list',
      tasks: args.tasks,
      displayCard: card,
      summary: `Updated plan progress: ${completedCount}/${totalCount} tasks completed`
    });
  }
};
