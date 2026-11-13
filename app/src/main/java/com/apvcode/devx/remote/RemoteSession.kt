package com.apvcode.devx.remote

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** One line of the conversation as the phone shows it. */
sealed class RItem {
    abstract val key: Long

    class User(override val key: Long, val text: String, val files: List<String>, val images: Int) : RItem() {
        /** What this phone attached: the pictures are drawn from the phone itself (the computer only knows the names). */
        var previews by mutableStateOf<List<Preview>>(emptyList())
    }
    class Assistant(override val key: Long, text: String) : RItem() {
        var text by mutableStateOf(text)
    }
    class Thought(override val key: Long, text: String, sec: String?, live: Boolean) : RItem() {
        var text by mutableStateOf(text)
        var sec by mutableStateOf(sec)
        var live by mutableStateOf(live)
    }
    class Tool(override val key: Long, val id: String, val name: String, val desc: String, val args: String, result: String?) : RItem() {
        var result by mutableStateOf(result)
    }
    class Compact(override val key: Long, val text: String) : RItem()
    class Notice(override val key: Long, val text: String, val error: Boolean = false) : RItem()
    class Summary(override val key: Long, val ms: Long, val tools: Int, val cost: Double, val failed: Boolean) : RItem()
}

class Preview(val uri: android.net.Uri?, val name: String, val image: Boolean)

class Effort(val supported: Boolean, val levels: List<String>, val level: String?, val alwaysOn: Boolean)

class SessionState(
    val id: String, val title: String, val cwd: String, val running: Boolean, val plan: Boolean, val yolo: Boolean, val extra: Boolean,
    val model: String, val provider: String, val effort: Effort, val cost: Double, val costEstimated: Boolean,
    val ctxUsed: Int, val ctxLimit: Int, val trusted: Boolean, val mcpPending: Int, val queued: Int, val terminal: Boolean = false,
) {
    companion object {
        fun from(o: JSONObject): SessionState {
            val e = o.optJSONObject("effort")
            val levels = e?.optJSONArray("levels")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            val ctx = o.optJSONObject("ctx")
            return SessionState(
                id = o.optString("id"), title = o.optString("title"), cwd = o.optString("cwd"), running = o.optBoolean("running"),
                plan = o.optBoolean("plan"), yolo = o.optBoolean("yolo"), extra = o.optBoolean("extra"),
                model = o.optString("model"), provider = o.optString("provider"),
                effort = Effort(e?.optBoolean("supported") ?: false, levels, e?.optString("level")?.takeIf { it.isNotEmpty() && it != "null" }, e?.optBoolean("alwaysOn") ?: false),
                cost = o.optDouble("cost", 0.0), costEstimated = o.optBoolean("costEstimated"),
                ctxUsed = ctx?.optInt("used") ?: 0, ctxLimit = ctx?.optInt("limit") ?: 0,
                trusted = o.optBoolean("trusted", true), mcpPending = o.optInt("mcpPending"), queued = o.optInt("queued"), terminal = o.optBoolean("terminal"),
            )
        }
    }
}

class Permission(val id: String, val title: String, val lines: List<String>, val notes: List<String>, val danger: Boolean, val canSession: Boolean, val canAlways: Boolean)
class Question(val question: String, val options: List<String>, val allowCustom: Boolean)
class Ask(val id: String, val questions: List<Question>)
class PlanCard(val id: String, val summary: String, val files: List<String>, val steps: List<String>)

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { getString(it) }

/**
 * A conversation that runs on the computer, as the phone sees it. The events of the computer build it up;
 * what the person does goes back as requests. When the connection comes back, the snapshot is asked for again.
 */
class RemoteSession(val conn: RemoteConnection, initialId: String, private val scope: CoroutineScope, val draftCwd: String? = null) {
    var id by mutableStateOf(initialId)
        private set
    val items = mutableStateListOf<RItem>()
    var state by mutableStateOf<SessionState?>(null)
        private set
    var perm by mutableStateOf<Permission?>(null)
        private set
    var ask by mutableStateOf<Ask?>(null)
        private set
    var plan by mutableStateOf<PlanCard?>(null)
        private set
    /** A new chat that is still being opened on the computer (it is kept there only once something is written in it). */
    var draft by mutableStateOf(draftCwd != null)
        private set
    var loading by mutableStateOf(draftCwd == null)
        private set
    var gone by mutableStateOf(false)
        private set
    var moreBefore by mutableStateOf(0)
        private set
    /** Messages sent while the model works: they wait for their turn. */
    var queued by mutableStateOf<List<String>>(emptyList())
        private set
    /** The context is being compacted on the computer: the chat shows it as work in progress. */
    var compacting by mutableStateOf(false)
        private set
    var turnStartedAt by mutableStateOf(0L)
        private set
    var reconnectNote by mutableStateOf<String?>(null)
        private set

