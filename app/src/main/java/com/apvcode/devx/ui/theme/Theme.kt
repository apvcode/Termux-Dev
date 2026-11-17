package com.apvcode.devx.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import kotlinx.coroutines.delay
import com.apvcode.devx.data.ThemeSchedule
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.apvcode.devx.data.Prefs
import com.apvcode.devx.data.ThemeMode
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.apvcode.devx.R

class Palette(
    val bg: Color, val drawer: Color, val card: Color, val cardInner: Color, val outline: Color,
    val text: Color, val textSecondary: Color, val textDisabled: Color,
    val accent: Color, val onAccent: Color,
    val success: Color, val warning: Color, val danger: Color,
    val light: Boolean,
) {
    companion object {
        /** Pure black OLED. */
        val Black = Palette(
            bg = Color(0xFF000000), drawer = Color(0xFF1A1A1A), card = Color(0xFF2A2A2A), cardInner = Color(0xFF111111), outline = Color(0xFF3A3A3A),
            text = Color(0xFFF2F2F2), textSecondary = Color(0xFF9A9A9A), textDisabled = Color(0xFF5C5C5C),
            accent = Color(0xFFFFFFFF), onAccent = Color(0xFF000000),
            success = Color(0xFF3FB950), warning = Color(0xFFE3A008), danger = Color(0xFFF0524F), light = false,
        )
        val Gray = Palette(
            bg = Color(0xFF161616), drawer = Color(0xFF212121), card = Color(0xFF2E2E2E), cardInner = Color(0xFF1C1C1C), outline = Color(0xFF444444),
            text = Color(0xFFF0F0F0), textSecondary = Color(0xFFA0A0A0), textDisabled = Color(0xFF666666),
            accent = Color(0xFFFFFFFF), onAccent = Color(0xFF000000),
            success = Color(0xFF3FB950), warning = Color(0xFFE3A008), danger = Color(0xFFF0524F), light = false,
        )
        val Light = Palette(
            bg = Color(0xFFFFFFFF), drawer = Color(0xFFF1F1F1), card = Color(0xFFEBEBEB), cardInner = Color(0xFFF5F5F5), outline = Color(0xFFD6D6D6),
            text = Color(0xFF141414), textSecondary = Color(0xFF6B6B6B), textDisabled = Color(0xFFA6A6A6),
            accent = Color(0xFF000000), onAccent = Color(0xFFFFFFFF),
            success = Color(0xFF1F8A37), warning = Color(0xFFB77900), danger = Color(0xFFD13B38), light = true,
        )
        /** Deep navy with a soft blue accent. */
        val Midnight = Palette(
            bg = Color(0xFF0B1020), drawer = Color(0xFF121A30), card = Color(0xFF1B2644), cardInner = Color(0xFF0F1630), outline = Color(0xFF2B3A63),
            text = Color(0xFFE8EEFF), textSecondary = Color(0xFF93A2C7), textDisabled = Color(0xFF55638A),
            accent = Color(0xFF8AB4FF), onAccent = Color(0xFF0B1020),
            success = Color(0xFF3FB950), warning = Color(0xFFE3A008), danger = Color(0xFFF0524F), light = false,
        )
        /** Dark pine with a mint accent. */
        val Forest = Palette(
            bg = Color(0xFF0A1410), drawer = Color(0xFF111F19), card = Color(0xFF1A2E24), cardInner = Color(0xFF0E1A14), outline = Color(0xFF2A4538),
            text = Color(0xFFE6F4EC), textSecondary = Color(0xFF8FB09F), textDisabled = Color(0xFF4F6E5E),
            accent = Color(0xFF7AD9A0), onAccent = Color(0xFF08140E),
            success = Color(0xFF3FB950), warning = Color(0xFFE3A008), danger = Color(0xFFF0524F), light = false,
        )
        /** Dusk: aubergine with a lilac accent. */
        val Dusk = Palette(
            bg = Color(0xFF120C1C), drawer = Color(0xFF1B1329), card = Color(0xFF2A1E40), cardInner = Color(0xFF170F24), outline = Color(0xFF41305F),
            text = Color(0xFFF0E8FF), textSecondary = Color(0xFFA899C7), textDisabled = Color(0xFF65577F),
            accent = Color(0xFFC4A1FF), onAccent = Color(0xFF140A22),
            success = Color(0xFF3FB950), warning = Color(0xFFE3A008), danger = Color(0xFFF0524F), light = false,
        )
        /** Embers: warm brown with an amber accent. */
        val Ember = Palette(
            bg = Color(0xFF140E0A), drawer = Color(0xFF1E1511), card = Color(0xFF2E2019), cardInner = Color(0xFF19110D), outline = Color(0xFF4A3428),
            text = Color(0xFFF7EBE0), textSecondary = Color(0xFFBBA08C), textDisabled = Color(0xFF6F5A4B),
            accent = Color(0xFFFFB86B), onAccent = Color(0xFF1A0E05),
            success = Color(0xFF3FB950), warning = Color(0xFFE3A008), danger = Color(0xFFF0524F), light = false,
        )
        /** Deep sea: dark teal with an aqua accent. */
        val Ocean = Palette(
            bg = Color(0xFF061417), drawer = Color(0xFF0D2125), card = Color(0xFF15333A), cardInner = Color(0xFF0A1C20), outline = Color(0xFF22505A),
            text = Color(0xFFE3F6F8), textSecondary = Color(0xFF88B4BA), textDisabled = Color(0xFF4A7279),
            accent = Color(0xFF5CE1E6), onAccent = Color(0xFF04181A),
            success = Color(0xFF3FB950), warning = Color(0xFFE3A008), danger = Color(0xFFF0524F), light = false,
        )
        /** Warm paper: sand ground, brown ink. */
        val Paper = Palette(
            bg = Color(0xFFFAF6EE), drawer = Color(0xFFF1EBDD), card = Color(0xFFE9E1CF), cardInner = Color(0xFFF5EFE2), outline = Color(0xFFD9CFB8),
            text = Color(0xFF2A2418), textSecondary = Color(0xFF7A6F58), textDisabled = Color(0xFFB3A98F),
            accent = Color(0xFF5A3A12), onAccent = Color(0xFFFFF8EA),
            success = Color(0xFF1F8A37), warning = Color(0xFFB77900), danger = Color(0xFFD13B38), light = true,
        )
        /** Blossom: pale pink with a raspberry accent. */
        val Sakura = Palette(
            bg = Color(0xFFFFF7F9), drawer = Color(0xFFFCEAF0), card = Color(0xFFF7DCE6), cardInner = Color(0xFFFDF0F4), outline = Color(0xFFEBC4D3),
            text = Color(0xFF2B1620), textSecondary = Color(0xFF85596B), textDisabled = Color(0xFFC4A0B0),
            accent = Color(0xFFC2185B), onAccent = Color(0xFFFFFFFF),
            success = Color(0xFF1F8A37), warning = Color(0xFFB77900), danger = Color(0xFFD13B38), light = true,
        )
        /** Fresh mint: light green with a deep green accent. */
        val Mint = Palette(
            bg = Color(0xFFF4FBF7), drawer = Color(0xFFE6F4EC), card = Color(0xFFD6EBDF), cardInner = Color(0xFFEEF8F2), outline = Color(0xFFBBD9C8),
            text = Color(0xFF10241A), textSecondary = Color(0xFF4F7562), textDisabled = Color(0xFF9BBBA9),
            accent = Color(0xFF1B7A4B), onAccent = Color(0xFFFFFFFF),
            success = Color(0xFF1F8A37), warning = Color(0xFFB77900), danger = Color(0xFFD13B38), light = true,
        )

        /** The palette of a theme; [systemDark] only matters for [ThemeMode.System]. */
        fun of(mode: ThemeMode, systemDark: Boolean): Palette = when (mode) {
            ThemeMode.Black -> Black
            ThemeMode.Gray -> Gray
            ThemeMode.Light -> Light
            ThemeMode.Midnight -> Midnight
            ThemeMode.Forest -> Forest
            ThemeMode.Dusk -> Dusk
            ThemeMode.Ember -> Ember
            ThemeMode.Ocean -> Ocean
            ThemeMode.Paper -> Paper
            ThemeMode.Sakura -> Sakura
            ThemeMode.Mint -> Mint
            ThemeMode.System -> if (systemDark) Black else Light
        }
    }
}

