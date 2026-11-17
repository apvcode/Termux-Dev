import os from 'os';
import path from 'path';
import fsSync from 'fs';
import { execSync } from 'child_process';
import https from 'https';
import pc from 'picocolors';
import { AgentConfig, DEVX_VERSION } from '../core/types.js';
import { getCurrentTheme } from './theme.js';
import { isTermux } from '../core/notify.js';

interface CheckItem {
  name: string;
  status: 'ok' | 'warn' | 'fail';
  details: string;
}

function checkCmd(cmd: string): string | null {
  try {
    const out = execSync(cmd, { stdio: ['ignore', 'pipe', 'ignore'], encoding: 'utf8' }).trim();
    return out.split('\n')[0];
  } catch {
    return null;
  }
}

async function pingUrl(url: string): Promise<boolean> {
  return new Promise((resolve) => {
    const req = https.get(url, {
      timeout: 3000,
      headers: {
        'User-Agent': `devx-doctor/${DEVX_VERSION}`
      }
    }, (res) => {
      res.resume(); // consume response data to free up memory and release socket
      resolve(res.statusCode !== undefined && res.statusCode < 500);
    });
    req.on('error', () => resolve(false));
    req.on('timeout', () => {
      req.destroy();
      resolve(false);
    });
  });
}

/** Smallest context window (tokens) that holds devx's system prompt, tool list and a real conversation. */
export const MIN_OLLAMA_CONTEXT = 16384;

const OLLAMA_FIX = `Restart it with: OLLAMA_CONTEXT_LENGTH=${MIN_OLLAMA_CONTEXT} ollama serve`;

/**
 * Local Ollama silently cuts a prompt that does not fit its context window (4096 tokens by
 * default on many builds): the middle goes first, so the model sees the system prompt and the
 * last message but none of the earlier conversation and "forgets" what was said before.
 * Returns null when the active provider is not a local Ollama.
 */
export async function checkOllamaContext(
  config: Pick<AgentConfig, 'provider' | 'baseUrl' | 'model'>,
  fetchImpl: typeof fetch = fetch,
): Promise<CheckItem | null> {
  const base = (config.baseUrl || (config.provider === 'ollama' ? 'http://127.0.0.1:11434/v1' : '')).replace(/\/+$/, '');
  if (!base) return null;
  let origin: string;
  try {
    const u = new URL(base);
    const local = ['localhost', '127.0.0.1', '[::1]', '::1', '0.0.0.0'].includes(u.hostname);
    if (!(config.provider === 'ollama' || (local && u.port === '11434'))) return null;
    origin = u.origin;
  } catch {
    return null;
  }

  const getJson = async (url: string): Promise<any | null> => {
    try {
      const res = await fetchImpl(url, { signal: AbortSignal.timeout(3000) });
      return res.ok ? await res.json() : null;
    } catch {
      return null;
    }
  };

  const name = 'Ollama Context Window';
  if (!(await getJson(`${origin}/api/version`))) {
    return { name, status: 'warn', details: `Ollama is not answering at ${origin}. Start it with: ollama serve` };
  }

  // /api/ps lists the models that are loaded right now, with the window they were loaded with.
  const ps = await getJson(`${origin}/api/ps`);
  const loaded: any[] = Array.isArray(ps?.models) ? ps.models : [];
  const want = (config.model || '').toLowerCase();
  const entry = loaded.find((m) => [m?.name, m?.model].some((n) => typeof n === 'string' && n.toLowerCase() === want))
    ?? (loaded.length === 1 ? loaded[0] : undefined);
  const ctx = Number(entry?.context_length);
  if (!Number.isFinite(ctx) || ctx <= 0) {
    return {
      name,
      status: 'warn',
      details: `Cannot tell yet (the model is not loaded). Send one message, then run /doctor again. ` +
        `If the model forgets earlier messages, the window is too small: ${OLLAMA_FIX}`,
    };
  }
  if (ctx < MIN_OLLAMA_CONTEXT) {
    return {
      name,
      status: 'warn',
      details: `${ctx} tokens: too small, Ollama drops earlier messages and the model forgets the conversation. ${OLLAMA_FIX}`,
    };
  }
  return { name, status: 'ok', details: `${ctx} tokens` };
}

