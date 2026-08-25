import { marked } from 'marked';
import { markedTerminal } from 'marked-terminal';
import pc from 'picocolors';
marked.use(markedTerminal({
    width: Math.min(process.stdout.columns || 80, 100),
    reflowText: false,
    tab: 2
}));
export function renderMarkdown(content) {
    if (!content)
        return '';
    try {
        return marked.parse(content).trim();
    }
    catch {
        return content;
    }
}
/**
 * Ultra-fast, lightweight streaming markdown renderer optimized for Termux & desktop CLI.
 * Avoids heavy synchronous AST re-parsing on every chunk, keeping token streaming buttery smooth.
 */
export class MarkdownStreamer {
    buffer = '';
    inCodeBlock = false;
    codeBlockLang = '';
    inTable = false;
    tableBuffer = [];
    push(chunk) {
        this.buffer += chunk;
        // 1. Process completed lines
        if (this.buffer.includes('\n')) {
            const lines = this.buffer.split('\n');
            this.buffer = lines.pop() || '';
            for (const line of lines) {
                this.processLine(line);
            }
        }
        // 2. Stream regular prose words immediately in real-time
        if (!this.inCodeBlock && !this.inTable && this.buffer.length > 0) {
            const trimmed = this.buffer.trimStart();
            const isSpecialLine = trimmed.startsWith('#') ||
                trimmed.startsWith('|') ||
                trimmed.startsWith('```') ||
                trimmed.startsWith('- [') ||
                trimmed.startsWith('* [') ||
                trimmed.startsWith('[') ||
                trimmed.startsWith('→') ||
                trimmed.startsWith('- ') ||
                trimmed.startsWith('* ') ||
                /^\d+\.\s/.test(trimmed) ||
                trimmed.startsWith('> ') ||
                trimmed === '---' ||
                trimmed === '***';
            if (!isSpecialLine) {
                const lastSpaceIdx = Math.max(this.buffer.lastIndexOf(' '), this.buffer.lastIndexOf('\t'));
                if (lastSpaceIdx > 0) {
                    const wordsToFlush = this.buffer.slice(0, lastSpaceIdx + 1);
                    this.buffer = this.buffer.slice(lastSpaceIdx + 1);
                    process.stdout.write(this.formatInline(wordsToFlush));
                }
            }
        }
    }
    processLine(line) {
        const trimmed = line.trim();
        // 1. Code block boundary
        if (trimmed.startsWith('```')) {
            if (this.inCodeBlock) {
                this.inCodeBlock = false;
                this.codeBlockLang = '';
                process.stdout.write(pc.dim('└' + '─'.repeat(Math.min(process.stdout.columns || 40, 50))) + '\n');
                return;
            }
            else {
                this.inCodeBlock = true;
                this.codeBlockLang = trimmed.slice(3).trim();
                const langTag = this.codeBlockLang ? ` [${this.codeBlockLang}] ` : ' ';
                process.stdout.write('\n' + pc.cyan('┌─' + langTag + '─'.repeat(Math.max(2, Math.min(process.stdout.columns || 40, 50) - langTag.length - 2))) + '\n');
                return;
            }
        }
        // 2. Inside code block: fast syntax-tinted output
        if (this.inCodeBlock) {
            process.stdout.write(pc.green('│ ') + pc.white(line) + '\n');
            return;
        }
        // 3. Tables
        if (trimmed.startsWith('|') && trimmed.endsWith('|')) {
            this.inTable = true;
            this.tableBuffer.push(line);
            return;
        }
        if (this.inTable) {
            this.flushTable();
            this.inTable = false;
        }
        // 4. OpenCode Checklists & Todos ([✓], [ ], [/], [>])
        const todoMatch = trimmed.match(/^(\s*(?:[-*]\s*)?)\[([ x✓X>/])\]\s*(.*)$/);
        if (todoMatch) {
            const mark = todoMatch[2].toLowerCase();
            const taskText = this.formatInline(todoMatch[3]);
            if (mark === 'x' || mark === '✓') {
                process.stdout.write('  ' + pc.green('[✓] ') + pc.dim(taskText) + '\n');
            }
            else if (mark === '/' || mark === '>') {
                process.stdout.write('  ' + pc.bold(pc.green('[ ] ' + taskText)) + '\n');
            }
            else {
                process.stdout.write('  ' + pc.dim('[ ] ') + pc.white(taskText) + '\n');
            }
            return;
        }
        // 5. OpenCode Action arrows (→ Read ..., → Edit ...)
        if (trimmed.startsWith('→ ')) {
            process.stdout.write(pc.bold(pc.cyan('→ ')) + pc.white(this.formatInline(trimmed.slice(2))) + '\n');
            return;
        }
        // 6. Regular line fast formatting (Headers, Bullets, Bold, Dims)
        if (trimmed.startsWith('# ')) {
            process.stdout.write('\n' + pc.bold(pc.cyan(trimmed.slice(2))) + '\n\n');
        }
        else if (trimmed.startsWith('## ')) {
            process.stdout.write('\n' + pc.bold(pc.white(trimmed.slice(3))) + '\n');
        }
        else if (trimmed.startsWith('### ')) {
            process.stdout.write('\n' + pc.bold(pc.yellow(trimmed.slice(4))) + '\n');
        }
        else if (trimmed.startsWith('- ') || trimmed.startsWith('* ')) {
            process.stdout.write('  ' + pc.cyan('• ') + this.formatInline(line.slice(2)) + '\n');
        }
        else if (/^\d+\.\s/.test(trimmed)) {
            const match = trimmed.match(/^(\d+\.)\s*(.*)$/);
            if (match) {
                process.stdout.write('  ' + pc.cyan(match[1]) + ' ' + this.formatInline(match[2]) + '\n');
            }
            else {
                process.stdout.write(this.formatInline(line) + '\n');
            }
        }
        else if (trimmed.startsWith('> ')) {
            process.stdout.write(pc.dim('  │ ' + trimmed.slice(2)) + '\n');
        }
        else if (trimmed === '---' || trimmed === '***' || trimmed === '___') {
            process.stdout.write(pc.dim('─'.repeat(Math.min(process.stdout.columns || 40, 60))) + '\n');
        }
        else {
            process.stdout.write(this.formatInline(line) + '\n');
        }
    }
    formatInline(text) {
        if (!text)
            return '';
        // Format `code`
        let formatted = text.replace(/`([^`]+)`/g, (_, c) => pc.yellow(c));
        // Format **bold**
        formatted = formatted.replace(/\*\*([^*]+)\*\*/g, (_, b) => pc.bold(b));
        // Format *italic*
        formatted = formatted.replace(/\*([^*]+)\*/g, (_, i) => pc.italic(i));
        return formatted;
    }
    flushTable() {
        if (this.tableBuffer.length > 0) {
            const tableMd = this.tableBuffer.join('\n');
            try {
                const rendered = renderMarkdown(tableMd);
                process.stdout.write(rendered + '\n');
            }
            catch {
                process.stdout.write(tableMd + '\n');
            }
            this.tableBuffer = [];
        }
    }
    reset() {
        this.buffer = '';
        this.inCodeBlock = false;
        this.codeBlockLang = '';
        this.inTable = false;
        this.tableBuffer = [];
    }
    finish() {
        if (this.buffer) {
            const trimmed = this.buffer.trim();
            if (trimmed.startsWith('|') && trimmed.endsWith('|')) {
                this.tableBuffer.push(this.buffer);
                this.buffer = '';
            }
        }
        this.flushTable();
        if (this.buffer) {
            if (this.inCodeBlock) {
                process.stdout.write(pc.green('│ ') + pc.white(this.buffer) + '\n');
                process.stdout.write(pc.dim('└' + '─'.repeat(Math.min(process.stdout.columns || 40, 50))) + '\n');
                this.inCodeBlock = false;
            }
            else {
                process.stdout.write(this.formatInline(this.buffer) + '\n');
            }
            this.buffer = '';
        }
    }
}
