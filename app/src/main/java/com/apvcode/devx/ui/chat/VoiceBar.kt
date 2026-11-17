package com.apvcode.devx.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.apvcode.devx.R
import com.apvcode.devx.data.VoiceInput
import com.apvcode.devx.ui.components.PopupMenu
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import kotlinx.coroutines.delay
import android.os.SystemClock
import androidx.compose.ui.graphics.RectangleShape

/**
 * Listening, in the pill of the composer. The card has one fixed height whatever is said, so that nothing around it moves:
 * the language and the time on top, the words in the middle (one piece of text; older lines melt away at the top, the end
 * stays in view), and below them a quiet cross, a thin line of the voice, and a white tick.
 */
@Composable
internal fun VoiceBar(voice: VoiceInput, onDone: () -> Unit, onCancel: () -> Unit, onLanguage: (String) -> Unit, modifier: Modifier = Modifier) {
    val failed = voice.state == VoiceInput.State.Failed
    Column(
        modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LanguageChip(VoiceInput.currentTag(), voice.detected, onLanguage)
            Box(Modifier.weight(1f))
            VoiceStatus(voice)
        }
        VoiceWords(voice)
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(40.dp).pressable(onClick = onCancel).clip(CircleShape).border(1.dp, DevxColors.TextPrimary.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Close, stringResource(R.string.voice_cancel), tint = DevxColors.TextSecondary, modifier = Modifier.size(20.dp)) }
            VoiceLine(voice, listening = !failed, modifier = Modifier.weight(1f).fillMaxHeight())
            Box(
                Modifier.size(40.dp).pressable(onClick = onDone).clip(CircleShape).background(DevxColors.Accent),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Check, stringResource(R.string.voice_done), tint = DevxColors.OnAccent, modifier = Modifier.size(21.dp)) }
        }
    }
}

/** "Listening…" or "Hearing…" (it changes with a small slide) and the time since it began. */
@Composable
private fun VoiceStatus(voice: VoiceInput) {
    var seconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(voice.startedAt) {
        while (true) {
            seconds = ((SystemClock.elapsedRealtime() - voice.startedAt) / 1000).coerceAtLeast(0)
            delay(500)
        }
    }
    val hearing = voice.pending.isNotBlank() || voice.confirmed.isNotBlank()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AnimatedContent(
            targetState = hearing,
            transitionSpec = { (slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(200))) togetherWith (slideOutVertically(tween(180)) { -it / 2 } + fadeOut(tween(120))) },
            label = "voice-status",
        ) { h ->
            Text(
                stringResource(if (h) R.string.voice_status_hearing else R.string.voice_status_listening),
                style = DevxType.Caption, color = DevxColors.TextSecondary,
            )
        }
        Text("%d:%02d".format(seconds / 60, seconds % 60), style = DevxType.Caption, color = DevxColors.TextDisabled)
    }
}

/**
 * The words. The box is always the same height (about four lines), the text is one piece and sits on the bottom edge: when a
 * new line begins the old ones glide up by one line instead of the whole card growing. What the recogniser is still unsure of is
 * paler until it settles. A message of any length stays calm.
 */
