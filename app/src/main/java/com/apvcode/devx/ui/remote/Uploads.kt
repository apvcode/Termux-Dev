package com.apvcode.devx.ui.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.apvcode.devx.remote.RemoteConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** A file on its way to the computer (or already there), waiting for the message that carries it. */
class PendingFile(val name: String, val mime: String) {
    /** Where it came from on the phone: the picture in the composer and in the bubble is drawn from it. */
    var uri: Uri? = null
    val isImage: Boolean get() = mime.startsWith("image/")
    var uid by mutableStateOf<String?>(null)
    var progress by mutableFloatStateOf(0f)
    var error by mutableStateOf<String?>(null)
    val ready: Boolean get() = uid != null && error == null
}

object Uploads {
    private const val CHUNK = 48 * 1024
    private const val MAX_EDGE = 1600

    fun describe(context: Context, uri: Uri): Pair<String, String> {
        var name = "file"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: name }
        return name to (context.contentResolver.getType(uri) ?: "application/octet-stream")
    }

    /** Reads the file (pictures are made smaller first) and sends it in pieces. The result is in `file.uid`. */
    suspend fun send(context: Context, conn: RemoteConnection, uri: Uri, file: PendingFile) = withContext(Dispatchers.IO) {
        try {
            val length = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
            if (length > 25L * 1024 * 1024) throw IllegalStateException(context.getString(com.apvcode.devx.R.string.remote_file_too_big))
            var bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("cannot read the file")
            var name = file.name
            var mime = file.mime
            if (mime.startsWith("image/") && mime != "image/gif" && mime != "image/svg+xml") {
                shrink(bytes)?.let { bytes = it; mime = "image/jpeg"; if (!name.lowercase().endsWith(".jpg") && !name.lowercase().endsWith(".jpeg")) name = name.substringBeforeLast('.') + ".jpg" }
            }
            val begin = conn.call("upload.begin", JSONObject().put("name", name).put("mime", mime).put("size", bytes.size))
            val uid = begin.getString("uid")
            var at = 0
            while (at < bytes.size) {
                val end = minOf(bytes.size, at + CHUNK)
                conn.call("upload.chunk", JSONObject().put("uid", uid).put("data", Base64.encodeToString(bytes, at, end - at, Base64.NO_WRAP)))
                at = end
                file.progress = at.toFloat() / bytes.size
            }
            conn.call("upload.end", JSONObject().put("uid", uid))
            file.progress = 1f
            file.uid = uid
        } catch (e: Exception) {
            file.error = e.message ?: "failed"
        }
    }

    private fun shrink(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE * 2) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = MAX_EDGE.toFloat() / maxOf(bmp.width, bmp.height)
        val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }
}
