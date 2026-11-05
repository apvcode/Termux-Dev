import fsSync from 'fs';
import path from 'path';
import os from 'os';
import { createHash } from 'crypto';
import pc from 'picocolors';
import { MCPClient } from './client.js';
import { getCurrentTheme } from '../cli/theme.js';
function stripAnsi(str) {
    return str.replace(/\x1b\[[0-9;]*[a-zA-Z]/g, '');
}
// Config files that live inside the project. MCP servers are arbitrary commands, so
// whatever a cloned repository declares here is NOT started until the user approved it.
const PROJECT_CONFIG_FILES = [
    path.join('.devx', 'mcp.json'),
    path.join('.claude', 'mcp.json'),
    '.devx.json',
    '.mcp.json'
];
function readMcpServers(file) {
    const result = {};
    try {
        if (!fsSync.existsSync(file))
            return result;
        const parsed = JSON.parse(fsSync.readFileSync(file, 'utf8'));
        if (parsed && typeof parsed.mcpServers === 'object' && parsed.mcpServers) {
            for (const [name, cfg] of Object.entries(parsed.mcpServers)) {
                if (cfg && typeof cfg === 'object' && cfg.command) {
                    result[name] = cfg;
                }
            }
        }
    }
    catch { }
    return result;
}
/** MCP servers declared by the current project (not started, only listed). */
export function discoverProjectMcpServers(cwd = process.cwd()) {
    const found = [];
    for (const rel of PROJECT_CONFIG_FILES) {
        const file = path.join(cwd, rel);
        for (const [name, config] of Object.entries(readMcpServers(file))) {
            found.push({ file: rel, name, config });
        }
    }
    return found;
}
/** Stable fingerprint of the commands, arguments and env of the project's servers. */
export function hashProjectMcpServers(servers) {
    const normalized = servers
        .map(s => ({
        file: s.file,
        name: s.name,
        command: s.config.command,
        args: s.config.args || [],
        env: Object.fromEntries(Object.entries(s.config.env || {}).sort(([a], [b]) => a.localeCompare(b))),
        disabled: !!s.config.disabled
    }))
        .sort((a, b) => (a.file + a.name).localeCompare(b.file + b.name));
    return createHash('sha256').update(JSON.stringify(normalized)).digest('hex');
}
export class MCPManager {
    static instance;
    clients = new Map();
    tools = [];
    serverStatuses = new Map();
    isInitialized = false;
    trustProject = false;
    constructor() { }
    static getInstance() {
        if (!MCPManager.instance) {
            MCPManager.instance = new MCPManager();
        }
        return MCPManager.instance;
    }
    /**
     * @param explicitConfigs use exactly these servers instead of reading config files
     * @param trustProject    also start the servers declared inside the current project
     *                        (only pass true after the user approved them)
     */
    async init(explicitConfigs, trustProject = false) {
        if (this.isInitialized) {
            return this.tools;
        }
        this.trustProject = trustProject;
        const configs = explicitConfigs || await this.loadConfigs(trustProject);
        const serverEntries = Object.entries(configs);
        if (serverEntries.length === 0) {
            this.isInitialized = true;
            return [];
        }
        const initPromises = serverEntries.map(async ([name, cfg]) => {
            if (cfg.disabled) {
                this.serverStatuses.set(name, {
                    name,
                    command: `${cfg.command} ${(cfg.args || []).join(' ')}`.trim(),
                    status: 'disabled',
                    toolsCount: 0,
                    tools: []
                });
                return;
            }
            this.serverStatuses.set(name, {
                name,
                command: `${cfg.command} ${(cfg.args || []).join(' ')}`.trim(),
                status: 'connecting',
                toolsCount: 0,
                tools: []
            });
            const client = new MCPClient(name, cfg);
            this.clients.set(name, client);
            try {
                const mcpTools = await client.start();
                this.serverStatuses.set(name, {
                    name,
                    command: `${cfg.command} ${(cfg.args || []).join(' ')}`.trim(),
                    status: 'connected',
                    toolsCount: mcpTools.length,
                    tools: mcpTools.map(t => t.name)
                });
                // Convert MCP tools into devx Tool format
                for (const mt of mcpTools) {
                    const namespacedName = `mcp__${name}__${mt.name}`;
                    const devxTool = {
                        name: namespacedName,
                        definition: {
                            name: namespacedName,
                            description: `[MCP: ${name}] ${mt.description || mt.name}`,
                            parameters: {
                                type: 'object',
                                properties: mt.inputSchema?.properties || {},
                                required: mt.inputSchema?.required
                            }
                        },
                        validateArgs(_args) { },
                        async execute(args) {
                            return await client.callTool(mt.name, args);
                        }
                    };
                    this.tools.push(devxTool);
                }
            }
            catch (err) {
                this.serverStatuses.set(name, {
                    name,
                    command: `${cfg.command} ${(cfg.args || []).join(' ')}`.trim(),
                    status: 'failed',
                    toolsCount: 0,
                    tools: [],
                    error: err.message
                });
            }
        });
        await Promise.all(initPromises);
        this.isInitialized = true;
        return this.tools;
    }
    getTools() {
        return this.tools;
    }
    getStatuses() {
        return Array.from(this.serverStatuses.values());
    }
    async reload() {
        this.stopAll();
        this.isInitialized = false;
        this.clients.clear();
        this.tools = [];
        this.serverStatuses.clear();
        return await this.init(undefined, this.trustProject);
    }
    stopAll() {
        for (const client of this.clients.values()) {
            try {
                client.close();
            }
            catch { }
        }
        this.clients.clear();
    }
    renderStatusCard() {
        const theme = getCurrentTheme();
        const statuses = this.getStatuses();
        const cols = Math.min(process.stdout.columns || 80, 80);
        const boxWidth = Math.max(48, cols - 4);
        const innerWidth = boxWidth - 6;
        const padRow = (left, right) => {
            const plainLen = stripAnsi(left).length + stripAnsi(right).length;
            const spaces = Math.max(1, innerWidth - plainLen);
            return `│  ${left}${' '.repeat(spaces)}${right}  │`;
        };
        const header = theme.colorFn('┌─ ') + pc.bold('Model Context Protocol (MCP) Servers') + ' ' + theme.colorFn('─'.repeat(Math.max(2, boxWidth - 43)) + '┐');
        const divider = theme.colorFn('├' + '─'.repeat(boxWidth - 2) + '┤');
        const footer = theme.colorFn('└' + '─'.repeat(boxWidth - 2) + '┘');
        const lines = [header];
        if (statuses.length === 0) {
            lines.push(padRow(pc.dim('No MCP servers configured.'), ''));
            lines.push(padRow(pc.dim('Configure in .devx/mcp.json or ~/.devxrc.json'), ''));
            lines.push(footer);
            return lines.join('\n') + '\n';
        }
        for (let i = 0; i < statuses.length; i++) {
            const s = statuses[i];
            if (i > 0)
                lines.push(divider);
            let statusBadge = pc.green('● Connected');
            if (s.status === 'connecting')
                statusBadge = pc.yellow('● Connecting');
            if (s.status === 'failed')
                statusBadge = pc.red('● Failed');
            if (s.status === 'disabled')
                statusBadge = pc.dim('○ Disabled');
            lines.push(padRow(pc.bold(pc.white(`Server: ${s.name}`)), statusBadge));
            lines.push(padRow(pc.dim(`  Command: ${s.command.slice(0, 36)}${s.command.length > 36 ? '...' : ''}`), pc.cyan(`${s.toolsCount} tools`)));
            if (s.tools.length > 0) {
                const toolsListStr = s.tools.slice(0, 3).map(t => pc.dim(`• ${t}`)).join(' ');
                const extra = s.tools.length > 3 ? pc.dim(` +${s.tools.length - 3} more`) : '';
                lines.push(padRow(`  ${toolsListStr}${extra}`, ''));
            }
            if (s.error) {
                lines.push(padRow(pc.red(`  Error: ${s.error.slice(0, innerWidth - 10)}`), ''));
            }
        }
        lines.push(footer);
        return lines.join('\n') + '\n';
    }
    async loadConfigs(includeProject) {
        const result = {};
        // User-level configuration is always trusted: the user wrote it.
        const configPaths = [
            path.join(os.homedir(), '.devxrc.json'),
            path.join(os.homedir(), '.devx', 'mcp.json')
        ];
        if (includeProject) {
            for (const rel of PROJECT_CONFIG_FILES) {
                configPaths.push(path.join(process.cwd(), rel));
            }
        }
        for (const pth of configPaths) {
            Object.assign(result, readMcpServers(pth));
        }
        return result;
    }
}
