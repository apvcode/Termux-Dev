package com.apvcode.devx.data

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/*
 * MCP (Model Context Protocol) servers the assistant may use: GitHub, documentation, anything that speaks MCP over HTTP.
 * A phone cannot start programs (npx, uvx), so only servers that are reachable by a web address work here. The phone
 * talks to them directly; the token stays on the phone, encrypted, and goes to that one server only.
 */

enum class McpStatus { Off, Connecting, Connected, Failed }

class McpToolInfo(val name: String, val description: String, val schema: JSONObject, val readOnly: Boolean)

class McpException(message: String) : IOException(message)

/** One server the person added. The tools and the state come from the server and are not saved. */
@Stable
class McpServer(val id: String, val slug: String, name: String, url: String, enabled: Boolean = true) {
    var name by mutableStateOf(name)
    var url by mutableStateOf(url)
    var enabled by mutableStateOf(enabled)
    var status by mutableStateOf(if (enabled) McpStatus.Connecting else McpStatus.Off)
    var error by mutableStateOf<String?>(null)
    var tools by mutableStateOf<List<McpToolInfo>>(emptyList())
    var serverName by mutableStateOf<String?>(null)
    /** Only what reads: GET requests (GitHub), tools marked read-only (others). Saved. */
    var readOnly by mutableStateOf(false)
    /** What the person allowed for good (GitHub: the method, e.g. "GET"; others: the tool's name): it is not asked again. Saved. */
    val always = androidx.compose.runtime.mutableStateListOf<String>()
}

const val GithubRest_TOOL = "github_api"

/** What a tool answered. */
class McpResult(val text: String, val isError: Boolean)

/**
 * One connection to a server over "Streamable HTTP": every message is a POST; the answer is a JSON body or an event
 * stream that carries it. The session id the server gives is sent back with every later message.
 */
class McpSession(private val http: OkHttpClient, private val url: String, private val headers: Map<String, String>) {
    private var sessionId: String? = null
    private var protocol = PROTOCOL
    private var nextId = 1
    private val lock = Mutex()
    var serverName: String? = null
        private set

