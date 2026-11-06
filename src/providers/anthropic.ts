import { LLMProvider, LLMRequest, LLMResponse, Message, ThinkingBlock, TokenUsage, ToolCall } from '../core/types.js';
import { UsageTracker } from '../core/usage.js';
import { PriceContext, priceUsage } from '../core/prices.js';
import { NOT_ABOUT_REASONING, PARAMETER_REJECTED, ProviderOptions, errorMessageOf, imageNotSentText, parseToolArguments, readSseData, toolTargetHint } from './shared.js';

/**
 * Speaks the native Anthropic Messages API: `POST {origin}/v1/messages`.
 *
 * What differs from the OpenAI format (see openai.ts):
 *   - the key goes into `x-api-key` and the version into `anthropic-version`
 *   - the system prompt is a top-level field, `max_tokens` is required
 *   - content is a list of typed blocks: text, tool_use, tool_result, thinking
 *   - reasoning blocks carry a signature and are sent back unchanged with the next request
 *   - the stream has typed events (message_start, content_block_delta, ...)
 *   - usage separates cached input from the rest
 */

const API_VERSION = '2023-06-01';
const DEFAULT_MAX_TOKENS = 16000;

// Fields the extra parameters may never overwrite.
const PROTECTED_FIELDS = new Set(['model', 'messages', 'stream', 'tools', 'system']);

const num = (v: unknown): number => (typeof v === 'number' && Number.isFinite(v) && v >= 0 ? v : 0);

/** "https://host/v1/" and "https://host" both mean the same endpoint root. */
export function anthropicRoot(baseUrl: string): string {
  return baseUrl.replace(/\/+$/, '').replace(/\/v1$/, '');
}

function splitDataUrl(dataUrl: string): { mediaType: string; data: string } | null {
  const m = /^data:([^;,]+);base64,(.+)$/s.exec(dataUrl);
  return m ? { mediaType: m[1], data: m[2] } : null;
}

type Block = Record<string, any>;

/** Converts devx messages to the Anthropic shape. */
export function toAnthropicMessages(
  messages: Message[],
  options: { cache?: boolean; thinking?: boolean; images?: boolean } = {}
): { system: string; messages: Array<{ role: 'user' | 'assistant'; content: Block[] }> } {
  const systemParts: string[] = [];
  const out: Array<{ role: 'user' | 'assistant'; content: Block[] }> = [];

  let lastImageIdx = -1;
  messages.forEach((m, i) => {
    if (m.images && m.images.length > 0) lastImageIdx = i;
  });

  const push = (role: 'user' | 'assistant', blocks: Block[]) => {
    if (blocks.length === 0) return;
    const last = out[out.length - 1];
    // Two messages of one role in a row are one message for this API.
    if (last && last.role === role) last.content.push(...blocks);
    else out.push({ role, content: blocks });
  };

  messages.forEach((m, idx) => {
    if (m.role === 'system') {
      if (m.content) systemParts.push(m.content);
      return;
    }

    if (m.role === 'tool') {
      push('user', [{ type: 'tool_result', tool_use_id: m.tool_call_id, content: m.content || '(empty)' }]);
      return;
    }

    if (m.role === 'assistant') {
      const blocks: Block[] = [];
      if (options.thinking !== false) {
        for (const t of m.thinking_blocks ?? []) blocks.push({ ...t });
      }
      if (m.content) blocks.push({ type: 'text', text: m.content });
      for (const call of m.tool_calls ?? []) {
        blocks.push({ type: 'tool_use', id: call.id, name: call.name, input: call.arguments ?? {} });
      }
      push('assistant', blocks.length > 0 ? blocks : [{ type: 'text', text: '(no content)' }]);
      return;
    }

    const blocks: Block[] = [];
    let text = m.content || '';
    if (m.images && m.images.length > 0) {
      if (options.images === false) {
        const note = imageNotSentText(m.images.map((img) => img.path));
        text = text ? `${text}\n${note}` : note;
      } else if (idx === lastImageIdx) {
        for (const img of m.images) {
          const parts = splitDataUrl(img.dataUrl);
          if (parts) blocks.push({ type: 'image', source: { type: 'base64', media_type: parts.mediaType, data: parts.data } });
        }
      } else {
        const refs = m.images.map((img) => `[Attached Image: ${img.path}]`).join(' ');
        text = text ? `${text}\n${refs}` : refs;
      }
    }
    if (text || blocks.length === 0) blocks.push({ type: 'text', text: text || '(empty)' });
    push('user', blocks);
  });

  if (options.cache && out.length > 0) {
    // One breakpoint at the end of the conversation: the whole prefix is read from the cache next time.
    const last = out[out.length - 1];
    const tail = last.content[last.content.length - 1];
    if (tail && tail.type !== 'thinking' && tail.type !== 'redacted_thinking') tail.cache_control = { type: 'ephemeral' };
  }

  return { system: systemParts.join('\n\n'), messages: out };
}

