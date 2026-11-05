import { Command } from 'commander';
import * as p from '@clack/prompts';
import { search, password, input, select } from '@inquirer/prompts';
import pc from 'picocolors';
import os from 'os';
import path from 'path';
import fs from 'fs/promises';
import fsSync from 'fs';
import { execSync, execFileSync } from 'child_process';
import { AgentConfig, DEVX_VERSION, Message } from '../core/types.js';
import { History } from '../core/history.js';
import { Agent } from '../core/loop.js';
import { buildSystemPrompt } from '../prompts/builder.js';
import { createProvider } from '../providers/index.js';
import { getTools, lastPlanReady, resetPlanReady } from '../tools/index.js';
import { CLIConsoleGuard } from '../permissions/guard.js';
import { globalSnapshotManager } from '../core/snapshot.js';
import { MemoryManager } from '../core/memory.js';
import { startServer, stopServer, displayServerBanner } from './server.js';
import { notifyDevice, acquireWakeLock } from '../core/notify.js';
import { runDoctor } from './doctor.js';

import { ALL_PROVIDERS } from './providers.js';
import { getModelContextInfo, getModelContextLimit, LimitSource } from '../core/models.js';
import { SessionManager } from '../core/session.js';
import { MarkdownStreamer, renderMarkdown } from './markdown.js';
import { SmoothStreamer } from './smooth.js';
import { askPrompt } from './prompt.js';
import { resolveAtMentions } from './files.js';
import { runStartupUpdateCheck, checkForUpdates, performSelfUpdate } from './updater.js';
import { getTheme, setActiveTheme, getCurrentTheme, listThemes, findTheme, renderSwatch, renderThemePreview, THEMES, ThemeDef } from './theme.js';
import { runHeadlessMode } from './headless.js';
import { CustomCommandManager } from '../core/commands.js';
import { UsageTracker } from '../core/usage.js';
import { CostLedger, CostState } from '../core/cost.js';
import { SessionExporter } from './export.js';
import { MCPManager, discoverProjectMcpServers, hashProjectMcpServers } from '../mcp/manager.js';
import { writeSecureFile } from '../core/secure-file.js';
import { applyLocalConfig } from '../core/local-config.js';
import { LiveTelemetryTracker } from './telemetry.js';
import { renderCleanStatus, renderPowerlineStatus, GitStatusCache } from './statusline.js';
import { formatDiffBox } from './diff.js';
import { drawBanner, BannerMode } from './banner.js';
import { getModeIndicator, formatPromptHeader } from './modeborder.js';
import { reportRuntimeError } from './runtime_errors.js';
import {
  GutterWriter,
  HeaderInfo,
  UiSpinner,
  formatTokens as formatTokensShort,
  palette,
  pickTip,
  renderHeader,
  renderNotice,
  renderResultBlock,
  renderResultLine,
  renderToolLine,
  renderTurnSummary,
  registerPauseHook,
  easeToward,
  renderStatusLine,
  renderUserEcho,
  resolveMode,
  summarizeToolResult,
} from './ui.js';
import { SettingsState, runSettings } from './settings.js';
import { runPicker } from './picker.js';
import { chooseEffort, effortCommand, Say } from './effort.js';
import { effectiveReasoning, parseEffortArg, detectRoute, thinkLabel } from '../core/reasoning.js';
import { catalogInfo, installCapabilities, refreshCapabilities, refreshCatalog } from '../core/capabilities.js';
import { modelAcceptsImages } from '../core/vision.js';
import { contextBreakdown } from '../core/context-stats.js';
import { runUsagePanel } from './usage-panel.js';
import { TurnComposer } from './typeahead.js';
import { anthropicOnlyModels, formatFromBaseUrl, refreshFormats } from '../core/formats.js';
import { providerFormat } from '../providers/index.js';

const CONFIG_PATH = path.join(os.homedir(), '.devxrc.json');

interface ModelItem {
  id: string;
  name?: string;
  contextLength?: number;
  /** Reachable only through the Anthropic format of the same gateway. */
  viaAnthropic?: boolean;
}

interface FetchModelsResult {
  ok: boolean;
  status: number;
  models: ModelItem[];
  error?: string;
}

function maskApiKey(key: string): string {
  if (!key) return '';
  if (key.length <= 8) return '****';
  return `${key.slice(0, 4)}...${key.slice(-4)}`;
}

async function saveConfig(config: any) {
  config.apiKeys = config.apiKeys || {};
  config.baseUrls = config.baseUrls || {};
  if (config.provider && config.apiKey) {
    config.apiKeys[config.provider] = config.apiKey;
  }
  if (config.provider && config.baseUrl) {
    config.baseUrls[config.provider] = config.baseUrl;
  }
  // Runtime-only flags must not end up in the user's config file.
  const { projectMcpTrusted: _projectMcpTrusted, effortOverride: _effortOverride, ...persisted } = config;
  // The file holds API keys: owner-only permissions (0600).
  await writeSecureFile(CONFIG_PATH, JSON.stringify(persisted, null, 2));
}

async function fetchModels(baseUrl: string, apiKey: string): Promise<FetchModelsResult> {
  try {
    const url = baseUrl.endsWith('/') ? `${baseUrl}models` : `${baseUrl}/models`;
    const headers: any = {};
    if (formatFromBaseUrl(baseUrl) === 'anthropic') {
      // Anthropic's own API wants x-api-key and a version, not a Bearer token.
      headers['anthropic-version'] = '2023-06-01';
      if (apiKey) headers['x-api-key'] = apiKey;
    } else if (apiKey && apiKey !== 'ollama') {
      headers['Authorization'] = `Bearer ${apiKey}`;
    }
    const res = await fetch(url, { headers });
    if (!res.ok) {
      if (res.status === 401 || res.status === 403) {
        return { ok: false, status: res.status, models: [], error: 'Invalid API Key / Unauthorized' };
      }
      return { ok: false, status: res.status, models: [], error: `HTTP ${res.status} ${res.statusText}` };
    }
    const data = await res.json();
    if (data && data.data && Array.isArray(data.data)) {
      const list: ModelItem[] = data.data.map((m: any) => ({
        id: m.id,
        name: m.name || m.display_name || m.id,
        contextLength: m.context_length || m.max_context_length || m.limit?.context || m.limit?.input
      }));
      return { ok: true, status: res.status, models: list };
    }
    return { ok: true, status: res.status, models: [] };
  } catch (e: any) {
    return { ok: false, status: 0, models: [], error: e.message || 'Network error' };
  }
}

/** Asks the endpoint what the model can do and which wire formats it speaks (cached for a day). */
function refreshEndpoint(config: any, options?: { force?: boolean }) {
  return Promise.all([refreshCapabilities(config, options), refreshFormats(config, options)]).then(([record]) => record);
}

/** `"probeEndpoints": false` in the config: devx asks nothing but the plain model list. */
let probeEndpointsAllowed = true;

const POPULAR_PROVIDER_MODELS: Record<string, string[]> = {
  google: ['gemini-2.5-flash', 'gemini-2.5-pro', 'gemini-1.5-flash', 'gemini-1.5-pro'],
  openai: ['gpt-4o', 'gpt-4o-mini', 'o3-mini', 'o1', 'gpt-4-turbo'],
  openrouter: ['google/gemini-2.5-flash', 'anthropic/claude-3.7-sonnet', 'openai/gpt-4o', 'deepseek/deepseek-r1', 'meta-llama/llama-3.3-70b-instruct'],
  anthropic: ['claude-3-7-sonnet-20250219', 'claude-3-5-sonnet-20241022', 'claude-3-5-haiku-20241022'],
  deepseek: ['deepseek-chat', 'deepseek-reasoner'],
  groq: ['llama-3.3-70b-versatile', 'llama-3.1-8b-instant', 'mixtral-8x7b-32768'],
  mistral: ['mistral-large-latest', 'mistral-small-latest', 'codestral-latest'],
};

async function selectModel(baseUrl: string, apiKey: string, currentModel: string, providerId: string = '', allowCancel = true): Promise<{ model: string; contextLimit: number } | null> {
  const s = p.spinner();
  s.start('Fetching available models from provider...');
  const result = await fetchModels(baseUrl, apiKey);
  // The same gateway may list more models (Claude) in its Anthropic format, with the same key.
  if (result.ok && probeEndpointsAllowed) {
    await refreshFormats({ baseUrl, apiKey }, { force: true }).catch(() => null);
    const known = new Set(result.models.map(m => m.id.toLowerCase()));
    for (const id of anthropicOnlyModels(baseUrl)) {
      if (!known.has(id.toLowerCase())) result.models.push({ id, name: id, viaAnthropic: true, contextLength: getModelContextLimit(id) });
    }
  }
  s.stop();

  let models = result.models;
  if (models.length === 0 && providerId && POPULAR_PROVIDER_MODELS[providerId]) {
    models = POPULAR_PROVIDER_MODELS[providerId].map(id => ({
      id,
      name: id,
      contextLength: getModelContextLimit(id)
    }));
  }

  let modelChoice: string;

  if (models.length > 0) {
    try {
      modelChoice = await search({
        message: `Select a model (${models.length} available, type to filter):`,
        source: async (term) => {
          const q = (term || '').trim().toLowerCase();
          const list: { name: string; value: string; description?: string }[] = [];
          if (allowCancel) {
            list.push({ name: pc.yellow('← Back / Cancel'), value: '__cancel__', description: 'Keep current model' });
          }
          list.push({ name: pc.yellow('+ Type a custom model name...'), value: 'custom_input', description: 'Enter any model ID manually' });
          for (const m of models) {
            const isCurrent = m.id === currentModel;
            const prefix = isCurrent ? pc.cyan('› ') : '  ';
            const nameStr = isCurrent ? pc.bold(pc.cyan(m.id + ' (current)')) : m.id;
            const ctxStr = (m.viaAnthropic ? 'Anthropic format' + (m.contextLength ? ', ' : '') : '') + (m.contextLength ? `${m.contextLength >= 1_000_000 ? (m.contextLength / 1_000_000).toFixed(1) + 'M' : Math.round(m.contextLength / 1000) + 'k'} context` : '');
            list.push({
              name: prefix + nameStr,
              value: m.id,
              description: ctxStr
            });
          }
          if (!q) return list;
          return list.filter(item => item.value.toLowerCase().includes(q) || item.name.toLowerCase().includes(q));
        },
        pageSize: 10
      });
    } catch {
      return null;
    }

    if (modelChoice === '__cancel__') {
      return null;
    }

    if (modelChoice === 'custom_input') {
      try {
        const customModel = await input({
          message: 'Type the model name (e.g. gpt-4o or claude-3-7-sonnet):',
        });
        if (!customModel || !customModel.trim()) return null;
        const name = customModel.trim();
        return { model: name, contextLimit: getModelContextLimit(name) };
      } catch {
        return null;
      }
    }

    const found = models.find(m => m.id === modelChoice);
    const limit = (found && found.contextLength) || getModelContextLimit(modelChoice);
    return { model: modelChoice, contextLimit: limit };
  } else {
    p.log.warn('Could not fetch models automatically.');
    try {
      const typedModel = await input({
        message: 'Type the model name you want to use (e.g. gpt-4o):',
        default: currentModel || ''
      });
      if (!typedModel || !typedModel.trim()) return null;
      const name = typedModel.trim();
      return { model: name, contextLimit: getModelContextLimit(name) };
    } catch {
      return null;
    }
  }
}

