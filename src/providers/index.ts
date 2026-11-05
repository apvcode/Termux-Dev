import { LLMProvider } from '../core/types.js';
import { effectiveReasoning } from '../core/reasoning.js';
import { resolveFormat } from '../core/formats.js';
import { modelAcceptsImages } from '../core/vision.js';
import { OpenAIProvider } from './openai.js';
import { AnthropicProvider } from './anthropic.js';

export function createProvider(config: any): LLMProvider {
  const baseUrl = config.baseUrl || 'https://api.openai.com/v1';
  const apiKey = config.apiKey || process.env.OPENAI_API_KEY || '';
  const model = config.model || 'gpt-4o-mini';

  // OpenAI or Anthropic wire format: by the base URL, or by what the gateway offers for this model.
  const format = resolveFormat({ baseUrl, model, apiFormat: config.apiFormat });

  // The reasoning level chosen with /effort, translated for this model and this API.
  const { params } = effectiveReasoning({ ...config, model, baseUrl });
  // Images are sent unless the catalogue says the model takes text only.
  const options = { price: { provider: config.provider, prices: config.prices }, images: modelAcceptsImages(model) !== false };

  if (format === 'anthropic') {
    return new AnthropicProvider(baseUrl, apiKey || process.env.ANTHROPIC_API_KEY || '', model, params ?? undefined, options);
  }
  return new OpenAIProvider(baseUrl, apiKey, model, params ?? undefined, options);
}

/** Which format the provider for this config speaks (for the status line and notices). */
export function providerFormat(config: any): 'openai' | 'anthropic' {
  return resolveFormat({ baseUrl: config.baseUrl || 'https://api.openai.com/v1', model: config.model || 'gpt-4o-mini', apiFormat: config.apiFormat });
}