    private var keys = 0L
    /** Items that arrived live (not from a snapshot): they play their entrance once, like in the usual chat. */
    private val fresh = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    fun consumeFresh(key: Long): Boolean = fresh.remove(key)
    private fun <T : RItem> live(item: T): T { fresh += item.key; return item }
    private var lastSeq = -1
    private var jobs = ArrayList<Job>()
    private fun key() = ++keys

    fun start() {
        jobs += scope.launch(Dispatchers.Default) { conn.events.collect { if (it.session == id) apply(it.seq, it.event) } }
        jobs += scope.launch(Dispatchers.IO) {
            conn.onlineAgain.collect { resync() }
        }
        if (conn.state == LinkState.Online && !draft) scope.launch(Dispatchers.IO) { resync() }
        if (draft) scope.launch(Dispatchers.IO) { try { ensureOpen() } catch (e: Exception) { notice(e.message ?: "", true) } }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        if (draft) return
        scope.launch(Dispatchers.IO) { runCatching { conn.request("session.detach", JSONObject().put("s", id), 5000) } }
    }

    /** Reads the whole conversation again (first open, after a break, after a lost packet). */
    suspend fun resync(first: Boolean = false) {
        if (draft) return
        try {
            val r = conn.call("session.open", JSONObject().put("id", id), 90_000)
            load(r)
        } catch (e: Exception) {
            if (e.message?.contains("no longer exists") == true) gone = true
            loading = false
        }
    }

