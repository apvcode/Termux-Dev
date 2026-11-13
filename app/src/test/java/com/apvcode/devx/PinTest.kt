package com.apvcode.devx

import com.apvcode.devx.data.PinHasher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinTest {
    @Test
    fun aCodeIsKeptAsAHashAndOnlyTheRightOneMatches() {
        val stored = PinHasher.encode("123456", iterations = 1000)
        assertFalse(stored.contains("123456"))
        assertTrue(PinHasher.matches("123456", stored))
        assertFalse(PinHasher.matches("123457", stored))
        assertFalse(PinHasher.matches("", stored))
        assertFalse(PinHasher.matches("123456", "garbage"))
    }

    @Test
    fun theSameCodeGivesDifferentStoredValues() {
        assertNotEquals(PinHasher.encode("111111", iterations = 1000), PinHasher.encode("111111", iterations = 1000))
    }

    @Test
    fun tooManyWrongCodesShutTheKeypadForLongerAndLonger() {
        assertEquals(0L, PinHasher.lockoutMs(4))
        assertEquals(30_000L, PinHasher.lockoutMs(5))
        assertEquals(60_000L, PinHasher.lockoutMs(6))
        assertEquals(600_000L, PinHasher.lockoutMs(30))
    }
}
