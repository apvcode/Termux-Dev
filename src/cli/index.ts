import { Command } from 'commander';
import * as p from '@clack/prompts';
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
import { CLIConsoleGuard, newPermissionMemory } from '../permissions/guard.js';
import { globalSnapshotManager } from '../core/snapshot.js';
import { MemoryManager, memoryForPrompt } from '../core/memory.js';
import { startServer, stopServer, displayServerBanner } from './server.js';
import { notifyDevice, acquireWakeLock, isTermux, notificationBody, sendDesktopNotification } from '../core/notify.js';
import { copyToClipboard } from './clipboard-write.js';
import { REWIND_CURRENT, messageDraft, rewindItems, userEntries } from './rewind.js';
import { runPicker } from './picker.js';
import { runFinder, runTextInput } from './finder.js';
import { renderWelcome } from './welcome.js';
import { savePlanFile } from '../core/planfile.js';
import { BASE_TITLE, RESTORE_TERMINAL_TITLE, SAVE_TERMINAL_TITLE, cleanTitle, generateChatTitle, provisionalTitle, terminalTitleSequence } from '../core/title.js';
import { runDoctor } from './doctor.js';

import { ALL_PROVIDERS } from './providers.js';
import { getModelContextInfo, getModelContextLimit, LimitSource } from '../core/models.js';
import { SessionManager } from '../core/session.js';
import { MarkdownStreamer, renderMarkdown } from './markdown.js';
import { SmoothStreamer } from './smooth.js';
import { askPrompt } from './prompt.js';
import { resolveAtMentions } from './files.js';
import { runStartupUpdateCheck, checkForUpdates, performSelfUpdate } from './updater.js';
import { getTheme, setActiveTheme, getCurrentTheme, listThemes, findTheme, THEMES, ThemeDef } from './theme.js';
import { runHeadlessMode } from './headless.js';
import { CustomCommandManager } from '../core/commands.js';
import { UsageTracker } from '../core/usage.js';
import { CostLedger, CostState } from '../core/cost.js';
import { SessionExporter } from './export.js';
import { MCPManager, discoverProjectMcpServers, hashProjectMcpServers, readClaudeServers, removeUserServer, saveUserServers } from '../mcp/manager.js';
import { writeSecureFile } from '../core/secure-file.js';
import { applyLocalConfig, persistedConfig, withoutSessionOnlySettings } from '../core/local-config.js';
import { cloneConfig, mergeConfigChanges } from '../core/config-merge.js';
import { BackgroundNote, getBackgroundManager } from '../core/background.js';
import { backgroundSummary, noteRows, runTasksCommand } from './background-ui.js';
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
  formatCost,
  formatTokens as formatTokensShort,
  palette,
  pickTip,
  renderHeader,
  renderNotice,
  renderResultBlock,
  renderResultLine,
  renderTaskCard,
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
import { runMcpPanel } from './mcp-panel.js';
import { renderResumedMessages } from './resume.js';
import { askConfirm, askPlanDecision, printFarewell } from './confirm.js';
import { Checkpoint, createCheckpoint, diffCheckpoint, restoreCheckpoint } from '../core/checkpoint.js';
import { log, note } from './log.js';
import { displayWidth, tailWidth } from './width.js';
import { COMPACT_MARK, TranscriptItem, collapsedThoughtLine, renderItem, messagesToItems, renderTranscript, toolResultRows } from './transcript.js';
import { chooseEffort, effortCommand, Say } from './effort.js';
import { effectiveReasoning, parseEffortArg, detectRoute, thinkLabel } from '../core/reasoning.js';
import { catalogInfo, installCapabilities, refreshCapabilities, refreshCatalog } from '../core/capabilities.js';
import { modelAcceptsImages } from '../core/vision.js';
import { contextBreakdown } from '../core/context-stats.js';
import { runUsagePanel } from './usage-panel.js';
import { TurnComposer } from './typeahead.js';
import { EXTENDED_KEYS_OFF, normalizeKey, splitKeys } from './keys.js';
import { LANGUAGES, getLanguage, setLanguage, t } from '../core/i18n.js';
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

/**
 * What this process last knew of the config file (its own settings after the start-up overrides,
 * or what it wrote last). A save writes only the difference to it, see core/config-merge.ts.
 */
let configBaseline: Record<string, any> | null = null;
let saveChain: Promise<void> = Promise.resolve();

/** Call once the start-up overrides (-m, project config) are applied: those are not saved by accident. */
function markConfigBaseline(config: any) {
  syncKeyMaps(config);
  configBaseline = cloneConfig(persistedConfig(config));
}

function syncKeyMaps(config: any) {
  config.apiKeys = config.apiKeys || {};
  config.baseUrls = config.baseUrls || {};
  if (config.provider && config.apiKey) {
    config.apiKeys[config.provider] = config.apiKey;
  }
  if (config.provider && config.baseUrl) {
    config.baseUrls[config.provider] = config.baseUrl;
  }
}

async function writeConfigFile(config: any) {
  syncKeyMaps(config);
  // Runtime-only flags and YOLO (one run only) must not end up in the user's config file.
  const mine = cloneConfig(persistedConfig(config)) as Record<string, any>;

  // Another devx may have saved since we looked: write only our own changes on top of the file.
  let disk: Record<string, any> | null = null;
  try {
    const parsed = JSON.parse(await fs.readFile(CONFIG_PATH, 'utf8'));
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) disk = withoutSessionOnlySettings(parsed);
  } catch {}
  const merged = disk ? mergeConfigChanges(configBaseline ?? {}, mine, disk) : mine;

  // The file holds API keys: owner-only permissions (0600). Written aside and renamed, so a second
  // process never reads half a file.
  const tmp = `${CONFIG_PATH}.${process.pid}.tmp`;
  try {
    await writeSecureFile(tmp, JSON.stringify(merged, null, 2));
    await fs.rename(tmp, CONFIG_PATH);
  } catch (err) {
    await fs.rm(tmp, { force: true }).catch(() => null);
    throw err;
  }
  configBaseline = mine;
}