/** The colours in use. Colour is used only for meaning (approval, success, danger). Switching theme swaps [palette]. */
object DevxColors {
    var palette by mutableStateOf(Palette.Black)

    val Bg get() = palette.bg
    val Drawer get() = palette.drawer
    val Card get() = palette.card
    val CardInner get() = palette.cardInner
    val Outline get() = palette.outline

    val TextPrimary get() = palette.text
    val TextSecondary get() = palette.textSecondary
    val TextDisabled get() = palette.textDisabled

    val Accent get() = palette.accent
    val OnAccent get() = palette.onAccent

    val Success get() = palette.success
    val Warning get() = palette.warning
    val Danger get() = palette.danger
}

/**
 * Fonts, both SIL OFL (see NOTICE). Claude's own faces (Anthropic Sans / Serif) are proprietary, so
 * these are the closest free ones: Inter for the interface, Source Serif 4 for the assistant's words.
 * Both are variable fonts with Cyrillic. Weights stay light: 400 for text, 500 for emphasis.
 */
@OptIn(ExperimentalTextApi::class)
object DevxFonts {
    private fun inter(weight: Int) = Font(
        R.font.inter_variable,
        FontWeight(weight),
        variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
    )

    private fun serif(weight: Int) = Font(
        R.font.source_serif_4_variable,
        FontWeight(weight),
        variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
    )