export class AnthropicProvider implements LLMProvider {
  id = 'anthropic-messages';

  private extrasRejected = false;
  private cacheRejected = false;
  private thinkingBlocksRejected = false;
  private maxTokensCap: number | null = null;
  private notices: string[] = [];
  private imageNoticeShown = false;

  constructor(
    private baseUrl: string,
    private apiKey: string,
    private model: string,
    /** Extra request parameters, e.g. the reasoning setting from /effort. */
    private extraBody?: Record<string, unknown>,
    private options: ProviderOptions = {}
  ) {}

  drainNotices(): string[] {
    return this.notices.splice(0);
  }

  private priceContext(): PriceContext {
    return { ...this.options.price, model: this.model, baseUrl: this.baseUrl };
  }

  private hasActiveExtras(): boolean {
    return !this.extrasRejected && !!this.extraBody && Object.keys(this.extraBody).length > 0;
  }

  private buildPayload(request: LLMRequest, stream: boolean): Record<string, any> {
    const converted = toAnthropicMessages(request.messages, { cache: !this.cacheRejected, thinking: !this.thinkingBlocksRejected, images: this.options.images });
    if (this.options.images === false && !this.imageNoticeShown && request.messages.some((m) => m.images?.length)) {
      this.imageNoticeShown = true;
      this.notices.push(`${this.model} cannot read images, so the image was not sent. Switch to a model with vision (/model).`);
    }
    const payload: Record<string, any> = {
      model: this.model,
      max_tokens: this.maxTokensCap ?? DEFAULT_MAX_TOKENS,
      stream,
      messages: converted.messages,
    };

    if (converted.system) {
      payload.system = this.cacheRejected
        ? converted.system
        : [{ type: 'text', text: converted.system, cache_control: { type: 'ephemeral' } }];
    }

    if (request.tools && request.tools.length > 0) {
      payload.tools = request.tools.map((t) => ({ name: t.name, description: t.description, input_schema: t.parameters }));
      if (!this.cacheRejected) payload.tools[payload.tools.length - 1].cache_control = { type: 'ephemeral' };
    }

    if (this.hasActiveExtras()) {
      for (const [key, value] of Object.entries(this.extraBody!)) {
        if (PROTECTED_FIELDS.has(key)) continue;
        // `reasoning_effort` / `reasoning` are OpenAI-style: the Anthropic API refuses them as unknown fields.
        if (key === 'reasoning_effort' || key === 'reasoning') continue;
        // The cap learned from an error must not be undone by the reasoning setting.
        if (key === 'max_tokens' && this.maxTokensCap !== null) continue;
        payload[key] = value;
      }
    }
    return payload;
  }

