import { calculateCost } from '../core/pricing.js';
import { UsageTracker } from '../core/usage.js';
export class OpenAIProvider {
    baseUrl;
    apiKey;
    model;
    id = 'openai-compatible';
    constructor(baseUrl, apiKey, model) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }
    buildPayload(request, stream) {
        const totalMsgs = request.messages.length;
        let lastImageMsgIdx = -1;
        for (let i = totalMsgs - 1; i >= 0; i--) {
            if (request.messages[i].images && request.messages[i].images.length > 0) {
                lastImageMsgIdx = i;
                break;
            }
        }
        const payload = {
            model: this.model,
            stream,
            messages: request.messages.map((m, idx) => {
                let content = m.content || "";
                if (m.images && m.images.length > 0) {
                    if (idx === lastImageMsgIdx) {
                        content = [
                            { type: 'text', text: m.content || "" },
                            ...m.images.map(img => ({
                                type: 'image_url',
                                image_url: { url: img.dataUrl }
                            }))
                        ];
                    }
                    else {
                        const imgRef = m.images.map(img => `[Attached Image: ${img.path}]`).join(' ');
                        content = m.content ? `${m.content}\n${imgRef}` : imgRef;
                    }
                }
                const msg = { role: m.role, content };
                if (m.name)
                    msg.name = m.name;
                if (m.tool_calls)
                    msg.tool_calls = m.tool_calls.map(tc => ({
                        id: tc.id,
                        type: 'function',
                        function: { name: tc.name, arguments: JSON.stringify(tc.arguments) }
                    }));
                if (m.tool_call_id)
                    msg.tool_call_id = m.tool_call_id;
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
    async chat(request) {
        const url = `${this.baseUrl}/chat/completions`;
        const payload = this.buildPayload(request, false);
        const bodyStr = JSON.stringify(payload);
        const headers = {
            'Content-Type': 'application/json',
        };
        if (this.apiKey) {
            headers['Authorization'] = `Bearer ${this.apiKey}`;
        }
        UsageTracker.getInstance().recordRequest(Buffer.byteLength(bodyStr, 'utf8'));
        const res = await fetch(url, {
            method: 'POST',
            headers,
            body: bodyStr,
            signal: request.signal
        });
        if (!res.ok) {
            const errText = await res.text();
            UsageTracker.getInstance().recordResponseChunk(Buffer.byteLength(errText, 'utf8'));
            let errMsg = errText;
            try {
                const parsed = JSON.parse(errText);
                if (parsed.error?.message) {
                    errMsg = parsed.error.message;
                }
                else if (parsed.message) {
                    errMsg = parsed.message;
                }
            }
            catch { }
            const err = new Error(`Provider HTTP Error ${res.status}: ${errMsg}`);
            err.status = res.status;
            throw err;
        }
        const rawResText = await res.text();
        UsageTracker.getInstance().recordResponseChunk(Buffer.byteLength(rawResText, 'utf8'));
        let data = {};
        try {
            data = JSON.parse(rawResText);
        }
        catch {
            throw new Error(`Invalid JSON response from provider`);
        }
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
            UsageTracker.getInstance().recordTokens(promptTokens, completionTokens, cost);
        }
        return {
            content: choice.content || null,
            toolCalls: choice.tool_calls ? choice.tool_calls.map((tc) => {
                let parsedArgs = {};
                try {
                    parsedArgs = JSON.parse(tc.function.arguments);
                }
                catch {
                    parsedArgs = {};
                }
                return {
                    id: tc.id || `call_${Math.random().toString(36).substring(2, 9)}`,
                    name: tc.function.name,
                    arguments: parsedArgs
                };
            }) : undefined,
            usage
        };
    }
    async *chatStream(request) {
        const url = `${this.baseUrl}/chat/completions`;
        const payload = this.buildPayload(request, true);
        const bodyStr = JSON.stringify(payload);
        const headers = {
            'Content-Type': 'application/json',
        };
        if (this.apiKey) {
            headers['Authorization'] = `Bearer ${this.apiKey}`;
        }
        UsageTracker.getInstance().recordRequest(Buffer.byteLength(bodyStr, 'utf8'));
        const res = await fetch(url, {
            method: 'POST',
            headers,
            body: bodyStr,
            signal: request.signal
        });
        if (!res.ok) {
            const errText = await res.text();
            UsageTracker.getInstance().recordResponseChunk(Buffer.byteLength(errText, 'utf8'));
            let errMsg = errText;
            try {
                const parsed = JSON.parse(errText);
                if (parsed.error?.message) {
                    errMsg = parsed.error.message;
                }
                else if (parsed.message) {
                    errMsg = parsed.message;
                }
            }
            catch { }
            const err = new Error(`Provider HTTP Error ${res.status}: ${errMsg}`);
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
                    reader.cancel().catch(() => { });
                }
                catch { }
            });
        }
        const decoder = new TextDecoder();
        let buffer = '';
        let accumulatedContent = '';
        let accumulatedReasoning = '';
        let inThinkTag = false;
        let thinkBuffer = '';
        const toolMap = new Map();
        let usageData = null;
        try {
            while (true) {
                if (request.signal?.aborted)
                    break;
                const { done, value } = await reader.read();
                if (done || request.signal?.aborted)
                    break;
                if (value) {
                    UsageTracker.getInstance().recordResponseChunk(value.byteLength);
                }
                buffer += decoder.decode(value, { stream: true });
                const lines = buffer.split('\n');
                buffer = lines.pop() || '';
                for (const line of lines) {
                    const trimmed = line.trim();
                    if (!trimmed || trimmed.startsWith(':'))
                        continue;
                    if (trimmed === 'data: [DONE]')
                        continue;
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
                            if (!choice)
                                continue;
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
                                        }
                                        else {
                                            const partialIdx = thinkBuffer.lastIndexOf('<');
                                            if (partialIdx !== -1 && thinkBuffer.length - partialIdx < 8) {
                                                const reasoning = thinkBuffer.slice(0, partialIdx);
                                                if (reasoning) {
                                                    accumulatedReasoning += reasoning;
                                                    yield { type: 'reasoning_delta', delta: reasoning };
                                                }
                                                thinkBuffer = thinkBuffer.slice(partialIdx);
                                                break;
                                            }
                                            else {
                                                accumulatedReasoning += thinkBuffer;
                                                yield { type: 'reasoning_delta', delta: thinkBuffer };
                                                thinkBuffer = '';
                                            }
                                        }
                                    }
                                    else {
                                        const openIdx = thinkBuffer.indexOf('<think>');
                                        if (openIdx !== -1) {
                                            const content = thinkBuffer.slice(0, openIdx);
                                            if (content) {
                                                accumulatedContent += content;
                                                yield { type: 'content_delta', delta: content };
                                            }
                                            inThinkTag = true;
                                            thinkBuffer = thinkBuffer.slice(openIdx + 7);
                                        }
                                        else {
                                            const partialIdx = thinkBuffer.lastIndexOf('<');
                                            if (partialIdx !== -1 && thinkBuffer.length - partialIdx < 8) {
                                                const content = thinkBuffer.slice(0, partialIdx);
                                                if (content) {
                                                    accumulatedContent += content;
                                                    yield { type: 'content_delta', delta: content };
                                                }
                                                thinkBuffer = thinkBuffer.slice(partialIdx);
                                                break;
                                            }
                                            else {
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
                                    if (tc.id)
                                        existing.id = tc.id;
                                    if (tc.function?.name)
                                        existing.name += tc.function.name;
                                    if (tc.function?.arguments)
                                        existing.argsStr += tc.function.arguments;
                                    toolMap.set(idx, existing);
                                    // Extract live target hint (e.g. path or command being generated)
                                    let targetHint = undefined;
                                    const args = existing.argsStr;
                                    if (args) {
                                        const pathMatch = args.match(/"(?:path|filePath|targetFile)"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"/);
                                        if (pathMatch && pathMatch[1]) {
                                            targetHint = pathMatch[1];
                                        }
                                        else {
                                            const cmdMatch = args.match(/"(?:command|cmd)"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"/);
                                            if (cmdMatch && cmdMatch[1]) {
                                                targetHint = cmdMatch[1].length > 30 ? cmdMatch[1].slice(0, 27) + '...' : cmdMatch[1];
                                            }
                                            else {
                                                const qMatch = args.match(/"(?:query|pattern)"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"/);
                                                if (qMatch && qMatch[1]) {
                                                    targetHint = `"${qMatch[1]}"`;
                                                }
                                            }
                                        }
                                    }
                                    yield {
                                        type: 'tool_generating',
                                        name: existing.name || 'tool',
                                        bytes: existing.argsStr.length,
                                        targetHint
                                    };
                                }
                            }
                        }
                        catch (e) {
                            if (e.message && e.message.startsWith('Provider')) {
                                throw e;
                            }
                        }
                    }
                }
            }
        }
        finally {
            reader.releaseLock();
        }
        if (thinkBuffer.length > 0) {
            if (inThinkTag) {
                accumulatedReasoning += thinkBuffer;
                yield { type: 'reasoning_delta', delta: thinkBuffer };
            }
            else {
                accumulatedContent += thinkBuffer;
                yield { type: 'content_delta', delta: thinkBuffer };
            }
        }
        const toolCalls = Array.from(toolMap.values()).map(tc => {
            let parsedArgs = {};
            try {
                parsedArgs = JSON.parse(tc.argsStr);
            }
            catch {
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
        UsageTracker.getInstance().recordTokens(promptTokens, completionTokens, cost);
        const finalResponse = {
            content: accumulatedContent || null,
            toolCalls: toolCalls.length > 0 ? toolCalls : undefined,
            usage
        };
        yield { type: 'done', response: finalResponse };
        return finalResponse;
    }
}
