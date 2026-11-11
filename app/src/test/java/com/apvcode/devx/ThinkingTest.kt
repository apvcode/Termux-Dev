package com.apvcode.devx

import com.apvcode.devx.data.Api
import com.apvcode.devx.data.Level
import com.apvcode.devx.data.Piece
import com.apvcode.devx.data.Provider
import com.apvcode.devx.data.ProviderKind
import com.apvcode.devx.data.ReasoningCatalog
import com.apvcode.devx.data.ThinkSplitter
import com.apvcode.devx.data.WireMessage
import com.apvcode.devx.data.resolveReasoning
import com.apvcode.devx.ui.chat.thinkingHeadline
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ThinkSplitterTest {
    private fun split(vararg chunks: String): Pair<String, String> {
        val s = ThinkSplitter()
        val pieces = chunks.flatMap { s.feed(it) } + s.flush()
        return pieces.filterIsInstance<Piece.Text>().joinToString("") { it.text } to
            pieces.filterIsInstance<Piece.Reasoning>().joinToString("") { it.text }
    }

    @Test fun plainTextPassesThrough() = assertEquals("hello" to "", split("hel", "lo"))

    @Test fun thinkTagsAreSeparated() = assertEquals("answer" to "pondering", split("<think>pondering</think>answer"))

    @Test fun tagCutInTwoByTheChunkBoundary() = assertEquals("done" to "deep thought", split("<thi", "nk>deep thought</th", "ink>done"))

    @Test fun lessThanSignIsNotATag() = assertEquals("a < b and c" to "", split("a < b", " and c"))
}

class ReasoningCatalogTest {
    private val catalog = File("src/main/assets/reasoning-catalog.json")

    @Test fun catalogueGivesLevelsThatRulesDoNotKnow() {
        ReasoningCatalog.load(catalog.inputStream())
        assertTrue(ReasoningCatalog.size > 1000)
        // A model that no name rule knows: its levels come from the catalogue.
        val caps = resolveReasoning("deepseek-v4-flash", "https://gw.example.com/v1", ProviderKind.OpenAI)
        assertTrue("catalogue should offer more than one level, got ${caps.levels}", caps.levels.size >= 3)
        assertTrue(Level.High in caps.levels)
    }

    @Test fun anUnknownModelStillGetsNothingInsteadOfAGuess() {
        ReasoningCatalog.load(catalog.inputStream())
        assertTrue(resolveReasoning("my-private-model-xyz", "https://gw.example.com/v1", ProviderKind.OpenAI).levels.isEmpty())
    }
}

class HeadlineTest {
    @Test fun latestBoldTitleWins() =
        assertEquals("Checking the edge cases", thinkingHeadline("**Reading the request**\nblah\n\n**Checking the edge cases**\nmore"))

    @Test fun rawThinkingGetsShortened() {
        val h = thinkingHeadline("Okay, let me work out how many apples are left after the first day of the sale")!!
        assertTrue(h, h.startsWith("Work out"))
        assertTrue(h.endsWith("…"))
    }

    @Test fun nothingToSay() = assertNull(thinkingHeadline("  "))
}

class ReasoningStreamTest {
    @Test fun openAiReasoningFieldsAreKeptApart() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(
                MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                    "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"first \"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"reasoning\":\"second\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"<think>inline</think>Answer\"}}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
            )
            val provider = Provider("p", "t", ProviderKind.OpenAI, server.url("/v1").toString().trimEnd('/'))
            val pieces = Api().stream(provider, "k", "m", "", listOf(WireMessage("user", "x"))).toList()
            assertEquals("Answer", pieces.filterIsInstance<Piece.Text>().joinToString("") { it.text })
            assertEquals("first secondinline", pieces.filterIsInstance<Piece.Reasoning>().joinToString("") { it.text })
            assertNotNull(pieces)
        } finally {
            server.shutdown()
        }
    }
}
