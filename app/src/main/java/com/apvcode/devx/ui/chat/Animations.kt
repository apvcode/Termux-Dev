package com.apvcode.devx.ui.chat

import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.Path
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import com.apvcode.devx.ui.components.pressable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import com.apvcode.devx.ui.theme.DevxType
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.apvcode.devx.ui.theme.DevxColors

/**
 * My own message popping out of the composer: it rises, fades in and grows a little from its
 * bottom-right corner, with a slight springy settle. Plays once, only when [play] is true.
 * Everything runs in the draw phase, so it does not recompose anything.
 */
@Composable
fun Modifier.enterPop(play: Boolean): Modifier {
    val progress = remember { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (play) progress.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 380f))
    }
    return this.graphicsLayer {
        val v = progress.value
        alpha = v.coerceIn(0f, 1f)
        translationY = (1f - v) * 32.dp.toPx()
        val scale = 0.9f + 0.1f * v
        scaleX = scale
        scaleY = scale
        transformOrigin = TransformOrigin(1f, 1f)
    }
}

/** Three dots that swell one after another while the assistant is "thinking". */
@Composable
fun ThinkingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "thinking")
    Row(
        modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        repeat(3) { index ->
            val phase by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(560, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * 160),
                ),
                label = "dot$index",
            )
            Box(
                Modifier
                    .size(9.dp)
                    .graphicsLayer {
                        val scale = 0.6f + 0.4f * phase
                        scaleX = scale
                        scaleY = scale
                        alpha = 0.3f + 0.7f * phase
                    }
                    .background(DevxColors.TextPrimary, CircleShape),
            )
        }
    }
}

/**
 * What the model is thinking about, in a few words that change as it goes: a sparkle that breathes
 * and twinkles in front of them. Before there is anything to say the sparkle is alone.
 * Pressing it opens the thinking, when there is any to show ([enabled]).
 */
@Composable
fun ThinkingLabel(text: String?, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "thinking-label")
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    Row(
        modifier
            .pressable(enabled = enabled, scaleTo = 0.97f, haptic = false, onClick = onClick)
            .clip(RoundedCornerShape(12.dp))
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ThinkingSparkle()
        AnimatedContent(
            targetState = text,
            transitionSpec = { (fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 3 }) togetherWith fadeOut(tween(160)) },
            label = "headline",
        ) { label ->
            if (label != null) {
                Text(
                    label,
                    style = DevxType.Body,
                    color = DevxColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer { alpha = pulse },
                )
            }
        }
    }
}

/**
 * The waiting mark, as in Gemini: three small dots that turn around one another. They line up in a
 * row, draw together into a triangle, and line up again at another angle, without ever stopping.
 */
@Composable
fun ThinkingSparkle(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "dots")
    val turn by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "turn")
    val color = DevxColors.TextPrimary
    Canvas(modifier.size(20.dp)) {
        val angle = turn * 2f * Math.PI.toFloat()
        // 0 = the dots stand in a row, 1 = they form a triangle; twice per turn, eased at both ends.
        val raw = 0.5f - 0.5f * kotlin.math.cos(turn * 4f * Math.PI.toFloat())
        val morph = raw * raw * (3f - 2f * raw)
        val spacing = 6.dp.toPx()
        val radius = 4.6.dp.toPx()
        val c = center
        for (k in 0..2) {
            val line = Offset((k - 1) * spacing, 0f)
            val a = (-90f + k * 120f) * Math.PI.toFloat() / 180f
            val tri = Offset(kotlin.math.cos(a) * radius, kotlin.math.sin(a) * radius)
            val x = line.x + (tri.x - line.x) * morph
            val y = line.y + (tri.y - line.y) * morph
            val cos = kotlin.math.cos(angle)
            val sin = kotlin.math.sin(angle)
            drawCircle(color, radius = 1.9.dp.toPx(), center = Offset(c.x + x * cos - y * sin, c.y + x * sin + y * cos))
        }
    }
}

/**
 * While the answer is awaited the whole screen glows from the top, in colours that slowly change
 * (blue, green, yellow, violet) and fade to the background lower down; it melts away as the first
 * words arrive.
 */
@Composable
fun GenerationGlow(active: Boolean, modifier: Modifier = Modifier) {
    val level by animateFloatAsState(if (active) 1f else 0f, tween(if (active) 900 else 700), label = "glow")
    if (level <= 0.01f) return
    val transition = rememberInfiniteTransition(label = "glow-cycle")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(18000, easing = LinearEasing)), label = "phase")
    val light = DevxColors.palette.light
    val background = DevxColors.Bg
    Canvas(modifier.fillMaxSize().graphicsLayer { alpha = level }) {
        val w = size.width
        val h = size.height
        val twoPi = 2f * Math.PI.toFloat()
        val strength = if (light) 0.42f else 0.78f
        // Three soft lights hang from the top edge, each in its own colour, drifting from side to side.
        for (k in 0..2) {
            val hue = ((phase + k / 3f) % 1f) * 360f
            val x = w * (0.5f + 0.42f * kotlin.math.sin(twoPi * (phase * 2f + k / 3f)))
            val center = Offset(x, -h * 0.02f)
            val radius = w * 1.15f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.hsv(hue, 0.85f, 1f, 0.6f * strength), Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }
        // Lower down the glow gives way to the plain background.
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.3f to Color.Transparent,
                0.68f to background,
                1f to background,
            ),
        )
    }
}

