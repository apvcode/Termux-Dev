package com.apvcode.devx.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.Intent
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A copy of everything (see [Backup]) written by itself, once a week, into a folder the person chose (internal storage, an SD card, a
 * cloud folder that shows up as one). It is checked when the app opens and by the alarm that already wakes the app twice a day; the
 * last few copies stay, the older ones are removed. The folder is where the phone allows: no permission beyond the one the person gave.
 */
object AutoBackup {
    private const val PREFS = PrefFiles.AUTO_BACKUP
    const val WEEK = 7L * 24 * 60 * 60 * 1000
    const val KEEP = 4
    private val running = AtomicBoolean(false)

    /** Where the password of the weekly copy is kept (in the keystore, on this phone): it lets the copy carry the keys, locked. */
    const val PASSWORD_ID = "autobackup:password"

    fun keysEnabled(context: Context): Boolean = !Secrets(context.applicationContext).get(PASSWORD_ID).isNullOrEmpty()

    /** A smaller weekly copy: without the attached files (see [Backup.LEAN]). */
    fun lean(context: Context): Boolean = prefs(context).getBoolean("lean", false)

    fun setLean(context: Context, on: Boolean) { prefs(context).edit().putBoolean("lean", on).apply() }

    /** A password puts the keys and tokens into every weekly copy (locked with it); null leaves them out. */
    fun setPassword(context: Context, password: String?) {
        val secrets = Secrets(context.applicationContext)
        if (password.isNullOrEmpty()) secrets.remove(PASSWORD_ID) else secrets.put(PASSWORD_ID, password)
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun folder(context: Context): Uri? = prefs(context).getString("folder", null)?.let(Uri::parse)
    fun lastAt(context: Context): Long = prefs(context).getLong("at", 0L)
    fun enabled(context: Context): Boolean = folder(context) != null || webdavUrl(context) != null

    // ---------- a WebDAV folder as a second place for the copy ----------

    private const val WEBDAV_PASSWORD_ID = "webdav:password"

    /** The address of the WebDAV folder (ends with "/"), or null when none is set. */
    fun webdavUrl(context: Context): String? = prefs(context).getString("webdav", null)?.takeIf { it.isNotBlank() }
    fun webdavUser(context: Context): String = prefs(context).getString("webdavUser", "").orEmpty()
    fun webdavPassword(context: Context): String = Secrets(context.applicationContext).get(WEBDAV_PASSWORD_ID).orEmpty()

    /** Keeps the address and the login (the password goes in the keystore). The first copy is due at once. */
    fun setWebdav(context: Context, url: String, user: String, password: String) {
        prefs(context).edit().putString("webdav", url).putString("webdavUser", user).putLong("at", 0L).apply()
        Secrets(context.applicationContext).put(WEBDAV_PASSWORD_ID, password)
    }

    fun clearWebdav(context: Context) {
        prefs(context).edit().remove("webdav").remove("webdavUser").apply()
        Secrets(context.applicationContext).remove(WEBDAV_PASSWORD_ID)
    }

    /** The person chose a folder: it is kept (the permission too, so that it works while the app is closed). */
    fun setFolder(context: Context, tree: Uri) {
        attempt("autobackup") { context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        prefs(context).edit().putString("folder", tree.toString()).putLong("at", 0L).apply()
    }

    fun disable(context: Context) {
        folder(context)?.let { runCatching { context.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        prefs(context).edit().remove("folder").apply()
    }

    /** True when a copy is due: nothing yet, or the last one is a week old. */
    fun isDue(last: Long, now: Long, interval: Long = WEEK) = last == 0L || now - last >= interval

    fun backupName(at: Long): String = "devx-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(at)) + ".zip"

    /** Of the copies in the folder (name, time), the ones to remove so that [keep] of the newest are left. Only files that are ours are touched. */
    fun toRemove(files: List<Pair<String, Long>>, keep: Int = KEEP): List<String> =
        files.filter { it.first.startsWith("devx-backup-") && it.first.endsWith(".zip") }.sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenByDescending { it.first }).drop(keep).map { it.first }

    /** The folder's name as a person reads it ("Backups" for primary:Backups). */
    fun label(tree: Uri): String = runCatching { DocumentsContract.getTreeDocumentId(tree).substringAfter(':').ifBlank { "/" } }.getOrDefault(tree.lastPathSegment.orEmpty())

    /** Makes the copy now if one is due; blocking (call it off the main thread). */
    fun runIfDue(context: Context, now: Long = System.currentTimeMillis()) {
        if (!enabled(context) || !isDue(lastAt(context), now)) return
        backupNow(context, now)
    }

    /** Writes a copy into the folder and to the WebDAV folder, whichever are set; true when it worked everywhere. */
    fun backupNow(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val app = context.applicationContext
        val tree = folder(app)
        val dav = webdavUrl(app)?.let { WebDav.folderUrl(it) }
        if (tree == null && dav == null) return false
        if (!running.compareAndSet(false, true)) return false
        // Made once, into a file of the cache, and then given to each place.
        val temp = File(app.cacheDir, "autobackup.zip")
        try {
            val name = backupName(now)
            val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()
            val made = runCatching {
                val secrets = Secrets(app)
                val password = secrets.get(PASSWORD_ID)?.takeIf { it.isNotEmpty() }
                val keys = if (password != null) secrets.all() else emptyMap()
                temp.outputStream().use { out ->
                    Backup.export(app.filesDir, File(app.applicationInfo.dataDir, "shared_prefs"), out, version, secrets = keys, password = password?.toCharArray(), leaveOut = if (lean(app)) Backup.LEAN else emptySet())
                }
            }.logged("autobackup").isSuccess
            if (!made) return false
            var ok = true
            if (tree != null) ok = copyToFolder(app, tree, name, temp) && ok
            if (dav != null) ok = (WebDav.send(dav, webdavUser(app), webdavPassword(app), name, temp) == WebDav.Status.Ok) && ok
            if (ok) prefs(app).edit().putLong("at", now).apply()
            return ok
        } catch (e: Exception) {
            ErrorLog.warn("autobackup", "the weekly copy failed", e)
            return false
        } finally {
            temp.delete()
            running.set(false)
        }
    }

    private fun copyToFolder(app: Context, tree: Uri, name: String, source: File): Boolean {
        val resolver = app.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        // Two copies in one day: the second replaces the first.
        findChild(app, tree, name)?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) }.logged("autobackup") }
        val doc = DocumentsContract.createDocument(resolver, parent, "application/zip", name) ?: return false
        val ok = runCatching { resolver.openOutputStream(doc)!!.use { out -> source.inputStream().use { it.copyTo(out) } } }.logged("autobackup").isSuccess
        if (!ok) { runCatching { DocumentsContract.deleteDocument(resolver, doc) }.logged("autobackup"); return false }
        prune(app, tree)
        return true
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
        runCatching { children(context, tree).firstOrNull { it.second == name }?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it.first) } }.logged("autobackup").getOrNull()

    private fun prune(context: Context, tree: Uri) {
        attempt("autobackup") {
            val all = children(context, tree)
            val gone = toRemove(all.map { it.second to it.third }).toSet()
            all.filter { it.second in gone }.forEach { runCatching { DocumentsContract.deleteDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, it.first)) }.logged("autobackup") }
        }
    }
}
