package com.apvcode.devx

import com.apvcode.devx.data.ChatDigest
import com.apvcode.devx.data.ChatSearch
import com.apvcode.devx.data.DigestMessage
import com.apvcode.devx.data.Level
import com.apvcode.devx.data.MemoryStore
import com.apvcode.devx.data.SystemPrompt
import com.apvcode.devx.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTest {
    private fun chat(id: String, title: String, vararg texts: String) = ChatDigest(
        id, title, 1_000L,
        texts.mapIndexed { i, t -> DigestMessage(if (i % 2 == 0) Role.User else Role.Assistant, t, 1_000L + i) },
    )

    private val chats = listOf(
        chat("aaaaaaaa-1", "Скрипт для бэкапа", "Напиши скрипт для резервного копирования документов", "Вот скрипт на bash..."),
        chat("bbbbbbbb-2", "Борщ", "Как приготовить борщ с говядиной?", "Нужна свёкла, капуста и мясо."),
        chat("cccccccc-3", "Kotlin coroutines", "Explain structured concurrency in Kotlin", "Structured concurrency means..."),
    )

    @Test
    fun searchFindsRussianWordsInOtherForms() {
        val hits = ChatSearch.search(chats, "документами бэкап")
        assertTrue(hits.isNotEmpty())
        assertEquals("aaaaaaaa-1", hits.first().chat.id)
    }

    @Test
    fun searchRanksBetterMatchesFirst() {
        val hits = ChatSearch.search(chats, "борщ говядина")
        assertEquals("bbbbbbbb-2", hits.first().chat.id)
        assertEquals(0, hits.first().index)
    }

    @Test
    fun searchEnglishAndNothing() {
        assertEquals("cccccccc-3", ChatSearch.search(chats, "concurrency").first().chat.id)
        assertTrue(ChatSearch.search(chats, "квантовая гравитация").isEmpty())
        assertTrue(ChatSearch.search(chats, "  ").isEmpty())
    }

    @Test
    fun snippetIsOneLineAroundTheMatch() {
        val text = "a".repeat(400) + "\n\nборщ\n\n" + "b".repeat(400)
        val s = ChatSearch.snippet(text, text.lowercase(), listOf("борщ"))
        assertTrue(s.contains("борщ"))
        assertFalse(s.contains("\n"))
        assertTrue(s.length < 400)
    }

    @Test
    fun notesAreCleanedAndDuplicatesRecognised() {
        assertEquals("Likes\ntea", MemoryStore.clean("  - Likes\n  tea "))
        // A long rule keeps its paragraphs, and a long note is no longer cut at 400 characters.
        assertEquals("One.\n\nTwo.", MemoryStore.clean("One.  \n\n\n  Two."))
        assertEquals(1199, MemoryStore.clean("word ".repeat(240)).length)
        assertTrue(MemoryStore.same("Likes tea.", "likes  TEA"))
        assertFalse(MemoryStore.same("Likes tea", "Likes coffee"))
        assertEquals(MemoryStore.MAX_NOTE_CHARS, MemoryStore.clean("x".repeat(9000)).length)
    }

    @Test
    fun theOverviewIsInThePromptToo() {
        val withOverview = SystemPrompt.forUser("", memory = "- [abc12] Likes tea", summary = "Builds an Android client; prefers short answers.")
        assertTrue(withOverview.contains("Builds an Android client"))
        assertTrue(withOverview.contains("Likes tea"))
        // Without one, nothing about an overview is said.
        assertFalse(SystemPrompt.forUser("", memory = "- [abc12] Likes tea").contains("Overview (written from"))
        // It cannot grow without limit.
        assertTrue(SystemPrompt.forUser("", memory = "x", summary = "y".repeat(9000)).length < SystemPrompt.forUser("", memory = "x").length + 4200)
    }

    @Test
    fun promptCarriesTheRulesAndTheNotes() {
        val with = SystemPrompt.forUser("Аня", "- [abc12] Любит чай")
        assertTrue(with.contains("memory_save"))
        assertTrue(with.contains("[abc12] Любит чай"))
        assertTrue(SystemPrompt.forUser("", "").contains("Nothing is saved yet."))
        assertFalse(SystemPrompt.forUser("Аня").contains("memory_save"))
    }
}

class ProjectPromptTest {
    @Test
    fun projectSectionCarriesWhatTheProjectIs() {
        val p = SystemPrompt.ProjectContext("Devx", "An Android client", "Answer briefly", "- [a1b2c] Uses Compose")
        val text = SystemPrompt.forUser("", null, p)
        assertTrue(text.contains("## Project: Devx"))
        assertTrue(text.contains("An Android client"))
        assertTrue(text.contains("Answer briefly"))
        assertTrue(text.contains("[a1b2c] Uses Compose"))
        assertTrue(text.contains("memory_save"))
        // Without the right to keep notes there is no knowledge base in the prompt.
        assertFalse(SystemPrompt.forUser("", null, SystemPrompt.ProjectContext("Devx", "", "", null)).contains("knowledge base"))
    }
}