export async function runDoctor(config: AgentConfig): Promise<void> {
  const theme = getCurrentTheme();
  console.log('\n' + theme.boldFn('devx Doctor — System & Environment Health Diagnostics'));
  console.log(pc.dim('──────────────────────────────────────────────────────────────────'));

  const envChecks: CheckItem[] = [];
  const toolChecks: CheckItem[] = [];
  const keyChecks: CheckItem[] = [];
  const netChecks: CheckItem[] = [];

  // 1. Environment Checks
  const nodeVer = process.version;
  const nodeMajor = parseInt(nodeVer.replace(/^v/, '').split('.')[0], 10);
  envChecks.push({
    name: 'Node.js Runtime',
    status: nodeMajor >= 20 ? 'ok' : 'warn',
    details: `${nodeVer} (Recommended: >= 20.0)`
  });

  const termux = isTermux();
  envChecks.push({
    name: 'Platform / Environment',
    status: 'ok',
    details: termux ? 'Android (Termux ARM64)' : `${os.platform()} ${os.arch()} (${os.release()})`
  });

  const cwd = process.cwd();
  envChecks.push({
    name: 'Working Directory',
    status: 'ok',
    details: cwd
  });

  // 2. Essential Tools
  const gitVer = checkCmd('git --version');
  toolChecks.push({
    name: 'Git VCS',
    status: gitVer ? 'ok' : 'warn',
    details: gitVer || 'Not installed (some git features disabled)'
  });

  const npmVer = checkCmd('npm --version');
  toolChecks.push({
    name: 'NPM Package Manager',
    status: npmVer ? 'ok' : 'warn',
    details: npmVer ? `v${npmVer}` : 'Not installed'
  });

  const pythonVer = checkCmd('python --version') || checkCmd('python3 --version');
  toolChecks.push({
    name: 'Python Runtime',
    status: pythonVer ? 'ok' : 'warn',
    details: pythonVer || 'Optional (not found)'
  });

  const cCompiler = checkCmd('clang --version') || checkCmd('gcc --version');
  toolChecks.push({
    name: 'C/C++ Compiler',
    status: cCompiler ? 'ok' : 'warn',
    details: cCompiler ? cCompiler.split(' ')[0] : 'Optional (not found)'
  });

  // 3. AI Providers & Keys
  const activeProvider = config.provider || 'openrouter';
  const hasActiveKey = !!config.apiKey;
  keyChecks.push({
    name: `Active Provider (${activeProvider})`,
    status: hasActiveKey ? 'ok' : 'fail',
    details: hasActiveKey ? `Configured (Model: ${config.model})` : 'Missing API Key! Set via /provider or ~/.devxrc.json'
  });

  const envOpenRouter = !!process.env.OPENROUTER_API_KEY;
  const envOpenAI = !!process.env.OPENAI_API_KEY;
  const envAnthropic = !!process.env.ANTHROPIC_API_KEY;
  const envGoogle = !!process.env.GEMINI_API_KEY;

  keyChecks.push({
    name: 'OpenRouter Key',
    status: envOpenRouter || config.provider === 'openrouter' && hasActiveKey ? 'ok' : 'warn',
    details: envOpenRouter || (config.provider === 'openrouter' && hasActiveKey) ? 'Configured' : 'Not set'
  });

  keyChecks.push({
    name: 'OpenAI / Gemini / Anthropic Keys',
    status: envOpenAI || envAnthropic || envGoogle ? 'ok' : 'warn',
    details: [envOpenAI && 'OpenAI', envAnthropic && 'Anthropic', envGoogle && 'Gemini'].filter(Boolean).join(', ') || 'Not set in env (using default provider)'
  });

  // 4. Network Connectivity
  const openRouterOnline = await pingUrl('https://openrouter.ai');
  netChecks.push({
    name: 'OpenRouter API Connectivity',
    status: openRouterOnline ? 'ok' : 'warn',
    details: openRouterOnline ? 'Online (HTTP 200/reachable)' : 'Unreachable (check VPN/DNS/WiFi)'
  });

  const githubOnline = await pingUrl('https://api.github.com');
  netChecks.push({
    name: 'GitHub API (for updater)',
    status: githubOnline ? 'ok' : 'warn',
    details: githubOnline ? 'Online (reachable)' : 'Unreachable'
  });

  const ollamaCheck = await checkOllamaContext(config);
  if (ollamaCheck) keyChecks.push(ollamaCheck);

  // Helper to render section
  const renderSection = (title: string, items: CheckItem[]) => {
    console.log('\n' + theme.colorFn(pc.bold(`• ${title}`)));
    for (const item of items) {
      let icon = pc.green('✓');
      if (item.status === 'warn') icon = pc.yellow('▲');
      if (item.status === 'fail') icon = pc.red('✖');
      console.log(`  ${icon} ${pc.bold(item.name)}: ${pc.dim(item.details)}`);
    }
  };

  renderSection('Environment & OS', envChecks);
  renderSection('Developer Tools', toolChecks);
  renderSection('AI Configuration & Keys', keyChecks);
  renderSection('Network Connectivity', netChecks);

  console.log('\n' + pc.dim('──────────────────────────────────────────────────────────────────'));
  const hasFailures = [...envChecks, ...toolChecks, ...keyChecks, ...netChecks].some(i => i.status === 'fail');
  if (hasFailures) {
    console.log(pc.red(pc.bold('Doctor found critical issues that might prevent devx from functioning properly.')));
  } else {
    console.log(theme.boldFn('Everything looks healthy! devx is fully configured and ready for vibe-coding.'));
  }
  console.log();
}
