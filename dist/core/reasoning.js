/**
 * Reasoning ("thinking") effort.
 *
 * Models differ a lot: OpenAI's o3 has low / medium / high and cannot switch reasoning off,
 * Claude takes a thinking budget in tokens, Gemini 2.5 Pro cannot be switched off, DeepSeek-R1
 * always reasons and has no setting at all. And every API wants the value in another
 * parameter. This module answers two questions:
 *
 *   1. What can THIS model do?   -> `resolveReasoning(...).levels` (only these are offered)
 *   2. How is a level sent?      -> `caps.build(level)` (the request parameters for this API)
 *
 * The first answer comes from, in this order: a profile in the config file, what the
 * endpoint itself reports (OpenRouter-style metadata, Ollama, LiteLLM...), the models.dev
 * catalogue, and finally rules by model name. The second comes from the route (by base URL).
 * A model that nothing describes gets no levels instead of a guess.
 */
export const LEVELS = ['off', 'low', 'normal', 'high', 'xhigh', 'max'];
export const isLevel = (value) => typeof value === 'string' && LEVELS.includes(value);
// ───────────────────────── routes ─────────────────────────
export function detectRoute(baseUrl = '') {
    let host = baseUrl.toLowerCase();
    try {
        host = new URL(baseUrl).hostname.toLowerCase();
    }
    catch { }
    if (host.endsWith('openrouter.ai'))
        return 'openrouter';
    if (host === 'api.openai.com')
        return 'openai';
    if (host === 'api.anthropic.com')
        return 'anthropic';
    if (host.endsWith('generativelanguage.googleapis.com'))
        return 'google';
    if (host.endsWith('aliyuncs.com'))
        return 'dashscope';
    if (host === 'api.z.ai' || host.endsWith('bigmodel.cn'))
        return 'zai';
    if (host.endsWith('deepseek.com'))
        return 'deepseek';
    return 'generic';
}
const ALWAYS_ON = [
    /^deepseek-reasoner/, /^deepseek-r1/, /^r1-/, /-r1(-|$)/, /^qwq/, /^magistral/, /thinking(-\d+)?$/,
    /^kimi-k2-thinking/, /^minimax-m2/, /^glm-z1/, /^sonar-reasoning/, /^grok-4/,
];
const OPENAI_EFFORT = { low: 'low', normal: 'medium', high: 'high' };
function normalizeModel(model) {
    const parts = model.toLowerCase().trim().split('/');
    const name = (parts[parts.length - 1] || '').replace(/:.*$/, '');
    return { vendor: parts.length > 1 ? parts[parts.length - 2] : '', name };
}
function familyFor(name) {
    // Specific families first, the "always on" list last-but-one so that e.g. grok-3-mini wins over grok-4.
    if (/^gpt-oss/.test(name)) {
        return { id: 'gpt-oss', kind: 'effort', levels: ['low', 'normal', 'high'], confidence: 'known', effort: OPENAI_EFFORT };
    }
    if (/^o[1345](-mini|-pro|-preview)?(-\d{4}-\d{2}-\d{2})?$/.test(name) && !/^o1-(mini|preview)/.test(name)) {
        if (/-pro/.test(name))
            return { id: 'openai-o-pro', kind: 'effort', levels: ['high'], confidence: 'known', effort: { high: 'high' } };
        return { id: 'openai-o', kind: 'effort', levels: ['low', 'normal', 'high'], confidence: 'known', effort: OPENAI_EFFORT };
    }
    const gpt5 = name.match(/^gpt-5(?:\.(\d+))?/);
    if (gpt5) {
        if (/chat/.test(name))
            return null; // gpt-5-chat does not reason
        const minor = gpt5[1] ? parseInt(gpt5[1], 10) : 0;
        if (/-pro/.test(name))
            return { id: 'gpt-5-pro', kind: 'effort', levels: ['high'], confidence: 'known', effort: { high: 'high' } };
        const levels = ['off', 'low', 'normal', 'high'];
        const effort = {
            // gpt-5 itself cannot be switched off, "minimal" is the closest; 5.1 and later accept "none"
            off: minor === 0 ? 'minimal' : 'none', low: 'low', normal: 'medium', high: 'high',
        };
        if (minor >= 2 || /codex-max/.test(name)) {
            levels.push('xhigh');
            effort.xhigh = 'xhigh';
        }
        return { id: minor === 0 ? 'gpt-5' : `gpt-5.${minor}`, kind: 'effort', levels, confidence: minor <= 2 ? 'known' : 'guess', effort };
    }
    if (/^grok-3-mini/.test(name)) {
        return { id: 'grok-3-mini', kind: 'effort', levels: ['low', 'high'], confidence: 'known', effort: { low: 'low', high: 'high' } };
    }
    if (/^claude-/.test(name) && /claude-(3[-.]7|(opus|sonnet|haiku)-[4-9]|[4-9])/.test(name)) {
        // Opus 4 / 4.1 can write at most 32k tokens, newer models 64k: that bounds the thinking budget.
        const smallOutput = /opus-4(?![.-]?[5-9])/.test(name);
        const levels = smallOutput ? ['off', 'low', 'normal', 'high', 'xhigh'] : ['off', 'low', 'normal', 'high', 'xhigh', 'max'];
        return {
            id: 'claude', kind: 'budget', levels, confidence: 'known',
            budgets: { low: 2048, normal: 6144, high: 16000, xhigh: smallOutput ? 24000 : 32000, max: 63000 },
        };
    }
    if (/^gemini-2\.5-pro/.test(name)) {
        return {
            id: 'gemini-2.5-pro', kind: 'budget', levels: ['low', 'normal', 'high', 'max'], confidence: 'known', defaultOn: true,
            budgets: { low: 1024, normal: 8192, high: 24576, max: 32768 },
        };
    }
    if (/^gemini-2\.5-flash/.test(name)) {
        return {
            id: 'gemini-2.5-flash', kind: 'budget', levels: ['off', 'low', 'normal', 'high', 'max'], confidence: 'known', defaultOn: true,
            budgets: { off: 0, low: 1024, normal: 8192, high: 16384, max: 24576 },
        };
    }
    if (/^gemini-3/.test(name)) {
        return { id: 'gemini-3', kind: 'effort', levels: ['low', 'high'], confidence: 'guess', effort: { low: 'low', high: 'high' } };
    }
    if (/^qwen3/.test(name) && !/(thinking|instruct|coder)/.test(name)) {
        return {
            id: 'qwen3', kind: 'budget', levels: ['off', 'low', 'normal', 'high', 'max'], confidence: 'known', defaultOn: true,
            budgets: { low: 1024, normal: 4096, high: 16384, max: 38912 },
        };
    }
    if (/^deepseek-(v3\.[1-9]|v[4-9]|chat-v3\.[1-9])/.test(name)) {
        return { id: 'deepseek-hybrid', kind: 'toggle', levels: ['off', 'normal'], confidence: 'guess' };
    }
    if (/^glm-(4\.[5-9]|[5-9])/.test(name)) {
        return { id: 'glm', kind: 'toggle', levels: ['off', 'normal'], confidence: 'guess' };
    }
    if (ALWAYS_ON.some((re) => re.test(name)))
        return 'always-on';
    return null;
}
// ───────────────────────── wiring a level into a request ─────────────────────────
const kTokens = (n) => (n >= 1000 ? `${Math.round(n / 1000)}k` : `${n}`);
const LEVEL_MEANING = {
    off: 'no reasoning',
    low: 'light reasoning',
    normal: 'balanced',
    high: 'deep reasoning',
    xhigh: 'very deep reasoning',
    max: 'maximum reasoning',
};
function adapt(family, route, source) {
    const caps = (levels, confidence, note, build, detail) => ({
        family: family.id,
        route,
        supported: levels.length > 0,
        levels,
        confidence,
        alwaysOn: !levels.includes('off'),
        source,
        note,
        build,
        describe: (l) => `${LEVEL_MEANING[l]}${detail(l) ? ` (${detail(l)})` : ''}`,
    });
    // ── effort families: a named effort per level
    if (family.kind === 'effort') {
        const effort = family.effort ?? {};
        const param = (level) => {
            const value = effort[level];
            if (value === undefined)
                return null;
            return route === 'openrouter' ? { reasoning: { effort: value } } : { reasoning_effort: value };
        };
        return caps(family.levels, family.confidence, route === 'openrouter' ? 'Sent as reasoning.effort (OpenRouter).' : 'Sent as reasoning_effort.', param, (l) => `effort: ${effort[l]}`);
    }
    // ── toggle families: thinking on or off
    if (family.kind === 'toggle') {
        const on = (level) => level !== 'off';
        if (route === 'openrouter') {
            return caps(family.levels, family.confidence, 'Sent as reasoning.enabled (OpenRouter).', (l) => ({ reasoning: { enabled: on(l) } }), () => '');
        }
        if (route === 'zai' || route === 'deepseek') {
            return caps(family.levels, 'guess', 'Sent as thinking.type.', (l) => ({ thinking: { type: on(l) ? 'enabled' : 'disabled' } }), () => '');
        }
        return caps(family.levels, 'guess', 'Sent as reasoning_effort; devx retries without it if the endpoint rejects it.', (l) => ({ reasoning_effort: on(l) ? 'medium' : 'none' }), () => '');
    }
    // ── budget families: a number of thinking tokens
    const budgets = family.budgets ?? {};
    const budgetOf = (l) => budgets[l] ?? 0;
    const detail = (l) => (l === 'off' ? '' : `~${kTokens(budgetOf(l))} thinking tokens`);
    if (route === 'anthropic') {
        return caps(family.levels, 'known', 'Sent as thinking.budget_tokens (Anthropic).', (l) => l === 'off' ? null : { thinking: { type: 'enabled', budget_tokens: budgetOf(l) }, max_tokens: budgetOf(l) + 16000 }, detail);
    }
    if (route === 'openrouter') {
        return caps(family.levels, 'known', 'Sent as reasoning.max_tokens (OpenRouter).', (l) => {
            if (l === 'off')
                return family.defaultOn ? { reasoning: { enabled: false } } : null;
            return { reasoning: { max_tokens: budgetOf(l) } };
        }, detail);
    }
    if (route === 'google') {
        return caps(family.levels, 'known', 'Sent as google.thinking_config.thinking_budget.', (l) => ({
            extra_body: { google: { thinking_config: { thinking_budget: budgetOf(l), include_thoughts: true } } },
        }), detail);
    }
    if (route === 'dashscope') {
        return caps(family.levels, 'known', 'Sent as enable_thinking / thinking_budget (DashScope).', (l) => l === 'off' ? { enable_thinking: false } : { enable_thinking: true, thinking_budget: budgetOf(l) }, detail);
    }
    // Any other endpoint: only the widely understood reasoning_effort, with three coarse steps.
    const generic = family.levels.filter((l) => l === 'low' || l === 'normal' || l === 'high' || l === 'off');
    const mapping = { off: 'none', low: 'low', normal: 'medium', high: 'high' };
    return caps(generic, 'guess', 'Sent as reasoning_effort; devx retries without it if the endpoint rejects it.', (l) => (mapping[l] ? { reasoning_effort: mapping[l] } : null), (l) => `effort: ${mapping[l]}`);
}
const NONE = (route, family, note, alwaysOn, source = 'none') => ({
    supported: false, levels: [], family, route, confidence: 'none', alwaysOn, source, note,
    build: () => null,
    describe: () => '',
});
function fromProfile(profile, route) {
    const levels = LEVELS.filter((l) => profile.levels.includes(l));
    return {
        supported: levels.length > 0,
        levels,
        family: 'custom',
        route,
        confidence: 'custom',
        alwaysOn: !levels.includes('off'),
        source: 'profile',
        note: `From your config (reasoningProfiles): ${profile.param}.`,
        build: (level) => {
            const value = profile.values[level];
            if (value === undefined)
                return null;
            const path = profile.param.split('.');
            return path.reduceRight((inner, key) => ({ [key]: inner }), value);
        },
        describe: (l) => `${LEVEL_MEANING[l]} (${profile.param}: ${String(profile.values[l])})`,
    };
}
// ───────────────────────── from source data to a family ─────────────────────────
const without = (family, level) => ({
    ...family,
    levels: family.levels.filter((l) => l !== level),
});
/** Effort values of an API -> our levels. "minimal" stands in for "off" where nothing is lower. */
function effortFamily(efforts) {
    const has = (e) => efforts.includes(e);
    const effort = {};
    if (has('none'))
        effort.off = 'none';
    else if (has('minimal'))
        effort.off = 'minimal';
    if (has('low'))
        effort.low = 'low';
    if (has('medium'))
        effort.normal = 'medium';
    if (has('high'))
        effort.high = 'high';
    if (has('xhigh'))
        effort.xhigh = 'xhigh';
    if (has('max'))
        effort.max = 'max';
    const levels = LEVELS.filter((l) => effort[l] !== undefined);
    return levels.length > 0 ? { id: 'catalog-effort', kind: 'effort', levels, confidence: 'known', effort } : null;
}
/** A token budget range -> five budgets, each clamped into the range the source allows. */
function budgetFamily(budget, toggle, mandatory) {
    const min = Math.max(0, budget.min ?? 0);
    const max = budget.max && budget.max > 0 ? budget.max : 32768;
    const clamp = (n) => Math.min(max, Math.max(min || 1, n));
    const wanted = [
        ['low', 2048], ['normal', 8192], ['high', 16384], ['xhigh', 24576], ['max', max],
    ];
    const budgets = {};
    const levels = [];
    const seen = new Set();
    for (const [level, value] of wanted) {
        const v = clamp(value);
        if (seen.has(v))
            continue; // two levels with the same budget would be one choice
        seen.add(v);
        budgets[level] = v;
        levels.push(level);
    }
    if (!mandatory && (toggle || min === 0)) {
        budgets.off = 0;
        levels.unshift('off');
    }
    return { id: 'catalog-budget', kind: 'budget', levels, confidence: 'known', budgets, defaultOn: true };
}
/**
 * Turns what a source reports into a family. The source is authoritative for effort values
 * (they change with every model generation); for budgets and toggles the hand-written rule
 * is kept when there is one, because it knows vendor limits that sources leave out.
 */
