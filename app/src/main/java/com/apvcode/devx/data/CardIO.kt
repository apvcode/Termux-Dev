package com.apvcode.devx.data

/**
 * Cards in and out as text: tab or comma separated (what Anki's "Notes in Plain Text" gives, and a spreadsheet), and the notes of an Anki
 * package. Nothing here touches the phone, so it is tested on its own. A card is a question, an answer and, if there is one, a topic
 * (Anki's tags).
 */
object CardIO {
    /** What one card of a file is, before it is put in a notebook; [image] is the name of a picture in the package, if the card has one. */
    class Imported(val draft: CardDraft, val image: String = "")

    // ---------- text files ----------

    /**
     * Reads cards from text. The separator is taken from Anki's "#separator:" line, else guessed from the first row (tab, semicolon, comma).
     * Fields may be quoted ("a ""quoted"" word", also across lines). A first row that names the columns is skipped. Columns: question, answer,
     * topic (optional). Anki's own markup ("#html:true") is turned into plain text.
     */
    fun parseText(raw: String): List<CardDraft> {
        val text = raw.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val lines = text.split('\n')
        var separator: Char? = null
        var html = false
        var tagColumn = -1
        var start = 0
        while (start < lines.size && lines[start].startsWith("#")) {
            val l = lines[start].trim()
            when {
                l.startsWith("#separator:") -> separator = when (l.removePrefix("#separator:").trim().lowercase()) { "tab" -> '\t'; "comma" -> ','; "semicolon" -> ';'; "space" -> ' '; "pipe" -> '|'; else -> l.removePrefix("#separator:").trim().firstOrNull() }
                l.startsWith("#html:") -> html = l.removePrefix("#html:").trim().equals("true", true)
                l.startsWith("#tags column:") -> tagColumn = (l.removePrefix("#tags column:").trim().toIntOrNull() ?: 0) - 1
            }
            start++
        }
        val body = lines.drop(start).joinToString("\n")
        val sep = separator ?: guess(body)
        val rows = rows(body, sep).filter { r -> r.any { it.isNotBlank() } }
        if (rows.isEmpty()) return emptyList()
        val first = rows.first().map { it.trim().lowercase() }
        val headed = first.size >= 2 && first[0] in HEADS_FRONT && first[1] in HEADS_BACK
        val out = ArrayList<CardDraft>()
        for (r in rows.drop(if (headed) 1 else 0)) {
            if (r.size < 2) continue
            val tags = if (tagColumn in r.indices) r[tagColumn] else r.getOrNull(2).orEmpty().takeIf { tagColumn < 0 }.orEmpty()
            val front = if (html) fromHtml(r[0]) else r[0].trim()
            val back = if (html) fromHtml(r[1]) else r[1].trim()
            if (front.isEmpty() || back.isEmpty()) continue
            out += CardDraft(front, back, topicOf(tags))
        }
        return out
    }

    private val HEADS_FRONT = setOf("front", "question", "q", "term", "вопрос", "термин", "frage", "pregunta")
    private val HEADS_BACK = setOf("back", "answer", "a", "definition", "ответ", "определение", "antwort", "respuesta")

    /** The separator of the first row (quotes are respected, so a quoted line break does not end it): tab, else the more common of ; and ,. */
    private fun guess(body: String): Char {
        var tabs = 0; var semis = 0; var commas = 0
        var quoted = false
        for (c in body.trimStart('\n')) {
            if (c == '"') quoted = !quoted
            else if (!quoted) when (c) { '\n' -> break; '\t' -> tabs++; ';' -> semis++; ',' -> commas++ }
        }
        return when { tabs > 0 -> '\t'; semis > commas -> ';'; else -> ',' }
    }

