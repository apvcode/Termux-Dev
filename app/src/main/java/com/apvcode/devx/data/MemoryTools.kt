package com.apvcode.devx.data

import com.apvcode.devx.model.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One message of a chat as the search sees it: plain text, with the blank ones already left out. */
class DigestMessage(val role: Role, val text: String, val at: Long)

/** A chat as the search sees it. A snapshot, so that it can be searched away from the screen's thread. */
class ChatDigest(val id: String, val title: String, val updatedAt: Long, val messages: List<DigestMessage>, val project: String? = null) {
    /** The short id the model quotes (the full one is a long UUID). */
    val shortId: String get() = id.take(8)
}

/** Searching the person's chats by a few words: fast, in memory, and kind to word endings (Russian especially). */
object ChatSearch {
    class Hit(val chat: ChatDigest, val index: Int, val score: Double, val snippet: String)

    /** The words of a query, lower case, without the ones too short to mean anything. */
    fun tokens(query: String): List<String> =
        query.lowercase().split(Regex("[^\\p{L}\\p{N}_]+")).filter { it.length >= 2 }.distinct()

    /** The part of a word that stays the same through its endings: "документами" and "документов" meet at "докумен". */
    fun stem(word: String): String = when {
        word.length <= 4 -> word
        word.length <= 6 -> word.take(4)
        else -> word.take(maxOf(5, word.length * 2 / 3))
    }

    fun search(chats: List<ChatDigest>, query: String, limit: Int = 8, perChat: Int = 3): List<Hit> {
        val words = tokens(query)
        if (words.isEmpty()) return emptyList()
        val stems = words.map { stem(it) }
        val phrase = query.trim().lowercase()
        val need = maxOf(1, (words.size + 1) / 2)
        val hits = ArrayList<Hit>()
        for (chat in chats) {
            val title = chat.title.lowercase()
            val titleMatches = stems.count { title.contains(it) }
            val found = ArrayList<Hit>()
            chat.messages.forEachIndexed { i, m ->
                val lower = m.text.lowercase()
                val matched = stems.count { lower.contains(it) }
                if (matched < need) return@forEachIndexed
                var score = matched.toDouble() / stems.size
                if (words.size > 1 && lower.contains(phrase)) score += 1.0
                score += 0.4 * titleMatches / stems.size
                found += Hit(chat, i, score, snippet(m.text, lower, stems))
            }
            hits += found.sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.index }).take(perChat)
        }
        return hits.sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.chat.updatedAt }).take(limit)
    }

    /** About [radius] characters either side of the first word that matched, on one line. */
    fun snippet(text: String, lower: String, stems: List<String>, radius: Int = 150): String {
        val at = stems.map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
        val from = (at - radius).coerceAtLeast(0)
        val to = (at + radius).coerceAtMost(text.length)
        val body = text.substring(from, to).replace(Regex("\\s+"), " ").trim()
        return (if (from > 0) "..." else "") + body + (if (to < text.length) "..." else "")
    }
}

/**
 * The assistant's tools for memory: notes about the person (save, change, forget, list), and a search through
 * the person's other chats. Chats that are locked or temporary are never offered to it.
 */
