/**
 * Applies a project-level config file (.devx.json / .devxrc.json found in the
 * working directory) on top of the user's own configuration.
 *
 * That file comes from the repository, i.e. from whoever wrote the repository, so:
 *  - harmless tuning (model, limits, theme) is always applied;
 *  - everything that decides WHERE the conversation and the API key are sent
 *    (provider, apiKey, baseUrl) or that loosens the permission guard
 *    (autoApprove, bashAllowlist) is applied only for a workspace the user trusted.
 *
 * Without that split a cloned repository could set `baseUrl` to its own server and
 * receive the user's API key together with the contents of their files.
 *
 * Returns the names of the settings that were ignored.
 */
export function applyLocalConfig(target: Record<string, any>, local: any, trusted: boolean): string[] {
  const ignored: string[] = [];
  if (!local || typeof local !== 'object') return ignored;

  if (local.model) target.model = local.model;
  if (local.maxIterations) target.maxIterations = local.maxIterations;
  if (local.maxContextTokens) target.maxContextTokens = local.maxContextTokens;
  if (local.dataSaverLimitMB) target.dataSaverLimitMB = local.dataSaverLimitMB;
  if (local.pureBlackTheme !== undefined) target.pureBlackTheme = local.pureBlackTheme;

  const elevated: Array<[string, boolean]> = [
    ['provider', !!local.provider],
    ['apiKey', !!local.apiKey],
    ['baseUrl', !!local.baseUrl],
    ['autoApprove', local.autoApprove !== undefined],
    ['bashAllowlist', Array.isArray(local.bashAllowlist)],
    ['hooks', local.hooks !== undefined],
  ];

  for (const [key, present] of elevated) {
    if (!present) continue;
    if (trusted) {
      target[key] = local[key];
    } else {
      ignored.push(key);
    }
  }
  return ignored;
}

/**
 * Settings that last for one run only and are never written to ~/.devxrc.json.
 *  - `autoApprove` (YOLO): switched on in the settings or with `--yolo`, it is off again the
 *    next time devx starts. A mode that skips confirmations should not stay on by accident.
 *  - `autoApproveAll` (Extra): YOLO without the safety net, the same.
 *  - `projectMcpTrusted`, `effortOverride`: runtime flags.
 */
export const SESSION_ONLY_SETTINGS = ['autoApprove', 'autoApproveAll', 'projectMcpTrusted', 'effortOverride'] as const;

/** The config as it goes to disk: without the settings that last for one run only. */
export function persistedConfig<T extends Record<string, any>>(config: T): Partial<T> {
  const out: Record<string, any> = { ...config };
  for (const key of SESSION_ONLY_SETTINGS) delete out[key];
  return out as Partial<T>;
}

/** The config as it was read from disk: a value left there by an older version is dropped. */
export function withoutSessionOnlySettings<T extends Record<string, any>>(config: T): T {
  const out: Record<string, any> = { ...config };
  delete out.autoApprove;
  delete out.autoApproveAll;
  return out as T;
}
