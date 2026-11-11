package com.apvcode.devx.data

import org.json.JSONObject
import java.net.URI

/**
 * Reasoning ("thinking") effort, ported from devx so both behave the same.
 *
 * Models differ a lot: o3 has low / medium / high and cannot switch reasoning off, Claude takes a
 * thinking budget in tokens, Gemini 2.5 Pro cannot be switched off, DeepSeek-R1 always reasons and
 * has no setting. And every API wants the value in another parameter. [resolveReasoning] answers:
 *   1. What can THIS model do?   -> [ReasoningCaps.levels] (only these are offered)
 *   2. How is a level sent?      -> [ReasoningCaps.build] (the request parameters for this API)
 * A model that nothing describes gets no levels instead of a guess.
 */
enum class Level(val key: String) {
    Off("off"), Low("low"), Normal("normal"), High("high"), XHigh("xhigh"), Max("max");

    companion object {
        fun fromKey(key: String?): Level? = entries.firstOrNull { it.key == key }
    }
}

enum class Route { OpenRouter, OpenAI, Anthropic, Google, DashScope, Zai, DeepSeek, Generic }

enum class Confidence { Known, Guess, None }

class ReasoningCaps(
    /** The levels this model offers, in ascending order. Empty when nothing can be set. */
    val levels: List<Level>,
    /** The model reasons whatever you do (it cannot be switched off). */
    val alwaysOn: Boolean,
    val confidence: Confidence,
    private val builder: (Level) -> JSONObject?,
    private val detailOf: (Level) -> String,
) {
    val supported: Boolean get() = levels.isNotEmpty()

    /**
     * A strong setting for work where quality matters more than speed (writing the memory summary): "high" if the
     * model has it, else the highest below it, else the lowest above it. Null when nothing can be set.
     */
    fun strongLevel(): Level? {
        val real = levels.filter { it != Level.Off }
        return real.firstOrNull { it == Level.High }
            ?: real.lastOrNull { it.ordinal < Level.High.ordinal }
            ?: real.firstOrNull()
    }

    /** Request parameters for a level, or null when nothing has to be sent. */
    fun build(level: Level): JSONObject? = builder(level)

    /** A short phrase such as "~16k thinking tokens" or "effort: high"; empty when there is nothing to say. */
    fun detail(level: Level): String = detailOf(level)

    companion object {
        val NONE = ReasoningCaps(emptyList(), false, Confidence.None, { null }, { "" })
        val ALWAYS_ON = ReasoningCaps(emptyList(), true, Confidence.None, { null }, { "" })
    }
}

internal enum class Kind { Effort, Budget, Toggle }

internal data class Family(
    val kind: Kind,
    val levels: List<Level>,
    val confidence: Confidence,
    val effort: Map<Level, String> = emptyMap(),
    val budgets: Map<Level, Int> = emptyMap(),
    /** The model thinks by default, so "off" has to be sent explicitly. */
    val defaultOn: Boolean = false,
)

fun detectRoute(baseUrl: String): Route {
    val host = runCatching { URI(baseUrl.trim()).host }.getOrNull()?.lowercase() ?: baseUrl.lowercase()
    return when {
        host.endsWith("openrouter.ai") -> Route.OpenRouter
        host == "api.openai.com" -> Route.OpenAI
        host == "api.anthropic.com" -> Route.Anthropic
        host.endsWith("generativelanguage.googleapis.com") -> Route.Google
        host.endsWith("aliyuncs.com") -> Route.DashScope
        host == "api.z.ai" || host.endsWith("bigmodel.cn") -> Route.Zai
        host.endsWith("deepseek.com") -> Route.DeepSeek
        else -> Route.Generic
    }
}

private val ALWAYS_ON = listOf(
    "^deepseek-reasoner", "^deepseek-r1", "^r1-", "-r1(-|$)", "^qwq", "^magistral", "thinking(-\\d+)?$",
    "^kimi-k2-thinking", "^minimax-m2", "^glm-z1", "^sonar-reasoning", "^grok-4",
).map { Regex(it) }

private val OPENAI_EFFORT = mapOf(Level.Low to "low", Level.Normal to "medium", Level.High to "high")

/** "vendor/Model:tag" -> "model" */
internal fun normalizeModel(model: String): String {
    val last = model.lowercase().trim().split('/').last()
    return last.replace(Regex(":.*$"), "")
}

internal sealed interface Found {
    data class Rule(val family: Family) : Found
    data object AlwaysOn : Found
}

