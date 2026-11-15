package com.apvcode.devx.data

import org.json.JSONObject

/** The text side of searching the web: what is asked, and which stretch of a page answers it. No phone needed, so it can be tested. */
object SearchText {
    private val STOP = setOf(
        "the", "and", "for", "with", "why", "how", "what", "that", "this", "from", "are", "was", "does", "not",
        "что", "как", "для", "это", "почему", "или", "при", "над", "под", "про", "его", "ее", "её",
    )

    /** What a search box likes: no quotes, no OR/AND/parentheses, no more than a dozen words. */
    fun tidy(raw: String): String {
        val words = raw.replace(Regex("[\"“”«»()]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it != "OR" && it != "AND" && it != "|" && it != "||" }
        return words.take(12).joinToString(" ").ifBlank { raw.trim() }
    }

    /** The words of a query that carry meaning (short filler words are left out; numbers like "4.7" stay). */
    fun terms(query: String): List<String> =
        Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}.]*").findAll(query.lowercase()).map { it.value.trimEnd('.') }
            .filter { (it.length >= 3 || it.any(Char::isDigit)) && it !in STOP }.distinct().toList()

    /** The stretch of a page (about 650 letters) that talks most about the [terms]; null when nothing there talks about them. */
    fun bestPassage(text: String, terms: List<String>): String? {
        val lines = text.lines().map { it.trim() }.filter { it.length > 20 }
        if (lines.isEmpty() || terms.isEmpty()) return null
        // Prefix match, so that other forms of a word count too ("windows" for "window").
        val stems = terms.map { if (it.length > 5 && !it.any(Char::isDigit)) it.take(it.length - 1) else it }
        var best: String? = null
        var bestScore = 0
        for (i in lines.indices) {
            val window = StringBuilder()
            var j = i
            while (j < lines.size && window.length < 650) { window.append(lines[j]).append(' '); j++ }
            val low = window.toString().lowercase()
            val distinct = stems.count { it in low }
            val score = distinct * 100 + stems.sumOf { t -> low.windowed(t.length, 1).count { it == t }.coerceAtMost(5) }
            if (distinct >= minOf(2, stems.size) && score > bestScore) { bestScore = score; best = window.toString().trim() }
        }
        return best?.replace(Regex("\\s+"), " ")?.take(700)
    }
}

/**
 * Search through a service with a key of the person's own (Tavily or Brave), an optional way: much better results than reading a search
 * page, and for Tavily the matching passage of each page comes with it. The kind of key is told by how it begins.
 */
object SearchApi {
    const val KEY_ID = "search_api_key"

    class Row(val title: String, val url: String, val snippet: String)

    enum class Kind { Tavily, Brave }

    fun kindOf(key: String): Kind? = when {
        key.startsWith("tvly-") -> Kind.Tavily
        key.startsWith("BSA") -> Kind.Brave
        else -> null
    }

    fun parseTavily(json: String): List<Row> = runCatching {
        val arr = JSONObject(json).optJSONArray("results") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url")
            if (url.isBlank()) null else Row(o.optString("title").ifBlank { url }, url, o.optString("content").replace(Regex("\\s+"), " ").trim().take(600))
        }
    }.getOrDefault(emptyList())

    fun parseBrave(json: String): List<Row> = runCatching {
        val arr = JSONObject(json).optJSONObject("web")?.optJSONArray("results") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url")
            // Brave marks the words that matched with <strong> tags.
            if (url.isBlank()) null else Row(o.optString("title").ifBlank { url }, url, o.optString("description").replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), " ").trim().take(400))
        }
    }.getOrDefault(emptyList())
}
