package com.apvcode.devx.data

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import com.apvcode.devx.model.Role

/** What a file is, as far as the library is concerned (it decides the picture, the filter and how it is opened). */
enum class LibKind { Image, Pdf, Word, Sheet, Slides, Text, Code, Video, Site, Other }

/** Who put the file in the library. */
enum class LibOrigin { Sent, Made, Source }

/** One place where a file was used: a message of a chat. */
data class LibraryUse(val chatId: String, val chatTitle: String, val messageId: String, val at: Long)

/**
 * A file of the library. The library is not a second copy of anything: it is a view over what is already kept (the photos and files
 * sent in chats, the files the assistant made, the sources of notebooks), with what the assistant said about each file.
 */
class LibraryEntry(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val kind: LibKind,
    val origin: LibOrigin,
    val at: Long,
    val attachment: Attachment?,
    val artifact: Artifact?,
    val url: String,
    val summary: String,
    val uses: List<LibraryUse>,
    val notebooks: List<String>,
)

/** The real type of the file: an office document is kept as given, although the attachment says "text" (that is what the model reads). */
fun LibraryEntry.realMime(): String = if (attachment?.original?.isNotEmpty() == true) ArtifactStore.mimeOf(name) else mime

fun libKindOf(name: String, mime: String, kind: AttachmentKind?, url: String = ""): LibKind {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        url.isNotEmpty() -> LibKind.Site
        kind == AttachmentKind.Image || mime.startsWith("image/") -> LibKind.Image
        kind == AttachmentKind.Video || mime.startsWith("video/") -> LibKind.Video
        kind == AttachmentKind.Pdf || ext == "pdf" -> LibKind.Pdf
        ext in setOf("doc", "docx", "odt", "rtf") -> LibKind.Word
        ext in setOf("xls", "xlsx", "ods", "csv", "tsv") -> LibKind.Sheet
        ext in setOf("ppt", "pptx", "odp") -> LibKind.Slides
        ext in setOf("py", "kt", "js", "ts", "go", "rs", "java", "c", "cpp", "h", "sh", "json", "xml", "html", "css", "yml", "yaml", "toml", "sql", "md") -> LibKind.Code
        kind == AttachmentKind.Text || mime.startsWith("text/") || ext in setOf("txt", "log", "ini") -> LibKind.Text
        else -> LibKind.Other
    }
}

/** What the assistant said about each file, kept between launches (one small file). */
class LibraryMeta(context: Context) {
    private val file = File(context.filesDir, "library_meta.json")
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "devx-library").apply { isDaemon = true } }

    /** The short description of a file by its id (what the assistant took from it). */
    val summaries = mutableStateMapOf<String, String>()

    /** The files that are being read now ("pending") or that could not be read ("failed"). Not kept. */
    val states = mutableStateMapOf<String, String>()

    /** When each file was first seen. */
    private val firstSeen = HashMap<String, Long>()

    init {
        attempt("library") {
            val root = JSONObject(file.takeIf { it.exists() }?.readText() ?: "{}")
            root.keys().forEach { id ->
                val o = root.getJSONObject(id)
                o.optString("summary").takeIf { it.isNotBlank() }?.let { summaries[id] = it }
                firstSeen[id] = o.optLong("at")
            }
        }
    }

    fun seen(id: String, fallback: Long): Long = firstSeen.getOrPut(id) { fallback }

    fun setSummary(id: String, text: String) {
        summaries[id] = text.trim()
        states.remove(id)
        save()
    }

    fun forget(id: String) {
        summaries.remove(id)
        states.remove(id)
        firstSeen.remove(id)
        save()
    }

    private fun save() {
        val json = JSONObject().also { root ->
            (summaries.keys + firstSeen.keys).toSet().forEach { id -> root.put(id, JSONObject().put("summary", summaries[id].orEmpty()).put("at", firstSeen[id] ?: 0L)) }
        }.toString()
        writer.execute { AtomicFile.writeQuietly("library", file, json) }
    }
}

object Library {
    private val FILE_BLOCK = Regex("```devx-file\\n([^\\n`]+)\\n```")

    /**
     * Every file the app knows, newest first: what was sent in chats (and where), what the assistant made, and the sources of
     * notebooks. Temporary and locked chats are not looked into.
     */
    fun build(
        chats: List<Chat>,
        projects: Map<String, ProjectInfo>,
        artifactOf: (String) -> Artifact?,
        summaries: Map<String, String>,
        seen: (String, Long) -> Long = { _, fallback -> fallback },
    ): List<LibraryEntry> {
        class Draft(val id: String, var name: String, var mime: String, var size: Long, var origin: LibOrigin, var at: Long, var attachment: Attachment?, var artifact: Artifact?, var url: String) {
            val uses = ArrayList<LibraryUse>()
            val notebooks = LinkedHashSet<String>()
        }
        val drafts = LinkedHashMap<String, Draft>()
        for (chat in chats) {
            if (chat.temporary || chat.locked) continue
            for (m in chat.messages) {
                if (m.role == Role.User) {
                    for (a in m.attachments) {
                        if (a.isQuote) continue
                        val d = drafts.getOrPut(a.id) { Draft(a.id, a.name, a.mime, a.size, LibOrigin.Sent, m.createdAt, a, null, a.url) }
                        d.uses += LibraryUse(chat.id, chat.title, m.id, m.createdAt)
                        d.at = minOf(d.at, m.createdAt)
                    }
                } else {
                    for (match in FILE_BLOCK.findAll(m.text)) {
                        val aid = match.groupValues[1].trim()
                        val art = artifactOf(aid) ?: continue
                        val d = drafts.getOrPut("art:$aid") { Draft("art:$aid", art.name, art.mime, art.size, LibOrigin.Made, m.createdAt, null, art, "") }
                        d.uses += LibraryUse(chat.id, chat.title, m.id, m.createdAt)
                    }
                }
            }
        }
        for ((name, info) in projects) {
            for (a in info.sources) {
                val d = drafts.getOrPut(a.id) { Draft(a.id, a.name, a.mime, a.size, LibOrigin.Source, 0L, a, null, a.url) }
                d.notebooks += name
            }
        }
        return drafts.values.map { d ->
            val kind = libKindOf(d.name, d.mime, d.attachment?.kind, d.url)
            LibraryEntry(
                id = d.id, name = d.name, mime = d.mime, size = d.size, kind = kind, origin = d.origin,
                at = seen(d.id, d.at.takeIf { it > 0 } ?: System.currentTimeMillis()), attachment = d.attachment, artifact = d.artifact,
                url = d.url, summary = summaries[d.id].orEmpty(), uses = d.uses.sortedByDescending { it.at }, notebooks = d.notebooks.toList(),
            )
        }.sortedByDescending { it.at }
    }

    /** The files whose name, description, address, notebook or chat title has all the words of [query]; best matches first. */
    fun search(entries: List<LibraryEntry>, query: String): List<LibraryEntry> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return entries
        return entries.mapNotNull { e ->
            val name = e.name.lowercase()
            val rest = (e.summary + " " + e.url + " " + e.notebooks.joinToString(" ") + " " + e.uses.joinToString(" ") { it.chatTitle }).lowercase()
            if (words.any { it !in name && it !in rest }) return@mapNotNull null
            // A word in the name counts more than a word in the rest.
            e to words.sumOf { (if (it in name) 3 else 0) + (if (it in rest) 1 else 0) }
        }.sortedWith(compareByDescending<Pair<LibraryEntry, Int>> { it.second }.thenByDescending { it.first.at }).map { it.first }
    }
}
