package com.apvcode.devx.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One thing in the trash: a chat, or a project or notebook with the chats that were in it. */
class TrashItem(
    val id: String,
    val kind: String,
    val title: String,
    val at: Long,
    /** For a project: how many chats went with it. */
    val chats: Int,
    /** The ids of the files (attachments, made files) that are still on disk for this item, so that nothing else removes them. */
    val keep: Set<String>,
) {
    val isProject: Boolean get() = kind == Trash.PROJECT

    /** Whole days until it is removed for good (0 on the last day). */
    fun daysLeft(now: Long, keepDays: Int = Trash.KEEP_DAYS): Int = (keepDays - (now - at) / DAY).toInt().coerceAtLeast(0)

    private companion object { const val DAY = 24L * 60 * 60 * 1000 }
}

/** What a project in the trash holds: its details, its chats, and its memory files (by their name). */
class ProjectBundle(val name: String, val meta: String, val chats: List<String>, val files: Map<String, ByteArray>)

/**
 * What the person deleted stays here for [KEEP_DAYS] days and can be put back. One folder per item (a chat is one file; a project is
 * its details, its chats and its memory), so a damaged item never spoils the others. The files of attachments are not copied: they
 * stay where they are and are removed only when the item is removed for good.
 */
class Trash(private val root: File) {
    fun list(): List<TrashItem> =
        (root.listFiles() ?: emptyArray()).filter { it.isDirectory && !it.name.endsWith(".tmp") }.mapNotNull(::read).sortedByDescending { it.at }

    fun putChat(title: String, chatJson: String, keep: Set<String>, now: Long = System.currentTimeMillis()): TrashItem =
        write(CHAT, title, now, 0, keep) { dir -> File(dir, "chat.json").writeText(chatJson) }

    fun putProject(name: String, metaJson: String, chatJsons: List<String>, files: Map<String, ByteArray>, keep: Set<String>, now: Long = System.currentTimeMillis()): TrashItem =
        write(PROJECT, name, now, chatJsons.size, keep) { dir ->
            File(dir, "project.json").writeText(metaJson)
            File(dir, "chats").mkdirs()
            chatJsons.forEachIndexed { i, json -> File(dir, "chats/$i.json").writeText(json) }
            File(dir, "memory").mkdirs()
            files.forEach { (n, bytes) -> File(dir, "memory/$n").writeBytes(bytes) }
        }

    fun chat(id: String): String? = File(File(root, id), "chat.json").takeIf { it.exists() }?.readText()

    fun project(id: String): ProjectBundle? {
        val dir = File(root, id)
        val meta = File(dir, "project.json").takeIf { it.exists() }?.readText() ?: return null
        val name = read(dir)?.title ?: return null
        val chats = (File(dir, "chats").listFiles() ?: emptyArray()).sortedBy { it.name.removeSuffix(".json").toIntOrNull() ?: 0 }.map { it.readText() }
        val files = (File(dir, "memory").listFiles() ?: emptyArray()).associate { it.name to it.readBytes() }
        return ProjectBundle(name, meta, chats, files)
    }

    fun remove(id: String) {
        if (id.isNotBlank() && !id.contains('/') && !id.contains("..")) File(root, id).deleteRecursively()
    }

    /** The items older than [keepDays] days. */
    fun expired(now: Long, keepDays: Int = KEEP_DAYS): List<TrashItem> = list().filter { it.daysLeft(now, keepDays) == 0 && now - it.at >= keepDays * 24L * 60 * 60 * 1000 }

    private fun write(kind: String, title: String, now: Long, chats: Int, keep: Set<String>, body: (File) -> Unit): TrashItem {
        val id = UUID.randomUUID().toString().take(12)
        // Written beside, then renamed: an item that is half written is never listed.
        val tmp = File(root, "$id.tmp").apply { deleteRecursively(); mkdirs() }
        body(tmp)
        File(tmp, "entry.json").writeText(JSONObject().put("kind", kind).put("title", title).put("at", now).put("chats", chats).put("keep", JSONArray(keep.toList())).toString())
        File(root, id).let { if (!tmp.renameTo(it)) { tmp.copyRecursively(it, overwrite = true); tmp.deleteRecursively() } }
        return TrashItem(id, kind, title, now, chats, keep)
    }

    private fun read(dir: File): TrashItem? = runCatching {
        val o = JSONObject(File(dir, "entry.json").readText())
        val keep = o.optJSONArray("keep")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
        TrashItem(dir.name, o.getString("kind"), o.optString("title"), o.optLong("at"), o.optInt("chats"), keep)
    }.logged("trash").getOrNull()

    companion object {
        const val CHAT = "chat"
        const val PROJECT = "project"
        const val KEEP_DAYS = 30
    }
}
