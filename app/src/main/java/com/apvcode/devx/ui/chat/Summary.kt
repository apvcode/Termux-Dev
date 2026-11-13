package com.apvcode.devx.ui.chat

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apvcode.devx.R
import com.apvcode.devx.data.Msg
import com.apvcode.devx.data.formatTokens
import com.apvcode.devx.model.Role
import com.apvcode.devx.ui.components.DevxIcons
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.markdown.MarkdownText
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import kotlinx.coroutines.delay

/** 75 as "1:15", 7 as "0:07": the time a thing has been running. */
private fun clock(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

/**
 * What the chat shows while its context is being compressed. Nothing here is decoration: the time is the real time, the
 * numbers are the messages being read, and the notes appear as the model writes them. A button stops it.
 */
@Composable
fun CompressingCard(text: String, startedAt: Long, count: Int, tokens: Int, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val seconds = ((now - startedAt) / 1000).coerceAtLeast(0)
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(DevxColors.Card).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(DevxIcons.Minimize, null, tint = DevxColors.TextPrimary, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.compress_working), style = DevxType.Body, color = DevxColors.TextPrimary)
                Text(
                    stringResource(R.string.compress_reading, count, formatTokens(tokens)),
                    style = DevxType.Caption, color = DevxColors.TextSecondary,
                )
            }
            Text(clock(seconds), style = DevxType.Body, color = DevxColors.TextSecondary)
        }
        // The notes as they come: the end of what is written so far, so that the new words are always in view.
        AnimatedVisibility(visible = text.isNotEmpty(), enter = expandVertically(spring(dampingRatio = 0.9f, stiffness = 400f)) + fadeIn(tween(200))) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    (if (text.length > 700) "…" else "") + text.takeLast(700).trimStart(),
                    style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 7, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DevxColors.Bg).padding(12.dp),
                )
                Text(stringResource(R.string.compress_writing, text.length), style = DevxType.Caption, color = DevxColors.TextDisabled)
            }
        }
        Row(
            Modifier.pressable(onClick = onCancel).clip(RoundedCornerShape(50)).background(DevxColors.Bg).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.compress_cancel), style = DevxType.Caption, color = DevxColors.TextPrimary)
        }
    }
}

/**
 * What a compression left in the chat, under the last message it took in: the sizes, how many messages, how long it took,
 * and, opened, the notes themselves and the messages they were made from. A new one opens on the result and counts the
 * size down once; an old one is closed until it is tapped. What comes before it is no longer sent to the model; the notes are.
 */
@Composable
fun CompressedCard(
    summary: String,
    before: Int,
    after: Int,
    seconds: Int,
    originals: List<Msg>,
    fresh: Boolean,
    onSeen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(fresh) }
    var listOpen by remember { mutableStateOf(false) }
    val shown = remember { Animatable(if (fresh && before > after) before.toFloat() else after.toFloat()) }
    val entered = remember { androidx.compose.animation.core.MutableTransitionState(!fresh).apply { targetState = true } }
    LaunchedEffect(fresh) {
        if (fresh && before > after) {
            delay(350)
            shown.animateTo(after.toFloat(), tween(1100, easing = FastOutSlowInEasing))
        }
        if (fresh) onSeen()
    }
    val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 90f else 0f, tween(220), label = "card-turn")
    AnimatedVisibility(
        visibleState = entered,
        enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = 380f)) + fadeIn(tween(260)),
    ) {
        Column(
            modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(DevxColors.Card),
        ) {
            Row(
                Modifier.fillMaxWidth().pressable { open = !open }.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(DevxIcons.Minimize, null, tint = DevxColors.TextPrimary, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.compress_title), style = DevxType.Body, color = DevxColors.TextPrimary)
                    val parts = buildList {
                        if (before > 0 && after > 0) add("≈" + formatTokens(before) + " → ≈" + formatTokens(shown.value.toInt()))
                        if (originals.isNotEmpty()) add(stringResource(R.string.compress_messages, originals.size))
                        if (seconds > 0) add(stringResource(R.string.compress_took, seconds))
                    }
                    if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = DevxType.Caption, color = DevxColors.TextSecondary)
                }
                Icon(DevxIcons.ChevronRight, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = turn })
            }
            AnimatedVisibility(visible = open, enter = expandVertically(spring(dampingRatio = 0.9f, stiffness = 420f)) + fadeIn(tween(200)), exit = shrinkVertically(tween(240)) + fadeOut(tween(160))) {
                Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.compress_result), style = DevxType.Section, color = DevxColors.TextSecondary)
                    SelectionContainer { MarkdownText(summary) }
                    if (originals.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().pressable { listOpen = !listOpen }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.compress_what, originals.size), style = DevxType.Section, color = DevxColors.TextSecondary, modifier = Modifier.weight(1f))
                            Icon(DevxIcons.ChevronRight, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = if (listOpen) 90f else 0f })
                        }
                        AnimatedVisibility(visible = listOpen, enter = expandVertically(tween(260)) + fadeIn(tween(200)), exit = shrinkVertically(tween(220)) + fadeOut(tween(140))) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                originals.forEach { m ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(
                                            stringResource(if (m.role == Role.User) R.string.compress_you else R.string.compress_devx),
                                            style = DevxType.Caption, color = DevxColors.TextDisabled, modifier = Modifier.padding(top = 1.dp),
                                        )
                                        Text(
                                            m.text.replace(Regex("\\s+"), " ").trim(),
                                            style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
