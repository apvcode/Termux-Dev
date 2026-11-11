package com.apvcode.devx.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * The press feel of the whole app: under the finger the element shrinks, and when let go it
 * springs back with a little overshoot; a light tick goes along with it.
 *
 * Put it FIRST in the chain, before `clip` and `background`: then the whole button shrinks, not
 * just what is inside it. The click is listened to before the shrinking, so the touch area stays
 * where it was while the look changes.
 *
 * [scaleTo] is how small it gets: buttons 0.86-0.9, wide rows only a little (0.97).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressable(
    enabled: Boolean = true,
    scaleTo: Float = 0.88f,
    haptic: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val feedback = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        if (pressed && enabled) scaleTo else 1f,
        spring(dampingRatio = 0.5f, stiffness = 520f),
        label = "press",
    )
    val tap = {
        if (haptic) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onClick()
    }
    val clicks = if (onLongClick != null) {
        combinedClickable(interactionSource = source, indication = null, enabled = enabled, onClick = tap, onLongClick = onLongClick)
    } else {
        clickable(interactionSource = source, indication = null, enabled = enabled, onClick = tap)
    }
    return clicks.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
