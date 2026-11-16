package com.apvcode.devx.data

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Everything of the app that is the person's (chats, projects, memory, skills, attachments, settings) in one zip file, and back.
 * What is bound to this phone is left out: the API keys and tokens (they live in the phone's keystore and cannot move), the
 * pairings with computers (they need this phone's key), and the key the app signs APKs with.
 */
object Backup {
    private const val APP = "devx-android"
    private val skipFiles = setOf("remote_computers.json")
    private val prefFiles = setOf("settings.xml", "devx_prefs.xml", "skills.xml", "cards.xml", "rich_state.xml")

    private fun skip(relative: String) = relative in skipFiles || relative.startsWith("apk-signing-")

    /** Writes the zip; gives the number of files in it. */
    fun export(filesDir: File, prefsDir: File, out: OutputStream, version: String, now: Long = System.currentTimeMillis()): Int {
        var count = 0
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(JSONObject().put("app", APP).put("version", version).put("at", now).toString().toByteArray())
            zip.closeEntry()
            filesDir.walkTopDown().filter { it.isFile }.forEach { f ->
                val relative = f.relativeTo(filesDir).invariantSeparatorsPath
                if (skip(relative)) return@forEach
                zip.putNextEntry(ZipEntry("files/$relative"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                count++
            }
            prefsDir.listFiles()?.filter { it.isFile && it.name in prefFiles }?.forEach { f ->
                zip.putNextEntry(ZipEntry("prefs/${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                count++
            }
        }
        return count
    }

    /** Puts the files of a backup back (those of the same name are replaced, the others stay). Gives the number of files, or throws if this is not a backup of Devx. */
    fun restore(filesDir: File, prefsDir: File, input: InputStream): Int {
        var count = 0
        var manifest = false
        val filesRoot = filesDir.canonicalFile
        val prefsRoot = prefsDir.canonicalFile
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                when {
                    entry.name == "manifest.json" -> {
                        val text = zip.readBytes().decodeToString()
                        if (JSONObject(text).optString("app") != APP) throw IllegalArgumentException("This is not a backup of Devx.")
                        manifest = true
                    }
                    entry.name.startsWith("files/") -> count += place(filesRoot, entry.name.removePrefix("files/"), zip, skipCheck = true)
                    entry.name.startsWith("prefs/") && entry.name.removePrefix("prefs/") in prefFiles -> count += place(prefsRoot, entry.name.removePrefix("prefs/"), zip, skipCheck = false)
                }
            }
        }
        if (!manifest) throw IllegalArgumentException("This is not a backup of Devx.")
        return count
    }

    /** One file, kept inside [root] (a name that climbs out of it, with "..", is refused). */
    private fun place(root: File, relative: String, from: InputStream, skipCheck: Boolean): Int {
        if (skipCheck && skip(relative)) return 0
        val target = File(root, relative).canonicalFile
        if (!target.path.startsWith(root.path + File.separator)) return 0
        target.parentFile?.mkdirs()
        target.outputStream().use { from.copyTo(it) }
        return 1
    }
}
