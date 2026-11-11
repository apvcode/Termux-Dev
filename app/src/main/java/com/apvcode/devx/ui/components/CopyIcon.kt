package com.apvcode.devx.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay

/**
 * "Copied" feedback: set it to true on tap; it flips back by itself after a moment.
 * Pair it with [CopyIcon].
 */
@Composable
fun rememberCopied(): MutableState<Boolean> {
    val state = remember { mutableStateOf(false) }
    LaunchedEffect(state.value) {
        if (state.value) {
            delay(1600)
            state.value = false
        }
    }
    return state
}

/** The copy icon that turns into a check mark when [copied]: the old one shrinks away, the new one pops in. */
@Composable
fun CopyIcon(copied: Boolean, tint: Color, description: String?, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = copied,
        transitionSpec = {
            (fadeIn(tween(140, delayMillis = 70)) + scaleIn(spring(dampingRatio = 0.45f, stiffness = 520f), initialScale = 0.3f)) togetherWith
                (fadeOut(tween(100)) + scaleOut(tween(110), targetScale = 0.3f))
        },
        label = "copy-icon",
    ) { done ->
        Icon(if (done) DevxIcons.CopyCheck else DevxIcons.Copy, description, tint = tint, modifier = modifier)
    }
}
