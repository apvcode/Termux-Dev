package com.apvcode.devx.data

import java.util.zip.ZipFile
import android.database.sqlite.SQLiteDatabase
import android.database.Cursor
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Photos and files that people attach. They are copied into the app's own folder right away (the
 * picker's link would stop working later), and photos are shrunk: a phone photo is 5 MB, and fifty
 * of them would not fit into one request.
 */
class AttachmentStore(private val context: Context) {
    private val dir = File(context.filesDir, "attachments").apply { mkdirs() }

    fun fileOf(a: Attachment): File = File(dir, a.file)

    /** How many pages a PDF has; null when it cannot be opened. */
    fun pdfPages(a: Attachment): Int? = runCatching {
        android.os.ParcelFileDescriptor.open(fileOf(a), android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            android.graphics.pdf.PdfRenderer(fd).use { it.pageCount }
        }
    }.getOrNull()

    /** The still picture shown for a video. */
    fun thumbOf(a: Attachment): File = File(dir, a.file.substringBeforeLast('.') + ".thumb.jpg")

    fun delete(a: Attachment) {
        fileOf(a).delete()
        if (a.kind == AttachmentKind.Video) thumbOf(a).delete()
        if (a.kind == AttachmentKind.Image) File(dir, a.file.substringBeforeLast('.') + ".orig.jpg").delete()
    }

    fun readBase64(a: Attachment): String = Base64.encodeToString(fileOf(a).readBytes(), Base64.NO_WRAP)

    fun readText(a: Attachment): String = fileOf(a).readText()

    /** Words the person picked out of a message: they join the next message as a text file ("Fragment 1.txt"), as if one had been attached. */
    fun saveText(name: String, text: String): Attachment {
        val id = UUID.randomUUID().toString()
        val out = File(dir, "$id.txt")
        out.writeText(text)
        return Attachment(id, AttachmentKind.Text, name, "text/plain", out.name, out.length())
    }

    /**
     * A photo, turned upright, scaled so its long side is at most [maxEdge], saved as JPEG: that is the copy that goes
     * into the request. A bigger original is kept too (up to [keepEdge]) for [originalOf]: the assistant zooms into it
     * with edit_image when something in the reduced copy is too small to read.
     */
    suspend fun importImage(uri: Uri, maxEdge: Int, quality: Int, keepEdge: Int = KEEP_EDGE): Attachment? = withContext(Dispatchers.IO) {
        // Out of memory with the big original: try again keeping only the reduced copy.
        importImageAt(uri, maxEdge, quality, keepEdge) ?: if (keepEdge > maxEdge) importImageAt(uri, maxEdge, quality, maxEdge) else null
    }

    private fun importImageAt(uri: Uri, maxEdge: Int, quality: Int, keepEdge: Int): Attachment? {
        val id = UUID.randomUUID().toString()
        val out = File(dir, "$id.jpg")
        val original = File(dir, "$id.orig.jpg")
        return try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > keepEdge.coerceAtLeast(maxEdge)) sample *= 2
            var bitmap = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null

            val orientation = runCatching {
                resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            }
            if (!matrix.isIdentity) {
                val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (turned !== bitmap) bitmap.recycle()
                bitmap = turned
            }
            // A picture with see-through parts (a PNG) would turn black as a JPEG: it goes onto white.
            bitmap = onWhite(bitmap)

