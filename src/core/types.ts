export type Role = 'system' | 'user' | 'assistant' | 'tool';

export interface ToolCall {
  id: string;
  name: string;
  arguments: any;
}

export interface Message {
  role: Role;
  content: string;
  images?: Array<{ path: string; dataUrl: string }>;
  tool_calls?: ToolCall[];
  tool_call_id?: string;
  name?: string;
}

export interface ToolDefinition {
  name: string;
  description: string;
  parameters: {
    type: 'object';
    properties: Record<string, any>;
    required?: string[];
  };
}

export interface Tool {
  name: string;
  definition: ToolDefinition;
  validateArgs(args: any): void;
  execute(args: any, config?: any): Promise<string>;
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
  cost?: number;
}

export interface LLMResponse {
  content: string | null;
  toolCalls?: ToolCall[];
  usage?: TokenUsage;
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
}

export interface AgentConfig {
  maxContextTokens: number;
  maxIterations: number;
  provider?: string;
  model?: string;
  apiKey?: string;
  baseUrl?: string;
  planMode?: boolean;
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
  | { type: 'usage'; usage: TokenUsage };
