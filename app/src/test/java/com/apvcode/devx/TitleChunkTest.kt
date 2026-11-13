package com.apvcode.devx

import com.apvcode.devx.data.TitleChunk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleChunkTest {
    @Test
    fun aBoldTitleGluedToTheTextIsAThinkingSummary() {
        assertTrue(TitleChunk.looksLikeSummary("**Inspecting the base source**", glued = true))
        assertTrue(TitleChunk.looksLikeSummary("**Проверяю поддержку тернарного оператора парсером**", glued = true))
    }

    @Test
    fun anAnswerThatOpensWithBoldWordsIsLeftAlone() {
        // At the start of a paragraph (not glued) a bold phrase is ordinary text.
        assertFalse(TitleChunk.looksLikeSummary("**Готово**", glued = false))
        // Part of a sentence is not a title.
        assertFalse(TitleChunk.looksLikeSummary("**Done** and then more", glued = true))
        assertFalse(TitleChunk.looksLikeSummary("plain words", glued = true))
        assertFalse(TitleChunk.looksLikeSummary("**", glued = true))
    }
}
