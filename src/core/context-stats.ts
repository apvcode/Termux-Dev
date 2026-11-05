import { History } from './history.js';
import { Message, ToolDefinition } from './types.js';

/** The tool schemas travel with every request and count against the context window. */
export function toolSchemaTokens(definitions: ToolDefinition[]): number {
  return Math.ceil(JSON.stringify(definitions).length * 0.33);
}

/** The share of the context window used for the prompt; the rest is left for the answer. */
export const CONTEXT_USAGE_RATIO = 0.85;

export interface ContextBreakdown {
  /** Size of the window. */
  limit: number;
  system: number;
  tools: number;
  toolCount: number;
  user: number;
  assistant: number;
  toolResults: number;
  images: number;
  imageCount: number;
  /** Everything the next request carries. */
  total: number;
  /** Where devx starts to drop old messages. */
  compactAt: number;
  free: number;
}

/** What fills the context window, by kind. The numbers are devx's estimate (about 3 characters per token). */
export function contextBreakdown(messages: Message[], definitions: ToolDefinition[], limit: number): ContextBreakdown {
  const meter = new History();
  const of = (role: Message['role']) =>
    meter.estimateTokens(messages.filter((m) => m.role === role).map((m) => ({ ...m, images: undefined })));

  const imageCount = messages.reduce((n, m) => n + (m.images?.length ?? 0), 0);
  const images = meter.estimateTokens(messages.filter((m) => m.images?.length).map((m) => ({ role: 'user' as const, content: '', images: m.images })));

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
