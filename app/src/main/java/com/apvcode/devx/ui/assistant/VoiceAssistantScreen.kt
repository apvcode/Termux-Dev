package com.apvcode.devx.ui.assistant

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.apvcode.devx.R
import com.apvcode.devx.data.SpeechText
import com.apvcode.devx.data.VoiceInput
import com.apvcode.devx.ui.chat.ChatViewModel
import com.apvcode.devx.ui.chat.StepIcon
import com.apvcode.devx.ui.chat.ToolApprovalCard
import com.apvcode.devx.ui.chat.stepLabel
import com.apvcode.devx.ui.components.ErrorCard
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxFonts
import com.apvcode.devx.ui.theme.DevxType
import kotlin.math.abs
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.material.icons.filled.KeyboardArrowUp
import kotlin.math.sin

private val PillColor = Color(0xF2101012)
private val CardColor = Color(0xF20C0C0E)
private val Hair = Color.White.copy(alpha = 0.12f)

/**
 * The voice assistant. A window over whatever was open, which darkens behind it: the answer in a
 * card, and one pill at the bottom to write in or to speak into. Every talk is saved as an ordinary chat.
 */
@Composable
fun VoiceAssistantScreen(
    vm: ChatViewModel?,
    listenAtStart: Boolean,
    say: String?,
    onClose: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestModel = androidx.compose.runtime.rememberUpdatedState(vm)
    val session = remember { VoiceSession(context, { latestModel.value }, scope) }
    DisposableEffect(session) { onDispose { session.close() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { session.pause() }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) session.listen() else session.micDenied = true
    }
    session.askForMic = { micPermission.launch(Manifest.permission.RECORD_AUDIO) }
    LaunchedEffect(Unit) {
        if (say != null) {
            kotlinx.coroutines.delay(500)
            session.submit(say, byVoice = true)
        } else if (listenAtStart) {
            com.apvcode.devx.data.VoiceTrace.mark("screen drawn, listening requested")
            session.listen()
        }
    }

    // One clock for everything that moves, and one smoothed "how lively" value that the light and the bars share.
    val clock = remember { mutableFloatStateOf(0f) }
    val energy = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(session) {
        val start = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                val t = (now - start) / 1_000_000_000f
                clock.floatValue = t
                val target = when (session.phase) {
                    VoicePhase.Idle -> 0.05f
                    VoicePhase.Listening -> 0.12f + 0.88f * session.voice.levels[VoiceInput.LEVELS - 1]
                    VoicePhase.Thinking -> 0.22f + 0.08f * sin(t * 2.2f)
                    VoicePhase.Speaking -> {
                        val sinceWord = android.os.SystemClock.elapsedRealtime() - session.speaker.lastWordAt
                        val bump = (1f - sinceWord / 240f).coerceIn(0f, 1f) * 0.3f
                        (0.32f + 0.14f * sin(t * 6.9f) + 0.09f * sin(t * 11.7f + 1.3f) + bump).coerceIn(0f, 1f)
                    }
                }
                energy.floatValue += (target - energy.floatValue) * 0.16f
            }
        }
    }

    // The way in: the screen darkens.
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }

    // Held and drawn upward, the whole assistant follows the finger and, let go high enough, flies up into the app.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val drag = remember { Animatable(0f) }
    val threshold = with(density) { 150.dp.toPx() }
    val flyTo = with(density) { 340.dp.toPx() }
    val lift = (-drag.value / threshold).coerceIn(0f, 1.4f)
    var opening by remember { mutableStateOf(false) }
    val openApp: () -> Unit = {
        if (!opening) {
            opening = true
            session.keepChat()
            scope.launch {
                drag.animateTo(-flyTo, tween(260, easing = FastOutSlowInEasing))
                onOpenChat()
            }
        }
    }
    // Every time the assistant opens: the climbing chevrons and a line above the pill say that a hold and a swipe up open the app. They
    // stay three seconds and then go back smoothly (the first question hides them sooner). The pill itself does not move for it.
    var hintOn by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(700)
        if (session.asked.isNotEmpty()) return@LaunchedEffect
        hintOn = true
        kotlinx.coroutines.delay(3000)
        hintOn = false
    }
    LaunchedEffect(session.asked.isNotEmpty()) { if (session.asked.isNotEmpty()) hintOn = false }
    val stopHint = {}

    Box(Modifier.fillMaxSize()) {
        // A tap on the free part of the screen closes the assistant.
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        )
        // The screen behind darkens, a little more toward the bottom, where the words are.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = enter.value * (1f - 0.7f * lift.coerceAtMost(1f)) }
                .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.40f), 0.5f to Color.Black.copy(alpha = 0.55f), 1f to Color.Black.copy(alpha = 0.85f))),
        )

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 14.dp)
                .padding(bottom = 14.dp)
                .graphicsLayer {
                    translationY = drag.value
                    alpha = (1f - 0.55f * lift).coerceIn(0f, 1f)
                    val grow = 1f + 0.025f * lift.coerceAtMost(1f)
                    scaleX = grow; scaleY = grow
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                },
            // No gap between the blocks is set here: each block carries its own 10 dp below it, so that the gap comes and goes with the
            // block (a gap kept by the column vanished all at once when a block had left, which was a jump).
        ) {
            val showCard = session.asked.isNotEmpty() || session.micDenied || session.noRecognizer
            AnimatedVisibility(
                visible = showCard,
                enter = fadeIn(tween(260)) + expandVertically(spring(dampingRatio = 0.85f, stiffness = 380f), expandFrom = Alignment.Bottom),
                exit = fadeOut(tween(160)) + shrinkVertically(),
            ) {
                Box(Modifier.padding(bottom = 10.dp)) { AnswerCard(session, onOpenChat = { session.keepChat(); onOpenChat() }, onClose = onClose, onOpenSettings = onOpenSettings) }
            }
            AnimatedVisibility(visible = !showCard, enter = fadeIn(tween(400, delayMillis = 200)), exit = fadeOut(tween(120))) {
                Box(Modifier.padding(bottom = 10.dp)) { Greeting(session) }
            }
            AnimatedVisibility(
                visible = hintOn,
                enter = fadeIn(tween(420)) + expandVertically(spring(dampingRatio = 0.9f, stiffness = 300f)),
                // It first fades, and only then does the space it took close, slowly, so that the pill and the greeting settle without a jump.
                exit = fadeOut(tween(380, easing = FastOutSlowInEasing)) + shrinkVertically(tween(560, delayMillis = 140, easing = FastOutSlowInEasing)),
            ) { Box(Modifier.padding(bottom = 10.dp)) { SwipeHint() } }
            Pill(
                session, energy = { energy.floatValue }, clock = { clock.floatValue }, enter = { enter.value },
                drag = drag, threshold = threshold, maxPull = flyTo, onOpen = openApp, onTouch = stopHint,
            )
        }
    }
}

