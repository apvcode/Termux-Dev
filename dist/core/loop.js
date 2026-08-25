function getActionDescription(toolName, args) {
    const t = toolName.toLowerCase();
    if (t === 'read_file' || t === 'view_file' || t === 'read') {
        return `📖 Reading: ${args.path || args.filePath || args.targetFile || ''}`;
    }
    if (t === 'write_file' || t === 'create_file' || t === 'write_to_file' || t === 'write') {
        return `📝 Writing: ${args.path || args.filePath || args.targetFile || ''}`;
    }
    if (t === 'edit_file' || t === 'replace_file_content' || t === 'patch') {
        return `✏️ Editing: ${args.path || args.filePath || args.targetFile || ''}`;
    }
    if (t === 'delete_file' || t === 'remove_file' || t === 'rm') {
        return `🗑️ Deleting: ${args.path || args.filePath || ''}`;
    }
    if (t === 'make_dir' || t === 'mkdir') {
        return `📁 Creating directory: ${args.path || args.dirPath || ''}`;
    }
    if (t === 'list_dir' || t === 'list_files' || t === 'find_files' || t === 'ls') {
        return `📂 Listing: ${args.path || args.dirPath || '.'}`;
    }
    if (t === 'search' || t === 'grep_search' || t === 'grep') {
        return `🔍 Searching: "${args.query || args.pattern || ''}" in ${args.dir || args.path || '.'}`;
    }
    if (t === 'ask_questions' || t === 'questions') {
        return `📋 Asking clarifying questions...`;
    }
    if (t === 'bash' || t === 'execute_command' || t === 'run_command' || t === 'exec') {
        return `⚡ Running command: ${args.command || args.cmd || ''}`;
    }
    return `🔧 Executing ${toolName}...`;
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
    async *run(signal) {
        let iterations = 0;
        while (iterations < this.config.maxIterations) {
            if (signal?.aborted) {
                return;
            }
            const wasCompacted = this.history.pruneToLimit(this.config.maxContextTokens);
            if (wasCompacted) {
                yield { type: 'system', message: 'Context compacted to fit model context window.' };
            }
            const request = {
                messages: this.history.getMessages(),
                tools: Array.from(this.tools.values()).map((t) => t.definition),
                signal,
            };
            if (request.tools.length === 0) {
                delete request.tools;
            }
            let response;
            try {
                if (this.provider.chatStream) {
                    for await (const chunk of this.provider.chatStream(request)) {
                        if (signal?.aborted) {
                            return;
                        }
                        if (chunk.type === 'reasoning_delta') {
                            yield { type: 'reasoning_delta', delta: chunk.delta };
                        }
                        else if (chunk.type === 'content_delta') {
                            yield { type: 'text_delta', delta: chunk.delta };
                        }
                        else if (chunk.type === 'tool_generating') {
                            yield { type: 'tool_generating', name: chunk.name, bytes: chunk.bytes };
                        }
                        else if (chunk.type === 'done') {
                            response = chunk.response;
                        }
                    }
                }
                else {
                    response = await this.provider.chat(request);
                }
            }
            catch (err) {
                if (signal?.aborted || err.name === 'AbortError' || err.message?.includes('aborted')) {
                    return;
                }
                yield { type: 'error', message: `API Error: ${err.message}`, isFatal: true };
                return;
            }
            if (signal?.aborted) {
                return;
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
            this.history.addMessage(assistantMsg);
            if (response.usage) {
                yield { type: 'usage', usage: response.usage };
            }
            // If streaming wasn't used or to notify completed text
            if (assistantMsg.content && !this.provider.chatStream) {
                yield { type: 'text', content: assistantMsg.content };
            }
            if (!response.toolCalls || response.toolCalls.length === 0) {
                break;
            }
            for (const call of response.toolCalls) {
                if (signal?.aborted) {
                    return;
                }
                const actionDesc = getActionDescription(call.name, call.arguments);
                yield { type: 'tool_start', id: call.id, name: call.name, argsRaw: JSON.stringify(call.arguments), actionDesc };
                let result = '';
                const tool = this.tools.get(call.name);
                if (!tool) {
                    result = `Error: Tool '${call.name}' not found.`;
                }
                else {
                    try {
                        tool.validateArgs(call.arguments);
                        if (this.guard.check(tool.name, call.arguments)) {
                            const allowed = await this.guard.askUser(tool.name, call.arguments);
                            if (allowed) {
                                result = await tool.execute(call.arguments, this.config);
                            }
                            else {
                                result = 'Action denied by user.';
                            }
                        }
                        else {
                            result = await tool.execute(call.arguments, this.config);
                        }
                    }
                    catch (err) {
                        result = `Error executing tool: ${err.message}`;
                    }
                }
                yield { type: 'tool_end', id: call.id, name: call.name, result };
                this.history.addMessage({
                    role: 'tool',
                    content: result,
                    tool_call_id: call.id,
                    name: call.name,
                });
            }
            iterations++;
        }
        if (iterations >= this.config.maxIterations) {
            yield { type: 'system', message: `Reached max iterations (${this.config.maxIterations}).` };
        }
    }
}
