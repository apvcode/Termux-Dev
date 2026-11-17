package com.apvcode.devx

import com.apvcode.devx.data.ThemeMode
import com.apvcode.devx.data.ThemeSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeScheduleTest {
    private val from = 21 * 60
    private val to = 7 * 60

    @Test fun nightRunsPastMidnight() {
        assertTrue(ThemeSchedule.isNight(23 * 60, from, to))
        assertTrue(ThemeSchedule.isNight(0, from, to))
        assertTrue(ThemeSchedule.isNight(6 * 60 + 59, from, to))
        assertFalse(ThemeSchedule.isNight(7 * 60, from, to))
        assertFalse(ThemeSchedule.isNight(12 * 60, from, to))
        assertTrue(ThemeSchedule.isNight(21 * 60, from, to))
        assertFalse(ThemeSchedule.isNight(20 * 60 + 59, from, to))
    }

    @Test fun nightWithinOneDayAndNoNight() {
        assertTrue(ThemeSchedule.isNight(13 * 60, 12 * 60, 14 * 60))
        assertFalse(ThemeSchedule.isNight(15 * 60, 12 * 60, 14 * 60))
        assertFalse(ThemeSchedule.isNight(3 * 60, 8 * 60, 8 * 60))
    }

    @Test fun theThemeFollowsTheClock() {
        assertEquals(ThemeMode.Black, ThemeSchedule.modeAt(22 * 60, from, to, ThemeMode.Paper, ThemeMode.Black))
        assertEquals(ThemeMode.Paper, ThemeSchedule.modeAt(10 * 60, from, to, ThemeMode.Paper, ThemeMode.Black))
        assertEquals("07:05", ThemeSchedule.label(7 * 60 + 5))
    }
}
