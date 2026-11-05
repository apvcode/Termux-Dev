/**
 * What a request cost, and how sure we are about it.
 *
 * Sources, best first:
 *   reported    the provider put the cost into its response (OpenRouter `usage.cost`, LiteLLM, ...)
 *   configured  the user wrote the prices into the config ("prices")
 *   listed      the endpoint's own model list carries the prices (OpenRouter, LiteLLM)
 *   estimated   the models.dev catalogue price of the same model; a gateway may charge differently
 *   free        a local server or a ":free" model
 * When none of them knows the model the cost is left undefined: "unknown" is shown as such and
 * never as $0.
 */
const PER_MILLION = 1_000_000;
const finite = (value) => typeof value === 'number' && Number.isFinite(value) && value >= 0;
/** The cost of the tokens at the given rates. */
export function computeCost(rates, usage) {
    const prompt = Math.max(0, usage.promptTokens || 0);
    const tier = rates.tier && prompt > rates.tier.over ? rates.tier : null;
    const input = tier?.input ?? rates.input;
    const output = tier?.output ?? rates.output;
    const cacheRead = tier ? (tier.cacheRead ?? rates.cacheRead) : rates.cacheRead;
    const cacheWrite = tier ? (tier.cacheWrite ?? rates.cacheWrite) : rates.cacheWrite;
    const read = Math.min(prompt, Math.max(0, usage.cacheReadTokens || 0));
    const written = Math.min(prompt - read, Math.max(0, usage.cacheWriteTokens || 0));
    const plain = prompt - read - written;
    // Without a cache price the cached tokens cost as much as ordinary input.
    let cost = plain * input + read * (cacheRead ?? input) + written * (cacheWrite ?? input);
    const completion = Math.max(0, usage.completionTokens || 0);
    const reasoning = Math.min(completion, Math.max(0, usage.reasoningTokens || 0));
    if (rates.reasoning !== undefined && reasoning > 0) {
        cost += (completion - reasoning) * output + reasoning * rates.reasoning;
    }
    else {
        cost += completion * output;
    }
    return cost / PER_MILLION;
}
/** A price as the OpenRouter list writes it: USD per token, often a string. */
function perToken(value) {
    const n = typeof value === 'string' ? Number(value) : value;
    return finite(n) ? n * PER_MILLION : undefined;
}
/**
 * Reads the prices from one entry of an endpoint's model list. Understands the OpenRouter
 * schema (`pricing.prompt` / `completion` / `input_cache_read` in USD per token) and the
 * LiteLLM schema (`input_cost_per_token`). Returns undefined when the entry has no prices.
 * A negative price (OpenRouter's "variable") is not a price.
 */
export function priceFromModelEntry(entry) {
    if (!entry || typeof entry !== 'object')
        return undefined;
    const pricing = entry.pricing;
    if (pricing && typeof pricing === 'object') {
        const input = perToken(pricing.prompt ?? pricing.input);
        const output = perToken(pricing.completion ?? pricing.output);
        if (input !== undefined && output !== undefined) {
            const rates = { input, output };
            const read = perToken(pricing.input_cache_read ?? pricing.cache_read);
            const write = perToken(pricing.input_cache_write ?? pricing.cache_write);
            const reasoning = perToken(pricing.internal_reasoning);
            if (read !== undefined)
                rates.cacheRead = read;
            if (write !== undefined)
                rates.cacheWrite = write;
            if (reasoning !== undefined && reasoning > 0 && reasoning !== output)
                rates.reasoning = reasoning;
            return rates;
        }
    }
    const info = entry.model_info ?? entry;
    const input = perToken(info.input_cost_per_token);
    const output = perToken(info.output_cost_per_token);
    if (input !== undefined && output !== undefined) {
        const rates = { input, output };
        const read = perToken(info.cache_read_input_token_cost);
        const write = perToken(info.cache_creation_input_token_cost);
        if (read !== undefined)
            rates.cacheRead = read;
        if (write !== undefined)
            rates.cacheWrite = write;
        return rates;
    }
    return undefined;
}
function numberOf(value) {
    const n = typeof value === 'string' ? Number(value) : value;
    return finite(n) ? n : undefined;
}
/**
 * The cost the provider itself reported in the usage block (several spellings exist).
 * Returns undefined when it did not report one.
 */
export function reportedCost(usage) {
    if (!usage || typeof usage !== 'object')
        return undefined;
    for (const key of ['cost', 'total_cost', 'cost_usd', 'estimated_cost']) {
        const value = usage[key];
        const direct = numberOf(value);
        if (direct !== undefined)
            return direct;
        if (value && typeof value === 'object') {
            const nested = numberOf(value.total ?? value.total_cost ?? value.usd);
            if (nested !== undefined)
                return nested;
        }
    }
    return undefined;
}
export function isLocalBaseUrl(baseUrl) {
    if (!baseUrl)
        return false;
    let host = '';
    try {
        host = new URL(baseUrl).hostname.toLowerCase();
    }
    catch {
        return false;
    }
    return (host === 'localhost' || host === '[::1]' || host === '::1' ||
        /^127\./.test(host) || /^10\./.test(host) || /^192\.168\./.test(host) || /^172\.(1[6-9]|2\d|3[01])\./.test(host) ||
        host.endsWith('.local'));
}
export function isFreeModel(model, baseUrl) {
    const id = (model || '').toLowerCase().trim();
    return id.endsWith(':free') || isLocalBaseUrl(baseUrl);
}
/**
 * "$0.0123" exact, "~$0.0123" estimated, "$0.0123+" when some requests are missing,
 * "n/a" when nothing could be priced at all.
 */
export function formatMoney(cost, state = {}) {
    const unpriced = state.unpriced ?? 0;
    const priced = state.priced ?? (cost > 0 ? 1 : 0);
    if (unpriced > 0 && priced === 0 && cost === 0)
        return 'n/a';
    const digits = cost >= 1 ? 2 : 4;
    let text = `$${cost.toFixed(digits)}`;
    if (state.estimated)
        text = `~${text}`;
    if (unpriced > 0)
        text += '+';
    return text;
}
/** One line that says what the number means (for /usage). */
export function describeCostState(state) {
    const parts = [];
    if (state.estimated)
        parts.push('estimated from catalogue prices');
    if ((state.unpriced ?? 0) > 0)
        parts.push(`${state.unpriced} request${state.unpriced === 1 ? '' : 's'} without a known price`);
    return parts.join(', ');
}
/** Adds up the cost of the requests of a session. */
export class CostLedger {
    total = 0;
    estimated = false;
    unpriced = 0;
    priced = 0;
    add(usage) {
        if (!usage)
            return;
        if (typeof usage.cost === 'number' && Number.isFinite(usage.cost)) {
            this.total += usage.cost;
            this.priced++;
            if (usage.costSource === 'estimated')
                this.estimated = true;
        }
        else {
            this.unpriced++;
        }
    }
    reset() {
        this.total = 0;
        this.estimated = false;
        this.unpriced = 0;
        this.priced = 0;
    }
    /** Restores a saved session (only the total was stored; it is shown as an estimate if it was one). */
    restore(total, estimated = false) {
        this.reset();
        this.total = Number.isFinite(total) ? total : 0;
        this.estimated = estimated;
        this.priced = this.total > 0 ? 1 : 0;
    }
    state() {
        return { estimated: this.estimated, unpriced: this.unpriced, priced: this.priced };
    }
    format() {
        return formatMoney(this.total, this.state());
    }
}