async function saveConfig(config: any) {
  // One save at a time inside this process; the read-merge-write of the next one sees the previous.
  const run = saveChain.then(() => writeConfigFile(config));
  saveChain = run.catch(() => undefined);
  return run;
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

const formatContext = (n: number) => (n >= 1_000_000 ? `${(n / 1_000_000).toFixed(1)}M` : `${Math.round(n / 1000)}k`);

/** `step` is "3/4" during the first run, so the card says where in the setup you are. */
async function selectModel(baseUrl: string, apiKey: string, currentModel: string, providerId: string = '', _allowCancel = true, step?: string): Promise<{ model: string; contextLimit: number } | null> {
  const s = new UiSpinner();
  s.start(t('onboard.model.fetching'));
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

  const typeName = async (note?: string) => {
    const name = await runTextInput({
      title: t('onboard.model.title'),
      subtitle: step,
      label: t('onboard.model.label'),
      initial: models.length === 0 ? currentModel : '',
      notes: note ? [note] : undefined,
    });
    return name ? { model: name, contextLimit: getModelContextLimit(name) } : null;
  };

  if (models.length === 0) return typeName(t('onboard.model.manual'));

  const CUSTOM = '__custom__';
  for (;;) {
    const choice = await runFinder<string>({
      title: t('onboard.model.title'),
      subtitle: step ?? `${models.length}`,
      placeholder: t('onboard.model.placeholder'),
      current: currentModel,
      items: [
        { value: CUSTOM, label: t('onboard.model.custom'), detail: t('onboard.model.customDetail'), pinned: true },
        ...models.map((m) => ({
          value: m.id,
          label: m.id,
          detail: [m.viaAnthropic ? t('onboard.model.anthropic') : '', m.contextLength ? t('onboard.model.context', { n: formatContext(m.contextLength) }) : ''].filter(Boolean).join(', '),
        })),
      ],
    });
    if (choice === undefined) return null;
    if (choice === CUSTOM) {
      const typed = await typeName();
      if (typed) return typed;
      continue;       // Esc in the field: back to the list
    }
    const found = models.find(m => m.id === choice);
    return { model: choice, contextLimit: (found && found.contextLength) || getModelContextLimit(choice) };
  }
}

/** The host of an address, for the detail column: `api.openai.com`. */
const hostOf = (url: string) => url.replace(/^https?:\/\//, '').replace(/\/.*$/, '');

async function selectProvider(currentConfig: any, allowCancel = true): Promise<any | null> {
  const apiKeys: Record<string, string> = currentConfig.apiKeys || {};
  const baseUrls: Record<string, string> = currentConfig.baseUrls || {};
  if (currentConfig.provider && currentConfig.apiKey) {
    apiKeys[currentConfig.provider] = currentConfig.apiKey;
  }
  const firstRun = !allowCancel;
  const stepOf = (n: number) => (firstRun ? `${n}/5` : undefined);

  let providerId = '';
  let baseUrl = '';
  let apiKey = '';
  let stage: 'provider' | 'url' | 'key' | 'model' = 'provider';
  let modelRes: { model: string; contextLimit: number } | null = null;

  while (!modelRes) {
    if (stage === 'provider') {
      const choice = await runFinder<string>({
        title: t('onboard.provider.title'),
        subtitle: stepOf(3),
        placeholder: t('onboard.provider.placeholder'),
        current: currentConfig.provider,
        notes: firstRun ? [t('onboard.provider.note')] : undefined,
        items: ALL_PROVIDERS.map((pr) => ({
          value: pr.value,
          label: pr.label,
          detail: `${apiKeys[pr.value] ? maskApiKey(apiKeys[pr.value]) + '  ' : ''}${hostOf(pr.baseUrl || '')}`.trim(),
          keywords: `${pr.value} ${hostOf(pr.baseUrl || '')}`,
          pinned: pr.value === 'custom',
        })),
      });
      if (choice === undefined) return null;       // Esc on the first step leaves the whole setup
      providerId = choice;
      const known = ALL_PROVIDERS.find(pr => pr.value === providerId);
      baseUrl = baseUrls[providerId] || known?.baseUrl || '';
      stage = providerId === 'custom' || !baseUrl ? 'url' : 'key';
    } else if (stage === 'url') {
      const url = await runTextInput({
        title: t('onboard.baseUrl.title'),
        subtitle: stepOf(3),
        label: t('onboard.baseUrl.label'),
        initial: baseUrl,
        placeholder: t('onboard.baseUrl.placeholder'),
      });
      if (url === undefined) { stage = 'provider'; continue; }
      baseUrl = url;
      baseUrls[providerId] = baseUrl;
      stage = 'key';
    } else if (stage === 'key') {
      const label = ALL_PROVIDERS.find(pr => pr.value === providerId)?.label || providerId;
      const back = providerId === 'custom' || !(ALL_PROVIDERS.find(pr => pr.value === providerId)?.baseUrl) ? 'url' : 'provider';
      if (providerId === 'ollama') {
        apiKey = 'ollama';
        stage = 'model';
        continue;
      }
      const saved = apiKeys[providerId];
      let useSaved = false;
      if (saved) {
        const choice = await runPicker<'saved' | 'new'>({
          title: t('onboard.key.title'),
          subtitle: label,
          items: [
            { value: 'saved', label: t('onboard.key.useSaved'), detail: maskApiKey(saved) },
            { value: 'new', label: t('onboard.key.enterNew') },
          ],
          notes: [t('onboard.key.savedTitle', { provider: label })],
        });
        if (choice === undefined) { stage = back; continue; }
        useSaved = choice === 'saved';
      }
      if (useSaved) {
        apiKey = saved;
      } else {
        const key = await runTextInput({
          title: t('onboard.key.title'),
          subtitle: stepOf(4) ?? label,
          label: t('onboard.key.label', { provider: label }),
          secret: true,
          notes: [t('onboard.key.note')],
        });
        if (key === undefined) { stage = back; continue; }
        apiKey = key;
        apiKeys[providerId] = apiKey;
      }
      stage = 'model';
    } else {
      modelRes = await selectModel(baseUrl, apiKey, currentConfig.model || '', providerId, allowCancel, stepOf(5));
      if (!modelRes) stage = apiKey === 'ollama' ? 'provider' : 'key';
    }
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

/** The themes as a list; the interface takes the colours of the focused one at once, so you see what you pick. */
async function chooseTheme(current: string | undefined, step?: string): Promise<string | undefined> {
  const before = current ?? getCurrentTheme().id;
  const picked = await runPicker<string>({
    title: t('onboard.theme.title'),
    subtitle: step,
    items: listThemes().map((th) => ({ value: th.id, label: th.name, detail: th.desc })),
    current: before,
    labelWidth: 20,
    notes: [t('onboard.theme.note')],
    onFocus: (id) => { setActiveTheme(id); },
  });
  setActiveTheme(picked ?? before);
  return picked;
}

/** First run: the welcome card, the language, then provider, key and model, each on its own small card. */
async function runOnboarding(): Promise<any> {
  const cols = () => process.stdout.columns || 80;
  const show = (done: number) => {
    process.stdout.write('\x1b[2J\x1b[3J\x1b[H');
    console.log();
    for (const line of renderWelcome(DEVX_VERSION, cols(), done)) console.log(line);
    console.log();
  };

  show(0);
  const language = await runPicker<string>({
    title: t('onboard.language.title'),
    subtitle: '1/5',
    items: LANGUAGES.map((l) => ({ value: l.id, label: l.name })),
    current: getLanguage(),
    notes: [t('onboard.language.note')],
    labelWidth: 14,
  });
  if (language === undefined) {
    process.exit(0);
  }
  setLanguage(language);

  show(1);
  const theme = await chooseTheme(undefined, '2/5');
  show(2);
  const res = await selectProvider({ apiKeys: {}, baseUrls: {}, language, ...(theme ? { theme } : {}), onboarded: true }, false);
  if (!res) {
    process.exit(0);
  }
  show(5);
  console.log(renderNotice('ok', t('onboard.done')));
  console.log();
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
    globalConfig = withoutSessionOnlySettings(JSON.parse(data));
    setLanguage(globalConfig.language);   // the questions below are asked in the chosen language
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
      const trusted = await askConfirm({
        title: t('trust.title'),
        question: t('trust.question'),
        details: [cwd, ...(localConfig?.autoApprove === true ? [t('trust.wantsYolo')] : []), ...(localConfig?.bashAllowlist ? [t('trust.wantsAllowlist', { list: localConfig.bashAllowlist.join(', ') })] : [])],
        yes: t('trust.yes'),
        no: t('trust.no'),
      });

      if (!trusted) {
        resetTerminalTheme();
        printFarewell(t('trust.exiting'), 'warn');
        process.exit(0);
      }

      globalConfig.trustedProjects = globalConfig.trustedProjects || {};
      globalConfig.trustedProjects[cwd] = true;
      await saveConfig(globalConfig);
      isTrusted = true;
      log.success(pc.green('Workspace marked as trusted. Full capabilities enabled.'));
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
      const listing = projectMcpServers.map((srv) => `${srv.name}  (${srv.file})  ${`${srv.config.command} ${(srv.config.args || []).join(' ')}`.trim()}`);
      const allow = await askConfirm({ title: 'mcp', question: t('trust.mcpQuestion'), details: listing });
      if (allow) {
        globalConfig.trustedMcp[cwd] = fingerprint;
        await saveConfig(globalConfig);
        effectiveConfig.projectMcpTrusted = true;
      } else {
        console.log(renderNotice('info', 'Project MCP servers skipped. Your own servers still start.'));
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
  if (process.stdout.isTTY) process.stdout.write(EXTENDED_KEYS_OFF + '\x1B[?1004l' + RESTORE_TERMINAL_TITLE + '\x1B[?25h');
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
    process.stdout.write(EXTENDED_KEYS_OFF + '\x1B[?1004l' + RESTORE_TERMINAL_TITLE + '\x1B[?25h'); // restore cursor
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

/** `#ab526 · 2h ago · 12 msgs`: what tells two sessions with the same title apart. */
function sessionDetail(s: { id: string; updatedAt: number; messages: Array<{ role: string }> }): string {
  const count = s.messages.filter((m) => m.role !== 'system').length;
  return `#${s.id.split('_').pop()} · ${formatSessionTime(s.updatedAt)} · ${t('resume.msgs', { n: count })}`;
}

async function handleSessionDelete(bannerMode: BannerMode) {
  while (true) {
    const sessions = await SessionManager.listSessions();
    if (sessions.length === 0) {
      drawLogo(bannerMode);
      log.warn('No saved sessions found.');
      break;
    }

    const DELETE_ALL = '__all__';
    const chosen = await runFinder<string>({
      title: t('sessions.deleteTitle'),
      subtitle: `${sessions.length}`,
      placeholder: t('resume.placeholder'),
      labelWidth: 36,
      items: [
        { value: DELETE_ALL, label: t('sessions.deleteAll', { n: sessions.length }), pinned: true },
        ...sessions.map((s) => ({ value: s.id, label: s.title, detail: sessionDetail(s), keywords: `${s.id} ${s.model || ''}` })),
      ],
    });
    const chosenSessionId = chosen ?? '__cancel__';

    if (!chosenSessionId || chosenSessionId === '__cancel__') {
      drawLogo(bannerMode);
      log.info('Session deletion finished.');
      break;
    }

    if (chosenSessionId === '__all__') {
      const confirmAll = await askConfirm({ title: 'sessions', question: t('confirm.deleteAll', { n: sessions.length }) });
      if (confirmAll) {
        const count = await SessionManager.deleteAllSessions();
        drawLogo(bannerMode);
        log.success(`Deleted all ${count} sessions.`);
        break;
      }
      continue;
    }

    const target = sessions.find(s => s.id === chosenSessionId);
    const title = target ? target.title : chosenSessionId;
    const ok = await SessionManager.deleteSession(chosenSessionId);
    drawLogo(bannerMode);
    if (ok) {
      log.success(`Deleted session: "${title}"`);
    } else {
      log.error(`Failed to delete session: "${title}"`);
    }
  }
}

async function handleThemeSelect(config: any): Promise<void> {
  const selected = await chooseTheme(config.theme);
  if (selected) {
    config.theme = selected;
    await saveConfig(config);
    const th = setActiveTheme(selected);
    // No redraw of the screen: that would wipe the conversation. New output uses the new colours.
    if (config.ui?.layout === 'classic') log.success(th.boldFn(`Theme switched to ${th.colorFn(th.symbol)} ${th.name}!`));
    else console.log(renderNotice('ok', `theme ${th.name}`));
  }
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
    costLimit: Number(config.maxCostPerSession) || 0,
    language: getLanguage(),
  };

  // One panel, redrawn in place; every change is visible at once and saved when it closes.
  const final = await runSettings(initial, {
    version: DEVX_VERSION,
    onChange: (key, state) => {
      if (key === 'theme') setActiveTheme(state.themeId);
      if (key === 'language') setLanguage(state.language);
      if (key === 'memory') MemoryManager.enabled = state.memory;
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
  config.language = final.language;
  config.maxCostPerSession = final.costLimit;
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
  setLanguage(config.language);
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
  // -m and the project config are for this run: only what changes from here on is saved.
  markConfigBaseline(config);
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
    // A config from before the first-run setup had a theme step: ask it once.
    clearTerminalScreen();
    const selected = await chooseTheme(config.theme);
    if (selected) config.theme = selected;
    config.onboarded = true;
    await saveConfig(config);
  }

  if (config.theme) {
    setActiveTheme(config.theme);
  }
  enableDarkTheme(config.pureBlackTheme !== false);

  let history = new History();
  
  MemoryManager.enabled = config.enableMemory !== false;
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
  // ctrl+p and ctrl+o while the agent works: reasoning and tool output, collapsed unless switched on.
  let thinkingExpanded = false;
  let outputExpanded = false;
  // Everything said on screen so far, kept as items: ctrl+p and ctrl+o draw it again, folded or unfolded.
  const transcript: TranscriptItem[] = [];
  // Snapshots of the working folder before each turn of the agent (git): /undo puts back what changed, also by shell commands.
  const checkpoints: Checkpoint[] = [];
  // What the user allowed on the approval card for this session ("Session"); "Always" goes to the config.
  const permissionMemory = newPermissionMemory();
  // Commands the agent keeps running in the background (servers, watchers): shown in the prompt frame, listed by /tasks.
  const bg = getBackgroundManager();
  const seenBgFailures = new Set<string>();
  const bgSummary = () => backgroundSummary(bg, seenBgFailures);
  // The most a session may cost (USD, 0 = no limit); "continue" at the limit raises it for this session only.
  let costLimitRaised: number | null = null;
  let costWarned = false;
  const costLimit = () => costLimitRaised ?? (Number(config.maxCostPerSession) || 0);
  for (const name of (config.allowedTools || [])) permissionMemory.tools.add(name);
  // The name of the chat: in the title of the terminal window (`◈ Short title`) and in the list of sessions.
  let windowTitle = '';
  let titleAttempts = 0;
  let terminalFocused = true;   // updated by the terminal's focus reports while a task runs
  const setWindowTitle = (title: string) => {
    windowTitle = title;
    if (process.stdout.isTTY) process.stdout.write(terminalTitleSequence(title));
  };
  if (process.stdout.isTTY) process.stdout.write(SAVE_TERMINAL_TITLE);
  /** Clears the screen and draws the whole conversation again, folded or unfolded as the switches say. */
  const redrawChat = () => {
    drawLogo(config.banner || 'full');
    const rows = renderTranscript(transcript, { cols: process.stdout.columns || 80, thinkingExpanded, outputExpanded, diffColors: !!config.ui?.diffColors });
    if (rows.length > 0) console.log(rows.join('\n'));
  };

  // /clear and /new: leave the current conversation (it stays saved and can be resumed)
  // and start from a clean slate: history, cost, usage counters, undo stack, screen.
  const startFreshSession = async () => {
    sessionManager.startNewSession(config.model, planMode);
    // The tasks belonged to the conversation that is being left.
    await bg.stopAll().catch(() => 0);
    bg.clearFinished();
    bg.takeNotes();
    seenBgFailures.clear();
    transcript.length = 0;
    checkpoints.length = 0;
    windowTitle = '';
    titleAttempts = 0;
    if (process.stdout.isTTY) process.stdout.write(RESTORE_TERMINAL_TITLE + SAVE_TERMINAL_TITLE);
    history = new History();
    history.addMessage({ role: 'system', content: await buildSystemPrompt(planMode) });
    sessionCost.reset();
    costLimitRaised = null;
    costWarned = false;
    currentDraft = '';
    UsageTracker.getInstance().reset();
    globalSnapshotManager.reset();
    GitStatusCache.invalidate();
    drawLogo(config.banner || 'full');
  };
  const announce = (message: string) => {
    if (config.ui?.layout === 'classic') log.success(message);
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
    // Set when this turn starts by itself, from the news of a background task (not from the user).
    let wakeNotes = '';

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
          : t('composer.placeholder'),
        initialValue: currentDraft,
        // Between turns the same keys work at the prompt: the chat above is drawn again.
        onViewToggle: (kind) => {
          if (kind === 'thinking') thinkingExpanded = !thinkingExpanded;
          else outputExpanded = !outputExpanded;
          redrawChat();
        },
        planMode,
        isYolo: !!config.autoApprove,
        ui: config.ui,
        model: modelLabel(config),
        cwd: process.cwd(),
        branch: GitStatusCache.getStatus()?.branch,
        dirty: GitStatusCache.getStatus()?.isDirty,
        contextPercent: usagePercent,
        cost: sessionCost.total,
        costState: sessionCost.state(),
        background: bgSummary,
        subscribe: (signal) => {
          const onChange = () => signal('redraw');
          // A task ended or printed what the agent asked to hear about: the agent takes it up by itself.
          const onNote = () => signal(config.backgroundAutoWake === false ? 'redraw' : 'wake');
          bg.on('change', onChange);
          bg.on('note', onNote);
          return () => {
            bg.off('change', onChange);
            bg.off('note', onNote);
          };
        },
      });

      if (inputStr.startsWith('__TOGGLE_MODE__:')) {
        currentDraft = inputStr.slice('__TOGGLE_MODE__:'.length);
        planMode = !planMode;
        const newSys = await buildSystemPrompt(planMode);
        history.updateSystemPrompt(newSys);
        // Clean layout: the prompt frame changes colour and label, no extra line needed.
        if (config.ui?.layout === 'classic') {
          log.info(planMode 
            ? pc.bold(pc.cyan('Mode: PLAN (Architect & Planner) — Press Tab to switch to AGENT'))
            : pc.bold(pc.green('Mode: AGENT (Coder & Executor) — Press Tab to switch to PLAN'))
          );
        }
        continue;
      }

      if (inputStr.startsWith('__WAKE__:')) {
        // The prompt was closed by news from a background task: the agent answers it. What was typed stays.
        currentDraft = inputStr.slice('__WAKE__:'.length);
        wakeNotes = bg.takeNotes();
        if (!wakeNotes) continue;
        console.log(noteRows(wakeNotes).map((r) => renderNotice('info', r)).join('\n'));
        console.log();
        answer = wakeNotes;
      } else {
        currentDraft = '';

        if (inputStr === '__CANCEL__') {
          const confirmExit = await askConfirm({ title: t('confirm.exitTitle'), question: t('confirm.exit'), record: false });
          if (confirmExit) {
            resetTerminalTheme();
            printFarewell(t('confirm.goodbye'));
            break;
          } else {
            continue;
          }
        }

        answer = (inputStr as string).trim();
      }
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
        log.info(pc.cyan(`Executing custom command: ${pc.bold(customCmd.cmd)} (${customCmd.desc})`));
      } else {
        const VALID_COMMANDS = [
          '/new', '/reset', '/resume', '/session', '/sessions', '/history',
          '/theme', '/themes', '/usage', '/export', '/mcp',
          '/settings', '/update', '/model', '/provider', '/providers',
          '/plan', '/agent', '/image', '/serve', '/memory', '/permissions', '/copy', '/rename', '/title', '/rewind', '/undo',
          '/diff', '/commit', '/status', '/compact', '/init', '/doctor',
          '/config', '/clear', '/exit', '/quit', '/help', '/effort', '/tasks', '/bg', '/jobs'
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
            { name: '/tasks        - Background tasks (list, output, stop)', value: '/tasks' },
            { name: '/memory       - View or edit project memory bank', value: '/memory' },
            { name: '/permissions  - What is allowed without asking', value: '/permissions' },
            { name: '/copy         - Copy the last answer to the clipboard', value: '/copy' },
            { name: '/rename       - Rename the chat', value: '/rename' },
            { name: '/rewind       - Go back to one of your messages', value: '/rewind' },
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
            const picked = await runFinder<string>({
              title: t('commands.title'),
              subtitle: `${SLASH_COMMANDS.length}`,
              placeholder: t('commands.placeholder'),
              labelWidth: 14,
              items: SLASH_COMMANDS.map((c) => {
                const [name, ...rest] = c.name.split(/\s+-\s+/);
                return { value: c.value, label: name.trim(), detail: rest.join(' - ') };
              }),
            });

            if (!picked) {
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
        costLimitRaised = null;
        costWarned = false;
        // The project memory switch changes what the system prompt contains.
        history.updateSystemPrompt(await buildSystemPrompt(planMode));
        continue;
      }

      if (cmd === '/update') {
        const s = new UiSpinner();
        s.start('Checking for updates from https://github.com/apvcode/Termux-Dev...');
        const res = await checkForUpdates(10000);
        s.stop();
        if (res.updateAvailable) {
          log.info(pc.bold(pc.yellow(`Update available: v${res.currentVersion} → v${res.latestVersion}`)));
          const doUpdate = await askConfirm({ title: 'update', question: t('confirm.update', { version: res.latestVersion }), initial: true });
          if (doUpdate) {
            await performSelfUpdate(res.latestVersion);
          }
        } else {
          log.success(pc.green(`devx is up to date! (v${res.currentVersion})`));
        }
        continue;
      }

      if (cmd === '/help') {
        note('/settings - Configure permissions (Auto-Approve / Ask every time)\n/resume - Resume previous chat session\n/session del - Select and delete saved sessions\n/new - Start a new chat session (or /new <prompt>)\n/model - Switch model for current provider\n/effort - Set the reasoning effort (only levels the model supports)\n/provider - Switch AI provider (with saved keys)\n/config - View configuration\n/plan - Switch to plan mode\n/agent - Switch to agent mode\n/clear - Clear context and start a new session\n/exit - Exit', 'Commands');
        continue;
      }
      if (cmd === '/exit') {
        printFarewell(t('confirm.goodbye'));
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
          log.warn('No saved sessions found.');
          continue;
        }

        const picked = await runFinder<string>({
          title: t('resume.title'),
          subtitle: `${sessions.length}`,
          placeholder: t('resume.placeholder'),
          labelWidth: 36,
          items: sessions.map((s) => ({
            value: s.id,
            label: s.title,
            detail: sessionDetail(s),
            keywords: `${s.id} ${s.model || ''}`,
          })),
        });
        if (picked === undefined) continue;
        const chosenSessionId = picked;

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

          transcript.length = 0;
          titleAttempts = 0;
          if (loaded.title && loaded.title !== 'New Session') setWindowTitle(loaded.title);
          transcript.push(...messagesToItems(recentMessages));
          console.log(renderResumedMessages(recentMessages, {
            title: loaded.title,
            shown: shownCount,
            total: totalMsgs,
            cols: process.stdout.columns || 80,
            thinkingExpanded,
            outputExpanded,
          }).join('\n'));
        } else {
          log.error('Failed to load session.');
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
        // With git: the whole folder goes back to how it was before the last turn, shell commands included.
        const cp = checkpoints[checkpoints.length - 1];
        const diff = cp ? await diffCheckpoint(cp).catch(() => null) : null;
        if (cp && diff && diff.modified.length + diff.created.length + diff.deleted.length > 0) {
          const list = [
            ...diff.modified.map((f) => `changed  ${f}`),
            ...diff.deleted.map((f) => `deleted  ${f}`),
            ...diff.created.map((f) => `new      ${f}`),
          ];
          const shown = list.slice(0, 12);
          if (list.length > shown.length) shown.push(`… +${list.length - shown.length} more`);
          const ok = await askConfirm({
            title: 'undo',
            question: t('undo.question', { n: list.length }),
            details: [...shown, t('undo.note')],
            initial: true,
          });
          if (ok) {
            await restoreCheckpoint(cp, diff);
            checkpoints.pop();
            await globalSnapshotManager.undoLastTurn().catch(() => null);
            history.popLastTurn();
            log.success(t('undo.done', { n: list.length }));
          }
        } else {
          const { revertedFiles, count } = await globalSnapshotManager.undoLastTurn();
          if (count === 0) {
            log.warn('No changes to undo.');
          } else {
            history.popLastTurn();
            log.success(`Reverted changes in ${count} file(s)`);
            for (const f of revertedFiles) console.log(pc.cyan(`  • ${f}`));
            console.log(pc.dim('  Note: without git only edits made with the file tools are reverted, not those of shell commands.'));
          }
        }
        continue;
      }
      if (cmd === '/diff') {
        try {
          const diffOutput = execSync('git diff', { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
          if (!diffOutput.trim()) {
            log.info('No uncommitted changes in git repository.');
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
          log.warn('Git is not available or current directory is not a git repo.');
        }
        continue;
      }
      if (cmd === '/status') {
        try {
          const statusOutput = execSync('git status --short', { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
          if (!statusOutput.trim()) {
            log.success('Working directory clean, no modified files.');
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
          log.warn('Git is not available or current directory is not a git repo.');
        }
        continue;
      }
      if (cmd === '/commit') {
        try {
          const status = execSync('git status --short', { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
          if (!status.trim()) {
            log.info('No changes to commit.');
            continue;
          }
          const diff = execSync('git diff', { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
          const userMsg = parts.slice(1).join(' ').trim();
          let commitMsg = userMsg;

          if (!commitMsg) {
            const s = new UiSpinner();
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

          const confirmed = await askConfirm({ title: 'commit', question: t('confirm.commit'), details: [commitMsg], initial: true });

          if (confirmed) {
            execFileSync('git', ['add', '-A'], { stdio: 'ignore' });
            execFileSync('git', ['commit', '-m', commitMsg], { stdio: 'ignore' });
            log.success(pc.bold(pc.green(`Committed: ${commitMsg}`)));
          } else {
            log.info('Commit cancelled.');
          }
        } catch (err: any) {
          log.error(`Git commit failed: ${err.message}`);
        }
        continue;
      }
      if (cmd === '/compact') {
        const msgs = history.getMessages();
        if (msgs.length <= 2) {
          log.info('Conversation is already short, no compaction needed.');
          continue;
        }

        const s = new UiSpinner();
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
            content: `${COMPACT_MARK} Summary of our conversation so far:\n${res.content}`
          });
          const item: TranscriptItem = { kind: 'compact', summary: res.content };
          transcript.push(item);
          console.log();
          console.log(renderItem(item, { cols: process.stdout.columns || 80, thinkingExpanded, outputExpanded }).join('\n'));
        } catch (err: any) {
          s.stop();
          log.error(`Compaction failed: ${err.message}`);
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
          log.warn('AGENTS.md already exists in current workspace.');
        } else {
          const template = `# Project Developer Instructions (AGENTS.md)\n\n## Overview\nProject description and goals.\n\n## Tech Stack\n- TypeScript / Node.js\n\n## Coding Guidelines\n- Clean, modular code\n- Verify changes after editing\n`;
          await fs.writeFile(agentsPath, template, 'utf8');
          log.success(`Created ${pc.bold('AGENTS.md')} in project root! Customize it to give devx persistent instructions.`);
        }
        continue;
      }
      if (cmd === '/doctor') {
        await runDoctor(config);
        continue;
      }
      if (cmd === '/tasks' || cmd === '/bg' || cmd === '/jobs') {
        const rows = await runTasksCommand(parts.slice(1).filter(Boolean), bg, process.stdout.columns || 80, (ids) => ids.forEach((id) => seenBgFailures.add(id)));
        console.log(rows.join('\n'));
        console.log();
        continue;
      }
      if (cmd === '/serve') {
        const sub = parts[1]?.toLowerCase();
        if (sub === 'stop') {
          if (stopServer()) {
            log.success('Web server stopped.');
          } else {
            log.info('No web server is currently running.');
          }
        } else {
          const customPort = parseInt(parts[1], 10) || 3000;
          try {
            const { port, localUrl, networkUrl } = await startServer(customPort);
            await displayServerBanner(localUrl, networkUrl);
          } catch (err: any) {
            log.error(`Failed to start web server: ${err.message}`);
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
            log.warn(`Unknown theme: "${themeArg}". Available themes: ${themes.map(t => `${t.colorFn(t.symbol)} ${t.id}`).join(', ')}`);
          }
        } else {
          await handleThemeSelect(config);
        }
        continue;
      }
      if (cmd === '/rewind') {
        const msgs = history.getMessages();
        const entries = userEntries(msgs);
        if (entries.length === 0) {
          log.warn(t('rewind.none'));
          continue;
        }
        const chosen = await runPicker<number>({
          title: t('rewind.title'),
          subtitle: t('rewind.count', { n: entries.length }),
          items: rewindItems(msgs),
          current: REWIND_CURRENT,
          labelWidth: 54,
        });
        if (chosen === undefined || chosen === REWIND_CURRENT) continue;
        const target = msgs[chosen];

        // The folder can go back too, to how it was before that message.
        const cpIndex = target.checkpoint ? checkpoints.findIndex((c) => c.sha === target.checkpoint) : -1;
        if (cpIndex >= 0) {
          const diff = await diffCheckpoint(checkpoints[cpIndex]).catch(() => null);
          const total = diff ? diff.modified.length + diff.created.length + diff.deleted.length : 0;
          if (diff && total > 0) {
            const list = [...diff.modified.map((f) => `changed  ${f}`), ...diff.deleted.map((f) => `deleted  ${f}`), ...diff.created.map((f) => `new      ${f}`)];
            const shown = list.slice(0, 10);
            if (list.length > shown.length) shown.push(`… +${list.length - shown.length} more`);
            const restore = await askConfirm({ title: t('rewind.title'), question: t('rewind.filesQuestion', { n: total }), details: shown, yes: t('rewind.filesYes'), no: t('rewind.filesNo') });
            if (restore) await restoreCheckpoint(checkpoints[cpIndex], diff);
          }
          checkpoints.length = cpIndex;
        } else if (target.checkpoint) {
          checkpoints.length = 0;
        }

        // The conversation after the message is burned: the model forgets it as well.
        history.rewindBefore(chosen);
        const kept = history.getMessages().filter((m) => m.role !== 'system');
        transcript.length = 0;
        transcript.push(...messagesToItems(kept));
        await sessionManager.save(history.getMessages(), sessionCost.total, config.model, planMode, sessionCost.estimated);
        currentDraft = messageDraft(target.content);
        redrawChat();
        log.info(t((target.images && target.images.length > 0) ? 'rewind.doneImages' : 'rewind.done'));
        continue;
      }
      if (cmd === '/copy') {
        const last = [...history.getMessages()].reverse().find((m) => m.role === 'assistant' && m.content && m.content.trim());
        if (!last) {
          log.warn(t('copy.none'));
        } else {
          const method = await copyToClipboard(last.content);
          if (method === 'none') log.error(t('copy.failed'));
          else log.success(t('copy.done', { n: Array.from(last.content).length }));
        }
        continue;
      }
      if (cmd === '/rename' || cmd === '/title') {
        const name = cleanTitle(parts.slice(1).join(' '));
        if (name) {
          sessionManager.setTitle(name);
          setWindowTitle(name);
          await sessionManager.save(history.getMessages(), sessionCost.total, config.model, planMode, sessionCost.estimated);
          log.success(t('rename.done', { name }));
        } else {
          // Without a name the model names the chat again.
          const msgs = history.getMessages();
          const firstUser = msgs.find((m) => m.role === 'user')?.content ?? '';
          const reply = [...msgs].reverse().find((m) => m.role === 'assistant' && m.content && m.content.trim())?.content ?? '';
          if (!firstUser.trim() || !reply.trim()) {
            log.warn(t('rename.usage'));
          } else {
            const generated = await generateChatTitle(createProvider(config), firstUser.split('\n\n--- Attached Files ---')[0], reply);
            if (!generated) log.error(t('rename.failed'));
            else {
              sessionManager.setTitle(generated);
              setWindowTitle(generated);
              await sessionManager.save(history.getMessages(), sessionCost.total, config.model, planMode, sessionCost.estimated);
              log.success(t('rename.done', { name: generated }));
            }
          }
        }
        continue;
      }
      if (cmd === '/permissions') {
        if ((parts[1] || '').toLowerCase() === 'clear') {
          config.bashAllowlist = [];
          config.allowedTools = [];
          permissionMemory.bash.clear();
          permissionMemory.tools.clear();
          await saveConfig(config);
          log.success(t('permissions.cleared'));
        } else {
          const bash = Array.from(new Set([...(config.bashAllowlist || []), ...permissionMemory.bash]));
          const tools = Array.from(permissionMemory.tools);
          const rows = [
            ...bash.map((k) => `command   ${k}`),
            ...tools.map((k) => `tool      ${k}`),
          ];
          if (rows.length === 0) log.info(t('permissions.none'));
          else note(rows.join('\n'), t('permissions.title'));
          if (rows.length > 0) log.info(t('permissions.hint'));
        }
        continue;
      }
      if (cmd === '/memory') {
        const sub = parts[1]?.toLowerCase();
        if (sub === 'clear') {
          await MemoryManager.clearMemory();
          history.updateSystemPrompt(await buildSystemPrompt(planMode));
          log.success('Cleared project memory bank (.devx/memory.md).');
        } else if (sub === 'remove' || sub === 'rm') {
          const name = parts[2];
          if (!name) {
            console.log(renderNotice('warn', t('mcp.removeUsage')));
          } else if (!removeUserServer(name)) {
            console.log(renderNotice('warn', t('mcp.removeNotFound', { name })));
          } else {
            const sp = new UiSpinner();
            sp.start('Reloading MCP servers...');
            await MCPManager.getInstance().reload();
            sp.stop();
            console.log(renderNotice('ok', t('mcp.removed', { name })));
          }
        } else if (sub === 'add') {
          const fact = parts.slice(2).join(' ').trim();
          if (!fact) {
            log.warn('Usage: /memory add <fact or rule to remember>');
          } else {
            await MemoryManager.addFact(fact);
            // The model sees the new rule from the next message on, not only after a restart.
            history.updateSystemPrompt(await buildSystemPrompt(planMode));
            log.success(`Saved to memory: "${fact}"`);
          }
        } else {
          const mem = await MemoryManager.loadMemory();
          if (!mem.trim()) {
            log.info('Project memory is empty. AI will remember facts as you work, or use /memory add <fact>.');
          } else {
            note(mem, 'Project Memory Bank (.devx/memory.md)');
            const capped = memoryForPrompt(mem);
            if (capped.omitted > 0) log.warn(t('memory.capped', { n: capped.omitted }));
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
          log.success(pc.bold(pc.green(`Session exported to: ${res.filePath}`)));
        } else {
          log.error(`Failed to export session: ${res.error}`);
        }
        continue;
      }
      if (cmd === '/mcp') {
        const sub = (parts[1] || '').toLowerCase();
        if (sub === 'reload' || sub === 'restart' || sub === 'r') {
          const s = new UiSpinner();
          s.start('Reloading MCP servers...');
          await MCPManager.getInstance().reload();
          s.stop();
          console.log('\n' + MCPManager.getInstance().renderStatusCard());
        } else if (sub === 'import') {
          // The servers that Claude Code already knows (~/.claude.json) become devx servers.
          const found = readClaudeServers();
          if (Object.keys(found).length === 0) {
            console.log(renderNotice('warn', 'No servers found in ~/.claude.json'));
          } else {
            const added = saveUserServers(found);
            const skipped = Object.keys(found).filter((n) => !added.includes(n));
            const s = new UiSpinner();
            s.start('Starting MCP servers...');
            await MCPManager.getInstance().reload();
            s.stop();
            console.log(renderNotice('ok', added.length ? `imported: ${added.join(', ')} (saved to ~/.devx/mcp.json)` : 'nothing new to import'));
            if (skipped.length) console.log(renderNotice('info', `already in devx: ${skipped.join(', ')}`));
            console.log('\n' + MCPManager.getInstance().renderStatusCard());
          }
        } else if (sub === 'add') {
          // /mcp add <name> <command> [args...]
          const [name, command, ...rest] = parts.slice(2);
          if (!name || !command) {
            console.log(renderNotice('warn', 'Usage: /mcp add <name> <command|url> [args...]   e.g. /mcp add notion npx -y mcp-remote https://mcp.notion.com/mcp'));
          } else {
            // An address is a remote server (over HTTP); anything else is a program to start.
            saveUserServers({ [name]: /^https?:\/\//i.test(command) ? { url: command } : { command, ...(rest.length ? { args: rest } : {}) } }, true);
            const s = new UiSpinner();
            s.start(`Starting ${name}...`);
            await MCPManager.getInstance().reload();
            s.stop();
            const st = MCPManager.getInstance().getStatus(name);
            console.log(st?.status === 'connected'
              ? renderNotice('ok', `${name} connected, ${st.toolsCount} tools (saved to ~/.devx/mcp.json)`)
              : renderNotice('error', `${name}: ${st?.error || st?.status || 'not started'}`));
          }
        } else if (process.stdin.isTTY && process.stdout.isTTY) {
          // The panel erases itself when it is closed; the conversation above it stays where it is.
          await runMcpPanel(MCPManager.getInstance());
          process.stdin.resume();
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
        note(JSON.stringify(masked, null, 2), 'Configuration (Secrets Masked)');
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
          log.info('Model selection cancelled.');
        }
        continue;
      }
      if (cmd === '/effort') {
        const showSay = (say: Say) => {
          if (config.ui?.layout === 'classic') {
            const text = say.detail ? `${say.text}\n${say.detail}` : say.text;
            if (say.kind === 'ok') log.success(text);
            else if (say.kind === 'warn') log.warn(text);
            else log.info(text);
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
          log.info('Provider selection cancelled.');
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
            log.success(pc.bold(pc.green('Plan approved! Auto-switched to AGENT mode. Starting execution...')));
          }
        }
      }
    }

    // Resolve @file and @image mentions
    // News from a background task is output of a program, not a message of the user: no @file in it is read.
    const { text: cleanAnswer, attachments, images, warnings: attachWarnings } = wakeNotes
      ? { text: answer, attachments: [], images: [], warnings: [] as string[] }
      : await resolveAtMentions(answer);
    for (const warning of attachWarnings) log.warn(warning);
    let finalContent = cleanAnswer;

    if (attachments.length > 0) {
      const attachBlocks = attachments.map(a => 
        `[Attached file: ${a.path}]\n\`\`\`\n${a.content}\n\`\`\``
      ).join('\n\n');
      finalContent = `${cleanAnswer}\n\n--- Attached Files ---\n${attachBlocks}`;
      log.info(pc.cyan(`Attached ${attachments.length} file(s): ${attachments.map(a => a.path).join(', ')}`));
    }

    if (images.length > 0) {
      if (modelAcceptsImages(config.model) === false) {
        log.warn(`${config.model} cannot read images: ${images.length === 1 ? 'the image' : 'the images'} will not be sent. Switch to a model with vision (/model).`);
      } else {
        log.info(pc.cyan(`Attached ${images.length} image(s): ${images.map(i => path.basename(i.path)).join(', ')}`));
      }
    }

    // The session is over its cost limit: ask before spending more.
    if (costLimit() > 0 && sessionCost.total >= costLimit()) {
      const proceed = await askConfirm({
        title: t('cost.title'),
        question: t('cost.reached', { spent: formatCost(sessionCost.total), limit: formatCost(costLimit()) }),
        details: [t('cost.raise', { limit: formatCost(costLimit() * 2) })],
        yes: t('cost.continue'),
        no: t('cost.cancel'),
      });
      if (!proceed) {
        log.info(t('cost.cancelled'));
        currentDraft = answer;
        continue;
      }
      costLimitRaised = costLimit() * 2;
      costWarned = false;
    }

    globalSnapshotManager.beginTurn();
    let turnCheckpoint: Checkpoint | null = null;
    if (!planMode) {
      turnCheckpoint = await createCheckpoint(process.cwd(), checkpoints[checkpoints.length - 1]?.sha);
      if (turnCheckpoint) checkpoints.push(turnCheckpoint);
    }
    if (wakeNotes) transcript.push({ kind: 'raw', rows: noteRows(wakeNotes).map((r) => renderNotice('info', r)) });
    else transcript.push({ kind: 'user', text: answer });
    // Until the model has named the chat, the start of the first message stands in for the name.
    if (!wakeNotes && !windowTitle && !sessionManager.hasGeneratedTitle) setWindowTitle(provisionalTitle(answer));
    history.addMessage({
      role: 'user',
      content: finalContent,
      images: images.length > 0 ? images : undefined,
      at: Date.now(),
      checkpoint: turnCheckpoint?.sha,
    });

    // The format of the gateway is learned once (cached); the first request waits for it.
    await refreshFormats(config).catch(() => null);
    const provider = createProvider(config);
    const tools = getTools(planMode);
    const guard = new CLIConsoleGuard(config.autoApprove, [...(config.bashAllowlist || [])], {
      memory: permissionMemory,
      // "Always": kept in the config for the next sessions (`/permissions` lists and clears them).
      onAlways: (r) => {
        if (r.kind === 'bash') config.bashAllowlist = Array.from(new Set([...(config.bashAllowlist || []), r.key]));
        else config.allowedTools = Array.from(new Set([...(config.allowedTools || []), r.key]));
        void saveConfig(config).catch(() => null);
      },
    });
    const agentConfig: AgentConfig = {
      maxContextTokens: config.maxContextTokens || 100000,
      maxIterations: config.maxIterations || 100,
      autoApprove: config.autoApprove,
      bashAllowlist: config.bashAllowlist,
      hooks: config.hooks,
    };

    const agent = new Agent(agentConfig, provider, tools, history, guard);
    const taskStartTime = Date.now();
    const abortController = new AbortController();
    let aborted = false;
    let isConnecting = true;
    let spinnerActive = false;
    let thinkingActive = false;
    let thinkingStartTime = 0;
    // Thinking is collapsed by default: only a live line shows the newest words. ctrl+p shows it in full.
    let thinkingStreaming = false;
    let thoughtBuffer = '';
    let thoughtPrinted = 0;
    let liveShown = 0;   // how much of the reasoning the live line has flowed through
    let liveAt = 0;
    // The same for commands and their output (ctrl+o).
    let textActive = false;
    let assistantBuf = '';
    // The last finished reasoning waits for the reply it belongs to, to be saved with it.
    let pendingReasoning: { text: string; sec: string; stats: string } | null = null;
    let pendingRedraw = false;
    let gapBeforeTool = false;

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
      const rows = toolResultRows(name, result, { cols: process.stdout.columns || 80, expanded: outputExpanded, diffColors: !!config.ui?.diffColors });
      if (rows.length > 0) console.log(rows.join('\n'));
    };

    const telemetry = new LiveTelemetryTracker();
    telemetry.start('connecting');
    uiSpinner?.setMetaProvider(liveTokens);

    // Only a text that is being written holds it back (its line cannot be cut in the middle).
    /** The reasoning goes into the saved session with the reply it came before. */
    const attachReasoning = () => {
      if (!pendingReasoning) return;
      const messages = history.getMessages();
      for (let i = messages.length - 1; i >= 0; i--) {
        if (messages[i].role === 'assistant') {
          if (!messages[i].reasoning) {
            messages[i].reasoning = pendingReasoning;
            pendingReasoning = null;
          }
          return;
        }
        if (messages[i].role === 'user') return;   // the reply is not written yet
      }
    };
    const safeToRedraw = () => !textActive;

    /** Clears the screen and draws the whole conversation again, folded or unfolded as the switches say. */
    const redraw = () => {
      composer.suspend();
      // A reasoning that was being printed in full is part of the screen that is wiped: it starts again.
      if (thinkingActive) {
        thinkingActive = false;
        thoughtPrinted = 0;
        thoughtStreamer.reset();
        thoughtGutter.reset();
      }
      redrawChat();
      composer.resume();
    };
    const runPendingRedraw = () => {
      if (pendingRedraw && safeToRedraw()) {
        pendingRedraw = false;
        redraw();
      }
    };

    /** The assistant's text is complete: close its line and keep it in the conversation. */
    const endText = () => {
      if (textActive) {
        process.stdout.write('\n');
        textActive = false;
      }
      if (assistantBuf.trim()) transcript.push({ kind: 'assistant', text: assistantBuf });
      assistantBuf = '';
      runPendingRedraw();
    };

    const finishThinking = (extraNewline = true) => {
      if (!thinkingActive && !thinkingStreaming) return;
      if (thinkingActive) {
        thoughtStreamer.flush();
        thoughtGutter.flush();
      }
      const elapsedSec = thinkingStartTime > 0 ? ((Date.now() - thinkingStartTime) / 1000).toFixed(1) : '0.0';
      const teleStats = config.ui?.liveTelemetry && telemetry.getTotalTokens() > 0
        ? ` · ${LiveTelemetryTracker.formatTokenCount(telemetry.getTotalTokens())} tok · ${telemetry.getTokensPerSecond()} tok/s`
        : '';
      if (cleanUi && !thinkingActive) {
        // Folded: one quiet line instead of the whole reasoning.
        if (spacerBeforeText) process.stdout.write('\n');
        process.stdout.write(collapsedThoughtLine(`${t('status.thoughtFor', { sec: elapsedSec })}${teleStats}`, !!thoughtBuffer.trim(), process.stdout.columns || 80) + '\n');
        spacerBeforeText = true;
      } else if (cleanUi) {
        process.stdout.write('\n' + palette.faint(`  ╰ ${t('status.thoughtFor', { sec: elapsedSec })}${teleStats}`) + '\n');
        thoughtGutter.reset();
        spacerBeforeText = true;
      } else {
        const width = Math.min(process.stdout.columns || 40, 52);
        const label = `─── Thought for ${elapsedSec}s${teleStats} `;
        const fillLen = Math.max(2, width - label.length);
        process.stdout.write('\n\n' + pc.dim(label + '─'.repeat(fillLen)) + (extraNewline ? '\n\n' : '\n'));
      }
      if (cleanUi) {
        transcript.push({ kind: 'thought', text: thoughtBuffer, sec: elapsedSec, stats: teleStats });
        gapBeforeTool = true;
      }
      if (thoughtBuffer.trim()) pendingReasoning = { text: thoughtBuffer.trim(), sec: elapsedSec, stats: teleStats };
      thoughtBuffer = '';
      thoughtPrinted = 0;
      liveShown = 0;
      thinkingActive = false;
      thinkingStreaming = false;
      thinkingStartTime = 0;
      runPendingRedraw();
    };

    /** Prints the header of the reasoning block (once) and what has not been written of it yet. */
    const showThought = () => {
      if (!thinkingActive) {
        process.stdout.write((cleanUi ? (spacerBeforeText ? '\n' : '') : '\n') + (cleanUi ? palette.muted(`  ∴ ${t('status.thinkingLine')}`) : pc.bold(pc.white('› Thought:'))) + '\n');
        if (cleanUi) spacerBeforeText = false;
        thinkingActive = true;
      }
      if (thoughtBuffer.length > thoughtPrinted) {
        thoughtStreamer.push(thoughtBuffer.slice(thoughtPrinted));
        thoughtPrinted = thoughtBuffer.length;
      }
    };

    /**
     * The newest words of the reasoning on one line while it is folded: half the width of the
     * screen, the oldest words fading out on the left, an ellipsis at the end. The text flows
     * in a few characters at a time (faster the further it is behind) instead of jumping with
     * every chunk the model sends.
     */
    const liveThought = (): string => {
      if (!cleanUi || !thinkingStreaming || thinkingActive) return '';
      const text = thoughtBuffer.replace(/\s+/g, ' ').trimStart();
      const now = Date.now();
      const lag = text.length - liveShown;
      if (lag > 0) liveShown = Math.min(text.length, liveShown + Math.max(1, Math.ceil((now - liveAt) * Math.max(0.12, lag / 220))));
      else liveShown = text.length;
      liveAt = now;
      const cols = process.stdout.columns || 80;
      const hint = `  (${t('view.expandThought')})`;
      const width = Math.max(16, Math.min(Math.floor(cols / 2) - 2, cols - 8 - displayWidth(hint)));
      const tail = Array.from(tailWidth(text.slice(0, liveShown), width));
      if (tail.length === 0) return '';
      const third = Math.ceil(tail.length / 3);
      const old = tail.slice(0, third).join('');
      const mid = tail.slice(third, third * 2).join('');
      const fresh = tail.slice(third * 2).join('');
      return '  ' + palette.faint('∴') + ' ' + palette.faint(old) + palette.muted(mid) + palette.text(fresh) + palette.faint('…' + hint);
    };

    /** ctrl+p / ctrl+o: fold or unfold the reasoning / the tool output of the whole chat, also what is already above. */
    const toggleView = (kind: 'thinking' | 'output') => {
      if (!cleanUi) return;
      if (kind === 'thinking') thinkingExpanded = !thinkingExpanded;
      else outputExpanded = !outputExpanded;
      composer.refresh();
      if (safeToRedraw()) {
        redraw();
        // A thought that is being written goes on in full from its beginning.
        if (thinkingExpanded && thinkingStreaming) showThought();
      } else {
        pendingRedraw = true;
      }
    };

    // Ctrl+Enter: what the user typed goes to the agent right now, replacing the task it was on.
    let interruptPrompt = '';
    const stopGeneration = (quiet = false, reason?: string) => {
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
      endText();
      if (reason) {
        console.log(renderNotice('warn', reason));
        console.log();
      } else if (quiet) {
        // The message that caused the stop is echoed by the next turn: only a quiet separator here.
        if (cleanUi) console.log(renderNotice('info', t('stop.interrupted')));
        else console.log(pc.yellow('\n' + t('stop.classicInterrupted')));
        console.log();
      } else if (cleanUi) {
        console.log(renderNotice('warn', isConnecting ? t('stop.connection') : t('stop.stopped')));
        console.log();
      } else if (isConnecting) {
        console.log(pc.yellow('\n' + t('stop.classicConnection')));
      } else {
        console.log(pc.yellow('\n' + t('stop.classicStopped')));
      }
    };

    const onSigInt = () => {
      stopGeneration();
    };

    const sendQueuedNow = () => {
      if (aborted) return;
      interruptPrompt = composer.takeQueued().join('\n');
      if (interruptPrompt) stopGeneration(true);
    };

    // The input line at the bottom while the agent works: what is typed there is queued and
    // reaches the model at its next step (see TurnComposer).
    // The working line (time and tokens) stays above the frame for the whole turn.
    let statusLabel = t('status.waiting');
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
        background: bgSummary(),
      };
    }, undefined, statusLine, sendQueuedNow);
    // A task started or ended during the turn: the frame shows the new count.
    const onBgChange = () => composer.refresh();
    bg.on('change', onBgChange);
    composer.live = liveThought;
    composer.onToggle = toggleView;
    const releasePauseHook = registerPauseHook(() => {
      composer.suspend();
      return () => composer.resume();
    });

    const onRawData = (data: Buffer | string) => {
      let text = (Buffer.isBuffer(data) ? data : Buffer.from(String(data))).toString('utf8');
      // Focus reports: ESC [ I (got the focus) and ESC [ O (lost it). They are not keys.
      if (text.includes('\x1b[I') || text.includes('\x1b[O')) {
        terminalFocused = text.lastIndexOf('\x1b[I') > text.lastIndexOf('\x1b[O');
        text = text.replace(/\x1b\[[IO]/g, '');
        if (!text) return;
      }
      // Ctrl+C is 0x03, or `CSI 99;5u` while the extended keyboard modes are on.
      if (text.includes('\x03') || splitKeys(text).some((k) => normalizeKey(k) === '\x03')) {
        stopGeneration();
        return;
      }
      composer.handleData(text);
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
          process.stdout.write('\x1b[?1004h');   // the terminal tells when it loses or gets the focus
        }
      } catch {}
    }

    s.start(cleanUi ? t('status.waiting') : 'Connecting...');
    spinnerActive = true;

    const turnStartTime = Date.now();
    let toolsExecutedCount = 0;

    try {
      for await (const event of agent.run(abortController.signal, {
        takePending: async () => Promise.all(composer.takeQueued().map(queuedToMessage)),
        hasPending: () => composer.hasQueued(),
        takeNotes: () => bg.takeNotes(),
        hasNotes: () => bg.hasNotes(),
      })) {
        if (aborted) break;
        if (event.type === 'reasoning_delta') {
          isConnecting = false;
          telemetry.setPhase('thinking');
          statusLabel = t('status.thinking');
          telemetry.recordTokens(telemetry.estimateTokensFromText(event.delta));
          requestOutEstimate += telemetry.estimateTokensFromText(event.delta);
          if (spinnerActive) {
            s.stop();
            spinnerActive = false;
          }
          if (!thinkingStreaming) {
            thinkingStreaming = true;
            thinkingStartTime = Date.now();
            thoughtBuffer = '';
            thoughtPrinted = 0;
            liveShown = 0;
            liveAt = Date.now();
          }
          thoughtBuffer += event.delta;
          if (!cleanUi || thinkingExpanded) showThought();
        } else if (event.type === 'text_delta') {
          isConnecting = false;
          telemetry.setPhase('generating');
          statusLabel = t('status.writing');
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
          assistantBuf += event.delta;
          gapBeforeTool = false;
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
              transcript.push({ kind: 'assistant', text: event.content });
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
          // The model has started on a tool call: its text is complete. (A long write can take minutes, and
          // ctrl+p / ctrl+o must work meanwhile: they wait only for a text that is still being written.)
          if (textActive) {
            streamer.finish();
            endText();
          }
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
          endText();
          statusLabel = t('status.waiting');
          const typed = event.content.split('\n\n--- Attached Files ---')[0];
          transcript.push({ kind: 'user', text: typed });
          console.log();
          console.log(renderUserEcho(typed, process.stdout.columns || 80).join('\n'));
          console.log();
          s.start(cleanUi ? t('status.waiting') : 'Connecting...');
          spinnerActive = true;
        } else if (event.type === 'tool_start') {
          attachReasoning();
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
          endText();
          if (cleanUi) {
            if (gapBeforeTool) console.log();
            gapBeforeTool = false;
            transcript.push({ kind: 'tool', name: event.name, desc: event.actionDesc || event.name });
            console.log(renderToolLine(event.actionDesc, process.stdout.columns || 80, outputExpanded));
            assistantGutter.reset();
          } else {
            console.log('\n' + pc.bold(pc.white(event.actionDesc)));
          }
          if (event.name !== 'ask_questions' && event.name !== 'todo_list') {
            s.start(cleanUi ? t('status.running') : 'Executing...');
            spinnerActive = true;
          }
        } else if (event.type === 'tool_end') {
          for (let i = transcript.length - 1; i >= 0; i--) {
            const it = transcript[i];
            if (it.kind === 'tool' && it.result === undefined) { it.result = event.result || ''; break; }
          }
          if (event.name === 'ask_questions') composer.resume();
          statusLabel = t('status.waiting');
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
          attachReasoning();
          sessionCost.add(event.usage);
          // The cost limit: a warning at 80%, the task is stopped at 100%.
          const limit = costLimit();
          if (limit > 0 && !aborted) {
            if (sessionCost.total >= limit) {
              stopGeneration(false, t('cost.stopped', { limit: formatCost(limit) }));
            } else if (!costWarned && sessionCost.total >= limit * 0.8) {
              costWarned = true;
              console.log(renderNotice('warn', t('cost.warn', { spent: formatCost(sessionCost.total), limit: formatCost(limit) })));
            }
          }
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
          statusLabel = t('status.reconnecting');
          telemetry.reset();
          if (spinnerActive) {
            s.stop();
            spinnerActive = false;
          }
          if (thinkingActive || thinkingStreaming) {
            finishThinking(false);
          }
          endText();
          streamer.reset();

          if (cleanUi) {
            console.log(renderNotice('warn', t('conn.unstable', { reason: event.reason })));
            s.start(t('conn.reconnecting', { attempt: event.attempt, max: event.maxAttempts, delay: (event.delayMs / 1000).toFixed(1) }));
          } else {
            console.log();
            console.log(pc.bold(pc.yellow(t('conn.classicUnstable', { reason: event.reason }))));
            console.log(pc.cyan(t('conn.classicRetry', { attempt: event.attempt, max: event.maxAttempts, delay: (event.delayMs / 1000).toFixed(1) })));
            
            s.start(pc.cyan(t('conn.classicWaiting', { attempt: event.attempt, max: event.maxAttempts })));
          }
          spinnerActive = true;
        } else if (event.type === 'reconnected') {
          statusLabel = t('status.waiting');
          if (spinnerActive) {
            s.stop();
            spinnerActive = false;
          }
          if (cleanUi) {
            console.log(renderNotice('ok', t('conn.restored')));
          } else {
            console.log(pc.bold(pc.green(t('conn.classicRestored'))));
            console.log();
          }
          s.start(cleanUi ? t('status.waiting') : 'Connecting...');
          spinnerActive = true;
        } else if (event.type === 'error') {
          if (spinnerActive) { s.stop(); spinnerActive = false; }
          finishThinking(true);
          streamer.finish();
          if (cleanUi) console.log(renderNotice('error', event.message));
          else log.error(pc.bold(pc.red(`${event.message}`)));
        } else if (event.type === 'system') {
          if (spinnerActive) { s.stop(); spinnerActive = false; }
          if (cleanUi) console.log(renderNotice('info', event.message));
          else log.info(event.message);
        } else if (event.type === 'background_note') {
          // A background task ended or printed what the agent asked to hear about.
          if (spinnerActive) { s.stop(); spinnerActive = false; }
          finishThinking(true);
          streamer.finish();
          endText();
          const rows = noteRows(event.content);
          transcript.push({ kind: 'raw', rows: rows.map((r) => renderNotice('info', r)) });
          for (const row of rows) console.log(cleanUi ? renderNotice('info', row) : pc.dim(row));
          s.start(cleanUi ? t('status.waiting') : 'Connecting...');
          spinnerActive = true;
        }
      }
      finishThinking(true);
      streamer.finish();
      if (spinnerActive) {
        s.stop();
        spinnerActive = false;
      }
      endText();

      // Hand the bottom of the screen back. Whatever was typed but not sent waits in the next prompt;
      // a message that arrived too late for the agent starts the next turn by itself.
      bg.off('change', onBgChange);
      const leftover = composer.stop();
      if (interruptPrompt) {
        autoTriggerPrompt = interruptPrompt;
        if (leftover.draft) currentDraft = leftover.draft;
      } else if (leftover.queued.length > 0) {
        if (aborted) currentDraft = [...leftover.queued, leftover.draft].filter(Boolean).join(' ');
        else autoTriggerPrompt = leftover.queued.join('\n');
      } else if (leftover.draft) {
        currentDraft = leftover.draft;
      }

      attachReasoning();
      const turnElapsedMs = Date.now() - turnStartTime;
      if (cleanUi && !aborted) {
        const summaryRow = renderTurnSummary({
          ms: turnElapsedMs,
          tools: toolsExecutedCount,
          outputTokens: turnOutputTokens,
          inputTokens: turnInputTokens,
          cost: sessionCost.total - costAtTurnStart,
          costState: turnCost
        });
        console.log(summaryRow);
        console.log();
        transcript.push({ kind: 'raw', rows: [summaryRow] });
      }
      const shouldNotify = (turnElapsedMs >= 15000) || (toolsExecutedCount > 0) || (planMode && lastPlanReady !== null);
      if (!aborted) {
        const secs = Math.round(turnElapsedMs / 1000);
        const lastReply = [...history.getMessages()].reverse().find((m) => m.role === 'assistant' && m.content && m.content.trim());
        const preview = notificationBody(lastReply?.content ?? '');
        const heading = windowTitle || BASE_TITLE;
        if (isTermux()) {
          if (shouldNotify) notifyDevice(heading, preview || `Task completed in ${secs}s!`);
        } else if (!terminalFocused) {
          // The terminal is not in front: a notification with the start of the answer.
          if (sendDesktopNotification(heading, preview || `Task completed in ${secs}s`) === 'none' && shouldNotify) notifyDevice(heading, preview || `Task completed in ${secs}s!`);
        } else if (shouldNotify) {
          notifyDevice(heading, `Task completed in ${secs}s!`);
        }
      }

      // Auto-save session
      await sessionManager.save(history.getMessages(), sessionCost.total, config.model, planMode, sessionCost.estimated);

      // The first exchange names the chat. It runs in the background and never holds the prompt back.
      if (!aborted && !sessionManager.hasGeneratedTitle && titleAttempts < 2) {
        titleAttempts++;
        const msgs = history.getMessages();
        const firstUser = msgs.find((m) => m.role === 'user')?.content ?? '';
        const reply = [...msgs].reverse().find((m) => m.role === 'assistant' && m.content && m.content.trim())?.content ?? '';
        if (firstUser.trim() && reply.trim()) {
          const forSession = sessionManager.getSession().id;
          void (async () => {
            try {
              const title = await generateChatTitle(createProvider(config), firstUser.split('\n\n--- Attached Files ---')[0], reply);
              // The user may have started another chat meanwhile.
              if (!title || sessionManager.getSession().id !== forSession) return;
              sessionManager.setTitle(title);
              setWindowTitle(title);
              await sessionManager.save(history.getMessages(), sessionCost.total, config.model, planMode, sessionCost.estimated);
            } catch {}
          })();
        }
      }

      // Check if plan was finalized in PLAN mode
      if (planMode && !aborted) {
        const lastMsg = history.getMessages().slice(-1)[0];
        const hasPlanReadyTool = lastPlanReady !== null;
        const hasPlanText = lastMsg && lastMsg.role === 'assistant' && (
          lastMsg.content.includes('Цель и архитектурный обзор') ||
          lastMsg.content.includes('Goal and architecture') ||
          lastMsg.content.includes('Step-by-step changes') ||
          lastMsg.content.includes('Пошаговый список изменений') ||
          lastMsg.content.includes('FINAL PLAN') ||
          lastMsg.content.includes('План готов') ||
          lastMsg.content.includes('Архитектурный план') ||
          lastMsg.content.includes('план готов')
        );

        if (hasPlanReadyTool || hasPlanText) {
          const approvedPlan = lastPlanReady ? { summary: lastPlanReady.summary, files: lastPlanReady.files, steps: lastPlanReady.steps } : (hasPlanText && lastMsg ? lastMsg.content : null);
          resetPlanReady();
          console.log();
          const choice = await askPlanDecision();

          if (choice === 'go') {
            planMode = false;
            // The approved plan is kept in the project: it survives /clear and a restart.
            if (approvedPlan) await savePlanFile(process.cwd(), approvedPlan).catch(() => null);
            const newSys = await buildSystemPrompt(false);
            history.updateSystemPrompt(newSys);
            log.success(t('plan.started'));
            currentDraft = '';
            autoTriggerPrompt = 'Go!';
            continue;
          } else {
            log.info(t('plan.editHint'));
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
        else log.error(pc.bold(pc.red(`${err.message}`)));
      }
    } finally {
      bg.off('change', onBgChange);
      const unsent = composer.stop();
      if (process.stdout.isTTY) process.stdout.write('\x1b[?1004l');
      if (interruptPrompt && !autoTriggerPrompt) autoTriggerPrompt = interruptPrompt;
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
  // The cards leave stdin resumed; nothing else keeps devx alive after the main loop, so end it here.
  if (process.stdin.isTTY) {
    try { process.stdin.setRawMode(false); } catch {}
  }
  process.stdin.pause();
  process.exit(0);
}

process.on('exit', () => {
  try {
    MCPManager.getInstance().stopAll();
  } catch {}
});

main().catch((err) => reportRuntimeError(err, activeAbortHandler));
