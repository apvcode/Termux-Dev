import { LLMProvider, LLMRequest, LLMResponse } from '../core/types.js';
import { UsageTracker } from '../core/usage.js';
import { PARAMETER_REJECTED, ProviderOptions, errorMessageOf, imageNotSentText, openAiUsage, parseToolArguments, toolTargetHint } from './shared.js';

export { parseToolArguments };

// Fields the extra parameters may never overwrite.
const PROTECTED_FIELDS = new Set(['model', 'messages', 'stream', 'tools', 'stream_options']);

export class OpenAIProvider implements LLMProvider {
  id = 'openai-compatible';
  
  /** Set once the endpoint has refused the extra (reasoning) parameters: they are not sent again. */
  private extrasRejected = false;
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

  private priceContext() {
    return { ...this.options.price, model: this.model, baseUrl: this.baseUrl };
  }

  /** Messages for the user produced while talking to the endpoint (shown once). */
  drainNotices(): string[] {
    return this.notices.splice(0);
  }

  private hasActiveExtras(): boolean {
    return !this.extrasRejected && !!this.extraBody && Object.keys(this.extraBody).length > 0;
  }

  /**
   * Posts the request. If the endpoint answers 400/422 and complains about a parameter
   * while reasoning parameters are being sent, the request is repeated once without them:
   * an unknown setting must never make the whole session unusable.
   */
  private async send(request: LLMRequest, stream: boolean): Promise<Response> {
    const url = `${this.baseUrl}/chat/completions`;
    const post = () => {
      const bodyStr = JSON.stringify(this.buildPayload(request, stream));
      const headers: Record<string, string> = { 'Content-Type': 'application/json' };
      if (this.apiKey) headers['Authorization'] = `Bearer ${this.apiKey}`;
      UsageTracker.getInstance().recordRequest(Buffer.byteLength(bodyStr, 'utf8'));
      return fetch(url, { method: 'POST', headers, body: bodyStr, signal: request.signal });
    };

    const res = await post();
    if (res.ok || !this.hasActiveExtras() || (res.status !== 400 && res.status !== 422)) return res;

    const text = await res.clone().text().catch(() => '');
    if (!PARAMETER_REJECTED.test(text)) return res;

    this.extrasRejected = true;
    let reason = text;
    try {
      const parsed = JSON.parse(text);
      reason = parsed.error?.message || parsed.message || text;
    } catch {}
    this.notices.push(
      `The endpoint rejected the reasoning setting (${String(reason).replace(/\s+/g, ' ').slice(0, 110)}). ` +
      `Continuing without it. /effort auto stops sending it for good.`
    );
    return post();
  }

  private buildPayload(request: LLMRequest, stream: boolean) {
    const totalMsgs = request.messages.length;
    let lastImageMsgIdx = -1;
    for (let i = totalMsgs - 1; i >= 0; i--) {
      if (request.messages[i].images && request.messages[i].images!.length > 0) {
        lastImageMsgIdx = i;
        break;
      }
    }

    const payload: any = {
      model: this.model,
      stream,
      messages: request.messages.map((m, idx) => {
        let content: any = m.content || "";
        if (m.images && m.images.length > 0) {
          if (this.options.images === false) {
            const note = imageNotSentText(m.images.map(img => img.path));
            content = m.content ? `${m.content}\n${note}` : note;
            if (!this.imageNoticeShown) {
              this.imageNoticeShown = true;
              this.notices.push(`${this.model} cannot read images, so the image was not sent. Switch to a model with vision (/model).`);
            }
          } else if (idx === lastImageMsgIdx) {
            content = [
              { type: 'text', text: m.content || "" },
              ...m.images.map(img => ({
                type: 'image_url',
                image_url: { url: img.dataUrl }
              }))
            ];
          } else {
            const imgRef = m.images.map(img => `[Attached Image: ${img.path}]`).join(' ');
            content = m.content ? `${m.content}\n${imgRef}` : imgRef;
          }
        }
        const msg: any = { role: m.role, content };
        if (m.name) msg.name = m.name;
        if (m.tool_calls) msg.tool_calls = m.tool_calls.map(tc => ({
          id: tc.id,
          type: 'function',
          function: { name: tc.name, arguments: JSON.stringify(tc.arguments) }
        }));
        if (m.tool_call_id) msg.tool_call_id = m.tool_call_id;
        return msg;
      }),
    };

    if (request.tools && request.tools.length > 0) {
      payload.tools = request.tools.map(t => ({
        type: 'function',
        function: t
      }));
    }

    if (stream) {
      payload.stream_options = { include_usage: true };
    }

    if (this.hasActiveExtras()) {
      for (const [key, value] of Object.entries(this.extraBody!)) {
        if (!PROTECTED_FIELDS.has(key)) payload[key] = value;
      }
    }

    return payload;
  }

