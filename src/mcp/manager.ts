import fsSync from 'fs';
import path from 'path';
import os from 'os';
import { createHash } from 'crypto';
import pc from 'picocolors';
import { MCPServerConfig, MCPServersConfig, MCPServerStatus, MCPToolInfo } from './types.js';
import { MCPClient } from './client.js';
import { HttpMCPClient } from './http-client.js';
import { Message, Tool } from '../core/types.js';
import { toolSchemaTokens } from '../core/context-stats.js';
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
/** Provider APIs only accept [A-Za-z0-9_-]{1,64} as a tool name; servers may use dots, spaces or other scripts. */
export function mcpToolName(server: string, tool: string): string {
  const clean = (v: string) => v.replace(/[^A-Za-z0-9_-]+/g, '_');
  const full = `mcp__${clean(server)}__${clean(tool)}`;
  if (full.length <= 64) return full;
  let h = 0;
  for (const ch of full) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  return `${full.slice(0, 55)}_${h.toString(36).padStart(8, '0').slice(-8)}`;
}

function uniqueName(name: string, existing: Array<{ name: string }>): string {
  let out = name;
  for (let i = 2; existing.some((t) => t.name === out); i++) out = `${name.slice(0, 60)}_${i}`;
  return out;
}

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

/** Tool schemas of the servers that are not lazy by choice stay within this (estimated tokens); the rest waits for the agent. */
export const EAGER_BUDGET_TOKENS = 4000;

/** One tool for the agent: its name, what it does and its parameters (required ones marked with *). */
export function describeTool(tool: Tool): string {
  const desc = tool.definition.description.replace(/^\[MCP: [^\]]*\]\s*/, '').replace(/\s+/g, ' ').trim();
  const required = new Set(tool.definition.parameters.required ?? []);
  const params = Object.keys(tool.definition.parameters.properties ?? {}).map((p) => (required.has(p) ? `${p}*` : p));
  return `- ${tool.name}: ${desc.length > 240 ? desc.slice(0, 237) + '...' : desc}${params.length ? ` (params: ${params.join(', ')})` : ''}`;
}