function familyFromData(data, rule) {
    const efforts = data.efforts ?? [];
    const hasEffort = efforts.length > 0;
    const hasBudget = !!data.budget;
    const mandatory = data.mandatory === true;
    const ruleKept = (f) => (mandatory ? without(f, 'off') : f);
    if (hasEffort && !hasBudget)
        return effortFamily(efforts);
    if (hasBudget)
        return rule && rule.kind === 'budget' ? ruleKept(rule) : budgetFamily(data.budget, !!data.toggle, mandatory);
    // Only "it can be switched" or "it reasons", without details.
    if (rule)
        return ruleKept(rule);
    if (data.toggle)
        return { id: 'catalog-toggle', kind: 'toggle', levels: mandatory ? ['normal'] : ['off', 'normal'], confidence: 'known' };
    const levels = mandatory ? ['low', 'normal', 'high'] : ['off', 'low', 'normal', 'high'];
    return {
        id: 'generic-effort', kind: 'effort', levels, confidence: 'guess',
        effort: { off: 'none', low: 'low', normal: 'medium', high: 'high' },
    };
}
const SOURCE_NAME = {
    endpoint: 'The endpoint',
    catalog: 'The models.dev catalogue',
};
/**
 * What a model can do, and how it is sent over this route.
 *
 * @param data what the endpoint or the catalogue reports for this model (optional)
 */