// ---------- a message flying out of the composer ----------

/**
 * Where the text of the message being sent stood, in window coordinates. The composer fills
 * [field] as it moves; [mark] freezes it at the moment of sending, and the new bubble [take]s it.
 */
class SendOrigin {
    var field: Rect? = null
    private var pending: Rect? = null
    private var at = 0L

    fun mark() {
        pending = field
        at = SystemClock.uptimeMillis()
    }

    /** Only a very fresh mark counts: an edited or retried message did not come from the composer. */
    fun take(): Rect? {
        val rect = pending?.takeIf { SystemClock.uptimeMillis() - at < 1500 }
        pending = null
        return rect
    }
}

val LocalSendOrigin = staticCompositionLocalOf { SendOrigin() }

/** One flight: the bubble starts where the text was typed and settles in its place. */
@Stable
class Flight(val from: Rect) {
    val progress = Animatable(0f)
    var left = 0f
    var top = 0f
    var height = 0f
    var placed by mutableStateOf(false)
}

@Composable
fun rememberFlight(from: Rect): Flight {
    val flight = remember { Flight(from) }
    LaunchedEffect(Unit) {
        // Soft, slightly springy: it glides in and settles instead of stopping dead.
        flight.progress.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = 230f))
    }
    return flight
}

private val BUBBLE_PAD_X = 18.dp
private val BUBBLE_PAD_Y = 12.dp

/**
 * The text of the bubble starts exactly over the text field and glides to its place. The place is
 * read live, so it works while the list is still scrolling to the new message.
 */
fun Modifier.flying(flight: Flight): Modifier = this
    .onGloballyPositioned { coords ->
        val at = coords.positionInWindow()
        flight.left = at.x
        flight.top = at.y
        flight.height = coords.size.height.toFloat()
        flight.placed = true
    }
    .graphicsLayer {
        if (!flight.placed) {
            alpha = 0f
            return@graphicsLayer
        }
        val remaining = 1f - flight.progress.value
        val textLeft = flight.left + BUBBLE_PAD_X.toPx()
        val textTop = flight.top + BUBBLE_PAD_Y.toPx()
        val textHeight = flight.height - 2 * BUBBLE_PAD_Y.toPx()
        val startTop = flight.from.top + (flight.from.height - textHeight) / 2f
        translationX = (flight.from.left - textLeft) * remaining
        translationY = (startTop - textTop) * remaining
    }

/** The bubble's own shape grows out from under the text while it flies, instead of being there from the start. */
fun Modifier.flightBackground(flight: Flight, color: Color): Modifier = drawBehind {
    val t = ((flight.progress.value - 0.1f) / 0.6f).coerceIn(0f, 1f)
    val inset = (1f - t) * 16.dp.toPx()
    drawRoundRect(
        color = color.copy(alpha = color.alpha * t),
        topLeft = Offset(inset, inset * 0.5f),
        size = Size((size.width - 2 * inset).coerceAtLeast(0f), (size.height - inset).coerceAtLeast(0f)),
        cornerRadius = CornerRadius(24.dp.toPx()),
    )
}

// ---------- after the name: the way into the chat ----------

/** What is going on while the app opens for the first time, so that the chat can take part in it. */
object WelcomeCeremony {
    /** The greeting is opening up and the chat is coming into focus underneath. */
    var running by androidx.compose.runtime.mutableStateOf(false)

    /** The coloured light runs over the top of the chat once. */
    var glow by androidx.compose.runtime.mutableStateOf(false)
}

/**
 * The greeting opens like an iris: from the middle a soft-edged hole grows until the whole screen is
 * clear, and what lies underneath shows through it.
 */
fun Modifier.irisHole(progress: () -> Float): Modifier = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val p = progress()
        if (p > 0f) {
            val reach = kotlin.math.hypot(size.width, size.height) * 0.64f
            val r = (reach * p).coerceAtLeast(1f)
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color.Black,
                    0.72f to Color.Black,
                    1f to Color.Transparent,
                    center = center,
                    radius = r,
                ),
                radius = r,
                center = center,
                blendMode = androidx.compose.ui.graphics.BlendMode.DstOut,
            )
        }
    }
