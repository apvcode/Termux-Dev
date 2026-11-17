package com.apvcode.devx.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.apvcode.devx.ui.chat.ThinkingSparkle
import androidx.compose.foundation.ExperimentalFoundationApi

/**
 * The name of a chat. While the model is still writing it (the chat has the first words of the question as a name for the time
 * being), the words are swept by a light and the three turning dots stand in front of them. When the name arrives, the old words
 * are gone and the new ones come in letter by letter, each fading in from nothing, with the sparkle folding away. A name that
 * is only changed (renamed by hand) appears as it is.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AnimatedChatTitle(
    title: String,
    naming: Boolean,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Clip,
    mark: Boolean = true,
    /** A name that does not fit scrolls past, so that all of it can be read. */
    marquee: Boolean = false,
) {
    var shown by remember { mutableStateOf(title) }
    var sawNaming by remember { mutableStateOf(naming) }
    val reveal = remember { Animatable(1f) }
    var width by remember { mutableIntStateOf(0) }

    LaunchedEffect(naming) { if (naming) sawNaming = true }
    LaunchedEffect(title) {
        if (title == shown) return@LaunchedEffect
        if (sawNaming) {
            // The name has arrived: it is written out, letter by letter.
            shown = title
            reveal.snapTo(0f)
            reveal.animateTo(1f, tween((title.length * 42).coerceIn(520, 1500), easing = LinearEasing))
            sawNaming = false
        } else {
            shown = title
        }
    }
    // The name was not changed by the model (it could not be written): the light just goes out.
    LaunchedEffect(naming, title) {
        if (!naming && sawNaming && title == shown) {
            kotlinx.coroutines.delay(900)
            if (!naming && title == shown) sawNaming = false
        }
    }

    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (mark) {
            AnimatedVisibility(
                visible = naming,
                enter = fadeIn(tween(220)) + scaleIn(spring(dampingRatio = 0.6f, stiffness = 420f), initialScale = 0.3f),
                exit = fadeOut(tween(200)) + scaleOut(tween(220), targetScale = 0.3f),
            ) {
                ThinkingSparkle(Modifier.size(14.dp))
            }
        }
        val sweep by rememberInfiniteTransition(label = "title-sweep").animateFloat(
            0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart), label = "sweep",
        )
        val text = if (reveal.value < 1f) {
            // The letters up to the front are whole; the last few are still coming up out of the dark.
            val front = reveal.value * (shown.length + 5)
            buildAnnotatedString {
                shown.forEachIndexed { i, ch ->
                    val a = ((front - i) / 5f).coerceIn(0f, 1f)
                    withStyle(SpanStyle(color = color.copy(alpha = color.alpha * a))) { append(ch) }
                }
            }
        } else null
        val shimmer = if (naming && width > 0) {
            // A band of light moves along the words (from before the start to past the end), over words that are dimmed a little.
            val w = width.toFloat()
            val at = -0.35f * w + sweep * 1.7f * w
            Brush.horizontalGradient(
                listOf(color.copy(alpha = 0.42f), color, color.copy(alpha = 0.42f)),
                startX = at - 0.3f * w, endX = at + 0.3f * w,
            )
        } else null
        val shapedStyle = if (shimmer != null) style.copy(brush = shimmer) else style
        val own = Modifier.weight(1f, fill = false).then(
            if (marquee) Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1400, velocity = 28.dp) else Modifier,
        )
        if (text != null) {
            Text(text, style = style, color = color, maxLines = if (marquee) 1 else maxLines, softWrap = !marquee, overflow = if (marquee) TextOverflow.Clip else overflow, onTextLayout = { width = it.size.width }, modifier = own)
        } else {
            Text(shown, style = shapedStyle, color = color, maxLines = if (marquee) 1 else maxLines, softWrap = !marquee, overflow = if (marquee) TextOverflow.Clip else overflow, onTextLayout = { width = it.size.width }, modifier = own)
        }
    }
}