  async chat(request: LLMRequest): Promise<LLMResponse> {
    const res = await this.send(request, false);

    if (!res.ok) {
      const errText = await res.text();
      UsageTracker.getInstance().recordResponseChunk(Buffer.byteLength(errText, 'utf8'));
      const errMsg = errorMessageOf(errText);
      const err: any = new Error(`Provider HTTP Error ${res.status}: ${errMsg}`);
      err.status = res.status;
      throw err;
    }

    const rawResText = await res.text();
    UsageTracker.getInstance().recordResponseChunk(Buffer.byteLength(rawResText, 'utf8'));
    let data: any = {};
    try {
      data = JSON.parse(rawResText);
    } catch {
      throw new Error(`Invalid JSON response from provider`);
    }

    if (data.error) {
      const msg = data.error.message || data.error.code || JSON.stringify(data.error);
      throw new Error(`Provider Error: ${msg}`);
    }

    const choice = data.choices?.[0]?.message || {};

    let usage;
    if (data.usage) {
      // LiteLLM puts the cost of the request into a header instead of the body.
      const headerCost = Number(res.headers?.get?.('x-litellm-response-cost'));
      const raw = Number.isFinite(headerCost) && res.headers.get('x-litellm-response-cost') !== null && data.usage.cost === undefined
        ? { ...data.usage, cost: headerCost }
        : data.usage;
      usage = openAiUsage(raw, this.priceContext());
      UsageTracker.getInstance().recordUsage(usage);
    }

    return {
      content: choice.content || null,
      toolCalls: choice.tool_calls ? choice.tool_calls.map((tc: any) => {
        const parsed = parseToolArguments(tc.function.arguments);
        return {
          id: tc.id || `call_${Math.random().toString(36).substring(2, 9)}`,
          name: tc.function.name,
          arguments: parsed.args,
          ...(parsed.error ? { argsError: parsed.error } : {})
        };
      }) : undefined,
      usage
    };
  }

