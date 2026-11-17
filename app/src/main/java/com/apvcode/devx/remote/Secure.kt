package com.apvcode.devx.remote

import com.apvcode.devx.data.attempt
import java.util.Base64
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.util.Timer
import java.util.TimerTask
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import android.util.Log

private const val PKT_HELLO = 0x01
private const val PKT_HELLO2 = 0x02
private const val PKT_DATA = 0x03
private const val PKT_DENY = 0x04
private const val PING_MS = 15_000L
private const val IDLE_MS = 45_000L

/** A line for the log of the link (adb logcat -s DevxRemote). Never contains what is said, only what happened. */
object RLog {
    @Volatile var out: (String) -> Unit = { runCatching { Log.d("DevxRemote", it) } }
    fun d(line: String) = out(line)
}

class RemoteException(message: String, val denied: Boolean = false) : Exception(message)

class PairRequest(val token: ByteArray, val name: String)

/** The encrypted channel to the computer, once the handshake is done. Messages are JSON objects. */
class SecureChannel internal constructor(
    val link: Link,
    private val sealer: Sealer,
    private val opener: Opener,
    val ready: JSONObject,
    val sas: String,
) {
    @Volatile var onMessage: (JSONObject) -> Unit = {}
    @Volatile var onClose: () -> Unit = {}
    @Volatile private var dead = false
    @Volatile private var lastHeard = System.currentTimeMillis()
    private val timer = Timer("devx-remote-keepalive", true)

    internal fun start() {
        link.onMessage = { data ->
            if (data.size >= 2 && data[0].toInt() == PKT_DATA) {
                opener.open(data.copyOfRange(1, data.size))?.let { plain ->
                    lastHeard = System.currentTimeMillis()
                    val msg = runCatching { JSONObject(String(plain)) }.getOrNull()
                    when (msg?.optString("k")) {
                        null -> Unit
                        "ping" -> send(JSONObject().put("k", "pong").put("t", msg.opt("t")))
                        "pong" -> Unit
                        else -> onMessage(msg)
                    }
                }
            }
        }
        link.onClose = { end() }
        timer.schedule(object : TimerTask() {
            override fun run() {
                if (dead) return
                if (System.currentTimeMillis() - lastHeard > IDLE_MS) { end(why = "silent for 45 s"); return }
                send(JSONObject().put("k", "ping").put("t", System.currentTimeMillis()))
            }
        }, PING_MS, PING_MS)
    }

    fun send(msg: JSONObject) {
        if (dead) return
        link.send(byteArrayOf(PKT_DATA.toByte()) + sealer.seal(msg.toString().toByteArray()))
    }

    val isOpen: Boolean get() = !dead

    fun close() = end(silent = true)

    private fun end(silent: Boolean = false, why: String = "link closed") {
        if (dead) return
        RLog.d("channel ended: $why (silent=$silent)")
        dead = true
        timer.cancel()
        attempt("secure") { link.close() }
        if (!silent) onClose()
    }
}

private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
private fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)

/**
 * Runs the handshake over [link] and returns the open channel, or throws [RemoteException].
 * The computer must prove it holds [hostPub] (pinned at pairing) before anything is sent that matters.
 */
suspend fun connectSecure(
    link: Link,
    hostPub: ByteArray,
    signer: Signer,
    did: String,
    pair: PairRequest? = null,
    onSas: (String) -> Unit = {},
    timeoutMs: Long = 12_000,
    /** Pairing waits for a person at the computer: once the computer has answered, it may take this long. */
    approvalMs: Long = 150_000,
): SecureChannel = suspendCancellableCoroutine { cont ->
    val eph = RemoteCrypto.newEphemeral()
    val nC = RemoteCrypto.randomBytes(16)
    val helloBody = JSONObject().put("k", "hello").put("v", 1).put("did", did).put("e", b64(eph.publicKey)).put("n", b64(nC)).toString().toByteArray()
    var opener: Opener? = null
    var sealer: Sealer? = null
    var sas = ""
    var state = 0 // 0 waiting for hello2, 1 waiting for ready, 2 done
    var timer = Timer("devx-remote-handshake", true)

    fun fail(e: Exception) {
        if (state == 2) return
        state = 2
        timer.cancel()
        attempt("secure") { link.close() }
        if (cont.isActive) cont.resumeWithException(e)
    }
    timer.schedule(object : TimerTask() { override fun run() { fail(RemoteException("no answer")) } }, timeoutMs)
    cont.invokeOnCancellation { if (state != 2) { state = 2; timer.cancel(); runCatching { link.close() } } }
    fun waitForPerson() {
        timer.cancel()
        timer = Timer("devx-remote-handshake", true)
        timer.schedule(object : TimerTask() { override fun run() { fail(RemoteException("nobody answered at the computer")) } }, approvalMs)
    }

    link.onClose = { fail(RemoteException("closed")) }
    link.onMessage = handler@{ data ->
        if (state == 2 || data.size < 2) return@handler
        val type = data[0].toInt()
        val body = data.copyOfRange(1, data.size)
        try {
            if (type == PKT_DENY) return@handler fail(RemoteException("denied: " + JSONObject(String(body)).optString("why"), denied = true))
            if (state == 0 && type == PKT_HELLO2) {
                val h2 = JSONObject(String(body))
                val eS = unb64(h2.getString("e"))
                val nS = unb64(h2.getString("n"))
                if (!RemoteCrypto.verify(hostPub, RemoteCrypto.HOST_LABEL + helloBody + eS + nS, unb64(h2.getString("sig")))) {
                    return@handler fail(RemoteException("the computer is not the one that was paired", denied = true))
                }
                val salt = RemoteCrypto.sha256(helloBody, eS, nS)
                val keys = RemoteCrypto.deriveKeys(eph.agree(eS), salt)
                sas = keys.sas
                opener = Opener(keys.s2c)
                sealer = Sealer(keys.c2s)
                val auth = JSONObject().put("k", "auth").put("sig", b64(signer.sign(RemoteCrypto.CLIENT_LABEL + salt)))
                if (pair != null) auth.put("pair", JSONObject().put("tok", b64(pair.token)).put("spk", b64(signer.publicKey)).put("name", pair.name))
                link.send(byteArrayOf(PKT_DATA.toByte()) + sealer!!.seal(auth.toString().toByteArray()))
                state = 1
                if (pair != null) { waitForPerson(); onSas(sas) }
                return@handler
            }
            if (state == 1 && type == PKT_DATA) {
                val plain = opener?.open(body) ?: return@handler
                val msg = JSONObject(String(plain))
                when (msg.optString("k")) {
                    "denied" -> fail(RemoteException("denied: " + msg.optString("why"), denied = true))
                    "ready" -> {
                        state = 2
                        timer.cancel()
                        val channel = SecureChannel(link, sealer!!, opener!!, msg, sas)
                        channel.start()
                        if (cont.isActive) cont.resume(channel)
                    }
                    else -> fail(RemoteException("unexpected reply"))
                }
            }
        } catch (e: Exception) {
            fail(RemoteException(e.message ?: "handshake failed"))
        }
    }
    link.send(byteArrayOf(PKT_HELLO.toByte()) + helloBody)
}