// ---------- the answer ----------

/** What was asked and the answer as it is written; a button to carry on in the full chat, and one to close. */
@Composable
private fun AnswerCard(session: VoiceSession, onOpenChat: () -> Unit, onClose: () -> Unit, onOpenSettings: () -> Unit) {
    val reply = session.reply
    val shown = reply?.let { SpeechText.display(it.text) }.orEmpty()
    val scroll = rememberScrollState()
    LaunchedEffect(shown.length, reply?.error, reply?.approval) { scroll.animateScrollTo(scroll.maxValue) }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(CardColor)
            .border(1.dp, Hair, RoundedCornerShape(28.dp))
            // The card takes the touches that fall on it; they do not reach the "tap outside" below.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                session.asked,
                style = DevxType.Caption,
                color = DevxColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            )
            if (reply != null) SmallButton(onClick = onOpenChat) { Icon(Icons.Outlined.OpenInFull, stringResource(R.string.voice_open_chat), tint = DevxColors.TextSecondary, modifier = Modifier.size(18.dp)) }
            SmallButton(onClick = onClose) { Icon(Icons.Outlined.Close, stringResource(R.string.voice_close), tint = DevxColors.TextSecondary, modifier = Modifier.size(19.dp)) }
        }
        Column(
            Modifier
                .heightIn(max = maxHeight)
                .verticalScroll(scroll)
                .padding(start = 22.dp, end = 22.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (shown.isNotEmpty()) {
                Text(shown, style = TextStyle(fontFamily = DevxFonts.Serif, fontSize = 21.sp, lineHeight = 30.sp), color = Color.White)
            } else if (reply?.error == null && reply?.approval == null && reply?.activity == null) {
                Dots()
            }
            reply?.activity?.let { act ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StepIcon(act.kind, size = 20.dp)
                    Text(
                        listOf(stepLabel(act.kind), act.detail).filter { it.isNotBlank() }.joinToString(": "),
                        style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            reply?.approval?.let { ToolApprovalCard(it) }
            reply?.error?.let { kind ->
                ErrorCard(
                    kind = kind,
                    detail = reply.errorDetail,
                    canContinue = false,
                    onRetry = { session.retry() },
                    onOpenSettings = onOpenSettings,
                    onPickModel = onOpenSettings,
                )
            }
            session.voice.error?.let { Text(stringResource(it), style = DevxType.Caption, color = DevxColors.Warning) }
            if (session.noRecognizer) Text(stringResource(R.string.voice_no_recognizer), style = DevxType.Caption, color = DevxColors.Warning)
            if (session.micDenied) Text(stringResource(R.string.voice_err_permission), style = DevxType.Caption, color = DevxColors.Warning)
        }
    }
}

/** Three dots that glow one after another while the answer has not started. */
@Composable
private fun Dots() {
    val t = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = androidx.compose.runtime.withFrameNanos { it }
        while (true) androidx.compose.runtime.withFrameNanos { t.floatValue = (it - start) / 1_000_000_000f }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until 3) {
            val a = 0.25f + 0.75f * (0.5f + 0.5f * sin(t.floatValue * 4.2f - i * 0.9f))
            Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White.copy(alpha = a)))
        }
    }
}

