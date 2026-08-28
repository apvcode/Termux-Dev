import fs from 'fs/promises';
import fsSync from 'fs';
import path from 'path';
import os from 'os';
import pc from 'picocolors';
import { MCPServerConfig, MCPServersConfig, MCPServerStatus } from './types.js';
import { MCPClient } from './client.js';
import { Tool } from '../core/types.js';
import { getCurrentTheme } from '../cli/theme.js';

function stripAnsi(str: string): string {
  return str.replace(/\x1b\[[0-9;]*[a-zA-Z]/g, '');
}

export class MCPManager {
  private static instance: MCPManager;
  private clients = new Map<string, MCPClient>();
  private tools: Tool[] = [];
  private serverStatuses = new Map<string, MCPServerStatus>();
  private isInitialized = false;

  private constructor() {}

  static getInstance(): MCPManager {
    if (!MCPManager.instance) {
      MCPManager.instance = new MCPManager();
    }
    return MCPManager.instance;
  }

  async init(explicitConfigs?: Record<string, MCPServerConfig>): Promise<Tool[]> {
    if (this.isInitialized) {
      return this.tools;
    }

    const configs = explicitConfigs || await this.loadConfigs();
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
          const devxTool: Tool = {
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
            validateArgs(_args: any) {},
            async execute(args: any) {
              return await client.callTool(mt.name, args);
            }
          };
          this.tools.push(devxTool);
        }
      } catch (err: any) {
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

  getTools(): Tool[] {
    return this.tools;
  }

  getStatuses(): MCPServerStatus[] {
    return Array.from(this.serverStatuses.values());
  }

  async reload(): Promise<Tool[]> {
    this.stopAll();
    this.isInitialized = false;
    this.clients.clear();
    this.tools = [];
    this.serverStatuses.clear();
    return await this.init();
  }

  stopAll() {
    for (const client of this.clients.values()) {
      try {
        client.close();
      } catch {}
    }
    this.clients.clear();
  }

  renderStatusCard(): string {
    const theme = getCurrentTheme();
    const statuses = this.getStatuses();
    const cols = Math.min(process.stdout.columns || 80, 80);
    const boxWidth = Math.max(48, cols - 4);
    const innerWidth = boxWidth - 6;

    const padRow = (left: string, right: string) => {
      const plainLen = stripAnsi(left).length + stripAnsi(right).length;
      const spaces = Math.max(1, innerWidth - plainLen);
      return `│  ${left}${' '.repeat(spaces)}${right}  │`;
    };

    const header = theme.colorFn('┌─ ') + pc.bold('🔌 Model Context Protocol (MCP) Servers') + ' ' + theme.colorFn('─'.repeat(Math.max(2, boxWidth - 43)) + '┐');
    const divider = theme.colorFn('├' + '─'.repeat(boxWidth - 2) + '┤');
    const footer = theme.colorFn('└' + '─'.repeat(boxWidth - 2) + '┘');

    const lines: string[] = [header];

    if (statuses.length === 0) {
      lines.push(padRow(pc.dim('No MCP servers configured.'), ''));
      lines.push(padRow(pc.dim('Configure in .devx/mcp.json or ~/.devxrc.json'), ''));
      lines.push(footer);
      return lines.join('\n') + '\n';
    }

    for (let i = 0; i < statuses.length; i++) {
      const s = statuses[i];
      if (i > 0) lines.push(divider);

      let statusBadge = pc.green('🟢 Connected');
      if (s.status === 'connecting') statusBadge = pc.yellow('🟡 Connecting');
      if (s.status === 'failed') statusBadge = pc.red('🔴 Failed');
      if (s.status === 'disabled') statusBadge = pc.dim('⚪ Disabled');

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

  private async loadConfigs(): Promise<Record<string, MCPServerConfig>> {
    const result: Record<string, MCPServerConfig> = {};

    const configPaths = [
      path.join(os.homedir(), '.devxrc.json'),
      path.join(process.cwd(), '.devx', 'mcp.json'),
      path.join(process.cwd(), '.claude', 'mcp.json'),
      path.join(process.cwd(), '.devx.json'),
      path.join(process.cwd(), '.mcp.json')
    ];

    for (const pth of configPaths) {
      try {
        if (fsSync.existsSync(pth)) {
          const raw = await fs.readFile(pth, 'utf8');
          const parsed: MCPServersConfig = JSON.parse(raw);
          if (parsed && typeof parsed.mcpServers === 'object') {
            for (const [name, cfg] of Object.entries(parsed.mcpServers)) {
              if (cfg && typeof cfg === 'object' && cfg.command) {
                result[name] = cfg;
              }
            }
          }
        }
      } catch {}
    }

    return result;
  }
}
