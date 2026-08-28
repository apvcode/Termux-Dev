import fs from 'fs/promises';
import fsSync from 'fs';
import path from 'path';
import os from 'os';
export class CustomCommandManager {
    static cachedCommands = null;
    static lastScanTime = 0;
    /**
     * Scans for custom commands in project and global directories
     */
    static async listCommands(forceRefresh = false) {
        const now = Date.now();
        if (!forceRefresh && this.cachedCommands && now - this.lastScanTime < 5000) {
            return this.cachedCommands;
        }
        const commandMap = new Map();
        // 1. Search directories (higher priority overrides lower priority)
        const searchDirs = [
            path.join(os.homedir(), '.devx', 'commands'),
            path.join(process.cwd(), '.claude', 'commands'),
            path.join(process.cwd(), '.devx', 'commands')
        ];
        for (const dir of searchDirs) {
            try {
                if (!fsSync.existsSync(dir))
                    continue;
                const entries = await fs.readdir(dir, { withFileTypes: true });
                for (const entry of entries) {
                    if (entry.isFile() && (entry.name.endsWith('.md') || entry.name.endsWith('.prompt'))) {
                        const rawName = entry.name.replace(/\.(md|prompt)$/i, '').toLowerCase();
                        const filePath = path.join(dir, entry.name);
                        const content = await fs.readFile(filePath, 'utf8');
                        const { description, template } = this.parseCommandFile(content, rawName);
                        commandMap.set(rawName, {
                            name: rawName,
                            cmd: `/${rawName}`,
                            desc: description || `Custom command (${path.basename(dir)}/${entry.name})`,
                            promptTemplate: template,
                            sourcePath: filePath
                        });
                    }
                }
            }
            catch { }
        }
        this.cachedCommands = Array.from(commandMap.values());
        this.lastScanTime = now;
        return this.cachedCommands;
    }
    /**
     * Finds a custom command by slash name (e.g. "/deploy" or "deploy")
     */
    static async findCommand(cmdName) {
        const clean = cmdName.replace(/^\//, '').toLowerCase().trim();
        const list = await this.listCommands();
        return list.find(c => c.name === clean) || null;
    }
    /**
     * Expands arguments into the prompt template
     */
    static expandTemplate(template, args) {
        const trimmedArgs = args.trim();
        if (!template.includes('$ARG') && !template.includes('$*') && !template.includes('$1')) {
            return trimmedArgs ? `${template}\n\nUser Arguments: ${trimmedArgs}` : template;
        }
        let expanded = template;
        expanded = expanded.replace(/\$ARG/g, () => trimmedArgs);
        expanded = expanded.replace(/\$\*/g, () => trimmedArgs);
        // Support positional parameters: $1, $2, etc.
        const parts = trimmedArgs.split(/\s+/);
        for (let i = 0; i < parts.length; i++) {
            expanded = expanded.replace(new RegExp(`\\$${i + 1}`, 'g'), () => parts[i]);
        }
        return expanded;
    }
    /**
     * Parses frontmatter (YAML description) and template content
     */
    static parseCommandFile(rawContent, defaultName) {
        let description = '';
        let template = rawContent;
        const frontmatterMatch = rawContent.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n([\s\S]*)$/);
        if (frontmatterMatch) {
            const frontmatter = frontmatterMatch[1];
            template = frontmatterMatch[2].trim();
            const descMatch = frontmatter.match(/description:\s*([^\r\n]+)/i);
            if (descMatch) {
                description = descMatch[1].trim().replace(/^["']|["']$/g, '');
            }
        }
        else {
            // Check for first-line Markdown heading or comment: # Description
            const firstLine = rawContent.split('\n')[0].trim();
            if (firstLine.startsWith('# ')) {
                description = firstLine.replace(/^#\s*/, '').trim();
            }
        }
        return { description, template };
    }
}
