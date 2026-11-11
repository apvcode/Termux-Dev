package com.apvcode.devx

import com.apvcode.devx.data.Api
import com.apvcode.devx.data.Piece
import com.apvcode.devx.data.Provider
import com.apvcode.devx.data.ProviderKind
import com.apvcode.devx.data.WireMessage
import com.apvcode.devx.data.WireTool
import com.apvcode.devx.data.WireToolCall
import com.apvcode.devx.data.WireToolResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsApiTest {
    private val tool = WireTool(
        "web_search", "Search the web",
        JSONObject("""{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""),
    )

    private fun sse(vararg lines: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(lines.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")

    @Test fun openAiToolCallArrivesInFragmentsAndComesOutWhole() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(
                sse(
                    """{"choices":[{"delta":{"content":"Let me look. "}}]}""",
                    """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"web_search","arguments":""}}]}}]}""",
                    """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"query\":"}}]}}]}""",
                    """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"kotlin\"}"}}]}}]}""",
                    """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
                ),
            )
            val provider = Provider("p", "t", ProviderKind.OpenAI, server.url("/v1").toString().trimEnd('/'))
            val pieces = Api().stream(provider, "k", "m", "", listOf(WireMessage("user", "hi")), null, listOf(tool)).toList()
            assertEquals("Let me look. ", pieces.filterIsInstance<Piece.Text>().joinToString("") { it.text })
            val call = pieces.filterIsInstance<Piece.ToolCall>().single()
            assertEquals("call_1", call.id)
            assertEquals("web_search", call.name)
            assertEquals("kotlin", JSONObject(call.arguments).getString("query"))
            // The tool was described to the provider in the OpenAI way.
            val sent = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("function", sent.getJSONArray("tools").getJSONObject(0).getString("type"))
            assertEquals("web_search", sent.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun openAiToolRoundIsSentBackAsToolMessages() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(sse("""{"choices":[{"delta":{"content":"ok"}}]}"""))
            val provider = Provider("p", "t", ProviderKind.OpenAI, server.url("/v1").toString().trimEnd('/'))
            val history = listOf(
                WireMessage("user", "find"),
                WireMessage("assistant", "", toolCalls = listOf(WireToolCall("call_1", "web_search", """{"query":"x"}"""))),
                WireMessage("tool", "", toolResults = listOf(WireToolResult("call_1", "web_search", "result text"))),
            )
            Api().stream(provider, "k", "m", "", history, null, listOf(tool)).toList()
            val messages = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
            assertEquals("assistant", messages.getJSONObject(1).getString("role"))
            assertEquals("call_1", messages.getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0).getString("id"))
            assertEquals("tool", messages.getJSONObject(2).getString("role"))
            assertEquals("call_1", messages.getJSONObject(2).getString("tool_call_id"))
            assertEquals("result text", messages.getJSONObject(2).getString("content"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun anthropicToolUseAndThinkingSignatureAreKept() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(
                sse(
                    """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
                    """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"hmm"}}""",
                    """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"SIG"}}""",
                    """{"type":"content_block_stop","index":0}""",
                    """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"web_search","input":{}}}""",
                    """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"query\":\"a"}}""",
                    """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"b\"}"}}""",
                    """{"type":"content_block_stop","index":1}""",
                ),
            )
            val provider = Provider("p", "t", ProviderKind.Anthropic, server.url("/").toString().trimEnd('/'))
            val pieces = Api().stream(provider, "k", "m", "sys", listOf(WireMessage("user", "hi")), null, listOf(tool)).toList()
            val block = pieces.filterIsInstance<Piece.ThinkingBlock>().single()
            assertEquals("hmm", block.text)
            assertEquals("SIG", block.signature)
            val call = pieces.filterIsInstance<Piece.ToolCall>().single()
            assertEquals("toolu_1", call.id)
            assertEquals("ab", JSONObject(call.arguments).getString("query"))
            val sent = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("web_search", sent.getJSONArray("tools").getJSONObject(0).getString("name"))
            assertTrue(sent.getJSONArray("tools").getJSONObject(0).has("input_schema"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun anthropicToolResultGoesBackInsideAUserTurnWithTheThinkingBlockFirst() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(sse("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}"""))
            val provider = Provider("p", "t", ProviderKind.Anthropic, server.url("/").toString().trimEnd('/'))
            val history = listOf(
                WireMessage("user", "find"),
                WireMessage(
                    "assistant", "",
                    toolCalls = listOf(WireToolCall("toolu_1", "web_search", """{"query":"x"}""")),
                    thinking = listOf(Piece.ThinkingBlock("hmm", "SIG")),
                ),
                WireMessage("tool", "", toolResults = listOf(WireToolResult("toolu_1", "web_search", "result text"))),
            )
            Api().stream(provider, "k", "m", "sys", history, null, listOf(tool)).toList()
            val messages = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
            val assistant = messages.getJSONObject(1).getJSONArray("content")
            assertEquals("thinking", assistant.getJSONObject(0).getString("type"))
            assertEquals("SIG", assistant.getJSONObject(0).getString("signature"))
            assertEquals("tool_use", assistant.getJSONObject(1).getString("type"))
            val user = messages.getJSONObject(2)
            assertEquals("user", user.getString("role"))
            assertEquals("tool_result", user.getJSONArray("content").getJSONObject(0).getString("type"))
            assertEquals("toolu_1", user.getJSONArray("content").getJSONObject(0).getString("tool_use_id"))
        } finally {
            server.shutdown()
        }
    }
}
