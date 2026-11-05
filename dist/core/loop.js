import { CONTEXT_USAGE_RATIO, toolSchemaTokens } from './context-stats.js';
import { isNetworkOrRetryableError, getHumanReadableNetworkError, waitDelay } from './network.js';
function getActionDescription(toolName, args) {
    const t = toolName.toLowerCase();
    if (t === 'read_file' || t === 'view_file' || t === 'read') {
        return `→ Read ${args.path || args.filePath || args.targetFile || ''}`;
    }
    if (t === 'write_file' || t === 'create_file' || t === 'write_to_file' || t === 'write') {
        return `→ Write ${args.path || args.filePath || args.targetFile || ''}`;
    }
    if (t === 'edit_file' || t === 'replace_file_content' || t === 'patch') {
        return `→ Edit ${args.path || args.filePath || args.targetFile || ''}`;
    }
    if (t === 'delete_file' || t === 'remove_file' || t === 'rm') {
        return `→ Delete ${args.path || args.filePath || ''}`;
    }
    if (t === 'make_dir' || t === 'mkdir') {
        return `→ Mkdir ${args.path || args.dirPath || ''}`;
    }
    if (t === 'list_dir' || t === 'list_files' || t === 'find_files' || t === 'ls') {
        return `→ List ${args.path || args.dirPath || '.'}`;
    }
    if (t === 'search' || t === 'grep_search' || t === 'grep') {
        return `→ Search "${args.query || args.pattern || ''}" in ${args.dir || args.path || '.'}`;
    }
    if (t === 'ask_questions' || t === 'questions') {
        return `→ Clarify: Asking questions...`;
    }
    if (t === 'bash' || t === 'execute_command' || t === 'run_command' || t === 'exec') {
        return `→ Run: ${args.command || args.cmd || ''}`;
    }
    if (t === 'todo_list' || t === 'update_todos') {
        return `→ Update Plan & Tasks`;
    }
    if (t === 'plan_ready') {
        return `→ Finalize Plan`;
    }
    return `→ Executing ${toolName}...`;
}
const CONTEXT_LENGTH_ERROR = /context[_ ]length|maximum context|context window|too many tokens|prompt is too long|reduce the length|input is too long|exceeds? the (?:model|maximum|max)/i;
function isContextLengthError(err) {
    const status = err?.status;
    if (status !== undefined && status !== 400 && status !== 413 && status !== 422)
        return false;
    return CONTEXT_LENGTH_ERROR.test(String(err?.message || ''));
}
export class Agent {
    config;
    provider;
    tools;
    history;
    guard;
    constructor(config, provider, tools, history, guard) {
        this.config = config;
        this.provider = provider;
        this.tools = new Map(tools.map((t) => [t.name, t]));
        this.history = history;
        this.guard = guard;
    }
    async *run(signal, hooks) {
        let iterations = 0;
        // A config without maxIterations used to make the loop exit immediately and silently.
        const maxIterations = this.config.maxIterations > 0 ? this.config.maxIterations : 100;
        while (iterations < maxIterations) {
            if (signal?.aborted) {
                return;
            }
            // Messages the user typed while the previous step ran join the conversation here, between
            // the tool results and the next request, so the model sees them before it decides what to do.
            for (const message of (await hooks?.takePending?.()) ?? []) {
                this.history.addMessage(message);
                yield { type: 'user_message', content: message.content };
            }
            const toolDefinitions = Array.from(this.tools.values()).map((t) => t.definition);
            // Tool schemas are sent with every request and count against the context window.
            const schemaTokens = toolSchemaTokens(toolDefinitions);
            const promptBudget = Math.max(1000, Math.floor(this.config.maxContextTokens * CONTEXT_USAGE_RATIO) - schemaTokens);
            const wasCompacted = this.history.pruneToLimit(promptBudget);
            if (wasCompacted) {
                yield { type: 'system', message: 'Context compacted to fit model context window.' };
            }
            const request = {
                messages: this.history.getMessages(),
                tools: toolDefinitions,
                signal,
            };
            if (request.tools.length === 0) {
                delete request.tools;
            }
            let response;
            let attempt = 0;
            let contextRetries = 0;
            const maxRetries = 4;
            while (true) {
                if (signal?.aborted)
                    return;
                try {
                    if (this.provider.chatStream) {
                        let receivedAnyChunk = false;
                        for await (const chunk of this.provider.chatStream(request)) {
                            if (signal?.aborted) {
                                return;
                            }
                            receivedAnyChunk = true;
                            if (chunk.type === 'reasoning_delta') {
                                yield { type: 'reasoning_delta', delta: chunk.delta };
                            }
                            else if (chunk.type === 'content_delta') {
                                yield { type: 'text_delta', delta: chunk.delta };
                            }
                            else if (chunk.type === 'tool_generating') {
                                yield { type: 'tool_generating', name: chunk.name, bytes: chunk.bytes, targetHint: chunk.targetHint };
                            }
                            else if (chunk.type === 'done') {
                                response = chunk.response;
                            }
                        }
                        if (response) {
                            break;
                        }
                        if (!receivedAnyChunk) {
                            throw new Error('Provider stream ended unexpectedly without receiving any data.');
                        }
                        break;
                    }
                    else {
                        response = await this.provider.chat(request);
                        break;
                    }
                }
                catch (err) {
                    if (signal?.aborted || err.name === 'AbortError' || err.message?.includes('aborted')) {
                        return;
                    }
                    // The provider says the prompt is too long (our estimate was too optimistic):
                    // shrink the history harder and retry instead of failing the whole session.
                    if (contextRetries < 2 && isContextLengthError(err)) {
                        contextRetries++;
                        const target = Math.floor(this.history.getTotalTokens() * (contextRetries === 1 ? 0.6 : 0.35));
                        this.history.pruneToLimit(Math.max(500, target));
                        request.messages = this.history.getMessages();
                        yield { type: 'system', message: 'Provider reported the context is too long. Compacted history, retrying.' };
                        continue;
                    }
                    const isNetwork = isNetworkOrRetryableError(err);
                    if (isNetwork && attempt < maxRetries) {
                        attempt++;
                        const delayMs = Math.min(1500 * Math.pow(1.5, attempt - 1), 8000);
                        yield {
                            type: 'reconnecting',
                            attempt,
                            maxAttempts: maxRetries,
                            delayMs,
                            reason: getHumanReadableNetworkError(err)
                        };
                        await waitDelay(delayMs, signal);
                        if (signal?.aborted)
                            return;
                        yield { type: 'reconnected', attempt };
                        continue;
                    }
                    const errMsg = isNetwork
                        ? `Ошибка сети: Не удалось восстановить интернет-соединение после ${attempt} попыток (${err.message}).`
                        : `API Error: ${err.message}`;
                    yield { type: 'error', message: errMsg, isFatal: true };
                    return;
                }
            }
            if (signal?.aborted) {
                return;
            }
            for (const notice of this.provider.drainNotices?.() ?? []) {
                yield { type: 'system', message: notice };
            }
            if (!response) {
                response = { content: '' };
            }
            const assistantMsg = {
                role: 'assistant',
                content: response.content || '',
            };
            if (response.toolCalls && response.toolCalls.length > 0) {
                assistantMsg.tool_calls = response.toolCalls;
            }
            if (response.thinkingBlocks && response.thinkingBlocks.length > 0) {
                assistantMsg.thinking_blocks = response.thinkingBlocks;
            }
            this.history.addMessage(assistantMsg);
            if (response.usage) {
                yield { type: 'usage', usage: response.usage };
            }
            // If streaming wasn't used or to notify completed text
            if (assistantMsg.content && !this.provider.chatStream) {
                yield { type: 'text', content: assistantMsg.content };
            }
            if (!response.toolCalls || response.toolCalls.length === 0) {
                // The user wrote something while the model was finishing: answer it instead of stopping.
                if (hooks?.hasPending?.()) {
                    iterations++;
                    continue;
                }
                break;
            }
            const executedToolCallIds = new Set();
            try {
                for (const call of response.toolCalls) {
                    if (signal?.aborted)
                        return;
                    const actionDesc = getActionDescription(call.name, call.arguments);
                    yield { type: 'tool_start', id: call.id, name: call.name, argsRaw: JSON.stringify(call.arguments), actionDesc };
                    if (signal?.aborted)
                        return;
                    let result = '';
                    const tool = this.tools.get(call.name);
                    if (!tool) {
                        result = `Error: Tool '${call.name}' not found.`;
                    }
                    else if (call.argsError) {
                        // Never run a tool with arguments we could not parse ({} could mean something else).
                        result = `Error: ${call.argsError}`;
                    }
                    else {
                        try {
                            tool.validateArgs(call.arguments);
                            const allowed = !this.guard.check(tool.name, call.arguments)
                                || await this.guard.askUser(tool.name, call.arguments);
                            // Cancellation may arrive while waiting for confirmation.
                            if (signal?.aborted)
                                return;
                            result = allowed
                                ? await tool.execute(call.arguments, this.config, { signal })
                                : 'Action denied by user.';
                        }
                        catch (err) {
                            result = signal?.aborted
                                ? '[Operation cancelled by user]'
                                : `Error executing tool: ${err.message}`;
                        }
                    }
                    // Save the result before yielding: the UI can stop consuming at any event.
                    this.history.addMessage({
                        role: 'tool',
                        content: result,
                        tool_call_id: call.id,
                        name: call.name,
                    });
                    executedToolCallIds.add(call.id);
                    yield { type: 'tool_end', id: call.id, name: call.name, result };
                }
            }
            finally {
                // Keep every tool call paired with a response, even if the consumer exits early.
                for (const call of response.toolCalls) {
                    if (!executedToolCallIds.has(call.id)) {
                        this.history.addMessage({
                            role: 'tool',
                            content: '[Operation cancelled by user]',
                            tool_call_id: call.id,
                            name: call.name,
                        });
                    }
                }
            }
            iterations++;
        }
        if (iterations >= maxIterations) {
            yield { type: 'system', message: `Reached max iterations (${maxIterations}).` };
        }
    }
}