@Composable
private fun SmallButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(42.dp).pressable(scaleTo = 0.88f, onClick = onClick), contentAlignment = Alignment.Center) { content() }
}

/** Before anything was said: what the assistant can do, one example at a time. */
@Composable
private fun Greeting(session: VoiceSession) {
    val examples = listOf(R.string.voice_ex_1, R.string.voice_ex_2, R.string.voice_ex_3, R.string.voice_ex_4, R.string.voice_ex_5)
    var index by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(3600)
            index = (index + 1) % examples.size
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.voice_greeting), style = TextStyle(fontFamily = DevxFonts.Serif, fontSize = 30.sp, lineHeight = 38.sp), color = Color.White)
        AnimatedContent(index, transitionSpec = { fadeIn(tween(500)) togetherWith fadeOut(tween(400)) }, label = "example") { i ->
            Text("«" + stringResource(examples[i]) + "»", style = DevxType.Body, color = Color.White.copy(alpha = 0.62f))
        }
    }
}

// ---------- the pill ----------

/** What the first-time hint says: three chevrons that climb, and a line. */
@Composable
private fun SwipeHint() {
    val t = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = androidx.compose.runtime.withFrameNanos { it }
        while (true) androidx.compose.runtime.withFrameNanos { t.floatValue = (it - start) / 1_000_000_000f }
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 2.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 0 until 3) {
            // A wave of light climbs the three chevrons from the bottom to the top.
            val phase = (t.floatValue * 1.6f - (2 - i) * 0.28f)
            val a = (0.2f + 0.8f * (0.5f + 0.5f * sin(phase * 6.28f - 1.57f))).coerceIn(0.2f, 1f)
            Icon(Icons.Filled.KeyboardArrowUp, null, tint = Color.White.copy(alpha = a), modifier = Modifier.size(22.dp).offset(y = (i * -6).dp))
        }
        Text(stringResource(R.string.voice_swipe_hint), style = DevxType.Caption, color = Color.White.copy(alpha = 0.82f))
    }
}