    /** Starts the session and asks for the tools (all pages of them). */
    suspend fun connect(): List<McpToolInfo> = lock.withLock {
        sessionId = null
        val init = rpc("initialize", JSONObject()
            .put("protocolVersion", PROTOCOL)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "devx-android").put("version", "2.1.0")), retry = false)
        init.optString("protocolVersion").takeIf { it.isNotEmpty() }?.let { protocol = it }
        serverName = init.optJSONObject("serverInfo")?.optString("title")?.takeIf { it.isNotBlank() }
            ?: init.optJSONObject("serverInfo")?.optString("name")?.takeIf { it.isNotBlank() }
        notify("notifications/initialized")
        val out = ArrayList<McpToolInfo>()
        var cursor: String? = null
        var pages = 0
        do {
            val result = rpc("tools/list", JSONObject().also { p -> cursor?.let { p.put("cursor", it) } })
            val list = result.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until list.length()) parseTool(list.getJSONObject(i))?.let { out += it }
            cursor = result.optString("nextCursor").takeIf { it.isNotEmpty() && it != "null" }
        } while (cursor != null && ++pages < 20)
        out
    }

    suspend fun call(tool: String, args: JSONObject): McpResult = lock.withLock {
        val result = rpc("tools/call", JSONObject().put("name", tool).put("arguments", args))
        val text = StringBuilder()
        val content = result.optJSONArray("content") ?: JSONArray()
        for (i in 0 until content.length()) {
            val part = content.getJSONObject(i)
            when (part.optString("type")) {
                "text" -> text.append(part.optString("text")).append('\n')
                "image" -> text.append("[a picture (").append(part.optString("mimeType")).append(") was returned; it cannot be shown here]\n")
                "resource" -> {
                    val r = part.optJSONObject("resource")
                    text.append(r?.optString("text")?.takeIf { it.isNotEmpty() } ?: ("[resource " + r?.optString("uri") + "]")).append('\n')
                }
                else -> text.append(part.toString()).append('\n')
            }
        }
        if (text.isBlank()) result.optJSONObject("structuredContent")?.let { text.append(it.toString()) }
        McpResult(text.toString().trim().ifEmpty { "(the tool returned nothing)" }, result.optBoolean("isError"))
    }

    private fun parseTool(o: JSONObject): McpToolInfo? {
        val name = o.optString("name").takeIf { it.isNotBlank() } ?: return null
        val schema = o.optJSONObject("inputSchema") ?: JSONObject()
        val ann = o.optJSONObject("annotations")
        val description = o.optString("description").ifBlank { o.optString("title") }
        return McpToolInfo(name, description, schema, ann?.optBoolean("readOnlyHint") == true)
    }

    private suspend fun notify(method: String) {
        withContext(Dispatchers.IO) { post(JSONObject().put("jsonrpc", "2.0").put("method", method), expectId = null) }
    }

    /** One request and its answer. A session that the server has forgotten is started again once. */
    private suspend fun rpc(method: String, params: JSONObject, retry: Boolean = true): JSONObject = withContext(Dispatchers.IO) {
        val id = nextId++
        val body = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        try {
            post(body, expectId = id) ?: throw McpException("The server did not answer.")
        } catch (e: SessionGone) {
            if (!retry || method == "initialize") throw McpException("The server closed the session.")
            sessionId = null
            val init = rpc("initialize", JSONObject().put("protocolVersion", PROTOCOL).put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "devx-android").put("version", "2.1.0")), retry = false)
            init.optString("protocolVersion").takeIf { it.isNotEmpty() }?.let { protocol = it }
            post(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"), expectId = null)
            rpc(method, params, retry = false)
        }
    }

    private class SessionGone : IOException()

    /** Sends one message. With [expectId] the answer is read (from JSON or from the event stream) and its `result` returned. */
    private fun post(message: JSONObject, expectId: Int?): JSONObject? {
        val builder = Request.Builder().url(url)
            .header("Accept", "application/json, text/event-stream")
            .header("User-Agent", "devx-android")
        headers.forEach { (k, v) -> builder.header(k, v) }
        if (expectId != null || sessionId != null) sessionId?.let { builder.header("Mcp-Session-Id", it) }
        if (message.optString("method") != "initialize") builder.header("MCP-Protocol-Version", protocol)
        val request = builder.post(message.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            response.header("Mcp-Session-Id")?.let { sessionId = it }
            when (response.code) {
                200, 202 -> Unit
                401, 403 -> {
                    // What the server says about why: its body, or the reason in its challenge header.
                    val why = response.body?.string().orEmpty().trim().take(240).ifEmpty { response.header("WWW-Authenticate").orEmpty().take(240) }
                    throw McpException("The server refused the access (HTTP ${response.code})." + if (why.isNotEmpty()) " $why" else " Check the token.")
                }
                404 -> if (sessionId != null && message.optString("method") != "initialize") throw SessionGone()
                    else throw McpException("The address was not found (HTTP 404). Is it the MCP address of the server?")
                405 -> throw McpException("The server does not take messages at this address (HTTP 405). Servers that only offer the older SSE address are not supported yet.")
                else -> throw McpException("The server answered HTTP ${response.code}: " + response.body?.string().orEmpty().take(200))
            }
            if (expectId == null) return null
            val body = response.body ?: throw McpException("Empty answer.")
            val type = response.header("Content-Type").orEmpty()
            return if (type.contains("text/event-stream")) readStream(body.source(), expectId) else readJson(body.string(), expectId)
        }
    }

    private fun readJson(text: String, id: Int): JSONObject {
        val trimmed = text.trim()
        if (trimmed.startsWith("[")) {
            val a = JSONArray(trimmed)
            for (i in 0 until a.length()) answerOf(a.getJSONObject(i), id)?.let { return it }
            throw McpException("The answer did not contain the reply.")
        }
        return answerOf(JSONObject(trimmed), id) ?: throw McpException("The answer did not contain the reply.")
    }

    /** Reads events until the one that answers [id]; other events (progress, logs) are skipped. */
    private fun readStream(source: okio.BufferedSource, id: Int): JSONObject {
        val data = StringBuilder()
        fun flush(): JSONObject? {
            if (data.isEmpty()) return null
            val text = data.toString()
            data.setLength(0)
            return runCatching { JSONObject(text) }.getOrNull()?.let { answerOf(it, id) }
        }
        while (true) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> flush()?.let { return it }
                line.startsWith("data:") -> data.append(line.removePrefix("data:").removePrefix(" "))
            }
        }
        flush()?.let { return it }
        throw McpException("The stream ended without the reply.")
    }

    private fun answerOf(o: JSONObject, id: Int): JSONObject? {
        if (!o.has("id") || o.opt("id").toString() != id.toString()) return null
        o.optJSONObject("error")?.let { throw McpException(it.optString("message").ifBlank { "The server reported an error." }) }
        return o.optJSONObject("result") ?: JSONObject()
    }

    companion object {
        const val PROTOCOL = "2025-06-18"
    }
}