  async *chatStream(request: LLMRequest): AsyncGenerator<any, LLMResponse, unknown> {
    const res = await this.send(request, true);

    if (!res.ok) {
      const errText = await res.text();
      UsageTracker.getInstance().recordResponseChunk(Buffer.byteLength(errText, 'utf8'));
      const errMsg = errorMessageOf(errText);
      const err: any = new Error(`Provider HTTP Error ${res.status}: ${errMsg}`);
      err.status = res.status;
      throw err;
    }

    const reader = res.body?.getReader();
    if (!reader) {
      // No streaming body: fall back to a regular request. The agent loop only reads
      // the final response from the 'done' chunk, so it has to be yielded.
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

    const decoder = new TextDecoder();
    let buffer = '';
    let accumulatedContent = '';
    let accumulatedReasoning = '';
    let inThinkTag = false;
    let thinkBuffer = '';
    const toolMap = new Map<number, { id: string; name: string; argsStr: string }>();
    let usageData: any = null;

    try {
      while (true) {
        if (request.signal?.aborted) break;
        const { done, value } = await reader.read();
        if (done || request.signal?.aborted) break;
        if (value) {
          UsageTracker.getInstance().recordResponseChunk(value.byteLength);
        }
        buffer += decoder.decode(value, { stream: true });
        
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          const trimmed = line.trim();
          if (!trimmed || trimmed.startsWith(':')) continue;
          if (trimmed.startsWith('data:')) {
            const dataPayload = trimmed.replace(/^data:\s*/, '');
            if (dataPayload === '[DONE]') continue;
            try {
              const json = JSON.parse(dataPayload);
              if (json.error) {
                const errMsg = json.error.message || json.error.code || JSON.stringify(json.error);
                throw new Error(`Provider Stream Error: ${errMsg}`);
              }
              if (json.usage) {
                usageData = json.usage;
              }
              const choice = json.choices?.[0];
              if (!choice) continue;

              const delta = choice.delta || {};

              // 1. Check reasoning_content (DeepSeek-R1 / OpenRouter / etc.)
              const reasoningText = delta.reasoning_content || delta.reasoning || '';
              if (reasoningText) {
                accumulatedReasoning += reasoningText;
                yield { type: 'reasoning_delta', delta: reasoningText };
              }

              // 2. Check content (and handle <think> tags if embedded in content)
              if (delta.content) {
                thinkBuffer += delta.content;

                while (thinkBuffer.length > 0) {
                  if (inThinkTag) {
                    const closeIdx = thinkBuffer.indexOf('</think>');
                    if (closeIdx !== -1) {
                      const reasoning = thinkBuffer.slice(0, closeIdx);
                      if (reasoning) {
                        accumulatedReasoning += reasoning;
                        yield { type: 'reasoning_delta', delta: reasoning };
                      }
                      inThinkTag = false;
                      thinkBuffer = thinkBuffer.slice(closeIdx + 8);
                    } else {
                      const partialIdx = thinkBuffer.lastIndexOf('<');
                      if (partialIdx !== -1 && thinkBuffer.length - partialIdx < 8) {
                        const reasoning = thinkBuffer.slice(0, partialIdx);
                        if (reasoning) {
                          accumulatedReasoning += reasoning;
                          yield { type: 'reasoning_delta', delta: reasoning };
                        }
                        thinkBuffer = thinkBuffer.slice(partialIdx);
                        break;
                      } else {
                        accumulatedReasoning += thinkBuffer;
                        yield { type: 'reasoning_delta', delta: thinkBuffer };
                        thinkBuffer = '';
                      }
                    }
                  } else {
                    const openIdx = thinkBuffer.indexOf('<think>');
                    if (openIdx !== -1) {
                      const content = thinkBuffer.slice(0, openIdx);
                      if (content) {
                        accumulatedContent += content;
                        yield { type: 'content_delta', delta: content };
                      }
                      inThinkTag = true;
                      thinkBuffer = thinkBuffer.slice(openIdx + 7);
                    } else {
                      const partialIdx = thinkBuffer.lastIndexOf('<');
                      if (partialIdx !== -1 && thinkBuffer.length - partialIdx < 8) {
                        const content = thinkBuffer.slice(0, partialIdx);
                        if (content) {
                          accumulatedContent += content;
                          yield { type: 'content_delta', delta: content };
                        }
                        thinkBuffer = thinkBuffer.slice(partialIdx);
                        break;
                      } else {
                        accumulatedContent += thinkBuffer;
                        yield { type: 'content_delta', delta: thinkBuffer };
                        thinkBuffer = '';
                      }
                    }
                  }
                }
              }

              // 3. Accumulate tool calls and yield live progress
              if (delta.tool_calls) {
                for (const tc of delta.tool_calls) {
                  const idx = tc.index ?? 0;
                  const existing = toolMap.get(idx) || { id: '', name: '', argsStr: '' };
                  if (tc.id) existing.id = tc.id;
                  if (tc.function?.name) existing.name += tc.function.name;
                  if (tc.function?.arguments) existing.argsStr += tc.function.arguments;
                  toolMap.set(idx, existing);

                  const targetHint = toolTargetHint(existing.argsStr);

                  yield {
                    type: 'tool_generating',
                    name: existing.name || 'tool',
                    bytes: existing.argsStr.length,
                    targetHint
                  };
                }
              }
            } catch (e: any) {
              if (e.message && e.message.startsWith('Provider')) {
                throw e;
              }
            }
          }
        }
      }
    } finally {
      reader.releaseLock();
    }
    
    if (thinkBuffer.length > 0) {
      if (inThinkTag) {
        accumulatedReasoning += thinkBuffer;
        yield { type: 'reasoning_delta', delta: thinkBuffer };
      } else {
        accumulatedContent += thinkBuffer;
        yield { type: 'content_delta', delta: thinkBuffer };
      }
    }

    const toolCalls = Array.from(toolMap.values()).map(tc => {
      const parsed = parseToolArguments(tc.argsStr);
      return {
        id: tc.id || `call_${Math.random().toString(36).substring(2, 9)}`,
        name: tc.name,
        arguments: parsed.args,
        ...(parsed.error ? { argsError: parsed.error } : {})
      };
    });

    if (!accumulatedContent && !accumulatedReasoning && toolCalls.length === 0) {
      throw new Error(`Provider returned empty response (0 tokens generated). Please check your model '${this.model}', balance, or API key.`);
    }

    const usage = openAiUsage(usageData, this.priceContext(), {
      prompt: Math.ceil(JSON.stringify(request.messages).length / 3.5),
      completion: Math.ceil((accumulatedContent.length + accumulatedReasoning.length) / 3.5),
    });
    UsageTracker.getInstance().recordUsage(usage);

    const finalResponse: LLMResponse = {
      content: accumulatedContent || null,
      toolCalls: toolCalls.length > 0 ? toolCalls : undefined,
      usage
    };

    yield { type: 'done', response: finalResponse };
    return finalResponse;
  }
}
