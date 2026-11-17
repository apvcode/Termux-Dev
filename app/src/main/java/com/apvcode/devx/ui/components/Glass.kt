package com.apvcode.devx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.apvcode.devx.ui.theme.DevxColors
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import com.apvcode.devx.data.Prefs

/** The blurred backdrop the glass panels look through (set by the screen that owns the content). */
val LocalGlass = compositionLocalOf<HazeState?> { null }

/** Built once per theme, not on every recomposition: a new style would make every panel redraw. */
@Composable
private fun rememberGlassStyle(): HazeStyle {
    val bg = DevxColors.Bg
    val text = DevxColors.TextPrimary
    val card = DevxColors.Card
    return remember(bg, text, card) {
        HazeStyle(
            backgroundColor = bg,
            tints = listOf(HazeTint(text.copy(alpha = 0.07f))),
            blurRadius = 16.dp,
            noiseFactor = 0f,
            fallbackTint = HazeTint(card.copy(alpha = 0.92f)),
        )
    }
}

/** "Liquid glass": whatever is behind shows through, blurred, with a faint light edge. */
@Composable
fun Modifier.glass(shape: Shape): Modifier {
    val state = if (Prefs.blur) LocalGlass.current else null
    val style = rememberGlassStyle()
    val edge = Color.White.copy(alpha = 0.14f)
    // Under the blur there is always a quiet ground of its own: when the blur is not drawn for a moment (the page behind it was
    // not drawn yet, the window just came back) the panel stays a visible capsule instead of turning fully transparent.
    val base = this.clip(shape).background(DevxColors.Card.copy(alpha = if (state != null) 0.6f else 0f))
    return (if (state != null) base.hazeEffect(state, style) {
        // Blur a shrunken copy of what is behind: it looks the same (it is blurred anyway) and costs a fraction.
        inputScale = HazeInputScale.Fixed(0.12f)
    } else base.background(DevxColors.Card.copy(alpha = if (com.apvcode.devx.data.Prefs.blur) 0.9f else 1f)))
        .border(1.dp, edge, shape)
}

@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .pressable(onClick = onClick)
            .glass(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = DevxColors.TextPrimary, modifier = Modifier.size(21.dp))
    }
}