/** The servers, kept in a small file; their tokens in the encrypted store. Connects to the ones that are on. */
class McpStore(private val context: Context, private val secrets: Secrets, private val scope: CoroutineScope) {
    val servers = mutableStateListOf<McpServer>()
    private val file = File(context.filesDir, "mcp_servers.json")
    private val sessions = HashMap<String, McpSession>()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .dns(FallbackDns())
        .build()

    fun load() {
        val list = runCatching {
            JSONArray(file.takeIf { it.exists() }?.readText() ?: "[]").let { a ->
                (0 until a.length()).map { i ->
                    a.getJSONObject(i).let { o ->
                        McpServer(o.getString("id"), o.getString("slug"), o.getString("name"), o.getString("url"), o.optBoolean("enabled", true)).also { sv ->
                            sv.readOnly = o.optBoolean("readOnly")
                            o.optJSONArray("always")?.let { arr -> for (k in 0 until arr.length()) sv.always += arr.getString(k) }
                        }
                    }
                }
            }
        }.getOrDefault(emptyList())
        servers.clear()
        servers.addAll(list)
        servers.filter { it.enabled }.forEach { connect(it) }
    }

    private fun save() {
        val json = JSONArray().also { a ->
            servers.forEach { a.put(JSONObject().put("id", it.id).put("slug", it.slug).put("name", it.name).put("url", it.url).put("enabled", it.enabled).put("readOnly", it.readOnly).put("always", JSONArray(it.always.toList()))) }
        }.toString()
        runCatching { file.writeText(json) }
    }

    /** What is sent with every message: a token as "Authorization: Bearer", and any other headers the person gave. */
    private fun headersOf(id: String): Map<String, String> {
        val raw = secrets.get("mcp:$id") ?: return emptyMap()
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        o.optString("headers").lineSequence().forEach { line ->
            val at = line.indexOf(':')
            if (at > 0) out[line.substring(0, at).trim()] = line.substring(at + 1).trim()
        }
        o.optString("token").trim().takeIf { it.isNotEmpty() }?.let { out["Authorization"] = if (it.startsWith("Bearer ", true)) it else "Bearer $it" }
        return out
    }

    /** The same server is the same address, whatever the case or a slash at the end. */
    private fun same(a: String, b: String) = a.trim().trimEnd('/').equals(b.trim().trimEnd('/'), ignoreCase = true)

    /** The server that is already added at this address, if any. */
    fun existing(url: String): McpServer? = servers.firstOrNull { same(it.url, url) }

    fun add(name: String, url: String, token: String, extraHeaders: String): McpServer {
        // A server is added once: the same address gives back the one that is there.
        existing(url)?.let { return it }
        val id = java.util.UUID.randomUUID().toString().take(8)
        val base = slugOf(name)
        var slug = base
        var n = 2
        while (servers.any { it.slug == slug }) slug = base + "_" + n++
        val server = McpServer(id, slug, name.trim(), url.trim(), true)
        secrets.put("mcp:$id", JSONObject().put("token", token.trim()).put("headers", extraHeaders.trim()).toString())
        servers += server
        save()
        connect(server)
        return server
    }

    fun remove(server: McpServer) {
        sessions.remove(server.id)
        secrets.remove("mcp:${server.id}")
        servers.remove(server)
        save()
    }