    private fun serifItalic(weight: Int) = Font(
        R.font.source_serif_4_italic,
        FontWeight(weight),
        FontStyle.Italic,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
    )

    val Sans = FontFamily(inter(400), inter(500), inter(600))
    val Serif = FontFamily(serif(400), serif(500), serif(600), serifItalic(400), serifItalic(600))
}

object DevxType {
    // Interface: Inter, regular, with medium (not bold) for titles.
    val Title = TextStyle(fontFamily = DevxFonts.Sans, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
    val Body = TextStyle(fontFamily = DevxFonts.Sans, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal)
    val BodyStrong = TextStyle(fontFamily = DevxFonts.Sans, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
    val Menu = TextStyle(fontFamily = DevxFonts.Sans, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal)
    val Caption = TextStyle(fontFamily = DevxFonts.Sans, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)
    val Section = TextStyle(fontFamily = DevxFonts.Sans, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)

    // Words: Source Serif 4, like Claude. Big headings and what the assistant says.
    val Large = TextStyle(fontFamily = DevxFonts.Serif, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Normal)
    val Reply = TextStyle(fontFamily = DevxFonts.Serif, fontSize = 17.sp, lineHeight = 27.sp, fontWeight = FontWeight.Normal)

    val Mono = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, fontFamily = FontFamily.Monospace)
}

/** Material components that show text without our styles still get Inter, not the system font. */
private val DevxTypography = Typography().let { t ->
    fun TextStyle.sans() = copy(fontFamily = DevxFonts.Sans)
    t.copy(
        displayLarge = t.displayLarge.sans(), displayMedium = t.displayMedium.sans(), displaySmall = t.displaySmall.sans(),
        headlineLarge = t.headlineLarge.sans(), headlineMedium = t.headlineMedium.sans(), headlineSmall = t.headlineSmall.sans(),
        titleLarge = t.titleLarge.sans(), titleMedium = t.titleMedium.sans(), titleSmall = t.titleSmall.sans(),
        bodyLarge = t.bodyLarge.sans(), bodyMedium = t.bodyMedium.sans(), bodySmall = t.bodySmall.sans(),
        labelLarge = t.labelLarge.sans(), labelMedium = t.labelMedium.sans(), labelSmall = t.labelSmall.sans(),
    )
}

private fun schemeFor(p: Palette) = (if (p.light) lightColorScheme() else darkColorScheme()).copy(
    primary = p.accent,
    onPrimary = p.onAccent,
    background = p.bg,
    onBackground = p.text,
    surface = p.bg,
    onSurface = p.text,
    surfaceVariant = p.card,
    onSurfaceVariant = p.textSecondary,
    outline = p.outline,
    error = p.danger,
)

private fun minuteOfDay(): Int = java.time.LocalTime.now().let { it.hour * 60 + it.minute }

@Composable
fun DevxTheme(content: @Composable () -> Unit) {
    // By the clock: looked at again every half minute (a state that does not change does not redraw anything).
    var scheduled by remember { mutableStateOf(ThemeSchedule.modeAt(minuteOfDay(), Prefs.nightFrom, Prefs.nightTo, Prefs.dayTheme, Prefs.nightTheme)) }
    if (Prefs.themeSchedule) LaunchedEffect(Prefs.nightFrom, Prefs.nightTo, Prefs.dayTheme, Prefs.nightTheme) {
        while (true) {
            scheduled = ThemeSchedule.modeAt(minuteOfDay(), Prefs.nightFrom, Prefs.nightTo, Prefs.dayTheme, Prefs.nightTheme)
            delay(30_000)
        }
    }
    val mode = if (Prefs.themeSchedule) scheduled else Prefs.theme
    val systemDark = isSystemInDarkTheme()
    val palette = Palette.of(mode, systemDark)
    DevxColors.palette = palette
    MaterialTheme(
        colorScheme = schemeFor(palette),
        typography = DevxTypography,
        content = { ThemeRevealHost(content) },
    )
}