class StrongLevelTest {
    private fun caps(vararg l: Level) =
        com.apvcode.devx.data.ReasoningCaps(l.toList(), false, com.apvcode.devx.data.Confidence.Known, { null }, { "" })

    @Test
    fun picksHighWhereThereIsAHigh() {
        assertEquals(Level.High, caps(Level.Low, Level.Normal, Level.High, Level.Max).strongLevel())
        assertEquals(Level.High, caps(Level.Off, Level.Low, Level.High).strongLevel())
    }

    @Test
    fun otherwiseTheNearestStrongOne() {
        assertEquals(Level.Normal, caps(Level.Off, Level.Low, Level.Normal).strongLevel())
        assertEquals(Level.Max, caps(Level.Off, Level.Max).strongLevel())
        assertEquals(null, caps().strongLevel())
        assertEquals(null, caps(Level.Off).strongLevel())
    }
}

class SkillHeaderTest {
    @Test
    fun plainAndQuotedValues() {
        val h = com.apvcode.devx.data.parseSkillHeader("---\nname: pdf\ndescription: \"Use it when 'a pdf' comes up.\"\nlicense: x\n---\nBody")
        assertEquals("pdf", h?.first)
        assertEquals("Use it when 'a pdf' comes up.", h?.second)
    }

    @Test
    fun blockValueIsJoined() {
        val h = com.apvcode.devx.data.parseSkillHeader("---\nname: learn\ndescription: |\n  Use this skill\n  when learning.\n---\n")
        assertEquals("Use this skill when learning.".replace(" when", "\nwhen").replace("\n", " "), h?.second?.replace("\n", " "))
    }

    @Test
    fun noUsableHeader() {
        assertEquals(null, com.apvcode.devx.data.parseSkillHeader("# no header"))
        assertEquals(null, com.apvcode.devx.data.parseSkillHeader("---\nname: x\n---\n"))
        assertEquals("a-b", com.apvcode.devx.data.skillFolderName("A B!"))
    }
}

class ReasoningChunksTest {
    @Test
    fun longThoughtsAreCutAtParagraphs() {
        val para = "x".repeat(300)
        val text = (1..20).joinToString("\n\n") { para }
        val chunks = com.apvcode.devx.ui.screens.reasoningChunks(text)
        assertTrue(chunks.size > 3)
        assertTrue(chunks.all { it.length < 1_500 })
        assertEquals(20, chunks.sumOf { c -> c.split("\n\n").size })
    }

    @Test
    fun aFencedBlockIsNeverCut() {
        val code = "```\n" + (1..400).joinToString("\n") { "line $it" } + "\n```"
        val text = "intro\n\n$code\n\noutro"
        val chunks = com.apvcode.devx.ui.screens.reasoningChunks(text, soft = 50, hard = 100)
        assertTrue(chunks.any { it.contains("line 1\n") && it.contains("line 400") })
    }

    @Test
    fun earlierPiecesStayWhileTheTextGrows() {
        val a = (1..30).joinToString("\n\n") { "paragraph $it " + "y".repeat(200) }
        val before = com.apvcode.devx.ui.screens.reasoningChunks(a)
        val after = com.apvcode.devx.ui.screens.reasoningChunks(a + "\n\nmore words here")
        assertEquals(before.dropLast(1), after.take(before.size - 1))
    }
}

class NotificationTextTest {
    private fun plain(s: String) = com.apvcode.devx.data.NotificationText.plain(s)

    @Test
    fun marksAreRemoved() {
        assertEquals("Hello world and more", plain("# Hello **world** and *more*"))
        assertEquals("Читай документацию по ссылке", plain("Читай __документацию__ по [ссылке](https://example.com/a)"))
        assertEquals("first. second", plain("- first\n- second"))
        assertEquals("quoted words", plain("> quoted `words`"))
    }

    @Test
    fun codeCardsAndTablesAreLeftOut() {
        val text = "Here you go.\n\n```devx-card\n{\"type\":\"card\"}\n```\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\nDone."
        assertEquals("Here you go. a b. 1 2. Done.", plain(text))
        assertEquals("", plain("```devx-file\nabc\n```"))
        assertEquals("Intro", plain("Intro\n```kotlin\nval x = 1"))
    }

    @Test
    fun snakeCaseAndMathSurvive() {
        assertEquals("use my_var_name here", plain("use my_var_name here"))
        assertEquals("x2 + 1", plain("\$x2 + 1\$"))
    }

    @Test
    fun shortCutsBetweenWords() {
        val n = com.apvcode.devx.data.NotificationText
        assertEquals("one two", n.short("one two", 20))
        assertEquals("one two three…", n.short("one two three four five six", 15))
    }
}
