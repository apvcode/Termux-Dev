package com.apvcode.devx

import com.apvcode.devx.data.McpException
import com.apvcode.devx.data.McpSession
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/** A tiny MCP server over HTTP (plain sockets): answers as plain JSON or as an event stream, and wants a session id and a token. */
class McpTest {
    private lateinit var socket: ServerSocket
    private lateinit var thread: Thread
    private var stream = false
    private var sessionHeaderSeen: String? = null
    private val calls = CopyOnWriteArrayList<String>()
    private val port get() = socket.localPort

    @Before
    fun start() {
        socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        thread = Thread {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                try { serve(client) } catch (_: Exception) {} finally { runCatching { client.close() } }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun serve(client: java.net.Socket) {
        val input = client.getInputStream().buffered()
        fun line(): String { val sb = StringBuilder(); while (true) { val c = input.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) sb.append(c.toChar()) }; return sb.toString() }
        line() // the request line
        val headers = HashMap<String, String>()
        while (true) { val l = line(); if (l.isEmpty()) break; headers[l.substringBefore(':').lowercase()] = l.substringAfter(':').trim() }
        val body = ByteArray(headers["content-length"]?.toInt() ?: 0).also { var n = 0; while (n < it.size) { val r = input.read(it, n, it.size - n); if (r < 0) break; n += r } }
        sessionHeaderSeen = headers["mcp-session-id"]
        val out = client.getOutputStream()
        fun reply(code: Int, type: String? = null, text: String = "", extra: String = "") {
            val bytes = text.toByteArray()
            out.write(("HTTP/1.1 $code X\r\n" + (type?.let { "Content-Type: $it\r\n" } ?: "") + extra + "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
            out.write(bytes); out.flush()
        }
        if (headers["authorization"] != "Bearer secret") { reply(401); return }
        val req = JSONObject(body.decodeToString())
        val method = req.getString("method")
        calls += method
        if (!req.has("id")) { reply(202); return }
        val result = when (method) {
            "initialize" -> JSONObject().put("protocolVersion", "2025-06-18").put("serverInfo", JSONObject().put("name", "Fake"))
            "tools/list" -> JSONObject().put("tools", org.json.JSONArray().put(JSONObject().put("name", "echo").put("description", "Says it back")
                .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject().put("text", JSONObject().put("type", "string"))))
                .put("annotations", JSONObject().put("readOnlyHint", true))))
            "tools/call" -> JSONObject().put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", "echo: " + req.getJSONObject("params").getJSONObject("arguments").getString("text"))))
            else -> JSONObject()
        }
        val answer = JSONObject().put("jsonrpc", "2.0").put("id", req.get("id")).put("result", result).toString()
        val extra = if (method == "initialize") "Mcp-Session-Id: sess-1\r\n" else ""
        if (stream) reply(200, "text/event-stream", "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\nevent: message\ndata: $answer\n\n", extra)
        else reply(200, "application/json", answer, extra)
    }

    @After
    fun stop() = socket.close()

    private fun session(token: String = "secret") =
        McpSession(OkHttpClient(), "http://127.0.0.1:$port/mcp", mapOf("Authorization" to "Bearer $token"))

    @Test
    fun connectsListsToolsAndCallsOneWithPlainJson() = runBlocking {
        val s = session()
        val tools = s.connect()
        assertEquals(listOf("echo"), tools.map { it.name })
        assertTrue(tools[0].readOnly)
        assertEquals("Fake", s.serverName)
        val r = s.call("echo", JSONObject().put("text", "hi"))
        assertEquals("echo: hi", r.text)
        // The session id the server gave is sent back, and the client told the server it was ready.
        assertEquals("sess-1", sessionHeaderSeen)
        assertTrue("notifications/initialized" in calls)
    }

    @Test
    fun readsTheAnswerOutOfAnEventStream() = runBlocking {
        stream = true
        val s = session()
        assertEquals(listOf("echo"), s.connect().map { it.name })
        assertEquals("echo: stream", s.call("echo", JSONObject().put("text", "stream")).text)
    }

    @Test
    fun aWrongTokenIsSaidPlainly() {
        val e = runCatching { runBlocking { session("wrong").connect() } }.exceptionOrNull()
        assertTrue(e is McpException)
        assertTrue(e!!.message!!.contains("401"))
    }
}