export function resolveReasoning(model, baseUrl = '', profiles, data, 
/** The wire format decides the route when it is not the one the host name suggests (Anthropic format on a gateway). */
routeOverride) {
    const route = routeOverride ?? detectRoute(baseUrl);
    const profile = profiles?.[model.toLowerCase().trim()] ?? profiles?.[model];
    if (profile && Array.isArray(profile.levels) && typeof profile.param === 'string' && profile.values) {
        return fromProfile(profile, route);
    }
    const { name } = normalizeModel(model);
    const found = familyFor(name);
    const rule = found && found !== 'always-on' ? found : null;
    if (data) {
        if (data.none) {
            return NONE(route, 'none-by-source', `${SOURCE_NAME[data.source]} reports that this model has no reasoning mode.`, false, data.source);
        }
        const family = familyFromData(data, rule);
        if (family)
            return adapt(family, route, data.source);
    }
    if (found === 'always-on') {
        return NONE(route, 'always-on', 'This model always reasons and has no setting.', true, 'rules');
    }
    if (!found) {
        return NONE(route, 'unknown', 'No reasoning setting is known for this model.', false);
    }
    return adapt(found, route, 'rules');
}
let dataLookup = null;
/** Registers where the endpoint / catalogue data comes from (see capabilities.ts). */
export function setReasoningDataLookup(lookup) {
    dataLookup = lookup;
}
let routeResolver = null;
/** Registers what decides the route besides the host name (see formats.ts). */
export function setRouteResolver(resolver) {
    routeResolver = resolver;
}
export function modelKey(model) {
    return (model || '').toLowerCase().trim();
}
/**
 * The level that applies to the current model: the saved choice, or the one-off override,
 * but only if this model still offers it (switching models never sends a stale setting).
 */
