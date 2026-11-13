package com.apvcode.devx

import com.apvcode.devx.ui.markdown.Md
import com.apvcode.devx.ui.markdown.MarkdownParser
import com.apvcode.devx.ui.markdown.MarkdownSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownSegmentsTest {
    private val filler = "word ".repeat(90)

    /** What the blocks say, in order: the same however the text was cut. */
    private fun shape(blocks: List<Md>): List<String> = blocks.map {
        when (it) {
            is Md.Para -> "P:" + it.rich.text.text
            is Md.Head -> "H${it.level}:" + it.rich.text.text
            is Md.Item -> "I${it.depth}${it.marker}:" + it.rich.text.text
            is Md.Quote -> "Q"
            is Md.CodeBlock -> "C:" + it.code
            is Md.Table -> "T"
            is Md.MathBlock -> "M"
            Md.Rule -> "R"
        }
    }

    private fun checkSame(text: String) {
        val pieces = MarkdownSegments.split(text)
        assertEquals("nothing is lost", text, pieces.joinToString(""))
        val whole = shape(MarkdownParser.parse(text).blocks)
        val parts = pieces.flatMap { shape(MarkdownParser.parse(it).blocks) }
        assertEquals(whole, parts)
    }

    @Test fun shortTextIsOnePiece() {
        assertEquals(1, MarkdownSegments.split("Hello\n\nworld").size)
    }

    @Test fun paragraphsAreCutAndReadTheSameAsTheWhole() {
        val text = "# Title\n\n$filler\n\n$filler\n\nLast words here."
        assertTrue(MarkdownSegments.split(text).size >= 3)
        checkSame(text)
    }

    @Test fun aListWithBlankLinesBetweenItemsIsNotCut() {
        val text = "$filler\n\n1. first\n\n2. second\n\n3. third\n\n$filler\n\nEnd."
        checkSame(text)
        // The items stay in one piece, so the numbers go on.
        val joined = MarkdownSegments.split(text).first { it.contains("1. first") }
        assertTrue(joined.contains("2. second") && joined.contains("3. third"))
    }

    @Test fun anIndentedContinuationStaysWithItsItem() {
        val text = "$filler\n\n- item\n\n  more of the item\n\n$filler\n\nEnd."
        checkSame(text)
    }

    @Test fun aFencedBlockIsNeverCut() {
        val text = "$filler\n\n```kotlin\nfun a() {}\n\nfun b() {}\n```\n\n$filler\n\nEnd."
        val pieces = MarkdownSegments.split(text)
        assertTrue(pieces.any { it.contains("fun a()") && it.contains("fun b()") })
        checkSame(text)
    }

    @Test fun aDisplayedFormulaIsNeverCut() {
        val text = "$filler\n\n$$\na = b\n\nc = d\n$$\n\n$filler\n\nEnd."
        val pieces = MarkdownSegments.split(text)
        assertTrue(pieces.any { it.contains("a = b") && it.contains("c = d") })
    }

    @Test fun tablesAndQuotesStayWhole() {
        val text = "$filler\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n> quote\n\n$filler\n\nEnd."
        checkSame(text)
    }

    @Test fun pieceBeforeTheLastDoNotChangeWhenTheEndGrows() {
        val base = "# Title\n\n$filler\n\n$filler\n\nThe last paragraph"
        val a = MarkdownSegments.split(base)
        val b = MarkdownSegments.split(base + " keeps growing and growing")
        assertEquals(a.dropLast(1), b.dropLast(1))
    }
}
