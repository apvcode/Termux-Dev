package com.apvcode.devx.ui.lock

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apvcode.devx.R
import com.apvcode.devx.data.AppLock
import com.apvcode.devx.data.Biometrics
import com.apvcode.devx.data.LockScope
import com.apvcode.devx.data.PinVerdict
import com.apvcode.devx.ui.components.ChoiceDialog
import com.apvcode.devx.ui.components.CircleIconButton
import com.apvcode.devx.ui.components.DevxIcons
import com.apvcode.devx.ui.components.SettingsGroup
import com.apvcode.devx.ui.components.SettingsRow
import com.apvcode.devx.ui.components.SettingsSwitchRow
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.remote.SuccessMark
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ───────────────────────── the pieces ─────────────────────────

/** A padlock drawn by hand: [open] 0 is shut, 1 is open (the shackle lifts and swings aside). */
@Composable
fun LockGlyph(open: Float, modifier: Modifier = Modifier, tint: Color = DevxColors.TextPrimary) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.075f
        val bodyW = w * 0.56f
        val bodyH = h * 0.40f
        val bodyLeft = (w - bodyW) / 2f
        val bodyTop = h * 0.46f
        val cx = w / 2f
        val r = bodyW * 0.30f
        val legLeft = cx - r
        val legRight = cx + r
        val archCenterY = h * 0.20f + r
        val lift = open * h * 0.12f
        withTransform({
            translate(0f, -lift)
            rotate(-open * 16f, pivot = Offset(legLeft, bodyTop))
        }) {
            val shackle = Path().apply {
                moveTo(legLeft, bodyTop + stroke)
                lineTo(legLeft, archCenterY)
                arcTo(androidx.compose.ui.geometry.Rect(cx - r, archCenterY - r, cx + r, archCenterY + r), 180f, 180f, false)
                lineTo(legRight, bodyTop + stroke - open * h * 0.10f)
            }
            drawPath(shackle, tint, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        drawRoundRect(tint, Offset(bodyLeft, bodyTop), Size(bodyW, bodyH), CornerRadius(w * 0.10f), style = Stroke(stroke))
        drawCircle(tint, stroke * 0.85f, Offset(cx, bodyTop + bodyH * 0.42f))
        drawLine(tint, Offset(cx, bodyTop + bodyH * 0.5f), Offset(cx, bodyTop + bodyH * 0.72f), stroke * 0.85f, StrokeCap.Round)
    }
}

private enum class Tone { Normal, Wrong, Good }

/** The six dots: each fills with a little pop; wrong turns them red, right turns them green. */
@Composable
private fun PinDots(filled: Int, tone: Tone) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(AppLock.LENGTH) { i ->
            val on = i < filled
            val scale by animateFloatAsState(if (on) 1f else 0.55f, spring(dampingRatio = 0.45f, stiffness = 520f), label = "dot")
            val color by animateColorAsState(
                when {
                    tone == Tone.Wrong -> DevxColors.Danger
                    tone == Tone.Good -> DevxColors.Success
                    on -> DevxColors.TextPrimary
                    else -> DevxColors.Outline
                },
                tween(220), label = "dot-color",
            )
            Box(Modifier.size(15.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(color))
        }
    }
}

@Composable
private fun Appear(index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(40L + index * 38L); a.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = 320f)) }
    Box(modifier.graphicsLayer { alpha = a.value.coerceIn(0f, 1f); translationY = (1f - a.value) * 22.dp.toPx(); val k = 0.92f + 0.08f * a.value; scaleX = k; scaleY = k }) { content() }
}

