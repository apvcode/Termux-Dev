package com.apvcode.devx.remote

import com.apvcode.devx.data.attempt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

enum class LinkState { Idle, Connecting, Online, Offline }

class SessionEvent(val session: String, val seq: Int, val event: JSONObject)

/**
 * Where to try to reach a computer: its addresses on the network, and the relay. Tried together; the first that
 * gets through the handshake wins. The direct way gets a short head start, so that on the same Wi-Fi it is chosen.
 */
suspend fun raceConnect(
    hostPub: ByteArray,
    signer: Signer,
    did: String,
    addresses: List<String>,
    brokers: List<Int>,
    pair: PairRequest? = null,
    onSas: (String) -> Unit = {},
    relayHeadStartMs: Long = 350,
    directTimeoutMs: Int = 4000,
): SecureChannel = withContext(Dispatchers.IO) {
    // Pairing shows six digits that belong to ONE handshake, so the ways are tried one after another there.
    if (pair != null) return@withContext pairInOrder(hostPub, signer, did, addresses, brokers, pair, onSas, directTimeoutMs)
    val hostId = hostIdOf(hostPub)
    val result = CompletableDeferred<SecureChannel>()
    val failures = AtomicInteger()
    val errors = ConcurrentHashMap<String, String>()
    val total = addresses.size + brokers.mapNotNull { RelayLink.BROKERS[it] }.size
    if (total == 0) throw RemoteException("no way to reach the computer")

    suspend fun attempt(label: String, delayMs: Long, open: () -> Link) {
        try {
            if (delayMs > 0) delay(delayMs)
            if (result.isCompleted) return
            val link = open()
            if (result.isCompleted) { link.close(); return }
            val channel = connectSecure(link, hostPub, signer, did, pair, onSas)
            if (!result.complete(channel)) channel.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors[label] = e.message ?: e.javaClass.simpleName
            // A refusal is final (wrong computer, removed phone); anything else may work through another way.
            if (e is RemoteException && e.denied) result.completeExceptionally(e)
            else if (failures.incrementAndGet() >= total) {
                result.completeExceptionally(RemoteException(if (addresses.isEmpty()) "the computer does not answer" else errors.values.firstOrNull() ?: "no connection"))
            }
        }
    }

    val jobs = ArrayList<Job>()
    addresses.forEach { a -> jobs += launch(Dispatchers.IO) { attempt("direct $a", 0) { WsLink.open(a, directTimeoutMs) } } }
    brokers.mapNotNull { RelayLink.BROKERS[it] }.forEach { url ->
        jobs += launch(Dispatchers.IO) { attempt("relay $url", if (addresses.isEmpty()) 0 else relayHeadStartMs) { RelayLink.open(url, hostId) } }
    }
    try {
        result.await()
    } finally {
        jobs.forEach { it.cancel() }
    }
}

private suspend fun pairInOrder(hostPub: ByteArray, signer: Signer, did: String, addresses: List<String>, brokers: List<Int>, pair: PairRequest, onSas: (String) -> Unit, directTimeoutMs: Int): SecureChannel {
    val hostId = hostIdOf(hostPub)
    var last: Exception = RemoteException("no way to reach the computer")
    val ways = addresses.map { a -> { WsLink.open(a, directTimeoutMs) as Link } } + brokers.mapNotNull { RelayLink.BROKERS[it] }.map { url -> { RelayLink.open(url, hostId) as Link } }
    for (way in ways) {
        try {
            val link = way()
            return connectSecure(link, hostPub, signer, did, pair, onSas, timeoutMs = 7000)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is RemoteException && e.denied) throw e
            last = e
        }
    }
    throw last
}

/** How many times a connection is tried (and tried again after it dropped) before the phone gives up until it is asked. */
const val MAX_TRIES = 2