export function effectiveReasoning(config, lookup = dataLookup) {
    const data = lookup ? lookup(config) : undefined;
    const caps = resolveReasoning(config.model || '', config.baseUrl || '', config.reasoningProfiles, data, routeResolver?.(config));
    const raw = config.effortOverride ?? config.effort?.[modelKey(config.model)];
    const level = isLevel(raw) && caps.supported && caps.levels.includes(raw) ? raw : undefined;
    return { caps, level, params: level ? caps.build(level) : null };
}
/** Parses what the user typed after /effort. Accepts a few natural aliases. */
export function parseEffortArg(arg) {
    const text = arg.trim().toLowerCase();
    if (['auto', 'default', 'reset', 'clear'].includes(text))
        return { kind: 'auto' };
    const aliases = {
        off: 'off', none: 'off', no: 'off', disable: 'off', disabled: 'off', '0': 'off',
        low: 'low', min: 'low', minimal: 'low', light: 'low',
        normal: 'normal', medium: 'normal', med: 'normal', mid: 'normal',
        high: 'high', hi: 'high',
        xhigh: 'xhigh', 'x-high': 'xhigh', extra: 'xhigh', 'extra-high': 'xhigh',
        max: 'max', maximum: 'max', ultra: 'max',
    };
    return aliases[text] ? { kind: 'level', level: aliases[text] } : { kind: 'invalid', text };
}
/** Stores (or clears, with null) the choice for a model. Returns a new effort map. */
export function withEffort(effort, model, level) {
    const next = { ...(effort || {}) };
    if (level === null)
        delete next[modelKey(model)];
    else
        next[modelKey(model)] = level;
    return next;
}
/** Short label for the status line: "high", or nothing for the model default. */
export function effortLabel(config) {
    return effectiveReasoning(config).level ?? '';
}
/**
 * What the status line says about reasoning next to the model name: "think high", "think auto"
 * (the model's own default) or nothing when the model has no reasoning setting.
 */
export function thinkLabel(config) {
    const { caps, level } = effectiveReasoning(config);
    return caps.supported ? `think ${level ?? 'auto'}` : '';
}
