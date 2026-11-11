import fs from 'fs/promises';
import fsSync from 'fs';
import os from 'os';
import path from 'path';
import { writeSecureFile } from '../core/secure-file.js';
import { applyLocalConfig, withoutSessionOnlySettings } from '../core/local-config.js';
import { getModelContextInfo } from '../core/models.js';
import { discoverProjectMcpServers, hashProjectMcpServers } from '../mcp/manager.js';
import { UsageTracker } from '../core/usage.js';

export const CONFIG_PATH = () => process.env.DEVX_CONFIG_PATH || path.join(os.homedir(), '.devxrc.json');

export interface LoadedConfig {
  config: any;
  /** The folder is marked as trusted: its own settings and MCP servers count. */
  trusted: boolean;
  /** The project declares MCP servers that nobody approved yet (they are not started). */
  mcpPending: number;
}

/** Same rules as the terminal start-up, without any question: what is not approved stays off. */
export async function loadRemoteConfig(cwd: string): Promise<LoadedConfig> {
  let globalConfig: any;
  try {
    globalConfig = withoutSessionOnlySettings(JSON.parse(await fs.readFile(CONFIG_PATH(), 'utf8')));
  } catch {
    throw new Error('No configuration in ~/.devxrc.json. Run devx in a terminal once and choose a provider.');
  }
  if (!globalConfig.model || !globalConfig.baseUrl) throw new Error('The configuration has no provider or model. Run devx in a terminal once.');

  const own = globalConfig.contextLimits?.[globalConfig.model];
  const info = getModelContextInfo(globalConfig.model);
  globalConfig.maxContextTokens =
    typeof own === 'number' && own >= 1024 ? own
      : info.source === 'catalogue' ? info.limit
      : typeof globalConfig.maxContextTokens === 'number' && globalConfig.maxContextTokens > 0 ? globalConfig.maxContextTokens
      : info.limit;
  globalConfig.apiKeys = globalConfig.apiKeys || {};
  globalConfig.baseUrls = globalConfig.baseUrls || {};
  globalConfig.trustedProjects = globalConfig.trustedProjects || {};
  if (globalConfig.provider && globalConfig.apiKey) globalConfig.apiKeys[globalConfig.provider] = globalConfig.apiKey;

  let local: any = null;
  for (const name of ['.devx.json', '.devxrc.json']) {
    const file = path.join(cwd, name);
    try {
      if (fsSync.existsSync(file)) { local = JSON.parse(await fs.readFile(file, 'utf8')); break; }
    } catch {}
  }
  const trusted = globalConfig.trustedProjects[cwd] === true;
  const effective = { ...globalConfig };
  applyLocalConfig(effective, local, trusted);

  effective.projectMcpTrusted = false;
  let mcpPending = 0;
  const servers = discoverProjectMcpServers(cwd);
  if (servers.length > 0) {
    if (globalConfig.trustedMcp?.[cwd] === hashProjectMcpServers(servers)) effective.projectMcpTrusted = true;
    else mcpPending = servers.length;
  }
  if (effective.dataSaverLimitMB) UsageTracker.getInstance().setLimit(effective.dataSaverLimitMB);
  return { config: effective, trusted, mcpPending };
}

/** Changes the config file: reads it fresh, lets `change` edit it, writes it back (private, atomically). */
export async function patchGlobalConfig(change: (config: any) => void): Promise<void> {
  let current: any = {};
  try { current = JSON.parse(await fs.readFile(CONFIG_PATH(), 'utf8')); } catch {}
  change(current);
  const tmp = `${CONFIG_PATH()}.${process.pid}.tmp`;
  await writeSecureFile(tmp, JSON.stringify(current, null, 2));
  await fs.rename(tmp, CONFIG_PATH());
}

/** The settings of one session: the global ones with the provider, key and model of this session on top. */
export function forProvider(config: any, providerId: string, model: string): any {
  const next = { ...config, provider: providerId, model };
  const baseUrl = config.baseUrls?.[providerId];
  if (baseUrl) next.baseUrl = baseUrl;
  const key = config.apiKeys?.[providerId];
  if (key !== undefined) next.apiKey = key;
  const own = config.contextLimits?.[model];
  next.maxContextTokens = typeof own === 'number' && own >= 1024 ? own : getModelContextInfo(model).limit;
  return next;
}
