package com.apvcode.devx

import androidx.compose.ui.graphics.Color
import com.apvcode.devx.data.ThemeMode
import com.apvcode.devx.ui.theme.Palette
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class ThemeContrastTest {
    private fun lum(c: Color): Double {
        fun ch(v: Float): Double = v.toDouble().let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }

    private fun ratio(a: Color, b: Color): Double {
        val x = lum(a); val y = lum(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    @Test fun everyThemeIsReadable() {
        val bad = ArrayList<String>()
        for (mode in ThemeMode.values()) for (dark in listOf(true, false)) {
            val p = Palette.of(mode, dark)
            fun need(name: String, a: Color, b: Color, min: Double) { val r = ratio(a, b); if (r < min) bad += "$mode $name %.1f < $min".format(r) }
            need("text on ground", p.text, p.bg, 7.0)
            need("text on card", p.text, p.card, 6.0)
            need("secondary text on ground", p.textSecondary, p.bg, 4.5)
            need("secondary text on card", p.textSecondary, p.card, 3.5)
            need("button label", p.onAccent, p.accent, 4.5)
            need("accent on ground", p.accent, p.bg, 3.0)
            need("card apart from ground", p.card, p.bg, 1.1)
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun lightnessMatchesTheFlag() {
        for (mode in ThemeMode.values()) {
            val p = Palette.of(mode, false)
            assertTrue("$mode", (lum(p.bg) > 0.5) == p.light)
        }
    }
}
