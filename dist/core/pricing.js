import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
let pricingCache = null;
function loadPricing() {
    if (pricingCache)
        return pricingCache;
    try {
        const __dirname = path.dirname(fileURLToPath(import.meta.url));
        const jsonPath = path.join(__dirname, 'model-pricing.json');
        if (fs.existsSync(jsonPath)) {
            pricingCache = JSON.parse(fs.readFileSync(jsonPath, 'utf8'));
            return pricingCache;
        }
    }
    catch { }
    try {
        const fallbackPath = path.join(process.cwd(), 'src/core/model-pricing.json');
        if (fs.existsSync(fallbackPath)) {
            pricingCache = JSON.parse(fs.readFileSync(fallbackPath, 'utf8'));
            return pricingCache;
        }
    }
    catch { }
    pricingCache = {};
    return pricingCache;
}
export function getModelPricing(modelName) {
    const clean = (modelName || '').trim().toLowerCase();
    if (clean.endsWith(':free') ||
        clean.includes('/free') ||
        clean === 'free' ||
        clean.includes('free-models/') ||
        clean.includes('stealth') ||
        clean.includes('local') ||
        clean.includes('ollama') ||
        clean.includes('lmstudio')) {
        return { input: 0, output: 0 };
    }
    const cache = loadPricing();
    const short = clean.split('/').pop() || clean;
    if (cache[clean])
        return cache[clean];
    if (cache[short])
        return cache[short];
    const baseName = short.replace(/-\d{4,8}$/, '').replace(/:latest$/, '');
    if (cache[baseName])
        return cache[baseName];
    if (clean.includes('gpt-4o-mini'))
        return { input: 0.15, output: 0.60 };
    if (clean.includes('gpt-4o'))
        return { input: 2.5, output: 10.0 };
    if (clean.includes('deepseek'))
        return { input: 0.14, output: 0.28 };
    if (clean.includes('claude-3-5-sonnet') || clean.includes('claude-3.5-sonnet'))
        return { input: 3.0, output: 15.0 };
    if (clean.includes('gemini-2.0-flash') || clean.includes('gemini-1.5-flash'))
        return { input: 0.1, output: 0.4 };
    return { input: 0, output: 0 };
}
export function calculateCost(modelName, promptTokens, completionTokens) {
    const { input, output } = getModelPricing(modelName);
    if (input === 0 && output === 0)
        return 0;
    return (promptTokens * (input / 1_000_000)) + (completionTokens * (output / 1_000_000));
}
