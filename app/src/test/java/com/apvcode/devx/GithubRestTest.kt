package com.apvcode.devx

import com.apvcode.devx.data.GithubRest
import com.apvcode.devx.data.McpException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/** The GitHub API as a tiny local server: it wants a token and answers a few paths. */
class GithubRestTest {
    private lateinit var socket: ServerSocket
    private val seen = CopyOnWriteArrayList<String>()
    private val base get() = "http://127.0.0.1:${socket.localPort}"

    @Before
    fun start() {
        socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                try {
                    val input = client.getInputStream().buffered()
                    fun line(): String { val sb = StringBuilder(); while (true) { val c = input.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) sb.append(c.toChar()) }; return sb.toString() }
                    val request = line()
                    val headers = HashMap<String, String>()
                    while (true) { val l = line(); if (l.isEmpty()) break; headers[l.substringBefore(':').lowercase()] = l.substringAfter(':').trim() }
                    val body = ByteArray(headers["content-length"]?.toInt() ?: 0).also { var n = 0; while (n < it.size) { val r = input.read(it, n, it.size - n); if (r < 0) break; n += r } }
                    seen += request + " | " + body.decodeToString()
                    val (code, text) = when {
                        headers["authorization"] != "Bearer good" -> 401 to "{\"message\":\"Bad credentials\"}"
                        request.startsWith("GET /user ") -> 200 to "{\"login\":\"apvcode\"}"
                        request.startsWith("POST /repos/a/b/issues") -> 201 to "{\"number\":7}"
                        else -> 404 to "{\"message\":\"Not Found\"}"
                    }
                    client.getOutputStream().write(("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${text.length}\r\nConnection: close\r\n\r\n$text").toByteArray())
                    client.close()
                } catch (_: Exception) {}
            }
        }.apply { isDaemon = true; start() }
    }

    @After
    fun stop() = socket.close()

    @Test
    fun checkNamesTheAccountAndAWrongTokenIsSaidPlainly() = runBlocking {
        val gh = GithubRest(OkHttpClient())
        assertEquals("apvcode", gh.check(base, "good"))
        val e = runCatching { gh.check(base, "bad") }.exceptionOrNull()
        assertTrue(e is McpException)
        assertTrue(e!!.message!!.contains("does not accept"))
    }

    @Test
    fun callsSendTheRequestAsTheTokenOwner() = runBlocking {
        val gh = GithubRest(OkHttpClient())
        val ok = gh.call(base, "good", "POST", "/repos/a/b/issues", JSONObject().put("title", "Hi"))
        assertFalse(ok.isError)
        assertTrue(ok.text.startsWith("HTTP 201"))
        assertTrue(seen.any { it.startsWith("POST /repos/a/b/issues") && it.contains("\"title\":\"Hi\"") })
        assertTrue(gh.call(base, "good", "GET", "/nothing", null).isError)
        // A full address (another host) is refused: the tool only talks to the GitHub API.
        assertTrue(gh.call(base, "good", "GET", "https://evil.example/x", null).isError)
    }

    @Test
    fun onlyTheGithubApiAddressCountsAsGithub() {
        assertTrue(GithubRest.isRest("https://api.github.com"))
        assertTrue(GithubRest.isRest("https://api.github.com/"))
        assertFalse(GithubRest.isRest("https://api.githubcopilot.com/mcp/"))
    }
}
