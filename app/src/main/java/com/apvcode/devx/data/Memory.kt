package com.apvcode.devx.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** One thing the assistant remembers about the person. [id] is short, so that the model can quote it. */
data class MemoryNote(val id: String, val text: String, val createdAt: Long, val updatedAt: Long, val expiresAt: Long = 0L) {
    /** A temporary fact ("the exam is tomorrow") stops being true: after [expiresAt] it is no longer used. 0: it does not expire. */
    fun expired(now: Long = System.currentTimeMillis()) = expiresAt in 1..now
}

/**
 * What the assistant remembers about the person, across all conversations: short notes the assistant (or
 * the person) writes, and an overview ("summary") that the model writes from the notes and the chats.
 * Kept in two small files in the app's folder; never sent anywhere except inside the requests to the
 * provider the person chose, and only while the memory is switched on.
 */
class MemoryStore(dir: File, notesName: String = "memory.json", summaryName: String = "memory_summary.json") {
    constructor(context: Context, notesName: String = "memory.json", summaryName: String = "memory_summary.json") : this(context.filesDir, notesName, summaryName)

    private val notesFile = File(dir, notesName)
    private val summaryFile = File(dir, summaryName)
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "devx-memory").apply { isDaemon = true } }

    val notes = mutableStateListOf<MemoryNote>()

    /** The overview as Markdown; empty until it has been written. */
    var summary by mutableStateOf("")
        private set
    var summaryAt by mutableLongStateOf(0L)
        private set

    /** The notes have changed since the overview was written: it may say something that is no longer true. */
    var summaryStale by mutableStateOf(false)
        private set
    /** The person wrote or changed the overview by hand: it is not renewed by itself, only when they ask for "Update". */
    var summaryManual by mutableStateOf(false)
        private set
    /** The overview as it was before the last rewrite, to show what is new in it. */
    var summaryPrev by mutableStateOf("")
        private set
    /** The person has seen (or closed) the list of what changed. */
    var changesSeen by mutableStateOf(true)
        private set
    var summarizing by mutableStateOf(false)
    var summaryError by mutableStateOf<ErrorKind?>(null)

    suspend fun load() {
        val (loaded, text, at, stale, manual, prev, seen) = withContext(Dispatchers.IO) {
            val list = runCatching {
                JSONArray(notesFile.takeIf { it.exists() }?.readText() ?: "[]").let { a ->
                    (0 until a.length()).map { i ->
                        val o = a.getJSONObject(i)
                        MemoryNote(o.getString("id"), o.getString("text"), o.optLong("createdAt"), o.optLong("updatedAt"), o.optLong("expiresAt"))
                    }
                }
            }.logged("memory").getOrDefault(emptyList()).filterNot { it.expired() }
            val s = runCatching { JSONObject(summaryFile.takeIf { it.exists() }?.readText() ?: "{}") }.logged("memory").getOrDefault(JSONObject())
            Loaded(list, s.optString("text"), s.optLong("at"), s.optBoolean("stale"), s.optBoolean("manual"), s.optString("prev"), s.optBoolean("seen", true))
        }
        notes.clear()
        notes.addAll(loaded)
        summary = text
        summaryAt = at
        summaryStale = stale
        summaryManual = manual
        summaryPrev = prev
        changesSeen = seen
    }

    private data class Loaded(val notes: List<MemoryNote>, val text: String, val at: Long, val stale: Boolean, val manual: Boolean, val prev: String, val seen: Boolean)

    // ---------- the notes ----------

    /** Result of a change: what was done, and the note it concerned. */
    sealed interface Result {
        data class Saved(val note: MemoryNote) : Result
        data class Duplicate(val note: MemoryNote) : Result
        data class Changed(val note: MemoryNote, val before: String) : Result
        data class Removed(val notes: List<MemoryNote>) : Result
        data class Failed(val why: String) : Result
    }

    @Synchronized
    fun add(raw: String, now: Long = System.currentTimeMillis(), validDays: Int = 0): Result {
        val text = clean(raw)
        if (text.isEmpty()) return Result.Failed("The note is empty.")
        notes.firstOrNull { same(it.text, text) }?.let { return Result.Duplicate(it) }
        if (notes.size >= MAX_NOTES) return Result.Failed("The memory is full ($MAX_NOTES notes). Remove or merge old notes first.")
        val note = MemoryNote(freshId(), text, now, now, if (validDays > 0) now + validDays.coerceAtMost(365) * DAY else 0L)
        notes += note
        changed()
        return Result.Saved(note)
    }

    @Synchronized
    fun edit(idOrPrefix: String, raw: String, now: Long = System.currentTimeMillis()): Result {
        val text = clean(raw)
        if (text.isEmpty()) return Result.Failed("The note is empty.")
        val old = find(idOrPrefix) ?: return Result.Failed("No note has the id \"$idOrPrefix\". Use memory_list to see the notes.")
        val note = old.copy(text = text, updatedAt = now)
        notes[notes.indexOf(old)] = note
        changed()
        return Result.Changed(note, old.text)
    }

    @Synchronized
    fun remove(idOrPrefix: String): Result {
        if (idOrPrefix.trim().equals("all", ignoreCase = true)) {
            val gone = notes.toList()
            notes.clear()
            changed()
            return Result.Removed(gone)
        }
        val old = find(idOrPrefix) ?: return Result.Failed("No note has the id \"$idOrPrefix\". Use memory_list to see the notes.")
        notes.remove(old)
        changed()
        return Result.Removed(listOf(old))
    }

    fun find(idOrPrefix: String): MemoryNote? {
        val key = idOrPrefix.trim().trim('[', ']').lowercase()
        if (key.isEmpty()) return null
        notes.firstOrNull { it.id == key }?.let { return it }
        return notes.filter { it.id.startsWith(key) }.singleOrNull()
    }

    /** The notes that match every word of [query] (all of them when it is blank), newest first. */
    fun search(query: String): List<MemoryNote> {
        val words = ChatSearch.tokens(query)
        val all = notes.filterNot { it.expired() }.sortedByDescending { it.updatedAt }
        if (words.isEmpty()) return all
        return all.filter { n -> val t = n.text.lowercase(); words.all { t.contains(ChatSearch.stem(it)) } }
    }

    // ---------- the overview ----------

    fun saveSummary(text: String, now: Long = System.currentTimeMillis()) {
        val fresh = text.trim()
        if (summary.isNotEmpty() && fresh != summary) {
            summaryPrev = summary
            changesSeen = false
        }
        summary = fresh
        summaryAt = now
        summaryStale = false
        summaryManual = false
        summaryError = null
        persistSummary()
    }

    /** The person's own version of the overview. An empty one gives the overview back to the model. */
    fun editSummary(text: String, now: Long = System.currentTimeMillis()) {
        val clean = text.trim()
        if (clean.isEmpty()) {
            summary = ""
            summaryManual = false
            summaryStale = true
        } else {
            summary = clean
            summaryManual = true
            summaryStale = false
        }
        summaryAt = now
        summaryError = null
        persistSummary()
    }

    /** What is new in the overview since the last rewrite: its lines that were not there before. Empty when there is nothing, or it was seen. */
    fun changes(): List<String> = if (changesSeen || summaryPrev.isEmpty()) emptyList() else newLines(summaryPrev, summary)

    fun dismissChanges() {
        if (changesSeen) return
        changesSeen = true
        persistSummary()
    }

    /** Anything the overview was built from has changed (a note, or a chat was deleted). */
    fun invalidateSummary() {
        if (summaryStale || summary.isEmpty()) return
        summaryStale = true
        persistSummary()
    }

    // ---------- for the prompt ----------

    /**
     * The notes as lines for the system prompt, with their ids. Notes that no longer hold are left out. When all do not fit, those
     * that touch what the person is asking about ([hint]) are kept first, then the newest ones; the rest is said to be hidden.
     */
    fun forPrompt(limit: Int = PROMPT_CHARS, hint: String = "", now: Long = System.currentTimeMillis()): String {
        val live = notes.filterNot { it.expired(now) }
        if (live.isEmpty()) return ""
        val kept = pick(live, hint, limit)
        val hidden = live.size - kept.size
        return kept.joinToString("\n") { "- [${it.id}] ${it.text.replace("\n", " ")}" } +
            if (hidden > 0) "\n($hidden other notes are not shown here: memory_list shows all of them.)" else ""
    }

    /** The project this belongs to is gone: so are its notes. */
    fun destroy() {
        notes.clear()
        summary = ""
        writer.execute { notesFile.delete(); summaryFile.delete() }
    }

    // ---------- storage ----------

    /** Called when the notes change (a note saved, edited or removed): the overview is written again a little later. */
    var onNotesChanged: (() -> Unit)? = null

    private fun changed() {
        summaryStale = summary.isNotEmpty()
        onNotesChanged?.invoke()
        val json = JSONArray().also { a ->
            notes.forEach { a.put(JSONObject().put("id", it.id).put("text", it.text).put("createdAt", it.createdAt).put("updatedAt", it.updatedAt).also { o -> if (it.expiresAt > 0) o.put("expiresAt", it.expiresAt) }) }
        }.toString()
        writer.execute { writeAtomic(notesFile, json) }
        if (summary.isNotEmpty()) persistSummary()
    }

    private fun persistSummary() {
        val json = JSONObject().put("text", summary).put("at", summaryAt).put("stale", summaryStale).put("manual", summaryManual).put("prev", summaryPrev).put("seen", changesSeen).toString()
        writer.execute { writeAtomic(summaryFile, json) }
    }

    private fun writeAtomic(file: File, text: String) {
        AtomicFile.writeQuietly("memory", file, text)
    }

    private fun freshId(): String {
        while (true) {
            val id = buildString { repeat(5) { append(ID_ALPHABET.random()) } }
            if (notes.none { it.id == id }) return id
        }
    }

    companion object {
        const val DAY = 86_400_000L
        const val MAX_NOTES = 200
        const val MAX_NOTE_CHARS = 4000
        const val PROMPT_CHARS = 4000
        private const val ID_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

        fun clean(raw: String): String = raw.replace(Regex("[ \\t]+"), " ").replace(Regex("\\s*\\n\\s*(\\n\\s*)+"), "\n\n").replace(Regex(" ?\\n ?"), "\n").trim().removePrefix("- ").trim().take(MAX_NOTE_CHARS)

        /** The same fact, written twice: equal when case, spaces and the final full stop are ignored. */
        fun same(a: String, b: String): Boolean {
            fun norm(s: String) = s.lowercase().replace(Regex("[\\s.!]+"), " ").trim()
            return norm(a) == norm(b)
        }

        /**
         * The notes that go to the prompt, oldest first, within [limit] characters. With a [hint] the notes that share words with it
         * come first (then the newer ones); without one the newest are kept, as before.
         */
        fun pick(notes: List<MemoryNote>, hint: String, limit: Int): List<MemoryNote> {
            val byAge = notes.sortedBy { it.createdAt }
            val words = ChatSearch.tokens(hint).map { ChatSearch.stem(it) }.distinct()
            val ranked = if (words.isEmpty()) byAge.asReversed() else {
                val newest = byAge.withIndex().associate { (i, n) -> n.id to i }
                byAge.sortedByDescending { n ->
                    val t = n.text.lowercase()
                    words.count { t.contains(it) } * 1000 + (newest[n.id] ?: 0)
                }
            }
            val kept = ArrayList<MemoryNote>()
            var used = 0
            for (n in ranked) {
                val len = n.text.length + 12
                if (used + len > limit && kept.isNotEmpty()) continue
                kept += n
                used += len
            }
            return kept.sortedBy { it.createdAt }
        }

        /** The lines of [now] (markup removed) that are not among the lines of [before]. */
        fun newLines(before: String, now: String): List<String> {
            fun norm(l: String) = l.trim().trimStart('-', '*', '#', ' ', '•').replace("**", "").lowercase().replace(Regex("[\\s.!]+"), " ").trim()
            val old = before.lines().map(::norm).filter { it.isNotEmpty() }.toSet()
            return now.lines().map { it.trim().trimStart('-', '*', '#', ' ', '•').replace("**", "").trim() }
                .filter { it.length > 3 && norm(it) !in old }.take(8)
        }
    }
}
