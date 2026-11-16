package com.apvcode.devx.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A copy of everything (see [Backup]) written by itself, once a week, into a folder the person chose (internal storage, an SD card, a
 * cloud folder that shows up as one). It is checked when the app opens and by the alarm that already wakes the app twice a day; the
 * last few copies stay, the older ones are removed. The folder is where the phone allows: no permission beyond the one the person gave.
 */
object AutoBackup {
    private const val PREFS = "autobackup"
    const val WEEK = 7L * 24 * 60 * 60 * 1000
    const val KEEP = 4
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun folder(context: Context): Uri? = prefs(context).getString("folder", null)?.let(Uri::parse)
    fun lastAt(context: Context): Long = prefs(context).getLong("at", 0L)
    fun enabled(context: Context): Boolean = folder(context) != null

    /** The person chose a folder: it is kept (the permission too, so that it works while the app is closed). */
    fun setFolder(context: Context, tree: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        prefs(context).edit().putString("folder", tree.toString()).putLong("at", 0L).apply()
    }

    fun disable(context: Context) {
        folder(context)?.let { runCatching { context.contentResolver.releasePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        prefs(context).edit().remove("folder").apply()
    }

    /** True when a copy is due: nothing yet, or the last one is a week old. */
    fun isDue(last: Long, now: Long, interval: Long = WEEK) = last == 0L || now - last >= interval

    fun backupName(at: Long): String = "devx-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(at)) + ".zip"

    /** Of the copies in the folder (name, time), the ones to remove so that [keep] of the newest are left. Only files that are ours are touched. */
    fun toRemove(files: List<Pair<String, Long>>, keep: Int = KEEP): List<String> =
        files.filter { it.first.startsWith("devx-backup-") && it.first.endsWith(".zip") }.sortedByDescending { it.second }.drop(keep).map { it.first }

    /** The folder's name as a person reads it ("Backups" for primary:Backups). */
    fun label(tree: Uri): String = runCatching { DocumentsContract.getTreeDocumentId(tree).substringAfter(':').ifBlank { "/" } }.getOrDefault(tree.lastPathSegment.orEmpty())

    /** Makes the copy now if one is due; blocking (call it off the main thread). */
    fun runIfDue(context: Context, now: Long = System.currentTimeMillis()) {
        if (!enabled(context) || !isDue(lastAt(context), now)) return
        backupNow(context, now)
    }

    /** Writes a copy into the folder; true when it worked. */
    fun backupNow(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val app = context.applicationContext
        val tree = folder(app) ?: return false
        if (!running.compareAndSet(false, true)) return false
        try {
            val resolver = app.contentResolver
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val name = backupName(now)
            // Two copies in one day: the second replaces the first.
            findChild(app, tree, name)?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            val doc = DocumentsContract.createDocument(resolver, parent, "application/zip", name) ?: return false
            val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()
            val ok = runCatching {
                resolver.openOutputStream(doc)!!.use { out -> Backup.export(app.filesDir, java.io.File(app.applicationInfo.dataDir, "shared_prefs"), out, version) }
            }.isSuccess
            if (!ok) { runCatching { DocumentsContract.deleteDocument(resolver, doc) }; return false }
            prefs(app).edit().putLong("at", now).apply()
            prune(app, tree)
            return true
        } catch (e: Exception) {
            return false
        } finally {
            running.set(false)
        }
    }

    private fun children(context: Context, tree: Uri): List<Triple<String, String, Long>> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val out = ArrayList<Triple<String, String, Long>>()
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            while (c.moveToNext()) out += Triple(c.getString(0), c.getString(1).orEmpty(), c.getLong(2))
        }
        return out
    }

    private fun findChild(context: Context, tree: Uri, name: String): Uri? =
        runCatching { children(context, tree).firstOrNull { it.second == name }?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it.first) } }.getOrNull()

    private fun prune(context: Context, tree: Uri) {
        runCatching {
            val all = children(context, tree)
            val gone = toRemove(all.map { it.second to it.third }).toSet()
            all.filter { it.second in gone }.forEach { runCatching { DocumentsContract.deleteDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, it.first)) } }
        }
    }
}
