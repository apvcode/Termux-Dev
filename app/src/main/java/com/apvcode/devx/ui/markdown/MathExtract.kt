package com.apvcode.devx.ui.markdown

class MathSpan(val latex: String, val display: Boolean)

class Extracted(val text: String, val math: List<MathSpan>)

/**
 * Markdown would mangle formulas (`_`, `*`, `\` mean things to it), so they are cut out first and
 * replaced by tokens, then put back as pictures. Code is left alone: a `$` in code is just a `$`.
 *
 * Recognised: `$$…$$` and `\[…\]` (display), `\(…\)` and `$…$` (inline). A lone `$` followed by a
 * space or a number ("it costs $5 and $10") is money, not a formula.
 */
object MathExtract {
    const val OPEN = ''
    const val CLOSE = ''
    private const val CODE_OPEN = ''
    private const val CODE_CLOSE = ''

    val token = Regex("$OPEN(\\d+)$CLOSE")

    private val fenced = Regex("(?s)(```|~~~).*?(\\1|\\z)")
    private val inlineCode = Regex("`+[^`\\n]+?`+")
    private val displayDollar = Regex("(?s)\\$\\$(.+?)\\$\\$")
    private val displayBracket = Regex("(?s)\\\\\\[(.+?)\\\\\\]")
    private val inlineParen = Regex("(?s)\\\\\\((.+?)\\\\\\)")
    private val inlineDollar = Regex("(?<![\\\\$\\d])\\$(?![\\s$])([^$\\n]+?)(?<!\\s)\\$(?![\\d$])")

    fun extract(source: String): Extracted {
        if ('$' !in source && '\\' !in source) return Extracted(source, emptyList())

        val code = ArrayList<String>()
        fun protect(text: String, regex: Regex) = regex.replace(text) {
            code += it.value
            "$CODE_OPEN${code.size - 1}$CODE_CLOSE"
        }
        var text = protect(protect(source, fenced), inlineCode)

        val math = ArrayList<MathSpan>()
        fun cut(input: String, regex: Regex, display: Boolean) = regex.replace(input) {
            math += MathSpan(it.groupValues[1].trim(), display)
            "$OPEN${math.size - 1}$CLOSE"
        }
        text = cut(text, displayDollar, true)
        text = cut(text, displayBracket, true)
        text = cut(text, inlineParen, false)
        text = cut(text, inlineDollar, false)

        text = Regex("$CODE_OPEN(\\d+)$CODE_CLOSE").replace(text) { code[it.groupValues[1].toInt()] }
        return Extracted(text, math)
    }
}
