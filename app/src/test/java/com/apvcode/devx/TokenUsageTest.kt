package com.apvcode.devx

import com.apvcode.devx.data.Api
import com.apvcode.devx.data.Piece
import com.apvcode.devx.data.Provider
import com.apvcode.devx.data.ProviderKind
import com.apvcode.devx.data.WireMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenUsageTest {
    private fun sse(vararg lines: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(lines.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")

    private fun usageOf(pieces: List<Piece>) = pieces.filterIsInstance<Piece.Usage>().lastOrNull()

    @Test fun openAiCountComesInAChunkWithoutChoices() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(
                sse(
                    """{"choices":[{"delta":{"content":"Hi"}}]}""",
                    """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
                    """{"choices":[],"usage":{"prompt_tokens":1234,"completion_tokens":56,"total_tokens":1290}}""",
                ),
            )
            val provider = Provider("p", "t", ProviderKind.OpenAI, server.url("/v1").toString().trimEnd('/'))
            val pieces = Api().stream(provider, "k", "m", "", listOf(WireMessage("user", "hi"))).toList()
            assertEquals("Hi", pieces.filterIsInstance<Piece.Text>().joinToString("") { it.text })
            assertEquals(Piece.Usage(1234, 56), usageOf(pieces))
            // A server of its own is not asked for the count with an option it may not know.
            assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("stream_options"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun openAiCountInTheChunkWithTheLastWordsIsRead() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(sse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":2}}"""))
            val provider = Provider("p", "t", ProviderKind.OpenAI, server.url("/v1").toString().trimEnd('/'))
            val pieces = Api().stream(provider, "k", "m", "", listOf(WireMessage("user", "hi"))).toList()
            assertEquals("ok", pieces.filterIsInstance<Piece.Text>().joinToString("") { it.text })
            assertEquals(Piece.Usage(10, 2), usageOf(pieces))
        } finally {
            server.shutdown()
        }
    }

    @Test fun noCountMeansNoUsagePiece() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(sse("""{"choices":[{"delta":{"content":"ok"}}]}"""))
            val provider = Provider("p", "t", ProviderKind.OpenAI, server.url("/v1").toString().trimEnd('/'))
            val pieces = Api().stream(provider, "k", "m", "", listOf(WireMessage("user", "hi"))).toList()
            assertTrue(pieces.none { it is Piece.Usage })
        } finally {
            server.shutdown()
        }
    }

    @Test fun anthropicCountsTheCacheAsReadAndTakesTheLastOutput() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(
                sse(
                    """{"type":"message_start","message":{"usage":{"input_tokens":20,"cache_read_input_tokens":900,"cache_creation_input_tokens":80,"output_tokens":1}}}""",
                    """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                    """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}""",
                    """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":42}}""",
                ),
            )
            val provider = Provider("p", "t", ProviderKind.Anthropic, server.url("/").toString().trimEnd('/'))
            val pieces = Api().stream(provider, "k", "m", "", listOf(WireMessage("user", "hi"))).toList()
            assertEquals("Hello", pieces.filterIsInstance<Piece.Text>().joinToString("") { it.text })
            assertEquals(Piece.Usage(1000, 42), usageOf(pieces))
        } finally {
            server.shutdown()
        }
    }
}