/** The connection to one computer: finds a way, keeps it alive, and comes back by itself when it drops. */
class RemoteConnection(
    var computer: Computer,
    private val scope: CoroutineScope,
    private val signer: Signer,
    private val onComputerChanged: (Computer) -> Unit,
) {
    var state by mutableStateOf(LinkState.Idle)
        private set
    var via by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Counts the tries since the last success: the screens show "trying again". */
    var attempts by mutableIntStateOf(0)
        private set
    /** Both tries failed: the phone stops trying by itself until the person asks again (or comes back to the app). */
    var gaveUp by mutableStateOf(false)
        private set
    /** The computer says it is not known any more (the phone was removed there). */
    var revoked by mutableStateOf(false)
        private set
    var host: JSONObject? = null
        private set
    /** How many chats on the computer want a look (unread answer, a question, an error): the number on the drawer button. */
    var attention by mutableIntStateOf(0)

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 1024)
    val events: SharedFlow<SessionEvent> = _events
    private val _global = MutableSharedFlow<JSONObject>(extraBufferCapacity = 64)
    val global: SharedFlow<JSONObject> = _global
    private val _online = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    /** Fires every time the connection (re)opens: sessions that are on screen ask for their snapshot again. */
    val onlineAgain: SharedFlow<Unit> = _online

    /** The phone's answer to a question of the agent (see `phone_memory`). */
    var onRpc: suspend (method: String, params: JSONObject) -> Any? = { _, _ -> null }
    /** What the computer should know about the person at this phone. */
    var profile: () -> JSONObject = { JSONObject() }

    private var channel: SecureChannel? = null
    private var loop: Job? = null
    private val nextId = AtomicInteger()
    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()

    fun start(initial: SecureChannel? = null) {
        if (loop?.isActive == true) return
        revoked = false
        gaveUp = false
        attempts = 0
        loop = scope.launch(Dispatchers.IO) { run(initial) }
    }

    fun stop() {
        RLog.d("stop() called")
        if (state == LinkState.Online) markSeen()
        loop?.cancel()
        loop = null
        channel?.close()
        channel = null
        state = LinkState.Idle
        waiting.values.forEach { it.completeExceptionally(RemoteException("disconnected")) }
        waiting.clear()
    }

    /** Try right now (the person came back to the app): skips the wait between tries. */
    fun nudge() {
        RLog.d("nudge in $state")
        if (state == LinkState.Offline || state == LinkState.Idle) {
            loop?.cancel()
            gaveUp = false
            attempts = 0
            loop = scope.launch(Dispatchers.IO) { run(null) }
        }
    }

    /** The moment the computer was last reachable: kept when the link ends, so that "inactive for 3 hours" is true. */
    private fun markSeen() {
        val updated = computer.copy(lastSeen = System.currentTimeMillis())
        computer = updated
        onComputerChanged(updated)
    }

    private suspend fun run(initial: SecureChannel?) {
        var next = initial
        var wait = 1000L
        while (scope.isActive) {
            try {
                state = LinkState.Connecting
                val ch = next ?: raceConnect(computer.hostPub, signer, computer.did, computer.addresses, computer.brokers)
                next = null
                adopt(ch)
                attempts = 0
                wait = 1000L
                suspendCancellableCoroutine<Unit> { cont ->
                    ch.onClose = { if (cont.isActive) cont.resumeWith(Result.success(Unit)) }
                    cont.invokeOnCancellation { ch.close() }
                    if (!ch.isOpen && cont.isActive) cont.resumeWith(Result.success(Unit))
                }
                markSeen()
                state = LinkState.Offline
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = LinkState.Offline
                error = e.message
                attempts++
                if (e is RemoteException && e.denied && e.message?.contains("unknown") == true) { revoked = true; return }
                if (e is RemoteException && e.denied) { revoked = true; return }
                // Two tries, then it is left alone: the person sees "not active" and a button, nothing keeps trying in the dark.
                if (attempts >= MAX_TRIES) {
                    gaveUp = true
                    channel = null
                    via = null
                    waiting.values.forEach { it.completeExceptionally(RemoteException("the connection dropped")) }
                    waiting.clear()
                    return
                }
            }
            channel = null
            via = null
            waiting.values.forEach { it.completeExceptionally(RemoteException("the connection dropped")) }
            waiting.clear()
            delay(wait)
            wait = (wait * 2).coerceAtMost(12_000)
        }
    }

    private fun adopt(ch: SecureChannel) {
        channel = ch
        via = ch.link.kind
        ch.onMessage = { handle(it) }
        host = ch.ready.optJSONObject("host")
        state = LinkState.Online
        error = null
        RLog.d("online via ${ch.link.kind}")
        val h = host
        val addresses = h?.optJSONArray("addresses")?.let { a -> List(a.length()) { a.getString(it) } }
        val name = h?.optString("name")?.takeIf { it.isNotBlank() } ?: computer.name
        val updated = computer.copy(
            name = name, lastSeen = System.currentTimeMillis(), lastVia = ch.link.kind,
            addresses = (addresses?.takeIf { it.isNotEmpty() } ?: computer.addresses),
        )
        computer = updated
        onComputerChanged(updated)
        // The computer learns who is here, and whether the memory of this phone may be read.
        scope.launch(Dispatchers.IO) {
            attempt("remoteconnection") { request("hello", profile()) }
            _online.emit(Unit)
        }
    }

    private fun handle(msg: JSONObject) {
        when (msg.optString("k")) {
            "res" -> waiting.remove(msg.optInt("id"))?.complete(msg)
            "ev" -> _events.tryEmit(SessionEvent(msg.optString("s"), msg.optInt("n"), msg.getJSONObject("e")))
            "g" -> _global.tryEmit(msg.getJSONObject("e"))
            "rpc" -> scope.launch(Dispatchers.IO) {
                val id = msg.optString("id")
                val reply = JSONObject().put("k", "rpc.res").put("id", id)
                try {
                    val r = onRpc(msg.optString("m"), msg.optJSONObject("p") ?: JSONObject())
                    reply.put("ok", true).put("r", r ?: "")
                } catch (e: Exception) {
                    reply.put("ok", false).put("err", e.message ?: "failed")
                }
                channel?.send(reply)
            }
        }
    }

    /** Asks the computer and waits for the answer. Throws when it says no, or the connection drops. */
    suspend fun request(method: String, params: JSONObject = JSONObject(), timeoutMs: Long = 60_000): JSONObject {
        val ch = channel?.takeIf { it.isOpen } ?: throw RemoteException("not connected")
        val id = nextId.incrementAndGet()
        val d = CompletableDeferred<JSONObject>()
        waiting[id] = d
        // Writing to the socket is never done on the main thread (the callers are screens).
        withContext(Dispatchers.IO) { ch.send(JSONObject().put("k", "req").put("id", id).put("m", method).put("p", params)) }
        val res = try {
            withTimeout(timeoutMs) { d.await() }
        } catch (e: TimeoutCancellationException) {
            waiting.remove(id)
            throw RemoteException("$method: the computer did not answer")
        }
        if (!res.optBoolean("ok")) throw RemoteException(res.optString("err", "failed"))
        return res
    }

    suspend fun call(method: String, params: JSONObject = JSONObject(), timeoutMs: Long = 60_000): JSONObject =
        request(method, params, timeoutMs).optJSONObject("r") ?: JSONObject()
}
