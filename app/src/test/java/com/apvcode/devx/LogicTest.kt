package com.apvcode.devx

import com.apvcode.devx.data.Chat
import com.apvcode.devx.data.Confidence
import com.apvcode.devx.data.Level
import com.apvcode.devx.data.Msg
import com.apvcode.devx.data.ProviderKind
import com.apvcode.devx.data.activePath
import com.apvcode.devx.data.parseBlocks
import com.apvcode.devx.data.resolveReasoning
import com.apvcode.devx.data.select
import com.apvcode.devx.data.siblings
import com.apvcode.devx.model.Block
import com.apvcode.devx.model.Role
import com.apvcode.devx.ui.markdown.MarkdownParser
import com.apvcode.devx.ui.markdown.MathExtract
import com.apvcode.devx.ui.markdown.Md
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MathExtractTest {
    @Test fun inlineAndDisplay() {
        val r = MathExtract.extract("Energy ${'$'}E=mc^2${'$'} and ${'$'}${'$'}\\int_0^1 x\\,dx${'$'}${'$'} done")
        assertEquals(2, r.math.size)
        // Numbered by pass (display first), but each token in the text carries its own number.
        assertEquals("E=mc^2", r.math.single { !it.display }.latex)
        assertTrue(r.math.single { it.display }.latex.startsWith("\\int"))
        assertEquals(2, MathExtract.token.findAll(r.text).count())
        assertFalse(r.text.contains("mc^2"))
    }

    @Test fun bracketsAndParens() {
        val r = MathExtract.extract("a \\(x_1\\) b \\[y^2\\] c")
        assertEquals(setOf(false, true), r.math.map { it.display }.toSet())
        assertEquals(2, MathExtract.token.findAll(r.text).count())
    }

    @Test fun moneyIsNotMath() {
        assertTrue(MathExtract.extract("It costs ${'$'}5 and ${'$'}10 in total").math.isEmpty())
    }

    @Test fun codeIsLeftAlone() {
        val r = MathExtract.extract("use `${'$'}x${'$'}` here\n```\n${'$'}${'$'}y${'$'}${'$'}\n```\n")
        assertTrue(r.math.isEmpty())
        assertTrue(r.text.contains("`${'$'}x${'$'}`"))
    }
}

class MarkdownParserTest {
    @Test fun structure() {
        val doc = MarkdownParser.parse(
            "# Title\n\nText with **bold** and ~~gone~~.\n\n- one\n  - nested\n- two\n\n1. first\n2. second\n\n> quote\n\n---\n",
        )
        val kinds = doc.blocks.map { it::class.simpleName }
        assertEquals(listOf("Head", "Para", "Item", "Item", "Item", "Item", "Item", "Quote", "Rule"), kinds)
        val nested = doc.blocks.filterIsInstance<Md.Item>()[1]
        assertEquals(1, nested.depth)
        assertEquals("2.", doc.blocks.filterIsInstance<Md.Item>()[4].marker)
    }

    @Test fun tableAndDisplayMath() {
        val doc = MarkdownParser.parse("| a | b |\n|---|--:|\n| 1 | 2 |\n\n${'$'}${'$'}x^2${'$'}${'$'}\n")
        val table = doc.blocks[0] as Md.Table
        assertEquals(2, table.header.size)
        assertEquals(1, table.rows.size)
        assertTrue(doc.blocks[1] is Md.MathBlock)
    }

    @Test fun inlineMathBecomesASlot() {
        val doc = MarkdownParser.parse("Value ${'$'}a_1${'$'} is small")
        val para = doc.blocks[0] as Md.Para
        assertEquals(listOf(0), para.rich.mathIds)
    }
}

class ParseBlocksTest {
    @Test fun cardsPicturesAndCode() {
        val text = "Intro\n```devx-card\n{\"type\":\"card\"}\n```\nMiddle\n```svg\n<svg viewBox=\"0 0 1 1\"></svg>\n```\n```kotlin\nval x = 1\n```\nEnd"
        val b = parseBlocks(text)
        assertEquals(listOf("Text", "Ui", "Text", "Svg", "Code", "Text"), b.map { it::class.simpleName })
        assertEquals("kotlin", (b[4] as Block.Code).language)
    }

