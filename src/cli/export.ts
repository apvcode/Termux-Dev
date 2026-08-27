import fs from 'fs/promises';
import path from 'path';
import { Message } from '../core/types.js';

export interface ExportResult {
  success: boolean;
  filePath: string;
  error?: string;
}

export class SessionExporter {
  static async exportToMarkdown(
    messages: Message[],
    model: string,
    targetFilename?: string
  ): Promise<ExportResult> {
    try {
      const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
      let fileName = targetFilename?.trim() || `devx-session-${timestamp.slice(0, 19)}.md`;
      if (!fileName.endsWith('.md')) fileName += '.md';

      const filePath = path.resolve(process.cwd(), fileName);

      const lines: string[] = [
        `# 📝 devx Session Transcript`,
        ``,
        `- **Date:** ${new Date().toLocaleString()}`,
        `- **Model:** \`${model}\``,
        `- **Total Messages:** ${messages.length}`,
        `- **Working Directory:** \`${process.cwd()}\``,
        ``,
        `---`,
        ``
      ];

      for (const msg of messages) {
        if (msg.role === 'system') continue; // Skip raw system prompt

        if (msg.role === 'user') {
          lines.push(`## 👤 User\n`);
          lines.push(msg.content || '');
          if (msg.images && msg.images.length > 0) {
            lines.push(`\n*Attached Images: ${msg.images.map(i => i.path).join(', ')}*`);
          }
          lines.push(`\n---\n`);
        } else if (msg.role === 'assistant') {
          lines.push(`## 🤖 Assistant\n`);
          if (msg.content) {
            lines.push(msg.content);
          }
          if (msg.tool_calls && msg.tool_calls.length > 0) {
            lines.push(`\n**Executed Tools:**`);
            for (const tc of msg.tool_calls) {
              lines.push(`- \`${tc.name}\`: \`\`\`json\n${JSON.stringify(tc.arguments, null, 2)}\n\`\`\``);
            }
          }
          lines.push(`\n---\n`);
        } else if (msg.role === 'tool') {
          lines.push(`> **Tool Result (${msg.name || 'tool'}):**\n`);
          let displayContent = msg.content || '';
          if (displayContent.length > 2000) {
            displayContent = displayContent.slice(0, 2000) + '\n... [truncated]';
          }
          lines.push('```\n' + displayContent + '\n```\n');
        }
      }

      await fs.writeFile(filePath, lines.join('\n'), 'utf8');
      return { success: true, filePath };
    } catch (err: any) {
      return { success: false, filePath: '', error: err.message };
    }
  }
}