@Composable
private fun PadKey(label: String?, index: Int, enabled: Boolean, onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Appear(index) {
        Box(
            Modifier.size(76.dp).clip(CircleShape).background(if (label != null || icon != null) DevxColors.Card else Color.Transparent)
                .then(if (enabled && (label != null || icon != null)) Modifier.pressable(scaleTo = 0.86f, haptic = true, onClick = onClick) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (label != null) Text(label, style = DevxType.Title.copy(fontSize = 28.sp), color = if (enabled) DevxColors.TextPrimary else DevxColors.TextDisabled)
            if (icon != null) Icon(icon, null, tint = DevxColors.TextPrimary, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun PinPad(enabled: Boolean, onDigit: (Char) -> Unit, onDelete: () -> Unit, onBiometric: (() -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val rows = listOf("123", "456", "789")
        rows.forEachIndexed { r, digits ->
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                digits.forEachIndexed { c, ch -> PadKey(ch.toString(), r * 3 + c, enabled, { onDigit(ch) }) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            if (onBiometric != null) PadKey(null, 9, enabled, onBiometric, icon = DevxIcons.Fingerprint) else PadKey(null, 9, false, {})
            PadKey("0", 10, enabled, { onDigit('0') })
            PadKey(null, 11, enabled, onDelete, icon = DevxIcons.Backspace)
        }
    }
}

// ───────────────────────── the panel ─────────────────────────

/**
 * The code entry: the padlock, a title, six dots and the keys. A right code plays [openFrom] → [openTo] on the padlock (opens when
 * unlocking, shuts when a lock is set) and the dots turn green; a wrong one shakes the dots and clears them. A trigger
 * [externalSuccess] (the fingerprint was right) plays the same ending.
 */
@Composable
fun PinPanel(
    title: String,
    subtitle: String?,
    openFrom: Float,
    openTo: Float,
    celebrate: Boolean,
    onSubmit: (String) -> PinVerdict,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    wrongText: String = stringResource(R.string.lock_wrong),
    onBiometric: (() -> Unit)? = null,
    externalSuccess: Int = 0,
    onAccepted: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var digits by remember { mutableStateOf("") }
    var tone by remember { mutableStateOf(Tone.Normal) }
    var message by remember { mutableStateOf<String?>(null) }
    var shutFor by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }
    val glyph = remember { Animatable(openFrom) }
    val appear = remember { Animatable(0f) }
    val resources = LocalContext.current.resources
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 260f)) }

    suspend fun finish() {
        busy = true
        tone = Tone.Good
        onAccepted()
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        if (celebrate) { glyph.animateTo(openTo, tween(560, easing = FastOutSlowInEasing)); delay(340) } else delay(120)
        onDone()
    }
    LaunchedEffect(externalSuccess) { if (externalSuccess > 0 && !busy) finish() }
    // The keypad stays shut after too many wrong codes; the seconds count down.
    LaunchedEffect(shutFor > 0) { while (shutFor > 0) { delay(1000); shutFor -= 1; if (shutFor > 0) message = resources.getString(R.string.lock_shut, shutFor) } ; if (message != null && shutFor == 0 && tone == Tone.Normal) message = null }

    fun submit(code: String) {
        busy = true
        scope.launch {
            delay(110) // the last dot is seen before the answer
            val verdict = withContext(Dispatchers.Default) { onSubmit(code) }
            when (verdict) {
                PinVerdict.Ok -> finish()
                else -> {
                    tone = Tone.Wrong
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (verdict is PinVerdict.Shut) { shutFor = verdict.seconds; message = resources.getString(R.string.lock_shut, verdict.seconds) } else message = wrongText
                    for (d in listOf(16f, -16f, 11f, -11f, 6f, 0f)) shake.animateTo(d, tween(55))
                    delay(120)
                    digits = ""
                    tone = Tone.Normal
                    busy = false
                }
            }
        }
    }

    Column(
        modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.graphicsLayer { alpha = appear.value.coerceIn(0f, 1f); val k = 0.55f + 0.45f * appear.value; scaleX = k; scaleY = k }) {
            LockGlyph(glyph.value, Modifier.size(84.dp), tint = if (tone == Tone.Good) DevxColors.Success else if (tone == Tone.Wrong) DevxColors.Danger else DevxColors.TextPrimary)
        }
        Spacer(Modifier.height(22.dp))
        Appear(0) { Text(title, style = DevxType.Title, color = DevxColors.TextPrimary, textAlign = TextAlign.Center) }
        Spacer(Modifier.height(6.dp))
        Appear(1) { Text(subtitle.orEmpty(), style = DevxType.Body, color = DevxColors.TextSecondary, textAlign = TextAlign.Center) }
        Spacer(Modifier.height(26.dp))
        Box(Modifier.graphicsLayer { translationX = shake.value.dp.toPx() }) { PinDots(digits.length, tone) }
        Box(Modifier.height(30.dp).padding(top = 8.dp), contentAlignment = Alignment.Center) {
            androidx.compose.animation.AnimatedVisibility(message != null, enter = fadeIn(tween(180)), exit = fadeOut(tween(220))) {
                Text(message.orEmpty(), style = DevxType.Caption, color = DevxColors.Danger, textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(14.dp))
        PinPad(
            enabled = !busy && shutFor == 0,
            onDigit = { ch ->
                if (digits.length < AppLock.LENGTH) {
                    message = null
                    digits += ch
                    if (digits.length == AppLock.LENGTH) submit(digits)
                }
            },
            onDelete = { if (digits.isNotEmpty()) digits = digits.dropLast(1) },
            onBiometric = onBiometric,
        )
    }
}

/** The fingerprint or the face, if the person turned it on and the phone has it: a function to call, or null. */
@Composable
private fun rememberBiometric(title: String, other: String, onOk: () -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    if (!AppLock.biometric || !Biometrics.available(context)) return null
    return remember(title) { { Biometrics.prompt(context, title, other, onOk, {}); Unit } }
}

// ───────────────────────── the lock of the app ─────────────────────────

/** Covers the whole app while it is locked; unlocking opens the padlock, then the cover zooms away. */
@Composable
fun AppLockOverlay(ready: Boolean = true) {
    val context = LocalContext.current
    BackHandler(enabled = AppLock.locked && ready) { (context as? Activity)?.moveTaskToBack(true) }
    var signal by remember { mutableIntStateOf(0) }
    val biometric = rememberBiometric(stringResource(R.string.lock_app_title), stringResource(R.string.lock_use_code)) { signal++ }
    LaunchedEffect(AppLock.locked, ready, biometric != null) {
        if (AppLock.locked && ready && biometric != null) { delay(620); biometric() }
    }
    AnimatedVisibility(
        visible = AppLock.locked && ready,
        enter = fadeIn(tween(220)) + scaleIn(tween(460, easing = FastOutSlowInEasing), initialScale = 1.1f),
        exit = fadeOut(tween(480, delayMillis = 60)) + scaleOut(tween(520, easing = FastOutSlowInEasing), targetScale = 1.16f),
    ) {
        Box(Modifier.fillMaxSize().background(DevxColors.Bg).swallowTouches()) {
            // No key here: the panel is made once per showing, and stays as it is while the cover leaves.
            PinPanel(
                title = stringResource(R.string.lock_app_title), subtitle = stringResource(R.string.lock_enter_code),
                openFrom = 0f, openTo = 1f, celebrate = true,
                onSubmit = { AppLock.check(it) }, onDone = { AppLock.unlock() }, onAccepted = { AppLock.revealing = true },
                onBiometric = biometric, externalSuccess = signal,
            )
        }
    }
}

/** Takes every touch (taps, drags, scrolls) so that nothing under it can be reached. */
private fun Modifier.swallowTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope { while (true) { awaitPointerEvent().changes.forEach { it.consume() } } }
}

/** The cover of a locked chat: the same padlock and keys over the whole chat, with its own way back to the list of chats. */
@Composable
fun ChatLockCover(onUnlocked: () -> Unit, onMenu: () -> Unit, modifier: Modifier = Modifier) {
    var signal by remember { mutableIntStateOf(0) }
    val biometric = rememberBiometric(stringResource(R.string.lock_chat_title), stringResource(R.string.lock_use_code)) { signal++ }
    LaunchedEffect(biometric != null) { if (biometric != null) { delay(520); biometric() } }
    Box(modifier.fillMaxSize().background(DevxColors.Bg).swallowTouches()) {
        PinPanel(
            title = stringResource(R.string.lock_chat_title), subtitle = stringResource(R.string.lock_enter_code),
            openFrom = 0f, openTo = 1f, celebrate = true,
            onSubmit = { AppLock.check(it) }, onDone = onUnlocked,
            onBiometric = biometric, externalSuccess = signal,
        )
        Row(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            CircleIconButton(DevxIcons.Menu4, stringResource(R.string.cd_menu), onMenu)
        }
    }
}

// ───────────────────────── setting it up ─────────────────────────

private sealed interface Mode {
    data object Main : Mode
    data object Create : Mode
    data object VerifyToDisable : Mode
    data object VerifyToChange : Mode
    data object Change : Mode
    data object Done : Mode
}

@Composable
fun AppLockScreen(onBack: () -> Unit) {
    var mode by remember { mutableStateOf<Mode>(Mode.Main) }
    BackHandler(enabled = mode != Mode.Main) { mode = Mode.Main }
    Box(Modifier.fillMaxSize().background(DevxColors.Bg)) {
        AnimatedContent(
            targetState = mode,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val forward = targetState != Mode.Main
                (slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = 380f)) { if (forward) it / 5 else -it / 5 } + fadeIn(tween(240))) togetherWith
                    (slideOutHorizontally(tween(240)) { if (forward) -it / 8 else it / 8 } + fadeOut(tween(160)))
            },
            label = "applock-page",
        ) { current ->
            when (current) {
                Mode.Main -> LockSettings(onBack, onTurnOn = { mode = Mode.Create }, onTurnOff = { mode = Mode.VerifyToDisable }, onChange = { mode = Mode.VerifyToChange })
                Mode.Create, Mode.Change -> CreateCode(changing = current == Mode.Change, onCancel = { mode = Mode.Main }, onDone = { mode = if (current == Mode.Create) Mode.Done else Mode.Main })
                Mode.VerifyToDisable -> VerifyPage(stringResource(R.string.lock_enter_to_off), onCancel = { mode = Mode.Main }, onDone = { AppLock.disable(); mode = Mode.Main })
                Mode.VerifyToChange -> VerifyPage(stringResource(R.string.lock_enter_to_change), onCancel = { mode = Mode.Main }, onDone = { mode = Mode.Change })
                Mode.Done -> LockEnabled { mode = Mode.Main }
            }
        }
    }
}

