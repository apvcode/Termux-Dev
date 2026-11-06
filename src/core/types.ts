import type { CostSource } from './cost.js';

export const DEVX_VERSION = '1.8.1';

export type Role = 'system' | 'user' | 'assistant' | 'tool';

export interface ToolCall {
  id: string;
  name: string;
  arguments: any;
  /**
   * Set by the provider when the model's tool arguments could not be parsed
   * (typically because the response was cut off by the output token limit).
   * The tool is not executed; the model gets this text back instead.
   */
  argsError?: string;
}

export interface Message {
  role: Role;
  content: string;
  images?: Array<{ path: string; dataUrl: string }>;
  tool_calls?: ToolCall[];
  tool_call_id?: string;
  name?: string;
  /**
   * Signed reasoning blocks of an assistant turn (Anthropic format). They are sent back
   * unchanged so the model keeps its chain of thought across tool calls.
   */
  thinking_blocks?: ThinkingBlock[];
  /**
   * The reasoning the model showed before this reply, kept with the saved session so a resumed
   * chat can unfold it again (ctrl+p). For the screen only: never sent to a provider and not
   * counted in the context.
   */
  reasoning?: { text: string; sec: string; stats: string };
  /** When a user message was sent (for /rewind); screen and session only. */
  at?: number;
  /** The snapshot of the folder taken before this turn (for /rewind); screen and session only. */
  checkpoint?: string;
}

export type ThinkingBlock =
  | { type: 'thinking'; thinking: string; signature: string }
  | { type: 'redacted_thinking'; data: string };

export interface ToolDefinition {
  name: string;
  description: string;
  parameters: {
    type: 'object';
    properties: Record<string, any>;
    required?: string[];
  };
}

export interface ToolExecutionContext {
  signal?: AbortSignal;
}

export interface Tool {
  name: string;
  definition: ToolDefinition;
  validateArgs(args: any): void;
  execute(args: any, config?: any, context?: ToolExecutionContext): Promise<string>;
}

export interface LLMRequest {
  messages: Message[];
  tools?: ToolDefinition[];
  signal?: AbortSignal;
}

export interface TokenUsage {
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  /** Part of promptTokens that was served from the prompt cache. */
  cacheReadTokens?: number;
  /** Part of promptTokens that was written to the prompt cache. */
  cacheWriteTokens?: number;
  /** Tokens spent on reasoning (already included in completionTokens). */
  reasoningTokens?: number;
  /** The token counts are devx's own estimate: the endpoint did not report them. */
  tokensEstimated?: boolean;
  /** Cost in USD; undefined when the price of the model is not known. */
  cost?: number;
  /** reported: the provider said it; listed: the endpoint's own price list; estimated: catalogue price. */
  costSource?: CostSource;
}

export interface LLMResponse {
  content: string | null;
  toolCalls?: ToolCall[];
  usage?: TokenUsage;
  thinkingBlocks?: ThinkingBlock[];
}

export type LLMStreamChunk =
  | { type: 'reasoning_delta'; delta: string }
  | { type: 'content_delta'; delta: string }
  | { type: 'tool_generating'; name: string; bytes: number; targetHint?: string }
  | { type: 'done'; response: LLMResponse };

export interface LLMProvider {
  id: string;
  chat(request: LLMRequest): Promise<LLMResponse>;
  chatStream?(request: LLMRequest): AsyncGenerator<LLMStreamChunk, LLMResponse, unknown>;
  /** Messages for the user collected while talking to the endpoint (returned once). */
  drainNotices?(): string[];
}

export interface UIConfig {
  layout?: 'clean' | 'classic';
  liveTelemetry?: boolean;
  powerlineStatus?: boolean;
  modeBorder?: boolean;
  diffColors?: boolean;
  commandPalette?: boolean;
  inputTokenCounter?: boolean;
  promptHistoryFuzzy?: boolean;
}

export type BannerMode = 'clean' | 'full' | 'minimal' | 'off';

export interface AgentConfig {
  /** Interface language id (see core/i18n.ts); English when missing. */
  language?: string;
  maxContextTokens: number;
  maxIterations: number;
  provider?: string;
  model?: string;
  apiKey?: string;
  baseUrl?: string;
  planMode?: boolean;
  autoApprove?: boolean;
  bashAllowlist?: string[];
  /** MCP tools the user allowed with "Always" on an approval card. */
  allowedTools?: string[];
  /** Commands run before and after tools (see core/hooks.ts). */
  hooks?: import('./hooks.js').HooksConfig;
  trustedProjects?: Record<string, boolean>;
  dataSaverLimitMB?: number;
  pureBlackTheme?: boolean;
  theme?: string;
  onboarded?: boolean;
  apiKeys?: Record<string, string>;
  baseUrls?: Record<string, string>;
  maxCostUSD?: number;
  /** Context window per model id, in tokens; it wins over every other source. */
  contextLimits?: Record<string, number>;
  /** Forces one wire format for every model; by default devx picks per model. */
  apiFormat?: 'openai' | 'anthropic';
  /** Own prices per model id, USD per 1M tokens; they win over every other source. */
  prices?: Record<string, { input: number; output: number; cacheRead?: number; cacheWrite?: number }>;
  banner?: BannerMode;
  ui?: UIConfig;
}

export interface PermissionGuard {
  check(toolName: string, args: any): boolean;
  askUser(toolName: string, args: any): Promise<boolean>;
}

export type AgentEvent =
  | { type: 'reasoning_delta'; delta: string }
  | { type: 'text_delta'; delta: string }
  | { type: 'text'; content: string }
  | { type: 'tool_generating'; name: string; bytes: number; targetHint?: string }
  | { type: 'tool_start'; id: string; name: string; argsRaw: string; actionDesc: string }
  | { type: 'tool_end'; id: string; name: string; result: string }
  | { type: 'reconnecting'; attempt: number; maxAttempts: number; delayMs: number; reason: string }
  | { type: 'reconnected'; attempt: number }
  | { type: 'error'; message: string; isFatal: boolean }
  | { type: 'system'; message: string }
  /** A message the user typed while the agent was working; it was added to the conversation. */
  | { type: 'user_message'; content: string }
  | { type: 'usage'; usage: TokenUsage };
