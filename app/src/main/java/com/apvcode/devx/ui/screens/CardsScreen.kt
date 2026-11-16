package com.apvcode.devx.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apvcode.devx.R
import com.apvcode.devx.data.Flashcard
import com.apvcode.devx.ui.chat.ChatViewModel
import com.apvcode.devx.ui.components.CircleIconButton
import com.apvcode.devx.ui.components.PillButton
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import kotlinx.coroutines.delay

/** How one answer is named on its button, in the order of the buttons. */
private val Answers = listOf("again", "hard", "good", "easy")

/**
 * The cards of a study notebook that are due, one after another, without a chat: the question, a tap turns the card over, then one of
 * four buttons says how it went (each shows when the card will come back). A card answered "again" returns at the end of this round. It
 * is quick and costs nothing: the assistant is only needed to make the cards.
 */
@Composable
fun CardsScreen(vm: ChatViewModel, name: String, onClose: () -> Unit, onAsk: (String) -> Unit) {
    val info = vm.projectInfo[name]
    if (info == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val tint = Color(info.color)
    val haptic = LocalHapticFeedback.current
    // The round: the cards that are due now, taken once (what is answered leaves it; "again" comes back at the end).
    val queue = remember { androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(info.plan.due().map { it.id }) } }
    val total = remember { queue.size }
    var answered by remember { mutableIntStateOf(0) }
    val counts = remember { IntArray(4) }
    var flipped by remember { mutableStateOf(false) }
    val card = queue.firstOrNull()?.let { id -> info.plan.cards.firstOrNull { it.id == id } }

    Column(Modifier.fillMaxSize().background(DevxColors.Bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CircleIconButton(Icons.Outlined.Close, stringResource(R.string.cd_close), onClose)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name, style = DevxType.BodyStrong, color = DevxColors.TextPrimary, maxLines = 1)
                val fraction by animateFloatAsState(if (total == 0) 1f else answered.coerceAtMost(total).toFloat() / total, spring(dampingRatio = 0.8f, stiffness = 200f), label = "round")
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(DevxColors.Outline)) {
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(CircleShape).background(tint))
                }
            }
            if (total > 0) Text("${answered.coerceAtMost(total)} / $total", style = DevxType.Caption, color = DevxColors.TextSecondary)
        }

        if (card == null) {
            Finished(info.plan.cards.size, total, counts, info.plan.log.streak(), tint, onClose, onAsk, vm)
        } else {
            AnimatedContent(
                targetState = card.id,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    (fadeIn(tween(260, delayMillis = 80)) + slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = 380f)) { it / 3 }) togetherWith
                        (fadeOut(tween(140)) + slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { -it / 3 })
                },
                label = "card",
            ) { id ->
                val shown = info.plan.cards.firstOrNull { it.id == id } ?: card
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Flip(shown, flipped && shown.id == card.id, tint) { flipped = !flipped }
                    // The four answers come up after the card is turned; before that, a plain button turns it.
                    if (flipped) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Answers.forEachIndexed { i, result ->
                                Rise(i, Modifier.weight(1f)) {
                                    AnswerButton(i, result, shown) {
                                        haptic.performHapticFeedback(if (i == 0) HapticFeedbackType.LongPress else HapticFeedbackType.Confirm)
                                        counts[i]++
                                        flipped = false
                                        vm.reviewCard(name, shown.id, result)
                                        queue.removeAt(0)
                                        if (result == "again") queue.add(shown.id) else answered++
                                    }
                                }
                            }
                        }
                    } else {
                        PillButton(stringResource(R.string.cards_show_answer), onClick = { flipped = true }, container = tint, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

/** The card: the question on its front; a tap turns it over to the answer. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Flip(card: Flashcard, back: Boolean, tint: Color, onTap: () -> Unit) {
    val turn by animateFloatAsState(if (back) 180f else 0f, spring(dampingRatio = 0.78f, stiffness = 220f), label = "turn")
    val density = LocalDensity.current
    val showBack = turn > 90f
    Box(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .graphicsLayer {
                rotationY = turn
                cameraDistance = 14f * density.density
                val lift = 1f - 0.04f * kotlin.math.sin(Math.toRadians(turn.toDouble())).toFloat().let { kotlin.math.abs(it) }
                scaleX = lift; scaleY = lift
            }
            .clip(RoundedCornerShape(30.dp))
            .background(DevxColors.Card)
            .border(1.dp, tint.copy(alpha = if (showBack) 0.5f else 0.22f), RoundedCornerShape(30.dp))
            .pressable(scaleTo = 0.985f, haptic = false, onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp)
                // The back is drawn turned, so that it reads the right way round after the half turn.
                .graphicsLayer { rotationY = if (showBack) 180f else 0f },
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(if (showBack) R.string.cards_answer else R.string.cards_question), style = DevxType.Caption,
                color = if (showBack) tint else DevxColors.TextDisabled, modifier = Modifier.padding(bottom = 14.dp),
            )
            Text(
                if (showBack) card.back else card.front,
                style = DevxType.Title.copy(fontSize = if ((if (showBack) card.back else card.front).length > 120) 19.sp else 24.sp, lineHeight = if ((if (showBack) card.back else card.front).length > 120) 27.sp else 33.sp),
                color = DevxColors.TextPrimary, textAlign = TextAlign.Center,
            )
            if (!showBack) Text(stringResource(R.string.cards_tap_flip), style = DevxType.Caption, color = DevxColors.TextDisabled, modifier = Modifier.padding(top = 22.dp))
        }
    }
}

/** One of the four answers, with when the card would come back. */
@Composable
private fun AnswerButton(index: Int, result: String, card: Flashcard, onClick: () -> Unit) {
    val colors = listOf(Color(0xFFE8685C), Color(0xFFE8A05C), Color(0xFF7BC47F), Color(0xFF6C9BE8))
    val labels = listOf(R.string.cards_again, R.string.cards_hard, R.string.cards_good, R.string.cards_easy)
    val tint = colors[index]
    val days = card.preview(result)
    Column(
        Modifier.fillMaxWidth().pressable(scaleTo = 0.94f, haptic = false, onClick = onClick).clip(RoundedCornerShape(20.dp)).background(tint.copy(alpha = 0.16f)).border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(20.dp)).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(stringResource(labels[index]), style = DevxType.BodyStrong.copy(fontSize = 14.sp), color = DevxColors.TextPrimary, maxLines = 1)
        Text(if (days == 0) stringResource(R.string.cards_in_minutes) else stringResource(R.string.cards_in_days, days), style = DevxType.Caption, color = tint, maxLines = 1)
    }
}

/** Each thing comes up a little after the one before it. */
@Composable
private fun Rise(index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(index * 40L); a.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 320f)) }
    Box(modifier.graphicsLayer { alpha = a.value.coerceIn(0f, 1f); translationY = (1f - a.value) * 14.dp.toPx() }) { content() }
}

