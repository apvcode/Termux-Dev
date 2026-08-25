import { marked } from 'marked';
import { markedTerminal } from 'marked-terminal';
import pc from 'picocolors';

marked.use(markedTerminal({
  width: Math.min(process.stdout.columns || 80, 100),
  reflowText: false,
  tab: 2
}) as any);

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
  private buffer = '';
  private inCodeBlock = false;
  private codeBlockLang = '';
  private inTable = false;
  private tableBuffer: string[] = [];

  public push(chunk: string) {
    this.buffer += chunk;
    const lines = this.buffer.split('\n');
    this.buffer = lines.pop() || '';

    for (const line of lines) {
      this.processLine(line);
    }
  }

  private processLine(line: string) {
    const trimmed = line.trim();

    // 1. Code block boundary
    if (trimmed.startsWith('```')) {
      if (this.inCodeBlock) {
        this.inCodeBlock = false;
        this.codeBlockLang = '';
        process.stdout.write(pc.dim('└' + '─'.repeat(Math.min(process.stdout.columns || 40, 50))) + '\n');
        return;
      } else {
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

    // 4. Regular line fast formatting (Headers, Bullets, Bold, Dims)
    if (trimmed.startsWith('# ')) {
      process.stdout.write('\n' + pc.bold(pc.cyan(trimmed.slice(2))) + '\n\n');
    } else if (trimmed.startsWith('## ')) {
      process.stdout.write('\n' + pc.bold(pc.white(trimmed.slice(3))) + '\n');
    } else if (trimmed.startsWith('### ')) {
      process.stdout.write('\n' + pc.bold(pc.yellow(trimmed.slice(4))) + '\n');
    } else if (trimmed.startsWith('- ') || trimmed.startsWith('* ')) {
      process.stdout.write('  ' + pc.cyan('• ') + this.formatInline(line.slice(2)) + '\n');
    } else if (/^\d+\.\s/.test(trimmed)) {
      const match = trimmed.match(/^(\d+\.)\s*(.*)$/);
      if (match) {
        process.stdout.write('  ' + pc.cyan(match[1]) + ' ' + this.formatInline(match[2]) + '\n');
      } else {
        process.stdout.write(this.formatInline(line) + '\n');
      }
    } else if (trimmed.startsWith('> ')) {
      process.stdout.write(pc.dim('  │ ' + trimmed.slice(2)) + '\n');
    } else if (trimmed === '---' || trimmed === '***' || trimmed === '___') {
      process.stdout.write(pc.dim('─'.repeat(Math.min(process.stdout.columns || 40, 60))) + '\n');
    } else {
      process.stdout.write(this.formatInline(line) + '\n');
    }
  }

  private formatInline(text: string): string {
    if (!text) return '';
    // Format `code`
    let formatted = text.replace(/`([^`]+)`/g, (_, c) => pc.yellow(c));
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
        process.stdout.write(rendered + '\n');
      } catch {
        process.stdout.write(tableMd + '\n');
      }
      this.tableBuffer = [];
    }
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
        process.stdout.write(pc.green('│ ') + pc.white(this.buffer) + '\n');
        process.stdout.write(pc.dim('└' + '─'.repeat(Math.min(process.stdout.columns || 40, 50))) + '\n');
        this.inCodeBlock = false;
      } else {
        process.stdout.write(this.formatInline(this.buffer) + '\n');
      }
      this.buffer = '';
    }
  }
}
