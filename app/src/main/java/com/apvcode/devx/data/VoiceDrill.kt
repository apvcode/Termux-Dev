package com.apvcode.devx.data

/**
 * The words of a spoken round of cards: how well what was said fits the answer on the card, and which of the four answers
 * ("again", "hard", "good", "easy") a spoken reply means. Nothing here needs the model or the network.
 */
object VoiceDrill {
    /** How much of the answer's content words were said, 0..1 (a word counts when it, or its beginning, was said). */
    fun score(heard: String, expected: String): Double {
        val said = words(heard)
        val want = words(expected).distinct()
        if (want.isEmpty()) return 0.0
        if (said.isEmpty()) return 0.0
        val hit = want.count { w -> said.any { s -> same(s, w) } }
        return hit.toDouble() / want.size
    }

    /** The answer to suggest for a score; null when nothing was said at all. */
    fun suggest(heard: String, expected: String): String? {
        if (words(heard).isEmpty()) return null
        val s = score(heard, expected)
        return when {
            s >= 0.6 -> "good"
            s >= 0.35 -> "hard"
            else -> "again"
        }
    }

    private val again = listOf("again", "wrong", "forgot", "don't know", "dont know", "no idea", "не знал", "не помню", "не знаю", "забыл", "неверно", "неправильно", "ошибся", "снова", "nicht gewusst", "falsch", "no lo sabía", "mal", "faux", "oublié")
    private val easy = listOf("easy", "легко", "просто", "очень легко", "leicht", "fácil", "facile")
    private val hard = listOf("hard", "difficult", "трудно", "сложно", "с трудом", "schwer", "difícil", "difficile")
    private val good = listOf("good", "right", "correct", "yes", "knew", "ok", "okay", "верно", "знал", "правильно", "да", "помню", "хорошо", "richtig", "gut", "ja", "bien", "sí", "correcto", "oui", "juste")

    /** The answer a spoken reply means, or null when it is none of them. A "no" word is looked for first, so "didn't know" is not "know". */
    fun command(text: String): String? {
        val t = text.lowercase().trim()
        if (t.isEmpty()) return null
        fun has(list: List<String>) = list.any { k -> Regex("(^|[^\\p{L}])" + Regex.escape(k) + "($|[^\\p{L}])").containsMatchIn(t) }
        return when {
            has(again) -> "again"
            has(easy) -> "easy"
            has(hard) -> "hard"
            has(good) -> "good"
            else -> null
        }
    }

    private fun words(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in STOP }

    private fun same(a: String, b: String): Boolean {
        if (a == b) return true
        val n = minOf(a.length, b.length)
        // Endings differ ("learn", "learning"; "функция", "функции"): the first letters decide.
        return n >= 5 && a.take(n - 2) == b.take(n - 2)
    }

    private val STOP = setOf(
        "the", "and", "for", "that", "with", "are", "was", "this", "from", "have", "has", "not", "but", "его", "это", "что", "как", "для", "при", "или", "the", "und", "der", "die", "das", "los", "las", "les", "des",
    )
}