    @Test fun mapsPicturesAndChartsStandWhereTheyAreWritten() {
        val text = "Here:\n```devx-map\n{\"markers\":[{\"lat\":55.7,\"lon\":37.6}]}\n```\nAnd my photo:\n```devx-image\n{\"src\":\"gallery:5\"}\n```\nThen:\n```devx-chart\n{\"kind\":\"bar\"}\n```\nDone"
        val b = parseBlocks(text)
        assertEquals(listOf("Text", "Map", "Text", "Image", "Text", "Chart", "Text"), b.map { it::class.simpleName })
        assertEquals("{\"src\":\"gallery:5\"}", (b[3] as Block.Image).json)
        // Still being written: shown as "being built", like a card.
        assertEquals(listOf("Text", "Building"), parseBlocks("x\n```devx-map\n{\"mark").map { if (it is Block.Building) "Building" else it::class.simpleName })
    }

    @Test fun quickRepliesAreABlockAtTheEnd() {
        val b = parseBlocks("Shall I book it?\n```devx-reply\n[\"Yes\",\"No\"]\n```")
        assertEquals(listOf("Text", "Replies"), b.map { it::class.simpleName })
        assertEquals("[\"Yes\",\"No\"]", (b[1] as Block.Replies).json)
    }

    @Test fun unfinishedCardIsBuilding() {
        val b = parseBlocks("Here:\n```devx-card\n{\"type\":\"ca")
        assertEquals(listOf("Text", "Building"), b.map { if (it is Block.Building) "Building" else it::class.simpleName })
    }
}

class ReasoningTest {
    @Test fun o3HasThreeLevelsAndCannotBeOff() {
        val caps = resolveReasoning("o3-mini", "https://api.openai.com/v1", ProviderKind.OpenAI)
        assertEquals(listOf(Level.Low, Level.Normal, Level.High), caps.levels)
        assertTrue(caps.alwaysOn)
        assertEquals("medium", caps.build(Level.Normal)!!.getString("reasoning_effort"))
    }

    @Test fun claudeUsesBudgetsAndBumpsMaxTokens() {
        val caps = resolveReasoning("claude-sonnet-4-5", "https://api.anthropic.com", ProviderKind.Anthropic)
        assertTrue(Level.Off in caps.levels)
        assertNull(caps.build(Level.Off))
        val params = caps.build(Level.High)!!
        assertEquals(16000, params.getJSONObject("thinking").getInt("budget_tokens"))
        assertEquals(32000, params.getInt("max_tokens"))
    }

    @Test fun openRouterWrapsEffort() {
        val caps = resolveReasoning("openai/gpt-5", "https://openrouter.ai/api/v1", ProviderKind.OpenAI)
        assertEquals("high", caps.build(Level.High)!!.getJSONObject("reasoning").getString("effort"))
    }

    @Test fun alwaysOnAndUnknownHaveNoLevels() {
        val r1 = resolveReasoning("deepseek-reasoner", "https://api.deepseek.com", ProviderKind.OpenAI)
        assertTrue(r1.levels.isEmpty() && r1.alwaysOn)
        val unknown = resolveReasoning("my-local-model", "http://localhost:11434/v1", ProviderKind.OpenAI)
        assertTrue(unknown.levels.isEmpty() && !unknown.alwaysOn)
        assertEquals(Confidence.None, unknown.confidence)
    }
}

class ChatTreeTest {
    private fun msg(id: String, parent: String?, role: Role, at: Long) = Msg(id, parent, role, id, at)

    @Test fun editingCreatesABranchAndKeepsTheOldOne() {
        val chat = Chat("c", "t", null, 0)
        chat.messages += msg("u1", null, Role.User, 1)
        chat.messages += msg("a1", "u1", Role.Assistant, 2)
        chat.messages += msg("u2", "a1", Role.User, 3)
        chat.messages += msg("a2", "u2", Role.Assistant, 4)
        assertEquals(listOf("u1", "a1", "u2", "a2"), chat.activePath().map { it.id })

        val edited = msg("u2b", "a1", Role.User, 5)
        chat.messages += edited
        chat.select(edited)
        assertEquals(listOf("u1", "a1", "u2b"), chat.activePath().map { it.id })
        assertEquals(2, chat.siblings(edited).size)

        chat.select(chat.messages.first { it.id == "u2" })
        assertEquals(listOf("u1", "a1", "u2", "a2"), chat.activePath().map { it.id })
    }
}
