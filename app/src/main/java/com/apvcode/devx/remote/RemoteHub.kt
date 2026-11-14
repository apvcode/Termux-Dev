package com.apvcode.devx.remote

import android.content.Context
import com.apvcode.devx.data.MemoryStore
import com.apvcode.devx.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.json.JSONObject

/** Everything about the computers this phone can control: who they are, the connections, the pairing. */
object RemoteHub {
    lateinit var store: ComputerStore
        private set
    private lateinit var signer: Signer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connections = HashMap<String, RemoteConnection>()
    private var ready = false

    /** The notes of the memory: read when the agent on the computer asks for them (and only if the person allows it). */
    var memory: MemoryStore? = null

    fun init(context: Context, signer: Signer? = null) {
        if (ready) return
        this.signer = signer ?: KeystoreSigner()
        store = ComputerStore(context.applicationContext).also { it.load() }
        ready = true
        RemoteHubReady.ready = true
    }

    /** What the computer should know: the name of the person, and whether its agent may read the memory of this phone. */
    fun profile(): JSONObject = JSONObject().put("name", Prefs.nickname).put("memory", Prefs.memory && Prefs.remoteMemory && memory != null)

    @Synchronized
    fun connection(hostId: String): RemoteConnection? {
        connections[hostId]?.let { return it }
        val computer = store.find(hostId) ?: return null
        return RemoteConnection(computer, scope, signer) { store.put(it) }.also { setup(it); connections[hostId] = it }
    }

    private fun setup(c: RemoteConnection) {
        c.profile = ::profile
        c.onRpc = { method, params -> if (method == "memory") answerMemory(params) else throw IllegalArgumentException("unknown") }
        // The number of chats that want a look follows the computer: asked when the link is up and whenever the list changes there.
        scope.launch(Dispatchers.IO) { c.onlineAgain.collect { refreshAttention(c) } }
        scope.launch(Dispatchers.IO) { c.global.collect { if (it.optString("e") == "sessions") refreshAttention(c) } }
        scope.launch(Dispatchers.IO) { androidx.compose.runtime.snapshotFlow { c.state }.collect { if (it == LinkState.Online) refreshAttention(c) else if (it == LinkState.Offline) c.attention = 0 } }
    }

    private suspend fun refreshAttention(c: RemoteConnection) {
        try { c.attention = c.call("attention").optInt("total") } catch (_: Exception) {}
    }

    private fun answerMemory(p: JSONObject): String {
        val store = memory ?: throw IllegalStateException("no memory")
        if (!(Prefs.memory && Prefs.remoteMemory)) throw IllegalStateException("the person did not allow it")
        val notes = if (p.optString("action") == "search") store.search(p.optString("query")) else store.notes.sortedByDescending { it.updatedAt }
        if (notes.isEmpty()) return "No notes."
        return notes.take(60).joinToString("\n") { "- ${it.text}" }
    }

    /**
     * Pairs with the computer of a QR code: reaches it (directly or through the relay), shows the six digits through [onSas],
     * waits for the person at the computer, stores the computer. The returned connection is already open.
     */
    suspend fun pair(p: Pairing, phoneName: String, onSas: (String) -> Unit): RemoteConnection {
        val did = RemoteCrypto.randomBytes(16).toHex()
        val channel = raceConnect(p.hostPub, signer, did, p.addresses, p.brokers, PairRequest(p.token, phoneName), onSas)
        val computer = Computer(p.hostId, p.name, p.hostPub, did, p.addresses, p.brokers, System.currentTimeMillis())
        store.put(computer)
        synchronized(this) { connections.remove(computer.hostId)?.stop() }
        val conn = RemoteConnection(computer, scope, signer) { store.put(it) }
        setup(conn)
        synchronized(this) { connections[computer.hostId] = conn }
        conn.start(channel)
        return conn
    }

    @Synchronized
    fun forget(hostId: String) {
        connections.remove(hostId)?.stop()
        store.remove(hostId)
    }

    /** The screens ask for a try right now (the person opened the app). */
    @Synchronized
    fun nudgeAll() = connections.values.forEach { it.nudge() }

    @Synchronized
    fun stopAll() = connections.values.forEach { it.stop() }

    /** Every connection but this one is let go. */
    @Synchronized
    fun stopOthers(hostId: String) = connections.filterKeys { it != hostId }.values.forEach { it.stop() }

    fun sessionOf(conn: RemoteConnection, id: String) = RemoteSession(conn, id, scope)

    /** The scope for work that must outlive a screen. */
    val work: CoroutineScope get() = scope

    /** Opens a new conversation on the computer, in [cwd]; returns its id. */
    suspend fun newSession(conn: RemoteConnection, cwd: String, plan: Boolean = false): String =
        conn.call("session.open", JSONObject().put("cwd", cwd).put("plan", plan), 90_000).getString("s")
}

/** A pairing code that arrived as a link (the app was opened with devx1:...): the pairing screen takes it. */
object PendingPairing {
    var code by androidx.compose.runtime.mutableStateOf<String?>(null)
}

/** A notification asked for this remote chat: the app opens on it. */
object PendingRemoteChat {
    var target by androidx.compose.runtime.mutableStateOf<Pair<String, String>?>(null)
}
