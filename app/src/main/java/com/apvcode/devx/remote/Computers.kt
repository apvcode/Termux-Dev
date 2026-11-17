package com.apvcode.devx.remote

import com.apvcode.devx.data.logged
import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import com.apvcode.devx.data.AtomicFile

/** A computer that is paired with this phone. The public key is pinned: only the computer that holds it is trusted. */
data class Computer(
    val hostId: String,
    val name: String,
    val hostPub: ByteArray,
    /** This phone's id as the computer knows it. */
    val did: String,
    /** `ip:port` where the computer can be reached directly (local network, VPN). */
    val addresses: List<String>,
    /** Relay brokers by id. */
    val brokers: List<Int>,
    val addedAt: Long,
    val lastSeen: Long = 0,
    val lastVia: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("hostId", hostId).put("name", name).put("hostPub", Base64.getEncoder().encodeToString(hostPub)).put("did", did)
        .put("addresses", JSONArray(addresses)).put("brokers", JSONArray(brokers))
        .put("addedAt", addedAt).put("lastSeen", lastSeen).put("lastVia", lastVia ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): Computer = Computer(
            hostId = o.getString("hostId"), name = o.getString("name"),
            hostPub = Base64.getDecoder().decode(o.getString("hostPub")), did = o.getString("did"),
            addresses = o.optJSONArray("addresses").strings(), brokers = o.optJSONArray("brokers").ints(),
            addedAt = o.optLong("addedAt"), lastSeen = o.optLong("lastSeen"), lastVia = o.optString("lastVia").takeIf { it.isNotEmpty() && it != "null" },
        )
    }
}

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { getString(it) }
private fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else List(length()) { getInt(it) }

/** What the QR code on the computer says (`devx remote pair`). */
class Pairing(val hostPub: ByteArray, val token: ByteArray, val name: String, val addresses: List<String>, val brokers: List<Int>) {
    val hostId: String get() = hostIdOf(hostPub)

    companion object {
        /** Reads the text of the code; null when it is not one of ours. */
        fun parse(text: String): Pairing? = try {
            val t = text.trim()
            if (!t.startsWith("devx1:")) null
            else {
                val o = JSONObject(String(Base64.getUrlDecoder().decode(t.removePrefix("devx1:"))))
                if (o.getInt("v") != 1) null
                else Pairing(
                    hostPub = Base64.getUrlDecoder().decode(o.getString("hp")), token = Base64.getUrlDecoder().decode(o.getString("t")),
                    name = o.optString("n", "computer"), addresses = o.optJSONArray("a").strings(), brokers = o.optJSONArray("r").ints(),
                ).takeIf { it.hostPub.size == 65 && it.token.size == 16 }
            }
        } catch (_: Exception) {
            null
        }
    }
}

fun hostIdOf(hostPub: ByteArray): String = RemoteCrypto.sha256(hostPub).toHex().substring(0, 24)

/** The paired computers, in one small file. Nothing secret is in it: the phone's key lives in the Keystore. */
class ComputerStore(context: Context, name: String = "remote_computers.json") {
    private val file = File(context.filesDir, name)
    val items = mutableStateListOf<Computer>()

    fun load() {
        val list = runCatching {
            val a = JSONArray(file.takeIf { it.exists() }?.readText() ?: "[]")
            List(a.length()) { Computer.fromJson(a.getJSONObject(it)) }
        }.logged("computers").getOrDefault(emptyList())
        items.clear()
        items.addAll(list)
    }

    @Synchronized
    fun put(c: Computer) {
        val i = items.indexOfFirst { it.hostId == c.hostId }
        if (i >= 0) items[i] = c else items += c
        save()
    }

    @Synchronized
    fun remove(hostId: String) {
        items.removeAll { it.hostId == hostId }
        save()
    }

    fun find(hostId: String): Computer? = items.firstOrNull { it.hostId == hostId }

    private fun save() {
        val json = JSONArray().also { a -> items.forEach { a.put(it.toJson()) } }.toString()
        AtomicFile.writeQuietly("computers", file, json)
    }
}
