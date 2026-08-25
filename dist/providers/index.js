import { OpenAIProvider } from './openai.js';
export function createProvider(config) {
    const baseUrl = config.baseUrl || 'https://api.openai.com/v1';
    const apiKey = config.apiKey || process.env.OPENAI_API_KEY || '';
    const model = config.model || 'gpt-4o-mini';
    return new OpenAIProvider(baseUrl, apiKey, model);
}
