import { Command } from 'commander';
import * as p from '@clack/prompts';
import { search, password, input, select } from '@inquirer/prompts';
import pc from 'picocolors';
import os from 'os';
import path from 'path';
import fs from 'fs/promises';
import fsSync from 'fs';
import { execSync, execFileSync } from 'child_process';
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
import { getModelContextLimit } from '../core/models.js';
import { SessionManager } from '../core/session.js';
import { MarkdownStreamer, renderMarkdown } from './markdown.js';
import { SmoothStreamer } from './smooth.js';
import { askPrompt } from './prompt.js';
import { resolveAtMentions } from './files.js';
import { runStartupUpdateCheck, checkForUpdates, performSelfUpdate } from './updater.js';
import { setActiveTheme, getCurrentTheme, listThemes, findTheme } from './theme.js';
import { runHeadlessMode } from './headless.js';
import { CustomCommandManager } from '../core/commands.js';
import { UsageTracker } from '../core/usage.js';
import { SessionExporter } from './export.js';
import { MCPManager } from '../mcp/manager.js';
const CONFIG_PATH = path.join(os.homedir(), '.devxrc.json');
function maskApiKey(key) {
    if (!key)
        return '';
    if (key.length <= 8)
        return '****';
    return `${key.slice(0, 4)}...${key.slice(-4)}`;
}
async function saveConfig(config) {
    config.apiKeys = config.apiKeys || {};
    config.baseUrls = config.baseUrls || {};
    if (config.provider && config.apiKey) {
        config.apiKeys[config.provider] = config.apiKey;
    }
    if (config.provider && config.baseUrl) {
        config.baseUrls[config.provider] = config.baseUrl;
    }
    await fs.writeFile(CONFIG_PATH, JSON.stringify(config, null, 2), 'utf8');
}
async function fetchModels(baseUrl, apiKey) {
    try {
        const url = baseUrl.endsWith('/') ? `${baseUrl}models` : `${baseUrl}/models`;
        const headers = {};
        if (apiKey && apiKey !== 'ollama')
            headers['Authorization'] = `Bearer ${apiKey}`;
        const res = await fetch(url, { headers });
        if (!res.ok) {
            if (res.status === 401 || res.status === 403) {
                return { ok: false, status: res.status, models: [], error: 'Invalid API Key / Unauthorized' };
            }
            return { ok: false, status: res.status, models: [], error: `HTTP ${res.status} ${res.statusText}` };
        }
        const data = await res.json();
        if (data && data.data && Array.isArray(data.data)) {
            const list = data.data.map((m) => ({
                id: m.id,
                name: m.name || m.id,
                contextLength: m.context_length || m.max_context_length || m.limit?.context || m.limit?.input
            }));
            return { ok: true, status: res.status, models: list };
        }
        return { ok: true, status: res.status, models: [] };
    }
    catch (e) {
        return { ok: false, status: 0, models: [], error: e.message || 'Network error' };
    }
}
const POPULAR_PROVIDER_MODELS = {
    google: ['gemini-2.5-flash', 'gemini-2.5-pro', 'gemini-1.5-flash', 'gemini-1.5-pro'],
    openai: ['gpt-4o', 'gpt-4o-mini', 'o3-mini', 'o1', 'gpt-4-turbo'],
    openrouter: ['google/gemini-2.5-flash', 'anthropic/claude-3.7-sonnet', 'openai/gpt-4o', 'deepseek/deepseek-r1', 'meta-llama/llama-3.3-70b-instruct'],
    anthropic: ['claude-3-7-sonnet-20250219', 'claude-3-5-sonnet-20241022', 'claude-3-5-haiku-20241022'],
    deepseek: ['deepseek-chat', 'deepseek-reasoner'],
    groq: ['llama-3.3-70b-versatile', 'llama-3.1-8b-instant', 'mixtral-8x7b-32768'],
    mistral: ['mistral-large-latest', 'mistral-small-latest', 'codestral-latest'],
};
async function selectModel(baseUrl, apiKey, currentModel, providerId = '', allowCancel = true) {
    const s = p.spinner();
    s.start('Fetching available models from provider...');
    const result = await fetchModels(baseUrl, apiKey);
    s.stop();
    let models = result.models;
    if (models.length === 0 && providerId && POPULAR_PROVIDER_MODELS[providerId]) {
        models = POPULAR_PROVIDER_MODELS[providerId].map(id => ({
            id,
            name: id,
            contextLength: getModelContextLimit(id)
        }));
    }
    let modelChoice;
    if (models.length > 0) {
        try {
            modelChoice = await search({
                message: `Select a model (${models.length} available, type to filter):`,
                source: async (term) => {
                    const q = (term || '').trim().toLowerCase();
                    const list = [];
                    if (allowCancel) {
                        list.push({ name: pc.yellow('⬅️  Back / Cancel'), value: '__cancel__', description: 'Keep current model' });
                    }
                    list.push({ name: pc.yellow('✏️  Type a custom model name...'), value: 'custom_input', description: 'Enter any model ID manually' });
                    for (const m of models) {
                        const isCurrent = m.id === currentModel;
                        const prefix = isCurrent ? pc.cyan('› ') : '  ';
                        const nameStr = isCurrent ? pc.bold(pc.cyan(m.id + ' (current)')) : m.id;
                        const ctxStr = m.contextLength ? `${m.contextLength >= 1_000_000 ? (m.contextLength / 1_000_000).toFixed(1) + 'M' : Math.round(m.contextLength / 1000) + 'k'} context` : '';
                        list.push({
                            name: prefix + nameStr,
                            value: m.id,
                            description: ctxStr
                        });
                    }
                    if (!q)
                        return list;
                    return list.filter(item => item.value.toLowerCase().includes(q) || item.name.toLowerCase().includes(q));
                },
                pageSize: 10
            });
        }
        catch {
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
                if (!customModel || !customModel.trim())
                    return null;
                const name = customModel.trim();
                return { model: name, contextLimit: getModelContextLimit(name) };
            }
            catch {
                return null;
            }
        }
        const found = models.find(m => m.id === modelChoice);
        const limit = (found && found.contextLength) || getModelContextLimit(modelChoice);
        return { model: modelChoice, contextLimit: limit };
    }
    else {
        p.log.warn('Could not fetch models automatically.');
        try {
            const typedModel = await input({
                message: 'Type the model name you want to use (e.g. gpt-4o):',
                default: currentModel || ''
            });
            if (!typedModel || !typedModel.trim())
                return null;
            const name = typedModel.trim();
            return { model: name, contextLimit: getModelContextLimit(name) };
        }
        catch {
            return null;
        }
    }
}
async function selectProvider(currentConfig, allowCancel = true) {
    const apiKeys = currentConfig.apiKeys || {};
    const baseUrls = currentConfig.baseUrls || {};
    if (currentConfig.provider && currentConfig.apiKey) {
        apiKeys[currentConfig.provider] = currentConfig.apiKey;
    }
    let providerId;
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
                        name: pc.yellow('⬅️  Back / Cancel'),
                        value: '__cancel__',
                        description: 'Keep current provider'
                    });
                }
                return list;
            },
            pageSize: 10
        });
    }
    catch {
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
            if (!customUrl || !customUrl.trim())
                return null;
            baseUrl = customUrl.trim();
            baseUrls[providerId] = baseUrl;
        }
        catch {
            return null;
        }
    }
    let apiKey = '';
    if (providerId === 'ollama') {
        apiKey = 'ollama';
    }
    else {
        const savedKey = apiKeys[providerId];
        if (savedKey) {
            let keyChoice;
            try {
                keyChoice = await search({
                    message: `API key found for ${selectedProvider.label} (${maskApiKey(savedKey)}):`,
                    source: async () => [
                        { name: `🟢 Continue with saved API key (${maskApiKey(savedKey)})`, value: 'use_saved' },
                        { name: '✏️  Enter a new API key', value: 'new_key' },
                        { name: pc.yellow('⬅️  Cancel'), value: '__cancel__' }
                    ],
                    pageSize: 5
                });
            }
            catch {
                return null;
            }
            if (keyChoice === '__cancel__')
                return null;
            if (keyChoice === 'use_saved') {
                apiKey = savedKey;
            }
            else {
                try {
                    const key = await password({
                        message: `Enter new API Key for ${selectedProvider.label}:`,
                        mask: '•'
                    });
                    if (!key || !key.trim())
                        return null;
                    apiKey = key.trim();
                    apiKeys[providerId] = apiKey;
                }
                catch {
                    return null;
                }
            }
        }
        else {
            try {
                const key = await password({
                    message: `Enter API Key for ${selectedProvider.label} (or press Esc to cancel):`,
                    mask: '•'
                });
                if (!key || !key.trim())
                    return null;
                apiKey = key.trim();
                apiKeys[providerId] = apiKey;
            }
            catch {
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
async function runOnboarding() {
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
async function loadConfig(interactive = true) {
    let globalConfig = {};
    try {
        const data = await fs.readFile(CONFIG_PATH, 'utf8');
        globalConfig = JSON.parse(data);
        if (!globalConfig.model || !globalConfig.baseUrl)
            throw new Error('Invalid config');
        globalConfig.maxContextTokens = globalConfig.maxContextTokens || getModelContextLimit(globalConfig.model);
        globalConfig.apiKeys = globalConfig.apiKeys || {};
        globalConfig.baseUrls = globalConfig.baseUrls || {};
        globalConfig.trustedProjects = globalConfig.trustedProjects || {};
        if (globalConfig.provider && globalConfig.apiKey) {
            globalConfig.apiKeys[globalConfig.provider] = globalConfig.apiKey;
        }
    }
    catch {
        if (interactive) {
            globalConfig = await runOnboarding();
        }
        else {
            throw new Error('No configuration found in ~/.devxrc.json. Please run devx interactively first to set up your AI provider.');
        }
    }
    // Check for local project config (.devx.json or .devxrc.json)
    const localConfigPaths = [
        path.join(process.cwd(), '.devx.json'),
        path.join(process.cwd(), '.devxrc.json')
    ];
    let localConfig = null;
    for (const lp of localConfigPaths) {
        try {
            if (fsSync.existsSync(lp)) {
                const raw = await fs.readFile(lp, 'utf8');
                localConfig = JSON.parse(raw);
                break;
            }
        }
        catch { }
    }
    const effectiveConfig = { ...globalConfig };
    const cwd = process.cwd();
    let isTrusted = globalConfig.trustedProjects?.[cwd];
    // Workspace & Project Trust Check on first time opening this directory
    if (isTrusted === undefined) {
        if (interactive) {
            console.log('');
            p.log.warn(pc.bold(pc.yellow(`🛡️  [WORKSPACE TRUST] First time opening this workspace:`)));
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
                initialValue: true
            });
            if (p.isCancel(answer) || answer !== true) {
                resetTerminalTheme();
                p.outro(pc.yellow('Workspace not trusted. Exiting devx.'));
                process.exit(0);
            }
            globalConfig.trustedProjects = globalConfig.trustedProjects || {};
            globalConfig.trustedProjects[cwd] = true;
            await saveConfig(globalConfig);
            p.log.success(pc.green('Workspace marked as trusted. Full capabilities enabled.'));
        }
        else {
            isTrusted = false; // Never auto-trust in non-interactive headless mode
        }
    }
    if (localConfig && typeof localConfig === 'object') {
        // Apply safe configuration overrides
        if (localConfig.model)
            effectiveConfig.model = localConfig.model;
        if (localConfig.provider)
            effectiveConfig.provider = localConfig.provider;
        if (localConfig.apiKey)
            effectiveConfig.apiKey = localConfig.apiKey;
        if (localConfig.baseUrl)
            effectiveConfig.baseUrl = localConfig.baseUrl;
        if (localConfig.maxIterations)
            effectiveConfig.maxIterations = localConfig.maxIterations;
        if (localConfig.maxContextTokens)
            effectiveConfig.maxContextTokens = localConfig.maxContextTokens;
        if (localConfig.dataSaverLimitMB)
            effectiveConfig.dataSaverLimitMB = localConfig.dataSaverLimitMB;
        if (localConfig.pureBlackTheme !== undefined)
            effectiveConfig.pureBlackTheme = localConfig.pureBlackTheme;
        // Apply elevated permissions only if explicitly trusted
        if (isTrusted) {
            if (localConfig.autoApprove !== undefined)
                effectiveConfig.autoApprove = localConfig.autoApprove;
            if (Array.isArray(localConfig.bashAllowlist))
                effectiveConfig.bashAllowlist = localConfig.bashAllowlist;
        }
    }
    // Configure Data Saver threshold
    if (effectiveConfig.dataSaverLimitMB) {
        UsageTracker.getInstance().setLimit(effectiveConfig.dataSaverLimitMB);
    }
    return effectiveConfig;
}
function enableDarkTheme(enabled = true) {
    if (!enabled)
        return;
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
let activeAbortHandler = null;
process.on('exit', () => {
    if (process.stdin.isTTY) {
        process.stdin.setRawMode(false);
    }
    process.stdout.write('\x1B[?25h');
    resetTerminalTheme();
});
process.on('SIGINT', () => {
    if (activeAbortHandler) {
        activeAbortHandler();
    }
    else {
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
    }
    else {
        console.clear();
    }
}
function drawLogo() {
    const cols = process.stdout.columns || 80;
    clearTerminalScreen();
    const theme = getCurrentTheme();
    if (cols < 56) {
        // Ultra-clean compact ASCII for small mobile screens (width: ~26 chars)
        const logo = [
            '',
            theme.colorFn('  █▀▀▄ █▀▀▀ █   █ █   █'),
            theme.colorFn('  █  █ █▀▀▀  ▀▄▀   ▀▄▀ '),
            theme.colorFn('  █▄▄▀ █▄▄▄   ▀    ▀ ▀ '),
            '  ' + theme.boldFn('v1.4.15'),
            ''
        ];
        for (const line of logo) {
            console.log(line);
        }
    }
    else {
        // Full TERMUX-DEV banner (width: 53 chars)
        const indent = cols < 68 ? ' ' : '   ';
        const logo = [
            '',
            indent + theme.colorFn('▀▀▀█▀▀▀ █▀▀▀ █▀▀█ █▄ ▄█ █  █ ▀▄ ▄▀    █▀▀▄ █▀▀▀ █   █'),
            indent + theme.colorFn('   █    █▀▀▀ █▄▄▀ █ █ █ █  █   █   ▀▀ █  █ █▀▀▀ █   █'),
            indent + theme.colorFn('   █    █▄▄▄ █ ▀▄ █   █ ▀▄▄▀ ▄▀ ▀▄    █▄▄▀ █▄▄▄  ▀▄▀ '),
            indent + theme.boldFn('v1.4.15'),
            ''
        ];
        for (const line of logo) {
            console.log(line);
        }
    }
    console.log();
}
function formatSessionTime(timestamp) {
    const diff = Date.now() - timestamp;
    const mins = Math.floor(diff / 60000);
    if (mins < 1)
        return 'just now';
    if (mins < 60)
        return `${mins}m ago`;
    const hours = Math.floor(mins / 60);
    if (hours < 24)
        return `${hours}h ago`;
    const days = Math.floor(hours / 24);
    return `${days}d ago`;
}
function formatToolGeneratingLabel(name, targetHint) {
    const t = name.toLowerCase();
    const target = targetHint ? ` ${targetHint}` : '';
    if (t === 'read_file' || t === 'read')
        return `→ Read${target || '...'}`;
    if (t === 'write_file' || t === 'write')
        return `→ Write${target || '...'}`;
    if (t === 'edit_file' || t === 'patch')
        return `→ Edit${target || '...'}`;
    if (t === 'delete_file' || t === 'remove_file' || t === 'rm')
        return `→ Delete${target || '...'}`;
    if (t === 'bash' || t === 'run_command' || t === 'exec')
        return `→ Run:${target || '...'}`;
    if (t === 'search' || t === 'grep_search')
        return `→ Search${target || '...'}`;
    if (t === 'list_dir' || t === 'ls')
        return `→ List${target || '...'}`;
    if (t === 'todo_list' || t === 'update_todos')
        return `→ Update Plan & Tasks...`;
    if (t === 'plan_ready')
        return `→ Finalize Plan...`;
    if (t === 'diagnose_code')
        return `→ Diagnosing code...`;
    if (t === 'ask_questions')
        return `→ Clarifying questions...`;
    return `→ ${name}${target}...`;
}
async function handleSessionDelete() {
    while (true) {
        const sessions = await SessionManager.listSessions();
        if (sessions.length === 0) {
            drawLogo();
            p.log.warn('No saved sessions found.');
            break;
        }
        let chosenSessionId;
        try {
            chosenSessionId = await search({
                message: `Select session to DELETE (${sessions.length} sessions, Esc to finish):`,
                source: async (term) => {
                    const q = (term || '').trim().toLowerCase();
                    const list = [
                        { name: pc.yellow('⬅️  Done / Cancel'), value: '__cancel__', description: 'Return to chat' },
                        { name: pc.red('🗑️  Delete ALL sessions'), value: '__all__', description: `Remove all ${sessions.length} saved sessions` }
                    ];
                    for (const s of sessions) {
                        const timeStr = formatSessionTime(s.updatedAt);
                        const msgCount = s.messages.filter(m => m.role !== 'system').length;
                        const shortId = s.id.split('_').pop();
                        list.push({
                            name: `${pc.red('🗑️ ')} ${pc.dim(`[#${shortId}]`)} ${s.title}`,
                            value: s.id,
                            description: `${timeStr} • ${msgCount} msgs • ${s.model || 'default'}`
                        });
                    }
                    if (!q)
                        return list;
                    return list.filter(item => item.name.toLowerCase().includes(q) || item.value.toLowerCase().includes(q));
                },
                pageSize: 10
            });
        }
        catch {
            break;
        }
        if (!chosenSessionId || chosenSessionId === '__cancel__') {
            drawLogo();
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
                drawLogo();
                p.log.success(`Deleted all ${count} sessions.`);
                break;
            }
            continue;
        }
        const target = sessions.find(s => s.id === chosenSessionId);
        const title = target ? target.title : chosenSessionId;
        const ok = await SessionManager.deleteSession(chosenSessionId);
        drawLogo();
        if (ok) {
            p.log.success(`Deleted session: "${title}"`);
        }
        else {
            p.log.error(`Failed to delete session: "${title}"`);
        }
    }
}
async function handleThemeSelect(config) {
    const currentTh = getCurrentTheme();
    const themeChoices = listThemes().map(t => ({
        name: `${t.emoji} ${t.boldFn(t.name.padEnd(18))} ${pc.dim(t.desc)} ${t.id === currentTh.id ? pc.green('(Active)') : ''}`,
        value: t.id,
        description: `Apply ${t.name} color palette (${t.hex}) to banners, prompts, and actions`
    }));
    try {
        const selected = await select({
            message: `${pc.bold('🎨 Select UI Theme / Выберите цветовую тему:')}`,
            choices: themeChoices
        });
        if (selected) {
            config.theme = selected;
            await saveConfig(config);
            const th = setActiveTheme(selected);
            drawLogo();
            p.log.success(th.boldFn(`🎨 Theme switched to ${th.emoji} ${th.name}!`));
        }
    }
    catch { }
}
async function handleSettings(config) {
    while (true) {
        try {
            const maxIter = config.maxIterations || 100;
            const maxIterLabel = maxIter >= 9999 ? 'Unlimited' : `${maxIter} steps`;
            const currentTh = getCurrentTheme();
            const choice = await select({
                message: `${pc.bold('⚙️  Settings')} ${pc.dim(`(devx v1.4.15 • theme: ${currentTh.name})`)}`,
                choices: [
                    {
                        name: `🎨 Color Theme: ${currentTh.emoji} ${currentTh.name}`,
                        value: 'change_theme',
                        description: `Switch UI accent colors (${currentTh.desc})`
                    },
                    {
                        name: `${config.pureBlackTheme !== false ? pc.green('🖤 Pure Black Background: ON') : pc.yellow('🖤 Pure Black Background: OFF')}`,
                        value: 'toggle_black_theme',
                        description: config.pureBlackTheme !== false
                            ? 'Apply deep OLED obsidian black background (#0a0a0c) like OpenCode'
                            : 'Use standard system terminal background color'
                    },
                    {
                        name: `${config.autoApprove ? pc.green('⚡ Auto-Approve (YOLO Mode): ON') : pc.yellow('🛡️  Auto-Approve (YOLO Mode): OFF')}`,
                        value: 'toggle_auto_approve',
                        description: config.autoApprove
                            ? 'Permissions are automatically granted (no confirmation prompts for commands/files)'
                            : 'Agent asks for confirmation before executing bash commands or writing files'
                    },
                    {
                        name: `${config.enableMemory !== false ? pc.green('🧠 Project Memory Bank: ON') : pc.yellow('🧠 Project Memory Bank: OFF')}`,
                        value: 'toggle_memory',
                        description: config.enableMemory !== false
                            ? 'Load persistent project rules and preferences from .devx/memory.md into AI context'
                            : 'Start sessions with a clean state without loading project memory'
                    },
                    {
                        name: `${config.checkUpdates !== false ? pc.green('🔔 Check for Updates on Startup: ON') : pc.yellow('🔔 Check for Updates on Startup: OFF')}`,
                        value: 'toggle_check_updates',
                        description: config.checkUpdates !== false
                            ? 'Automatically check for updates from GitHub repository when launching devx'
                            : 'Disable update checking on startup (run /update manually instead)'
                    },
                    {
                        name: `🔄 Max Agent Iterations: ${currentTh.colorFn(maxIterLabel)}`,
                        value: 'change_max_iterations',
                        description: 'Limit how many tool steps (file edits, terminal commands) agent can do per request'
                    },
                    {
                        name: `${currentTh.colorFn('✨ About devx')} ${pc.dim('(v1.4.15 by ApvCode)')}`,
                        value: 'about',
                        description: 'Terminal-Native AI Coding Agent created by ApvCode (https://github.com/apvcode/Termux-Dev)'
                    },
                    {
                        name: '⬅️ Back / Save',
                        value: 'back',
                        description: 'Return to chat'
                    }
                ]
            });
            if (choice === 'change_theme') {
                await handleThemeSelect(config);
                continue;
            }
            if (choice === 'about') {
                p.note(`⚡ devx v1.4.15 — Terminal-Native AI Coding Agent\n` +
                    `🎨 Theme: ${currentTh.emoji} ${currentTh.name}\n` +
                    `👤 Author: ApvCode (https://github.com/apvcode)\n` +
                    `🌟 Repository: https://github.com/apvcode/Termux-Dev\n` +
                    `📜 License: MIT License (2026)\n` +
                    `Built for Android Termux, Windows, macOS, and Linux.`, 'About devx');
                continue;
            }
            if (choice === 'toggle_black_theme') {
                config.pureBlackTheme = config.pureBlackTheme === false ? true : false;
                await saveConfig(config);
                if (config.pureBlackTheme) {
                    enableDarkTheme(true);
                }
                else {
                    resetTerminalTheme();
                }
                drawLogo();
                p.log.success(`Pure Black background: ${config.pureBlackTheme ? pc.bold(pc.green('ON (Deep Black)')) : pc.bold(pc.yellow('OFF (System Default)'))}`);
                continue;
            }
            if (choice === 'toggle_auto_approve') {
                config.autoApprove = !config.autoApprove;
                await saveConfig(config);
                p.log.success(`Auto-approve permissions: ${config.autoApprove ? pc.bold(pc.green('ON (Automatic Yes)')) : pc.bold(pc.yellow('OFF (Ask every time)'))}`);
                continue;
            }
            if (choice === 'toggle_memory') {
                config.enableMemory = config.enableMemory === false ? true : false;
                await saveConfig(config);
                p.log.success(`Project memory bank: ${config.enableMemory !== false ? pc.bold(pc.green('ON (Persistent .devx/memory.md)')) : pc.bold(pc.yellow('OFF'))}`);
                continue;
            }
            if (choice === 'toggle_check_updates') {
                config.checkUpdates = config.checkUpdates === false ? true : false;
                await saveConfig(config);
                p.log.success(`Check for updates: ${config.checkUpdates !== false ? pc.bold(pc.green('ON (Checked on startup)')) : pc.bold(pc.yellow('OFF (Manual only)'))}`);
                continue;
            }
            if (choice === 'change_max_iterations') {
                const val = await select({
                    message: 'Select maximum iterations limit per prompt:',
                    choices: [
                        { name: '30 steps (Strict / Safe)', value: 30 },
                        { name: '50 steps (Moderate)', value: 50 },
                        { name: '100 steps (Recommended / Default)', value: 100 },
                        { name: '200 steps (Very large refactors)', value: 200 },
                        { name: 'Unlimited (No limit)', value: 9999 }
                    ]
                });
                config.maxIterations = val;
                await saveConfig(config);
                p.log.success(`Max iterations updated to: ${pc.bold(val >= 9999 ? 'Unlimited' : `${val} steps`)}`);
                continue;
            }
            break;
        }
        catch {
            break;
        }
    }
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
        }
        catch { }
        process.exit(0);
    });
    // Protect against crash on background I/O errors (EIO/EPIPE/ERR_STREAM_DESTROYED)
    process.on('uncaughtException', (err) => {
        const isIgnorableIo = err.code === 'EIO' || err.code === 'EPIPE' || err.code === 'ERR_STREAM_DESTROYED';
        if (isIgnorableIo)
            return;
        console.error('\n[devx runtime error]:', err.message);
    });
    process.on('unhandledRejection', (reason) => {
        // Prevent Node.js crash from unhandled network promise rejections
    });
    acquireWakeLock();
    const program = new Command();
    program
        .name('devx')
        .description('Terminal-native AI coding assistant and vibe-coding agent')
        .version('1.4.15')
        .option('-p, --prompt <task>', 'Run one-shot task non-interactively (headless mode)')
        .option('-y, --yolo', 'Automatically approve all tool executions without confirmation')
        .option('-m, --model <model>', 'Specify AI model to use for this execution')
        .option('-q, --quiet', 'Quiet output in headless mode (suppress banners and tool logs)')
        .option('--json', 'Output structured JSON in headless mode')
        .option('--plan', 'Start in plan mode (architect & planner)')
        .parse(process.argv);
    const options = program.opts();
    const planModeInitial = !!options.plan;
    const isHeadless = !!options.prompt;
    let config = await loadConfig(!isHeadless);
    if (options.model) {
        config.model = options.model;
        config.maxContextTokens = getModelContextLimit(options.model);
    }
    if (options.yolo) {
        config.autoApprove = true;
    }
    // If -p / --prompt is provided, execute one-shot headless mode and exit!
    if (isHeadless) {
        const exitCode = await runHeadlessMode(options.prompt, config, {
            planMode: planModeInitial,
            yolo: !!options.yolo,
            quiet: !!options.quiet,
            json: !!options.json
        });
        process.exit(exitCode);
    }
    let planMode = planModeInitial;
    if (!config.onboarded) {
        const cols = Math.min(process.stdout.columns || 40, 42);
        const fill = Math.max(2, cols - 16);
        const topBorder = '┌─ preview.ts ' + '─'.repeat(fill) + '┐';
        const bottomBorder = '└' + '─'.repeat(cols - 2) + '┘';
        const themeChoices = listThemes().map(t => {
            const addLine = t.diffAddBg(` + 1 | const theme = "${t.id}";`.padEnd(cols - 2));
            const remLine = t.diffRemoveBg(` - 2 | const theme = "none";`.padEnd(cols - 2));
            const preview = `${t.colorFn(topBorder)}\n${addLine}\n${remLine}\n${t.colorFn(bottomBorder)}\n\n${pc.dim('💡 You can change this anytime with /theme')}`;
            return {
                name: `${t.emoji} ${t.boldFn(t.name.padEnd(18))} ${pc.dim(t.desc)}`,
                value: t.id,
                description: preview
            };
        });
        try {
            clearTerminalScreen();
            const selected = await search({
                message: `${pc.bold('🎨 Pick a theme to personalize your workspace:')}`,
                source: async () => themeChoices,
                pageSize: 6
            });
            if (selected) {
                config.theme = selected;
            }
        }
        catch { }
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
    drawLogo();
    await runStartupUpdateCheck(config);
    await MCPManager.getInstance().init();
    let totalSessionCost = 0;
    let currentDraft = '';
    let autoTriggerPrompt = '';
    while (true) {
        const currentTokens = history.getConversationTokens();
        const maxTokens = config.maxContextTokens || getModelContextLimit(config.model);
        config.maxContextTokens = maxTokens;
        const usagePercent = Math.min(100, Math.round((currentTokens / maxTokens) * 100));
        const formatTokens = (n) => {
            if (n >= 1_000_000)
                return `${(n / 1_000_000).toFixed(1)}M`;
            if (n >= 1000)
                return `${(n / 1000).toFixed(1)}k`;
            return `${n}`;
        };
        const costStr = totalSessionCost > 0 ? `Cost: $${totalSessionCost.toFixed(4)}` : 'Cost: $0.0000';
        const tokenStats = `Context: ${formatTokens(currentTokens)} / ${formatTokens(maxTokens)} (${usagePercent}%) • ${costStr}`;
        const cols = process.stdout.columns || 80;
        const modeName = planMode ? 'PLAN' : 'AGENT';
        const theme = getCurrentTheme();
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
        }
        else {
            // 1-line layout for wider desktop screens
            p.intro(`${badge}  ${pc.dim(tokenStats)}`);
        }
        let answer = '';
        if (autoTriggerPrompt) {
            answer = autoTriggerPrompt;
            autoTriggerPrompt = '';
            console.log(theme.colorFn('◆') + '  ' + pc.bold(pc.white(answer)));
        }
        else {
            const inputStr = await askPrompt({
                message: 'Ask anything...',
                placeholder: 'Fix a TODO, type /help, or press Tab to switch mode',
                initialValue: currentDraft,
                planMode
            });
            if (inputStr.startsWith('__TOGGLE_MODE__:')) {
                currentDraft = inputStr.slice('__TOGGLE_MODE__:'.length);
                planMode = !planMode;
                const newSys = await buildSystemPrompt(planMode);
                history.updateSystemPrompt(newSys);
                p.log.info(planMode
                    ? pc.bold(pc.cyan('🔄 Mode: PLAN (Architect & Planner) — Press Tab to switch to AGENT'))
                    : pc.bold(pc.green('🔄 Mode: AGENT (Coder & Executor) — Press Tab to switch to PLAN')));
                continue;
            }
            currentDraft = '';
            if (inputStr === '__CANCEL__') {
                const confirmExit = await p.confirm({
                    message: 'Are you sure you want to exit devx?',
                    initialValue: false
                });
                if (p.isCancel(confirmExit) || confirmExit) {
                    resetTerminalTheme();
                    p.outro('Goodbye!');
                    break;
                }
                else {
                    continue;
                }
            }
            answer = inputStr.trim();
        }
        if (!answer)
            continue;
        if (answer.startsWith('/')) {
            const parts = answer.split(' ');
            let cmd = parts[0];
            // 1. Check for custom slash commands (.devx/commands/*.md)
            const customCmd = await CustomCommandManager.findCommand(cmd);
            if (customCmd) {
                const cmdArgs = answer.slice(cmd.length).trim();
                const expandedPrompt = CustomCommandManager.expandTemplate(customCmd.promptTemplate, cmdArgs);
                answer = expandedPrompt;
                p.log.info(pc.cyan(`⚡ Executing custom command: ${pc.bold(customCmd.cmd)} (${customCmd.desc})`));
            }
            else {
                const VALID_COMMANDS = [
                    '/new', '/reset', '/resume', '/session', '/sessions', '/history',
                    '/theme', '/themes', '/usage', '/export', '/mcp',
                    '/settings', '/update', '/model', '/provider', '/providers',
                    '/plan', '/agent', '/image', '/serve', '/memory', '/undo',
                    '/diff', '/commit', '/status', '/compact', '/init', '/doctor',
                    '/config', '/clear', '/exit', '/quit', '/help'
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
                        { name: '/clear        - Clear message history', value: '/clear' },
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
                                    { name: pc.yellow('⬅️  Cancel'), value: '__cancel__' },
                                    ...SLASH_COMMANDS
                                ];
                                if (!q)
                                    return list;
                                return list.filter(item => item.name.toLowerCase().includes(q) || item.value.toLowerCase().includes(q));
                            },
                            pageSize: 12
                        });
                        if (!picked || picked === '__cancel__') {
                            continue;
                        }
                        if (picked === '/session del') {
                            await handleSessionDelete();
                            continue;
                        }
                        cmd = picked;
                    }
                    catch {
                        continue;
                    }
                }
            }
            if (cmd === '/settings') {
                config = await handleSettings(config);
                drawLogo();
                continue;
            }
            if (cmd === '/update') {
                const s = p.spinner();
                s.start('Checking for updates from https://github.com/apvcode/Termux-Dev...');
                const res = await checkForUpdates(10000);
                s.stop();
                if (res.updateAvailable) {
                    p.log.info(pc.bold(pc.yellow(`🚀 Update available: v${res.currentVersion} ➔ v${res.latestVersion}`)));
                    const doUpdate = await p.confirm({
                        message: `Do you want to update devx to v${res.latestVersion} now?`,
                        initialValue: true
                    });
                    if (!p.isCancel(doUpdate) && doUpdate) {
                        await performSelfUpdate(res.latestVersion);
                    }
                }
                else {
                    p.log.success(pc.green(`devx is up to date! (v${res.currentVersion})`));
                }
                continue;
            }
            if (cmd === '/help') {
                p.note('/settings - Configure permissions (Auto-Approve / Ask every time)\n/resume - Resume previous chat session\n/session del - Select and delete saved sessions\n/new - Start a new chat session (or /new <prompt>)\n/model - Switch model for current provider\n/provider - Switch AI provider (with saved keys)\n/config - View configuration\n/plan - Switch to plan mode\n/agent - Switch to agent mode\n/clear - Clear history\n/exit - Exit', 'Commands');
                continue;
            }
            if (cmd === '/exit') {
                p.outro('Goodbye!');
                break;
            }
            if (cmd === '/clear') {
                history.clear();
                history.addMessage({ role: 'system', content: await buildSystemPrompt(planMode) });
                p.log.success('History cleared.');
                continue;
            }
            if (cmd === '/new' || cmd === '/reset') {
                sessionManager.startNewSession(config.model, planMode);
                history = new History();
                history.addMessage({ role: 'system', content: await buildSystemPrompt(planMode) });
                totalSessionCost = 0;
                drawLogo();
                const initialPrompt = parts.slice(1).join(' ').trim();
                if (initialPrompt) {
                    p.log.success('Started new session with prompt.');
                    answer = initialPrompt;
                }
                else {
                    p.log.success('Started a new chat session.');
                    continue;
                }
            }
            else if (cmd === '/session' || cmd === '/sessions' || cmd === '/resume' || cmd === '/history') {
                const sub = (parts[1] || '').toLowerCase();
                if (sub === 'del' || sub === 'delete' || sub === 'rm') {
                    await handleSessionDelete();
                    continue;
                }
                const sessions = await SessionManager.listSessions();
                if (sessions.length === 0) {
                    p.log.warn('No saved sessions found.');
                    continue;
                }
                let chosenSessionId;
                try {
                    chosenSessionId = await search({
                        message: 'Select a session to resume (type to filter, Esc to cancel):',
                        source: async (term) => {
                            const q = (term || '').trim().toLowerCase();
                            const list = [
                                { name: pc.yellow('⬅️  Back / Cancel'), value: '__cancel__', description: 'Return to chat' },
                                { name: pc.red('🗑️  Delete sessions... (/session del)'), value: '__delete_mode__', description: 'Selectively delete saved sessions' }
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
                            if (!q)
                                return list;
                            return list.filter(item => item.name.toLowerCase().includes(q) || item.value.toLowerCase().includes(q));
                        },
                        pageSize: 10
                    });
                }
                catch {
                    continue;
                }
                if (chosenSessionId === '__cancel__') {
                    continue;
                }
                if (chosenSessionId === '__delete_mode__') {
                    await handleSessionDelete();
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
                        config.maxContextTokens = getModelContextLimit(loaded.model);
                    }
                    if (loaded.planMode !== undefined) {
                        planMode = loaded.planMode;
                    }
                    totalSessionCost = loaded.totalCost || 0;
                    drawLogo();
                    const nonSysMessages = loaded.messages.filter(m => m.role !== 'system');
                    const totalMsgs = nonSysMessages.length;
                    const displayLimit = 20;
                    const recentMessages = nonSysMessages.slice(-displayLimit);
                    const shownCount = recentMessages.length;
                    // Header banner showing messages loaded
                    const countInfo = totalMsgs > displayLimit
                        ? `Loaded recent ${shownCount} messages of ${totalMsgs}`
                        : `Loaded all ${totalMsgs} messages`;
                    console.log('\n' + pc.bold(pc.cyan(`─── 📜 ${countInfo} (Session: "${loaded.title}") ───`)) + '\n');
                    // Render each message in chronological order
                    for (const msg of recentMessages) {
                        if (msg.role === 'user') {
                            console.log(pc.cyan('◆') + '  ' + pc.bold(pc.white(msg.content)));
                            if (msg.images && msg.images.length > 0) {
                                console.log(pc.magenta(`  [🖼️ ${msg.images.map(i => i.path).join(', ')}]`));
                            }
                            console.log();
                        }
                        else if (msg.role === 'assistant') {
                            if (msg.content) {
                                console.log(renderMarkdown(msg.content));
                            }
                            if (msg.tool_calls && msg.tool_calls.length > 0) {
                                for (const tc of msg.tool_calls) {
                                    console.log(pc.dim(`  ⚡ ${tc.name}`));
                                }
                            }
                            console.log();
                        }
                        else if (msg.role === 'tool') {
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
                }
                else {
                    p.log.error('Failed to load session.');
                }
                continue;
            }
            if (cmd === '/plan') {
                planMode = true;
                const newSys = await buildSystemPrompt(true);
                history.updateSystemPrompt(newSys);
                drawLogo();
                p.log.success(pc.cyan('Switched to PLAN mode (Architect & Planner). Modifying tools are disabled.'));
                continue;
            }
            if (cmd === '/agent') {
                planMode = false;
                const newSys = await buildSystemPrompt(false);
                history.updateSystemPrompt(newSys);
                drawLogo();
                p.log.success(pc.green('Switched to AGENT mode (Coder & Executor). Full tools enabled.'));
                continue;
            }
            if (cmd === '/undo') {
                const { revertedFiles, count } = await globalSnapshotManager.undoLastTurn();
                if (count === 0) {
                    p.log.warn('No changes to undo.');
                }
                else {
                    history.popLastTurn();
                    p.log.success(pc.bold(pc.green(`⏪ Successfully reverted changes in ${count} file(s):`)));
                    for (const f of revertedFiles) {
                        console.log(pc.cyan(`  • ${f}`));
                    }
                }
                continue;
            }
            if (cmd === '/diff') {
                try {
                    const diffOutput = execSync('git diff', { encoding: 'utf8' });
                    if (!diffOutput.trim()) {
                        p.log.info('No uncommitted changes in git repository.');
                    }
                    else {
                        console.log('\n' + pc.bold(pc.cyan('─── Git Diff ─────────────────────────')));
                        for (const line of diffOutput.split('\n')) {
                            if (line.startsWith('+') && !line.startsWith('+++')) {
                                console.log(pc.green(line));
                            }
                            else if (line.startsWith('-') && !line.startsWith('---')) {
                                console.log(pc.red(line));
                            }
                            else if (line.startsWith('@@')) {
                                console.log(pc.magenta(line));
                            }
                            else {
                                console.log(line);
                            }
                        }
                        console.log(pc.bold(pc.cyan('──────────────────────────────────────\n')));
                    }
                }
                catch {
                    p.log.warn('Git is not available or current directory is not a git repo.');
                }
                continue;
            }
            if (cmd === '/status') {
                try {
                    const statusOutput = execSync('git status --short', { encoding: 'utf8' });
                    if (!statusOutput.trim()) {
                        p.log.success('Working directory clean, no modified files.');
                    }
                    else {
                        console.log('\n' + pc.bold(pc.cyan('─── Git Status ───────────────────────')));
                        for (const line of statusOutput.split('\n')) {
                            if (!line.trim())
                                continue;
                            if (line.startsWith(' M') || line.startsWith('M ')) {
                                console.log(pc.yellow(`  ${line}`));
                            }
                            else if (line.startsWith('??')) {
                                console.log(pc.green(`  ${line}`));
                            }
                            else if (line.startsWith(' D')) {
                                console.log(pc.red(`  ${line}`));
                            }
                            else {
                                console.log(`  ${line}`);
                            }
                        }
                        console.log(pc.bold(pc.cyan('──────────────────────────────────────\n')));
                    }
                }
                catch {
                    p.log.warn('Git is not available or current directory is not a git repo.');
                }
                continue;
            }
            if (cmd === '/commit') {
                try {
                    const status = execSync('git status --short', { encoding: 'utf8' });
                    if (!status.trim()) {
                        p.log.info('No changes to commit.');
                        continue;
                    }
                    const diff = execSync('git diff', { encoding: 'utf8' });
                    const userMsg = parts.slice(1).join(' ').trim();
                    let commitMsg = userMsg;
                    if (!commitMsg) {
                        const s = p.spinner();
                        s.start('Generating conventional commit message with AI...');
                        const provider = createProvider(config);
                        const promptReq = {
                            messages: [
                                {
                                    role: 'system',
                                    content: 'You generate single-line conventional git commit messages (e.g. feat(auth): add token validation). Output ONLY the commit message line and nothing else.'
                                },
                                {
                                    role: 'user',
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
                        p.log.success(pc.bold(pc.green(`✅ Committed: ${commitMsg}`)));
                    }
                    else {
                        p.log.info('Commit cancelled.');
                    }
                }
                catch (err) {
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
                    const provider = createProvider(config);
                    const compactPrompt = {
                        messages: [
                            ...msgs,
                            {
                                role: 'user',
                                content: 'Please create a clear, comprehensive, and structured summary of our conversation so far, including all established requirements, decisions, modified files, and remaining tasks. Be concise and precise.'
                            }
                        ]
                    };
                    const res = await provider.chat(compactPrompt);
                    s.stop();
                    const sysPrompt = await buildSystemPrompt(planMode);
                    history.clear();
                    history.addMessage({ role: 'system', content: sysPrompt });
                    history.addMessage({
                        role: 'assistant',
                        content: `[CONVERSATION COMPACTED]\n${res.content || ''}`
                    });
                    p.log.success(pc.bold(pc.green('🗜️ Context successfully compacted! Freed up tokens while retaining task summary.')));
                }
                catch (err) {
                    s.stop();
                    p.log.error(`Compaction failed: ${err.message}`);
                }
                continue;
            }
            if (cmd === '/clear') {
                console.clear();
                drawLogo();
                continue;
            }
            if (cmd === '/session' && parts[1]?.toLowerCase() !== 'del') {
                const cur = sessionManager.getSession();
                const nonSys = history.getMessages().filter(m => m.role !== 'system');
                const shortId = cur.id.split('_').pop();
                console.log('\n' + pc.bold(pc.cyan('─── 🆔 Active Session Information ───')));
                console.log(`  ${pc.bold('ID:')}        ${pc.cyan(cur.id)} ${pc.dim(`(#${shortId})`)}`);
                console.log(`  ${pc.bold('Title:')}     "${cur.title}"`);
                console.log(`  ${pc.bold('Model:')}     ${config.model}`);
                console.log(`  ${pc.bold('Mode:')}      ${planMode ? pc.cyan('PLAN') : pc.green('AGENT')}`);
                console.log(`  ${pc.bold('Messages:')}  ${nonSys.length} messages`);
                console.log(`  ${pc.bold('Cost:')}      $${totalSessionCost.toFixed(4)}`);
                console.log(`  ${pc.bold('File:')}      ~/.devx/sessions/${cur.id}.json`);
                console.log(pc.bold(pc.cyan('──────────────────────────────────────\n')));
                continue;
            }
            if (cmd === '/init') {
                const agentsPath = path.join(process.cwd(), 'AGENTS.md');
                if (fsSync.existsSync(agentsPath)) {
                    p.log.warn('AGENTS.md already exists in current workspace.');
                }
                else {
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
                    }
                    else {
                        p.log.info('No web server is currently running.');
                    }
                }
                else {
                    const customPort = parseInt(parts[1], 10) || 3000;
                    try {
                        const { port, localUrl, networkUrl } = await startServer(customPort);
                        await displayServerBanner(localUrl, networkUrl);
                    }
                    catch (err) {
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
                        drawLogo();
                        p.log.success(th.boldFn(`🎨 Theme switched to ${th.emoji} ${th.name}!`));
                    }
                    else {
                        const themes = listThemes();
                        p.log.warn(`Unknown theme: "${themeArg}". Available themes: ${themes.map(t => `${t.emoji} ${t.id}`).join(', ')}`);
                    }
                }
                else {
                    await handleThemeSelect(config);
                }
                continue;
            }
            if (cmd === '/memory') {
                const sub = parts[1]?.toLowerCase();
                if (sub === 'clear') {
                    await MemoryManager.clearMemory();
                    p.log.success('Cleared project memory bank (.devx/memory.md).');
                }
                else if (sub === 'add') {
                    const fact = parts.slice(2).join(' ').trim();
                    if (!fact) {
                        p.log.warn('Usage: /memory add <fact or rule to remember>');
                    }
                    else {
                        await MemoryManager.addFact(fact);
                        p.log.success(`Saved to memory: "${fact}"`);
                    }
                }
                else {
                    const mem = await MemoryManager.loadMemory();
                    if (!mem.trim()) {
                        p.log.info('Project memory is empty. AI will remember facts as you work, or use /memory add <fact>.');
                    }
                    else {
                        p.note(mem, '🧠 Project Memory Bank (.devx/memory.md)');
                        console.log(pc.dim('Commands: /memory add <text> | /memory clear\n'));
                    }
                }
                continue;
            }
            if (cmd === '/usage') {
                const card = UsageTracker.getInstance().renderUsageCard();
                console.log('\n' + card);
                continue;
            }
            if (cmd === '/export') {
                const targetName = parts.slice(1).join(' ').trim();
                const res = await SessionExporter.exportToMarkdown(history.getMessages(), config.model, targetName || undefined);
                if (res.success) {
                    p.log.success(pc.bold(pc.green(`📄 Session exported to: ${res.filePath}`)));
                }
                else {
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
                }
                else {
                    console.log('\n' + MCPManager.getInstance().renderStatusCard());
                }
                continue;
            }
            if (cmd === '/config') {
                const masked = { ...config };
                if (masked.apiKey)
                    masked.apiKey = maskApiKey(masked.apiKey);
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
                drawLogo();
                if (res) {
                    config.model = res.model;
                    config.maxContextTokens = res.contextLimit;
                    await saveConfig(config);
                    p.log.success(`Switched model to: ${pc.bold(config.model)}`);
                }
                else {
                    p.log.info('Model selection cancelled.');
                }
                continue;
            }
            if (cmd === '/provider' || cmd === '/providers') {
                const newConfig = await selectProvider(config, true);
                drawLogo();
                if (newConfig) {
                    config = newConfig;
                    p.log.success(`Switched provider to: ${pc.bold(config.provider || 'custom')} (${config.model})`);
                }
                else {
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
            const planWasPresented = assistantContent.includes('план') ||
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
                    const isApproval = words.some(w => actionRoots.some(root => w.startsWith(root))) ||
                        words.includes('давай') ||
                        (words.length <= 2 && (words.includes('да') || words.includes('yes') || words.includes('ок') || words.includes('ok') || words.includes('go')));
                    if (isApproval) {
                        planMode = false;
                        const newSys = await buildSystemPrompt(false);
                        history.updateSystemPrompt(newSys);
                        p.log.success(pc.bold(pc.green('⚡ Plan approved! Auto-switched to AGENT mode. Starting execution...')));
                    }
                }
            }
        }
        // Resolve @file and @image mentions
        const { text: cleanAnswer, attachments, images } = await resolveAtMentions(answer);
        let finalContent = cleanAnswer;
        if (attachments.length > 0) {
            const attachBlocks = attachments.map(a => `[Attached file: ${a.path}]\n\`\`\`\n${a.content}\n\`\`\``).join('\n\n');
            finalContent = `${cleanAnswer}\n\n--- Attached Files ---\n${attachBlocks}`;
            p.log.info(pc.cyan(`📎 Attached ${attachments.length} file(s): ${attachments.map(a => a.path).join(', ')}`));
        }
        if (images.length > 0) {
            p.log.info(pc.cyan(`🖼️  Attached ${images.length} image(s): ${images.map(i => i.path).join(', ')}`));
        }
        globalSnapshotManager.beginTurn();
        history.addMessage({
            role: 'user',
            content: finalContent,
            images: images.length > 0 ? images : undefined
        });
        const provider = createProvider(config);
        const tools = getTools(planMode);
        const guard = new CLIConsoleGuard(config.autoApprove, config.bashAllowlist || []);
        const agentConfig = {
            maxContextTokens: config.maxContextTokens || 100000,
            maxIterations: config.maxIterations || 100,
            autoApprove: config.autoApprove,
            bashAllowlist: config.bashAllowlist
        };
        const agent = new Agent(agentConfig, provider, tools, history, guard);
        const taskStartTime = Date.now();
        const s = p.spinner();
        s.start('Connecting...');
        let spinnerActive = true;
        let thinkingActive = false;
        let thinkingStartTime = 0;
        let textActive = false;
        const streamer = new MarkdownStreamer();
        const thoughtStreamer = new SmoothStreamer((chunk) => {
            process.stdout.write(pc.dim(chunk));
        });
        const finishThinking = (extraNewline = true) => {
            if (!thinkingActive)
                return;
            thoughtStreamer.flush();
            const elapsedSec = thinkingStartTime > 0 ? ((Date.now() - thinkingStartTime) / 1000).toFixed(1) : '0.0';
            const width = Math.min(process.stdout.columns || 40, 52);
            const label = `─── Thought for ${elapsedSec}s `;
            const fillLen = Math.max(2, width - label.length);
            process.stdout.write('\n\n' + pc.dim(label + '─'.repeat(fillLen)) + (extraNewline ? '\n\n' : '\n'));
            thinkingActive = false;
            thinkingStartTime = 0;
        };
        const abortController = new AbortController();
        let aborted = false;
        const stopGeneration = () => {
            if (aborted)
                return;
            aborted = true;
            abortController.abort();
            if (spinnerActive) {
                s.stop();
                spinnerActive = false;
            }
            thoughtStreamer.reset();
            finishThinking(true);
            streamer.finish();
            if (textActive) {
                process.stdout.write('\n');
            }
            console.log(pc.yellow('\n⏹  Generation stopped / Ответ остановлен (Ctrl+C).'));
        };
        const onSigInt = () => {
            stopGeneration();
        };
        const onRawData = (data) => {
            const buf = Buffer.isBuffer(data) ? data : Buffer.from(String(data));
            if (buf.includes(3) || buf.includes(0x03) || data === '\x03') {
                stopGeneration();
            }
        };
        activeAbortHandler = stopGeneration;
        process.on('SIGINT', onSigInt);
        if (process.stdin.isTTY) {
            try {
                process.stdin.resume();
                process.stdin.setRawMode(true);
                process.stdin.on('data', onRawData);
            }
            catch { }
        }
        const turnStartTime = Date.now();
        let toolsExecutedCount = 0;
        try {
            for await (const event of agent.run(abortController.signal)) {
                if (aborted)
                    break;
                if (event.type === 'reasoning_delta') {
                    if (spinnerActive) {
                        s.stop();
                        spinnerActive = false;
                    }
                    if (!thinkingActive) {
                        thinkingStartTime = Date.now();
                        process.stdout.write('\n' + pc.bold(pc.white('› Thought:')) + '\n');
                        thinkingActive = true;
                    }
                    thoughtStreamer.push(event.delta);
                }
                else if (event.type === 'text_delta') {
                    if (spinnerActive) {
                        s.stop();
                        spinnerActive = false;
                    }
                    finishThinking(true);
                    if (!textActive) {
                        textActive = true;
                    }
                    streamer.push(event.delta);
                }
                else if (event.type === 'text') {
                    if (spinnerActive) {
                        s.stop();
                        spinnerActive = false;
                    }
                    finishThinking(true);
                    if (!textActive) {
                        console.log('\n' + renderMarkdown(event.content) + '\n');
                    }
                }
                else if (event.type === 'tool_generating') {
                    const theme = getCurrentTheme();
                    const label = formatToolGeneratingLabel(event.name, event.targetHint);
                    if (!spinnerActive) {
                        streamer.finish();
                        finishThinking(false);
                        s.start(theme.boldFn(label));
                        spinnerActive = true;
                    }
                    else {
                        s.message(theme.boldFn(label));
                    }
                }
                else if (event.type === 'tool_start') {
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
                    console.log('\n' + pc.bold(pc.white(event.actionDesc)));
                    if (event.name !== 'ask_questions' && event.name !== 'todo_list') {
                        s.start('Executing...');
                        spinnerActive = true;
                    }
                }
                else if (event.type === 'tool_end') {
                    toolsExecutedCount++;
                    if (spinnerActive) {
                        s.stop();
                        spinnerActive = false;
                    }
                    if (event.result) {
                        try {
                            const parsed = JSON.parse(event.result);
                            if (parsed.type === 'todo_list' && parsed.displayCard) {
                                process.stdout.write(parsed.displayCard);
                            }
                            else if (parsed.action === 'edit' && parsed.diffLines) {
                                const theme = getCurrentTheme();
                                const cols = Math.min(process.stdout.columns || 80, 80);
                                const boxWidth = Math.max(30, Math.min(cols - 4, 76));
                                const fileName = typeof parsed.path === 'string' ? parsed.path.split(/[\/\\]/).pop() || 'file' : 'file';
                                const fillCount = Math.max(2, boxWidth - 5 - fileName.length);
                                console.log(theme.colorFn('┌─ ') + pc.bold(fileName) + ' ' + theme.colorFn('─'.repeat(fillCount) + '┐'));
                                const maxShown = Math.min(parsed.diffLines.length, 30);
                                for (let i = 0; i < maxShown; i++) {
                                    const line = parsed.diffLines[i];
                                    const match = line.match(/^(\d+)(\s+[+\- ]\s+)(.*)$/);
                                    if (match) {
                                        const lineNum = match[1].padStart(4, ' ');
                                        const symbol = match[2];
                                        const contentStr = match[3] || '';
                                        const prefix = symbol.includes('-') ? '-' : symbol.includes('+') ? '+' : ' ';
                                        const maxCodeLen = Math.max(10, boxWidth - 11);
                                        const paddedCode = contentStr.length > maxCodeLen
                                            ? contentStr.substring(0, maxCodeLen - 1) + '…'
                                            : contentStr.padEnd(maxCodeLen, ' ');
                                        const innerRow = ` ${lineNum} ${prefix} ${paddedCode} `;
                                        if (prefix === '-') {
                                            console.log(theme.colorFn('│') + theme.diffRemoveBg(innerRow) + theme.colorFn('│'));
                                        }
                                        else if (prefix === '+') {
                                            console.log(theme.colorFn('│') + theme.diffAddBg(innerRow) + theme.colorFn('│'));
                                        }
                                        else {
                                            console.log(theme.colorFn('│') + pc.dim(innerRow) + theme.colorFn('│'));
                                        }
                                    }
                                    else {
                                        const maxLen = Math.max(10, boxWidth - 4);
                                        const padded = line.length > maxLen ? line.substring(0, maxLen - 1) + '…' : line.padEnd(maxLen, ' ');
                                        console.log(theme.colorFn('│ ') + pc.white(padded) + theme.colorFn(' │'));
                                    }
                                }
                                if (parsed.diffLines.length > maxShown) {
                                    const dots = `... +${parsed.diffLines.length - maxShown} more lines`;
                                    const maxLen = Math.max(10, boxWidth - 4);
                                    const paddedDots = dots.length > maxLen ? dots.substring(0, maxLen - 1) + '…' : dots.padEnd(maxLen, ' ');
                                    console.log(theme.colorFn('│ ') + pc.dim(paddedDots) + theme.colorFn(' │'));
                                }
                                console.log(theme.colorFn('└' + '─'.repeat(boxWidth - 2) + '┘'));
                                console.log(theme.boldFn(`  └─ ${parsed.summary}`));
                            }
                            else if (parsed.summary) {
                                console.log(pc.green(`  └─ ${parsed.summary}`));
                            }
                            else {
                                const firstLine = event.result.trim().split('\n')[0];
                                if (firstLine.includes('+') || firstLine.includes('lines') || firstLine.includes('Successfully')) {
                                    console.log(pc.green(`  └─ ${firstLine}`));
                                }
                            }
                        }
                        catch {
                            const firstLine = event.result.trim().split('\n')[0];
                            if (firstLine.includes('+') || firstLine.includes('lines') || firstLine.includes('Successfully')) {
                                console.log(pc.green(`  └─ ${firstLine}`));
                            }
                        }
                    }
                }
                else if (event.type === 'usage') {
                    totalSessionCost += event.usage.cost || 0;
                }
                else if (event.type === 'reconnecting') {
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
                    console.log();
                    console.log(pc.bold(pc.yellow(`⚠️  Интернет-соединение нестабильно (${event.reason})`)));
                    console.log(pc.cyan(`🔄 Попытка переподключения [${event.attempt}/${event.maxAttempts}] (пауза ${(event.delayMs / 1000).toFixed(1)}с)...`));
                    s.start(pc.cyan(`Ожидание сети [${event.attempt}/${event.maxAttempts}]...`));
                    spinnerActive = true;
                }
                else if (event.type === 'reconnected') {
                    if (spinnerActive) {
                        s.stop();
                        spinnerActive = false;
                    }
                    console.log(pc.bold(pc.green(`✔ Соединение восстановлено! Возобновляем генерацию...`)));
                    console.log();
                    s.start('Connecting...');
                    spinnerActive = true;
                }
                else if (event.type === 'error') {
                    if (spinnerActive) {
                        s.stop();
                        spinnerActive = false;
                    }
                    finishThinking(true);
                    streamer.finish();
                    p.log.error(pc.bold(pc.red(`❌ ${event.message}`)));
                }
                else if (event.type === 'system') {
                    if (spinnerActive) {
                        s.stop();
                        spinnerActive = false;
                    }
                    p.log.info(event.message);
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
            globalSnapshotManager.finishTurn();
            const turnElapsedMs = Date.now() - turnStartTime;
            const shouldNotify = (turnElapsedMs >= 15000) || (toolsExecutedCount > 0) || (planMode && lastPlanReady !== null);
            if (shouldNotify && !aborted) {
                const secs = Math.round(turnElapsedMs / 1000);
                notifyDevice('devx', `Task completed in ${secs}s!`);
            }
            // Auto-save session
            await sessionManager.save(history.getMessages(), totalSessionCost, config.model, planMode);
            // Check if plan was finalized in PLAN mode
            if (planMode && !aborted) {
                const lastMsg = history.getMessages().slice(-1)[0];
                const hasPlanReadyTool = lastPlanReady !== null;
                const hasPlanText = lastMsg && lastMsg.role === 'assistant' && (lastMsg.content.includes('🎯 Цель') ||
                    lastMsg.content.includes('📁 Пошаговый') ||
                    lastMsg.content.includes('FINAL PLAN') ||
                    lastMsg.content.includes('План готов') ||
                    lastMsg.content.includes('Архитектурный план') ||
                    lastMsg.content.includes('план готов'));
                if (hasPlanReadyTool || hasPlanText) {
                    resetPlanReady();
                    console.log();
                    let choice = '';
                    try {
                        choice = await select({
                            message: pc.bold(pc.cyan('📋 Plan is ready! What would you like to do?')),
                            choices: [
                                {
                                    name: pc.bold(pc.green('🚀 Go (Switch to AGENT mode and start execution)')),
                                    value: 'go',
                                    description: 'Automatically switch to AGENT mode and start implementing the plan immediately'
                                },
                                {
                                    name: pc.bold(pc.yellow('✏️  Other (Modify, add details, or ask questions)')),
                                    value: 'other',
                                    description: 'Stay in PLAN mode and open prompt to type adjustments or additions'
                                }
                            ]
                        });
                    }
                    catch { }
                    if (choice === 'go') {
                        planMode = false;
                        const newSys = await buildSystemPrompt(false);
                        history.updateSystemPrompt(newSys);
                        p.log.success(pc.bold(pc.green('🚀 Switched to AGENT mode! Starting execution of approved plan...')));
                        currentDraft = '';
                        autoTriggerPrompt = 'Go!';
                        continue;
                    }
                    else {
                        currentDraft = '';
                        continue;
                    }
                }
            }
        }
        catch (err) {
            finishThinking(true);
            streamer.finish();
            if (spinnerActive) {
                s.stop();
                spinnerActive = false;
            }
            if (!aborted && err.name !== 'AbortError' && !err.message?.includes('aborted')) {
                p.log.error(pc.bold(pc.red(`❌ ${err.message}`)));
            }
        }
        finally {
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
    }
    catch { }
});
main().catch(console.error);
