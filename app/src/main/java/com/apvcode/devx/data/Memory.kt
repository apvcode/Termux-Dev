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
data class MemoryNote(val id: String, val text: String, val createdAt: Long, val updatedAt: Long)

/**
 * What the assistant remembers about the person, across all conversations: short notes the assistant (or
 * the person) writes, and an overview ("summary") that the model writes from the notes and the chats.
 * Kept in two small files in the app's folder; never sent anywhere except inside the requests to the
 * provider the person chose, and only while the memory is switched on.
 */
class MemoryStore(context: Context, notesName: String = "memory.json", summaryName: String = "memory_summary.json") {
    private val notesFile = File(context.filesDir, notesName)
    private val summaryFile = File(context.filesDir, summaryName)
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
    var summarizing by mutableStateOf(false)
    var summaryError by mutableStateOf<ErrorKind?>(null)

    suspend fun load() {
        val (loaded, text, at, stale, manual) = withContext(Dispatchers.IO) {
            val list = runCatching {
                JSONArray(notesFile.takeIf { it.exists() }?.readText() ?: "[]").let { a ->
                    (0 until a.length()).map { i ->
                        val o = a.getJSONObject(i)
                        MemoryNote(o.getString("id"), o.getString("text"), o.optLong("createdAt"), o.optLong("updatedAt"))
                    }
                }
            }.getOrDefault(emptyList())
            val s = runCatching { JSONObject(summaryFile.takeIf { it.exists() }?.readText() ?: "{}") }.getOrDefault(JSONObject())
            Loaded(list, s.optString("text"), s.optLong("at"), s.optBoolean("stale"), s.optBoolean("manual"))
        }
        notes.clear()
        notes.addAll(loaded)
        summary = text
        summaryAt = at
        summaryStale = stale
        summaryManual = manual
    }

    private data class Loaded(val notes: List<MemoryNote>, val text: String, val at: Long, val stale: Boolean, val manual: Boolean)

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
    fun add(raw: String, now: Long = System.currentTimeMillis()): Result {
        val text = clean(raw)
        if (text.isEmpty()) return Result.Failed("The note is empty.")
        notes.firstOrNull { same(it.text, text) }?.let { return Result.Duplicate(it) }
        if (notes.size >= MAX_NOTES) return Result.Failed("The memory is full ($MAX_NOTES notes). Remove or merge old notes first.")
        val note = MemoryNote(freshId(), text, now, now)
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
        val all = notes.sortedByDescending { it.updatedAt }
        if (words.isEmpty()) return all
        return all.filter { n -> val t = n.text.lowercase(); words.all { t.contains(ChatSearch.stem(it)) } }
    }

    // ---------- the overview ----------

    fun saveSummary(text: String, now: Long = System.currentTimeMillis()) {
        summary = text.trim()
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

    /** Anything the overview was built from has changed (a note, or a chat was deleted). */
    fun invalidateSummary() {
        if (summaryStale || summary.isEmpty()) return
        summaryStale = true
        persistSummary()
    }

    // ---------- for the prompt ----------

    /** The notes as lines for the system prompt, with their ids; the oldest are left out when there are too many. */
    fun forPrompt(limit: Int = PROMPT_CHARS): String {
        if (notes.isEmpty()) return ""
        val lines = notes.sortedBy { it.createdAt }.map { "- [${it.id}] ${it.text.replace("\n", " ")}" }
        var used = 0
        val kept = ArrayList<String>()
        for (line in lines.asReversed()) {
            if (used + line.length > limit && kept.isNotEmpty()) break
            kept += line
            used += line.length + 1
        }
        val hidden = lines.size - kept.size
        return kept.asReversed().joinToString("\n") +
            if (hidden > 0) "\n($hidden older notes are not shown here: memory_list shows all of them.)" else ""
    }

    /** The project this belongs to is gone: so are its notes. */
    fun destroy() {
        notes.clear()
        summary = ""
        writer.execute { notesFile.delete(); summaryFile.delete() }
    }

    // ---------- storage ----------

    private fun changed() {
        summaryStale = summary.isNotEmpty()
        val json = JSONArray().also { a ->
            notes.forEach { a.put(JSONObject().put("id", it.id).put("text", it.text).put("createdAt", it.createdAt).put("updatedAt", it.updatedAt)) }
        }.toString()
        writer.execute { writeAtomic(notesFile, json) }
        if (summary.isNotEmpty()) persistSummary()
    }

    private fun persistSummary() {
        val json = JSONObject().put("text", summary).put("at", summaryAt).put("stale", summaryStale).put("manual", summaryManual).toString()
        writer.execute { writeAtomic(summaryFile, json) }
    }

    private fun writeAtomic(file: File, text: String) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun freshId(): String {
        while (true) {
            val id = buildString { repeat(5) { append(ID_ALPHABET.random()) } }
            if (notes.none { it.id == id }) return id
        }
    }

    companion object {
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
    }
}
