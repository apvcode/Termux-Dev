import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { catalogContextWindow } from './vision.js';
const FALLBACK_LIMITS = {
    'claude-3.5-sonnet': 200000,
    'claude-3-opus': 200000,
    'gemini-2.5-flash': 1048576,
    'gemini-2.5-pro': 1048576,
    'gemini-2.0-flash': 1048576,
    'gpt-4o': 128000,
    'gpt-4o-mini': 128000,
    'deepseek-chat': 128000,
    'deepseek-r1': 128000,
    'qwen-2.5-coder': 131072,
};
let limitsCache = null;
function loadLimits() {
    if (limitsCache)
        return limitsCache;
    try {
        const __dirname = path.dirname(fileURLToPath(import.meta.url));
        const jsonPath = path.join(__dirname, 'model-limits.json');
        if (fs.existsSync(jsonPath)) {
            limitsCache = JSON.parse(fs.readFileSync(jsonPath, 'utf8'));
            return limitsCache;
        }
    }
    catch { }
    try {
        const fallbackPath = path.join(process.cwd(), 'src/core/model-limits.json');
        if (fs.existsSync(fallbackPath)) {
            limitsCache = JSON.parse(fs.readFileSync(fallbackPath, 'utf8'));
            return limitsCache;
        }
    }
    catch { }
    limitsCache = { ...FALLBACK_LIMITS };
    return limitsCache;
}
export function getModelContextLimit(modelName) {
    return getModelContextInfo(modelName).limit;
}
/**
 * The context window of a model and how sure we are. The models.dev catalogue is asked first
 * (it knows the maker's own number for thousands of models and is refreshed with /effort refresh),
 * then devx's table, then rules by name, and only then a default of 128k.
 */
export function getModelContextInfo(modelName) {
    const fromCatalogue = catalogContextWindow(modelName || '');
    if (fromCatalogue)
        return { limit: fromCatalogue, source: 'catalogue' };
    const rule = (limit) => ({ limit, source: 'rule' });
    const cache = loadLimits();
    const clean = (modelName || '').trim().toLowerCase();
    const short = clean.split('/').pop() || clean;
    if (cache[clean])
        return { limit: cache[clean], source: 'table' };
    if (cache[short])
        return { limit: cache[short], source: 'table' };
    const baseName = short.replace(/-\d{4,8}$/, '').replace(/:latest$/, '');
    if (cache[baseName])
        return { limit: cache[baseName], source: 'table' };
    if (clean.includes('kimi-k2') || clean.includes('kimi'))
        return rule(256000);
    if (clean.includes('ox-alpha') || clean.includes('stealth'))
        return rule(1048576);
    if (clean.includes('gemini-3') || clean.includes('gemini-2') || clean.includes('gemini-1.5') || clean.includes('gemini'))
        return rule(1048576);
    if (clean.includes('claude-opus-4') || clean.includes('claude-4'))
        return rule(1000000);
    if (clean.includes('claude-3-5') || clean.includes('claude-3.5') || clean.includes('claude-3-7') || clean.includes('claude-3.7') || clean.includes('claude'))
        return rule(200000);
    if (clean.includes('gpt-5'))
        return rule(1050000);
    if (clean.includes('gpt-4o') || clean.includes('gpt-4-turbo') || clean.includes('o1') || clean.includes('o3'))
        return rule(128000);
    if (clean.includes('deepseek-v4') || clean.includes('deepseek-v3') || clean.includes('deepseek-r1') || clean.includes('deepseek'))
        return rule(128000);
    if (clean.includes('qwen3.5') || clean.includes('qwen-3.5'))
        return rule(262144);
    if (clean.includes('qwen2.5') || clean.includes('qwen-2.5') || clean.includes('qwen'))
        return rule(131072);
    if (clean.includes('glm-5') || clean.includes('glm-4.7') || clean.includes('glm-4.6'))
        return rule(200000);
    if (clean.includes('glm-4'))
        return rule(128000);
    if (clean.includes('minimax-m1') || clean.includes('minimax'))
        return rule(1000000);
    if (clean.includes('doubao'))
        return rule(256000);
    if (clean.includes('llama-3.3') || clean.includes('llama-3.1') || clean.includes('llama-3'))
        return rule(128000);
    if (clean.includes('mistral-large') || clean.includes('codestral'))
        return rule(128000);
    return { limit: 128000, source: 'default' };
}
