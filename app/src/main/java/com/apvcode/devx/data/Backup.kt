package com.apvcode.devx.data

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.security.GeneralSecurityException

/**
 * Everything of the app that is the person's (chats, projects, memory, skills, attachments, settings) in one zip file, and back.
 *
 * The API keys, the tokens and the key the app signs APKs with are not put in the zip as they are: they are the person's secrets.
 * They go in only when a password is given, as one encrypted block (the key made from the password, AES-GCM); the file is useless
 * without it, and on the other phone they are put into that phone's keystore. What cannot move at all is left out: the pairings
 * with computers (they hold a key that never leaves this phone's hardware).
 */
object Backup {
    private const val APP = "devx-android"
    private const val SECRETS = "secrets.enc"
    private val skipFiles = setOf("remote_computers.json")
    private val prefFiles = PrefFiles.backedUp.map(PrefFiles::fileName).toSet()

    /** The files kept out of the plain part (the signing key and its certificate go in the encrypted block). */
    private fun isSigning(relative: String) = relative.startsWith("apk-signing-")
    private fun skip(relative: String) = relative in skipFiles || isSigning(relative)

    /** What a smaller copy leaves out: the photos, videos and files that were attached, and the chats' working folders. */
    val LEAN = setOf("attachments", "workspace")

    /** The password was not the one the backup was made with. */
    class WrongPassword : Exception("Wrong password.")

    /** What the encrypted block holds: the secrets by id (the keystore ones) and some files by their path. */
    class Vault(val secrets: Map<String, String>, val files: Map<String, ByteArray> = emptyMap())

    /**
     * Writes the zip; gives the number of files in it. With a [password] and a [vault] the secrets are in it too, encrypted; the
     * signing key is read from [filesDir] into the vault.
     */
    fun export(
        filesDir: File, prefsDir: File, out: OutputStream, version: String, now: Long = System.currentTimeMillis(),
        secrets: Map<String, String> = emptyMap(), password: CharArray? = null,
        /** Folders of the app's files that are left out (for a smaller copy), by their first name: see [LEAN]. */
        leaveOut: Set<String> = emptySet(),
    ): Int {
        var count = 0
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(JSONObject().put("app", APP).put("version", version).put("at", now).put("secrets", password != null).toString().toByteArray())
            zip.closeEntry()
            filesDir.walkTopDown().filter { it.isFile }.forEach { f ->
                val relative = f.relativeTo(filesDir).invariantSeparatorsPath
                if (skip(relative) || relative.substringBefore('/') in leaveOut) return@forEach
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
            if (password != null) {
                val signing = filesDir.listFiles()?.filter { it.isFile && isSigning(it.name) }.orEmpty().associate { it.name to it.readBytes() }
                zip.putNextEntry(ZipEntry(SECRETS))
                zip.write(seal(Vault(secrets, signing), password))
                zip.closeEntry()
                count++
            }
        }
        return count
    }

