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