export class MCPManager {
  private static instance: MCPManager;
  private clients = new Map<string, McpClientLike>();
  private entries = new Map<string, ServerEntry>();
  private tools: Tool[] = [];
  /** Which server a tool belongs to, and its name there (the tool names the agent sees are prefixed). */
  private meta = new Map<string, { server: string; bare: string }>();
  /** What the tool schemas of a server weigh (estimated tokens). */
  private weights = new Map<string, number>();
  /** Tools of lazy servers the agent has loaded, in the order it loaded them. Survives reload() and reconnects. */
  private loaded = new Set<string>();
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
        const namespacedName = uniqueName(mcpToolName(name, mt.name), this.tools);
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
          execute: async (args: any) => {
            // What the agent has used stays in its requests, also when the server later turns lazy.
            this.loaded.add(namespacedName);
            return await client.callTool(mt.name, args);
          },
        };
        this.tools.push(devxTool);
        this.meta.set(namespacedName, { server: name, bare: mt.name });
      }
      // What the schemas of this server cost in every request (decides whether it is sent or waits to be loaded).
      this.weights.set(name, toolSchemaTokens(this.tools.filter((t) => this.meta.get(t.name)?.server === name).map((t) => t.definition)));
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
    for (const t of this.tools) if (this.meta.get(t.name)?.server === name) this.meta.delete(t.name);
    this.tools = this.tools.filter((t) => this.meta.has(t.name));
    this.weights.delete(name);
  }

  /**
   * The servers whose tools are not sent until the agent loads them. A server says so itself
   * (`"lazy": true|false`); otherwise the small ones are sent (loading them would only cost a step) and
   * the big ones wait, so that all the tools sent together stay within EAGER_BUDGET_TOKENS.
   */
  private lazyServers(): Set<string> {
    const lazy = new Set<string>();
    const withTools = Array.from(this.serverStatuses.values()).filter((s) => s.status === 'connected' && s.toolsCount > 0);
    const weight = (n: string) => this.weights.get(n) ?? 0;
    let budget = EAGER_BUDGET_TOKENS;
    const auto: string[] = [];
    for (const s of withTools) {
      const explicit = this.entries.get(s.name)?.config.lazy;
      if (explicit === true) lazy.add(s.name);
      else if (explicit === false) budget -= weight(s.name);
      else auto.push(s.name);
    }
    auto.sort((a, b) => weight(a) - weight(b) || a.localeCompare(b));
    for (const n of auto) {
      if (weight(n) <= budget) budget -= weight(n);
      else lazy.add(n);
    }
    return lazy;
  }

  /** The tools the agent gets in its requests: those of the servers that are not lazy, then the ones it loaded. */
  getTools(): Tool[] {
    const lazy = this.lazyServers();
    if (lazy.size === 0) return this.tools;
    const inLazy = (t: Tool) => lazy.has(this.meta.get(t.name)?.server ?? '');
    const byName = new Map(this.tools.map((t) => [t.name, t]));
    const loaded = Array.from(this.loaded).map((n) => byName.get(n)).filter((t): t is Tool => !!t && inLazy(t));
    return [...this.tools.filter((t) => !inLazy(t)), ...loaded];
  }

  /** Every tool of every connected server, loaded or not. */
  getAllTools(): Tool[] {
    return this.tools;
  }

  /** The tools of one server (case does not matter in the name), loaded or not. */
  toolsOf(server: string): Tool[] {
    const want = server.toLowerCase();
    return this.tools.filter((t) => this.meta.get(t.name)?.server.toLowerCase() === want);
  }

  /** True when some server keeps its tools back: the agent then needs `mcp_search`. */
  hasLazy(): boolean {
    return this.lazyServers().size > 0;
  }

  /** The servers with what the agent needs to know about them to decide whether to look inside. */
  catalog(): Array<{ name: string; lazy: boolean; total: number; loaded: number; tools: string[] }> {
    const lazy = this.lazyServers();
    return Array.from(this.serverStatuses.values())
      .filter((s) => s.status === 'connected' && s.toolsCount > 0)
      .map((s) => {
        const mine = this.tools.filter((t) => this.meta.get(t.name)?.server === s.name);
        const isLazy = lazy.has(s.name);
        return {
          name: s.name,
          lazy: isLazy,
          total: mine.length,
          loaded: isLazy ? mine.filter((t) => this.loaded.has(t.name)).length : mine.length,
          tools: mine.map((t) => this.meta.get(t.name)!.bare),
        };
      });
  }

  /** The tool a reference points to: the full name, `server/tool`, or the tool's own name when only one has it. */
  findTool(ref: string): Tool | undefined {
    const r = ref.trim();
    const exact = this.tools.find((t) => t.name === r);
    if (exact) return exact;
    const lower = r.toLowerCase();
    const whole = this.tools.filter((t) => this.meta.get(t.name)!.bare.toLowerCase() === lower || t.name.toLowerCase() === lower);
    if (whole.length > 0) return whole.length === 1 ? whole[0] : undefined;
    // `server/tool` or `server:tool`: the first separator ends the server's name (the tool's own name may have one too).
    const cut = lower.search(/[/:]/);
    if (cut < 0) return undefined;
    const server = lower.slice(0, cut);
    const bare = lower.slice(cut + 1);
    const hits = this.tools.filter((t) => {
      const m = this.meta.get(t.name)!;
      return m.server.toLowerCase() === server && m.bare.toLowerCase() === bare;
    });
    return hits.length === 1 ? hits[0] : undefined;
  }

  /** Puts tools into the agent's requests (from its next step). Tools of servers that are not lazy are there anyway. */
  load(tools: Tool[]): void {
    for (const t of tools) if (this.meta.has(t.name)) this.loaded.add(t.name);
  }

  isLoaded(tool: Tool): boolean {
    const server = this.meta.get(tool.name)?.server ?? '';
    return !this.lazyServers().has(server) || this.loaded.has(tool.name);
  }

  /** Ranks the tools of the connected servers for a few keywords (best first). Only tools that match at all. */
  search(query: string, server?: string): Tool[] {
    const stem = (w: string) => (w.length > 3 ? w.replace(/(es|s)$/, '') : w);
    const words = query.toLowerCase().split(/[^\p{L}\p{N}]+/u).filter(Boolean).map(stem);
    if (words.length === 0) return [];
    const scored: Array<{ tool: Tool; score: number; order: number }> = [];
    this.tools.forEach((tool, order) => {
      const m = this.meta.get(tool.name)!;
      if (server && m.server.toLowerCase() !== server.toLowerCase()) return;
      const name = m.bare.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ');
      const nameWords = name.split(' ').map(stem);
      const desc = tool.definition.description.replace(/^\[MCP: [^\]]*\]\s*/, '').toLowerCase();
      const srv = m.server.toLowerCase();
      let score = 0;
      let hits = 0;
      for (const w of words) {
        let hit = 0;
        if (nameWords.includes(w)) hit = 4;
        else if (name.includes(w)) hit = 3;
        if (desc.includes(w)) hit += 1;
        if (srv.includes(w)) hit += 1;
        if (hit > 0) hits++;
        score += hit;
      }
      if (score > 0) scored.push({ tool, score: score + (hits === words.length ? 3 : 0), order });
    });
    return scored.sort((a, b) => b.score - a.score || a.order - b.order).map((x) => x.tool);
  }

  /**
   * The agent called a tool that exists but was never loaded (it knew the name from the list, but has
   * not seen the schema). It is loaded now and the agent is told to call it again with the right arguments.
   */
  loadOnMiss(name: string): string | undefined {
    const tool = this.tools.find((t) => t.name === name);
    if (!tool || this.isLoaded(tool)) return undefined;
    this.load([tool]);
    return `Tool '${name}' was not loaded, so its arguments were unknown. It is loaded now: call it again.\n${describeTool(tool)}`;
  }

  /** A new chat starts from nothing: what was loaded in the old one is not carried over. */
  resetLoaded(): void {
    this.loaded.clear();
  }

  /** A resumed chat keeps working with the MCP tools it used before. */
  restoreLoaded(messages: Message[]): void {
    for (const m of messages) {
      for (const call of m.tool_calls ?? []) {
        const tool = this.tools.find((t) => t.name === call.name);
        if (tool) this.loaded.add(tool.name);
      }
    }
  }

  getStatuses(): MCPServerStatus[] {
    return Array.from(this.serverStatuses.values()).map((s) => this.withLoad(s));
  }

  getStatus(name: string): MCPServerStatus | undefined {
    const s = this.serverStatuses.get(name);
    return s ? this.withLoad(s) : undefined;
  }

  private withLoad(s: MCPServerStatus): MCPServerStatus {
    if (s.status !== 'connected') return s;
    const entry = this.catalog().find((c) => c.name === s.name);
    return entry ? { ...s, lazy: entry.lazy, loadedCount: entry.loaded, schemaTokens: this.weights.get(s.name) } : s;
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
    this.meta.clear();
    this.weights.clear();
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
      const load = s.lazy ? `${s.toolsCount} tools · ${s.loadedCount ?? 0} loaded` : `${s.toolsCount} tools`;
      lines.push(padRow(pc.dim(`  Command: ${s.command.slice(0, 36)}${s.command.length > 36 ? '...' : ''}`), pc.cyan(load)));

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
