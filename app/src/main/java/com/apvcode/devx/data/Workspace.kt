package com.apvcode.devx.data

import android.content.Context
import android.webkit.MimeTypeMap
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * Each chat has a folder of its own where the assistant works: the files it makes, the archives it
 * unpacks, the pictures it edits. The attachments of the chat appear in it, under `uploads/`, as links.
 * Nothing outside the folder is reached by the file tools.
 */
class Workspace(context: Context) {
    private val root = File(context.filesDir, "workspace")

    fun dirOf(chatId: String): File = File(root, chatId).apply { mkdirs() }

    fun delete(chatId: String) {
        File(root, chatId).deleteRecursively()
    }

    /** Links the chat's attachments into `uploads/`, so that the tools (and the terminal) can work on them. */
    fun syncUploads(chatId: String, files: List<Attachment>, store: AttachmentStore) {
        val uploads = File(dirOf(chatId), "uploads").apply { mkdirs() }
        val wanted = HashMap<String, Attachment>()
        for (a in files) {
            var name = a.name.replace('/', '_').ifBlank { a.id }
            var n = 1
            while (name in wanted) name = a.name.substringBeforeLast('.') + "-${n++}" + (a.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" })
            wanted[name] = a
        }
        uploads.listFiles()?.forEach { if (it.name !in wanted) it.delete() }
        for ((name, a) in wanted) {
            val link = File(uploads, name)
            if (link.exists() || Files.isSymbolicLink(link.toPath())) continue
            val source = store.fileOf(a)
            runCatching { Files.createSymbolicLink(link.toPath(), source.toPath()) }
                .onFailure { runCatching { source.copyTo(link) } }
        }
    }

    /** A path as the model wrote it, inside the chat's folder; null if it points anywhere else. */
    fun resolve(chatId: String, relative: String): File? {
        val base = dirOf(chatId).toPath().toAbsolutePath().normalize()
        val cleaned = relative.trim().removePrefix("./").ifEmpty { "." }
        val target = base.resolve(cleaned).normalize()
        return if (target.startsWith(base)) target.toFile() else null
    }

    fun relative(chatId: String, file: File): String =
        file.toPath().toAbsolutePath().normalize().let { dirOf(chatId).toPath().toAbsolutePath().normalize().relativize(it).toString() }
}

/** What the assistant handed over to the person: a file shown as a card in the answer. */
data class Artifact(val id: String, val name: String, val mime: String, val size: Long) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
}

/** The files the assistant attached to its answers. They live in the app's own folder, named by an id. */
class ArtifactStore(context: Context) {
    private val root = File(context.filesDir, "artifacts").apply { mkdirs() }

    fun add(source: File, name: String): Artifact {
        val id = UUID.randomUUID().toString().take(12)
        val safe = name.substringAfterLast('/').ifBlank { "file" }
        val dir = File(root, id).apply { mkdirs() }
        val target = File(dir, safe)
        source.copyTo(target, overwrite = true)
        val mime = mimeOf(safe)
        File(dir, "meta.json").writeText(JSONObject().put("name", safe).put("mime", mime).put("size", target.length()).toString())
        return Artifact(id, safe, mime, target.length())
    }

    fun get(id: String): Artifact? = runCatching {
        val dir = File(root, id.trim())
        val meta = JSONObject(File(dir, "meta.json").readText())
        Artifact(id.trim(), meta.getString("name"), meta.optString("mime", "application/octet-stream"), meta.optLong("size"))
    }.getOrNull()

    fun fileOf(a: Artifact): File = File(File(root, a.id), a.name)

    fun delete(id: String) {
        File(root, id.trim()).deleteRecursively()
    }

    companion object {
        fun mimeOf(name: String): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: when (name.substringAfterLast('.', "").lowercase()) {
                "py", "kt", "js", "ts", "go", "rs", "java", "c", "cpp", "h", "sh", "md", "yml", "yaml", "toml", "ini", "log", "csv", "json", "xml", "html", "css" -> "text/plain"
                else -> "application/octet-stream"
            }
    }
}
