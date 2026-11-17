package com.apvcode.devx.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/** The pictures of cards: each is shrunk (the long side to 1024) and kept as a small JPEG in the app's own folder, which a backup carries. */
object CardImages {
    private const val MAX_SIDE = 1024
    private const val MAX_BYTES = 12 * 1024 * 1024

    private fun dir(context: Context) = File(context.filesDir, "card_images").apply { mkdirs() }

    /** The file of a picture by the name a card holds (a name that tries to leave the folder gives nothing). */
    fun file(context: Context, name: String): File? =
        name.takeIf { it.isNotEmpty() && '/' !in it && ".." !in it }?.let { File(dir(context), it) }?.takeIf { it.exists() }

    /** A picture the person chose. Gives the name to keep on the card, or null if it could not be read. */
    fun saveFromUri(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            // Read in pieces up to the limit (a huge file is not pulled into memory whole).
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (out.size() < MAX_BYTES) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) }
            out.toByteArray()
        }?.let { saveBytes(context, it) }
    }.onFailure { ErrorLog.warn("cardimages", "a picture could not be read", it) }.getOrNull()

    fun saveBytes(context: Context, bytes: ByteArray): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true) else decoded
        val name = "ci" + System.nanoTime().toString(36) + ".jpg"
        File(dir(context), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (bitmap !== decoded) decoded.recycle()
        return name
    }

    /** A picture ready to draw, no bigger than [maxSide] on its long side; null if it is gone. */
    fun load(context: Context, name: String, maxSide: Int = 800): Bitmap? = file(context, name)?.let { f ->
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }
}
