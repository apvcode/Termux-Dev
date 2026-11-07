import { LLMProvider } from '../core/types.js';
import { effectiveReasoning } from '../core/reasoning.js';
import { resolveFormat } from '../core/formats.js';
import { modelAcceptsImages } from '../core/vision.js';
import { OpenAIProvider } from './openai.js';
import { AnthropicProvider } from './anthropic.js';

export interface ProviderOptions {
  /**
   * For small side jobs (a chat title, a commit message, a summary): the lightest reasoning level
   * the model offers instead of the one chosen for the work, so a thinking model does not burn
   * hidden reasoning tokens on a few words.
   */
  light?: boolean;
}

const LEVEL_ORDER = ['off', 'low', 'normal', 'high', 'xhigh', 'max'] as const;

export function createProvider(config: any, opts: ProviderOptions = {}): LLMProvider {
  const baseUrl = config.baseUrl || 'https://api.openai.com/v1';
  const apiKey = config.apiKey || process.env.OPENAI_API_KEY || '';
  const model = config.model || 'gpt-4o-mini';

  // OpenAI or Anthropic wire format: by the base URL, or by what the gateway offers for this model.
  const format = resolveFormat({ baseUrl, model, apiFormat: config.apiFormat });

  // The reasoning level chosen with /effort, translated for this model and this API.
  const chosen = effectiveReasoning({ ...config, model, baseUrl });
  let params = chosen.params;
  if (opts.light && chosen.caps.supported) {
    const lightest = LEVEL_ORDER.find((level) => chosen.caps.levels.includes(level));
    if (lightest) params = effectiveReasoning({ ...config, model, baseUrl, effortOverride: lightest }).params;
  }
  // Images are sent unless the catalogue says the model takes text only.
  const seconds = Number(config.requestTimeoutSeconds);
  const options = {
    price: { provider: config.provider, prices: config.prices },
    images: modelAcceptsImages(model) !== false,
    // 0 turns the limit off; anything that is not a number keeps the default.
    timeoutMs: Number.isFinite(seconds) && seconds >= 0 && config.requestTimeoutSeconds !== undefined && config.requestTimeoutSeconds !== '' ? seconds * 1000 : undefined,
  };

  if (format === 'anthropic') {
    return new AnthropicProvider(baseUrl, apiKey || process.env.ANTHROPIC_API_KEY || '', model, params ?? undefined, options);
  }
  return new OpenAIProvider(baseUrl, apiKey, model, params ?? undefined, options);
}

/** Which format the provider for this config speaks (for the status line and notices). */
export function providerFormat(config: any): 'openai' | 'anthropic' {
  return resolveFormat({ baseUrl: config.baseUrl || 'https://api.openai.com/v1', model: config.model || 'gpt-4o-mini', apiFormat: config.apiFormat });
}
