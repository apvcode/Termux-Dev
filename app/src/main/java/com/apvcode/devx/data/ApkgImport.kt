package com.apvcode.devx.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * The cards of an Anki package (.apkg, a zip with a small database and the pictures). The older database formats are read; the newest one
 * that Anki writes by default (compressed) is not, and the person is told how to export one that is readable.
 */
object ApkgImport {
    /** The package is in a format that is not read; the message says what to do. */
    class Unsupported(message: String) : Exception(message)

    /** Reads up to [limit] cards; the picture of a card is saved as a [CardImages] file and named in the draft. */
    fun read(context: Context, input: InputStream, limit: Int = 2000): List<CardDraft> {
        val work = File(context.cacheDir, "apkg_" + System.nanoTime().toString(36)).apply { mkdirs() }
        try {
            val zipFile = File(work, "in.apkg")
            zipFile.outputStream().use { input.copyTo(it) }
            ZipFile(zipFile).use { zip ->
                val dbEntry = zip.getEntry("collection.anki21") ?: zip.getEntry("collection.anki2")
                    ?: if (zip.getEntry("collection.anki21b") != null) throw Unsupported("NEW_FORMAT") else throw IllegalArgumentException("not an Anki package")
                val dbFile = File(work, "collection.db")
                zip.getInputStream(dbEntry).use { s -> dbFile.outputStream().use { s.copyTo(it) } }
                // Picture name -> the number it is stored under in the zip.
                val media = HashMap<String, String>()
                zip.getEntry("media")?.let { e ->
                    runCatching { JSONObject(zip.getInputStream(e).use { it.readBytes() }.decodeToString()).let { o -> o.keys().forEach { k -> media[o.getString(k)] = k } } }
                }
                val out = ArrayList<CardDraft>()
                val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
                try {
                    db.rawQuery("select flds, tags from notes", null).use { c ->
                        while (c.moveToNext() && out.size < limit) {
                            val note = CardIO.fromNote(c.getString(0).orEmpty(), c.getString(1).orEmpty()) ?: continue
                            var image = ""
                            if (note.image.isNotEmpty()) media[note.image]?.let { number ->
                                zip.getEntry(number)?.let { e -> if (e.size in 1..12_000_000) image = CardImages.saveBytes(context, zip.getInputStream(e).use { it.readBytes() }).orEmpty() }
                            }
                            out += CardDraft(note.draft.front, note.draft.back, note.draft.topic, image)
                        }
                    }
                } finally {
                    db.close()
                }
                return out
            }
        } finally {
            work.deleteRecursively()
        }
    }
}
