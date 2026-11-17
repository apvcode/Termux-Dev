package com.apvcode.devx.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The cards as an Anki package (.apkg): a zip with the small database Anki reads (the older format, which every version of Anki opens)
 * and the pictures. Every card becomes a "Basic" note (front, back) with the topic as its tag, and is new in Anki: what was learned
 * here is not carried over, because Anki counts its own days.
 */
object ApkgExport {
    /** The tables of the database. */
    internal val SCHEMA = listOf(
        "CREATE TABLE col (id integer primary key, crt integer not null, mod integer not null, scm integer not null, ver integer not null, dty integer not null, usn integer not null, ls integer not null, conf text not null, models text not null, decks text not null, dconf text not null, tags text not null)",
        "CREATE TABLE notes (id integer primary key, guid text not null, mid integer not null, mod integer not null, usn integer not null, tags text not null, flds text not null, sfld integer not null, csum integer not null, flags integer not null, data text not null)",
        "CREATE TABLE cards (id integer primary key, nid integer not null, did integer not null, ord integer not null, mod integer not null, usn integer not null, type integer not null, queue integer not null, due integer not null, ivl integer not null, factor integer not null, reps integer not null, lapses integer not null, left integer not null, odue integer not null, odid integer not null, flags integer not null, data text not null)",
        "CREATE TABLE revlog (id integer primary key, cid integer not null, usn integer not null, ease integer not null, ivl integer not null, lastIvl integer not null, factor integer not null, time integer not null, type integer not null)",
        "CREATE TABLE graves (usn integer not null, oid integer not null, type integer not null)",
        "CREATE INDEX ix_notes_usn on notes (usn)",
        "CREATE INDEX ix_cards_usn on cards (usn)",
        "CREATE INDEX ix_revlog_usn on revlog (usn)",
        "CREATE INDEX ix_cards_nid on cards (nid)",
        "CREATE INDEX ix_cards_sched on cards (did, queue, due)",
        "CREATE INDEX ix_revlog_cid on revlog (cid)",
        "CREATE INDEX ix_notes_csum on notes (csum)",
    )

