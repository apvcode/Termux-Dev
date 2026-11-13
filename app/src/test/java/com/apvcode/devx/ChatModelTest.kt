package com.apvcode.devx

import com.apvcode.devx.data.Chat
import com.apvcode.devx.data.ErrorKind
import com.apvcode.devx.data.ModelRef
import com.apvcode.devx.data.Msg
import com.apvcode.devx.data.chatFromJson
import com.apvcode.devx.data.toJson
import com.apvcode.devx.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatModelTest {
    @Test
    fun chatKeepsItsOwnModel() {
        val a = Chat("a", "A", null, 1).also { it.model = ModelRef("p1", "gpt-x") }
        val b = Chat("b", "B", null, 1).also { it.model = ModelRef("p2", "glm-y") }
        assertEquals(ModelRef("p1", "gpt-x"), chatFromJson(a.toJson()).model)
        assertEquals(ModelRef("p2", "glm-y"), chatFromJson(b.toJson()).model)
        assertNull(chatFromJson(Chat("c", "C", null, 1).toJson()).model)
    }

    @Test
    fun cutOffAnswerIsMarkedInterrupted() {
        val chat = Chat("a", "A", null, 1)
        chat.messages += Msg("m", null, Role.Assistant, "half", 1).also { it.inflight = true }
        val back = chatFromJson(chat.toJson()).messages.single()
        assertEquals(ErrorKind.Interrupted, back.error)
        assertEquals("half", back.text)
    }

    @Test
    fun tokenCountsSurviveSaving() {
        val chat = Chat("a", "A", null, 1)
        chat.messages += Msg("m", null, Role.Assistant, "done", 1).also { it.tokensIn = 3000; it.tokensOut = 120; it.contextTokens = 1620 }
        val back = chatFromJson(chat.toJson()).messages.single()
        assertEquals(3000, back.tokensIn)
        assertEquals(120, back.tokensOut)
        assertEquals(1620, back.contextTokens)
    }

    @Test
    fun aShortenedChatKeepsItsSummary() {
        val chat = Chat("a", "A", null, 1)
        chat.summary = "The person is building an app."
        chat.summaryUpTo = "m5"
        val back = chatFromJson(chat.toJson())
        assertEquals("The person is building an app.", back.summary)
        assertEquals("m5", back.summaryUpTo)
        assertNull(chatFromJson(Chat("b", "B", null, 1).toJson()).summaryUpTo)
    }
}