    /** True when the zip has the encrypted block, so the person is asked for the password. Throws if this is not a backup of Devx. */
    fun hasSecrets(input: InputStream): Boolean {
        var manifest = false
        var secrets = false
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "manifest.json") manifest = isOurs(zip.readBytes())
                if (entry.name == SECRETS) secrets = true
            }
        }
        if (!manifest) throw IllegalArgumentException("This is not a backup of Devx.")
        return secrets
    }

    /** What a backup holds, read without changing anything: shown before the person agrees to restore it. */
    class Preview(
        val version: String,
        val at: Long,
        val hasSecrets: Boolean,
        val chats: Int,
        val projects: Int,
        /** Notes of the memory (the person's own and those of notebooks and projects) and their overviews: the files that hold them. */
        val memoryFiles: Int,
        val attachments: Int,
        val settings: Int,
        val files: Int,
        /** How many of the files are on this phone already: they are replaced; the rest are added, and nothing else is removed. */
        val replaced: Int,
    )

    /** Reads the list of the zip (and its manifest) and says what is in it; [filesDir] is looked at to tell what would be replaced. Throws if this is not a backup of Devx. */
    fun inspect(filesDir: File, prefsDir: File, input: InputStream): Preview {
        var manifest: JSONObject? = null
        var secrets = false
        var chats = 0; var memory = 0; var attachments = 0; var settings = 0; var files = 0; var replaced = 0
        var projectsJson: ByteArray? = null
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name
                when {
                    name == "manifest.json" -> manifest = runCatching { JSONObject(zip.readBytes().decodeToString()) }.getOrNull()
                    name == SECRETS -> secrets = true
                    name.startsWith("files/") -> {
                        val relative = name.removePrefix("files/")
                        if (skip(relative)) continue
                        files++
                        if (File(filesDir, relative).exists()) replaced++
                        val first = relative.substringBefore('/')
                        when {
                            first == "chats" && relative.endsWith(".json") -> chats++
                            first == "attachments" -> attachments++
                            relative == "projects.json" -> projectsJson = zip.readBytes()
                            relative.startsWith("memory") || relative.startsWith("project_") -> memory++
                        }
                    }
                    name.startsWith("prefs/") && name.removePrefix("prefs/") in prefFiles -> {
                        settings++
                        if (File(prefsDir, name.removePrefix("prefs/")).exists()) replaced++
                    }
                }
            }
        }
        val m = manifest
        if (m == null || m.optString("app") != APP) throw IllegalArgumentException("This is not a backup of Devx.")
        val projects = projectsJson?.let { b -> runCatching { org.json.JSONArray(b.decodeToString()).length() }.getOrNull() } ?: 0
        return Preview(m.optString("version"), m.optLong("at"), secrets, chats, projects, memory, attachments, settings, files + settings, replaced)
    }

    private fun isOurs(bytes: ByteArray) = runCatching { JSONObject(bytes.decodeToString()).optString("app") == APP }.getOrDefault(false)

    /**
     * Puts the files of a backup back (those of the same name are replaced, the others stay). The whole zip is read and checked
     * first, so a file that is cut short or damaged changes nothing. Gives the number of files; throws if this is not a backup of
     * Devx, or [WrongPassword] when the backup has secrets and a [password] is given that does not open them. Without a password the
     * secrets are left alone. The ones that were opened are given to [onSecrets] (they belong in the keystore, not in a file).
     */
    fun restore(filesDir: File, prefsDir: File, input: InputStream, password: CharArray? = null, onSecrets: (Vault) -> Unit = {}): Int {
        // Unpacked to a folder first (attachments can be big, so not into memory): nothing of the app is touched until all is read.
        val stage = File(filesDir.canonicalFile.parentFile, "restore_stage").apply { deleteRecursively(); mkdirs() }
        try {
            var manifest = false
            var vault: ByteArray? = null
            var staged = 0
            val stageFiles = File(stage, "files").canonicalFile
            val stagePrefs = File(stage, "prefs").canonicalFile
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    when {
                        entry.name == "manifest.json" -> {
                            if (!isOurs(zip.readBytes())) throw IllegalArgumentException("This is not a backup of Devx.")
                            manifest = true
                        }
                        entry.name == SECRETS -> vault = zip.readBytes()
                        entry.name.startsWith("files/") -> {
                            val relative = entry.name.removePrefix("files/")
                            if (!skip(relative)) staged += unpack(stageFiles, relative, zip)
                        }
                        entry.name.startsWith("prefs/") && entry.name.removePrefix("prefs/") in prefFiles -> staged += unpack(stagePrefs, entry.name.removePrefix("prefs/"), zip)
                    }
                }
            }
            if (!manifest) throw IllegalArgumentException("This is not a backup of Devx.")
            // Opened before anything is put back: a wrong password must not leave half of a restore behind.
            val opened = if (vault != null && password != null) open(vault!!, password) else null

            var count = 0
            count += promote(stageFiles, filesDir.canonicalFile)
            count += promote(stagePrefs, prefsDir.apply { mkdirs() }.canonicalFile)
            if (opened != null) {
                val root = filesDir.canonicalFile
                opened.files.forEach { (relative, bytes) ->
                    val target = File(root, relative).canonicalFile
                    if (isSigning(relative) && target.parentFile == root) { target.writeBytes(bytes); count++ }
                }
                onSecrets(opened)
                count++
            }
            return count
        } finally {
            stage.deleteRecursively()
        }
    }

    /** One file of the zip into the staging folder, kept inside [root] (a name that climbs out of it, with "..", is refused). */
    private fun unpack(root: File, relative: String, from: InputStream): Int {
        val target = File(root, relative).canonicalFile
        if (!target.path.startsWith(root.path + File.separator)) return 0
        target.parentFile?.mkdirs()
        target.outputStream().use { from.copyTo(it) }
        return 1
    }

    /** Moves what was unpacked into its place; a file of the same name is replaced. */
    private fun promote(from: File, to: File): Int {
        var count = 0
        if (!from.exists()) return 0
        from.walkTopDown().filter { it.isFile }.forEach { f ->
            val target = File(to, f.relativeTo(from).path)
            target.parentFile?.mkdirs()
            if (!f.renameTo(target)) { f.copyTo(target, overwrite = true); f.delete() }
            count++
        }
        return count
    }

    // ---------- the encrypted block: salt (16) + iv (12) + AES-GCM of the json ----------

    private const val SALT = 16
    private const val IV = 12
    private const val ROUNDS = 120_000

    private fun keyOf(password: CharArray, salt: ByteArray): SecretKeySpec {
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password, salt, ROUNDS, 256)).encoded
        return SecretKeySpec(raw, "AES")
    }

    internal fun seal(vault: Vault, password: CharArray): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT).also(random::nextBytes)
        val iv = ByteArray(IV).also(random::nextBytes)
        val json = JSONObject()
            .put("secrets", JSONObject().also { o -> vault.secrets.forEach { (k, v) -> o.put(k, v) } })
            .put("files", JSONObject().also { o -> vault.files.forEach { (k, v) -> o.put(k, Base64.getEncoder().encodeToString(v)) } })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keyOf(password, salt), GCMParameterSpec(128, iv)) }
        return salt + iv + cipher.doFinal(json.toString().toByteArray())
    }

    internal fun open(block: ByteArray, password: CharArray): Vault {
        if (block.size < SALT + IV + 16) throw IllegalArgumentException("This is not a backup of Devx.")
        val salt = block.copyOfRange(0, SALT)
        val iv = block.copyOfRange(SALT, SALT + IV)
        val plain = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, keyOf(password, salt), GCMParameterSpec(128, iv)) }
                .doFinal(block, SALT + IV, block.size - SALT - IV)
        } catch (e: GeneralSecurityException) {
            throw WrongPassword()
        }
        val o = JSONObject(plain.decodeToString())
        val secrets = o.optJSONObject("secrets")?.let { s -> s.keys().asSequence().associateWith { s.getString(it) } }.orEmpty()
        val files = o.optJSONObject("files")?.let { f -> f.keys().asSequence().associateWith { Base64.getDecoder().decode(f.getString(it)) } }.orEmpty()
        return Vault(secrets, files)
    }
}
