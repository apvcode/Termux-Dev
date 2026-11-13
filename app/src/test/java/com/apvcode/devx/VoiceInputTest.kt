package com.apvcode.devx

import com.apvcode.devx.data.VoiceInput
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceInputTest {
    @Test fun sentencesAreJoinedWithOneSpace() {
        assertEquals("привет мир", VoiceInput.join("привет", "мир"))
        assertEquals("привет", VoiceInput.join("привет", ""))
        assertEquals("мир", VoiceInput.join("  ", " мир "))
        assertEquals("", VoiceInput.join("", ""))
    }

    @Test fun loudnessIsKeptBetweenZeroAndOne() {
        assertEquals(0f, VoiceInput.level(-20f), 0.0001f)
        assertEquals(1f, VoiceInput.level(30f), 0.0001f)
        assertEquals(0.5f, VoiceInput.level(4f), 0.0001f)
    }

    @Test fun theNameOfALanguageIsItsShortCode() {
        assertEquals("RU", VoiceInput.shortName("ru-RU"))
        assertEquals("EN", VoiceInput.shortName("en"))
    }

    @Test fun everyOfferedLanguageHasATagAndAName() {
        assertEquals(VoiceInput.LANGUAGES.size, VoiceInput.LANGUAGES.map { it.first }.distinct().size)
        assertEquals("Русский", VoiceInput.LANGUAGES.first { it.first == "ru-RU" }.second)
    }
}

class VoiceLanguageNamesTest {
    @org.junit.Test fun aChoiceOfLanguageHasAName() {
        org.junit.Assert.assertEquals("Как в приложении", VoiceInput.nameOf("", "Как в приложении", "Авто"))
        org.junit.Assert.assertEquals("Авто", VoiceInput.nameOf(VoiceInput.AUTO, "x", "Авто"))
        org.junit.Assert.assertEquals("Русский", VoiceInput.nameOf("ru-RU", "x", "y"))
        org.junit.Assert.assertEquals("AUTO", VoiceInput.shortName(VoiceInput.AUTO))
    }
}