    private fun load(r: JSONObject) {
        val snapshotState = r.optJSONObject("state")?.let { SessionState.from(it) }
        val list = ArrayList<RItem>()
        val arr = r.optJSONArray("items") ?: JSONArray()
        for (i in 0 until arr.length()) toItem(arr.getJSONObject(i))?.let { list += it }
        val live = r.optJSONObject("live")
        if (live != null) {
            live.optString("reasoning").takeIf { it.isNotEmpty() }?.let { list += RItem.Thought(key(), it, null, true) }
            live.optString("text").takeIf { it.isNotEmpty() }?.let { list += RItem.Assistant(key(), it) }
            val tools = live.optJSONArray("tools")
            if (tools != null) for (i in 0 until tools.length()) tools.getJSONObject(i).let { t -> list += RItem.Tool(key(), t.optString("id"), t.optString("name"), t.optString("desc"), t.optString("args"), null) }
            perm = live.optJSONObject("perm")?.let { toPerm(it) }
            ask = live.optJSONObject("ask")?.let { toAsk(it) }
            plan = live.optJSONObject("plan")?.let { toPlan(it) }
            turnStartedAt = live.optLong("startedAt")
        } else {
            perm = null; ask = null; plan = null; turnStartedAt = 0
        }
        moreBefore = r.optInt("more")
        lastSeq = r.optInt("seq", -1)
        queued = emptyList()
        state = snapshotState
        snapshotState?.id?.let { if (it.isNotEmpty()) id = it }
        // The change is applied in one go: the screen does not flicker through an empty list.
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            items.clear()
            items.addAll(list)
            loading = false
        }
    }

    private fun toItem(o: JSONObject): RItem? = when (o.optString("k")) {
        "user" -> RItem.User(key(), o.optString("text"), o.optJSONArray("files").strings(), o.optInt("images"))
        "assistant" -> RItem.Assistant(key(), o.optString("text"))
        "thought" -> RItem.Thought(key(), o.optString("text"), o.optString("sec").takeIf { it.isNotEmpty() }, false)
        "tool" -> RItem.Tool(key(), o.optString("id"), o.optString("name"), o.optString("desc"), o.optString("args"), if (o.isNull("result")) null else o.optString("result"))
        "compact" -> RItem.Compact(key(), o.optString("text"))
        else -> null
    }

    private fun toPerm(o: JSONObject): Permission {
        val offer = o.optJSONObject("offer")
        return Permission(o.optString("id"), o.optString("title"), o.optJSONArray("lines").strings(), o.optJSONArray("notes").strings(), o.optBoolean("danger"), offer?.optBoolean("session") ?: false, offer?.optBoolean("always") ?: false)
    }

    private fun toAsk(o: JSONObject): Ask {
        val qs = o.optJSONArray("questions") ?: JSONArray()
        return Ask(o.optString("id"), List(qs.length()) { i -> qs.getJSONObject(i).let { q -> Question(q.optString("question"), q.optJSONArray("options").strings(), q.optBoolean("allowCustom", true)) } })
    }

    private fun toPlan(o: JSONObject) = PlanCard(o.optString("id"), o.optString("summary"), o.optJSONArray("files").strings(), o.optJSONArray("steps").strings())

    // ───────────────────────── events ─────────────────────────

    private fun apply(seq: Int, e: JSONObject) {
        if (lastSeq >= 0 && seq != lastSeq + 1 && e.optString("e") != "reset") {
            // A packet was lost on the way: the conversation is read again instead of showing a hole.
            lastSeq = seq
            scope.launch(Dispatchers.IO) { resync() }
            return
        }
        lastSeq = seq
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { applyNow(e) }
    }

    private fun applyNow(e: JSONObject) {
        when (e.optString("e")) {
            "text" -> {
                val last = items.lastOrNull()
                closeThought()
                if (last is RItem.Assistant && liveText) last.text += e.optString("d") else { items += live(RItem.Assistant(key(), e.optString("d"))); liveText = true }
            }
            "reasoning" -> {
                val last = items.lastOrNull()
                if (last is RItem.Thought && last.live) last.text += e.optString("d") else { thoughtFrom = System.currentTimeMillis(); items += RItem.Thought(key(), e.optString("d"), null, true) }
                liveText = false
            }
            "tool_start" -> {
                closeThought(); liveText = false
                items += RItem.Tool(key(), e.optString("id"), e.optString("name"), e.optString("desc"), e.optString("args"), null)
            }
            "tool_end" -> items.lastOrNull { it is RItem.Tool && it.id == e.optString("id") }?.let { (it as RItem.Tool).result = e.optString("result") }
            "user" -> {
                liveText = false
                val u = live(RItem.User(key(), e.optString("text"), e.optJSONArray("files").strings(), e.optInt("images")))
                if (u.files.isNotEmpty() || u.images > 0) synchronized(outgoing) { outgoing.removeFirstOrNull() }?.let { u.previews = it }
                items += u
            }
            "queued" -> queued = queued + e.optString("text")
            "queued_taken" -> { queued = queued.drop(1); liveText = false; items += live(RItem.User(key(), e.optString("text"), emptyList(), 0)) }
            "perm" -> perm = toPerm(e)
            "perm_done" -> if (perm?.id == e.optString("id")) perm = null
            "ask" -> ask = toAsk(e)
            "ask_done" -> if (ask?.id == e.optString("id")) ask = null
            "plan" -> plan = toPlan(e)
            "plan_done" -> if (plan?.id == e.optString("id")) plan = null
            "state" -> { val s = SessionState.from(e); state = s; if (s.id.isNotEmpty()) id = s.id }
            "turn_start" -> { turnStartedAt = e.optLong("at"); liveText = false }
            "turn_end" -> {
                closeThought(); liveText = false; turnStartedAt = 0
                if (e.optBoolean("aborted")) items += RItem.Notice(key(), "stopped")
                else items += RItem.Summary(key(), e.optLong("ms"), e.optInt("tools"), e.optDouble("cost", 0.0), !e.isNull("failed") && e.optString("failed").isNotEmpty() && e.optString("failed") != "null")
                queued = emptyList()
            }
            "compact" -> { items.clear(); items += RItem.Compact(key(), e.optString("summary")) }
            "undone" -> scope.launch(Dispatchers.IO) { resync() }
            "reset" -> { items.clear(); id = e.optString("id", id); lastSeq = -1 }
            "note" -> items += RItem.Notice(key(), e.optString("text"))
            "system" -> items += RItem.Notice(key(), e.optString("text"))
            "error" -> items += RItem.Notice(key(), e.optString("text"), error = true)
            "reconnecting" -> reconnectNote = e.optString("reason")
            "reconnected" -> reconnectNote = null
            "gone" -> gone = true
        }
        if (e.optString("e") != "reconnecting") { if (e.optString("e") in setOf("text", "reasoning", "tool_start", "turn_end")) reconnectNote = null }
    }

    private var liveText = false
    private var thoughtFrom = 0L

    private fun closeThought() {
        val last = items.lastOrNull()
        if (last is RItem.Thought && last.live) {
            last.live = false
            // How long this thought took, not how long the whole turn has been going.
            if (last.sec == null && thoughtFrom > 0) last.sec = "${((System.currentTimeMillis() - thoughtFrom) / 1000).coerceAtLeast(1)}"
        }
    }

    /** A command the person gave shows in the conversation like a message (only on this phone). */
    fun echoCommand(text: String) { items += live(RItem.User(key(), text, emptyList(), 0)) }

    /** A line only this phone sees (the answer of a command). */
    fun notice(text: String, error: Boolean = false) { items += RItem.Notice(key(), text, error) }

    // ───────────────────────── what the person does ─────────────────────────

    private fun obj(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().also { o -> o.put("s", id); pairs.forEach { (k, v) -> o.put(k, v) } }

    suspend fun send(text: String, files: List<String> = emptyList()) {
        ensureOpen()
        conn.call("session.send", obj("text" to text, "files" to JSONArray(files)))
    }

    private val outgoing = ArrayDeque<List<Preview>>()

    /** What the phone attaches to the message it is about to send: the echo from the computer picks it up. */
    fun rememberOutgoing(previews: List<Preview>) { if (previews.isNotEmpty()) synchronized(outgoing) { outgoing.addLast(previews) } }
    fun forgetOutgoing() { synchronized(outgoing) { outgoing.removeLastOrNull() } }

    private val opening = kotlinx.coroutines.sync.Mutex()

    /** Opens the new chat on the computer (once); the computer forgets it if nothing is written in it. */
    private suspend fun ensureOpen() = opening.withLock {
        if (!draft) return@withLock
        val r = conn.call("session.open", JSONObject().put("cwd", draftCwd).put("plan", false), 90_000)
        r.optString("s").takeIf { it.isNotEmpty() }?.let { id = it }
        load(r)
        draft = false
    }
    suspend fun stopTurn() { conn.call("session.stop", obj()) }
    suspend fun answerPermission(permId: String, answer: String) { conn.call("perm.answer", obj("id" to permId, "answer" to answer)) }
    suspend fun answerQuestions(askId: String, answers: List<String?>) { conn.call("ask.answer", obj("id" to askId, "answers" to JSONArray().also { a -> answers.forEach { a.put(it ?: JSONObject.NULL) } })) }
    suspend fun decidePlan(planId: String, choice: String) { conn.call("plan.decide", obj("id" to planId, "choice" to choice)) }
    suspend fun setMode(plan: Boolean? = null, yolo: Boolean? = null, extra: Boolean? = null) {
        conn.call("session.mode", obj().also { o -> plan?.let { o.put("plan", it) }; yolo?.let { o.put("yolo", it) }; extra?.let { o.put("extra", it) } })
    }
    suspend fun setModel(provider: String?, model: String) { conn.call("session.model", obj("provider" to provider, "model" to model)) }
    suspend fun setEffort(level: String?) { conn.call("session.effort", obj("level" to level)) }
    suspend fun rename(title: String) { conn.call("session.rename", obj("title" to title)) }
    suspend fun compact(): JSONObject = conn.call("session.compact", obj(), 180_000)

    /** Compacts in the background of the screen (it goes on when the sheet that asked for it is gone). */
    fun startCompact() {
        if (compacting) return
        compacting = true
        scope.launch(Dispatchers.IO) {
            try {
                val r = compact()
                if (!r.optBoolean("ok")) notice(r.optString("text"), true)
            } catch (e: Exception) { notice(e.message ?: "", true) }
            finally { compacting = false }
        }
    }
    suspend fun mcp(args: String): JSONObject = conn.call("session.mcp", obj("args" to args), 120_000)
    suspend fun undo(): JSONObject = conn.call("session.undo", obj())
    suspend fun info(): JSONObject = conn.call("session.info", obj())
    suspend fun tasks(): JSONArray = conn.request("session.tasks", obj()).optJSONArray("r") ?: JSONArray()
    suspend fun stopTask(taskId: String) { conn.call("session.stopTask", obj("id" to taskId)) }
    suspend fun trust() { conn.call("session.trust", obj()) }
    suspend fun commands(): List<Pair<String, String>> {
        val a = conn.request("commands.list", obj()).optJSONArray("r") ?: return emptyList()
        return List(a.length()) { a.getJSONObject(it).let { c -> c.getString("cmd") to c.optString("desc") } }
    }
    suspend fun expand(line: String): String? = conn.call("session.expand", obj("line" to line)).optString("text").takeIf { it.isNotEmpty() && it != "null" }
    /** A new empty chat in the same folder; this object follows it. */
    suspend fun reset() { val r = conn.call("session.reset", obj()); r.optString("id").takeIf { it.isNotEmpty() }?.let { id = it }; items.clear(); lastSeq = -1; resync() }
}
