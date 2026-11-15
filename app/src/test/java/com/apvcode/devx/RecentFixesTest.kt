package com.apvcode.devx

import com.apvcode.devx.data.ArtifactStore
import com.apvcode.devx.data.SearchApi
import com.apvcode.devx.data.SearchText
import com.apvcode.devx.data.SystemPrompt
import com.apvcode.devx.ui.chat.UiNode
import com.apvcode.devx.ui.chat.UiParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The places that broke before: cards, searching, file names, the prompt. */
class RecentFixesTest {
    // ---- cards ----

    private fun fieldsOf(node: UiNode?): List<UiNode> = when (node) {
        is UiNode.Card -> node.children.flatMap { fieldsOf(it) }
        is UiNode.Section -> node.children.flatMap { fieldsOf(it) }
        null -> emptyList()
        else -> listOf(node)
    }

    @Test fun cardKeepsLabelHintAndExample() {
        val card = UiParser.parse("""{"type":"card","children":[{"type":"input","id":"a","label":"When do you sleep?","hint":"The real time","placeholder":"e.g. 1 am","required":true}]}""")
        val input = fieldsOf(card).single() as UiNode.Input
        assertEquals("When do you sleep?", input.label)
        assertEquals("The real time", input.hint)
        assertEquals("e.g. 1 am", input.placeholder)
        assertTrue(input.required)
    }

    @Test fun fieldWithoutIdIsNotDropped() {
        val card = UiParser.parse("""{"type":"card","children":[{"type":"input","label":"Name"},{"type":"choice","label":"Pick","options":["a","b"]}]}""")
        assertEquals(2, fieldsOf(card).size)
    }

    @Test fun otherNamesForTheSameThingAreUnderstood() {
        val card = UiParser.parse(
            """{"type":"card","children":[{"type":"textarea","id":"t","question":"Tell me"},{"type":"checkboxes","id":"c","options":["x","y"]},{"type":"radio","id":"r","options":[{"label":"One"},{"value":"Two"}]}]}""",
        )
        val f = fieldsOf(card)
        assertTrue((f[0] as UiNode.Input).multiline)
        assertEquals("Tell me", (f[0] as UiNode.Input).label)
        assertTrue((f[1] as UiNode.Choice).multiple)
        assertEquals(listOf("One", "Two"), (f[2] as UiNode.Choice).options)
    }

    @Test fun sliderAndToggleAreRead() {
        val card = UiParser.parse("""{"type":"card","children":[{"type":"slider","id":"s","label":"Hours","min":3,"max":12,"step":0.5,"unit":"h","value":7},{"type":"toggle","id":"t","label":"Sport?","value":true}]}""")
        val f = fieldsOf(card)
        val slider = f[0] as UiNode.Slider
        assertEquals(3f, slider.min, 0f)
        assertEquals(7f, slider.value, 0f)
        assertEquals("h", slider.unit)
        assertTrue((f[1] as UiNode.Toggle).value)
    }

    @Test fun brokenJsonGivesNothing() {
        assertNull(UiParser.parse("{not json"))
    }

    // ---- searching ----

    @Test fun queryIsTidied() {
        assertEquals("500k 500,000 Grok context window xAI", SearchText.tidy("\"500k\" OR \"500,000\" Grok context window xAI"))
        assertEquals(12, SearchText.tidy("a b c d e f g h i j k l m n o p").split(" ").size)
    }

    @Test fun termsKeepNumbersAndDropFiller() {
        val terms = SearchText.terms("why Grok 4.7 context window")
        assertTrue("grok" in terms)
        assertTrue("4.7" in terms)
        assertFalse("why" in terms)
    }

    @Test fun bestPassageFindsTheTalkingStretch() {
        val page = "Cookies and privacy settings are managed here for the whole site.\n" +
            "Home page navigation and many other unrelated words are written in this line.\n" +
            "The Grok 4 context window is 256000 tokens according to the documentation of the model.\n" +
            "Another line about something else entirely that has nothing to do with it at all."
        val passage = SearchText.bestPassage(page, SearchText.terms("Grok context window"))
        assertNotNull(passage)
        assertTrue(passage!!.contains("256000"))
        assertNull(SearchText.bestPassage("A line long enough but about nothing in particular.", SearchText.terms("Grok context window")))
    }

    @Test fun searchKeysAreToldApart() {
        assertEquals(SearchApi.Kind.Tavily, SearchApi.kindOf("tvly-abc"))
        assertEquals(SearchApi.Kind.Brave, SearchApi.kindOf("BSAabc"))
        assertNull(SearchApi.kindOf("sk-abc"))
    }

    @Test fun searchAnswersAreParsed() {
        val tavily = SearchApi.parseTavily("""{"results":[{"title":"T","url":"https://a.b","content":"the   matching  passage"}]}""")
        assertEquals("https://a.b", tavily.single().url)
        assertEquals("the matching passage", tavily.single().snippet)
        val brave = SearchApi.parseBrave("""{"web":{"results":[{"title":"B","url":"https://c.d","description":"a <strong>bold</strong> hit"}]}}""")
        assertEquals("a bold hit", brave.single().snippet)
        assertTrue(SearchApi.parseBrave("garbage").isEmpty())
    }

    // ---- file names ----

    @Test fun nameKeepsTheKindOfTheFile() {
        assertEquals("Resume.pdf", ArtifactStore.withExtension("Resume", "out.pdf"))
        assertEquals("Resume.pdf", ArtifactStore.withExtension("Resume.pdf", "out.pdf"))
        assertEquals("Report v2 final.docx", ArtifactStore.withExtension("Report v2 final", "x.docx"))
        assertEquals("notes", ArtifactStore.withExtension("notes", "noextension"))
    }

    // ---- the prompt ----

    @Test fun guidesAsToolMakeThePromptShorter() {
        val full = SystemPrompt.base(false)
        val short = SystemPrompt.base(true)
        assertTrue(short.length < full.length - 8_000)
        assertTrue(short.contains("guide"))
        assertTrue(full.contains("devx-card"))
        assertTrue(SystemPrompt.guide("cards").contains("\"label\""))
        assertTrue(SystemPrompt.guide("blocks").contains("devx-map"))
        assertTrue(SystemPrompt.guide("nonsense").startsWith("Unknown topic"))
    }
}
