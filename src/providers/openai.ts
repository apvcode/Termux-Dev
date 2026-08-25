import { LLMProvider, LLMRequest, LLMResponse } from '../core/types.js';
import { calculateCost } from '../core/pricing.js';

export class OpenAIProvider implements LLMProvider {
  id = 'openai-compatible';
  
  constructor(
    private baseUrl: string,
    private apiKey: string,
    private model: string
  ) {}

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
          if (idx === lastImageMsgIdx) {
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

    return payload;
  }

  async chat(request: LLMRequest): Promise<LLMResponse> {
    const url = `${this.baseUrl}/chat/completions`;
    const payload = this.buildPayload(request, false);

    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
    };
    if (this.apiKey) {
      headers['Authorization'] = `Bearer ${this.apiKey}`;
    }

    const res = await fetch(url, {
      method: 'POST',
      headers,
      body: JSON.stringify(payload),
      signal: request.signal
    });

    if (!res.ok) {
      const errText = await res.text();
      let errMsg = errText;
      try {
        const parsed = JSON.parse(errText);
        if (parsed.error?.message) {
          errMsg = parsed.error.message;
        } else if (parsed.message) {
          errMsg = parsed.message;
        }
      } catch {}
      const err: any = new Error(`Provider HTTP Error ${res.status}: ${errMsg}`);
      err.status = res.status;
      throw err;
    }

    const data = await res.json();
    if (data.error) {
      const msg = data.error.message || data.error.code || JSON.stringify(data.error);
      throw new Error(`Provider Error: ${msg}`);
    }

    const choice = data.choices?.[0]?.message || {};

    let usage;
    if (data.usage) {
      const promptTokens = data.usage.prompt_tokens || 0;
      const completionTokens = data.usage.completion_tokens || 0;
      const totalTokens = data.usage.total_tokens || (promptTokens + completionTokens);
      const cost = typeof data.usage.total_cost === 'number'
        ? data.usage.total_cost
        : calculateCost(this.model, promptTokens, completionTokens);

      usage = {
        promptTokens,
        completionTokens,
        totalTokens,
        cost
      };
    }

    return {
      content: choice.content || null,
      toolCalls: choice.tool_calls ? choice.tool_calls.map((tc: any) => ({
        id: tc.id,
        name: tc.function.name,
        arguments: JSON.parse(tc.function.arguments)
      })) : undefined,
      usage
    };
  }

  async *chatStream(request: LLMRequest): AsyncGenerator<any, LLMResponse, unknown> {
    const url = `${this.baseUrl}/chat/completions`;
    const payload = this.buildPayload(request, true);

    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
    };
    if (this.apiKey) {
      headers['Authorization'] = `Bearer ${this.apiKey}`;
    }

    const res = await fetch(url, {
      method: 'POST',
      headers,
      body: JSON.stringify(payload),
      signal: request.signal
    });

    if (!res.ok) {
      const errText = await res.text();
      let errMsg = errText;
      try {
        const parsed = JSON.parse(errText);
        if (parsed.error?.message) {
          errMsg = parsed.error.message;
        } else if (parsed.message) {
          errMsg = parsed.message;
        }
      } catch {}
      const err: any = new Error(`Provider HTTP Error ${res.status}: ${errMsg}`);
      err.status = res.status;
      throw err;
    }

    const reader = res.body?.getReader();
    if (!reader) {
      return await this.chat(request);
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
    const toolMap = new Map<number, { id: string; name: string; argsStr: string }>();
    let usageData: any = null;

    try {
      while (true) {
        if (request.signal?.aborted) break;
        const { done, value } = await reader.read();
        if (done || request.signal?.aborted) break;
        buffer += decoder.decode(value, { stream: true });
        
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          const trimmed = line.trim();
          if (!trimmed || trimmed.startsWith(':')) continue;
          if (trimmed === 'data: [DONE]') continue;
          if (trimmed.startsWith('data: ')) {
            try {
              const json = JSON.parse(trimmed.slice(6));
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
                const text = delta.content;
                
                if (text.includes('<think>')) {
                  inThinkTag = true;
                  const parts = text.split('<think>');
                  if (parts[0]) {
                    accumulatedContent += parts[0];
                    yield { type: 'content_delta', delta: parts[0] };
                  }
                  if (parts[1]) {
                    accumulatedReasoning += parts[1];
                    yield { type: 'reasoning_delta', delta: parts[1] };
                  }
                } else if (text.includes('</think>')) {
                  inThinkTag = false;
                  const parts = text.split('</think>');
                  if (parts[0]) {
                    accumulatedReasoning += parts[0];
                    yield { type: 'reasoning_delta', delta: parts[0] };
                  }
                  if (parts[1]) {
                    accumulatedContent += parts[1];
                    yield { type: 'content_delta', delta: parts[1] };
                  }
                } else if (inThinkTag) {
                  accumulatedReasoning += text;
                  yield { type: 'reasoning_delta', delta: text };
                } else {
                  accumulatedContent += text;
                  yield { type: 'content_delta', delta: text };
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

                  yield {
                    type: 'tool_generating',
                    name: existing.name || 'tool',
                    bytes: existing.argsStr.length
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

    const toolCalls = Array.from(toolMap.values()).map(tc => {
      let parsedArgs = {};
      try {
        parsedArgs = JSON.parse(tc.argsStr);
      } catch {
        parsedArgs = {};
      }
      return {
        id: tc.id || `call_${Math.random().toString(36).substring(2, 9)}`,
        name: tc.name,
        arguments: parsedArgs
      };
    });

    if (!accumulatedContent && !accumulatedReasoning && toolCalls.length === 0) {
      throw new Error(`Provider returned empty response (0 tokens generated). Please check your model '${this.model}', balance, or API key.`);
    }

    let usage;
    const promptTokens = usageData?.prompt_tokens || Math.ceil(JSON.stringify(request.messages).length / 3.5);
    const completionTokens = usageData?.completion_tokens || Math.ceil((accumulatedContent.length + accumulatedReasoning.length) / 3.5);
    const totalTokens = usageData?.total_tokens || (promptTokens + completionTokens);
    const cost = typeof usageData?.total_cost === 'number'
      ? usageData.total_cost
      : calculateCost(this.model, promptTokens, completionTokens);

    usage = {
      promptTokens,
      completionTokens,
      totalTokens,
      cost
    };

    const finalResponse: LLMResponse = {
      content: accumulatedContent || null,
      toolCalls: toolCalls.length > 0 ? toolCalls : undefined,
      usage
    };

    yield { type: 'done', response: finalResponse };
    return finalResponse;
  }
}
