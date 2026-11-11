package com.apvcode.devx

import com.apvcode.devx.data.Piece
import com.apvcode.devx.data.TextToolCalls
import com.apvcode.devx.data.ThoughtSummaryFilter
import com.apvcode.devx.data.WireTool
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiLeakTest {
    private fun run(vararg chunks: String): Pair<String, String> {
        val f = ThoughtSummaryFilter()
        val pieces = chunks.flatMap { f.feed(it) } + f.flush()
        return pieces.filterIsInstance<Piece.Text>().joinToString("") { it.text } to
            pieces.filterIsInstance<Piece.Reasoning>().joinToString("") { it.text }
    }

    @Test fun thoughtSummariesAtTheHeadGoToThinkingAndTheAnswerStays() {
        val (answer, thinking) = run(
            "**Understanding intent**\nThe user said \"вацап\", which can mean the app or a greeting.\n\n",
            "**Drafting response**\nKeep it direct and calm, in Russian.\n\nПривет! Чем могу помочь с Вацапом?",
        )
        assertEquals("Привет! Чем могу помочь с Вацапом?", answer)
        assertTrue(thinking, thinking.contains("**Understanding intent**\n\nThe user said"))
        assertTrue(thinking, thinking.contains("**Drafting response**\n\nKeep it direct"))
    }

    @Test fun worksWhenTheTextArrivesInTinyPieces() {
        val text = "**Checking the pages**\nThe criteria start from page 6.\n\nНа этой странице таблица."
        val (answer, thinking) = run(*text.chunked(3).toTypedArray())
        assertEquals("На этой странице таблица.", answer)
        assertTrue(thinking.contains("**Checking the pages**"))
    }

    @Test fun anAnswerThatDoesNotOpenWithBoldPassesAtOnce() {
        val f = ThoughtSummaryFilter()
        assertEquals("Привет", f.feed("Привет").filterIsInstance<Piece.Text>().single().text)
        assertEquals(" и всё", f.feed(" и всё").filterIsInstance<Piece.Text>().single().text)
    }

    @Test fun aBoldLeadWithAColonIsAnAnswerNotAThought() {
        val (answer, thinking) = run("**Short answer:** yes, it works.\n\nMore below.")
        assertEquals("**Short answer:** yes, it works.\n\nMore below.", answer)
        assertEquals("", thinking)
    }

    @Test fun anAnswerThatIsOnlyBoldIsNotLost() {
        val (answer, thinking) = run("**Готово**")
        assertEquals("**Готово**", answer)
        assertEquals("", thinking)
    }

    private val tool = WireTool(
        "search_in_file", "x",
        JSONObject("""{"type":"object","properties":{"name":{"type":"string"},"query":{"type":"string"}}}"""),
    )
    private val pages = WireTool(
        "read_pdf_pages", "x",
        JSONObject("""{"type":"object","properties":{"name":{"type":"string"},"first_page":{"type":"integer"}}}"""),
    )

    @Test fun aCallWrittenAsTextIsReadAsACall() {
        val text = "Let's find the file. call:default_api:search_in_file{name:Положение ип.PDF.pdf,query:критерий}\n" +
            "call:default_api:search_in_file{name:Положение ип.PDF.pdf,query:оценк}"
        val calls = TextToolCalls.find(text, listOf(tool))
        assertEquals(2, calls.size)
        val first = JSONObject(calls[0].arguments)
        assertEquals("Положение ип.PDF.pdf", first.getString("name"))
        assertEquals("критерий", first.getString("query"))
        assertEquals("оценк", JSONObject(calls[1].arguments).getString("query"))
    }

    @Test fun numbersGetTheirType() {
        val call = TextToolCalls.find("call:default_api:read_pdf_pages{name:a.pdf,first_page:6}", listOf(pages)).single()
        assertEquals(6, JSONObject(call.arguments).getInt("first_page"))
    }

    @Test fun aToolThatDoesNotExistIsNotCalled() {
        assertTrue(TextToolCalls.find("call:default_api:delete_everything{x:1}", listOf(tool)).isEmpty())
    }

    @Test fun theLinesAreTakenOutOfTheVisibleText() {
        assertEquals("Looking.", TextToolCalls.strip("Looking. call:default_api:search_in_file{name:a,query:b}"))
    }
}
