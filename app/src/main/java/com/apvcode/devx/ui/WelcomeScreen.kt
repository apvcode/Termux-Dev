package com.apvcode.devx.ui

import com.apvcode.devx.ui.components.pressable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apvcode.devx.R
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * First start: soft light drifting in the dark, the question writing itself in letter by letter,
 * the name field rising up. After the name is given, everything fades and a greeting appears
 * before the app opens.
 */
@Composable
fun WelcomeScreen(onDone: (String) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var name by remember { mutableStateOf("") }
    var leaving by remember { mutableStateOf(false) }

    val question = stringResource(R.string.welcome_question)
    val typed = remember { Animatable(0f) }
    val sub = remember { Animatable(0f) }
    val field = remember { Animatable(0f) }
    val farewell = remember { Animatable(0f) }
    val fadeOut = remember { Animatable(0f) }
    val glow = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch { glow.animateTo(1f, tween(1800, easing = LinearEasing)) }
        delay(350)
        typed.animateTo(question.length + 4f, tween(question.length * 55 + 500, easing = LinearEasing))
    }
    LaunchedEffect(Unit) {
        delay(350 + 600L)
        sub.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        field.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 140f))
        delay(100)
        runCatching { focus.requestFocus() }
    }

    val finish: () -> Unit = {
        if (name.isNotBlank() && !leaving) {
            leaving = true
            keyboard?.hide()
            scope.launch {
                launch { fadeOut.animateTo(1f, tween(450, easing = FastOutSlowInEasing)) }
                delay(300)
                farewell.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
                delay(600)
                onDone(name.trim())
            }
        }
    }

    Box(modifier.fillMaxSize().background(DevxColors.Bg)) {
        Glows(strength = glow.value * (1f - 0.5f * fadeOut.value))

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 28.dp)
                .graphicsLayer { alpha = 1f - fadeOut.value },
            horizontalAlignment = Alignment.Start,
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                "devx",
                style = DevxType.Title.copy(fontFamily = com.apvcode.devx.ui.theme.DevxFonts.Serif),
                color = DevxColors.TextSecondary,
                modifier = Modifier.graphicsLayer { alpha = (typed.value / 3f).coerceIn(0f, 1f) },
            )
            Spacer(Modifier.height(14.dp))
            val base = DevxColors.TextPrimary
            Text(
                text = buildAnnotatedString {
                    question.forEachIndexed { i, c ->
                        val a = ((typed.value - i) / 4f).coerceIn(0f, 1f)
                        withStyle(SpanStyle(color = base.copy(alpha = a))) { append(c) }
                    }
                },
                style = DevxType.Large.copy(fontSize = 34.sp, lineHeight = 42.sp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.welcome_sub),
                style = DevxType.Body,
                color = DevxColors.TextSecondary,
                modifier = Modifier.graphicsLayer {
                    alpha = sub.value
                    translationY = (1f - sub.value) * 12.dp.toPx()
                },
            )
            Spacer(Modifier.height(30.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = field.value.coerceIn(0f, 1f)
                        translationY = (1f - field.value) * 40.dp.toPx()
                    }
                    .clip(CircleShape)
                    .background(DevxColors.Card.copy(alpha = 0.55f))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                    .padding(start = 22.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).height(48.dp), contentAlignment = Alignment.CenterStart) {
                    BasicTextField(
                        value = name,
                        onValueChange = { if (it.length <= 32) name = it.replace("\n", "") },
                        singleLine = true,
                        textStyle = DevxType.Body.copy(color = DevxColors.TextPrimary),
                        cursorBrush = SolidColor(DevxColors.Accent),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { finish() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (name.isEmpty()) Text(stringResource(R.string.welcome_hint), style = DevxType.Body, color = DevxColors.TextDisabled)
                                inner()
                            }
                        },
                    )
                }
                val ready = name.isNotBlank()
                val pop by androidx.compose.animation.core.animateFloatAsState(
                    if (ready) 1f else 0f,
                    spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
                    label = "go",
                )
                Box(
                    Modifier
                        .size(48.dp)
                        .graphicsLayer {
                            scaleX = 0.4f + 0.6f * pop
                            scaleY = 0.4f + 0.6f * pop
                            alpha = pop.coerceIn(0f, 1f)
                        }
                        .pressable(enabled = ready, onClick = finish)
                        .clip(CircleShape)
                        .background(DevxColors.Accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.ArrowForward, stringResource(R.string.welcome_continue), tint = DevxColors.OnAccent, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.height(36.dp))
        }

        // The greeting that replaces everything for a moment.
        if (leaving) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp)
                    .graphicsLayer {
                        alpha = farewell.value
                        translationY = (1f - farewell.value) * 16.dp.toPx()
                    },
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.welcome_greeting), style = DevxType.Body, color = DevxColors.TextSecondary)
                Spacer(Modifier.height(6.dp))
                Text(name.trim(), style = DevxType.Large.copy(fontSize = 40.sp, lineHeight = 48.sp), color = DevxColors.TextPrimary)
            }
        }
    }
}

/** A few large, very soft lights slowly circling; white on the dark themes, grey on the light one. */
@Composable
private fun Glows(strength: Float) {
    val t = rememberInfiniteTransition(label = "glows")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(26000, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    val tint = if (DevxColors.palette.light) Color(0xFF000000) else Color(0xFFFFFFFF)
    val violet = Color(0xFF8B5CF6)
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        fun orb(cx: Float, cy: Float, r: Float, c: Color, a: Float) {
            drawCircle(
                brush = Brush.radialGradient(listOf(c.copy(alpha = a * strength), Color.Transparent), center = Offset(cx, cy), radius = r),
                radius = r,
                center = Offset(cx, cy),
            )
        }
        val a = phase * 2 * PI.toFloat()
        orb(w * (0.25f + 0.12f * cos(a)), h * (0.22f + 0.07f * sin(a)), w * 0.85f, tint, 0.10f)
        orb(w * (0.80f + 0.10f * sin(a * 1f + 1.7f)), h * (0.62f + 0.08f * cos(a + 0.6f)), w * 0.9f, violet, 0.11f)
        orb(w * (0.45f + 0.15f * cos(a + 3.1f)), h * (0.90f + 0.04f * sin(a + 2f)), w * 0.8f, tint, 0.06f)
    }
}
