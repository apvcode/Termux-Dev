import { marked } from 'marked';
import { markedTerminal } from 'marked-terminal';
import pc from 'picocolors';
import { palette } from './ui.js';

marked.use(markedTerminal({
  width: Math.min(process.stdout.columns || 80, 100),
  reflowText: false,
  showSectionPrefix: false,
  tab: 2
}) as any);

/**
 * Inline markdown (`code`, **bold**, *italic*) for text that arrives in pieces. The state of an open
 * marker is kept between pieces, so a span that runs over several words (`**seven words in bold**`)
 * is styled as a whole and the marks themselves never reach the screen. A mark that cannot be a
 * mark (`2 * 3`, a lone `*`) stays as it is. Every piece is closed at its end and opened again at
 * the start of the next one, so a line that is wrapped or cut in between stays well-formed.
 */
export class InlineStyler {
  private bold = false;
  private italic = false;
  private code = false;
  /** The character before the next one: whether a mark opens or closes depends on it. */
  private prev = ' ';
  private readonly codeOn: string;
  private readonly codeOff: string;

  constructor() {
    const [on, off] = palette.warn('\u0001').split('\u0001');
    this.codeOn = on ?? '';
    this.codeOff = off ?? '';
  }

  /** A new line: nothing stays open across a line break. */
  public endLine(): void {
    this.bold = this.italic = this.code = false;
    this.prev = ' ';
  }

  private open(): string {
    return (this.bold ? '\x1b[1m' : '') + (this.italic ? '\x1b[3m' : '') + (this.code ? this.codeOn : '');
  }

  private close(): string {
    return (this.code ? this.codeOff : '') + (this.italic ? '\x1b[23m' : '') + (this.bold ? '\x1b[22m' : '');
  }

  public format(text: string): string {
    if (!text) return '';
    const colour = !!(pc.isColorSupported);
    let out = colour ? this.open() : '';
    const chars = Array.from(text);
    const isWord = (c: string | undefined) => !!c && /[\p{L}\p{N}]/u.test(c);
    const isSpace = (c: string | undefined) => c === undefined || /\s/.test(c);

    for (let i = 0; i < chars.length; i++) {
      const c = chars[i];
      const next = chars[i + 1];

      if (c === '`') {
        // A code span: inside it nothing else is a mark.
        this.code = !this.code;
        out += colour ? (this.code ? this.codeOn : this.codeOff) : '';
        this.prev = c;
        continue;
      }
      if (this.code) {
        out += c;
        this.prev = c;
        continue;
      }
      if (c === '*' && next === '*') {
        const after = chars[i + 2];
        const opens = !this.bold && !isSpace(after) && after !== '*' && !isWord(this.prev);
        const closes = this.bold && !isSpace(this.prev);
        if (opens || closes) {
          this.bold = opens;
          out += colour ? (opens ? '\x1b[1m' : '\x1b[22m') : '';
          i++;
          this.prev = '*';
          continue;
        }
      } else if (c === '*') {
        const opens = !this.italic && !isSpace(next) && next !== '*' && !isWord(this.prev);
        const closes = this.italic && !isSpace(this.prev) && !isWord(next);
        if (opens || closes) {
          this.italic = opens;
          out += colour ? (opens ? '\x1b[3m' : '\x1b[23m') : '';
          this.prev = '*';
          continue;
        }
      }
      out += c;
      this.prev = c;
    }
    return out + (colour ? this.close() : '');
  }
}

/** Width of horizontal rules / code frames: leaves room for the 2-column gutter. */
function ruleWidth(max: number): number {
  return Math.max(10, Math.min((process.stdout.columns || 40) - 4, max));
}

/** A code fence of the language `markdown` / `md` holds markdown to be shown formatted, not source to be shown as it is (`/copy` gives the source). */
const MD_FENCE_LANG = /^(?:markdown|md)$/i;

/** Takes the fences off ```markdown blocks so that their content is rendered as markdown. */
export function unfenceMarkdown(text: string): string {
  return text.replace(/^[ \t]*```[ \t]*(?:markdown|md)[^\n]*\n([\s\S]*?)^[ \t]*```[ \t]*$/gim, (_m, body: string) => body.replace(/\n$/, ''));
}