async function selectProvider(currentConfig: any, allowCancel = true): Promise<any | null> {
  const apiKeys: Record<string, string> = currentConfig.apiKeys || {};
  const baseUrls: Record<string, string> = currentConfig.baseUrls || {};
  if (currentConfig.provider && currentConfig.apiKey) {
    apiKeys[currentConfig.provider] = currentConfig.apiKey;
  }

  let providerId: string;
  try {
    providerId = await search({
      message: 'Select AI Provider (type to filter, Esc to cancel):',
      source: async (term) => {
        const q = (term || '').trim().toLowerCase();
        const filtered = q
          ? ALL_PROVIDERS.filter(pr => pr.label.toLowerCase().includes(q) || pr.value.toLowerCase().includes(q))
          : ALL_PROVIDERS;
        const list = filtered.map(pr => {
          const isCurrent = pr.value === currentConfig.provider;
          const labelStr = isCurrent ? pc.bold(pc.cyan(pr.label + ' (current)')) : pr.label;
          const hasKey = apiKeys[pr.value] ? `[Key: ${maskApiKey(apiKeys[pr.value])}] ` : '';
          return {
            name: `${isCurrent ? pc.cyan('› ') : '  '}${labelStr}`,
            value: pr.value,
            description: `${hasKey}${pr.baseUrl || 'Custom endpoint'}`
          };
        });
        if (allowCancel) {
          list.unshift({
            name: pc.yellow('← Back / Cancel'),
            value: '__cancel__',
            description: 'Keep current provider'
          });
        }
        return list;
      },
      pageSize: 10
    });
  } catch {
    return null;
  }

  if (providerId === '__cancel__') {
    return null;
  }

  const selectedProvider = ALL_PROVIDERS.find(pr => pr.value === providerId) || { baseUrl: '', label: providerId };
  let baseUrl = baseUrls[providerId] || selectedProvider.baseUrl;

  if (providerId === 'custom' || !baseUrl) {
    try {
      const customUrl = await input({
        message: 'Enter Base URL (e.g. https://api.openai.com/v1):',
        default: baseUrl || ''
      });
      if (!customUrl || !customUrl.trim()) return null;
      baseUrl = customUrl.trim();
      baseUrls[providerId] = baseUrl;
    } catch {
      return null;
    }
  }

  let apiKey = '';
  if (providerId === 'ollama') {
    apiKey = 'ollama';
  } else {
    const savedKey = apiKeys[providerId];
    if (savedKey) {
      let keyChoice: string;
      try {
        keyChoice = await search({
          message: `API key found for ${selectedProvider.label} (${maskApiKey(savedKey)}):`,
          source: async () => [
            { name: `● Continue with saved API key (${maskApiKey(savedKey)})`, value: 'use_saved' },
            { name: '+ Enter a new API key', value: 'new_key' },
            { name: pc.yellow('← Cancel'), value: '__cancel__' }
          ],
          pageSize: 5
        });
      } catch {
        return null;
      }

      if (keyChoice === '__cancel__') return null;

      if (keyChoice === 'use_saved') {
        apiKey = savedKey;
      } else {
        try {
          const key = await password({
            message: `Enter new API Key for ${selectedProvider.label}:`,
            mask: '•'
          });
          if (!key || !key.trim()) return null;
          apiKey = key.trim();
          apiKeys[providerId] = apiKey;
        } catch {
          return null;
        }
      }
    } else {
      try {
        const key = await password({
          message: `Enter API Key for ${selectedProvider.label} (or press Esc to cancel):`,
          mask: '•'
        });
        if (!key || !key.trim()) return null;
        apiKey = key.trim();
        apiKeys[providerId] = apiKey;
      } catch {
        return null;
      }
    }
  }

  // Pick a model for this provider
  const modelRes = await selectModel(baseUrl, apiKey, currentConfig.model || '', providerId, allowCancel);
  if (!modelRes) {
    return null;
  }

  const updatedConfig = {
    ...currentConfig,
    provider: providerId,
    baseUrl,
    apiKey,
    model: modelRes.model,
    maxContextTokens: modelRes.contextLimit,
    maxIterations: currentConfig.maxIterations || 30,
    apiKeys,
    baseUrls
  };

  await saveConfig(updatedConfig);
  return updatedConfig;
}

async function runOnboarding(): Promise<any> {
  console.clear();
  p.intro(pc.bgCyan(pc.black(' Welcome to devx ')));
  p.note('No configuration found. Let\'s set up your AI provider.', 'Setup');
  const res = await selectProvider({ apiKeys: {}, baseUrls: {} }, false);
  if (!res) {
    process.exit(0);
  }
  p.outro(pc.green('Setup complete! Configuration saved to ~/.devxrc.json'));
  return res;
}

type ContextSource = LimitSource | 'config' | 'saved';

/**
 * The context window to use for the model of this config: a number the user wrote into
 * `contextLimits`, else the catalogue's (the maker's own number), else what was saved when the
 * model was chosen, else devx's table, rules and default.
 */
function resolveContextLimit(config: any): { limit: number; source: ContextSource } {
  const own = config.contextLimits?.[config.model];
  if (typeof own === 'number' && own >= 1024) return { limit: own, source: 'config' };
  const info = getModelContextInfo(config.model);
  if (info.source === 'catalogue') return info;
  if (typeof config.maxContextTokens === 'number' && config.maxContextTokens > 0) {
    return config.maxContextTokens === info.limit ? info : { limit: config.maxContextTokens, source: 'saved' };
  }
  return info;
}


async function loadConfig(interactive: boolean = true): Promise<any> {
  let globalConfig: any = {};
  try {
    const data = await fs.readFile(CONFIG_PATH, 'utf8');
    globalConfig = JSON.parse(data);
    if (!globalConfig.model || !globalConfig.baseUrl) throw new Error('Invalid config');
    globalConfig.maxContextTokens = resolveContextLimit(globalConfig).limit;
    globalConfig.apiKeys = globalConfig.apiKeys || {};
    globalConfig.baseUrls = globalConfig.baseUrls || {};
    globalConfig.trustedProjects = globalConfig.trustedProjects || {};
    if (globalConfig.provider && globalConfig.apiKey) {
      globalConfig.apiKeys[globalConfig.provider] = globalConfig.apiKey;
    }
  } catch {
    if (interactive) {
      globalConfig = await runOnboarding();
    } else {
      throw new Error('No configuration found in ~/.devxrc.json. Please run devx interactively first to set up your AI provider.');
    }
  }

  // Check for local project config (.devx.json or .devxrc.json)
  const localConfigPaths = [
    path.join(process.cwd(), '.devx.json'),
    path.join(process.cwd(), '.devxrc.json')
  ];

  let localConfig: any = null;
  for (const lp of localConfigPaths) {
    try {
      if (fsSync.existsSync(lp)) {
        const raw = await fs.readFile(lp, 'utf8');
        localConfig = JSON.parse(raw);
        break;
      }
    } catch {}
  }

  const effectiveConfig = { ...globalConfig };

  const cwd = process.cwd();
  let isTrusted = globalConfig.trustedProjects?.[cwd];

  // Workspace & Project Trust Check on first time opening this directory
  if (isTrusted === undefined) {
    if (interactive) {
      console.log('');
      p.log.warn(pc.bold(pc.yellow(`[WORKSPACE TRUST] First time opening this workspace:`)));
      console.log(pc.dim(`   Directory: ${cwd}`));
      if (localConfig) {
        if (localConfig.autoApprove === true) {
          console.log(pc.yellow(`   • Local .devx.json requests Auto-approval (YOLO mode)`));
        }
        if (localConfig.bashAllowlist) {
          console.log(pc.yellow(`   • Local .devx.json requests Bash allowlist: ${localConfig.bashAllowlist.join(', ')}`));
        }
      }

      const answer = await p.confirm({
        message: 'Do you trust this workspace and allow agent operations?',
        initialValue: false
      });

      if (p.isCancel(answer) || answer !== true) {
        resetTerminalTheme();
        p.outro(pc.yellow('Workspace not trusted. Exiting devx.'));
        process.exit(0);
      }

      globalConfig.trustedProjects = globalConfig.trustedProjects || {};
      globalConfig.trustedProjects[cwd] = true;
      await saveConfig(globalConfig);
      isTrusted = true;
      p.log.success(pc.green('Workspace marked as trusted. Full capabilities enabled.'));
    } else {
      isTrusted = false; // Never auto-trust in non-interactive headless mode
    }
  }

  // Provider / API key / permission settings from the repository only count in a trusted workspace.
  const ignoredLocalSettings = applyLocalConfig(effectiveConfig, localConfig, isTrusted === true);
  if (ignoredLocalSettings.length > 0) {
    console.error(pc.yellow(`Ignored ${ignoredLocalSettings.join(', ')} from the local project config: this workspace is not trusted.`));
  }

  // MCP servers declared by the project are arbitrary commands. They run only if the
  // user approved exactly this set of commands (re-asked whenever the files change).
  effectiveConfig.projectMcpTrusted = false;
  const projectMcpServers = discoverProjectMcpServers(cwd);
  if (projectMcpServers.length > 0) {
    const fingerprint = hashProjectMcpServers(projectMcpServers);
    globalConfig.trustedMcp = globalConfig.trustedMcp || {};
    if (globalConfig.trustedMcp[cwd] === fingerprint) {
      effectiveConfig.projectMcpTrusted = true;
    } else if (interactive) {
      console.log('');
      p.log.warn(pc.bold(pc.yellow('[MCP] This project wants to start the following MCP servers (they run as local programs):')));
      for (const s of projectMcpServers) {
        const cmdline = `${s.config.command} ${(s.config.args || []).join(' ')}`.trim();
        console.log(`   ${pc.cyan(s.name)} ${pc.dim(`(${s.file})`)}  ${pc.green(cmdline)}`);
      }
      const allow = await p.confirm({ message: 'Allow these MCP servers to start?', initialValue: false });
      if (allow === true) {
        globalConfig.trustedMcp[cwd] = fingerprint;
        await saveConfig(globalConfig);
        effectiveConfig.projectMcpTrusted = true;
      } else {
        p.log.info(pc.dim('Project MCP servers skipped. Servers from ~/.devxrc.json still start.'));
      }
    } else {
      console.error(pc.yellow(`Skipped ${projectMcpServers.length} project MCP server(s): not approved yet. Run devx interactively in this folder once to review and approve them.`));
    }
  }

  // Configure Data Saver threshold
  if (effectiveConfig.dataSaverLimitMB) {
    UsageTracker.getInstance().setLimit(effectiveConfig.dataSaverLimitMB);
  }

  return effectiveConfig;
}

function enableDarkTheme(enabled = true) {
  if (!enabled) return;
  if (process.stdout.isTTY) {
    // Deep OLED / Obsidian Black background (#0a0a0c) and bright crisp foreground (#f0f6fc)
    process.stdout.write('\x1b]11;#0a0a0c\x07');
    process.stdout.write('\x1b]10;#f0f6fc\x07');
  }
}

function resetTerminalTheme() {
  if (process.stdout.isTTY) {
    process.stdout.write('\x1b]111\x07');
    process.stdout.write('\x1b]110\x07');
  }
}

let activeAbortHandler: (() => void) | null = null;

process.on('exit', () => {
  if (process.stdin.isTTY) {
    process.stdin.setRawMode(false);
  }
  if (process.stdout.isTTY) process.stdout.write('\x1B[?25h');
  resetTerminalTheme();
});

process.on('SIGINT', () => {
  if (activeAbortHandler) {
    activeAbortHandler();
  } else {
    if (process.stdin.isTTY) {
      process.stdin.setRawMode(false);
      process.stdin.pause();
    }
    process.stdout.write('\x1B[?25h'); // restore cursor
    resetTerminalTheme();
    process.exit(0);
  }
});