    /** One field of a note: text made safe for Anki's markup, line breaks kept. */
    internal fun html(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\r\n", "\n").replace("\n", "<br>")

    /** Anki's check number of a note: the first eight hex digits of the SHA-1 of its first field without markup. */
    internal fun checksum(firstField: String): Long {
        val plain = CardIO.fromHtml(firstField)
        val digest = MessageDigest.getInstance("SHA-1").digest(plain.toByteArray(Charsets.UTF_8))
        return digest.take(4).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
    }

    /** Tags are words without spaces. */
    internal fun tagOf(topic: String): String = topic.trim().replace(Regex("\\s+"), "_")

    private val GUID_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!#$%&()*+,-./:;<=>?@[]^_`{|}~"

    private fun guid(random: SecureRandom): String = buildString { repeat(10) { append(GUID_CHARS[random.nextInt(GUID_CHARS.length)]) } }

    private fun models(modelId: Long, deckId: Long, now: Long): String {
        val field = { name: String, ord: Int ->
            JSONObject().put("name", name).put("ord", ord).put("sticky", false).put("rtl", false).put("font", "Arial").put("size", 20).put("media", JSONArray())
        }
        val template = JSONObject().put("name", "Card 1").put("ord", 0)
            .put("qfmt", "{{Front}}").put("afmt", "{{FrontSide}}\n\n<hr id=answer>\n\n{{Back}}")
            .put("bqfmt", "").put("bafmt", "").put("did", JSONObject.NULL).put("bfont", "").put("bsize", 0)
        val model = JSONObject()
            .put("id", modelId).put("name", "Basic").put("type", 0).put("mod", now / 1000).put("usn", -1).put("sortf", 0).put("did", deckId)
            .put("tmpls", JSONArray().put(template))
            .put("flds", JSONArray().put(field("Front", 0)).put(field("Back", 1)))
            .put("css", ".card {\n font-family: arial;\n font-size: 20px;\n text-align: center;\n color: black;\n background-color: white;\n}\n")
            .put("latexPre", "\\documentclass[12pt]{article}\n\\special{papersize=3in,5in}\n\\usepackage[utf8]{inputenc}\n\\usepackage{amssymb,amsmath}\n\\pagestyle{empty}\n\\setlength{\\parindent}{0in}\n\\begin{document}\n")
            .put("latexPost", "\\end{document}")
            .put("tags", JSONArray()).put("vers", JSONArray())
            .put("req", JSONArray().put(JSONArray().put(0).put("all").put(JSONArray().put(0))))
        return JSONObject().put(modelId.toString(), model).toString()
    }

    private fun deck(id: Long, name: String, now: Long) = JSONObject()
        .put("id", id).put("name", name).put("mod", now / 1000).put("usn", -1).put("desc", "").put("dyn", 0).put("collapsed", false)
        .put("conf", 1).put("extendNew", 10).put("extendRev", 50)
        .put("lrnToday", JSONArray().put(0).put(0)).put("newToday", JSONArray().put(0).put(0)).put("revToday", JSONArray().put(0).put(0)).put("timeToday", JSONArray().put(0).put(0))

    private fun decks(deckId: Long, name: String, now: Long): String =
        JSONObject().put("1", deck(1, "Default", now)).apply { if (deckId != 1L) put(deckId.toString(), deck(deckId, name, now)) }.toString()

    private fun deckConfigs(now: Long): String = JSONObject().put("1", JSONObject()
        .put("id", 1).put("name", "Default").put("mod", now / 1000).put("usn", -1).put("maxTaken", 60).put("autoplay", true).put("timer", 0).put("replayq", true)
        .put("new", JSONObject().put("bury", true).put("delays", JSONArray().put(1).put(10)).put("initialFactor", 2500).put("ints", JSONArray().put(1).put(4).put(7)).put("order", 1).put("perDay", 20).put("separate", true))
        .put("rev", JSONObject().put("bury", true).put("ease4", 1.3).put("fuzz", 0.05).put("ivlFct", 1).put("maxIvl", 36500).put("minSpace", 1).put("perDay", 200))
        .put("lapse", JSONObject().put("delays", JSONArray().put(10)).put("leechAction", 0).put("leechFails", 8).put("minInt", 1).put("mult", 0)),
    ).toString()

    /** The cards that go in, in the order they are shown: only those with something on both sides. */
    internal fun usable(cards: List<Flashcard>) = cards.filter { it.front.isNotBlank() && it.back.isNotBlank() }

    /** Writes the package. Gives how many cards are in it. The pictures are the [CardImages] files named by the cards. */
    fun write(context: Context, cards: List<Flashcard>, deckName: String, out: OutputStream, now: Long = System.currentTimeMillis()): Int {
        val list = usable(cards)
        val work = File(context.cacheDir, "apkg_out_" + System.nanoTime().toString(36)).apply { mkdirs() }
        try {
            val dbFile = File(work, "collection.anki2")
            val random = SecureRandom()
            val deckId = now
            val modelId = now + 1
            // The pictures are numbered in the zip, as Anki wants them; the "media" file says what each number is.
            val media = JSONObject()
            val mediaFiles = ArrayList<Pair<String, File>>()
            val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
            try {
                SCHEMA.forEach(db::execSQL)
                db.beginTransaction()
                try {
                    db.execSQL(
                        "INSERT INTO col VALUES (1, ?, ?, ?, 11, 0, 0, 0, ?, ?, ?, ?, '{}')",
                        arrayOf<Any>(now / 1000 / 86400 * 86400, now, now, "{\"nextPos\":${list.size + 1},\"estTimes\":true,\"activeDecks\":[1],\"sortType\":\"noteFld\",\"timeLim\":0,\"sortBackwards\":false,\"addToCur\":true,\"curDeck\":1,\"newBury\":true,\"newSpread\":0,\"dueCounts\":true,\"curModel\":\"$modelId\",\"collapseTime\":1200}", models(modelId, deckId, now), decks(deckId, deckName, now), deckConfigs(now)),
                    )
                    list.forEachIndexed { i, c ->
                        val noteId = now + 10 + i
                        var front = html(c.front)
                        if (c.image.isNotBlank()) {
                            val file = CardImages.file(context, c.image)
                            if (file != null) {
                                val number = mediaFiles.size.toString()
                                mediaFiles += number to file
                                media.put(number, c.image)
                                front += "<br><img src=\"${c.image}\">"
                            }
                        }
                        val back = html(c.back)
                        db.execSQL(
                            "INSERT INTO notes VALUES (?, ?, ?, ?, -1, ?, ?, ?, ?, 0, '')",
                            arrayOf<Any>(noteId, guid(random), modelId, now / 1000, " " + tagOf(c.topic) + " ", front + "\u001F" + back, CardIO.fromHtml(front), checksum(front)),
                        )
                        db.execSQL(
                            "INSERT INTO cards VALUES (?, ?, ?, 0, ?, -1, 0, 0, ?, 0, 0, 0, 0, 0, 0, 0, 0, '')",
                            arrayOf<Any>(noteId + 100_000, noteId, deckId, now / 1000, i + 1),
                        )
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            } finally {
                db.close()
            }
            ZipOutputStream(out.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("collection.anki2")); dbFile.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                zip.putNextEntry(ZipEntry("media")); zip.write(media.toString().toByteArray()); zip.closeEntry()
                mediaFiles.forEach { (number, file) -> zip.putNextEntry(ZipEntry(number)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
            }
            return list.size
        } finally {
            work.deleteRecursively()
        }
    }
}
