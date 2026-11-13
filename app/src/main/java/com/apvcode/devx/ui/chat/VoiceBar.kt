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

/** A thing that comes in a moment after the one before it: it grows from small with a little spring and fades in. */
@Composable
private fun Stagger(delayMs: Int, modifier: Modifier = Modifier, from: Float = 0.6f, content: @Composable () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        progress.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 360f))
    }
    Box(
        modifier.graphicsLayer {
            val k = from + (1f - from) * progress.value
            scaleX = k; scaleY = k
            alpha = progress.value.coerceIn(0f, 1f)
        },
    ) { content() }
}

/**
 * Listening, in the pill of the composer, all in one card: the language and the clock on top, the words as they come in the
 * middle (each one rises into place), and below them a cross, the bars of the voice on both sides of the microphone, and a
 * tick. It all comes in one after another when the listening starts.
 */
@Composable
internal fun VoiceBar(voice: VoiceInput, onDone: () -> Unit, onCancel: () -> Unit, onLanguage: (String) -> Unit, modifier: Modifier = Modifier) {
    val failed = voice.state == VoiceInput.State.Failed
    Column(
        modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Stagger(0, Modifier.fillMaxWidth(), from = 0.96f) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LanguageChip(VoiceInput.currentTag(), voice.detected, onLanguage)
                Box(Modifier.weight(1f))
                VoiceStatus(voice)
            }
        }
        AnimatedVisibility(visible = voice.error != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Text(
                voice.error?.let { stringResource(it) }.orEmpty(),
                style = DevxType.Caption, color = DevxColors.Danger, textAlign = TextAlign.Center,
            )
        }
        VoiceWords(voice)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stagger(110) {
                Box(
                    Modifier.size(44.dp).pressable(onClick = onCancel).clip(CircleShape).background(DevxColors.Outline),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Close, stringResource(R.string.voice_cancel), tint = DevxColors.TextPrimary, modifier = Modifier.size(22.dp)) }
            }
            Stagger(170, Modifier.weight(1f), from = 0.2f) { VoiceWave(voice, listening = !failed, towardsLeft = true, modifier = Modifier.fillMaxWidth().height(40.dp)) }
            VoiceOrb(voice, failed)
            Stagger(170, Modifier.weight(1f), from = 0.2f) { VoiceWave(voice, listening = !failed, towardsLeft = false, modifier = Modifier.fillMaxWidth().height(40.dp)) }
            Stagger(230) {
                Box(
                    Modifier.size(44.dp).pressable(onClick = onDone).clip(CircleShape).background(DevxColors.Accent),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Check, stringResource(R.string.voice_done), tint = DevxColors.OnAccent, modifier = Modifier.size(22.dp)) }
            }
        }
    }
}

/** "Listening…" or "Hearing…" (it changes with a small slide) and the time since it began. */
@Composable
private fun VoiceStatus(voice: VoiceInput) {
    var seconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(voice.startedAt) {
        while (true) {
            seconds = ((android.os.SystemClock.elapsedRealtime() - voice.startedAt) / 1000).coerceAtLeast(0)
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
 * The words, centred and even. Each new word rises a little and fades in; the words the recogniser is still unsure of are
 * paler until it settles on them. With a lot said, the top fades out and the end stays in view.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceWords(voice: VoiceInput) {
    val settled = remember(voice.confirmed) { voice.confirmed.split(Regex("\\s+")).filter { it.isNotEmpty() } }
    val loose = voice.pending.split(Regex("\\s+")).filter { it.isNotEmpty() }
    val all = settled + loose
    // Only the last words are kept as separate pieces; the number counted off keeps the rest of them in their places.
    val dropped = (all.size - MAX_WORDS).coerceAtLeast(0)
    val shown = all.drop(dropped)
    val scroll = rememberScrollState()
    LaunchedEffect(all.size, voice.pending) { scroll.animateScrollTo(scroll.maxValue) }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp, max = 168.dp)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // The top edge fades out, so that older lines melt away instead of being cut.
                drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, 0.18f to Color.Black, 1f to Color.Black),
                    blendMode = BlendMode.DstIn,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (all.isEmpty()) {
            val pulse by rememberInfiniteTransition(label = "voice-wait").animateFloat(
                0.45f, 1f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "wait",
            )
            Text(
                stringResource(R.string.voice_say), style = DevxType.Reply, color = DevxColors.TextSecondary,
                modifier = Modifier.graphicsLayer { alpha = pulse },
            )
        } else {
            FlowRow(
                Modifier.fillMaxWidth().verticalScroll(scroll).padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                shown.forEachIndexed { i, word ->
                    androidx.compose.runtime.key(dropped + i) { VoiceWord(word, pale = dropped + i >= settled.size) }
                }
            }
        }
    }
}

