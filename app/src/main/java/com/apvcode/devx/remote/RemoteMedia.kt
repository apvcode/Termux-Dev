package com.apvcode.devx.remote

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The pictures and files that the computer shows in a chat: fetched from it piece by piece (`file.get`) into the cache the
 * first time they are wanted, and kept there. A card that is scrolled away mid-way starts again; the file is never half-used.
 */
object RemoteMedia {
    private val running = ConcurrentHashMap<String, CompletableDeferred<File?>>()

    private fun cacheFile(context: Context, m: RItem.Media): File {
        val safe = m.name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80).ifBlank { "file" }
        return File(File(context.cacheDir, "remote-media").apply { mkdirs() }, "${m.id}_$safe")
    }

    /** The file on the phone, if it is already fetched. */
    fun cached(context: Context, m: RItem.Media): File? = cacheFile(context, m).takeIf { it.exists() && it.length() == m.size }

    /** Fetches it (or waits for the fetch that is already running); null when the computer could not give it. */
    suspend fun fetch(context: Context, session: RemoteSession, m: RItem.Media, onProgress: (Float) -> Unit = {}): File? {
        cached(context, m)?.let { return it }
        val mine = CompletableDeferred<File?>()
        val other = running.putIfAbsent(m.id, mine)
        if (other != null) return other.await()
        return try {
            val result = withContext(Dispatchers.IO) { download(context, session, m, onProgress) }
            mine.complete(result)
            result
        } catch (e: Exception) {
            mine.complete(null)
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        } finally {
            running.remove(m.id, mine)
        }
    }

    /** A picture of the computer that an answer names by its path: the computer is asked to make it fetchable, then it is fetched. */
    suspend fun fetchPath(context: Context, session: RemoteSession, path: String): File? {
        val item = withContext(Dispatchers.IO) {
            runCatching {
                session.conn.call("media.resolve", JSONObject().put("s", session.id).put("paths", org.json.JSONArray().put(path)), 20_000).optJSONArray("items")?.optJSONObject(0)
            }.getOrNull()
        } ?: return null
        if (item.has("error") || !item.has("id")) return null
        val media = RItem.Media(0, item.optString("id"), item.optString("name"), item.optString("mime"), item.optLong("size"), false)
        return fetch(context, session, media)
    }

    private suspend fun download(context: Context, session: RemoteSession, m: RItem.Media, onProgress: (Float) -> Unit): File? {
        val target = cacheFile(context, m)
        val part = File(target.path + ".part")
        part.delete()
        var offset = 0L
        part.outputStream().use { out ->
            while (offset < m.size) {
                val r: JSONObject = session.conn.call("file.get", JSONObject().put("s", session.id).put("id", m.id).put("offset", offset).put("len", 32768), 60_000)
                val bytes = Base64.decode(r.optString("data"), Base64.DEFAULT)
                if (bytes.isEmpty()) break
                out.write(bytes)
                offset += bytes.size
                onProgress((offset.toFloat() / m.size).coerceIn(0f, 1f))
            }
        }
        if (offset < m.size) { part.delete(); return null }
        part.renameTo(target)
        return target
    }
}
