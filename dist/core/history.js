const IMAGE_TOKEN_ESTIMATE = 1500;
// Last-resort size for tool output that is still too large once everything
// that can be dropped has been dropped.
const EMERGENCY_TOOL_CHARS = 4000;
function shortenMiddle(text, maxChars) {
    if (text.length <= maxChars)
        return text;
    const head = Math.floor(maxChars * 0.4);
    const tail = maxChars - head;
    return `${text.slice(0, head)}\n[... ${text.length - maxChars} characters removed to fit the context window ...]\n${text.slice(text.length - tail)}`;
}
export class History {
    messages = [];
    /** How often old messages had to be shortened or dropped, and how many were dropped (for /usage). */
    compactions = 0;
    droppedMessages = 0;
    constructor() { }
    addMessage(msg) {
        this.messages.push(msg);
    }
    updateSystemPrompt(content) {
        const idx = this.messages.findIndex(m => m.role === 'system');
        if (idx >= 0) {
            this.messages[idx].content = content;
        }
        else {
            this.messages.unshift({ role: 'system', content });
        }
    }
    getMessages() {
        return [...this.messages];
    }
    popLastTurn() {
        // Pop assistant and tool messages from the end of history
        while (this.messages.length > 1 && this.messages[this.messages.length - 1].role !== 'user') {
            this.messages.pop();
        }
        // Pop the triggering user message
        if (this.messages.length > 1 && this.messages[this.messages.length - 1].role === 'user') {
            this.messages.pop();
        }
    }
    clear() {
        this.messages = [];
        this.compactions = 0;
        this.droppedMessages = 0;
    }
    getTotalTokens() {
        return this.estimateTokens(this.messages);
    }
    getConversationTokens() {
        const chatOnly = this.messages.filter(m => m.role !== 'system');
        return this.estimateTokens(chatOnly);
    }
    estimateTokens(messages) {
        let tokens = 0;
        const countStringTokens = (str) => {
            if (!str)
                return 0;
            let t = 0;
            for (let i = 0; i < str.length; i++) {
                const code = str.charCodeAt(i);
                if (code >= 0x0400 && code <= 0x04FF) {
                    t += 0.8;
                }
                else if (code > 0x07FF) {
                    t += 1.2;
                }
                else {
                    // Source code and JSON average ~3 characters per token; 0.25 (4 chars/token)
                    // under-counts them and lets requests overflow the context window.
                    t += 0.33;
                }
            }
            return t;
        };
        for (const m of messages) {
            tokens += countStringTokens(m.content || '');
            if (m.images && m.images.length > 0) {
                tokens += m.images.length * IMAGE_TOKEN_ESTIMATE;
            }
            for (const t of m.thinking_blocks ?? []) {
                tokens += countStringTokens(t.type === 'thinking' ? t.thinking : t.data);
            }
            if (m.tool_calls) {
                for (const tc of m.tool_calls) {
                    tokens += countStringTokens(tc.name);
                    tokens += countStringTokens(JSON.stringify(tc.arguments));
                }
            }
        }
        return Math.ceil(tokens);
    }
    groupIntoBlocks(messages) {
        const blocks = [];
        let i = 0;
        while (i < messages.length) {
            const msg = messages[i];
            if (msg.role === 'assistant' && msg.tool_calls && msg.tool_calls.length > 0) {
                // Начинаем Блок типа B
                const block = [msg];
                i++;
                // Собираем все следующие tool-сообщения
                while (i < messages.length && messages[i].role === 'tool') {
                    block.push(messages[i]);
                    i++;
                }
                blocks.push(block);
            }
            else {
                // Блок типа A
                blocks.push([msg]);
                i++;
            }
        }
        return blocks;
    }
    pruneToLimit(maxTokens) {
        if (this.estimateTokens(this.messages) <= maxTokens) {
            return false;
        }
        let compacted = false;
        const blocks = this.groupIntoBlocks(this.messages);
        // Определяем pinned блоки
        const pinnedIndices = new Set();
        let foundUser = false;
        for (let i = 0; i < blocks.length; i++) {
            const firstMsg = blocks[i][0];
            if (firstMsg.role === 'system') {
                pinnedIndices.add(i);
            }
            else if (firstMsg.role === 'user' && !foundUser) {
                pinnedIndices.add(i);
                foundUser = true;
            }
        }
        const numProtectLast = 2;
        for (let i = Math.max(0, blocks.length - numProtectLast); i < blocks.length; i++) {
            pinnedIndices.add(i);
        }
        // Фаза 1: Сжатие tool-сообщений
        const TOOL_TRUNCATE_THRESHOLD = 1500;
        for (let i = 0; i < blocks.length; i++) {
            if (!pinnedIndices.has(i)) {
                const block = blocks[i];
                for (let j = 0; j < block.length; j++) {
                    const msg = block[j];
                    if (msg.role === 'tool' && msg.content && msg.content.length > TOOL_TRUNCATE_THRESHOLD) {
                        msg.content = `[Content truncated to save context window. Original length: ${msg.content.length} chars...]`;
                    }
                }
            }
        }
        if (this.estimateTokens(blocks.flat()) <= maxTokens) {
            this.messages = blocks.flat();
            this.compactions++;
            return true;
        }
        // Фаза 2: Удаление незащищенных блоков целиком (от старых к новым)
        let currentTokens = this.estimateTokens(blocks.flat());
        for (let i = 0; i < blocks.length; i++) {
            if (currentTokens <= maxTokens) {
                break;
            }
            if (!pinnedIndices.has(i)) {
                const blockTokens = this.estimateTokens(blocks[i]);
                currentTokens -= blockTokens;
                blocks[i] = []; // помечаем как удаленный
            }
        }
        // Фаза 3: остались только защищённые блоки, но они всё ещё не помещаются
        // (например, огромный результат инструмента в последнем ходе). Сокращаем
        // содержимое tool-сообщений, начиная со старых.
        if (currentTokens > maxTokens) {
            for (const block of blocks) {
                if (currentTokens <= maxTokens)
                    break;
                for (const msg of block) {
                    if (currentTokens <= maxTokens)
                        break;
                    if (msg.role === 'tool' && msg.content && msg.content.length > EMERGENCY_TOOL_CHARS) {
                        const before = this.estimateTokens([msg]);
                        msg.content = shortenMiddle(msg.content, EMERGENCY_TOOL_CHARS);
                        currentTokens -= before - this.estimateTokens([msg]);
                    }
                }
            }
        }
        const before = this.messages.length;
        this.messages = blocks.filter(b => b.length > 0).flat();
        this.compactions++;
        this.droppedMessages += before - this.messages.length;
        return true;
    }
}
