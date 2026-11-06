import fsSync from 'fs';
import path from 'path';
import os from 'os';
import { createHash } from 'crypto';
import pc from 'picocolors';
import { MCPServerConfig, MCPServersConfig, MCPServerStatus, MCPToolInfo } from './types.js';
import { MCPClient } from './client.js';
import { HttpMCPClient } from './http-client.js';
import { Tool } from '../core/types.js';
import { getCurrentTheme } from '../cli/theme.js';

function stripAnsi(str: string): string {
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

/** In the home folder the "project" files are the user's own files: they are not a project's, and need no approval. */
function isUserFile(file: string): boolean {
  const abs = path.resolve(file);
  return USER_FILES.some((f) => path.resolve(os.homedir(), f.rel) === abs);
}

function readMcpServers(file: string): Record<string, MCPServerConfig> {
  const result: Record<string, MCPServerConfig> = {};
  try {
    if (!fsSync.existsSync(file)) return result;
    const parsed: MCPServersConfig = JSON.parse(fsSync.readFileSync(file, 'utf8'));
    if (parsed && typeof parsed.mcpServers === 'object' && parsed.mcpServers) {
      for (const [name, cfg] of Object.entries(parsed.mcpServers)) {
        if (cfg && typeof cfg === 'object' && (cfg.command || cfg.url)) {
          result[name] = cfg;
        }
      }
    }
  } catch {}
  return result;
}

export interface ProjectMcpServer {
  file: string;
  name: string;
  config: MCPServerConfig;
}

/** MCP servers declared by the current project (not started, only listed). */
export function discoverProjectMcpServers(cwd: string = process.cwd()): ProjectMcpServer[] {
  const found: ProjectMcpServer[] = [];
  for (const rel of PROJECT_CONFIG_FILES) {
    const file = path.join(cwd, rel);
    if (isUserFile(file)) continue;
    for (const [name, config] of Object.entries(readMcpServers(file))) {
      found.push({ file: rel, name, config });
    }
  }
  return found;
}

/** Stable fingerprint of the commands, arguments and env of the project's servers. */
export function hashProjectMcpServers(servers: ProjectMcpServer[]): string {
  const normalized = servers
    .map(s => ({
      file: s.file,
      name: s.name,
      command: s.config.command ?? '',
      url: s.config.url ?? '',
      headers: Object.fromEntries(Object.entries(s.config.headers || {}).sort(([a], [b]) => a.localeCompare(b))),
      args: s.config.args || [],
      env: Object.fromEntries(Object.entries(s.config.env || {}).sort(([a], [b]) => a.localeCompare(b))),
      disabled: !!s.config.disabled
    }))
    .sort((a, b) => (a.file + a.name).localeCompare(b.file + b.name));
  return createHash('sha256').update(JSON.stringify(normalized)).digest('hex');
}

interface ServerEntry {
  config: MCPServerConfig;
  source: string;
  scope: 'user' | 'project';
}

const USER_FILES = [
  { rel: '.devxrc.json', label: '~/.devxrc.json' },
  { rel: path.join('.devx', 'mcp.json'), label: '~/.devx/mcp.json' },
];

/** What the user switched on or off in the /mcp panel. Kept apart from the config files, which are never edited. */
const stateFile = () => path.join(os.homedir(), '.devx', 'mcp-state.json');

function readOverrides(): Record<string, boolean> {
  try {
    const parsed = JSON.parse(fsSync.readFileSync(stateFile(), 'utf8'));
    return parsed && typeof parsed.disabled === 'object' && parsed.disabled ? parsed.disabled : {};
  } catch {
    return {};
  }
}

function writeOverrides(disabled: Record<string, boolean>): void {
  try {
    const file = stateFile();
    fsSync.mkdirSync(path.dirname(file), { recursive: true, mode: 0o700 });
    fsSync.writeFileSync(file, JSON.stringify({ disabled }, null, 2), { mode: 0o600 });
  } catch {}
}

const stateKey = (scope: string, name: string, cwd = process.cwd()) => (scope === 'project' ? `${cwd}::${name}` : `user::${name}`);

function toolInfo(def: { name: string; description?: string; annotations?: any; inputSchema?: any }): MCPToolInfo {
  const props = def.inputSchema?.properties && typeof def.inputSchema.properties === 'object' ? def.inputSchema.properties : {};
  const required: string[] = Array.isArray(def.inputSchema?.required) ? def.inputSchema.required : [];
  const a = def.annotations || {};
  return {
    name: def.name,
    description: String(def.description || ''),
    readOnly: a.readOnlyHint === true,
    // MCP assumes a tool may be destructive unless it says otherwise; only say so when it is stated.
    destructive: a.destructiveHint === true && a.readOnlyHint !== true,
    openWorld: a.openWorldHint === true,
    params: Object.entries(props).map(([name, spec]: [string, any]) => ({
      name,
      type: Array.isArray(spec?.type) ? spec.type.join('|') : String(spec?.type ?? 'any'),
      required: required.includes(name),
      description: String(spec?.description || ''),
    })),
  };
}

const userMcpFile = () => path.join(os.homedir(), '.devx', 'mcp.json');

/**
 * The servers of Claude Code (`~/.claude.json`: the user's own and the ones of this folder and its parents).
 * A local server is taken as it is; a remote one (an address) is reached through the
 * `mcp-remote` bridge, which devx can start like any local command.
 */
export function readClaudeServers(cwd: string = process.cwd()): Record<string, MCPServerConfig> {
  const out: Record<string, MCPServerConfig> = {};
  let data: any;
  try {
    data = JSON.parse(fsSync.readFileSync(path.join(os.homedir(), '.claude.json'), 'utf8'));
  } catch {
    return out;
  }
  // Claude Code keeps some servers per folder: the ones of this folder and of the folders above it count.
  const ancestors: string[] = [];
  for (let dir = cwd; ; dir = path.dirname(dir)) {
    ancestors.unshift(dir);
    if (path.dirname(dir) === dir) break;
  }
  const groups = [data?.mcpServers, ...ancestors.map((dir) => data?.projects?.[dir]?.mcpServers)];
  for (const group of groups) {
    if (!group || typeof group !== 'object') continue;
    for (const [name, raw] of Object.entries<any>(group)) {
      if (!raw || typeof raw !== 'object') continue;
      if (typeof raw.command === 'string' && raw.command) {
        out[name] = { command: raw.command, ...(Array.isArray(raw.args) ? { args: raw.args.map(String) } : {}), ...(raw.env && typeof raw.env === 'object' ? { env: raw.env } : {}) };
      } else if (typeof raw.url === 'string' && raw.url) {
        out[name] = { command: 'npx', args: ['-y', 'mcp-remote', raw.url] };
      }
    }
  }
  return out;
}

/** Writes servers into the user's own file `~/.devx/mcp.json`. A name that is already there is left alone unless `overwrite`. Returns the names that were written. */
export function saveUserServers(servers: Record<string, MCPServerConfig>, overwrite = false): string[] {
  const file = userMcpFile();
  let current: any = {};
  try {
    current = JSON.parse(fsSync.readFileSync(file, 'utf8'));
  } catch {}
  const existing: Record<string, MCPServerConfig> = current && typeof current.mcpServers === 'object' && current.mcpServers ? current.mcpServers : {};
  const written: string[] = [];
  for (const [name, cfg] of Object.entries(servers)) {
    if (name in existing && !overwrite) continue;
    existing[name] = cfg;
    written.push(name);
  }
  if (written.length > 0) {
    fsSync.mkdirSync(path.dirname(file), { recursive: true, mode: 0o700 });
    // The file may hold tokens in `env`: readable by the owner only.
    fsSync.writeFileSync(file, JSON.stringify({ ...current, mcpServers: existing }, null, 2), { mode: 0o600 });
  }
  return written;
}

/** What the manager needs from a client, local (stdio) or remote (HTTP). */
export interface McpClientLike {
  start(): Promise<import('./types.js').MCPToolDefinition[]>;
  callTool(toolName: string, args: any): Promise<string>;
  getCapabilities(): string[];
  close(): void;
}

/** Removes a server from the user's own file `~/.devx/mcp.json`. Returns false when it is not there (a server of another file has to be removed by hand). */
export function removeUserServer(name: string): boolean {
  const file = userMcpFile();
  try {
    const current = JSON.parse(fsSync.readFileSync(file, 'utf8'));
    if (!current?.mcpServers || !(name in current.mcpServers)) return false;
    delete current.mcpServers[name];
    fsSync.writeFileSync(file, JSON.stringify(current, null, 2), { mode: 0o600 });
    // What the panel remembered about it (switched off, ...) goes too.
    const overrides = readOverrides();
    if (overrides[stateKey('user', name)] !== undefined) {
      delete overrides[stateKey('user', name)];
      writeOverrides(overrides);
    }
    return true;
  } catch {
    return false;
  }
}

export class MCPManager {
  private static instance: MCPManager;
  private clients = new Map<string, McpClientLike>();
  private entries = new Map<string, ServerEntry>();
  private tools: Tool[] = [];
  private serverStatuses = new Map<string, MCPServerStatus>();
  private isInitialized = false;
  private trustProject = false;

  private constructor() {}

  static getInstance(): MCPManager {
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
  async init(explicitConfigs?: Record<string, MCPServerConfig>, trustProject = false): Promise<Tool[]> {
    if (this.isInitialized) {
      return this.tools;
    }
    this.trustProject = trustProject;

    this.entries.clear();
    if (explicitConfigs) {
      for (const [name, config] of Object.entries(explicitConfigs)) this.entries.set(name, { config, source: 'explicit', scope: 'user' });
    } else {
      for (const [name, entry] of await this.loadEntries(trustProject)) this.entries.set(name, entry);
    }

    if (this.entries.size === 0) {
      this.isInitialized = true;
      return [];
    }

    await Promise.all(Array.from(this.entries.keys()).map((name) => this.startServer(name)));
    this.isInitialized = true;
    return this.tools;
  }

  private isDisabled(name: string): boolean {
    const entry = this.entries.get(name)!;
    const override = readOverrides()[stateKey(entry.scope, name)];
    return override !== undefined ? override : !!entry.config.disabled;
  }

  /** Starts one server and registers its tools. Never throws: a failure becomes the server's status. */
  private async startServer(name: string): Promise<void> {
    const entry = this.entries.get(name);
    if (!entry) return;
    const { config: cfg, source, scope } = entry;
    const command = cfg.url ? cfg.url : `${cfg.command ?? ''} ${(cfg.args || []).join(' ')}`.trim();
    const base = { name, command, source, scope, toolsCount: 0, tools: [] as string[] };

    if (this.isDisabled(name)) {
      this.serverStatuses.set(name, { ...base, status: 'disabled' });
      return;
    }

    this.serverStatuses.set(name, { ...base, status: 'connecting' });
    const client: McpClientLike = cfg.url ? new HttpMCPClient(name, { ...cfg, disabled: false }) : new MCPClient(name, { ...cfg, disabled: false });
    this.clients.set(name, client);

    try {
      const mcpTools = await client.start();
      this.serverStatuses.set(name, {
        ...base,
        status: 'connected',
        toolsCount: mcpTools.length,
        tools: mcpTools.map((t) => t.name),
        capabilities: client.getCapabilities(),
        toolInfo: mcpTools.map(toolInfo),
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
              required: mt.inputSchema?.required,
            },
          },
          validateArgs(_args: any) {},
          async execute(args: any) {
            return await client.callTool(mt.name, args);
          },
        };
        this.tools.push(devxTool);
      }
    } catch (err: any) {
      this.clients.delete(name);
      this.serverStatuses.set(name, { ...base, status: 'failed', error: err.message });
    }
  }

  /** Stops a server and takes its tools away from the agent. */
  private stopServer(name: string): void {
    try {
      this.clients.get(name)?.close();
    } catch {}
    this.clients.delete(name);
    this.tools = this.tools.filter((t) => !t.name.startsWith(`mcp__${name}__`));
  }

  getTools(): Tool[] {
    return this.tools;
  }

  getStatuses(): MCPServerStatus[] {
    return Array.from(this.serverStatuses.values());
  }

  getStatus(name: string): MCPServerStatus | undefined {
    return this.serverStatuses.get(name);
  }

  /** Starts a server again (also after it failed). The tools of the agent follow at once. */
  async reconnect(name: string): Promise<MCPServerStatus | undefined> {
    if (!this.entries.has(name)) return undefined;
    this.stopServer(name);
    await this.startServer(name);
    return this.serverStatuses.get(name);
  }

  /** Switches a server off: it is stopped and stays off in the next sessions too. */
  async setEnabled(name: string, enabled: boolean): Promise<MCPServerStatus | undefined> {
    const entry = this.entries.get(name);
    if (!entry) return undefined;
    const overrides = readOverrides();
    overrides[stateKey(entry.scope, name)] = !enabled;
    writeOverrides(overrides);
    this.stopServer(name);
    await this.startServer(name);
    return this.serverStatuses.get(name);
  }

  async reload(): Promise<Tool[]> {
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

    const header = theme.colorFn('┌─ ') + pc.bold('Model Context Protocol (MCP) Servers') + ' ' + theme.colorFn('─'.repeat(Math.max(2, boxWidth - 43)) + '┐');
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

      let statusBadge = pc.green('● Connected');
      if (s.status === 'connecting') statusBadge = pc.yellow('● Connecting');
      if (s.status === 'failed') statusBadge = pc.red('● Failed');
      if (s.status === 'disabled') statusBadge = pc.dim('○ Disabled');

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

  private async loadEntries(includeProject: boolean): Promise<Map<string, ServerEntry>> {
    const result = new Map<string, ServerEntry>();

    // User-level configuration is always trusted: the user wrote it. A later file wins over an earlier one.
    for (const f of USER_FILES) {
      for (const [name, config] of Object.entries(readMcpServers(path.join(os.homedir(), f.rel)))) {
        result.set(name, { config, source: f.label, scope: 'user' });
      }
    }
    if (includeProject) {
      for (const rel of PROJECT_CONFIG_FILES) {
        if (isUserFile(path.join(process.cwd(), rel))) continue;
        for (const [name, config] of Object.entries(readMcpServers(path.join(process.cwd(), rel)))) {
          result.set(name, { config, source: rel, scope: 'project' });
        }
      }
    }
    return result;
  }
}