@Composable
private fun VoiceWords(voice: VoiceInput) {
    val settledColor = DevxColors.TextPrimary
    val looseColor = DevxColors.TextPrimary.copy(alpha = 0.5f)
    val text = remember(voice.confirmed, voice.pending, settledColor, looseColor) {
        buildAnnotatedString {
            val head = voice.confirmed.trim()
            val tail = voice.pending.trim()
            withStyle(SpanStyle(color = settledColor)) { append(head) }
            if (tail.isNotEmpty()) {
                if (head.isNotEmpty()) append(' ')
                withStyle(SpanStyle(color = looseColor)) { append(tail) }
            }
        }
    }
    // When the text gets taller by a line the lines are kept where they were for a moment and glide up.
    val glide = remember { Animatable(0f) }
    var height by remember { mutableIntStateOf(0) }
    var grew by remember { mutableIntStateOf(0) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(RectangleShape)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, 0.22f to Color.Black, 1f to Color.Black),
                    blendMode = BlendMode.DstIn,
                )
            },
        contentAlignment = Alignment.BottomStart,
    ) {
        if (text.isEmpty()) {
            val pulse by rememberInfiniteTransition(label = "voice-wait").animateFloat(
                0.4f, 1f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "wait",
            )
            Text(
                stringResource(R.string.voice_say), style = DevxType.Reply, color = DevxColors.TextSecondary,
                modifier = Modifier.padding(bottom = 4.dp).graphicsLayer { alpha = pulse },
            )
        } else {
            Text(
                text, style = DevxType.Reply,
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(Alignment.Bottom, unbounded = true)
                    .onSizeChanged { size ->
                        grew = (size.height - height).coerceAtLeast(0)
                        height = size.height
                    }
                    .graphicsLayer { translationY = glide.value },
            )
        }
    }
    LaunchedEffect(height) {
        if (grew > 0 && height > grew) {
            glide.snapTo(grew.toFloat())
            glide.animateTo(0f, tween(200, easing = FastOutSlowInEasing))
        }
    }
}

/** A single thin line of the voice, from the left to the right: the newest bar at the right edge, older ones to the left and paler. */
@Composable
private fun VoiceLine(voice: VoiceInput, listening: Boolean, modifier: Modifier = Modifier) {
    val color = DevxColors.TextPrimary
    Canvas(modifier) {
        @Suppress("UNUSED_VARIABLE") val moved = voice.tick
        val bar = 2.5.dp.toPx()
        val gap = 3.dp.toPx()
        val count = ((size.width + gap) / (bar + gap)).toInt().coerceIn(3, voice.levels.size)
        for (i in 0 until count) {
            val level = if (listening) voice.levels[voice.levels.size - count + i] else 0f
            val h = (bar + level * (size.height - bar)).coerceAtMost(size.height)
            val fade = (i + 1f) / count
            val x = size.width - (count - i) * (bar + gap) + gap
            drawRoundRect(
                color.copy(alpha = 0.12f + 0.78f * fade * fade),
                topLeft = Offset(x, (size.height - h) / 2f),
                size = Size(bar, h),
                cornerRadius = CornerRadius(bar / 2f),
            )
        }
    }
}

/** The language that is listened for, as "RU ⌄"; a tap offers the others. */
@Composable
private fun LanguageChip(tag: String, detected: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.pressable { open = true }.clip(CircleShape).background(DevxColors.Outline).padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // In auto mode the chip also says which language the recogniser found, when it says.
            Text(
                VoiceInput.shortName(tag) + if (tag == VoiceInput.AUTO && detected.isNotBlank()) " · " + VoiceInput.shortName(detected) else "",
                style = DevxType.Caption, color = DevxColors.TextPrimary,
            )
            Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.voice_language), tint = DevxColors.TextSecondary, modifier = Modifier.size(18.dp))
        }
        PopupMenu(expanded = open, onDismiss = { open = false }, offset = DpOffset(0.dp, 4.dp)) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.voice_lang_auto), style = DevxType.Body, color = if (tag == VoiceInput.AUTO) DevxColors.TextPrimary else DevxColors.TextSecondary) },
                trailingIcon = if (tag == VoiceInput.AUTO) { { Icon(Icons.Outlined.Check, null, tint = DevxColors.TextPrimary, modifier = Modifier.size(20.dp)) } } else null,
                onClick = { open = false; onPick(VoiceInput.AUTO) },
            )
            VoiceInput.LANGUAGES.forEach { (code, name) ->
                val chosen = tag != VoiceInput.AUTO && code.substringBefore('-') == tag.substringBefore('-')
                DropdownMenuItem(
                    text = { Text(name, style = DevxType.Body, color = if (chosen) DevxColors.TextPrimary else DevxColors.TextSecondary) },
                    trailingIcon = if (chosen) { { Icon(Icons.Outlined.Check, null, tint = DevxColors.TextPrimary, modifier = Modifier.size(20.dp)) } } else null,
                    onClick = { open = false; onPick(code) },
                )
            }
        }
    }
}