internal fun familyFor(name: String): Found? {
    fun rule(f: Family) = Found.Rule(f)

    if (Regex("^gpt-oss").containsMatchIn(name)) {
        return rule(Family(Kind.Effort, listOf(Level.Low, Level.Normal, Level.High), Confidence.Known, effort = OPENAI_EFFORT))
    }

    if (Regex("^o[1345](-mini|-pro|-preview)?(-\\d{4}-\\d{2}-\\d{2})?$").containsMatchIn(name) && !Regex("^o1-(mini|preview)").containsMatchIn(name)) {
        if (Regex("-pro").containsMatchIn(name)) return rule(Family(Kind.Effort, listOf(Level.High), Confidence.Known, effort = mapOf(Level.High to "high")))
        return rule(Family(Kind.Effort, listOf(Level.Low, Level.Normal, Level.High), Confidence.Known, effort = OPENAI_EFFORT))
    }

    Regex("^gpt-5(?:\\.(\\d+))?").find(name)?.let { match ->
        if (name.contains("chat")) return null // gpt-5-chat does not reason
        val minor = match.groupValues[1].toIntOrNull() ?: 0
        if (name.contains("-pro")) return rule(Family(Kind.Effort, listOf(Level.High), Confidence.Known, effort = mapOf(Level.High to "high")))
        val levels = mutableListOf(Level.Off, Level.Low, Level.Normal, Level.High)
        // gpt-5 itself cannot be switched off, "minimal" is the closest; 5.1 and later accept "none"
        val effort = mutableMapOf(Level.Off to if (minor == 0) "minimal" else "none", Level.Low to "low", Level.Normal to "medium", Level.High to "high")
        if (minor >= 2 || name.contains("codex-max")) {
            levels += Level.XHigh
            effort[Level.XHigh] = "xhigh"
        }
        return rule(Family(Kind.Effort, levels, if (minor <= 2) Confidence.Known else Confidence.Guess, effort = effort))
    }

    if (Regex("^grok-3-mini").containsMatchIn(name)) {
        return rule(Family(Kind.Effort, listOf(Level.Low, Level.High), Confidence.Known, effort = mapOf(Level.Low to "low", Level.High to "high")))
    }

    if (name.startsWith("claude-") && Regex("claude-(3[-.]7|(opus|sonnet|haiku)-[4-9]|[4-9])").containsMatchIn(name)) {
        // Opus 4 / 4.1 can write at most 32k tokens, newer models 64k: that bounds the thinking budget.
        val smallOutput = Regex("opus-4(?![.-]?[5-9])").containsMatchIn(name)
        val levels = if (smallOutput) listOf(Level.Off, Level.Low, Level.Normal, Level.High, Level.XHigh)
        else listOf(Level.Off, Level.Low, Level.Normal, Level.High, Level.XHigh, Level.Max)
        return rule(
            Family(
                Kind.Budget, levels, Confidence.Known,
                budgets = mapOf(Level.Low to 2048, Level.Normal to 6144, Level.High to 16000, Level.XHigh to if (smallOutput) 24000 else 32000, Level.Max to 63000),
            ),
        )
    }

    if (Regex("^gemini-2\\.5-pro").containsMatchIn(name)) {
        return rule(
            Family(
                Kind.Budget, listOf(Level.Low, Level.Normal, Level.High, Level.Max), Confidence.Known, defaultOn = true,
                budgets = mapOf(Level.Low to 1024, Level.Normal to 8192, Level.High to 24576, Level.Max to 32768),
            ),
        )
    }
    if (Regex("^gemini-2\\.5-flash").containsMatchIn(name)) {
        return rule(
            Family(
                Kind.Budget, listOf(Level.Off, Level.Low, Level.Normal, Level.High, Level.Max), Confidence.Known, defaultOn = true,
                budgets = mapOf(Level.Off to 0, Level.Low to 1024, Level.Normal to 8192, Level.High to 16384, Level.Max to 24576),
            ),
        )
    }
    if (Regex("^gemini-3").containsMatchIn(name)) {
        return rule(Family(Kind.Effort, listOf(Level.Low, Level.High), Confidence.Guess, effort = mapOf(Level.Low to "low", Level.High to "high")))
    }

    if (Regex("^qwen3").containsMatchIn(name) && !Regex("(thinking|instruct|coder)").containsMatchIn(name)) {
        return rule(
            Family(
                Kind.Budget, listOf(Level.Off, Level.Low, Level.Normal, Level.High, Level.Max), Confidence.Known, defaultOn = true,
                budgets = mapOf(Level.Low to 1024, Level.Normal to 4096, Level.High to 16384, Level.Max to 38912),
            ),
        )
    }

    if (Regex("^deepseek-(v3\\.[1-9]|v[4-9]|chat-v3\\.[1-9])").containsMatchIn(name)) {
        return rule(Family(Kind.Toggle, listOf(Level.Off, Level.Normal), Confidence.Guess))
    }
    if (Regex("^glm-(4\\.[5-9]|[5-9])").containsMatchIn(name)) {
        return rule(Family(Kind.Toggle, listOf(Level.Off, Level.Normal), Confidence.Guess))
    }

    if (ALWAYS_ON.any { it.containsMatchIn(name) }) return Found.AlwaysOn
    return null
}