    fun setEnabled(server: McpServer, on: Boolean) {
        server.enabled = on
        save()
        if (on) connect(server) else {
            sessions.remove(server.id)
            server.status = McpStatus.Off
            server.tools = emptyList()
            server.error = null
        }
    }

    fun reconnect(server: McpServer) = connect(server)

    fun setReadOnly(server: McpServer, on: Boolean) { server.readOnly = on; save() }

    fun allowAlways(server: McpServer, key: String) { if (key.isNotEmpty() && key !in server.always) { server.always += key; save() } }

    fun clearAlways(server: McpServer) { server.always.clear(); save() }

    private val github = GithubRest(http)

    /** The token of a server, as the person gave it. */
    private fun tokenOf(id: String): String? = headersOf(id)["Authorization"]?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }

    suspend fun githubCall(server: McpServer, method: String, path: String, body: JSONObject?): McpResult {
        val token = tokenOf(server.id) ?: return McpResult("No token is saved for this server.", true)
        return github.call(server.url, token, method, path, body)
    }

    /** Starts the session and reads the tools; the state of the server follows the outcome. */
    fun connect(server: McpServer) {
        server.status = McpStatus.Connecting
        server.error = null
        if (GithubRest.isRest(server.url)) {
            // GitHub itself: checked with its ordinary API; the assistant gets one tool that sends requests to it.
            scope.launch(Dispatchers.IO) {
                try {
                    val token = tokenOf(server.id) ?: throw McpException("Give a token.")
                    server.serverName = github.check(server.url, token)
                    server.tools = listOf(McpToolInfo(GithubRest_TOOL, "GitHub API as " + server.serverName, JSONObject(), false))
                    server.status = McpStatus.Connected
                } catch (e: Exception) {
                    server.status = McpStatus.Failed
                    server.error = e.message ?: e.javaClass.simpleName
                    server.tools = emptyList()
                }
            }
            return
        }
        scope.launch(Dispatchers.IO) {
            val session = McpSession(http, server.url, headersOf(server.id))
            try {
                val tools = session.connect()
                sessions[server.id] = session
                server.tools = tools
                server.serverName = session.serverName
                server.status = McpStatus.Connected
            } catch (e: Exception) {
                server.status = McpStatus.Failed
                server.error = e.message ?: e.javaClass.simpleName
                server.tools = emptyList()
            }
        }
    }

    suspend fun call(server: McpServer, tool: String, args: JSONObject): McpResult {
        val session = sessions[server.id] ?: run {
            // The session was lost (the app was away for long): start it again.
            val fresh = McpSession(http, server.url, headersOf(server.id))
            fresh.connect()
            sessions[server.id] = fresh
            fresh
        }
        return session.call(tool, args)
    }

    companion object {
        fun slugOf(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24).ifEmpty { "server" }
    }
}

/** The tools of the servers, as the assistant sees them. */
class McpTools(private val store: McpStore) {
    private val directLimit = 24

    fun isMcp(name: String) = name.startsWith("mcp__") || name == FIND || name == CALL || name == GithubRest_TOOL

    private fun live(): List<McpServer> = store.servers.filter { it.enabled && it.status == McpStatus.Connected && it.tools.isNotEmpty() }

    private fun directName(server: McpServer, tool: String): String {
        val full = "mcp__${server.slug}__" + tool.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return full.take(64)
    }

