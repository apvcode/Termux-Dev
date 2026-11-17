package com.apvcode.devx.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** API keys, encrypted with a key that never leaves the phone's hardware-backed keystore. */
class Secrets(context: Context) {
    private val prefs = context.getSharedPreferences(PrefFiles.SECRETS, Context.MODE_PRIVATE)

    fun put(id: String, secret: String) {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val packed = cipher.iv + cipher.doFinal(secret.toByteArray())
        prefs.edit().putString(id, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun get(id: String): String? {
        val packed = prefs.getString(id, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed, 0, IV_SIZE))
            String(cipher.doFinal(packed, IV_SIZE, packed.size - IV_SIZE))
        }.logged("storage").getOrNull()
    }

    fun remove(id: String) {
        prefs.edit().remove(id).apply()
    }

    /** Every secret, opened (for a backup that the person protects with a password). */
    fun all(): Map<String, String> = prefs.all.keys.filter { !it.startsWith("autobackup:") }.mapNotNull { id -> get(id)?.let { id to it } }.toMap()

    /** Puts many secrets in at once, written to disk before it returns (the app is closed right after a restore). */
    fun putAll(map: Map<String, String>) {
        map.forEach { (id, value) -> put(id, value) }
        prefs.edit().commit()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ALIAS = "devx_secrets"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}

/** Providers, projects, the chosen model and every chat, as small JSON files in the app's folder. */
class Store(private val context: Context) {
    private val dir = context.filesDir
    private val chatsDir = File(dir, "chats").apply { mkdirs() }

    fun loadProviders(): List<Provider> = readArray("providers.json").mapNotNull { runCatching { providerFromJson(it) }.logged("storage").getOrNull() }

    fun saveProviders(list: List<Provider>) = writeAtomic(File(dir, "providers.json"), JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString())

    fun loadProjects(): List<String> = File(dir, "projects.json").takeIf { it.exists() }?.let { f ->
        runCatching { JSONArray(f.readText()).let { a -> (0 until a.length()).map { a.getString(it) } } }.logged("storage")
            .onFailure { ErrorLog.warn("store", "projects.json could not be read (the names are taken from the details)", it) }.getOrNull()
    } ?: emptyList()

    fun saveProjects(list: List<String>) = writeAtomic(File(dir, "projects.json"), JSONArray(list).toString())

    fun loadModel(): ModelRef? = File(dir, "model.json").takeIf { it.exists() }?.let { f ->
        runCatching { JSONObject(f.readText()).let { ModelRef(it.getString("providerId"), it.getString("model")) } }.logged("storage").getOrNull()
    }

    fun saveModel(ref: ModelRef?) {
        val file = File(dir, "model.json")
        if (ref == null) file.delete()
        else writeAtomic(file, JSONObject().put("providerId", ref.providerId).put("model", ref.model).toString())
    }

    fun loadEfforts(): Map<String, String> = File(dir, "efforts.json").takeIf { it.exists() }?.let { f ->
        runCatching { JSONObject(f.readText()).let { o -> o.keys().asSequence().associateWith { o.getString(it) } } }.logged("storage").getOrNull()
    } ?: emptyMap()

    fun saveEfforts(map: Map<String, String>) = writeAtomic(File(dir, "efforts.json"), JSONObject(map).toString())

    /** How many chat files could not be read the last time the chats were loaded (they are put aside, see [loadChats]). */
    var damagedChats = 0
        private set

    /** A chat file that cannot be read is moved to "chats_damaged" instead of being skipped each time: it is not lost, and it is in backups. */
    fun loadChats(): List<Chat> {
        damagedChats = 0
        return (chatsDir.listFiles() ?: emptyArray())
            .filter { it.extension == "json" }
            .mapNotNull { f ->
                runCatching { chatFromJson(JSONObject(f.readText())) }.getOrElse { e ->
                    ErrorLog.warn("store", "chat file ${f.name} could not be read and was put aside", e)
                    attempt("storage") {
                        val aside = File(dir, "chats_damaged").apply { mkdirs() }
                        if (!f.renameTo(File(aside, f.name))) { f.copyTo(File(aside, f.name), overwrite = true); f.delete() }
                    }
                    damagedChats++
                    null
                }
            }
    }

    /** [json] is built on the main thread (it reads Compose state); only the writing happens elsewhere. */
    fun saveChatJson(id: String, json: String) {
        writeAtomic(File(chatsDir, "$id.json"), json)
        // The "recent chats" widget follows the chats.
        com.apvcode.devx.Widgets.refreshSoon(context)
    }

    fun deleteChat(id: String) {
        File(chatsDir, "$id.json").delete()
    }

    private fun readArray(name: String): List<JSONObject> {
        val file = File(dir, name).takeIf { it.exists() } ?: return emptyList()
        return runCatching { JSONArray(file.readText()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } }.logged("storage")
            .onFailure { ErrorLog.warn("store", "$name could not be read", it) }.getOrDefault(emptyList())
    }

    /** Write to a temp file and rename, so a crash in the middle never leaves a half-written file. */
    private fun writeAtomic(file: File, text: String) = AtomicFile.write(file, text)
}
