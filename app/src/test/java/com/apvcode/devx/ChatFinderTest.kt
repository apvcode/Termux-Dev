package com.apvcode.devx

import com.apvcode.devx.data.Chat
import com.apvcode.devx.data.ChatFinder
import com.apvcode.devx.data.Msg
import com.apvcode.devx.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFinderTest {
    private fun chat(id: String, title: String, updated: Long, vararg texts: String, locked: Boolean = false): Chat {
        val c = Chat(id, title, null, 1)
        c.updatedAt = updated
        c.locked = locked
        texts.forEachIndexed { i, t -> c.messages += Msg("$id-$i", if (i == 0) null else "$id-${i - 1}", if (i % 2 == 0) Role.User else Role.Assistant, t, 100L + i) }
        return c
    }

    @Test fun wordsInsideMessagesAreFoundWithAHighlightedSpan() {
        val hits = ChatFinder.run(listOf(chat("a", "Trip", 5, "hello", "The train leaves at noon from the station")), "train")
        val hit = hits.single()
        assertEquals("a-1", hit.messageId)
        assertEquals("train", hit.snippet!!.substring(hit.matchStart, hit.matchEnd))
    }

    @Test fun titleMatchesComeFirstThenTheNewestChats() {
        val list = listOf(
            chat("old", "Cooking", 1, "I like pasta"),
            chat("new", "Pasta night", 2, "x"),
            chat("newer", "Other", 9, "pasta again"),
        )
        assertEquals(listOf("new", "newer", "old"), ChatFinder.run(list, "pasta").map { it.chatId })
    }

    @Test fun theContentsOfALockedChatAreNeverRead() {
        val locked = chat("l", "Secret", 5, "the password is swordfish", locked = true)
        assertTrue(ChatFinder.run(listOf(locked), "swordfish").isEmpty())
        val byTitle = ChatFinder.run(listOf(locked), "secret").single()
        assertNull(byTitle.snippet)
    }

    @Test fun theCountIsTheNumberOfMessagesWithTheWords() {
        val hit = ChatFinder.run(listOf(chat("a", "T", 1, "cat", "a cat sat", "no", "cat again")), "cat").single()
        assertEquals(3, hit.count)
    }

    @Test fun aLongLineIsCutWithDots() {
        val text = "x".repeat(200) + " needle " + "y".repeat(200)
        val cut = ChatFinder.snippet(text, "needle")!!
        assertTrue(cut.text.startsWith("…") && cut.text.endsWith("…"))
        assertEquals("needle", cut.text.substring(cut.start, cut.end))
    }
}