export function renderMarkdown(content: string): string {
  if (!content) return '';
  try {
    return (marked.parse(unfenceMarkdown(content)) as string).trim();
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
  /** Inside a ```markdown fence: the lines are markdown, drawn formatted and without a frame. */
  private inMdFence = false;
  private codeBlockLang = '';
  private inTable = false;
  private tableBuffer: string[] = [];
  private readonly styler = new InlineStyler();
  /** The buffer starts a line. After words of a line were written it does not: its rest is prose, not a list item or a heading. */
  private atLineStart = true;

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
        if (this.atLineStart) this.processLine(line);
        else this.sink(this.formatInline(line) + '\n');
        this.atLineStart = true;
        this.styler.endLine();
      }
    }

    // 2. Stream regular prose words immediately in real-time
    if (!this.inCodeBlock && !this.inTable && this.buffer.length > 0) {
      const trimmed = this.buffer.trimStart();
      // Only spaces so far (the indent of a nested item): wait for what follows.
      const onlyBlank = trimmed.length === 0;
      const isSpecialLine = onlyBlank || this.atLineStart && (
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
        trimmed === '***');

      if (!isSpecialLine) {
        const lastSpaceIdx = Math.max(this.buffer.lastIndexOf(' '), this.buffer.lastIndexOf('\t'));
        if (lastSpaceIdx > 0) {
          const wordsToFlush = this.buffer.slice(0, lastSpaceIdx + 1);
          this.buffer = this.buffer.slice(lastSpaceIdx + 1);
          this.sink(this.formatInline(wordsToFlush));
          this.atLineStart = false;
        }
      }
    }
  }

  private processLine(line: string) {
    const trimmed = line.trim();

    // 1. Code block boundary
    if (trimmed.startsWith('```')) {
      if (this.inMdFence) {
        this.inMdFence = false;
        return;
      }
      if (!this.inCodeBlock && MD_FENCE_LANG.test(trimmed.slice(3).trim().split(/\s+/)[0] ?? '')) {
        this.inMdFence = true;
        return;
      }
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
    const plainMarks = (text: string) => text.replace(/\*{1,2}([^*\n]+)\*{1,2}/g, '$1').replace(/`/g, '');
    if (trimmed.startsWith('# ')) {
      this.sink('\n' + palette.bold(palette.accent(plainMarks(trimmed.slice(2)))) + '\n\n');
    } else if (trimmed.startsWith('## ')) {
      this.sink('\n' + palette.bold(palette.text(plainMarks(trimmed.slice(3)))) + '\n');
    } else if (/^#{3,6} /.test(trimmed)) {
      this.sink('\n' + palette.bold(palette.warn(plainMarks(trimmed.replace(/^#+ /, '')))) + '\n');
    } else if (trimmed.startsWith('- ') || trimmed.startsWith('* ')) {
      // A nested item keeps its indentation.
      const indent = ' '.repeat(Math.min(8, line.length - line.trimStart().length));
      this.sink('  ' + indent + palette.accent('• ') + this.formatInline(trimmed.slice(2)) + '\n');
    } else if (/^\d+\.\s/.test(trimmed)) {
      const match = trimmed.match(/^(\d+\.)\s*(.*)$/);
      if (match) {
        this.sink('  ' + palette.accent(match[1]) + ' ' + this.formatInline(match[2]) + '\n');
      } else {
        this.sink(this.formatInline(line) + '\n');
      }
    } else if (trimmed.startsWith('> ')) {
      this.sink(palette.faint('  │ ') + this.formatInline(trimmed.slice(2)) + '\n');
    } else if (trimmed === '---' || trimmed === '***' || trimmed === '___') {
      this.sink(palette.rule('─'.repeat(ruleWidth(60))) + '\n');
    } else {
      this.sink(this.formatInline(line) + '\n');
    }
  }

  private formatInline(text: string): string {
    return this.styler.format(text);
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
    this.inMdFence = false;
    this.codeBlockLang = '';
    this.inTable = false;
    this.tableBuffer = [];
    this.atLineStart = true;
    this.styler.endLine();
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
      this.atLineStart = true;
      this.styler.endLine();
    }
  }
}
