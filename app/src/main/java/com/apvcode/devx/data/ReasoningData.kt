package com.apvcode.devx.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

/** What a source (the endpoint or the catalogue) says about the reasoning of one model. */
data class ReasoningData(
    val source: String = "catalog",
    /** The model has no reasoning mode at all. */
    val none: Boolean = false,
    /** Effort values the API accepts: "none", "minimal", "low", "medium", "high", "xhigh"... */
    val efforts: List<String> = emptyList(),
    /** Reasoning can be switched on and off. */
    val toggle: Boolean = false,
    /** Reasoning takes a token budget (the range may be unknown). */
    val hasBudget: Boolean = false,
    val budgetMin: Int? = null,
    val budgetMax: Int? = null,
    /** Reasoning cannot be switched off. */
    val mandatory: Boolean = false,
)

fun ReasoningData.toJson(): JSONObject = JSONObject()
    .put("source", source).put("none", none).put("efforts", JSONArray(efforts)).put("toggle", toggle)
    .put("hasBudget", hasBudget).put("budgetMin", budgetMin).put("budgetMax", budgetMax).put("mandatory", mandatory)

fun reasoningDataFromJson(o: JSONObject): ReasoningData = ReasoningData(
    source = o.optString("source", "endpoint"),
    none = o.optBoolean("none"),
    efforts = o.optJSONArray("efforts")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
    toggle = o.optBoolean("toggle"),
    hasBudget = o.optBoolean("hasBudget"),
    budgetMin = if (o.isNull("budgetMin")) null else o.optInt("budgetMin"),
    budgetMax = if (o.isNull("budgetMax")) null else o.optInt("budgetMax"),
    mandatory = o.optBoolean("mandatory"),
)

/**
 * Reads reasoning information from one entry of a provider's model list. Understands the OpenRouter
 * schema (`reasoning`, `supported_parameters`), models.dev style `reasoning_options`, capability
 * lists and plain boolean flags. Null when the entry says nothing about reasoning.
 */
fun reasoningFromModelEntry(entry: JSONObject): ReasoningData? {
    entry.optJSONObject("reasoning")?.let { r ->
        val efforts = r.optJSONArray("supported_efforts")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        val mandatory = r.optBoolean("mandatory", false)
        return ReasoningData(
            efforts = efforts,
            mandatory = mandatory,
            hasBudget = r.optBoolean("supports_max_tokens", false),
            toggle = efforts.isEmpty() && r.has("mandatory") && !mandatory,
        )
    }
    entry.optJSONArray("reasoning_options")?.let { options ->
        val parsed = ReasoningCatalog.parseOptions(options)
        if (parsed.isNotEmpty()) return ReasoningCatalog.dataFrom(parsed)
    }
    entry.optJSONArray("supported_parameters")?.let { p ->
        val params = (0 until p.length()).map { p.getString(it) }
        if ("reasoning_effort" in params) return ReasoningData(efforts = listOf("low", "medium", "high"))
        if (params.any { it == "reasoning" || it == "include_reasoning" || it == "thinking" }) return ReasoningData()
        if (params.isNotEmpty()) return ReasoningData(none = true)
    }
    for (key in listOf("supports_reasoning", "reasoning_supported", "supports_thinking")) {
        if (entry.has(key) && entry.optBoolean(key)) return ReasoningData()
        if (entry.has(key) && !entry.optBoolean(key, true)) return ReasoningData(none = true)
    }
    return null
}

/** The bundled models.dev catalogue: the reasoning options of thousands of models. */
object ReasoningCatalog {
    class Option(val type: String, val values: List<String> = emptyList(), val min: Int? = null, val max: Int? = null)

    private var byProvider: Map<String, Map<String, List<Option>>> = emptyMap()
    private var normalized: Map<String, Map<String, List<Option>>> = emptyMap()
    private var byName: Map<String, List<Pair<String, List<Option>>>> = emptyMap()

    @Volatile var size = 0
        private set

    private val nativeProviders = listOf("openai", "anthropic", "google", "deepseek", "alibaba", "xai", "zai", "moonshotai", "mistral", "meta", "nvidia", "groq")

    fun load(context: Context) {
        if (size > 0) return
        context.assets.open("reasoning-catalog.json").use { load(it) }
    }

    fun load(stream: InputStream) {
        val root = JSONObject(stream.readBytes().toString(Charsets.UTF_8))
        val shapesJson = root.getJSONArray("shapes")
        val shapes = (0 until shapesJson.length()).map { parseOptions(shapesJson.getJSONArray(it)) }
        val raw = HashMap<String, Map<String, List<Option>>>()
        val norm = HashMap<String, Map<String, List<Option>>>()
        val names = HashMap<String, MutableList<Pair<String, List<Option>>>>()
        var count = 0
        val models = root.getJSONObject("models")
        for (provider in models.keys()) {
            val entries = models.getJSONObject(provider)
            val rawMap = HashMap<String, List<Option>>()
            val normMap = HashMap<String, List<Option>>()
            for (id in entries.keys()) {
                val options = shapes.getOrNull(entries.getInt(id)) ?: continue
                rawMap[id] = options
                val key = normalizeModelKey(id)
                normMap[key] = options
                names.getOrPut(key) { ArrayList() } += provider to options
                count++
            }
            raw[provider] = rawMap
            norm[provider] = normMap
        }
        byProvider = raw
        normalized = norm
        byName = names
        size = count
    }

