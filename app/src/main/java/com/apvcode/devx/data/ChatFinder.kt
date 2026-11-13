package com.apvcode.devx.data

/** One chat that matches a search: where, and a few words around the match (null when only the title matched). */
class SearchHit(
    val chatId: String,
    val titleMatch: Boolean,
    /** The newest message that has the words, and a line of text around them with the span to highlight. */
    val messageId: String?,
    val snippet: String?,
    val matchStart: Int,
    val matchEnd: Int,
    /** How many messages of the chat have the words. */
    val count: Int,
)

/** Looking for words in every chat: titles first, then what was said. The contents of a locked chat are never read. */
object ChatFinder {
    fun run(chats: List<Chat>, query: String, limit: Int = 60): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return chats.asSequence()
            .filter { !it.archived && !it.temporary }
            .mapNotNull { chat ->
                val inTitle = chat.title.contains(q, ignoreCase = true)
                if (chat.locked) return@mapNotNull if (inTitle) SearchHit(chat.id, true, null, null, 0, 0, 0) to chat.updatedAt else null
                val found = chat.messages.filter { it.text.contains(q, ignoreCase = true) }
                if (!inTitle && found.isEmpty()) return@mapNotNull null
                val latest = found.maxByOrNull { it.createdAt }
                val cut = latest?.let { snippet(it.text, q) }
                SearchHit(chat.id, inTitle, latest?.id, cut?.text, cut?.start ?: 0, cut?.end ?: 0, found.size) to chat.updatedAt
            }
            // A match in the title comes first, then the chats used most lately.
            .sortedWith(compareByDescending<Pair<SearchHit, Long>> { it.first.titleMatch }.thenByDescending { it.second })
            .map { it.first }
            .take(limit)
            .toList()
    }

    class Cut(val text: String, val start: Int, val end: Int)

    /** The words around the first match, on one line, with "…" where the text goes on. */
    fun snippet(text: String, query: String, before: Int = 40, after: Int = 90): Cut? {
        val at = text.indexOf(query.trim(), ignoreCase = true)
        if (at < 0) return null
        val len = query.trim().length
        val from = (at - before).coerceAtLeast(0)
        val to = (at + len + after).coerceAtMost(text.length)
        fun flat(s: String) = s.replace(Regex("\\s+"), " ")
        val head = (if (from > 0) "…" else "") + flat(text.substring(from, at)).trimStart()
        val mid = flat(text.substring(at, at + len))
        val tail = flat(text.substring(at + len, to)).trimEnd() + (if (to < text.length) "…" else "")
        return Cut(head + mid + tail, head.length, head.length + mid.length)
    }
}