@Composable
private fun TopBack(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircleIconButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), onBack)
    }
}

@Composable
private fun VerifyPage(title: String, onCancel: () -> Unit, onDone: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        PinPanel(title = title, subtitle = stringResource(R.string.lock_enter_code), openFrom = 0f, openTo = 1f, celebrate = true, onSubmit = { AppLock.check(it) }, onDone = onDone)
        Box(Modifier.align(Alignment.TopStart)) { TopBack(onCancel) }
    }
}

/** Two entries of the new code; the second one that matches shuts the padlock. */
@Composable
private fun CreateCode(changing: Boolean, onCancel: () -> Unit, onDone: () -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        key(first == null) {
            if (first == null) {
                PinPanel(
                    title = stringResource(if (changing) R.string.lock_new_code else R.string.lock_make_code), subtitle = stringResource(R.string.lock_make_hint),
                    openFrom = 1f, openTo = 1f, celebrate = false,
                    onSubmit = { first = it; PinVerdict.Ok }, onDone = {},
                )
            } else {
                PinPanel(
                    title = stringResource(R.string.lock_repeat_code), subtitle = stringResource(R.string.lock_repeat_hint),
                    openFrom = 1f, openTo = 0f, celebrate = true, wrongText = stringResource(R.string.lock_mismatch),
                    onSubmit = {
                        if (it == first) {
                            AppLock.setPin(it)
                            // The fingerprint or the face comes on with the code, if the phone has one: it can be switched off in Settings.
                            if (!changing && runCatching { Biometrics.available(context) }.getOrDefault(false)) AppLock.useBiometric(true)
                            PinVerdict.Ok
                        } else PinVerdict.Wrong
                    },
                    onDone = onDone,
                )
            }
        }
        Box(Modifier.align(Alignment.TopStart)) { TopBack(onCancel) }
    }
}

