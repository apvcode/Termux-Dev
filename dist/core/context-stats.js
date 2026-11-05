import { History } from './history.js';
/** The tool schemas travel with every request and count against the context window. */
export function toolSchemaTokens(definitions) {
    return Math.ceil(JSON.stringify(definitions).length * 0.33);
}
/** The share of the context window used for the prompt; the rest is left for the answer. */
export const CONTEXT_USAGE_RATIO = 0.85;
/** What fills the context window, by kind. The numbers are devx's estimate (about 3 characters per token). */
export function contextBreakdown(messages, definitions, limit) {
    const meter = new History();
    const of = (role) => meter.estimateTokens(messages.filter((m) => m.role === role).map((m) => ({ ...m, images: undefined })));
    const imageCount = messages.reduce((n, m) => n + (m.images?.length ?? 0), 0);
    const images = meter.estimateTokens(messages.filter((m) => m.images?.length).map((m) => ({ role: 'user', content: '', images: m.images })));
    const system = of('system');
    const tools = definitions.length > 0 ? toolSchemaTokens(definitions) : 0;
    const user = of('user');
    const assistant = of('assistant');
    const toolResults = of('tool');
    const total = system + tools + user + assistant + toolResults + images;
    return {
        limit,
        system, tools, toolCount: definitions.length,
        user, assistant, toolResults,
        images, imageCount,
        total,
        compactAt: Math.floor(limit * CONTEXT_USAGE_RATIO),
        free: Math.max(0, limit - total),
    };
}
