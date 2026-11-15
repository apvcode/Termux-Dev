package com.apvcode.devx.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apvcode.devx.R
import com.apvcode.devx.data.Msg
import com.apvcode.devx.data.ToolActivity
import com.apvcode.devx.data.ToolKind
import com.apvcode.devx.ui.components.DevxIcons
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Every step is shown at least this long, however quickly the work itself was done. */
private const val MIN_STEP_MS = 1400L

/** After the work is over the step stays until the thinking goes on; but never longer than this. */
private const val HOLD_CAP_MS = 6000L

/** How much new thinking counts as "went on". */
private const val MOVE_ON_CHARS = 30

/**
 * The step the line shows, kept on the screen long enough to be read. The work itself (opening a file, reading a page) can be over in
 * a fraction of a second; the step does not vanish with it. It stays for at least [MIN_STEP_MS], and then until the model starts
 * thinking or writing again (the next step takes its place at once, once its own time is up).
 */
@Composable
private fun rememberHeldStep(msg: Msg): ToolActivity? {
    var shown by remember(msg.id) { mutableStateOf<ToolActivity?>(null) }
    var since by remember(msg.id) { mutableLongStateOf(0L) }
    // A new step takes the place of the one on the screen, but not before that one has had its time.
    LaunchedEffect(msg.id) {
        snapshotFlow { msg.activity }.filterNotNull().distinctUntilChanged().conflate().collect { next ->
            val current = shown
            val same = current != null && current.kind == next.kind && current.detail == next.detail
            if (!same) {
                val wait = MIN_STEP_MS - (android.os.SystemClock.elapsedRealtime() - since)
                if (current != null && wait > 0) delay(wait)
                since = android.os.SystemClock.elapsedRealtime()
            }
            shown = next
        }
    }
    // When the work is over the step is let go: after its time, and once the model has gone on.
    val over = msg.activity == null
    LaunchedEffect(msg.id, over, shown) {
        if (!over || shown == null) return@LaunchedEffect
        val endedWith = msg.reasoning.length
        val left = MIN_STEP_MS - (android.os.SystemClock.elapsedRealtime() - since)
        if (left > 0) delay(left)
        withTimeoutOrNull(HOLD_CAP_MS) {
            snapshotFlow { msg.reasoning.length > endedWith + MOVE_ON_CHARS || msg.text.isNotEmpty() || msg.activity != null }.first { it }
        }
        if (msg.activity == null) shown = null
    }
    return shown
}

/** One soft change of what is written: the old words rise and melt away, the new ones come up from below. */
private fun <T> wordsChange(): AnimatedContentTransitionScopeSpec<T> = {
    ContentTransform(
        targetContentEnter = fadeIn(tween(320, delayMillis = 140)) + slideInVertically(tween(420, easing = FastOutSlowInEasing)) { it * 2 / 3 },
        initialContentExit = fadeOut(tween(200)) + slideOutVertically(tween(300, easing = FastOutSlowInEasing)) { -it / 2 },
        sizeTransform = SizeTransform(clip = false) { _, _ -> tween(320, easing = FastOutSlowInEasing) },
    )
}

private typealias AnimatedContentTransitionScopeSpec<T> = androidx.compose.animation.AnimatedContentTransitionScope<T>.() -> ContentTransform

/**
 * The line above an answer that is being made: what the assistant is doing this moment, as one thing that moves. The mark in front
 * of the words turns into the picture of the step (the dots into a globe, the globe into a page, into a file) and the words rise
 * out and are replaced; under it the sites it has looked at come in one by one. A step stays long enough to be read.
 */