private const val MAX_WORDS = 90

@Composable
private fun VoiceWord(word: String, pale: Boolean) {
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 420f)) }
    val shade by animateFloatAsState(if (pale) 0.55f else 1f, tween(260), label = "word-shade")
    Text(
        word, style = DevxType.Reply, color = DevxColors.TextPrimary,
        modifier = Modifier.graphicsLayer {
            alpha = (rise.value * shade).coerceIn(0f, 1f)
            translationY = (1f - rise.value) * 10.dp.toPx()
        },
    )
}

/**
 * The microphone: a white disc that breathes while it waits and swells a little with the voice, with a soft halo behind it
 * that follows how loud it is (it grows and shrinks in place; nothing spreads out from it).
 */
@Composable
private fun VoiceOrb(voice: VoiceInput, failed: Boolean) {
    val breath by rememberInfiniteTransition(label = "voice-breath").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath",
    )
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(60)
        enter.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 340f))
    }
    val halo = DevxColors.Accent
    Box(Modifier.size(68.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSizeCompat()) {
            @Suppress("UNUSED_VARIABLE") val moved = voice.tick
            val level = if (failed) 0f else voice.levels.last()
            val radius = size.minDimension / 2f * (0.70f + 0.30f * level) * enter.value.coerceIn(0f, 1.2f)
            drawCircle(halo.copy(alpha = 0.08f + 0.22f * level), radius = radius)
        }
        Box(
            Modifier
                .size(48.dp)
                .graphicsLayer {
                    @Suppress("UNUSED_VARIABLE") val moved = voice.tick
                    val level = if (failed) 0f else voice.levels.last()
                    val k = enter.value * (1f + 0.035f * breath + 0.10f * level)
                    scaleX = k; scaleY = k
                }
                .clip(CircleShape)
                .background(if (failed) DevxColors.Outline else DevxColors.Accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Mic, null, tint = if (failed) DevxColors.TextDisabled else DevxColors.OnAccent, modifier = Modifier.size(24.dp))
        }
    }
}

private fun Modifier.matchParentSizeCompat(): Modifier = this.fillMaxWidth().height(68.dp)

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

/**
 * Bars that follow the voice, on one side of the microphone. The two sides are the same, so the whole is symmetric: the
 * newest bar is next to the microphone, older ones move outward and fade. A flat row of dots when nothing is heard.
 */
@Composable
private fun VoiceWave(voice: VoiceInput, listening: Boolean, towardsLeft: Boolean, modifier: Modifier = Modifier) {
    val color = DevxColors.TextPrimary
    Canvas(modifier) {
        @Suppress("UNUSED_VARIABLE") val moved = voice.tick
        val bar = 3.dp.toPx()
        val gap = 3.5.dp.toPx()
        val count = ((size.width + gap) / (bar + gap)).toInt().coerceIn(3, voice.levels.size)
        for (i in 0 until count) {
            // i = 0 is the bar next to the microphone.
            val level = if (listening) voice.levels[voice.levels.size - 1 - i] else 0f
            val h = (bar + level * (size.height - bar)).coerceAtMost(size.height)
            val alpha = 0.95f - 0.8f * i / count
            val x = if (towardsLeft) size.width - (i + 1) * bar - i * gap else i * (bar + gap)
            drawRoundRect(
                color.copy(alpha = alpha),
                topLeft = Offset(x, (size.height - h) / 2f),
                size = Size(bar, h),
                cornerRadius = CornerRadius(bar / 2f),
            )
        }
    }
}
