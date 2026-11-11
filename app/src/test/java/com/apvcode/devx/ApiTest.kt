package com.apvcode.devx

import com.apvcode.devx.data.Api
import com.apvcode.devx.data.ApiException
import com.apvcode.devx.data.ErrorKind
import com.apvcode.devx.data.Piece
import com.apvcode.devx.data.Provider
import com.apvcode.devx.data.ProviderKind
import com.apvcode.devx.data.WireMessage
import com.apvcode.devx.data.formatOf
import com.apvcode.devx.data.shouldProbeFormats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

private suspend fun Flow<Piece>.answer() = toList().filterIsInstance<Piece.Text>().joinToString("") { it.text }

class ApiTest {
    private lateinit var server: MockWebServer

    @Before fun up() {
        server = MockWebServer().also { it.start() }
    }

    @After fun down() {
        server.shutdown()
    }

    private fun provider(kind: ProviderKind) = Provider("p", "test", kind, server.url("/v1").toString().trimEnd('/'))

    @Test fun gatewayThatServesBothFormatsIsDetected() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.getHeader("x-api-key") != null) {
                    MockResponse().setBody("""{"data":[{"id":"gpt-4o"},{"id":"claude-sonnet-4-5"}]}""")
                } else {
                    MockResponse().setBody("""{"data":[{"id":"gpt-4o"}]}""")
                }
        }
        val lists = Api().discoverModels(provider(ProviderKind.OpenAI), "k", probeAnyServer = true)
        assertEquals(listOf("claude-sonnet-4-5", "gpt-4o"), lists.models.sorted())
        assertEquals(listOf("claude-sonnet-4-5"), lists.anthropicOnly)
    }

    @Test fun gatewayThatIgnoresAnthropicHeadersHasOneFormat() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("""{"data":[{"id":"a"},{"id":"b"}]}""")
        }
        val lists = Api().discoverModels(provider(ProviderKind.OpenAI), "k", probeAnyServer = true)
        assertEquals(listOf("a", "b"), lists.models)
        assertTrue(lists.anthropicOnly.isEmpty())
    }

    @Test fun anthropicRequestAndStream() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hel\"}}\n\n" +
                    "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"hmm\"}}\n\n" +
                    "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"lo\"}}\n\n" +
                    "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
            ),
        )
        val extra = JSONObject("""{"thinking":{"type":"enabled","budget_tokens":2048},"max_tokens":18048,"reasoning_effort":"high"}""")
        val pieces = Api().stream(provider(ProviderKind.Anthropic), "KEY", "claude-x", "SYS", listOf(WireMessage("user", "hi")), extra).toList()
        assertEquals("Hello", pieces.filterIsInstance<Piece.Text>().joinToString("") { it.text })
        assertEquals("hmm", pieces.filterIsInstance<Piece.Reasoning>().joinToString("") { it.text }) // kept apart from the answer

        val request = server.takeRequest()
        assertEquals("/v1/messages", request.path)
        assertEquals("KEY", request.getHeader("x-api-key"))
        assertEquals("2023-06-01", request.getHeader("anthropic-version"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("SYS", body.getString("system"))
        assertEquals(18048, body.getInt("max_tokens"))
        assertTrue(body.getBoolean("stream"))
        assertEquals("user", body.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertFalse("OpenAI-only parameters must not reach Anthropic", body.has("reasoning_effort"))
        assertEquals(2048, body.getJSONObject("thinking").getInt("budget_tokens"))
    }

    @Test fun openAiRequestAndStream() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hi \"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":null}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"there\"}}]}\n\ndata: [DONE]\n\n",
            ),
        )
        val extra = JSONObject("""{"reasoning_effort":"medium"}""")
        val text = Api().stream(provider(ProviderKind.OpenAI), "KEY", "o3-mini", "SYS", listOf(WireMessage("user", "hi")), extra).answer()
        assertEquals("Hi there", text)
        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer KEY", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("system", body.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertEquals("medium", body.getString("reasoning_effort"))
    }

    @Test fun errorsAreNamedForPeople() = runBlocking {
        suspend fun kindOf(code: Int, body: String): ErrorKind {
            server.enqueue(MockResponse().setResponseCode(code).setBody(body))
            return try {
                Api().stream(provider(ProviderKind.OpenAI), "k", "m", "", listOf(WireMessage("user", "x"))).answer()
                fail("should have failed")
                ErrorKind.Unknown
            } catch (e: ApiException) {
                e.kind
            }
        }
        assertEquals(ErrorKind.Quota, kindOf(429, """{"error":{"message":"You exceeded your current quota, check billing"}}"""))
        assertEquals(ErrorKind.RateLimit, kindOf(429, """{"error":{"message":"Slow down"}}"""))
        assertEquals(ErrorKind.InvalidKey, kindOf(401, """{"error":{"message":"Incorrect API key"}}"""))
        assertEquals(ErrorKind.ProviderDown, kindOf(529, """{"error":{"type":"overloaded_error"}}"""))
        assertEquals(ErrorKind.BadRequest, kindOf(400, """{"error":{"message":"bad field"}}"""))
    }

    @Test fun eachModelUsesItsOwnFormat() {
        val gateway = Provider(
            "g", "gw", ProviderKind.OpenAI, "https://gw.example.com/v1",
            models = listOf("gpt-4o", "claude-sonnet-4-5"), anthropicModels = listOf("claude-sonnet-4-5"),
        )
        assertEquals(ProviderKind.OpenAI, formatOf(gateway, "gpt-4o"))
        assertEquals(ProviderKind.Anthropic, formatOf(gateway, "claude-sonnet-4-5"))
        assertEquals(ProviderKind.Anthropic, formatOf(gateway, "claude-opus-4-1")) // typed by hand
        assertEquals(ProviderKind.Anthropic, formatOf(gateway.copy(kind = ProviderKind.Anthropic), "gpt-4o"))
        assertEquals(ProviderKind.Anthropic, formatOf(Provider("a", "a", ProviderKind.OpenAI, "https://api.anthropic.com"), "claude-x"))
    }

    @Test fun onlyPlainGatewaysAreProbed() {
        fun p(url: String, kind: ProviderKind = ProviderKind.OpenAI) = Provider("x", "x", kind, url)
        assertTrue(shouldProbeFormats(p("https://gw.example.com/v1")))
        assertFalse(shouldProbeFormats(p("https://api.openai.com/v1")))
        assertFalse(shouldProbeFormats(p("https://openrouter.ai/api/v1")))
        assertFalse(shouldProbeFormats(p("http://localhost:11434/v1")))
        assertFalse(shouldProbeFormats(p("http://192.168.1.5:1234/v1")))
        assertFalse(shouldProbeFormats(p("https://api.anthropic.com", ProviderKind.Anthropic)))
        assertFalse(shouldProbeFormats(p("https://gw.example.com/custom/path/v1")))
    }
}
