package com.apvcode.devx.ui.markdown

/**
 * Cuts Markdown into pieces that mean the same when read on their own as they do inside the whole. An answer that is being
 * written changes only at its end, so the pieces before the last one come out the same every time and can be kept, and only
 * the last one has to be read again. (Reading the whole answer again for every few words made long answers stutter.)
 *
 * A cut is made only at a blank line followed by a line that starts a new top-level block and cannot belong to what came
 * before: not indented, not an item of a list, not a quote or a table, and never inside a fenced block or a formula.
 */
object MarkdownSegments {
    private val listMarker = Regex("^(?:[-*+]|\\d{1,9}[.)])(?:\\s|$)")

    fun split(text: String): List<String> {
        if (text.length < MIN_TO_SPLIT) return listOf(text)
        val pieces = ArrayList<String>()
        var start = 0
        var offset = 0
        var inFence = false
        var inMath = false
        var previousBlank = false
        var seenText = false
        for (line in text.lineSequence()) {
            val trimmed = line.trimStart()
            val blank = trimmed.isEmpty()
            // A cut falls before this line: the one before it was blank, and this one starts something new and plain.
            if (!blank && previousBlank && seenText && !inFence && !inMath && line.length == trimmed.length &&
                !listMarker.containsMatchIn(trimmed) && trimmed[0] != '>' && trimmed[0] != '|' && !trimmed.startsWith("```") && !trimmed.startsWith("~~~") && offset > start
            ) {
                pieces += text.substring(start, offset)
                start = offset
            }
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) inFence = !inFence
            // "$$" opens and closes a displayed formula; the ones on a line cancel out in pairs.
            if (!inFence && Regex("\\$\\$").findAll(line).count() % 2 == 1) inMath = !inMath
            if (!blank) seenText = true
            previousBlank = blank
            offset += line.length + 1
        }
        if (start < text.length) pieces += text.substring(start)
        return pieces.ifEmpty { listOf(text) }
    }

    private const val MIN_TO_SPLIT = 400
}