    /** Few tools: each is offered by itself. Many (GitHub has dozens): two tools, one to find and one to call, so that the request stays small. */
    fun definitions(): List<WireTool> {
        val all = live()
        val rest = all.filter { GithubRest.isRest(it.url) }
        val servers = all - rest.toSet()
        val github = if (rest.isEmpty()) emptyList() else listOf(
            WireTool(
                GithubRest_TOOL,
                "Send a request to the GitHub API (api.github.com) as the person (${rest.first().serverName}), with their token. Use it for repositories, files, branches, commits, issues, pull requests and search: " +
                    "for example GET /user/repos, GET /repos/{owner}/{repo}/contents/{path}, PUT /repos/{owner}/{repo}/contents/{path} (content in base64, with the sha of the old file to change one), POST /repos/{owner}/{repo}/issues, GET /search/code?q=. " +
                    "It changes real data: before anything that deletes or overwrites, tell the person what you are going to do and ask.",
                JSONObject().put("type", "object")
                    .put("properties", JSONObject()
                        .put("method", str("GET, POST, PATCH, PUT or DELETE."))
                        .put("path", str("The path on api.github.com, starting with /, with the query if any."))
                        .put("body", JSONObject().put("type", "object").put("description", "The JSON to send (for POST, PATCH, PUT).")))
                    .put("required", JSONArray(listOf("method", "path"))),
            ),
        )
        if (servers.isEmpty()) return github
        val total = servers.sumOf { it.tools.size }
        if (total <= directLimit) {
            return github + servers.flatMap { s -> s.tools.filter { !s.readOnly || it.readOnly }.map { t -> WireTool(directName(s, t.name), "[${s.name}] " + t.description.take(700), fixSchema(t.schema)) } }
        }
        val names = servers.joinToString("; ") { "${it.slug} (${it.name}, ${it.tools.size} tools)" }
        return github + listOf(
            WireTool(
                FIND,
                "Find tools of the connected MCP servers. Servers: $names. Give a few words of what you need; gives matching tools with what each does and its arguments. Use this before mcp_call.",
                schema("query" to str("What you want to do, in a few words."), "server" to str("Only this server (its short name)."), required = listOf("query")),
            ),
            WireTool(
                CALL,
                "Call a tool of an MCP server (find it first with mcp_find_tools; pass the arguments as that tool describes them).",
                JSONObject().put("type", "object")
                    .put("properties", JSONObject()
                        .put("server", str("The server's short name."))
                        .put("tool", str("The tool's name."))
                        .put("arguments", JSONObject().put("type", "object").put("description", "The arguments of the tool.")))
                    .put("required", JSONArray(listOf("server", "tool"))),
            ),
        )
    }

    suspend fun run(name: String, args: JSONObject, report: ToolReport): ToolOutcome {
        if (name == GithubRest_TOOL) {
            val server = live().firstOrNull { GithubRest.isRest(it.url) } ?: return ToolOutcome("GitHub is not connected. The person can add it in the MCP servers screen.")
            val method = args.optString("method").ifBlank { "GET" }
            val path = args.optString("path")
            val body = args.optJSONObject("body")
            val verb = method.uppercase()
            if (server.readOnly && verb != "GET") return ToolOutcome("GitHub is in read-only mode (the person turned it on): only GET requests are allowed. Say what you could not do; the person can switch the mode off in the MCP servers screen.")
            if (!report.allowed(server.id) && verb !in server.always) {
                val say = report.approve(ToolApproval(server.id, server.name, "$verb $path", body?.toString()?.take(400).orEmpty(), verb != "GET", verb))
                if (say == ApprovalDecision.Deny) return ToolOutcome(DECLINED)
                if (say == ApprovalDecision.Always) store.allowAlways(server, verb)
            }
            report.activity(ToolActivity(ToolKind.Mcp, "GitHub · ${method.uppercase()} ${path.take(60)}"))
            val result = store.githubCall(server, method, path, args.optJSONObject("body"))
            return ToolOutcome(result.text)
        }
        val servers = live().filter { !GithubRest.isRest(it.url) }
        if (name == FIND) {
            val words = ChatSearch.tokens(args.optString("query"))
            val only = args.optString("server").takeIf { it.isNotBlank() }
            val hits = servers.filter { only == null || it.slug == only }.flatMap { s -> s.tools.filter { !s.readOnly || it.readOnly }.map { s to it } }
                .map { (s, t) -> Triple(s, t, score(t, words)) }.filter { words.isEmpty() || it.third > 0 }.sortedByDescending { it.third }.take(12)
            if (hits.isEmpty()) return ToolOutcome("No tool matches. Servers: " + servers.joinToString { it.slug })
            return ToolOutcome(hits.joinToString("\n\n") { (s, t, _) ->
                "${s.slug} · ${t.name}${if (t.readOnly) " (read only)" else ""}: ${t.description.take(300)}\narguments: ${t.schema.optJSONObject("properties")?.toString()?.take(700) ?: "{}"}" + (t.schema.optJSONArray("required")?.let { "\nrequired: $it" } ?: "")
            })
        }
        val server: McpServer
        val tool: String
        val arguments: JSONObject
        if (name == CALL) {
            server = servers.firstOrNull { it.slug == args.optString("server") } ?: return ToolOutcome("No connected server is called \"${args.optString("server")}\". Servers: " + servers.joinToString { it.slug })
            tool = args.optString("tool")
            arguments = args.optJSONObject("arguments") ?: JSONObject()
        } else {
            val found = servers.firstNotNullOfOrNull { s -> s.tools.firstOrNull { directName(s, it.name) == name }?.let { s to it } } ?: return ToolOutcome("That MCP tool is not available any more.")
            server = found.first
            tool = found.second.name
            arguments = args
        }
        if (server.tools.none { it.name == tool }) return ToolOutcome("The server ${server.slug} has no tool \"$tool\". Use mcp_find_tools.")
        val info = server.tools.first { it.name == tool }
        if (server.readOnly && !info.readOnly) return ToolOutcome("This server is in read-only mode (the person turned it on): only tools that just read are allowed, and \"$tool\" is not one. Say what you could not do.")
        if (!report.allowed(server.id) && tool !in server.always) {
            val say = report.approve(ToolApproval(server.id, server.name, tool, arguments.toString().take(400).takeIf { it != "{}" }.orEmpty(), !info.readOnly, tool))
            if (say == ApprovalDecision.Deny) return ToolOutcome(DECLINED)
            if (say == ApprovalDecision.Always) store.allowAlways(server, tool)
        }
        report.activity(ToolActivity(ToolKind.Mcp, "${server.name} · $tool"))
        val result = store.call(server, tool, arguments)
        val text = if (result.text.length > MAX_TEXT) result.text.take(MAX_TEXT) + "\n[cut: the answer was longer]" else result.text
        return ToolOutcome(if (result.isError) "The tool reported an error:\n$text" else text)
    }