    fun parseOptions(array: JSONArray): List<Option> {
        val out = ArrayList<Option>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            when (o.optString("type")) {
                "effort" -> out += Option("effort", o.optJSONArray("values")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList())
                "toggle" -> out += Option("toggle")
                "budget_tokens" -> out += Option("budget", min = if (o.has("min")) o.optInt("min") else null, max = if (o.has("max")) o.optInt("max") else null)
            }
        }
        return out
    }

    fun dataFrom(options: List<Option>): ReasoningData {
        var data = ReasoningData()
        for (o in options) {
            when (o.type) {
                "effort" -> data = data.copy(efforts = (data.efforts + o.values).distinct())
                "toggle" -> data = data.copy(toggle = true)
                "budget" -> if (!data.hasBudget) data = data.copy(hasBudget = true, budgetMin = o.min, budgetMax = o.max)
            }
        }
        return data
    }

    /** The entry for a model: first on the user's own provider, then on the model's maker, then anywhere. */
    fun lookup(provider: String?, model: String): ReasoningData? {
        val id = model.lowercase().trim()
        val key = normalizeModelKey(model)
        if (key.isEmpty()) return null
        if (provider != null) {
            val exact = byProvider[provider]?.get(id) ?: normalized[provider]?.get(key)
            if (exact != null) return dataFrom(exact)
        }
        val candidates = byName[key] ?: return null
        val best = candidates.minByOrNull { (p, _) -> nativeProviders.indexOf(p).let { if (it == -1) nativeProviders.size else it } } ?: return null
        return dataFrom(best.second)
    }
}

/** "claude-sonnet-4.5" and "claude-sonnet-4-5-20250929" are the same model. */
fun normalizeModelKey(model: String): String =
    model.lowercase().trim().split('/').last()
        .replace(Regex(":.*$"), "")
        .replace(Regex("-latest$"), "")
        .replace(Regex("-\\d{4}-?\\d{2}-?\\d{2}$"), "")
        .replace('.', '-')

/** The catalogue's provider id for a route, when the route names the maker. */
fun catalogProvider(route: Route): String? = when (route) {
    Route.OpenAI -> "openai"
    Route.Anthropic -> "anthropic"
    Route.Google -> "google"
    Route.DeepSeek -> "deepseek"
    Route.Zai -> "zai"
    Route.DashScope -> "alibaba"
    Route.OpenRouter -> "openrouter"
    Route.Generic -> null
}

// ---------- from source data to a family (as in devx) ----------

private fun Family.without(level: Level) = copy(levels = levels.filter { it != level })

/** Effort values of an API to our levels. "minimal" stands in for "off" where nothing is lower. */
private fun effortFamily(efforts: List<String>): Family? {
    val effort = LinkedHashMap<Level, String>()
    if ("none" in efforts) effort[Level.Off] = "none" else if ("minimal" in efforts) effort[Level.Off] = "minimal"
    if ("low" in efforts) effort[Level.Low] = "low"
    if ("medium" in efforts) effort[Level.Normal] = "medium"
    if ("high" in efforts) effort[Level.High] = "high"
    if ("xhigh" in efforts) effort[Level.XHigh] = "xhigh"
    if ("max" in efforts) effort[Level.Max] = "max"
    val levels = Level.entries.filter { it in effort }
    return if (levels.isEmpty()) null else Family(Kind.Effort, levels, Confidence.Known, effort = effort)
}

/** A token budget range to up to five budgets, each clamped into what the source allows. */
private fun budgetFamily(min: Int?, max: Int?, toggle: Boolean, mandatory: Boolean): Family {
    val low = (min ?: 0).coerceAtLeast(0)
    val high = if (max != null && max > 0) max else 32768
    fun clamp(n: Int) = n.coerceIn(if (low == 0) 1 else low, high)
    val wanted = listOf(Level.Low to 2048, Level.Normal to 8192, Level.High to 16384, Level.XHigh to 24576, Level.Max to high)
    val budgets = LinkedHashMap<Level, Int>()
    val levels = ArrayList<Level>()
    val seen = HashSet<Int>()
    for ((level, value) in wanted) {
        val v = clamp(value)
        if (!seen.add(v)) continue // two levels with the same budget would be one choice
        budgets[level] = v
        levels += level
    }
    if (!mandatory && (toggle || low == 0)) {
        budgets[Level.Off] = 0
        levels.add(0, Level.Off)
    }
    return Family(Kind.Budget, levels, Confidence.Known, budgets = budgets, defaultOn = true)
}

/**
 * Turns what a source reports into a family. The source is authoritative for effort values (they
 * change with every model generation); for budgets and toggles the hand-written rule is kept when
 * there is one, because it knows vendor limits that sources leave out.
 */
internal fun familyFromData(data: ReasoningData, rule: Family?): Family? {
    val hasEffort = data.efforts.isNotEmpty()
    fun kept(f: Family) = if (data.mandatory) f.without(Level.Off) else f

    if (hasEffort && !data.hasBudget) return effortFamily(data.efforts)
    if (data.hasBudget) return if (rule != null && rule.kind == Kind.Budget) kept(rule) else budgetFamily(data.budgetMin, data.budgetMax, data.toggle, data.mandatory)
    // Only "it can be switched" or "it reasons", without details.
    if (rule != null) return kept(rule)
    if (data.toggle) return Family(Kind.Toggle, if (data.mandatory) listOf(Level.Normal) else listOf(Level.Off, Level.Normal), Confidence.Known)
    val levels = if (data.mandatory) listOf(Level.Low, Level.Normal, Level.High) else listOf(Level.Off, Level.Low, Level.Normal, Level.High)
    return Family(
        Kind.Effort, levels, Confidence.Guess,
        effort = mapOf(Level.Off to "none", Level.Low to "low", Level.Normal to "medium", Level.High to "high"),
    )
}
