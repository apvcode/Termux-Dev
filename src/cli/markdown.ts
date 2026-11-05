import { marked } from 'marked';
import { markedTerminal } from 'marked-terminal';
import pc from 'picocolors';
import { palette } from './ui.js';

marked.use(markedTerminal({
  width: Math.min(process.stdout.columns || 80, 100),
  reflowText: false,
  tab: 2
}) as any);

/** Width of horizontal rules / code frames: leaves room for the 2-column gutter. */
function ruleWidth(max: number): number {
  return Math.max(10, Math.min((process.stdout.columns || 40) - 4, max));
}

export function renderMarkdown(content: string): string {
  if (!content) return '';
  try {
    return (marked.parse(content) as string).trim();
  } catch {
    return content;
  }
}

/**
 * Ultra-fast, lightweight streaming markdown renderer optimized for Termux & desktop CLI.
 * Avoids heavy synchronous AST re-parsing on every chunk, keeping token streaming buttery smooth.
 */
export class MarkdownStreamer {
  private sink: (text: string) => void = (text) => {
    process.stdout.write(text);
  };
  private buffer = '';
  private inCodeBlock = false;
  private codeBlockLang = '';
  private inTable = false;
  private tableBuffer: string[] = [];

  /** Redirects the output (e.g. through a GutterWriter). Defaults to stdout. */
  public setSink(sink: (text: string) => void) {
    this.sink = sink;
  }

  public push(chunk: string) {
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
      const isSpecialLine =
        trimmed.startsWith('#') ||
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
          this.sink(this.formatInline(wordsToFlush));
        }
      }
    }
  }

  private processLine(line: string) {
    const trimmed = line.trim();

    // 1. Code block boundary
    if (trimmed.startsWith('```')) {
      if (this.inCodeBlock) {
        this.inCodeBlock = false;
        this.codeBlockLang = '';
        this.sink(palette.rule('└' + '─'.repeat(ruleWidth(50))) + '\n');
        return;
      } else {
        this.inCodeBlock = true;
        this.codeBlockLang = trimmed.slice(3).trim();
        const langTag = this.codeBlockLang ? ` [${this.codeBlockLang}] ` : ' ';
        this.sink('\n' + palette.accent('┌─' + langTag + '─'.repeat(Math.max(2, ruleWidth(50) - langTag.length - 2))) + '\n');
        return;
      }
    }

    // 2. Inside code block: fast syntax-tinted output
    if (this.inCodeBlock) {
      this.sink(palette.rule('│ ') + palette.text(line) + '\n');
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
        this.sink('  ' + palette.ok('[✓] ') + palette.faint(taskText) + '\n');
      } else if (mark === '/' || mark === '>') {
        this.sink('  ' + palette.bold(palette.ok('[ ] ' + taskText)) + '\n');
      } else {
        this.sink('  ' + palette.faint('[ ] ') + palette.text(taskText) + '\n');
      }
      return;
    }

    // 5. OpenCode Action arrows (→ Read ..., → Edit ...)
    if (trimmed.startsWith('→ ')) {
      this.sink(palette.bold(palette.accent('→ ')) + palette.text(this.formatInline(trimmed.slice(2))) + '\n');
      return;
    }

    // 6. Regular line fast formatting (Headers, Bullets, Bold, Dims)
    if (trimmed.startsWith('# ')) {
      this.sink('\n' + palette.bold(palette.accent(trimmed.slice(2))) + '\n\n');
    } else if (trimmed.startsWith('## ')) {
      this.sink('\n' + palette.bold(palette.text(trimmed.slice(3))) + '\n');
    } else if (trimmed.startsWith('### ')) {
      this.sink('\n' + palette.bold(palette.warn(trimmed.slice(4))) + '\n');
    } else if (trimmed.startsWith('- ') || trimmed.startsWith('* ')) {
      this.sink('  ' + palette.accent('• ') + this.formatInline(line.slice(2)) + '\n');
    } else if (/^\d+\.\s/.test(trimmed)) {
      const match = trimmed.match(/^(\d+\.)\s*(.*)$/);
      if (match) {
        this.sink('  ' + palette.accent(match[1]) + ' ' + this.formatInline(match[2]) + '\n');
      } else {
        this.sink(this.formatInline(line) + '\n');
      }
    } else if (trimmed.startsWith('> ')) {
      this.sink(palette.faint('  │ ' + trimmed.slice(2)) + '\n');
    } else if (trimmed === '---' || trimmed === '***' || trimmed === '___') {
      this.sink(palette.rule('─'.repeat(ruleWidth(60))) + '\n');
    } else {
      this.sink(this.formatInline(line) + '\n');
    }
  }

  private formatInline(text: string): string {
    if (!text) return '';
    // Format `code`
    let formatted = text.replace(/`([^`]+)`/g, (_, c) => palette.warn(c));
    // Format **bold**
    formatted = formatted.replace(/\*\*([^*]+)\*\*/g, (_, b) => pc.bold(b));
    // Format *italic*
    formatted = formatted.replace(/\*([^*]+)\*/g, (_, i) => pc.italic(i));
    return formatted;
  }

  private flushTable() {
    if (this.tableBuffer.length > 0) {
      const tableMd = this.tableBuffer.join('\n');
      try {
        const rendered = renderMarkdown(tableMd);
        this.sink(rendered + '\n');
      } catch {
        this.sink(tableMd + '\n');
      }
      this.tableBuffer = [];
    }
  }

  public reset() {
    this.buffer = '';
    this.inCodeBlock = false;
    this.codeBlockLang = '';
    this.inTable = false;
    this.tableBuffer = [];
  }

  public finish() {
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
        this.sink(palette.rule('│ ') + palette.text(this.buffer) + '\n');
        this.sink(palette.rule('└' + '─'.repeat(ruleWidth(50))) + '\n');
        this.inCodeBlock = false;
      } else {
        this.sink(this.formatInline(this.buffer) + '\n');
      }
      this.buffer = '';
    }
  }
}