/** "The lock is on": a check draws itself, then the page goes back by itself. */
@Composable
private fun LockEnabled(onBack: () -> Unit) {
    LaunchedEffect(Unit) { delay(1900); onBack() }
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        SuccessMark(size = 150.dp)
        Spacer(Modifier.height(26.dp))
        Appear(2) { Text(stringResource(R.string.lock_on_title), style = DevxType.Title, color = DevxColors.TextPrimary, textAlign = TextAlign.Center) }
        Spacer(Modifier.height(8.dp))
        Appear(3) { Text(stringResource(R.string.lock_on_body), style = DevxType.Body, color = DevxColors.TextSecondary, textAlign = TextAlign.Center) }
    }
}

@Composable
private fun LockSettings(onBack: () -> Unit, onTurnOn: () -> Unit, onTurnOff: () -> Unit, onChange: () -> Unit) {
    val context = LocalContext.current
    var timeoutDialog by remember { mutableStateOf(false) }
    val breathe by rememberInfiniteTransition(label = "breathe").animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val shut by animateFloatAsState(if (AppLock.enabled) 0f else 1f, spring(dampingRatio = 0.6f, stiffness = 140f), label = "glyph")
    Column(Modifier.fillMaxSize()) {
        TopBack(onBack)
        Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.graphicsLayer { translationY = (breathe - 0.5f) * 6.dp.toPx() }) {
                    LockGlyph(shut, Modifier.size(92.dp), tint = if (AppLock.enabled) DevxColors.Success else DevxColors.TextPrimary)
                }
                Text(stringResource(R.string.settings_pin), style = DevxType.Title, color = DevxColors.TextPrimary)
                Text(stringResource(if (!AppLock.enabled) R.string.lock_state_off else when (AppLock.scope) { LockScope.Chats -> R.string.lock_state_chats; LockScope.App -> R.string.lock_state_app; LockScope.Both -> R.string.lock_state_on }), style = DevxType.Body, color = DevxColors.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
            }
            SettingsGroup {
                SettingsSwitchRow(stringResource(R.string.settings_pin), stringResource(R.string.lock_switch_hint), AppLock.enabled) { on -> if (on) onTurnOn() else onTurnOff() }
            }
            androidx.compose.animation.AnimatedVisibility(AppLock.enabled, enter = fadeIn(tween(260)) + scaleIn(spring(dampingRatio = 0.8f, stiffness = 300f), initialScale = 0.96f), exit = fadeOut(tween(160))) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.lock_scope_title), style = DevxType.Section, color = DevxColors.TextSecondary, modifier = Modifier.padding(start = 6.dp))
                    ScopePicker()
                    Text(
                        stringResource(when (AppLock.scope) { LockScope.Chats -> R.string.lock_scope_chats_hint; LockScope.App -> R.string.lock_scope_app_hint; LockScope.Both -> R.string.lock_scope_both_hint }),
                        style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = Modifier.padding(horizontal = 8.dp).animateContentSize(),
                    )
                }
            }
            androidx.compose.animation.AnimatedVisibility(AppLock.enabled, enter = fadeIn(tween(260)) + scaleIn(spring(dampingRatio = 0.8f, stiffness = 300f), initialScale = 0.96f), exit = fadeOut(tween(160))) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SettingsGroup {
                        SettingsRow(title = stringResource(R.string.lock_change), showChevron = true, onClick = onChange)
                        if (Biometrics.available(context)) {
                            com.apvcode.devx.ui.components.SettingsDivider()
                            SettingsSwitchRow(stringResource(R.string.lock_biometric), stringResource(R.string.lock_biometric_hint), AppLock.biometric) { AppLock.useBiometric(it) }
                        }
                        com.apvcode.devx.ui.components.SettingsDivider()
                        SettingsRow(title = stringResource(R.string.lock_after), value = timeoutLabel(AppLock.timeoutSeconds), showChevron = true, onClick = { timeoutDialog = true })
                        com.apvcode.devx.ui.components.SettingsDivider()
                        SettingsRow(title = stringResource(R.string.lock_now), showChevron = false, onClick = { AppLock.lockNow() })
                    }
                    if (AppLock.locksChats) Text(stringResource(R.string.lock_chats_hint), style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = Modifier.padding(horizontal = 12.dp))
                    Text(stringResource(R.string.lock_forgot_hint), style = DevxType.Caption, color = DevxColors.TextDisabled, modifier = Modifier.padding(horizontal = 12.dp))
                }
            }
        }
    }
    if (timeoutDialog) {
        ChoiceDialog(
            stringResource(R.string.lock_after),
            listOf(0 to timeoutLabel(0), 30 to timeoutLabel(30), 60 to timeoutLabel(60), 300 to timeoutLabel(300)),
            AppLock.timeoutSeconds, onPick = { AppLock.setTimeout(it); timeoutDialog = false }, onDismiss = { timeoutDialog = false },
        )
    }
}

