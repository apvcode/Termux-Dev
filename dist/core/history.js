export class History {
    messages = [];
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
    }
    getTotalTokens() {
        return this.estimateTokens(this.messages);
    }
    getConversationTokens() {
        const chatOnly = this.messages.filter(m => m.role !== 'system');
        return this.estimateTokens(chatOnly);
    }
    // Эвристика: кол-во токенов = длина всех строк / 3.5
    estimateTokens(messages) {
        let chars = 0;
        for (const m of messages) {
            chars += m.content?.length || 0;
            if (m.tool_calls) {
                for (const tc of m.tool_calls) {
                    chars += (tc.name.length + JSON.stringify(tc.arguments).length);
                }
            }
        }
        return Math.ceil(chars / 3.5);
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
        this.messages = blocks.filter(b => b.length > 0).flat();
        return true;
    }
}
