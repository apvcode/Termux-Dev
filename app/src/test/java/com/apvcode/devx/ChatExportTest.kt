package com.apvcode.devx

import com.apvcode.devx.data.Chat
import com.apvcode.devx.data.ChatExport
import com.apvcode.devx.data.Msg
import com.apvcode.devx.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatExportTest {
    private fun chat(): Chat {
        val c = Chat("a", "Plan: the trip?", null, 1)
        c.messages += Msg("1", null, Role.User, "How do I get there?", 1)
        c.messages += Msg("2", "1", Role.Assistant, "Take the **train**:\n\n- leave at noon\n- bring `cash`\n\n```\nrun --fast\n```", 2)
        return c
    }

    @Test fun markdownKeepsTheTextAndNamesTheSpeakers() {
        val md = ChatExport.markdown(chat(), "Ilya", "Devx", "Nov 13, 2026")
        assertTrue(md.startsWith("# Plan: the trip?\n"))
        assertTrue(md.contains("### Ilya\n\nHow do I get there?"))
        assertTrue(md.contains("### Devx\n\nTake the **train**"))
        assertTrue(md.contains("```\nrun --fast\n```"))
        assertTrue(md.endsWith("\n") && !md.endsWith("\n\n"))
    }

    @Test fun theMarksAreTakenOutForThePages() {
        assertEquals("Take the train now", ChatExport.plain("Take the **train** now"))
        assertEquals("a link here", ChatExport.plain("a [link](http://x.y) here"))
        assertEquals("run cash", ChatExport.plain("run `cash`"))
        assertEquals("2 * 3 * 4", ChatExport.plain("2 * 3 * 4"))
    }

    @Test fun blocksSplitCodeFromProse() {
        val kinds = ChatExport.blocks(chat(), "Me", "Devx").map { it.kind.name }
        assertEquals(listOf("Heading", "Speaker", "Body", "Speaker", "Body", "Body", "Body", "Code"), kinds)
        assertEquals("•  leave at noon", ChatExport.blocks(chat(), "Me", "Devx")[5].text)
    }

    @Test fun theFileNameHasNoForbiddenSigns() {
        assertEquals("Plan the trip.md", ChatExport.fileName("Plan: the trip?", "md"))
        assertFalse(ChatExport.fileName("a/b\\c", "pdf").contains('/'))
        assertEquals("chat.pdf", ChatExport.fileName("   ", "pdf"))
    }
}