class MemoryTools(
    private val memory: MemoryStore,
    private val projectMemory: (String) -> MemoryStore?,
    private val chats: () -> List<ChatDigest>,
) {
    val names = setOf("memory_save", "memory_update", "memory_delete", "memory_list", "search_chats", "read_chat")

    /** In a chat that belongs to a project the notes can go to the project or to the person, and a search can stay in the project. */
    fun definitions(inProject: Boolean = false): List<WireTool> = listOf(
        tool(
            "memory_save",
            "Remember one lasting fact (a short note, in the third person, in the person's language)." + (if (inProject) " By default it is saved to the knowledge base of the project; scope \"person\" saves it about the person." else "") + " Gives the note's id. Does nothing if the same note exists.",
            schema(*withScope(inProject, "text" to str("The fact: one sentence, or a few when it needs them (a rule or a preference may take a short paragraph; up to 4000 characters)."), "valid_days" to int("Only for a fact that stops being true soon (an exam on Friday, a trip next week): after how many days to forget it. Leave out for lasting facts.")), required = listOf("text")),
        ),
        tool(
            "memory_update",
            "Replace the text of a saved note (when a fact changed or was wrong), by its id.",
            schema("id" to str("The note's id."), "text" to str("The new text of the note.")),
        ),
        tool(
            "memory_delete",
            "Forget a saved note, by its id. The id \"all\" forgets everything: only when the person asks to wipe the whole memory.",
            schema(*withScope(inProject, "id" to str("The note's id, or \"all\" (with the scope of the notes to wipe).")), required = listOf("id")),
        ),
        tool(
            "memory_list",
            "List the saved notes with their ids, newest first. Optionally only those that contain the given words.",
            schema(*withScope(inProject, "query" to str("Words to look for in the notes (optional).")), required = emptyList()),
        ),
        tool(
            "search_chats",
            "Search the person's other conversations by keywords. Gives the best matches: the chat, its date, the message number and the words around the match. Use a few distinctive words; try other forms or the other language if nothing is found.",
            schema(
                "query" to str("A few keywords."),
                "limit" to int("How many matches (default 8, at most 20)."),
                *(if (inProject) arrayOf("all_chats" to JSONObject().put("type", "boolean").put("description", "Search every chat, not only this project's (default false).")) else emptyArray()),
                required = listOf("query"),
            ),
        ),
        tool(
            "read_chat",
            "Read messages of one of the person's other conversations, by the chat id that search_chats gave. Gives up to 12 messages from the given message number.",
            schema(
                "chat_id" to str("The chat's id."),
                "from" to int("The number of the first message to read (default 0)."),
                "count" to int("How many messages (default 12, at most 25)."),
                required = listOf("chat_id"),
            ),
        ),
    )

    private fun withScope(inProject: Boolean, vararg props: Pair<String, JSONObject>): Array<Pair<String, JSONObject>> =
        if (!inProject) arrayOf(*props)
        else arrayOf(*props, "scope" to str("\"project\" (default) or \"person\"."))

    /** The notes a call is about: the project's by default inside a project, the person's when asked or outside one. */
    private fun storeFor(args: JSONObject, project: String?): MemoryStore =
        if (project != null && !args.optString("scope").equals("person", true)) projectMemory(project) ?: memory else memory

    /** A note by id, wherever it is: the project's notes first, then the person's. */
    private fun holder(id: String, project: String?): MemoryStore? =
        listOfNotNull(project?.let(projectMemory), memory).firstOrNull { it.find(id) != null }

    suspend fun run(name: String, args: JSONObject, report: ToolReport, currentChat: String, project: String? = null): ToolOutcome = when (name) {
        "memory_save" -> {
            val text = args.optString("text")
            report.activity(ToolActivity(ToolKind.Memory, MemoryStore.clean(text)))
            when (val r = storeFor(args, project).add(text, validDays = args.optInt("valid_days", 0))) {
                is MemoryStore.Result.Saved -> ToolOutcome("Saved (id ${r.note.id}).")
                is MemoryStore.Result.Duplicate -> ToolOutcome("This is already remembered (id ${r.note.id}).")
                is MemoryStore.Result.Failed -> ToolOutcome(r.why)
                else -> ToolOutcome("Done.")
            }
        }
        "memory_update" -> {
            val id = args.optString("id")
            report.activity(ToolActivity(ToolKind.Memory, MemoryStore.clean(args.optString("text"))))
            when (val r = (holder(id, project) ?: storeFor(args, project)).edit(id, args.optString("text"))) {
                is MemoryStore.Result.Changed -> ToolOutcome("Changed (id ${r.note.id}); it said: ${r.before}")
                is MemoryStore.Result.Failed -> ToolOutcome(r.why)
                else -> ToolOutcome("Done.")
            }
        }
        "memory_delete" -> {
            val id = args.optString("id")
            val owner = holder(id, project)
            val before = owner?.find(id)?.text.orEmpty()
            report.activity(ToolActivity(ToolKind.Memory, if (id.trim().equals("all", true)) "all" else before))
            // "all" wipes the person's notes, or the project's when the call says so.
            when (val r = (owner ?: storeFor(args, project)).remove(id)) {
                is MemoryStore.Result.Removed -> ToolOutcome("Forgotten: " + r.notes.joinToString("; ") { it.text }.ifBlank { "(nothing was saved)" })
                is MemoryStore.Result.Failed -> ToolOutcome(r.why)
                else -> ToolOutcome("Done.")
            }
        }
        "memory_list" -> {
            val query = args.optString("query")
            report.activity(ToolActivity(ToolKind.Memory, query))
            val found = storeFor(args, project).search(query)
            ToolOutcome(
                if (found.isEmpty()) "No notes${if (query.isBlank()) " are saved." else " match \"$query\"."}"
                else "${found.size} note(s):\n" + found.joinToString("\n") { "- [${it.id}] ${it.text} (${day(it.updatedAt)})" },
            )
        }
        "search_chats" -> {
            val query = args.optString("query")
            report.activity(ToolActivity(ToolKind.History, query))
            // The snapshot is taken here (the chats live in screen state); the searching happens elsewhere.
            val onlyProject = project != null && !args.optBoolean("all_chats", false)
            val snapshot = chats().filter { it.id != currentChat && (!onlyProject || it.project == project) }
            withContext(Dispatchers.Default) { search(snapshot, query, args.optInt("limit", 8).coerceIn(1, 20)) }
        }
        "read_chat" -> {
            val snapshot = chats()
            withContext(Dispatchers.Default) { read(snapshot, args.optString("chat_id"), args.optInt("from", 0), args.optInt("count", 12).coerceIn(1, 25), report) }
        }
        else -> ToolOutcome("Unknown tool: $name")
    }

    private fun search(snapshot: List<ChatDigest>, query: String, limit: Int): ToolOutcome {
        if (ChatSearch.tokens(query).isEmpty()) return ToolOutcome("Give a few keywords to look for.")
        val hits = ChatSearch.search(snapshot, query, limit)
        if (hits.isEmpty()) return ToolOutcome("Nothing found for \"$query\" in ${snapshot.size} other chats. Try other words or other forms of them.")
        val out = StringBuilder("${hits.size} match(es) in ${snapshot.size} other chats:\n")
        hits.forEachIndexed { i, h ->
            val m = h.chat.messages[h.index]
            out.append("\n${i + 1}. \"${h.chat.title}\" (chat ${h.chat.shortId}, ${day(m.at)}, message #${h.index}, ${if (m.role == Role.User) "person" else "assistant"}): ${h.snippet}")
        }
        out.append("\n\nread_chat(chat_id, from) reads more of a chat.")
        return ToolOutcome(out.toString())
    }

    private fun read(snapshot: List<ChatDigest>, wanted: String, from: Int, count: Int, report: ToolReport): ToolOutcome {
        val key = wanted.trim().lowercase()
        val chat = snapshot.firstOrNull { it.id.lowercase() == key }
            ?: snapshot.filter { key.isNotEmpty() && it.id.lowercase().startsWith(key) }.singleOrNull()
            ?: return ToolOutcome("No chat has the id \"$wanted\". Use search_chats first.")
        report.activity(ToolActivity(ToolKind.History, chat.title))
        val start = from.coerceIn(0, chat.messages.size)
        val end = (start + count).coerceAtMost(chat.messages.size)
        val out = StringBuilder("\"${chat.title}\" (chat ${chat.shortId}), messages $start to ${(end - 1).coerceAtLeast(start)} of ${chat.messages.size}:\n")
        var i = start
        while (i < end) {
            val m = chat.messages[i]
            val text = if (m.text.length > PER_MESSAGE) m.text.take(PER_MESSAGE) + "... [cut]" else m.text
            out.append("\n#$i ${if (m.role == Role.User) "person" else "assistant"} (${day(m.at)}):\n$text\n")
            i++
            if (out.length > MAX_READ && i < end) {
                out.append("\n[cut here: read_chat from $i for the rest]")
                return ToolOutcome(out.toString())
            }
        }
        if (end < chat.messages.size) out.append("\n[${chat.messages.size - end} more messages: read_chat from $end]") else out.append("\n[end of the chat]")
        return ToolOutcome(out.toString())
    }

    private fun day(at: Long): String = if (at <= 0) "?" else SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(at))

    private companion object {
        const val PER_MESSAGE = 1_800
        const val MAX_READ = 14_000
    }
}