/** The round is over (or there was nothing due): what was done, the streak, and the ways on. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Finished(
    cards: Int, total: Int, counts: IntArray, streak: Int, tint: Color, onClose: () -> Unit, onAsk: (String) -> Unit, vm: ChatViewModel,
) {
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 200f)) }
    Column(
        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp).graphicsLayer { alpha = pop.value.coerceIn(0f, 1f); scaleX = 0.8f + 0.2f * pop.value; scaleY = 0.8f + 0.2f * pop.value },
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(84.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(com.apvcode.devx.ui.components.DevxIcons.Check, null, tint = tint, modifier = Modifier.size(42.dp))
        }
        Text(stringResource(if (total > 0) R.string.cards_done_title else R.string.cards_empty), style = DevxType.Title, color = DevxColors.TextPrimary, modifier = Modifier.padding(top = 20.dp), textAlign = TextAlign.Center)
        Text(
            if (total > 0) pluralStringResource(R.plurals.cards_done_count, total, total) else if (cards == 0) stringResource(R.string.cards_none_yet) else stringResource(R.string.cards_empty_hint),
            style = DevxType.Body, color = DevxColors.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
        )
        if (total > 0) Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val colors = listOf(Color(0xFFE8685C), Color(0xFFE8A05C), Color(0xFF7BC47F), Color(0xFF6C9BE8))
            counts.forEachIndexed { i, n -> if (n > 0) Row(Modifier.clip(CircleShape).background(colors[i].copy(alpha = 0.16f)).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(n.toString(), style = DevxType.BodyStrong, color = colors[i])
            } }
        }
        if (streak > 0) Text(pluralStringResource(R.plurals.nb_streak, streak, streak), style = DevxType.BodyStrong, color = tint, modifier = Modifier.padding(top = 18.dp))
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (cards == 0) PillButton(stringResource(R.string.cards_make), onClick = { onAsk(vm.cardsMakePrompt()) }, container = tint, modifier = Modifier.fillMaxWidth())
        PillButton(stringResource(R.string.cards_close), onClick = onClose, filled = false, modifier = Modifier.fillMaxWidth())
    }
}