/** Three choices in one pill with a thumb that slides to the chosen one: only chats, the app at the door, or both. */
@Composable
private fun ScopePicker() {
    val options = listOf(LockScope.Chats to R.string.lock_scope_chats, LockScope.App to R.string.lock_scope_app, LockScope.Both to R.string.lock_scope_both)
    val selected = options.indexOfFirst { it.first == AppLock.scope }.coerceAtLeast(0)
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().clip(CircleShape).background(DevxColors.Card).padding(4.dp)) {
        val w = maxWidth / options.size
        val x by androidx.compose.animation.core.animateDpAsState(w * selected, spring(dampingRatio = 0.78f, stiffness = 420f), label = "scope-thumb")
        Box(Modifier.androidxOffset(x).size(width = w, height = 44.dp).clip(CircleShape).background(DevxColors.Accent))
        Row {
            options.forEachIndexed { i, (value, label) ->
                val ink by animateColorAsState(if (i == selected) DevxColors.OnAccent else DevxColors.TextSecondary, tween(200), label = "scope-ink")
                Box(Modifier.size(width = w, height = 44.dp).clip(CircleShape).pressable(scaleTo = 0.96f, haptic = false) { AppLock.chooseScope(value) }, contentAlignment = Alignment.Center) {
                    Text(stringResource(label), style = DevxType.Body.copy(fontSize = 14.sp), color = ink, maxLines = 1, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

private fun Modifier.androidxOffset(x: androidx.compose.ui.unit.Dp): Modifier = this.then(Modifier.offset(x = x))

@Composable
private fun timeoutLabel(seconds: Int): String = when (seconds) {
    0 -> stringResource(R.string.lock_after_now)
    30 -> stringResource(R.string.lock_after_30)
    60 -> stringResource(R.string.lock_after_60)
    else -> stringResource(R.string.lock_after_300)
}
