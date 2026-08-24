import { marked } from 'marked';
import { markedTerminal } from 'marked-terminal';

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

export class MarkdownStreamer {
  private buffer = '';
  private tableBuffer: string[] = [];
  private inTable = false;

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
    if (trimmed.startsWith('|') && trimmed.endsWith('|')) {
      this.inTable = true;
      this.tableBuffer.push(line);
      return;
    }

    if (this.inTable) {
      this.flushTable();
      this.inTable = false;
    }

    const rendered = renderMarkdown(line);
    process.stdout.write(rendered + '\n');
  }

  private flushTable() {
    if (this.tableBuffer.length > 0) {
      const tableMd = this.tableBuffer.join('\n');
      const rendered = renderMarkdown(tableMd);
      process.stdout.write(rendered + '\n');
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
      process.stdout.write(renderMarkdown(this.buffer));
      this.buffer = '';
    }
  }
}
