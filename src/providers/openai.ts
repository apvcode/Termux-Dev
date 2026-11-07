import { LLMProvider, LLMRequest, LLMResponse, TokenUsage } from '../core/types.js';
import { UsageTracker } from '../core/usage.js';
import { ToolGuard } from './toolguard.js';
import { DEFAULT_REQUEST_TIMEOUT_MS, NOT_ABOUT_REASONING, RequestWatchdog, PARAMETER_REJECTED, ProviderOptions, errorMessageOf, imageNotSentText, imagesToSend, openAiUsage, parseToolArguments, toolTargetHint } from './shared.js';

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

  private toolGuard = new ToolGuard();

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
    const send = (body: Record<string, any>) => {
      const bodyStr = JSON.stringify(body);
      const headers: Record<string, string> = { 'Content-Type': 'application/json' };
      if (this.apiKey) headers['Authorization'] = `Bearer ${this.apiKey}`;
      UsageTracker.getInstance().recordRequest(Buffer.byteLength(bodyStr, 'utf8'));
      return fetch(url, { method: 'POST', headers, body: bodyStr, signal: request.signal });
    };
    const post = () => send(this.buildPayload(request, stream));

    let res = await post();
    if (res.ok || (res.status !== 400 && res.status !== 422)) return res;

    // Refused, and it works without tools: a tool definition is what the gateway does not like.
    if (request.tools && request.tools.length > 0 && !NOT_ABOUT_REASONING.test(errorMessageOf(await res.clone().text().catch(() => '')))) {
      const probe = async (tools: any[]) => {
        const body = this.buildPayload({ ...request, tools: tools.length > 0 ? tools : undefined }, false, true);
        const r = await send(body);
        await r.text().catch(() => '');
        return r.ok;
      };
      const repair = await this.toolGuard.repair(this.toolGuard.apply(request.tools), probe);
      if (repair.notice) this.notices.push(repair.notice);
      if (repair.changed) {
        res = await post();
        if (res.ok || (res.status !== 400 && res.status !== 422)) return res;
      }
    }
    if (!this.hasActiveExtras()) return res;

    const text = await res.clone().text().catch(() => '');
    // A gateway may word the refusal in its own way ("the model rejected this conversation or its
    // parameters"): the reasoning setting is the one thing devx added, so it is tried without it once.
    const known = PARAMETER_REJECTED.test(text);
    if (!known && NOT_ABOUT_REASONING.test(errorMessageOf(text))) return res;

    this.extrasRejected = true;
    let reason = text;
    try {
      const parsed = JSON.parse(text);
      reason = parsed.error?.message || parsed.message || text;
    } catch {}
    const retry = await post();
    if (!known && !retry.ok) {
      // It was not the reasoning setting: it goes on being sent, and the real answer is shown.
      this.extrasRejected = false;
      return retry;
    }
    this.notices.push(
      `The endpoint rejected the reasoning setting (${String(reason).replace(/\s+/g, ' ').slice(0, 110)}). ` +
      `Continuing without it. /effort auto stops sending it for good.`
    );
    return retry;
  }

  private buildPayload(request: LLMRequest, stream: boolean, probe = false) {
    const send = imagesToSend(request.messages);

    const messages: any[] = [];
    // Pictures that tool results carry: this format only takes text in a tool message, so they
    // follow the run of tool messages as one message of the user.
    let toolPictures: Array<{ path: string; dataUrl: string }> = [];
    const flushToolPictures = () => {
      if (toolPictures.length === 0) return;
      messages.push({
        role: 'user',
        content: [
          { type: 'text', text: `Pictures returned by the tool call(s) above: ${toolPictures.map((img) => img.path).join(', ')}` },
          ...toolPictures.map((img) => ({ type: 'image_url', image_url: { url: img.dataUrl } })),
        ],
      });
      toolPictures = [];
    };

    request.messages.forEach((m, idx) => {
      if (m.role !== 'tool') flushToolPictures();
      let content: any = m.content || "";
      if (m.images && m.images.length > 0) {
        if (this.options.images === false) {
          const note = imageNotSentText(m.images.map(img => img.path));
          content = m.content ? `${m.content}\n${note}` : note;
          if (!this.imageNoticeShown) {
            this.imageNoticeShown = true;
            this.notices.push(`${this.model} cannot read images, so the image was not sent. Switch to a model with vision (/model).`);
          }
        } else if (send.has(idx)) {
          if (m.role === 'tool') {
            toolPictures.push(...m.images);
          } else {
            content = [
              { type: 'text', text: m.content || "" },
              ...m.images.map(img => ({
                type: 'image_url',
                image_url: { url: img.dataUrl }
              }))
            ];
          }
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
      messages.push(msg);
    });
    flushToolPictures();

    const payload: any = {
      model: this.model,
      stream,
      messages,
    };

    // Tools the gateway refused are left out; a probe is a request that costs next to nothing.
    const tools = this.toolGuard.apply(request.tools);
    if (tools && tools.length > 0) {
      payload.tools = tools.map(t => ({
        type: 'function',
        function: t
      }));
    }
    if (probe) payload.max_tokens = 1;

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

  lostRequestUsage(promptTokens: number, completionTokens: number): TokenUsage {
    const usage = openAiUsage({}, this.priceContext(), { prompt: promptTokens, completion: completionTokens });
    UsageTracker.getInstance().recordUsage(usage);
    return usage;
  }

  private get timeoutMs(): number {
    return this.options.timeoutMs ?? DEFAULT_REQUEST_TIMEOUT_MS;
  }

  async chat(request: LLMRequest): Promise<LLMResponse> {
    const watch = new RequestWatchdog(this.timeoutMs, request.signal);
    try {
      return await this.chatOnce({ ...request, signal: watch.signal });
    } catch (err) {
      throw watch.timedOut ? watch.error() : err;
    } finally {
      watch.stop();
    }
  }

  async *chatStream(request: LLMRequest): AsyncGenerator<any, LLMResponse, unknown> {
    const watch = new RequestWatchdog(this.timeoutMs, request.signal);
    try {
      const response = yield* this.streamOnce({ ...request, signal: watch.signal }, watch);
      // The time ran out and the stream was cut: what came so far is not an answer.
      if (watch.timedOut) throw watch.error();
      return response;
    } catch (err) {
      throw watch.timedOut ? watch.error() : err;
    } finally {
      watch.stop();
    }
  }

  private async chatOnce(request: LLMRequest): Promise<LLMResponse> {
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

  private async *streamOnce(request: LLMRequest, watch: RequestWatchdog): AsyncGenerator<any, LLMResponse, unknown> {
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
        watch.touch();
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
    // Cut off because it went silent: nothing here is an answer, and it must not be counted as one.
    if (watch.timedOut) throw watch.error();

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