    private fun score(t: McpToolInfo, words: List<String>): Int {
        val hay = (t.name + " " + t.description).lowercase()
        return words.count { hay.contains(ChatSearch.stem(it)) }
    }

    /** The schema as the models want it: an object with properties (some servers send less). */
    private fun fixSchema(raw: JSONObject): JSONObject {
        val copy = JSONObject(raw.toString())
        copy.remove("\$schema")
        if (!copy.has("type")) copy.put("type", "object")
        if (copy.optString("type") == "object" && !copy.has("properties")) copy.put("properties", JSONObject())
        return copy
    }

    companion object {
        const val FIND = "mcp_find_tools"
        const val CALL = "mcp_call"
        const val MAX_TEXT = 20_000
        const val DECLINED = "The person declined this. Do not try it again; say what you could not do and ask what they would like instead."
    }
}

/**
 * The name lookup of the phone first; when it cannot find a host (a router or a provider that hides some addresses), the
 * same question is asked over HTTPS of public resolvers, so that the server is still reached.
 */
class FallbackDns : okhttp3.Dns {
    private val doh = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()

    override fun lookup(hostname: String): List<java.net.InetAddress> {
        try {
            return okhttp3.Dns.SYSTEM.lookup(hostname)
        } catch (e: java.net.UnknownHostException) {
            val found = resolvers.firstNotNullOfOrNull { base -> runCatching { ask(base, hostname) }.getOrNull()?.takeIf { it.isNotEmpty() } }
            return found ?: throw e
        }
    }

    private fun ask(base: String, hostname: String): List<java.net.InetAddress> {
        val request = Request.Builder().url("$base?name=$hostname&type=A").header("Accept", "application/dns-json").build()
        doh.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return emptyList()
            val answers = JSONObject(r.body?.string().orEmpty()).optJSONArray("Answer") ?: return emptyList()
            val out = ArrayList<java.net.InetAddress>()
            for (i in 0 until answers.length()) {
                val a = answers.getJSONObject(i)
                if (a.optInt("type") != 1) continue
                val ip = java.net.InetAddress.getByName(a.optString("data"))
                out += java.net.InetAddress.getByAddress(hostname, ip.address)
            }
            return out
        }
    }

    private companion object {
        val resolvers = listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/resolve")
    }
}
