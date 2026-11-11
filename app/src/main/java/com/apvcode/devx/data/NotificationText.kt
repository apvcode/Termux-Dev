package com.apvcode.devx.data

/** Turns an answer written in Markdown into the plain words a notification can show. */
object NotificationText {
    private val fenced = Regex("(?s)```.*?```")
    private val openFence = Regex("(?s)```.*$")
    private val displayMath = Regex("(?s)\\$\\$.*?\\$\\$")
    private val tableRule = Regex("^\\|?[\\s:|-]+\\|?$")
    private val rule = Regex("^([-*_])(\\s*\\1){2,}$")
    private val prefix = Regex("^(#{1,6}\\s+|>+\\s*|[-*+]\\s+|\\d+[.)]\\s+)+")
    private val image = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
    private val link = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val url = Regex("https?://([^/\\s)]+)\\S*")
    private val bold = Regex("(\\*\\*|__)(.+?)\\1")
    private val italicStar = Regex("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])")
    private val italicUnder = Regex("(?<!\\w)_(?!\\s)(.+?)(?<!\\s)_(?!\\w)")
    private val strike = Regex("~~(.+?)~~")
    private val code = Regex("`([^`]+)`")
    private val html = Regex("<[^>]+>")
    private val escaped = Regex("\\\\([\\\\`*_{}\\[\\]()#+\\-.!|$])")

    /** The answer as one line of plain words: no Markdown marks, no code, cards or pictures. Empty if nothing is left. */
    fun plain(markdown: String): String {
        var text = markdown.replace(fenced, " ").replace(openFence, " ").replace(displayMath, " ")
        val lines = ArrayList<String>()
        for (raw in text.lines()) {
            var line = raw.trim()
            if (line.isEmpty() || rule.matches(line) || (line.contains('|') && tableRule.matches(line))) continue
            line = line.replace(prefix, "")
            line = line.replace(image, "$1").replace(link, "$1").replace(url, "$1")
            line = line.replace(bold, "$2").replace(italicStar, "$1").replace(italicUnder, "$1").replace(strike, "$1").replace(code, "$1")
            line = line.replace(html, "").replace('|', ' ').replace("$", "")
            line = line.replace(escaped, "$1").replace("**", "").replace("`", "")
            line = line.replace(Regex("\\s+"), " ").trim()
            if (line.isNotEmpty()) lines += line
        }
        // Lines are joined as sentences: a heading or a list item without a full stop gets one, so they do not run together.
        val joined = StringBuilder()
        for (line in lines) {
            if (joined.isNotEmpty()) joined.append(if (joined.last() in ".!?:;…,)") " " else ". ")
            joined.append(line)
        }
        return joined.toString().replace(Regex("\\s+"), " ").trim()
    }

    /** At most [max] characters, cut between words and ended with an ellipsis. */
    fun short(text: String, max: Int): String {
        if (text.length <= max) return text
        val cut = text.take(max)
        val space = cut.lastIndexOf(' ')
        return (if (space > max * 0.6) cut.take(space) else cut).trimEnd(' ', ',', ';', ':', '-', '.') + "…"
    }
}