/**
 * The one bar at the bottom: write here, or speak. It shows what is heard, and what the assistant is doing.
 * A tap on it grows it into a place to write (the speaker button steps aside, the field opens upward); held a moment and drawn
 * upward it carries the whole assistant with it into the app.
 */
@Composable
private fun Pill(
    session: VoiceSession,
    energy: () -> Float,
    clock: () -> Float,
    enter: () -> Float,
    drag: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    threshold: Float,
    maxPull: Float,
    onOpen: () -> Unit,
    onTouch: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf(1) }
    // While the whole assistant is being pulled up, the field does not take part (it would answer a hold with its own menu).
    var pulling by remember { mutableStateOf(false) }
    val fieldFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val phase = session.phase
    val typing = focused || text.isNotEmpty()
    val send = {
        val t = text.trim()
        if (t.isNotEmpty()) {
            focus.clearFocus()
            session.submit(t, byVoice = false)
            text = ""
        }
    }
    val startTyping: () -> Unit = {
        onTouch()
        session.stopListening()
        runCatching { fieldFocus.requestFocus() }
        keyboard?.show()
    }
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 320f)) }
    // The pill opens upward as a place to write: it gets taller, and taller still with each line.
    val height by androidx.compose.animation.core.animateDpAsState(
        if (typing) (46 + 26 * lines.coerceIn(2, 5)).dp else 62.dp,
        spring(dampingRatio = 0.82f, stiffness = 360f), label = "pill-height",
    )
    val corner by androidx.compose.animation.core.animateDpAsState(if (typing) 30.dp else 31.dp, tween(260), label = "pill-corner")
    val shape = RoundedCornerShape(corner)
    var crossed by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = rise.value.coerceIn(0f, 1f)
                translationY = (1f - rise.value) * 60.dp.toPx()
            }
            .height(height)
            .clip(shape)
            .background(PillColor)
            .border(1.dp, Hair, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = startTyping)
            .pointerInput(Unit) {
                // Watched before the buttons and the field see the touch, so that they cannot take it: a hold a moment long
                // that does not slip sideways is the start of the pull; a tap or a quick move is left to them.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis * 7 / 10) {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                            if (!change.pressed) return@withTimeoutOrNull false
                            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull false
                        }
                        @Suppress("UNREACHABLE_CODE") false
                    }
                    if (held != null) return@awaitEachGesture
                    // Held long enough: the pull begins.
                    pulling = true
                    onTouch()
                    crossed = false
                    focus.clearFocus()
                    scope.launch { drag.stop() }
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                        val dy = change.position.y - change.previousPosition.y
                        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { drag.snapTo((drag.value + dy).coerceIn(-maxPull, 0f)) }
                        // A small tick as the pull goes past the point where letting go opens the app, and again if it goes back.
                        val over = -drag.value > threshold
                        if (over != crossed) {
                            crossed = over
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        }
                    }
                    pulling = false
                    if (-drag.value > threshold) onOpen()
                    else scope.launch { drag.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = 380f)) }
                }
            }
            .padding(start = 6.dp, end = 7.dp),
    ) {
        AnimatedVisibility(
            visible = !typing,
            modifier = Modifier.align(Alignment.CenterVertically),
            enter = fadeIn(tween(220, delayMillis = 80)) + androidx.compose.animation.expandHorizontally(spring(dampingRatio = 0.85f, stiffness = 420f)),
            exit = fadeOut(tween(120)) + androidx.compose.animation.shrinkHorizontally(tween(200)),
        ) {
            SmallButton(onClick = { session.toggleMuted() }) {
                Icon(
                    if (session.muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                    stringResource(if (session.muted) R.string.voice_unmute else R.string.voice_mute),
                    tint = if (session.muted) DevxColors.TextDisabled else DevxColors.TextSecondary,
                    modifier = Modifier.size(21.dp),
                )
            }
        }
        // The field is always there: a tap on it (while the microphone is listening too) stops the listening and opens the keyboard.
        val sidePad by androidx.compose.animation.core.animateDpAsState(if (typing) 14.dp else 6.dp, tween(260), label = "field-pad")
        Box(Modifier.weight(1f).padding(start = sidePad, end = 6.dp).fillMaxHeight()) {
            // The first line sits where the middle of a one-line pill is, so that nothing jumps when it opens.
            Box(Modifier.fillMaxWidth().padding(top = 19.dp)) {
                if (text.isEmpty()) {
                    if (phase == VoicePhase.Listening) {
                        val heard = session.voice.heard
                        Text(
                            heard.ifBlank { stringResource(R.string.voice_listening) },
                            style = DevxType.Body,
                            color = if (heard.isBlank()) DevxColors.TextSecondary else Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Text(
                            stringResource(
                                when {
                                    typing -> R.string.voice_type_hint
                                    phase == VoicePhase.Thinking -> R.string.voice_thinking
                                    phase == VoicePhase.Speaking -> R.string.voice_tap_interrupt
                                    else -> R.string.voice_placeholder
                                },
                            ),
                            style = DevxType.Body, color = DevxColors.TextDisabled, maxLines = 1,
                        )
                    }
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    enabled = !pulling,
                    singleLine = false,
                    maxLines = 5,
                    onTextLayout = { lines = it.lineCount },
                    textStyle = DevxType.Body.copy(color = Color.White),
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fieldFocus)
                        .onFocusChanged {
                            focused = it.isFocused
                            if (it.isFocused) session.stopListening()
                        },
                )
            }
        }
        // The round button: send (when there are words), or what the voice is doing right now; at the bottom corner when the pill is tall.
        val typed = text.isNotBlank() && phase != VoicePhase.Listening
        Box(
            Modifier
                .align(Alignment.Bottom)
                .padding(bottom = 7.dp)
                .size(48.dp)
                .pressable(scaleTo = 0.9f, onClick = {
                    onTouch()
                    if (typed) send() else { focus.clearFocus(); session.tapOrb() }
                })
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(if (typed) 4 else phase.ordinal, animationSpec = tween(180), label = "action") { which ->
                when (which) {
                    4 -> Icon(Icons.Filled.ArrowUpward, stringResource(R.string.voice_send), tint = Color.Black, modifier = Modifier.size(23.dp))
                    VoicePhase.Idle.ordinal -> Icon(Icons.Outlined.Mic, stringResource(R.string.voice_mic), tint = Color.Black, modifier = Modifier.size(24.dp))
                    VoicePhase.Thinking.ordinal -> Icon(Icons.Filled.Stop, null, tint = Color.Black, modifier = Modifier.size(22.dp))
                    else -> Bars(energy, clock)
                }
            }
        }
    }
}

/** Slim bars that dance with the voice (yours while you speak, the assistant's while it answers). */
@Composable
private fun Bars(energy: () -> Float, clock: () -> Float) {
    Canvas(Modifier.size(width = 26.dp, height = 24.dp)) {
        val t = clock()
        val e = energy()
        val n = 5
        val bar = 3.6.dp.toPx()
        val gap = (size.width - bar * n) / (n - 1)
        for (i in 0 until n) {
            val wobble = 0.5f + 0.5f * sin(t * (5.1f + i * 1.3f) + i * 1.7f)
            val level = (0.16f + (0.34f + 0.5f * e) * wobble * (if (i == 2) 1f else 0.82f)).coerceIn(0.14f, 1f)
            val h = size.height * level
            drawRoundRect(
                Color.Black,
                topLeft = Offset(i * (bar + gap), (size.height - h) / 2f),
                size = Size(bar, h),
                cornerRadius = CornerRadius(bar / 2f),
            )
        }
    }
}
