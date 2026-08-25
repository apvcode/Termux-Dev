export let lastPlanReady = null;
export function resetPlanReady() {
    lastPlanReady = null;
}
export const planReadyTool = {
    name: 'plan_ready',
    definition: {
        name: 'plan_ready',
        description: 'Signal that the plan is finalized and ready for user approval and execution.',
        parameters: {
            type: 'object',
            properties: {
                summary: {
                    type: 'string',
                    description: 'A brief 1-2 sentence summary of what will be built or modified.'
                },
                files: {
                    type: 'array',
                    items: { type: 'string' },
                    description: 'List of file paths that will be created or edited.'
                },
                steps: {
                    type: 'array',
                    items: { type: 'string' },
                    description: 'Step-by-step implementation roadmap.'
                }
            },
            required: ['summary']
        }
    },
    validateArgs: (args) => {
        if (!args || typeof args !== 'object') {
            throw new Error('Arguments must be an object');
        }
    },
    execute: async (args) => {
        lastPlanReady = {
            summary: args.summary,
            files: args.files,
            steps: args.steps,
            timestamp: Date.now()
        };
        let output = `Plan finalized: ${args.summary}\n`;
        if (args.files && args.files.length > 0) {
            output += `Files: ${args.files.join(', ')}\n`;
        }
        if (args.steps && args.steps.length > 0) {
            output += `Steps:\n${args.steps.map((s, i) => `${i + 1}. ${s}`).join('\n')}`;
        }
        return output;
    }
};