function clearTerminalScreen() {
  if (process.stdout.isTTY) {
    // \x1b[2J: clear screen, \x1b[3J: clear scrollback buffer (crucial for Termux/xterm), \x1b[H: cursor to home
    process.stdout.write('\x1b[2J\x1b[3J\x1b[H');
  } else {
    console.clear();
  }
}

/** The model name and its reasoning setting: "o3 · think high", "mimo-v2.6-flash · think auto". */
function modelLabel(config: any): string {
  const think = thinkLabel(config);
  return think ? `${config.model} · ${think}` : config.model;
}

// Registered by main() once the config exists; lets every screen redraw show the live
// model / mode / folder in the header card.
let headerProvider: (() => HeaderInfo) | null = null;

function drawLogo(bannerMode: BannerMode = 'full') {
  const cols = process.stdout.columns || 80;
  clearTerminalScreen();
  if (bannerMode === 'clean' && headerProvider) {
    console.log();
    for (const line of renderHeader(headerProvider(), cols)) console.log(line);
    console.log();
    return;
  }
  drawBanner(bannerMode, DEVX_VERSION, cols);
}

function formatSessionTime(timestamp: number): string {
  const diff = Date.now() - timestamp;
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return `${days}d ago`;
}

function formatToolGeneratingLabel(name: string, targetHint?: string): string {
  const t = name.toLowerCase();
  const target = targetHint ? ` ${targetHint}` : '';
  if (t === 'read_file' || t === 'read') return `→ Read${target || '...'}`;
  if (t === 'write_file' || t === 'write') return `→ Write${target || '...'}`;
  if (t === 'edit_file' || t === 'patch') return `→ Edit${target || '...'}`;
  if (t === 'delete_file' || t === 'remove_file' || t === 'rm') return `→ Delete${target || '...'}`;
  if (t === 'bash' || t === 'run_command' || t === 'exec') return `→ Run:${target || '...'}`;
  if (t === 'search' || t === 'grep_search') return `→ Search${target || '...'}`;
  if (t === 'list_dir' || t === 'ls') return `→ List${target || '...'}`;
  if (t === 'todo_list' || t === 'update_todos') return `→ Update Plan & Tasks...`;
  if (t === 'plan_ready') return `→ Finalize Plan...`;
  if (t === 'diagnose_code') return `→ Diagnosing code...`;
  if (t === 'ask_questions') return `→ Clarifying questions...`;
  return `→ ${name}${target}...`;
}

