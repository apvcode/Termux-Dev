package com.apvcode.devx.data

import android.content.Context
import org.json.JSONObject
import java.io.InputStream
import java.util.Locale

/** Where a context size comes from: the models.dev catalogue, devx's own table, a rule by the model's name, or a plain guess. */
enum class LimitSource { Catalogue, Table, Rule, Default }

class ContextLimit(val tokens: Int, val source: LimitSource) {
    /** True when the number is a guess and not what the maker says. */
    val approximate: Boolean get() = source == LimitSource.Rule || source == LimitSource.Default
}

/**
 * The context window of a model, found the way devx finds it: the models.dev catalogue first (the maker's own number),
 * then devx's table, then rules by the name, and only then 128k.
 */
object ModelLimits {
    @Volatile private var catalogue: Map<String, Int> = emptyMap()
    @Volatile private var table: Map<String, Int> = emptyMap()
    @Volatile private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        attempt("modellimits") {
            load(context.assets.open("context-catalog.json"), context.assets.open("model-limits.json"))
        }
    }

    fun load(catalogueJson: InputStream, tableJson: InputStream) {
        val c = JSONObject(catalogueJson.readBytes().toString(Charsets.UTF_8)).optJSONObject("context")
        catalogue = c?.keys()?.asSequence()?.associateWith { c.getInt(it) } ?: emptyMap()
        val t = JSONObject(tableJson.readBytes().toString(Charsets.UTF_8))
        table = t.keys().asSequence().associateWith { t.optInt(it) }.filterValues { it > 0 }
        loaded = true
    }

    fun of(model: String): ContextLimit {
        catalogue[normalizeModelKey(model)]?.takeIf { it > 0 }?.let { return ContextLimit(it, LimitSource.Catalogue) }
        val clean = model.trim().lowercase()
        val short = clean.substringAfterLast('/').ifEmpty { clean }
        table[clean]?.let { return ContextLimit(it, LimitSource.Table) }
        table[short]?.let { return ContextLimit(it, LimitSource.Table) }
        val base = short.replace(Regex("-\\d{4,8}$"), "").replace(Regex(":latest$"), "")
        table[base]?.let { return ContextLimit(it, LimitSource.Table) }
        fun rule(n: Int) = ContextLimit(n, LimitSource.Rule)
        return when {
            "kimi" in clean -> rule(256_000)
            "ox-alpha" in clean || "stealth" in clean -> rule(1_048_576)
            "gemini" in clean -> rule(1_048_576)
            "claude-opus-4" in clean || "claude-4" in clean -> rule(1_000_000)
            "claude" in clean -> rule(200_000)
            "gpt-5" in clean -> rule(1_050_000)
            "gpt-4o" in clean || "gpt-4-turbo" in clean || "o1" in clean || "o3" in clean -> rule(128_000)
            "deepseek" in clean -> rule(128_000)
            "qwen3.5" in clean || "qwen-3.5" in clean -> rule(262_144)
            "qwen" in clean -> rule(131_072)
            "glm-5" in clean || "glm-4.7" in clean || "glm-4.6" in clean -> rule(200_000)
            "glm-4" in clean -> rule(128_000)
            "minimax" in clean -> rule(1_000_000)
            "doubao" in clean -> rule(256_000)
            "llama-3" in clean -> rule(128_000)
            "mistral-large" in clean || "codestral" in clean -> rule(128_000)
            else -> ContextLimit(128_000, LimitSource.Default)
        }
    }
}

/** A count of tokens in a few characters: 840, 12.4k, 393k, 1M, 1.05M (never "1050.0k"). */
fun formatTokens(n: Int): String {
    fun trim(v: Double, digits: Int) = String.format(Locale.US, "%.${digits}f", v).trimEnd('0').trimEnd('.')
    return when {
        n < 1_000 -> n.toString()
        n < 100_000 -> trim(n / 1_000.0, 1) + "k"
        n < 1_000_000 -> "${Math.round(n / 1_000.0)}k"
        else -> trim(n / 1_000_000.0, 2) + "M"
    }
}
