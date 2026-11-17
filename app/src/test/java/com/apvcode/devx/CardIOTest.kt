package com.apvcode.devx

import com.apvcode.devx.data.CardIO
import com.apvcode.devx.data.Flashcard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardIOTest {
    @Test fun tabSeparatedWithAnkiHeader() {
        val cards = CardIO.parseText("#separator:tab\n#html:false\n#tags column:3\nWhat is 2+2?\t4\tarithmetic\nCapital of France\tParis\tgeo_europe leech\n")
        assertEquals(2, cards.size)
        assertEquals("What is 2+2?", cards[0].front); assertEquals("4", cards[0].back); assertEquals("arithmetic", cards[0].topic)
        assertEquals("geo europe", cards[1].topic)
    }

    @Test fun commaAndSemicolonAreGuessedAndAHeaderRowIsSkipped() {
        val a = CardIO.parseText("front,back,topic\nHello,Привет,words\n\"Hi, there\",\"Say \"\"hi\"\"\",\n")
        assertEquals(2, a.size)
        assertEquals("Hi, there", a[1].front); assertEquals("Say \"hi\"", a[1].back)
        val b = CardIO.parseText("Вопрос;Ответ\nстолица;Москва\n")
        assertEquals(1, b.size); assertEquals("Москва", b[0].back)
    }

    @Test fun quotedFieldsCanHoldLineBreaksAndBlankOrShortRowsAreDropped() {
        val cards = CardIO.parseText("﻿\"two\nlines\"\tanswer\n\nonlyone\n\t\n")
        assertEquals(1, cards.size)
        assertEquals("two\nlines", cards[0].front)
    }

    @Test fun ankiMarkupBecomesPlainText() {
        assertEquals("a\nb & c < d", CardIO.fromHtml("<div>a</div><b>b</b> &amp; c &lt; d[sound:x.mp3]"))
        val n = CardIO.fromNote("<b>Q</b><br>x\u001FA &nbsp;1\u001F", "chem_basics")!!
        assertEquals("Q\nx", n.draft.front); assertEquals("A  1".replace("  ", " "), n.draft.back.replace("  ", " ")); assertEquals("chem basics", n.draft.topic)
    }

    @Test fun aClozeBecomesAQuestionWithAGap() {
        val n = CardIO.fromNote("The {{c1::mitochondria}} makes {{c2::energy::what}}\u001Fextra", "")!!
        assertEquals("The […] makes […]", n.draft.front)
        assertTrue(n.draft.back.startsWith("The mitochondria makes energy"))
        assertTrue(n.draft.back.contains("extra"))
    }

    @Test fun aPictureOfANoteIsNamed() {
        val n = CardIO.fromNote("<img src=\"cat.jpg\">\u001FA cat", "")!!
        assertEquals("cat.jpg", n.image); assertEquals("?", n.draft.front)
        assertEquals(null, CardIO.fromNote("\u001F", ""))
    }

    @Test fun exportReadsBackTheSame() {
        val cards = listOf(
            Flashcard("1", "Tab\there", "Line\nbreak", 0, topic = "my topic"),
            Flashcard("2", "Say \"hi\"", "ok", 0),
        )
        val back = CardIO.parseText(CardIO.toTsv(cards))
        assertEquals(2, back.size)
        assertEquals("Tab\there", back[0].front); assertEquals("Line\nbreak", back[0].back); assertEquals("my topic", back[0].topic)
        assertEquals("Say \"hi\"", back[1].front)
        val csv = CardIO.parseText(CardIO.toCsv(cards))
        assertEquals(2, csv.size); assertEquals("Line\nbreak", csv[0].back)
    }
}

class ApkgExportTest {
    private val export = com.apvcode.devx.data.ApkgExport

    @org.junit.Test fun aFieldIsMadeSafeForAnkisMarkupAndKeepsItsLineBreaks() {
        org.junit.Assert.assertEquals("a &lt; b &amp; c<br>next", export.html("a < b & c\nnext"))
        // and reads back as it was written
        org.junit.Assert.assertEquals("a < b & c\nnext", com.apvcode.devx.data.CardIO.fromHtml(export.html("a < b & c\nnext")))
    }

    @org.junit.Test fun theTopicBecomesATagWithoutSpaces() {
        org.junit.Assert.assertEquals("Past_tense", export.tagOf("  Past   tense "))
        org.junit.Assert.assertEquals("", export.tagOf(" "))
    }

    @org.junit.Test fun theCheckNumberIsTheFirstFourBytesOfTheSha1() {
        // sha1("hello") = aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d
        org.junit.Assert.assertEquals(0xaaf4c61dL, export.checksum("hello"))
        org.junit.Assert.assertEquals(export.checksum("hello"), export.checksum("<b>hello</b>"))
    }

    @org.junit.Test fun onlyCardsWithBothSidesGoIn() {
        val cards = listOf(
            com.apvcode.devx.data.Flashcard("1", "q", "a", 1L),
            com.apvcode.devx.data.Flashcard("2", "", "a", 1L),
            com.apvcode.devx.data.Flashcard("3", "q", "  ", 1L),
        )
        org.junit.Assert.assertEquals(listOf("1"), export.usable(cards).map { it.id })
    }
}