  private headers(): Record<string, string> {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      'anthropic-version': API_VERSION,
    };
    if (this.apiKey) {
      headers['x-api-key'] = this.apiKey;
      // Gateways in front of the API often read the key from the standard header instead.
      let host = '';
      try {
        host = new URL(this.baseUrl).hostname.toLowerCase();
      } catch {}
      if (host !== 'api.anthropic.com') headers['Authorization'] = `Bearer ${this.apiKey}`;
    }
    return headers;
  }

  /**
   * Posts the request. A 400 that complains about something devx added on its own (the
   * reasoning setting, cache markers, signed reasoning blocks, a too large max_tokens) is
   * repeated once without it, so an unusual gateway never makes the session unusable.
   */
  private async send(request: LLMRequest, stream: boolean): Promise<Response> {
    const url = `${anthropicRoot(this.baseUrl)}/v1/messages`;
    const post = () => {
      const bodyStr = JSON.stringify(this.buildPayload(request, stream));
      UsageTracker.getInstance().recordRequest(Buffer.byteLength(bodyStr, 'utf8'));
      return fetch(url, { method: 'POST', headers: this.headers(), body: bodyStr, signal: request.signal });
    };

    let res = await post();
    let genericTry = false;
    for (let attempt = 0; attempt < 4 && !res.ok && (res.status === 400 || res.status === 422); attempt++) {
      const text = await res.clone().text().catch(() => '');
      const reason = errorMessageOf(text).replace(/\s+/g, ' ').slice(0, 110);

      const cap = /max_tokens/i.test(text) ? this.capFromError(text) : null;
      if (cap !== null) {
        this.maxTokensCap = cap;
      } else if (/signature|redacted_thinking|thinking block/i.test(text) && !this.thinkingBlocksRejected) {
        this.thinkingBlocksRejected = true;
        this.notices.push(`The endpoint refused the saved reasoning blocks (${reason}). Continuing without them.`);
      } else if (/cache_control|cache control/i.test(text) && !this.cacheRejected) {
        this.cacheRejected = true;
      } else if (this.hasActiveExtras() && PARAMETER_REJECTED.test(text)) {
        this.extrasRejected = true;
        this.notices.push(`The endpoint rejected the reasoning setting (${reason}). Continuing without it. /effort auto stops sending it for good.`);
      } else if (this.hasActiveExtras() && !genericTry && !NOT_ABOUT_REASONING.test(errorMessageOf(text))) {
        // The refusal names nothing ("the model rejected this conversation or its parameters"). The reasoning
        // setting is what devx added on its own, so it is tried without it once.
        genericTry = true;
        this.extrasRejected = true;
      } else {
        break;
      }
      res = await post();
    }
    if (genericTry) {
      if (res.ok) {
        this.notices.push('The endpoint refused the request while a reasoning setting was sent; without it the request works. Continuing without it. /effort auto stops sending it for good.');
      } else {
        this.extrasRejected = false;      // it was not the setting: the real error is shown
      }
    }
    return res;
  }

  /** "max_tokens: 16000 > 8192, which is the maximum allowed" -> 8192 */
  private capFromError(text: string): number | null {
    const current = this.maxTokensCap ?? DEFAULT_MAX_TOKENS;
    const numbers = (errorMessageOf(text).match(/\d{3,7}/g) ?? []).map(Number).filter((n) => n >= 256 && n < current);
    if (numbers.length === 0) return null;
    return Math.max(...numbers);
  }

  private async failure(res: Response): Promise<never> {
    const text = await res.text();
    UsageTracker.getInstance().recordResponseChunk(Buffer.byteLength(text, 'utf8'));
    const err: any = new Error(`Provider HTTP Error ${res.status}: ${errorMessageOf(text)}`);
    err.status = res.status;
    throw err;
  }

  private usageOf(raw: { input: number; output: number; cacheRead: number; cacheWrite: number } | null, fallback?: { prompt: number; completion: number }): TokenUsage {
    const guessed = !raw || (raw.input + raw.cacheRead + raw.cacheWrite + raw.output === 0);
    const base = raw ?? { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 };
    // Anthropic reports the cached tokens apart from input_tokens; devx counts them all as prompt.
    const promptTokens = base.input + base.cacheRead + base.cacheWrite || (guessed ? fallback?.prompt ?? 0 : 0);
    const completionTokens = base.output || (guessed ? fallback?.completion ?? 0 : 0);
    const usage: TokenUsage = { promptTokens, completionTokens, totalTokens: promptTokens + completionTokens };
    if (base.cacheRead) usage.cacheReadTokens = base.cacheRead;
    if (base.cacheWrite) usage.cacheWriteTokens = base.cacheWrite;
    if (guessed && fallback) usage.tokensEstimated = true;

    const priced = priceUsage(this.priceContext(), usage);
    if (priced.cost !== undefined) {
      usage.cost = priced.cost;
      usage.costSource = guessed && fallback && priced.costSource !== 'free' ? 'estimated' : priced.costSource;
    }
    UsageTracker.getInstance().recordUsage(usage);
    return usage;
  }

  async chat(request: LLMRequest): Promise<LLMResponse> {
    const res = await this.send(request, false);
    if (!res.ok) await this.failure(res);

    const raw = await res.text();
    UsageTracker.getInstance().recordResponseChunk(Buffer.byteLength(raw, 'utf8'));
    let data: any;
    try {
      data = JSON.parse(raw);
    } catch {
      throw new Error('Invalid JSON response from provider');
    }
    if (data.type === 'error' || data.error) {
      throw new Error(`Provider Error: ${data.error?.message || JSON.stringify(data.error)}`);
    }

    let content = '';
    const toolCalls: ToolCall[] = [];
    const thinkingBlocks: ThinkingBlock[] = [];
    for (const block of Array.isArray(data.content) ? data.content : []) {
      if (block.type === 'text') content += block.text || '';
      else if (block.type === 'thinking') thinkingBlocks.push({ type: 'thinking', thinking: block.thinking || '', signature: block.signature || '' });
      else if (block.type === 'redacted_thinking') thinkingBlocks.push({ type: 'redacted_thinking', data: block.data || '' });
      else if (block.type === 'tool_use') {
        toolCalls.push({
          id: block.id || `toolu_${Math.random().toString(36).slice(2, 9)}`,
          name: block.name,
          arguments: block.input && typeof block.input === 'object' ? block.input : {},
        });
      }
    }
    // A reply cut off by the token limit while a tool call was being written has no usable call.
    if (data.stop_reason === 'max_tokens' && toolCalls.length > 0) {
      for (const call of toolCalls) {
        if (Object.keys(call.arguments).length === 0) call.argsError = parseToolArguments('{').error;
      }
    }

    const u = data.usage;
    const usage = u
      ? this.usageOf({
          input: num(u.input_tokens), output: num(u.output_tokens),
          cacheRead: num(u.cache_read_input_tokens), cacheWrite: num(u.cache_creation_input_tokens),
        })
      : undefined;

    return {
      content: content || null,
      toolCalls: toolCalls.length > 0 ? toolCalls : undefined,
      thinkingBlocks: thinkingBlocks.length > 0 ? thinkingBlocks : undefined,
      usage,
    };
  }

  async *chatStream(request: LLMRequest): AsyncGenerator<any, LLMResponse, unknown> {
    const res = await this.send(request, true);
    if (!res.ok) await this.failure(res);

    const reader = res.body?.getReader();
    if (!reader) {
      const fallback = await this.chat(request);
      yield { type: 'done', response: fallback };
      return fallback;
    }
    if (request.signal) {
      request.signal.addEventListener('abort', () => {
        try {
          reader.cancel().catch(() => {});
        } catch {}
      });
    }

    let content = '';
    let reasoningChars = 0;
    const blocks = new Map<number, { kind: 'text' | 'thinking' | 'redacted' | 'tool'; text: string; signature: string; id: string; name: string; json: string }>();
    const tally = { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 };
    let sawUsage = false;
    let stopReason = '';

    const readUsage = (u: any) => {
      if (!u || typeof u !== 'object') return;
      sawUsage = true;
      // Later events repeat or refine the numbers; a missing field must not erase an earlier value.
      if (u.input_tokens !== undefined) tally.input = num(u.input_tokens);
      if (u.output_tokens !== undefined) tally.output = num(u.output_tokens);
      if (u.cache_read_input_tokens !== undefined) tally.cacheRead = num(u.cache_read_input_tokens);
      if (u.cache_creation_input_tokens !== undefined) tally.cacheWrite = num(u.cache_creation_input_tokens);
    };

    try {
      for await (const payload of readSseData(reader, request.signal, (n) => UsageTracker.getInstance().recordResponseChunk(n))) {
        if (payload === '[DONE]') continue;
        let event: any;
        try {
          event = JSON.parse(payload);
        } catch {
          continue;
        }

        switch (event.type) {
          case 'error':
            throw new Error(`Provider Stream Error: ${event.error?.message || JSON.stringify(event.error)}`);

          case 'message_start':
            readUsage(event.message?.usage);
            break;

          case 'content_block_start': {
            const b = event.content_block || {};
            const kind = b.type === 'tool_use' ? 'tool' : b.type === 'thinking' ? 'thinking' : b.type === 'redacted_thinking' ? 'redacted' : 'text';
            blocks.set(event.index, {
              kind,
              text: kind === 'redacted' ? '' : b.text || b.thinking || '',
              signature: b.signature || '',
              id: b.id || '',
              name: b.name || '',
              json: kind === 'redacted' ? String(b.data || '') : '',
            });
            if (kind === 'tool') yield { type: 'tool_generating', name: b.name || 'tool', bytes: 0 };
            break;
          }

          case 'content_block_delta': {
            const block = blocks.get(event.index);
            const d = event.delta || {};
            if (!block) break;
            if (d.type === 'text_delta' && d.text) {
              block.text += d.text;
              content += d.text;
              yield { type: 'content_delta', delta: d.text };
            } else if (d.type === 'thinking_delta' && d.thinking) {
              block.text += d.thinking;
              reasoningChars += d.thinking.length;
              yield { type: 'reasoning_delta', delta: d.thinking };
            } else if (d.type === 'signature_delta' && d.signature) {
              block.signature += d.signature;
            } else if (d.type === 'input_json_delta' && typeof d.partial_json === 'string') {
              block.json += d.partial_json;
              yield { type: 'tool_generating', name: block.name || 'tool', bytes: block.json.length, targetHint: toolTargetHint(block.json) };
            }
            break;
          }

          case 'message_delta':
            readUsage(event.usage);
            if (event.delta?.stop_reason) stopReason = event.delta.stop_reason;
            break;

          default:
            break; // ping, content_block_stop, message_stop
        }
      }
    } finally {
      try {
        reader.releaseLock();
      } catch {}
    }

    const toolCalls: ToolCall[] = [];
    const thinkingBlocks: ThinkingBlock[] = [];
    for (const index of [...blocks.keys()].sort((a, b) => a - b)) {
      const b = blocks.get(index)!;
      if (b.kind === 'thinking') {
        // A reasoning block without a signature cannot be sent back; it would only make the next request fail.
        if (b.signature) thinkingBlocks.push({ type: 'thinking', thinking: b.text, signature: b.signature });
      } else if (b.kind === 'redacted') {
        thinkingBlocks.push({ type: 'redacted_thinking', data: b.json });
      } else if (b.kind === 'tool') {
        const parsed = parseToolArguments(b.json);
        toolCalls.push({
          id: b.id || `toolu_${Math.random().toString(36).slice(2, 9)}`,
          name: b.name,
          arguments: parsed.args,
          ...(parsed.error ? { argsError: parsed.error } : {}),
        });
      }
    }

    if (!content && reasoningChars === 0 && toolCalls.length === 0) {
      throw new Error(`Provider returned empty response (0 tokens generated). Please check your model '${this.model}', balance, or API key.`);
    }
    if (stopReason === 'max_tokens' && toolCalls.length === 0 && !content && reasoningChars > 0) {
      this.notices.push('The reply was cut off by the output limit while the model was still thinking. Lower /effort or ask again.');
    }

    const usage = this.usageOf(sawUsage ? tally : null, {
      prompt: Math.ceil(JSON.stringify(request.messages).length / 3.5),
      completion: Math.ceil((content.length + reasoningChars) / 3.5),
    });

    const response: LLMResponse = {
      content: content || null,
      toolCalls: toolCalls.length > 0 ? toolCalls : undefined,
      thinkingBlocks: thinkingBlocks.length > 0 ? thinkingBlocks : undefined,
      usage,
    };
    yield { type: 'done', response };
    return response;
  }
}
