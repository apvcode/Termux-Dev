package com.apvcode.devx.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
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

@Composable
fun DevxTheme(content: @Composable () -> Unit) {
    val mode = Prefs.theme
    val systemDark = isSystemInDarkTheme()
    val palette = when (mode) {
        ThemeMode.Black -> Palette.Black
        ThemeMode.Gray -> Palette.Gray
        ThemeMode.Light -> Palette.Light
        ThemeMode.System -> if (systemDark) Palette.Black else Palette.Light
    }
    DevxColors.palette = palette
    MaterialTheme(
        colorScheme = schemeFor(palette),
        typography = DevxTypography,
        content = content,
    )
}