@Composable
fun AssistantStatus(msg: Msg, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val step = rememberHeldStep(msg)
    val headline = rememberThinkingHeadline(msg)
    val words = step?.let { stepLabel(it.kind) + if (it.detail.isNotBlank()) " · " + it.detail else "" } ?: headline
    val kind = step?.kind
    val tappable = step != null || msg.reasoning.isNotBlank() || msg.steps.isNotEmpty()
    val pulse by rememberInfiniteTransition(label = "status-pulse").animateFloat(
        0.55f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse",
    )
    val tap = if (tappable) Modifier.pressable(scaleTo = 0.98f, haptic = false, onClick = onClick) else Modifier
    Column(modifier.then(tap).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // The mark: it grows out of nothing and the old one shrinks into it, a little turned.
            AnimatedContent(
                targetState = kind,
                transitionSpec = {
                    ContentTransform(
                        targetContentEnter = fadeIn(tween(260, delayMillis = 120)) + scaleIn(spring(dampingRatio = 0.62f, stiffness = 420f), initialScale = 0.35f),
                        initialContentExit = fadeOut(tween(160)) + scaleOut(tween(220, easing = FastOutSlowInEasing), targetScale = 0.35f),
                        sizeTransform = null,
                    )
                },
                contentAlignment = Alignment.Center,
                label = "status-mark",
                modifier = Modifier.size(22.dp),
            ) { k -> if (k == null) ThinkingSparkle() else StepIcon(k) }
            AnimatedContent(
                targetState = words,
                transitionSpec = wordsChange(),
                label = "status-words",
                modifier = Modifier.weight(1f, fill = false),
            ) { text ->
                if (text != null) {
                    Text(
                        text,
                        style = DevxType.Body,
                        color = DevxColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.graphicsLayer { alpha = pulse },
                    )
                } else Box(Modifier.size(1.dp))
            }
            AnimatedVisibility(visible = tappable, enter = fadeIn(tween(300)), exit = fadeOut(tween(200))) {
                Icon(DevxIcons.ChevronRight, null, tint = DevxColors.TextDisabled, modifier = Modifier.size(18.dp))
            }
        }
        // The sites it has looked at: they come in one by one, and the count rolls over.
        AnimatedVisibility(
            visible = msg.sources.isNotEmpty() && (step != null || msg.text.isEmpty()),
            enter = fadeIn(tween(320)) + expandVertically(spring(dampingRatio = 0.85f, stiffness = 380f)),
            exit = fadeOut(tween(200)) + shrinkVertically(tween(260)),
        ) { SitesLine(msg) }
    }
}

@Composable
private fun SitesLine(msg: Msg) {
    val shown = msg.sources.takeLast(6)
    val width by animateDpAsState((22 + 14 * (shown.size - 1).coerceAtLeast(0)).dp, spring(dampingRatio = 0.85f, stiffness = 420f), label = "sites-width")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(width = width, height = 22.dp)) {
            shown.forEachIndexed { i, s -> key(s.url) { PoppingFavicon(s.host, i) } }
        }
        AnimatedContent(
            targetState = msg.sources.size,
            transitionSpec = {
                val up = targetState > initialState
                ContentTransform(
                    targetContentEnter = fadeIn(tween(220, delayMillis = 60)) + slideInVertically(spring(dampingRatio = 0.8f, stiffness = 500f)) { if (up) it else -it },
                    initialContentExit = fadeOut(tween(140)) + slideOutVertically(tween(200)) { if (up) -it else it },
                    sizeTransform = SizeTransform(clip = false),
                )
            },
            label = "sites-count",
        ) { n ->
            Text(stringResource(R.string.tool_sites, n), style = DevxType.Caption, color = DevxColors.TextSecondary)
        }
    }
}

/** A site's icon: it pops in with a little spring, and slides along when another comes. */
@Composable
private fun PoppingFavicon(host: String, index: Int) {
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 520f)) }
    val x by animateDpAsState((14 * index).dp, spring(dampingRatio = 0.85f, stiffness = 420f), label = "favicon-x")
    Favicon(
        host,
        Modifier.offset(x = x).graphicsLayer {
            val v = pop.value
            scaleX = v; scaleY = v
            alpha = v.coerceIn(0f, 1f)
        },
        22.dp,
    )
}