async function handleSessionDelete(bannerMode: BannerMode) {
  while (true) {
    const sessions = await SessionManager.listSessions();
    if (sessions.length === 0) {
      drawLogo(bannerMode);
      p.log.warn('No saved sessions found.');
      break;
    }

    let chosenSessionId: string;
    try {
      chosenSessionId = await search({
        message: `Select session to DELETE (${sessions.length} sessions, Esc to finish):`,
        source: async (term) => {
          const q = (term || '').trim().toLowerCase();
          const list: { name: string; value: string; description?: string }[] = [
            { name: pc.yellow('← Done / Cancel'), value: '__cancel__', description: 'Return to chat' },
            { name: pc.red('× Delete ALL sessions'), value: '__all__', description: `Remove all ${sessions.length} saved sessions` }
          ];
          for (const s of sessions) {
            const timeStr = formatSessionTime(s.updatedAt);
            const msgCount = s.messages.filter(m => m.role !== 'system').length;
            const shortId = s.id.split('_').pop();
            list.push({
              name: `${pc.red('× ')} ${pc.dim(`[#${shortId}]`)} ${s.title}`,
              value: s.id,
              description: `${timeStr} • ${msgCount} msgs • ${s.model || 'default'}`
            });
          }
          if (!q) return list;
          return list.filter(item => item.name.toLowerCase().includes(q) || item.value.toLowerCase().includes(q));
        },
        pageSize: 10
      });
    } catch {
      break;
    }

    if (!chosenSessionId || chosenSessionId === '__cancel__') {
      drawLogo(bannerMode);
      p.log.info('Session deletion finished.');
      break;
    }

    if (chosenSessionId === '__all__') {
      const confirmAll = await p.confirm({
        message: `Are you sure you want to delete ALL ${sessions.length} saved sessions?`,
        initialValue: false
      });
      if (!p.isCancel(confirmAll) && confirmAll) {
        const count = await SessionManager.deleteAllSessions();
        drawLogo(bannerMode);
        p.log.success(`Deleted all ${count} sessions.`);
        break;
      }
      continue;
    }

    const target = sessions.find(s => s.id === chosenSessionId);
    const title = target ? target.title : chosenSessionId;
    const ok = await SessionManager.deleteSession(chosenSessionId);
    drawLogo(bannerMode);
    if (ok) {
      p.log.success(`Deleted session: "${title}"`);
    } else {
      p.log.error(`Failed to delete session: "${title}"`);
    }
  }
}

async function handleThemeSelect(config: any): Promise<void> {
  const currentTh = getCurrentTheme();
  const themeChoices = listThemes().map(t => ({
    theme: t,
    choice: {
      name: `${renderSwatch(t)} ${t.boldFn(t.name.padEnd(18))} ${pc.dim(t.desc)} ${t.id === currentTh.id ? pc.green('(Active)') : ''}`,
      value: t.id,
      description: `${renderThemePreview(t)}\n\n${pc.dim(`${t.name}  ${t.hex}`)}`
    }
  }));

  try {
    const selected = await search({
      message: `${pc.bold('Select UI Theme / Выберите цветовую тему')} ${pc.dim(`(${themeChoices.length} themes, type to filter):`)}`,
      pageSize: 8,
      source: async (term) => {
        const q = (term || '').toLowerCase().trim();
        return themeChoices
          .filter(({ theme: t }) => !q || `${t.id} ${t.name} ${t.desc}`.toLowerCase().includes(q))
          .map(({ choice }) => choice);
      }
    });

    if (selected) {
      config.theme = selected;
      await saveConfig(config);
      const th = setActiveTheme(selected);
      // No redraw of the screen: that would wipe the conversation. New output uses the new colours.
      if (config.ui?.layout === 'classic') p.log.success(th.boldFn(`Theme switched to ${th.colorFn(th.symbol)} ${th.name}!`));
      else console.log(renderNotice('ok', `theme ${th.name}`));
    }
  } catch {}
}

async function handleSettings(config: any): Promise<any> {
  const initial: SettingsState = {
    themeId: getCurrentTheme().id,
    layout: config.ui?.layout === 'classic' ? 'classic' : 'clean',
    pureBlack: config.pureBlackTheme !== false,
    autoApprove: !!config.autoApprove,
    maxIterations: config.maxIterations || 100,
    memory: config.enableMemory !== false,
    updates: config.checkUpdates !== false,
  };

  // One panel, redrawn in place; every change is visible at once and saved when it closes.
  const final = await runSettings(initial, {
    version: DEVX_VERSION,
    onChange: (key, state) => {
      if (key === 'theme') setActiveTheme(state.themeId);
      if (key === 'pureBlack') {
        if (state.pureBlack) enableDarkTheme(true);
        else resetTerminalTheme();
      }
    },
  });

  config.theme = final.themeId;
  config.ui = config.ui || {};
  config.ui.layout = final.layout;
  config.banner = final.layout === 'clean' ? (config.banner === 'off' ? 'off' : 'clean') : 'full';
  config.pureBlackTheme = final.pureBlack;
  config.autoApprove = final.autoApprove;
  config.maxIterations = final.maxIterations;
  config.enableMemory = final.memory;
  config.checkUpdates = final.updates;
  await saveConfig(config);

  process.stdin.resume();
  return config;
}

export async function main() {
  // === Termux / Android Stability: Global Signal Handlers ===
  // Ignore SIGHUP so the process survives when Termux is minimized
  if (process.platform !== 'win32') {
    process.on('SIGHUP', () => {
      // Termux minimized or window lost focus — keep process alive
    });
  }

  // Graceful shutdown on SIGTERM (closing Termux tab / kill command)
  process.on('SIGTERM', () => {
    try {
      resetTerminalTheme();
      MCPManager.getInstance().stopAll();
    } catch {}
    process.exit(0);
  });

  // Protect against crash on background I/O errors (EIO/EPIPE/ERR_STREAM_DESTROYED)
  process.on('uncaughtException', (err: any) => {
    const isIgnorableIo = err.code === 'EIO' || err.code === 'EPIPE' || err.code === 'ERR_STREAM_DESTROYED';
    if (isIgnorableIo) return;
    reportRuntimeError(err, activeAbortHandler);
  });

  process.on('unhandledRejection', (reason: any) => {
    reportRuntimeError(reason, activeAbortHandler);
  });

  acquireWakeLock();

  const program = new Command();
  program
    .name('devx')
    .description('Terminal-native AI coding assistant and vibe-coding agent')
    .version(DEVX_VERSION)
    .option('-p, --prompt <task>', 'Run one-shot task non-interactively (headless mode)')
    .option('-y, --yolo', 'Automatically approve all tool executions without confirmation')
    .option('-m, --model <model>', 'Specify AI model to use for this execution')
    .option('-q, --quiet', 'Quiet output in headless mode (suppress banners and tool logs)')
    .option('--json', 'Output structured JSON in headless mode')
    .option('--plan', 'Start in plan mode (architect & planner)')
    .option('-e, --effort <level>', 'Reasoning effort for this run: off, low, normal, high, xhigh, max, or auto')
    .parse(process.argv);

  const options = program.opts();
  const planModeInitial = !!options.plan;
  const isHeadless = !!options.prompt;

  let config = await loadConfig(!isHeadless);
  // What each model offers for reasoning: the stored answers of the endpoint and the catalogue.
  installCapabilities();
  probeEndpointsAllowed = config.probeEndpoints !== false;
  config.banner = config.ui?.layout === 'classic'
    ? config.banner || 'full'
    : config.banner === 'off' ? 'off' : 'clean';

  if (options.model) {
    config.model = options.model;
    config.maxContextTokens = resolveContextLimit({ ...config, maxContextTokens: 0 }).limit;
  }
  if (options.yolo) {
    config.autoApprove = true;
  }
  if (options.effort !== undefined) {
    // One run only (never saved). Anything the model does not offer is ignored with a warning.
    // A custom endpoint is asked about the model first (cached, a few seconds at most).
    await refreshEndpoint(config).catch(() => null);
    const parsed = parseEffortArg(String(options.effort));
    const { caps } = effectiveReasoning({ ...config, effortOverride: undefined });
    if (parsed.kind === 'invalid') {
      console.error(pc.yellow(`--effort: unknown level "${parsed.text}". Use off, low, normal, high, xhigh, max or auto.`));
    } else if (parsed.kind === 'auto') {
      config.effortOverride = '';
    } else if (!caps.supported) {
      console.error(pc.yellow(`--effort ignored: ${config.model} has no reasoning setting.`));
    } else if (!caps.levels.includes(parsed.level)) {
      console.error(pc.yellow(`--effort ignored: ${config.model} does not offer "${parsed.level}" (it offers ${caps.levels.join(', ')}).`));
    } else {
      config.effortOverride = parsed.level;
    }
  }

  // If -p / --prompt is provided, execute one-shot headless mode and exit!
  if (isHeadless) {
    const abortController = new AbortController();
    activeAbortHandler = () => abortController.abort();
    const exitCode = await runHeadlessMode(options.prompt, config, {
      planMode: planModeInitial,
      yolo: !!options.yolo,
      quiet: !!options.quiet,
      json: !!options.json,
      signal: abortController.signal
    });
    activeAbortHandler = null;
    process.exit(exitCode);
  }

  let planMode = planModeInitial;
  headerProvider = () => {
    const git = GitStatusCache.getStatus(true);
    return {
      version: DEVX_VERSION,
      model: modelLabel(config),
      provider: config.provider,
      cwd: process.cwd(),
      branch: git?.branch,
      dirty: git?.isDirty,
      yolo: !!config.autoApprove,
      tip: pickTip(),
    };
  };

  if (!config.onboarded) {
    const themeChoices = listThemes().map(t => ({
      theme: t,
      choice: {
        name: `${renderSwatch(t)} ${t.boldFn(t.name.padEnd(18))} ${pc.dim(t.desc)}`,
        value: t.id,
        description: `${renderThemePreview(t)}\n\n${pc.dim('You can change this anytime with /theme')}`
      }
    }));

    try {
      clearTerminalScreen();
      const selected = await search({
        message: `${pc.bold('Pick a theme to personalize your workspace:')}`,
        source: async (term) => {
          const q = (term || '').toLowerCase().trim();
          return themeChoices
            .filter(({ theme: t }) => !q || `${t.id} ${t.name} ${t.desc}`.toLowerCase().includes(q))
            .map(({ choice }) => choice);
        },
        pageSize: 8
      });
      if (selected) {
        config.theme = selected;
      }
    } catch {}
    
    config.onboarded = true;
    await saveConfig(config);
  }

  if (config.theme) {
    setActiveTheme(config.theme);
  }
  enableDarkTheme(config.pureBlackTheme !== false);

  let history = new History();
  
  const sysPrompt = await buildSystemPrompt(planMode);
  history.addMessage({ role: 'system', content: sysPrompt });

  const sessionManager = new SessionManager(config.model, planMode);

  drawLogo(config.banner || 'full');
  await runStartupUpdateCheck(config);
  await MCPManager.getInstance().init(undefined, config.projectMcpTrusted === true);
  // Ask a custom endpoint what the current model can do (cached for a day; never blocks the prompt).
  void refreshEndpoint(config).catch(() => null);

  const sessionCost = new CostLedger();
  const currentContext = () =>
    contextBreakdown(history.getMessages(), getTools(planMode).map(t => t.definition), config.maxContextTokens || getModelContextLimit(config.model));
  let currentDraft = '';
  let autoTriggerPrompt = '';

  // /clear and /new: leave the current conversation (it stays saved and can be resumed)
  // and start from a clean slate: history, cost, usage counters, undo stack, screen.
  const startFreshSession = async () => {
    sessionManager.startNewSession(config.model, planMode);
    history = new History();
    history.addMessage({ role: 'system', content: await buildSystemPrompt(planMode) });
    sessionCost.reset();
    currentDraft = '';
    UsageTracker.getInstance().reset();
    globalSnapshotManager.reset();
    GitStatusCache.invalidate();
    drawLogo(config.banner || 'full');
  };
  const announce = (message: string) => {
    if (config.ui?.layout === 'classic') p.log.success(message);
    else console.log(renderNotice('ok', message));
  };

  while (true) {
    const maxTokens = config.maxContextTokens || getModelContextLimit(config.model);
    config.maxContextTokens = maxTokens;
    // The conversation only: the system prompt and tool schemas are fixed overhead that every
    // chat has, so an empty chat shows 0%. /usage shows the full picture.
    const ctxNow = currentContext();
    const currentTokens = ctxNow.total - ctxNow.system - ctxNow.tools;
    const usagePercent = Math.min(100, Math.round((currentTokens / maxTokens) * 100));
    
    const formatTokens = (n: number) => {
      if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
      if (n >= 1000) return `${(n / 1000).toFixed(1)}k`;
      return `${n}`;
    };

    const costStr = `Cost: ${sessionCost.format()}`;
    const tokenStats = `Context: ${formatTokens(currentTokens)} / ${formatTokens(maxTokens)} (${usagePercent}%) • ${costStr}`;

    const cols = process.stdout.columns || 80;
    const modeName = planMode ? 'PLAN' : 'AGENT';
    const theme = getCurrentTheme();

    if (config.ui?.layout !== 'classic') {
      // Clean layout: mode, model, context and cost live in the borders of the prompt frame.
    } else if (config.ui?.powerlineStatus) {
      const statusLine = renderPowerlineStatus({
        mode: modeName as 'PLAN' | 'AGENT',
        model: config.model,
        currentTokens,
        maxTokens,
        cost: sessionCost.total,
        cols,
        maxCostUSD: config.maxCostUSD,
        isYolo: !!config.autoApprove
      });
      p.intro(statusLine);
    } else {
      // Shorten model name if too long on narrow mobile screens
      let displayModel = config.model;
      if (cols < 75 && displayModel.length > 20) {
        const parts = displayModel.split('/');
        displayModel = parts.length > 1 ? parts.slice(1).join('/') : displayModel;
        if (displayModel.length > 20) {
          displayModel = displayModel.slice(0, 17) + '...';
        }
      }

      const badge = theme.badgeFn(`devx | ${modeName} | ${displayModel}`);

      if (cols < 75) {
        // 2-line layout for mobile screens: perfectly aligned with clack box borders
        p.intro(`${badge}\n${pc.dim('│')}  ${pc.dim(tokenStats)}`);
      } else {
        // 1-line layout for wider desktop screens
        p.intro(`${badge}  ${pc.dim(tokenStats)}`);
      }
    }

    let answer = '';

    if (autoTriggerPrompt) {
      answer = autoTriggerPrompt;
      autoTriggerPrompt = '';
      console.log(config.ui?.layout === 'classic'
        ? theme.colorFn('◆') + '  ' + pc.bold(pc.white(answer))
        : renderUserEcho(answer, cols).join('\n'));
    } else {
      const inputStr = await askPrompt({
        message: 'Ask anything...',
        placeholder: config.ui?.layout === 'classic'
          ? 'Fix a TODO, type /help, or press Tab to switch mode'
          : 'Ask or describe a task…',
        initialValue: currentDraft,
        planMode,
        isYolo: !!config.autoApprove,
        ui: config.ui,
        model: modelLabel(config),
        cwd: process.cwd(),
        branch: GitStatusCache.getStatus()?.branch,
        dirty: GitStatusCache.getStatus()?.isDirty,
        contextPercent: usagePercent,
        cost: sessionCost.total,
        costState: sessionCost.state()
      });

      if (inputStr.startsWith('__TOGGLE_MODE__:')) {
        currentDraft = inputStr.slice('__TOGGLE_MODE__:'.length);
        planMode = !planMode;
        const newSys = await buildSystemPrompt(planMode);
        history.updateSystemPrompt(newSys);
        // Clean layout: the prompt frame changes colour and label, no extra line needed.
        if (config.ui?.layout === 'classic') {
          p.log.info(planMode 
            ? pc.bold(pc.cyan('Mode: PLAN (Architect & Planner) — Press Tab to switch to AGENT'))
            : pc.bold(pc.green('Mode: AGENT (Coder & Executor) — Press Tab to switch to PLAN'))
          );
        }
        continue;
      }

      currentDraft = '';

      if (inputStr === '__CANCEL__') {
        const confirmExit = await p.confirm({
          message: 'Are you sure you want to exit devx?',
          initialValue: false
        });
        if (!p.isCancel(confirmExit) && confirmExit === true) {
          resetTerminalTheme();
          p.outro('Goodbye!');
          break;
        } else {
          continue;
        }
      }

      answer = (inputStr as string).trim();
    }

    if (!answer) continue;

    if (answer.startsWith('/')) {
      const parts = answer.split(' ');
      let cmd = parts[0];

      // 1. Check for custom slash commands (.devx/commands/*.md)
      const customCmd = await CustomCommandManager.findCommand(cmd);
      if (customCmd) {
        const cmdArgs = answer.slice(cmd.length).trim();
        const expandedPrompt = CustomCommandManager.expandTemplate(customCmd.promptTemplate, cmdArgs);
        answer = expandedPrompt;
        p.log.info(pc.cyan(`Executing custom command: ${pc.bold(customCmd.cmd)} (${customCmd.desc})`));
      } else {
        const VALID_COMMANDS = [
          '/new', '/reset', '/resume', '/session', '/sessions', '/history',
          '/theme', '/themes', '/usage', '/export', '/mcp',
          '/settings', '/update', '/model', '/provider', '/providers',
          '/plan', '/agent', '/image', '/serve', '/memory', '/undo',
          '/diff', '/commit', '/status', '/compact', '/init', '/doctor',
          '/config', '/clear', '/exit', '/quit', '/help', '/effort'
        ];
        if (!VALID_COMMANDS.includes(cmd)) {
          const customList = await CustomCommandManager.listCommands();
          const customSlashItems = customList.map(c => ({
            name: `${c.cmd.padEnd(14)} - ${c.desc}`,
            value: c.cmd
          }));

          const SLASH_COMMANDS = [
            { name: '/new          - Start a new clean chat session', value: '/new' },
            { name: '/resume       - Resume a previous chat session', value: '/resume' },
            { name: '/session del  - Select and delete saved sessions', value: '/session del' },
            { name: '/usage        - Show network bandwidth, data saver & token cost', value: '/usage' },
            { name: '/export       - Export session conversation to Markdown', value: '/export' },
            { name: '/mcp          - Manage Model Context Protocol (MCP) servers & tools', value: '/mcp' },
            { name: '/theme        - Switch UI color theme', value: '/theme' },
            { name: '/doctor       - Run system & environment health diagnostics', value: '/doctor' },
            { name: '/settings     - Configure permissions & auto-approval', value: '/settings' },
            { name: '/update       - Check and install updates from GitHub', value: '/update' },
            { name: '/model        - Switch model for current provider', value: '/model' },
            { name: '/effort       - Set the reasoning effort for the current model', value: '/effort' },
            { name: '/provider     - Change AI provider (Google, OpenRouter...)', value: '/provider' },
            { name: '/plan         - Switch to PLAN mode (architect)', value: '/plan' },
            { name: '/agent        - Switch to AGENT mode (coder)', value: '/agent' },
            { name: '/serve        - Start local web server for web preview', value: '/serve' },
            { name: '/memory       - View or edit project memory bank', value: '/memory' },
            { name: '/undo         - Revert last file changes made by AI', value: '/undo' },
            { name: '/diff         - Show git diff of modified files', value: '/diff' },
            { name: '/commit       - AI-generated git commit message', value: '/commit' },
            { name: '/status       - Show git repository status', value: '/status' },
            { name: '/compact      - Compact conversation context', value: '/compact' },
            { name: '/config       - View current configuration', value: '/config' },
            { name: '/clear        - Clear context and start a new session', value: '/clear' },
            { name: '/help         - Show commands overview', value: '/help' },
            { name: '/exit         - Exit devx', value: '/exit' },
            ...customSlashItems
          ];

          try {
            const picked = await search({
              message: 'Commands (type to search or select):',
              source: async (term) => {
                const q = (term || '').trim().toLowerCase();
                const list = [
                  { name: pc.yellow('← Cancel'), value: '__cancel__' },
                  ...SLASH_COMMANDS
                ];
                if (!q) return list;
                return list.filter(item => item.name.toLowerCase().includes(q) || item.value.toLowerCase().includes(q));
              },
              pageSize: 12
            });

            if (!picked || picked === '__cancel__') {
              continue;
            }
            if (picked === '/session del') {
              await handleSessionDelete(config.banner || 'full');
              continue;
            }
            cmd = picked;
          } catch {
            continue;
          }
        }
      }

      if (cmd === '/settings') {
        // The panel erases itself; the conversation above it stays where it is.
        config = await handleSettings(config);
        continue;
      }

      if (cmd === '/update') {
        const s = p.spinner();
        s.start('Checking for updates from https://github.com/apvcode/Termux-Dev...');
        const res = await checkForUpdates(10000);
        s.stop();
        if (res.updateAvailable) {
          p.log.info(pc.bold(pc.yellow(`Update available: v${res.currentVersion} → v${res.latestVersion}`)));
          const doUpdate = await p.confirm({
            message: `Do you want to update devx to v${res.latestVersion} now?`,
            initialValue: true
          });
          if (!p.isCancel(doUpdate) && doUpdate) {
            await performSelfUpdate(res.latestVersion);
          }
        } else {
          p.log.success(pc.green(`devx is up to date! (v${res.currentVersion})`));
        }
        continue;
      }

      if (cmd === '/help') {
        p.note('/settings - Configure permissions (Auto-Approve / Ask every time)\n/resume - Resume previous chat session\n/session del - Select and delete saved sessions\n/new - Start a new chat session (or /new <prompt>)\n/model - Switch model for current provider\n/effort - Set the reasoning effort (only levels the model supports)\n/provider - Switch AI provider (with saved keys)\n/config - View configuration\n/plan - Switch to plan mode\n/agent - Switch to agent mode\n/clear - Clear context and start a new session\n/exit - Exit', 'Commands');
        continue;
      }
      if (cmd === '/exit') {
        p.outro('Goodbye!');
        break;
      }
      if (cmd === '/clear') {
        await startFreshSession();
        announce('new session · context cleared');
        continue;
      }
      if (cmd === '/new' || cmd === '/reset') {
        await startFreshSession();
        const initialPrompt = parts.slice(1).join(' ').trim();
        if (initialPrompt) {
          announce('new session · starting with your prompt');
          answer = initialPrompt;
        } else {
          announce('new session started');
          continue;
        }
      } else if (cmd === '/session' || cmd === '/sessions' || cmd === '/resume' || cmd === '/history') {
        const sub = (parts[1] || '').toLowerCase();
        if (sub === 'del' || sub === 'delete' || sub === 'rm') {
          await handleSessionDelete(config.banner || 'full');
          continue;
        }

        const sessions = await SessionManager.listSessions();
        if (sessions.length === 0) {
          p.log.warn('No saved sessions found.');
          continue;
        }

        let chosenSessionId: string;
        try {
          chosenSessionId = await search({
            message: 'Select a session to resume (type to filter, Esc to cancel):',
            source: async (term) => {
              const q = (term || '').trim().toLowerCase();
              const list: { name: string; value: string; description?: string }[] = [
                { name: pc.yellow('← Back / Cancel'), value: '__cancel__', description: 'Return to chat' },
                { name: pc.red('× Delete sessions... (/session del)'), value: '__delete_mode__', description: 'Selectively delete saved sessions' }
              ];
              for (const s of sessions) {
                const timeStr = formatSessionTime(s.updatedAt);
                const msgCount = s.messages.filter(m => m.role !== 'system').length;
                const shortId = s.id.split('_').pop();
                list.push({
                  name: `${pc.dim(`[#${shortId}]`)} ${s.title}`,
                  value: s.id,
                  description: `${timeStr} • ${msgCount} msgs • ${s.model || 'default'}`
                });
              }
              if (!q) return list;
              return list.filter(item => item.name.toLowerCase().includes(q) || item.value.toLowerCase().includes(q));
            },
            pageSize: 10
          });
        } catch {
          continue;
        }

        if (chosenSessionId === '__cancel__') {
          continue;
        }

        if (chosenSessionId === '__delete_mode__') {
          await handleSessionDelete(config.banner || 'full');
          continue;
        }

        const loaded = await SessionManager.loadSession(chosenSessionId);
        if (loaded) {
          sessionManager.setLoadedSession(loaded);
          history = new History();
          for (const m of loaded.messages) {
            history.addMessage(m);
          }
          if (loaded.model) {
            config.model = loaded.model;
            config.maxContextTokens = resolveContextLimit({ ...config, maxContextTokens: 0 }).limit;
          }
          if (loaded.planMode !== undefined) {
            planMode = loaded.planMode;
          }
          sessionCost.restore(loaded.totalCost || 0, loaded.costEstimated === true);
          drawLogo(config.banner || 'full');

          const nonSysMessages = loaded.messages.filter(m => m.role !== 'system');
          const totalMsgs = nonSysMessages.length;
          const displayLimit = 20;
          const recentMessages = nonSysMessages.slice(-displayLimit);
          const shownCount = recentMessages.length;

          // Header banner showing messages loaded
          const countInfo = totalMsgs > displayLimit
            ? `Loaded recent ${shownCount} messages of ${totalMsgs}`
            : `Loaded all ${totalMsgs} messages`;

          console.log('\n' + pc.bold(pc.cyan(`─── ${countInfo} (Session: "${loaded.title}") ───`)) + '\n');

          // Render each message in chronological order
          for (const msg of recentMessages) {
            if (msg.role === 'user') {
              console.log(pc.cyan('◆') + '  ' + pc.bold(pc.white(msg.content)));
              if (msg.images && msg.images.length > 0) {
                console.log(pc.magenta(`  [${msg.images.map(i => i.path).join(', ')}]`));
              }
              console.log();
            } else if (msg.role === 'assistant') {
              if (msg.content) {
                console.log(renderMarkdown(msg.content));
              }
              if (msg.tool_calls && msg.tool_calls.length > 0) {
                for (const tc of msg.tool_calls) {
                  console.log(pc.dim(`  ${tc.name}`));
                }
              }
              console.log();
            } else if (msg.role === 'tool') {
              if (msg.content) {
                const firstLine = msg.content.trim().split('\n')[0];
                if (firstLine.includes('+') || firstLine.includes('lines') || firstLine.includes('Successfully')) {
                  console.log(pc.green(`  └─ ${firstLine}`));
                }
              }
            }
          }

          console.log(pc.bold(pc.cyan(`────────────────────────────────────────────────────────────────────────\n`)));
          p.log.success(`Resumed session: "${loaded.title}" (${totalMsgs} total messages in history)`);
        } else {
          p.log.error('Failed to load session.');
        }
        continue;
      }
      if (cmd === '/plan') {
        planMode = true;
        const newSys = await buildSystemPrompt(true);
        history.updateSystemPrompt(newSys);
        announce('plan mode · designs and asks questions, does not touch files');
        continue;
      }
      if (cmd === '/agent') {
        planMode = false;
        const newSys = await buildSystemPrompt(false);
        history.updateSystemPrompt(newSys);
        announce('agent mode · edits files and runs commands');
        continue;
      }
      if (cmd === '/undo') {
        const { revertedFiles, count } = await globalSnapshotManager.undoLastTurn();
        if (count === 0) {
          p.log.warn('No changes to undo.');
        } else {
          history.popLastTurn();
          p.log.success(pc.bold(pc.green(`Successfully reverted changes in ${count} file(s):`)));
          for (const f of revertedFiles) {
            console.log(pc.cyan(`  • ${f}`));
          }
          console.log(pc.dim('  Note: only edits made with the file tools are reverted. Changes made by shell commands (bash) are not tracked.'));
        }
        continue;
      }
      if (cmd === '/diff') {
        try {
          const diffOutput = execSync('git diff', { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
          if (!diffOutput.trim()) {
            p.log.info('No uncommitted changes in git repository.');
          } else {
            console.log('\n' + pc.bold(pc.cyan('─── Git Diff ─────────────────────────')));
            for (const line of diffOutput.split('\n')) {
              if (line.startsWith('+') && !line.startsWith('+++')) {
                console.log(pc.green(line));
              } else if (line.startsWith('-') && !line.startsWith('---')) {
                console.log(pc.red(line));
              } else if (line.startsWith('@@')) {
                console.log(pc.magenta(line));
              } else {
                console.log(line);
              }
            }
            console.log(pc.bold(pc.cyan('──────────────────────────────────────\n')));
          }
        } catch {
          p.log.warn('Git is not available or current directory is not a git repo.');
        }
        continue;
      }
      if (cmd === '/status') {
        try {
          const statusOutput = execSync('git status --short', { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
          if (!statusOutput.trim()) {
            p.log.success('Working directory clean, no modified files.');
          } else {
            console.log('\n' + pc.bold(pc.cyan('─── Git Status ───────────────────────')));
            for (const line of statusOutput.split('\n')) {
              if (!line.trim()) continue;
              if (line.startsWith(' M') || line.startsWith('M ')) {
                console.log(pc.yellow(`  ${line}`));
              } else if (line.startsWith('??')) {
                console.log(pc.green(`  ${line}`));
              } else if (line.startsWith(' D')) {
                console.log(pc.red(`  ${line}`));
              } else {
                console.log(`  ${line}`);
              }
            }
            console.log(pc.bold(pc.cyan('──────────────────────────────────────\n')));
          }
        } catch {
          p.log.warn('Git is not available or current directory is not a git repo.');
        }
        continue;
      }
      if (cmd === '/commit') {
        try {
          const status = execSync('git status --short', { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
          if (!status.trim()) {
            p.log.info('No changes to commit.');
            continue;
          }
          const diff = execSync('git diff', { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
          const userMsg = parts.slice(1).join(' ').trim();
          let commitMsg = userMsg;

          if (!commitMsg) {
            const s = p.spinner();
            s.start('Generating conventional commit message with AI...');
            await refreshFormats(config).catch(() => null);
            const provider = createProvider(config);
            const promptReq = {
              messages: [
                {
                  role: 'system' as const,
                  content: 'You generate single-line conventional git commit messages (e.g. feat(auth): add token validation). Output ONLY the commit message line and nothing else.'
                },
                {
                  role: 'user' as const,
                  content: `Git status:\n${status}\n\nGit diff snippet:\n${diff.slice(0, 3000)}`
                }
              ]
            };
            const genRes = await provider.chat(promptReq);
            s.stop();
            commitMsg = (genRes.content || 'chore: update project files').trim().replace(/^["'`]|["'`]$/g, '').split('\n')[0];
          }

          const confirmed = await p.confirm({
            message: `Commit changes with message:\n"${pc.bold(pc.green(commitMsg))}"?`,
            initialValue: true
          });

          if (!p.isCancel(confirmed) && confirmed) {
            execFileSync('git', ['add', '-A'], { stdio: 'ignore' });
            execFileSync('git', ['commit', '-m', commitMsg], { stdio: 'ignore' });
            p.log.success(pc.bold(pc.green(`Committed: ${commitMsg}`)));
          } else {
            p.log.info('Commit cancelled.');
          }
        } catch (err: any) {
          p.log.error(`Git commit failed: ${err.message}`);
        }
        continue;
      }
      if (cmd === '/compact') {
        const msgs = history.getMessages();
        if (msgs.length <= 2) {
          p.log.info('Conversation is already short, no compaction needed.');
          continue;
        }

        const s = p.spinner();
        s.start('Compacting conversation context with AI...');
        try {
          await refreshFormats(config).catch(() => null);
          const provider = createProvider(config);
          // The request itself must fit the window: shorten a copy of the history first.
          const forSummary = new History();
          for (const m of msgs) forSummary.addMessage({ ...m, images: undefined });
          forSummary.pruneToLimit(Math.floor((config.maxContextTokens || 100000) * 0.7));
          const compactPrompt = {
            messages: [
              ...forSummary.getMessages(),
              {
                role: 'user' as const,
                content: 'Write a short, structured summary of our conversation so far: what the user wants, decisions made, files created or changed, and what is left to do. Plain text, no filler.'
              }
            ]
          };
          const res = await provider.chat(compactPrompt);
          s.stop();
          if (!res.content || !res.content.trim()) throw new Error('the model returned an empty summary');

          const sysPrompt = await buildSystemPrompt(planMode);
          history.clear();
          history.addMessage({ role: 'system', content: sysPrompt });
          // A user message, not an assistant one: the Anthropic format cannot start with the assistant.
          history.addMessage({
            role: 'user',
            content: `[CONVERSATION COMPACTED] Summary of our conversation so far:\n${res.content}`
          });
          p.log.success(pc.bold(pc.green('Context successfully compacted! Freed up tokens while retaining task summary.')));
        } catch (err: any) {
          s.stop();
          p.log.error(`Compaction failed: ${err.message}`);
        }
        continue;
      }
      if (cmd === '/session' && parts[1]?.toLowerCase() !== 'del') {
        const cur = sessionManager.getSession();
        const nonSys = history.getMessages().filter(m => m.role !== 'system');
        const shortId = cur.id.split('_').pop();
        console.log('\n' + pc.bold(pc.cyan('─── Active Session Information ───')));
        console.log(`  ${pc.bold('ID:')}        ${pc.cyan(cur.id)} ${pc.dim(`(#${shortId})`)}`);
        console.log(`  ${pc.bold('Title:')}     "${cur.title}"`);
        console.log(`  ${pc.bold('Model:')}     ${config.model}`);
        console.log(`  ${pc.bold('Mode:')}      ${planMode ? pc.cyan('PLAN') : pc.green('AGENT')}`);
        console.log(`  ${pc.bold('Messages:')}  ${nonSys.length} messages`);
        console.log(`  ${pc.bold('Cost:')}      ${sessionCost.format()}`);
        console.log(`  ${pc.bold('File:')}      ~/.devx/sessions/${cur.id}.json`);
        console.log(pc.bold(pc.cyan('──────────────────────────────────────\n')));
        continue;
      }
      if (cmd === '/init') {
        const agentsPath = path.join(process.cwd(), 'AGENTS.md');
        if (fsSync.existsSync(agentsPath)) {
          p.log.warn('AGENTS.md already exists in current workspace.');
        } else {
          const template = `# Project Developer Instructions (AGENTS.md)\n\n## Overview\nProject description and goals.\n\n## Tech Stack\n- TypeScript / Node.js\n\n## Coding Guidelines\n- Clean, modular code\n- Verify changes after editing\n`;
          await fs.writeFile(agentsPath, template, 'utf8');
          p.log.success(`Created ${pc.bold('AGENTS.md')} in project root! Customize it to give devx persistent instructions.`);
        }
        continue;
      }
      if (cmd === '/doctor') {
        await runDoctor(config);
        continue;
      }
      if (cmd === '/serve') {
        const sub = parts[1]?.toLowerCase();
        if (sub === 'stop') {
          if (stopServer()) {
            p.log.success('Web server stopped.');
          } else {
            p.log.info('No web server is currently running.');
          }
        } else {
          const customPort = parseInt(parts[1], 10) || 3000;
          try {
            const { port, localUrl, networkUrl } = await startServer(customPort);
            await displayServerBanner(localUrl, networkUrl);
          } catch (err: any) {
            p.log.error(`Failed to start web server: ${err.message}`);
          }
        }
        continue;
      }
      if (cmd === '/theme' || cmd === '/themes') {
        const themeArg = answer.replace(/^\/(?:theme|themes)\s*/i, '').trim();
        if (themeArg) {
          const matched = findTheme(themeArg);
          if (matched) {
            config.theme = matched.id;
            await saveConfig(config);
            const th = setActiveTheme(matched.id);
            announce(`theme ${th.name}`);
          } else {
            const themes = listThemes();
            p.log.warn(`Unknown theme: "${themeArg}". Available themes: ${themes.map(t => `${t.colorFn(t.symbol)} ${t.id}`).join(', ')}`);
          }
        } else {
          await handleThemeSelect(config);
        }
        continue;
      }
      if (cmd === '/memory') {
        const sub = parts[1]?.toLowerCase();
        if (sub === 'clear') {
          await MemoryManager.clearMemory();
          p.log.success('Cleared project memory bank (.devx/memory.md).');
        } else if (sub === 'add') {
          const fact = parts.slice(2).join(' ').trim();
          if (!fact) {
            p.log.warn('Usage: /memory add <fact or rule to remember>');
          } else {
            await MemoryManager.addFact(fact);
            p.log.success(`Saved to memory: "${fact}"`);
          }
        } else {
          const mem = await MemoryManager.loadMemory();
          if (!mem.trim()) {
            p.log.info('Project memory is empty. AI will remember facts as you work, or use /memory add <fact>.');
          } else {
            p.note(mem, 'Project Memory Bank (.devx/memory.md)');
            console.log(pc.dim('Commands: /memory add <text> | /memory clear\n'));
          }
        }
        continue;
      }
      if (cmd === '/usage') {
        await runUsagePanel({
          model: config.model,
          format: providerFormat(config),
          effort: thinkLabel(config) || undefined,
          context: currentContext(),
          compactions: history.compactions,
          dropped: history.droppedMessages,
          session: UsageTracker.getInstance().getSummary(),
          noVision: modelAcceptsImages(config.model) === false,
          limitSource: resolveContextLimit(config).source,
        });
        process.stdin.resume();
        continue;
      }
      if (cmd === '/export') {
        const targetName = parts.slice(1).join(' ').trim();
        const res = await SessionExporter.exportToMarkdown(history.getMessages(), config.model, targetName || undefined);
        if (res.success) {
          p.log.success(pc.bold(pc.green(`Session exported to: ${res.filePath}`)));
        } else {
          p.log.error(`Failed to export session: ${res.error}`);
        }
        continue;
      }
      if (cmd === '/mcp') {
        const sub = (parts[1] || '').toLowerCase();
        if (sub === 'reload' || sub === 'restart' || sub === 'r') {
          const s = p.spinner();
          s.start('Reloading MCP servers...');
          await MCPManager.getInstance().reload();
          s.stop();
          console.log('\n' + MCPManager.getInstance().renderStatusCard());
        } else {
          console.log('\n' + MCPManager.getInstance().renderStatusCard());
        }
        continue;
      }
      if (cmd === '/config') {
        const masked = { ...config };
        if (masked.apiKey) masked.apiKey = maskApiKey(masked.apiKey);
        if (masked.apiKeys) {
          masked.apiKeys = { ...masked.apiKeys };
          for (const k of Object.keys(masked.apiKeys)) {
            masked.apiKeys[k] = maskApiKey(masked.apiKeys[k]);
          }
        }
        p.note(JSON.stringify(masked, null, 2), 'Configuration (Secrets Masked)');
        continue;
      }
      if (cmd === '/model') {
        const res = await selectModel(config.baseUrl, config.apiKey, config.model, config.provider || '', true);
        if (res) {
          config.model = res.model;
          config.maxContextTokens = config.contextLimits?.[config.model] ?? res.contextLimit;
          await saveConfig(config);
          await refreshEndpoint(config).catch(() => null);
          announce(`model ${config.model}${providerFormat(config) === 'anthropic' ? ' (Anthropic format)' : ''}`);
        } else {
          p.log.info('Model selection cancelled.');
        }
        continue;
      }
      if (cmd === '/effort') {
        const showSay = (say: Say) => {
          if (config.ui?.layout === 'classic') {
            const text = say.detail ? `${say.text}\n${say.detail}` : say.text;
            if (say.kind === 'ok') p.log.success(text);
            else if (say.kind === 'warn') p.log.warn(text);
            else p.log.info(text);
          } else {
            console.log(renderNotice(say.kind, say.text));
            if (say.detail) console.log(renderResultBlock(say.detail, 'muted', process.stdout.columns || 80));
          }
        };
        const argText = parts.slice(1).join(' ').trim();
        if (/^(refresh|update)$/i.test(argText)) {
          showSay({ kind: 'info', text: 'asking models.dev and the endpoint...' });
          try {
            const result = await refreshCatalog();
            showSay({ kind: 'ok', text: `catalogue updated: ${result.models} models from ${result.providers} providers, ${result.priced} prices`, detail: `models.dev, ${result.generated}` });
          } catch (e: any) {
            const info = catalogInfo();
            showSay({ kind: 'warn', text: 'could not download the catalogue.', detail: `${String(e?.message || e)}. Using the ${info.downloaded ? 'downloaded' : 'built-in'} one (${info.models} models, ${info.generated}).` });
          }
          const route = detectRoute(config.baseUrl);
          if (route === 'generic' || route === 'openrouter') {
            const record = await refreshEndpoint(config, { force: true }).catch(() => null);
            showSay(record?.data
              ? { kind: 'ok', text: `the endpoint described ${config.model}` }
              : { kind: 'info', text: `the endpoint says nothing about ${config.model}`, detail: 'levels come from the catalogue or from the model name' });
          }
          continue;
        }
        // A custom endpoint is asked about this model first (cached, a few seconds at most).
        await refreshEndpoint(config).catch(() => null);
        const outcome = effortCommand(config, argText);
        if (outcome.type === 'say') {
          if (outcome.say.kind === 'ok') await saveConfig(config);
          showSay(outcome.say);
        } else {
          const choice = await runPicker(outcome.picker);
          if (choice === undefined) {
            showSay({ kind: 'info', text: 'effort unchanged' });
          } else {
            const say = chooseEffort(config, choice);
            await saveConfig(config);
            showSay(say);
          }
        }
        continue;
      }
      if (cmd === '/provider' || cmd === '/providers') {
        const newConfig = await selectProvider(config, true);
        if (newConfig) {
          config = newConfig;
          await refreshEndpoint(config).catch(() => null);
          announce(`provider ${config.provider || 'custom'} · ${config.model}${providerFormat(config) === 'anthropic' ? ' (Anthropic format)' : ''}`);
        } else {
          p.log.info('Provider selection cancelled.');
        }
        continue;
      }
    }

    // Auto-switch to AGENT mode ONLY when a plan has been presented and user approves it
    if (planMode) {
      const msgs = history.getMessages();
      const lastAssistantMsg = [...msgs].reverse().find(m => m.role === 'assistant');
      const assistantContent = (lastAssistantMsg?.content || '').toLowerCase();

      // Has the AI already presented a plan in the conversation?
      const planWasPresented = 
        assistantContent.includes('план') ||
        assistantContent.includes('plan') ||
        assistantContent.includes('архитектур') ||
        assistantContent.includes('структур') ||
        assistantContent.includes('приступать') ||
        assistantContent.includes('выполняй') ||
        assistantContent.includes('готово к');

      if (planWasPresented && !answer.includes('?')) {
        const norm = answer.toLowerCase().trim().replace(/[!.,?;:()«»""'']/g, ' ');
        const words = norm.split(/\s+/).filter(Boolean);

        // Ensure user is not asking a question
        const isQuestion = words.some(w => ['поможешь', 'помоги', 'как', 'почему', 'зачем', 'что', 'можешь', 'сможешь'].includes(w));

        if (!isQuestion) {
          const actionRoots = [
            'дела', 'сдела', 'выполн', 'приступ', 'начин', 'начн', 'реализ',
            'создав', 'создай', 'погнал', 'старту', 'утвержд', 'одобря', 'соглас',
            'start', 'proceed', 'execute', 'apply'
          ];

          const isApproval = 
            words.some(w => actionRoots.some(root => w.startsWith(root))) ||
            words.includes('давай') ||
            (words.length <= 2 && (words.includes('да') || words.includes('yes') || words.includes('ок') || words.includes('ok') || words.includes('go')));

          if (isApproval) {
            planMode = false;
            const newSys = await buildSystemPrompt(false);
            history.updateSystemPrompt(newSys);
            p.log.success(pc.bold(pc.green('Plan approved! Auto-switched to AGENT mode. Starting execution...')));
          }
        }
      }
    }

    // Resolve @file and @image mentions
    const { text: cleanAnswer, attachments, images, warnings: attachWarnings } = await resolveAtMentions(answer);
    for (const warning of attachWarnings) p.log.warn(warning);
    let finalContent = cleanAnswer;

    if (attachments.length > 0) {
      const attachBlocks = attachments.map(a => 
        `[Attached file: ${a.path}]\n\`\`\`\n${a.content}\n\`\`\``
      ).join('\n\n');
      finalContent = `${cleanAnswer}\n\n--- Attached Files ---\n${attachBlocks}`;
      p.log.info(pc.cyan(`Attached ${attachments.length} file(s): ${attachments.map(a => a.path).join(', ')}`));
    }

    if (images.length > 0) {
      if (modelAcceptsImages(config.model) === false) {
        p.log.warn(`${config.model} cannot read images: ${images.length === 1 ? 'the image' : 'the images'} will not be sent. Switch to a model with vision (/model).`);
      } else {
        p.log.info(pc.cyan(`Attached ${images.length} image(s): ${images.map(i => path.basename(i.path)).join(', ')}`));
      }
    }

    globalSnapshotManager.beginTurn();
    history.addMessage({
      role: 'user',
      content: finalContent,
      images: images.length > 0 ? images : undefined
    });

    // The format of the gateway is learned once (cached); the first request waits for it.
    await refreshFormats(config).catch(() => null);
    const provider = createProvider(config);
    const tools = getTools(planMode);
    const guard = new CLIConsoleGuard(config.autoApprove, config.bashAllowlist || []);
    const agentConfig: AgentConfig = {
      maxContextTokens: config.maxContextTokens || 100000,
      maxIterations: config.maxIterations || 100,
      autoApprove: config.autoApprove,
      bashAllowlist: config.bashAllowlist
    };

    const agent = new Agent(agentConfig, provider, tools, history, guard);
    const taskStartTime = Date.now();
    const abortController = new AbortController();
    let aborted = false;
    let isConnecting = true;
    let spinnerActive = false;
    let thinkingActive = false;
    let thinkingStartTime = 0;
    let textActive = false;

    const cleanUi = config.ui?.layout !== 'classic';
    const s = (cleanUi ? new UiSpinner() : p.spinner()) as any;
    const uiSpinner = cleanUi ? (s as UiSpinner) : null;
    const assistantGutter = new GutterWriter(palette.accent('●') + ' ', '  ');
    const thoughtGutter = new GutterWriter(palette.faint('  │ '), palette.faint('  │ '));
    const streamer = new MarkdownStreamer();
    if (cleanUi) streamer.setSink((text) => assistantGutter.write(text));
    const thoughtStreamer = new SmoothStreamer((chunk) => {
      if (cleanUi) thoughtGutter.write(palette.muted(chunk));
      else process.stdout.write(pc.dim(chunk));
    });
    let turnOutputTokens = 0;
    // Live token counter of this turn: exact numbers of finished requests plus an estimate for
    // the request in flight: what the model has written for the user's request so far (↓).
    let turnInputTokens = 0;
    let requestOutEstimate = 0;
    let toolArgsEstimate = 0;
    // The shown number eases toward the real one: it climbs fast, then slows down, instead of jumping.
    let shownOutput = 0;
    let shownAt = Date.now();
    const liveTokens = (): string => {
      const target = turnOutputTokens + requestOutEstimate + toolArgsEstimate;
      const now = Date.now();
      shownOutput = easeToward(shownOutput, target, now - shownAt);
      shownAt = now;
      return `↓ ${formatTokensShort(Math.round(shownOutput))} tokens`;
    };
    const costAtTurnStart = sessionCost.total;
    const turnCost: CostState = { estimated: false, unpriced: 0, priced: 0 };
    let spacerBeforeText = false;

    // Tool results in the clean layout: a quiet "╰ summary" line, a diff box for edits.
    const renderCleanToolResult = (name: string, result: string) => {
      const cols = process.stdout.columns || 80;
      let handled = false;
      try {
        const parsed = JSON.parse(result);
        if (parsed && typeof parsed === 'object') {
          if (parsed.type === 'todo_list' && parsed.displayCard) {
            process.stdout.write(parsed.displayCard);
            handled = true;
          } else if (parsed.action === 'edit' && parsed.diffLines) {
            const box = formatDiffBox(parsed.path, parsed.diffLines, Math.min(cols - 4, 80), undefined, !!config.ui?.diffColors);
            console.log(box.split('\n').map((l: string) => '    ' + l).join('\n'));
            if (parsed.summary) console.log(renderResultLine(parsed.summary, 'ok', cols));
            handled = true;
          } else if (parsed.summary) {
            console.log(renderResultLine(parsed.summary, 'ok', cols));
            handled = true;
          }
        }
      } catch {}
      if (!handled) {
        const lines = summarizeToolResult(name, result, cols);
        if (lines.length > 0) console.log(lines.join('\n'));
      }
    };

    const telemetry = new LiveTelemetryTracker();
    telemetry.start('connecting');
    uiSpinner?.setMetaProvider(liveTokens);

    const finishThinking = (extraNewline = true) => {
      if (!thinkingActive) return;
      thoughtStreamer.flush();
      thoughtGutter.flush();
      const elapsedSec = thinkingStartTime > 0 ? ((Date.now() - thinkingStartTime) / 1000).toFixed(1) : '0.0';
      const teleStats = config.ui?.liveTelemetry && telemetry.getTotalTokens() > 0
        ? ` · ${LiveTelemetryTracker.formatTokenCount(telemetry.getTotalTokens())} tok · ${telemetry.getTokensPerSecond()} tok/s`
        : '';
      if (cleanUi) {
        process.stdout.write('\n' + palette.faint(`  ╰ thought for ${elapsedSec}s${teleStats}`) + '\n');
        thoughtGutter.reset();
        spacerBeforeText = true;
      } else {
        const width = Math.min(process.stdout.columns || 40, 52);
        const label = `─── Thought for ${elapsedSec}s${teleStats} `;
        const fillLen = Math.max(2, width - label.length);
        process.stdout.write('\n\n' + pc.dim(label + '─'.repeat(fillLen)) + (extraNewline ? '\n\n' : '\n'));
      }
      thinkingActive = false;
      thinkingStartTime = 0;
    };

    const stopGeneration = () => {
      if (aborted) return;
      aborted = true;
      abortController.abort();
      if (spinnerActive) {
        s.stop();
        spinnerActive = false;
      }
      telemetry.reset();
      thoughtStreamer.reset();
      finishThinking(true);
      streamer.finish();
      if (textActive) {
        process.stdout.write('\n');
      }
      if (cleanUi) {
        console.log(renderNotice('warn', isConnecting ? 'connection cancelled' : 'stopped'));
        console.log();
      } else if (isConnecting) {
        console.log(pc.yellow('\nПодключение отменено / Connection cancelled (Ctrl+C).'));
      } else {
        console.log(pc.yellow('\nGeneration stopped / Ответ остановлен (Ctrl+C).'));
      }
    };

    const onSigInt = () => {
      stopGeneration();
    };

    // The input line at the bottom while the agent works: what is typed there is queued and
    // reaches the model at its next step (see TurnComposer).
    // The working line (time and tokens) stays above the frame for the whole turn.
    let statusLabel = 'Waiting for the model';
    let statusFrame = 0;
    const statusStartedAt = Date.now();
    const statusLine = () => renderStatusLine({
      label: statusLabel,
      elapsedMs: Date.now() - statusStartedAt,
      meta: liveTokens(),
      frame: statusFrame++,
      columns: process.stdout.columns || 80,
    });
    const composer = new TurnComposer(process.stdout, () => {
      const ctx = currentContext();
      const used = ctx.total - ctx.system - ctx.tools;
      const git = GitStatusCache.getStatus();
      return {
        mode: resolveMode(planMode, !!config.autoApprove),
        model: modelLabel(config),
        cwd: process.cwd(),
        branch: git?.branch,
        dirty: git?.isDirty,
        contextPercent: Math.min(100, Math.round((used / (config.maxContextTokens || getModelContextLimit(config.model))) * 100)),
        cost: sessionCost.total,
        costState: sessionCost.state(),
      };
    }, undefined, statusLine);
    const releasePauseHook = registerPauseHook(() => {
      composer.suspend();
      return () => composer.resume();
    });

    const onRawData = (data: Buffer | string) => {
      const buf = Buffer.isBuffer(data) ? data : Buffer.from(String(data));
      if (buf.includes(3) || buf.includes(0x03) || data === '\x03') {
        stopGeneration();
        return;
      }
      composer.handleData(buf.toString('utf8'));
    };
    const onResize = () => composer.onResize();

    /** What a queued message becomes in the conversation: its @files and images are attached like in a normal prompt. */
    const queuedToMessage = async (text: string): Promise<Message> => {
      const resolved = await resolveAtMentions(text);
      let content = resolved.text;
      if (resolved.attachments.length > 0) {
        const blocks = resolved.attachments.map(a => `[Attached file: ${a.path}]\n\`\`\`\n${a.content}\n\`\`\``).join('\n\n');
        content = `${resolved.text}\n\n--- Attached Files ---\n${blocks}`;
      }
      return { role: 'user', content, images: resolved.images.length > 0 ? resolved.images : undefined };
    };

    activeAbortHandler = stopGeneration;
    process.on('SIGINT', onSigInt);
    if (process.stdin.isTTY) {
      try {
        process.stdin.resume();
        process.stdin.setRawMode(true);
        process.stdin.on('data', onRawData);
        if (cleanUi && config.ui?.typeAhead !== false && composer.start()) {
          process.stdout.on('resize', onResize);
          uiSpinner?.mute(true);
        }
      } catch {}
    }

    s.start(cleanUi ? 'Waiting for the model' : 'Connecting...');
    spinnerActive = true;

    const turnStartTime = Date.now();
    let toolsExecutedCount = 0;

    try {
      for await (const event of agent.run(abortController.signal, {
        takePending: async () => Promise.all(composer.takeQueued().map(queuedToMessage)),
        hasPending: () => composer.hasQueued(),
      })) {
        if (aborted) break;
        if (event.type === 'reasoning_delta') {
          isConnecting = false;
          telemetry.setPhase('thinking');
          statusLabel = 'Thinking';
          telemetry.recordTokens(telemetry.estimateTokensFromText(event.delta));
          requestOutEstimate += telemetry.estimateTokensFromText(event.delta);
          if (spinnerActive) {
            s.stop();
            spinnerActive = false;
          }
          if (!thinkingActive) {
            thinkingStartTime = Date.now();
            process.stdout.write((cleanUi ? (spacerBeforeText ? '\n' : '') : '\n') + (cleanUi ? palette.muted('  ∴ thinking') : pc.bold(pc.white('› Thought:'))) + '\n');
            if (cleanUi) spacerBeforeText = false;
            thinkingActive = true;
          }
          thoughtStreamer.push(event.delta);
        } else if (event.type === 'text_delta') {
          isConnecting = false;
          telemetry.setPhase('generating');
          statusLabel = 'Writing';
          telemetry.recordTokens(telemetry.estimateTokensFromText(event.delta));
          requestOutEstimate += telemetry.estimateTokensFromText(event.delta);
          if (spinnerActive) {
            s.stop();
            spinnerActive = false;
          }
          finishThinking(true);
          if (!textActive) {
            textActive = true;
            if (cleanUi) {
              if (spacerBeforeText) process.stdout.write('\n');
              spacerBeforeText = false;
              assistantGutter.reset();
            }
          }
          streamer.push(event.delta);
        } else if (event.type === 'text') {
          isConnecting = false;
          if (spinnerActive) {
            s.stop();
            spinnerActive = false;
          }
          finishThinking(true);
          if (!textActive) {
            if (cleanUi) {
              if (spacerBeforeText) console.log();
              spacerBeforeText = false;
              assistantGutter.reset();
              assistantGutter.write(renderMarkdown(event.content) + '\n');
              console.log();
            } else {
              console.log('\n' + renderMarkdown(event.content) + '\n');
            }
          }
        } else if (event.type === 'tool_generating') {
          isConnecting = false;
          telemetry.setPhase('tool');
          const theme = getCurrentTheme();
          const label = formatToolGeneratingLabel(event.name, event.targetHint);
          statusLabel = label;
          const teleStats = telemetry.getTotalTokens() > 0
            ? ` · ${LiveTelemetryTracker.formatTokenCount(telemetry.getTotalTokens())} tok · ${telemetry.getTokensPerSecond()} tok/s`
            : '';
          const telemetrySuffix = config.ui?.liveTelemetry ? ` ${pc.dim(`(${telemetry.getElapsedSeconds()}s${teleStats})`)}` : '';
          if (!spinnerActive) {
            streamer.finish();
            finishThinking(false);
            s.start(cleanUi ? label : theme.boldFn(label) + telemetrySuffix);
            spinnerActive = true;
          } else {
            s.message(cleanUi ? label : theme.boldFn(label) + telemetrySuffix);
          }
          // The arguments written so far (about 3 bytes per token), on top of the text before them.
          toolArgsEstimate = Math.max(toolArgsEstimate, Math.round(event.bytes / 3));
        } else if (event.type === 'user_message') {
          // The message typed during the work reached the model: show it in the chat like any prompt.
          if (spinnerActive) {
            s.stop();
            spinnerActive = false;
          }
          finishThinking(true);
          streamer.finish();
          if (textActive) {
            process.stdout.write('\n');
            textActive = false;
          }
          statusLabel = 'Waiting for the model';
          const typed = event.content.split('\n\n--- Attached Files ---')[0];
          console.log();
          console.log(renderUserEcho(typed, process.stdout.columns || 80).join('\n'));
          console.log();
          s.start(cleanUi ? 'Waiting for the model' : 'Connecting...');
          spinnerActive = true;
        } else if (event.type === 'tool_start') {
          isConnecting = false;
          telemetry.reset();
          statusLabel = event.actionDesc || event.name;
          // A question to the user takes over the keyboard: the typing line steps aside.
          if (event.name === 'ask_questions') composer.suspend();
          if (spinnerActive) {
            s.stop();
            spinnerActive = false;
          }
          streamer.finish();
          finishThinking(false);
          if (textActive) {
            process.stdout.write('\n');
            textActive = false;
          }
          if (cleanUi) {
            console.log(renderToolLine(event.actionDesc, process.stdout.columns || 80));
            assistantGutter.reset();
          } else {
            console.log('\n' + pc.bold(pc.white(event.actionDesc)));
          }
          if (event.name !== 'ask_questions' && event.name !== 'todo_list') {
            s.start(cleanUi ? 'Running' : 'Executing...');
            spinnerActive = true;
          }
        } else if (event.type === 'tool_end') {
          if (event.name === 'ask_questions') composer.resume();
          statusLabel = 'Waiting for the model';
          composer.refresh();
          toolsExecutedCount++;
          GitStatusCache.invalidate();
          if (spinnerActive) {
            s.stop();
            spinnerActive = false;
          }
          if (cleanUi) {
            if (event.result) renderCleanToolResult(event.name, event.result);
            spacerBeforeText = true;
          } else if (event.result) {
            try {
              const parsed = JSON.parse(event.result);
              if (parsed.type === 'todo_list' && parsed.displayCard) {
                process.stdout.write(parsed.displayCard);
              } else if (parsed.action === 'edit' && parsed.diffLines) {
                const cols = Math.min(process.stdout.columns || 80, 80);
                console.log(formatDiffBox(parsed.path, parsed.diffLines, cols, parsed.summary, !!config.ui?.diffColors));
              } else if (parsed.summary) {
                console.log(pc.green(`  └─ ${parsed.summary}`));
              } else {
                const firstLine = event.result.trim().split('\n')[0];
                if (firstLine.includes('+') || firstLine.includes('lines') || firstLine.includes('Successfully')) {
                  console.log(pc.green(`  └─ ${firstLine}`));
                }
              }
            } catch {
              const firstLine = event.result.trim().split('\n')[0];
              if (firstLine.includes('+') || firstLine.includes('lines') || firstLine.includes('Successfully')) {
                console.log(pc.green(`  └─ ${firstLine}`));
              }
            }
          }
        } else if (event.type === 'usage') {
          sessionCost.add(event.usage);
          composer.refresh();
          if (typeof event.usage.cost === 'number') {
            turnCost.priced = (turnCost.priced ?? 0) + 1;
            if (event.usage.costSource === 'estimated') turnCost.estimated = true;
          } else {
            turnCost.unpriced = (turnCost.unpriced ?? 0) + 1;
          }
          turnOutputTokens += event.usage.completionTokens || 0;
          turnInputTokens += event.usage.promptTokens || 0;
          requestOutEstimate = 0;
          toolArgsEstimate = 0;
        } else if (event.type === 'reconnecting') {
          statusLabel = 'Reconnecting';
          telemetry.reset();
          if (spinnerActive) {
            s.stop();
            spinnerActive = false;
          }
          if (thinkingActive) {
            finishThinking(false);
          }
          if (textActive) {
            process.stdout.write('\n');
            textActive = false;
          }
          streamer.reset();

          if (cleanUi) {
            console.log(renderNotice('warn', `connection unstable (${event.reason})`));
            s.start(`reconnecting ${event.attempt}/${event.maxAttempts} · retry in ${(event.delayMs / 1000).toFixed(1)}s`);
          } else {
            console.log();
            console.log(pc.bold(pc.yellow(`Интернет-соединение нестабильно (${event.reason})`)));
            console.log(pc.cyan(`Попытка переподключения [${event.attempt}/${event.maxAttempts}] (пауза ${(event.delayMs / 1000).toFixed(1)}с)...`));
            
            s.start(pc.cyan(`Ожидание сети [${event.attempt}/${event.maxAttempts}]...`));
          }
          spinnerActive = true;
        } else if (event.type === 'reconnected') {
          statusLabel = 'Waiting for the model';
          if (spinnerActive) {
            s.stop();
            spinnerActive = false;
          }
          if (cleanUi) {
            console.log(renderNotice('ok', 'connection restored'));
          } else {
            console.log(pc.bold(pc.green(`✓ Соединение восстановлено! Возобновляем генерацию...`)));
            console.log();
          }
          s.start(cleanUi ? 'Waiting for the model' : 'Connecting...');
          spinnerActive = true;
        } else if (event.type === 'error') {
          if (spinnerActive) { s.stop(); spinnerActive = false; }
          finishThinking(true);
          streamer.finish();
          if (cleanUi) console.log(renderNotice('error', event.message));
          else p.log.error(pc.bold(pc.red(`${event.message}`)));
        } else if (event.type === 'system') {
          if (spinnerActive) { s.stop(); spinnerActive = false; }
          if (cleanUi) console.log(renderNotice('info', event.message));
          else p.log.info(event.message);
        }
      }
      finishThinking(true);
      streamer.finish();
      if (spinnerActive) {
        s.stop();
        spinnerActive = false;
      }
      if (textActive) {
        process.stdout.write('\n');
      }

      // Hand the bottom of the screen back. Whatever was typed but not sent waits in the next prompt;
      // a message that arrived too late for the agent starts the next turn by itself.
      const leftover = composer.stop();
      if (leftover.queued.length > 0) {
        if (aborted) currentDraft = [...leftover.queued, leftover.draft].filter(Boolean).join(' ');
        else autoTriggerPrompt = leftover.queued.join('\n');
      } else if (leftover.draft) {
        currentDraft = leftover.draft;
      }

      const turnElapsedMs = Date.now() - turnStartTime;
      if (cleanUi && !aborted) {
        console.log(renderTurnSummary({
          ms: turnElapsedMs,
          tools: toolsExecutedCount,
          outputTokens: turnOutputTokens,
          inputTokens: turnInputTokens,
          cost: sessionCost.total - costAtTurnStart,
          costState: turnCost
        }));
        console.log();
      }
      const shouldNotify = (turnElapsedMs >= 15000) || (toolsExecutedCount > 0) || (planMode && lastPlanReady !== null);
      if (shouldNotify && !aborted) {
        const secs = Math.round(turnElapsedMs / 1000);
        notifyDevice('devx', `Task completed in ${secs}s!`);
      }

      // Auto-save session
      await sessionManager.save(history.getMessages(), sessionCost.total, config.model, planMode, sessionCost.estimated);

      // Check if plan was finalized in PLAN mode
      if (planMode && !aborted) {
        const lastMsg = history.getMessages().slice(-1)[0];
        const hasPlanReadyTool = lastPlanReady !== null;
        const hasPlanText = lastMsg && lastMsg.role === 'assistant' && (
          lastMsg.content.includes('Цель и архитектурный обзор') ||
          lastMsg.content.includes('Пошаговый список изменений') ||
          lastMsg.content.includes('FINAL PLAN') ||
          lastMsg.content.includes('План готов') ||
          lastMsg.content.includes('Архитектурный план') ||
          lastMsg.content.includes('план готов')
        );

        if (hasPlanReadyTool || hasPlanText) {
          resetPlanReady();
          console.log();
          let choice = '';
          try {
            choice = await select({
              message: pc.bold(pc.cyan('Plan is ready! What would you like to do?')),
              loop: false,
              choices: [
                {
                  name: pc.bold(pc.green('Go (Switch to AGENT mode and start execution)')),
                  value: 'go',
                  description: 'Automatically switch to AGENT mode and start implementing the plan immediately'
                },
                {
                  name: pc.bold(pc.yellow('+ Other (Modify, add details, or ask questions)')),
                  value: 'other',
                  description: 'Stay in PLAN mode and open prompt to type adjustments or additions'
                }
              ]
            });
          } catch {}

          if (choice === 'go') {
            planMode = false;
            const newSys = await buildSystemPrompt(false);
            history.updateSystemPrompt(newSys);
            p.log.success(pc.bold(pc.green('Switched to AGENT mode! Starting execution of approved plan...')));
            currentDraft = '';
            autoTriggerPrompt = 'Go!';
            continue;
          } else {
            currentDraft = '';
            continue;
          }
        }
      }
    } catch (err: any) {
      finishThinking(true);
      streamer.finish();
      if (spinnerActive) {
        s.stop();
        spinnerActive = false;
      }
      if (!aborted && err.name !== 'AbortError' && !err.message?.includes('aborted')) {
        if (cleanUi) console.log(renderNotice('error', err.message));
        else p.log.error(pc.bold(pc.red(`${err.message}`)));
      }
    } finally {
      const unsent = composer.stop();
      releasePauseHook();
      process.stdout.removeListener('resize', onResize);
      if (!currentDraft && (unsent.draft || unsent.queued.length > 0)) {
        currentDraft = [...unsent.queued, unsent.draft].filter(Boolean).join(' ');
      }
      globalSnapshotManager.finishTurn();
      activeAbortHandler = null;
      process.removeListener('SIGINT', onSigInt);
      if (process.stdin.isTTY) {
        process.stdin.removeListener('data', onRawData);
        process.stdin.setRawMode(false);
      }
    }
  }

  MCPManager.getInstance().stopAll();
}

process.on('exit', () => {
  try {
    MCPManager.getInstance().stopAll();
  } catch {}
});

main().catch((err) => reportRuntimeError(err, activeAbortHandler));