            // The original first (it is only kept when it is bigger than the copy the model gets).
            val longSide = maxOf(bitmap.width, bitmap.height)
            if (keepEdge > maxEdge && longSide > maxEdge) {
                val kept = scaledTo(bitmap, keepEdge)
                FileOutputStream(original).use { kept.compress(Bitmap.CompressFormat.JPEG, KEEP_QUALITY, it) }
                if (kept !== bitmap) kept.recycle()
            }
            val small = scaledTo(bitmap, maxEdge)
            FileOutputStream(out).use { small.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            if (small !== bitmap) small.recycle()
            bitmap.recycle()
            Attachment(id, AttachmentKind.Image, displayName(uri) ?: "photo.jpg", "image/jpeg", out.name, out.length())
        } catch (e: Exception) {
            out.delete(); original.delete()
            null
        } catch (e: OutOfMemoryError) {
            out.delete(); original.delete()
            null
        }
    }

    /** The photo as big as it was kept (for zooming into); the same file as [fileOf] when no bigger copy exists. */
    fun originalOf(a: Attachment): File =
        File(dir, a.file.substringBeforeLast('.') + ".orig.jpg").takeIf { a.kind == AttachmentKind.Image && it.isFile } ?: fileOf(a)

    private fun onWhite(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(flat).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
        bitmap.recycle()
        return flat
    }

    /** A video: copied as it is, with a picture from its beginning and its length. Too big ones come back as null. */
    suspend fun importVideo(uri: Uri): Attachment? = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val out = File(dir, "$id.mp4")
        try {
            var total = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(out).use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_VIDEO) {
                            out.delete()
                            return@withContext null
                        }
                        sink.write(buffer, 0, n)
                    }
                }
            } ?: return@withContext null

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(out.absolutePath)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val still = retriever.getFrameAtTime(minOf(300_000L, duration * 500), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(0)
                val attachment = Attachment(id, AttachmentKind.Video, displayName(uri) ?: "video.mp4", "video/mp4", out.name, out.length(), duration)
                still?.let { saveScaled(it, thumbOf(attachment), 640, 82) }
                attachment
            } finally {
                runCatching { retriever.release() }
            }
        } catch (e: Exception) {
            out.delete()
            null
        }
    }

    /**
     * Pictures from a video, evenly along its length, as base64 JPEG: that is how a model "sees" it.
     * Providers do not take videos, but they do take a handful of pictures.
     */
    fun videoFrames(a: Attachment, count: Int = VIDEO_FRAMES): List<String> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(fileOf(a).absolutePath)
            val duration = a.durationMs.takeIf { it > 0 }
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            (0 until count).mapNotNull { i ->
                val at = duration * 1000L * (2 * i + 1) / (2 * count)
                retriever.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST)?.let { frame ->
                    val bytes = java.io.ByteArrayOutputStream()
                    val scaled = scaledTo(frame, 1024)
                    scaled.compress(Bitmap.CompressFormat.JPEG, 80, bytes)
                    if (scaled !== frame) scaled.recycle()
                    frame.recycle()
                    Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
                }
            }
        } catch (e: Exception) {
            emptyList()
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** One frame of a video, as big as it is, at [seconds] from the start (null if it cannot be taken). */
    fun videoFrameAt(a: Attachment, seconds: Double): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(fileOf(a).absolutePath)
            retriever.getFrameAtTime((seconds.coerceAtLeast(0.0) * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaledTo(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longSide = maxOf(bitmap.width, bitmap.height)
        if (longSide <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longSide
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
    }

    private fun saveScaled(bitmap: Bitmap, file: File, maxEdge: Int, quality: Int) {
        val scaled = scaledTo(bitmap, maxEdge)
        FileOutputStream(file).use { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
    }

    /**
     * Any file at all. A PDF goes along as a document. Word, Excel, PowerPoint and OpenDocument files
     * and SQLite databases are read into text (what is in them is what the model gets). Plain text
     * stays text. Everything else is kept whole as a file: the model is told its name and size,
     * and the person can still send it on from the chat. Only a file over [MAX_FILE] (a PDF: [MAX_PDF]) is refused.
     */
    suspend fun importFile(uri: Uri): Attachment? = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val name = displayName(uri) ?: "file"
            val mime = resolver.getType(uri).orEmpty()
            val extension = name.substringAfterLast('.', "").lowercase()
            val id = UUID.randomUUID().toString()

            if (mime == "application/pdf" || extension == "pdf") {
                val out = File(dir, "$id.pdf")
                // Copied with a limit: a file over it is refused before it fills the phone.
                if (!copyLimited(uri, out, MAX_PDF)) return@withContext null
                return@withContext Attachment(id, AttachmentKind.Pdf, name, "application/pdf", out.name, out.length())
            }

            if (extension in OFFICE_EXTENSIONS || extension in DATABASE_EXTENSIONS) {
                val temp = File(context.cacheDir, "import_$id.$extension")
                try {
                    if (copyLimited(uri, temp, MAX_OFFICE)) {
                        val text = if (extension in DATABASE_EXTENSIONS) databaseSummary(temp) else FileText.office(temp, extension, MAX_TEXT)
                        if (text != null) {
                            val out = File(dir, "$id.txt")
                            out.writeText(text)
                            return@withContext Attachment(id, AttachmentKind.Text, name, "text/plain", out.name, out.length())
                        }
                    }
                } finally {
                    temp.delete()
                }
            }

            val head = resolver.openInputStream(uri)?.use { it.readNBytes(MAX_TEXT + 1) } ?: return@withContext null
            val looksLikeText = mime.startsWith("text/") || extension in TEXT_EXTENSIONS ||
                mime == "application/json" || mime == "application/xml" ||
                (head.isNotEmpty() && head.take(8192).none { it.toInt() == 0 })
            if (looksLikeText && head.size <= MAX_TEXT * 2) {
                val kept = if (head.size > MAX_TEXT) head.copyOf(MAX_TEXT) else head
                val out = File(dir, "$id.txt")
                out.writeBytes(kept)
                return@withContext Attachment(id, AttachmentKind.Text, name, "text/plain", out.name, out.length())
            }

            val out = File(dir, "$id.bin")
            if (!copyLimited(uri, out, MAX_FILE)) return@withContext null
            Attachment(id, AttachmentKind.File, name, mime.ifBlank { "application/octet-stream" }, out.name, out.length())
        } catch (e: Exception) {
            null
        }
    }

    /** Copies [uri] into [to]; false (and nothing left behind) when it is bigger than [limit]. */
    private fun copyLimited(uri: Uri, to: File, limit: Long): Boolean {
        var total = 0L
        val ok = context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(to).use { sink ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) return@use true
                    total += n
                    if (total > limit) return@use false
                    sink.write(buffer, 0, n)
                }
                @Suppress("UNREACHABLE_CODE") true
            }
        } ?: false
        if (!ok) to.delete()
        return ok
    }

    /** The tables of an SQLite file: how each is made, how many rows it has, and the first few rows. */
    private fun databaseSummary(file: File): String? = runCatching {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val out = StringBuilder("SQLite database\n")
            db.rawQuery(
                "SELECT name, sql FROM sqlite_master WHERE type IN ('table','view') AND name NOT LIKE 'sqlite_%' ORDER BY name LIMIT 40",
                null,
            ).use { tables ->
                while (tables.moveToNext()) {
                    val table = tables.getString(0)
                    val quoted = "\"" + table.replace("\"", "\"\"") + "\""
                    out.append("\n-- ").append(table)
                    runCatching {
                        db.rawQuery("SELECT COUNT(*) FROM $quoted", null).use { if (it.moveToFirst()) out.append(" (").append(it.getLong(0)).append(" rows)") }
                    }
                    out.append("\n").append(tables.getString(1) ?: "").append(";\n")
                    runCatching {
                        db.rawQuery("SELECT * FROM $quoted LIMIT 5", null).use { rows ->
                            out.append(rows.columnNames.joinToString(" | ")).append("\n")
                            while (rows.moveToNext()) {
                                out.append(
                                    (0 until rows.columnCount).joinToString(" | ") { i ->
                                        if (rows.getType(i) == Cursor.FIELD_TYPE_BLOB) "<blob>" else (rows.getString(i) ?: "NULL").take(80)
                                    },
                                ).append("\n")
                            }
                        }
                    }
                }
            }
            out.toString().take(MAX_TEXT)
        }
    }.getOrNull()

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    private companion object {
        const val MAX_TEXT = 400_000
        const val MAX_PDF = 200L * 1024 * 1024
        const val MAX_VIDEO = 150L * 1024 * 1024
        const val MAX_FILE = 200L * 1024 * 1024
        /** Word, Excel and databases are read into memory to be turned into text, so they stay smaller. */
        const val MAX_OFFICE = 50L * 1024 * 1024
        val OFFICE_EXTENSIONS = setOf("docx", "docm", "xlsx", "xlsm", "pptx", "odt", "ods", "odp")
        val DATABASE_EXTENSIONS = setOf("db", "sqlite", "sqlite3", "db3")
        const val VIDEO_FRAMES = 6
        /** The long side of the photo that is kept beside the reduced copy, and its JPEG quality. */
        const val KEEP_EDGE = 4096
        const val KEEP_QUALITY = 90
        val TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "json", "csv", "tsv", "xml", "yaml", "yml", "toml", "ini", "log", "kt", "kts", "java",
            "py", "js", "ts", "tsx", "jsx", "c", "h", "cpp", "hpp", "cs", "go", "rs", "rb", "php", "sh", "bash", "sql",
            "html", "css", "scss", "swift", "gradle", "properties", "tex", "bib", "rst",
        )
    }
}
