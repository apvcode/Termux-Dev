package com.apvcode.devx.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechTextTest {
    /** All the pieces that would be said for a finished text. */
    private fun pieces(raw: String): List<String> {
        val out = ArrayList<String>()
        var at = 0
        while (true) {
            val (piece, next) = SpeechText.next(raw, at, final = true) ?: break
            at = next
            if (piece.isNotEmpty()) out += piece
            if (at >= raw.length) break
        }
        return out
    }

    @Test
    fun sentencesAreSpokenOneByOne() {
        assertEquals(listOf("Привет!", "Это тест.", "Всё хорошо?"), pieces("Привет! Это тест. Всё хорошо?"))
    }

    @Test
    fun anUnfinishedSentenceWaits() {
        assertNull(SpeechText.next("Привет! Это те", 7, final = false))
        assertEquals("Привет!" to 7, SpeechText.next("Привет! Это те", 0, final = false))
    }

    @Test
    fun markdownIsNotRead() {
        assertEquals(listOf("Важно: сначала это.", "Потом то."), pieces("**Важно**: сначала `это`.\n- Потом [то](https://example.com)."))
        assertEquals(listOf("Заголовок", "Текст."), pieces("# Заголовок\nТекст."))
    }

    @Test
    fun codeBlocksAreSkipped() {
        assertEquals(listOf("Вот код:", "Готово."), pieces("Вот код:\n```kotlin\nval a = 1\n```\nГотово."))
    }

    @Test
    fun anOpenCodeBlockWaitsForItsEnd() {
        assertNull(SpeechText.next("```py\nprint(1)", 0, final = false))
    }

    @Test
    fun listNumbersAndEmojiAreDropped() {
        assertEquals(listOf("Первое.", "Второе."), pieces("1. Первое.\n2. Второе."))
        assertEquals(listOf("Готово!"), pieces("Готово! 🎉"))
    }

    @Test
    fun displayKeepsParagraphsAndDropsCode() {
        assertEquals("Первый абзац.\n\nВторой.", SpeechText.display("**Первый** абзац.\n\n```x\ncode\n```\nВторой."))
    }
}