private fun kTokens(n: Int) = if (n >= 1000) "${Math.round(n / 1000f)}k" else "$n"

private fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().also { o -> pairs.forEach { (k, v) -> o.put(k, v) } }

internal fun adapt(family: Family, route: Route): ReasoningCaps {
    fun caps(levels: List<Level>, confidence: Confidence, build: (Level) -> JSONObject?, detail: (Level) -> String) =
        ReasoningCaps(levels, !levels.contains(Level.Off), confidence, build, detail)

    // Effort families: a named effort per level.
    if (family.kind == Kind.Effort) {
        return caps(
            family.levels, family.confidence,
            build = { level ->
                val value = family.effort[level]
                when {
                    value == null -> null
                    route == Route.OpenRouter -> obj("reasoning" to obj("effort" to value))
                    else -> obj("reasoning_effort" to value)
                }
            },
            detail = { "effort: ${family.effort[it]}" },
        )
    }

    // Toggle families: thinking on or off.
    if (family.kind == Kind.Toggle) {
        fun on(level: Level) = level != Level.Off
        return when (route) {
            Route.OpenRouter -> caps(family.levels, family.confidence, { obj("reasoning" to obj("enabled" to on(it))) }, { "" })
            Route.Zai, Route.DeepSeek -> caps(family.levels, Confidence.Guess, { obj("thinking" to obj("type" to if (on(it)) "enabled" else "disabled")) }, { "" })
            else -> caps(family.levels, Confidence.Guess, { obj("reasoning_effort" to if (on(it)) "medium" else "none") }, { "" })
        }
    }

    // Budget families: a number of thinking tokens.
    fun budgetOf(level: Level) = family.budgets[level] ?: 0
    val detail = { level: Level -> if (level == Level.Off) "" else "~${kTokens(budgetOf(level))} thinking tokens" }
    return when (route) {
        Route.Anthropic -> caps(family.levels, Confidence.Known, { level ->
            if (level == Level.Off) null
            else obj(
                "thinking" to obj("type" to "enabled", "budget_tokens" to budgetOf(level)),
                "max_tokens" to budgetOf(level) + 16000,
            )
        }, detail)
        Route.OpenRouter -> caps(family.levels, Confidence.Known, { level ->
            if (level == Level.Off) (if (family.defaultOn) obj("reasoning" to obj("enabled" to false)) else null)
            else obj("reasoning" to obj("max_tokens" to budgetOf(level)))
        }, detail)
        Route.Google -> caps(family.levels, Confidence.Known, { level ->
            obj(
                "extra_body" to obj(
                    "google" to obj("thinking_config" to obj("thinking_budget" to budgetOf(level), "include_thoughts" to true)),
                ),
            )
        }, detail)
        Route.DashScope -> caps(family.levels, Confidence.Known, { level ->
            if (level == Level.Off) obj("enable_thinking" to false) else obj("enable_thinking" to true, "thinking_budget" to budgetOf(level))
        }, detail)
        else -> {
            // Any other endpoint: only the widely understood reasoning_effort, with three coarse steps.
            val mapping = mapOf(Level.Off to "none", Level.Low to "low", Level.Normal to "medium", Level.High to "high")
            val generic = family.levels.filter { it in mapping }
            caps(generic, Confidence.Guess, { level -> mapping[level]?.let { obj("reasoning_effort" to it) } }, { "effort: ${mapping[it]}" })
        }
    }
}

/**
 * What a model can do, and how it is sent over this provider. The sources, in order: what the
 * endpoint itself reports for the model, the bundled models.dev catalogue, and rules by name.
 */
fun resolveReasoning(model: String, baseUrl: String, kind: ProviderKind, endpoint: ReasoningData? = null): ReasoningCaps {
    // The Anthropic wire format decides the route even on a gateway.
    val route = if (kind == ProviderKind.Anthropic) Route.Anthropic else detectRoute(baseUrl)
    val data = endpoint ?: ReasoningCatalog.lookup(catalogProvider(route), model)
    val found = familyFor(normalizeModel(model))
    val rule = (found as? Found.Rule)?.family

    if (data != null) {
        if (data.none) return ReasoningCaps.NONE
        familyFromData(data, rule)?.let { return adapt(it, route) }
    }
    return when (found) {
        null -> ReasoningCaps.NONE
        Found.AlwaysOn -> ReasoningCaps.ALWAYS_ON
        is Found.Rule -> adapt(found.family, route)
    }
}
