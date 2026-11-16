package com.apvcode.devx.ui.markdown

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Where an answer got a fact from. The assistant writes `[[notes.pdf, p. 12]]` right after it; the answer shows a small tappable
 * "notes.pdf · 12" that opens that source (at that page when it is a PDF). The text is turned into an ordinary link with an address of
 * its own that the app catches before the browser does.
 */
object Citations {
    private const val SCHEME = "devx-src://open"
    private val mark = Regex("""\[\[([^\[\]\n]{2,160})]]""")
    private val page = Regex("""(?:,|\s)\s*(?:p\.?|pp\.?|page|стр\.?|страница|с\.|s\.)\s*(\d{1,4})\s*$""", RegexOption.IGNORE_CASE)

    /** The text with every citation written as a link; text without any is returned as it is. */
    fun rewrite(text: String): String {
        if ("[[" !in text) return text
        return mark.replace(text) { m ->
            val raw = m.groupValues[1].trim()
            val p = page.find(raw)
            val name = (if (p != null) raw.substring(0, p.range.first) else raw).trim().trimEnd(',', ';')
            if (name.length < 2) return@replace m.value
            val number = p?.groupValues?.get(1)?.toIntOrNull()
            val label = name + (if (number != null) " · $number" else "")
            "[$label]($SCHEME?n=" + URLEncoder.encode(name, "UTF-8") + (if (number != null) "&p=$number" else "") + ")"
        }
    }

    /** The source name and page (0: none) a link made by [rewrite] points at; null for any other address. */
    fun decode(uri: String): Pair<String, Int>? {
        if (!uri.startsWith(SCHEME)) return null
        val query = uri.substringAfter('?', "")
        val parts = query.split('&').mapNotNull { it.split('=', limit = 2).takeIf { kv -> kv.size == 2 } }.associate { it[0] to it[1] }
        val name = parts["n"]?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() } ?: return null
        return name to (parts["p"]?.toIntOrNull() ?: 0)
    }
}