    /** Rows of fields; a field can be quoted and then holds the separator and line breaks. */
    internal fun rows(body: String, sep: Char): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        var wasQuoted = false
        while (i < body.length) {
            val c = body[i]
            when {
                quoted -> if (c == '"') { if (i + 1 < body.length && body[i + 1] == '"') { cur.append('"'); i++ } else quoted = false } else cur.append(c)
                c == '"' && cur.isEmpty() && !wasQuoted -> { quoted = true; wasQuoted = true }
                c == sep -> { row.add(cur.toString()); cur.clear(); wasQuoted = false }
                c == '\n' -> { row.add(cur.toString()); cur.clear(); wasQuoted = false; rows.add(row); row = ArrayList() }
                else -> cur.append(c)
            }
            i++
        }
        if (cur.isNotEmpty() || row.isNotEmpty()) { row.add(cur.toString()); rows.add(row) }
        return rows
    }

    /** The first tag of Anki's tag column is the topic ("organic_chemistry" → "organic chemistry"). */
    fun topicOf(tags: String): String =
        tags.trim().split(Regex("\\s+")).firstOrNull { it.isNotEmpty() && it.lowercase() !in SKIP_TAGS }?.replace('_', ' ')?.substringAfterLast("::")?.take(40).orEmpty()

    private val SKIP_TAGS = setOf("leech", "marked", "suspended", "buried")

    // ---------- Anki notes ----------

    /**
     * One note of an Anki collection: its fields (separated by the character 0x1F) and its tags. The first two fields are the question and
     * the answer; a cloze ("{{c1::word}}") becomes a question with a gap and the sentence as the answer. The picture of a field, if any,
     * is named in [Imported.image]. Null for a note that has no usable text.
     */
    fun fromNote(fields: String, tags: String): Imported? {
        val parts = fields.split('\u001F')
        if (parts.isEmpty()) return null
        val rawFront = parts[0]
        val rawBack = parts.getOrElse(1) { "" }
        val image = firstImage(rawFront).ifEmpty { firstImage(rawBack) }
        var front: String
        var back: String
        if (CLOZE.containsMatchIn(rawFront)) {
            front = fromHtml(rawFront.replace(CLOZE) { "[…]" })
            back = fromHtml(rawFront.replace(CLOZE) { it.groupValues[1] })
            val extra = fromHtml(rawBack)
            if (extra.isNotEmpty()) back = back + "\n" + extra
        } else {
            front = fromHtml(rawFront)
            back = fromHtml(rawBack)
        }
        if (front.isEmpty() && image.isNotEmpty()) front = "?"
        if (front.isEmpty() || back.isEmpty()) return null
        return Imported(CardDraft(front, back, topicOf(tags)), image)
    }

    private val CLOZE = Regex("\\{\\{c\\d+::(.*?)(?:::.*?)?\\}\\}", RegexOption.DOT_MATCHES_ALL)
    private val IMG = Regex("<img[^>]*?src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

    fun firstImage(html: String): String = IMG.find(html)?.groupValues?.get(1).orEmpty()

    /** Anki's markup as plain text: line breaks kept, tags, sounds and pictures dropped, the common entities decoded. */
    fun fromHtml(html: String): String {
        var t = html
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</(div|p|li|tr)>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("\\[sound:[^\\]]*\\]"), "")
            .replace(Regex("<[^>]+>"), "")
        t = t.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
        return t.lines().joinToString("\n") { it.trim() }.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    // ---------- out ----------

    /**
     * The cards as Anki's "Notes in Plain Text" reads them (and a spreadsheet opens them): tab separated, question, answer, topic; a field
     * with a tab, a quote or a line break is quoted. The header lines tell Anki the separator and the tag column.
     */
    fun toTsv(cards: List<Flashcard>): String = buildString {
        append("#separator:tab\n#html:false\n#tags column:3\n")
        for (c in cards) append(field(c.front, '\t')).append('\t').append(field(c.back, '\t')).append('\t').append(c.topic.trim().replace(' ', '_')).append('\n')
    }

    /** The same as comma separated values, with a header row. */
    fun toCsv(cards: List<Flashcard>): String = buildString {
        append("front,back,topic\n")
        for (c in cards) append(field(c.front, ',')).append(',').append(field(c.back, ',')).append(',').append(field(c.topic, ',')).append('\n')
    }

    private fun field(v: String, sep: Char): String =
        if (v.any { it == sep || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
}
