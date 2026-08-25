import pc from 'picocolors';
export let currentTodoList = [];
export function resetTodoList() {
    currentTodoList = [];
}
export function formatTodoListCard(tasks) {
    if (!tasks || tasks.length === 0)
        return '';
    const cols = Math.min(process.stdout.columns || 80, 80);
    const boxWidth = Math.max(30, Math.min(cols - 4, 65));
    const borderChar = '─';
    const lines = [];
    lines.push('\n' + pc.bold(pc.cyan('┌─ 📋 Plan & Tasks ' + borderChar.repeat(Math.max(2, boxWidth - 19)) + '┐')));
    for (const task of tasks) {
        let taskLine = '';
        if (task.status === 'completed') {
            taskLine = pc.green('[✓] ') + pc.dim(task.text);
        }
        else if (task.status === 'in_progress') {
            taskLine = pc.bold(pc.green('[ ] ' + task.text));
        }
        else {
            taskLine = pc.dim('[ ] ') + pc.white(task.text);
        }
        lines.push(pc.cyan('│ ') + taskLine);
    }
    lines.push(pc.cyan('└' + borderChar.repeat(boxWidth) + '┘\n'));
    return lines.join('\n');
}
export const todoListTool = {
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
    validateArgs: (args) => {
        if (!args || !Array.isArray(args.tasks)) {
            throw new Error('tasks must be an array of task objects');
        }
    },
    execute: async (args) => {
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
