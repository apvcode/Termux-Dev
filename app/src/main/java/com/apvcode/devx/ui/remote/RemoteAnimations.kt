package com.apvcode.devx.ui.remote

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import com.apvcode.devx.ui.components.DevxIcons
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxFonts
import kotlin.math.PI
import kotlin.math.sin

enum class LinkVisual { Connecting, Online, Offline }

/**
 * The phone and the computer, and the line between them: dashes run while it connects,
 * and when the link is up it turns into a steady line with small lights running both ways. Offline: the line breaks.
 */
@Composable
fun LinkHero(visual: LinkVisual, modifier: Modifier = Modifier, height: Dp = 150.dp, computerIcon: ImageVector = DevxIcons.Desktop) {
    val infinite = rememberInfiniteTransition(label = "link")
    val t by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "t")
    val breathe by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val up by animateFloatAsState(if (visual == LinkVisual.Online) 1f else 0f, tween(700, easing = FastOutSlowInEasing), label = "up")
    val connecting by animateFloatAsState(if (visual == LinkVisual.Connecting) 1f else 0f, tween(500), label = "connecting")
    val line = DevxColors.TextPrimary
    val dim = DevxColors.TextDisabled
    val ok = DevxColors.Success
    val card = DevxColors.Card
    val outline = DevxColors.Outline

    BoxWithConstraints(modifier.fillMaxWidth().height(height)) {
        val node = 64.dp
        val side = 28.dp
        Canvas(Modifier.matchParentSize()) {
            val cy = size.height / 2
            val a = Offset(side.toPx() + node.toPx() / 2, cy)
            val b = Offset(size.width - side.toPx() - node.toPx() / 2, cy)
            val r = node.toPx() / 2

            // the line between: dashes (connecting / offline) become one steady line (online)
            val from = Offset(a.x + r + 8.dp.toPx(), cy)
            val to = Offset(b.x - r - 8.dp.toPx(), cy)
            val len = to.x - from.x
            if (up < 0.999f) {
                val dash = if (visual == LinkVisual.Offline) floatArrayOf(3.dp.toPx(), 16.dp.toPx()) else floatArrayOf(8.dp.toPx(), 9.dp.toPx())
                val phase = if (visual == LinkVisual.Offline) 0f else -t * 17.dp.toPx()
                drawLine(
                    (if (visual == LinkVisual.Offline) dim else line).copy(alpha = (1 - up) * (if (visual == LinkVisual.Offline) 0.7f else 0.55f)),
                    from, to, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(dash, phase),
                )
            }
            if (up > 0.001f) {
                drawLine(line.copy(alpha = 0.35f * up), from, to, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                // two lights, one each way
                for (k in 0..1) {
                    val p = (t + k * 0.5f) % 1f
                    val x = if (k == 0) from.x + len * p else to.x - len * p
                    val fade = sin(p * PI).toFloat()
                    drawCircle(Brush.radialGradient(listOf(line.copy(alpha = 0.75f * fade * up), Color.Transparent), Offset(x, cy), 14.dp.toPx()), 14.dp.toPx(), Offset(x, cy))
                    drawCircle(line.copy(alpha = fade * up), 2.6.dp.toPx(), Offset(x, cy))
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(height).padding(horizontal = side), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            DeviceNode(DevxIcons.Mobile, active = true, glow = up * (0.5f + 0.5f * breathe))
            DeviceNode(computerIcon, active = visual != LinkVisual.Offline, glow = up * (0.5f + 0.5f * breathe))
        }
    }
}

@Composable
private fun DeviceNode(icon: ImageVector, active: Boolean, glow: Float) {
    val outline = DevxColors.Outline
    val glowColor = DevxColors.TextPrimary
    Box(
        Modifier.size(64.dp)
            .drawBehind { if (glow > 0f) drawCircle(Brush.radialGradient(listOf(glowColor.copy(alpha = 0.16f * glow), Color.Transparent), center, size.minDimension * 0.95f), size.minDimension * 0.95f) }
            .clip(CircleShape).background(DevxColors.Card)
            .drawBehind { drawCircle(outline, size.minDimension / 2 - 0.5f, style = Stroke(1.dp.toPx())) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (active) DevxColors.TextPrimary else DevxColors.TextDisabled, modifier = Modifier.size(28.dp))
    }
}

/** The six digits both screens show while a phone is paired: they appear one by one, so that the eye reads them. */
@Composable
fun SasDigits(code: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        code.forEachIndexed { i, ch ->
            val shown = remember(code) { Animatable(0f) }
            LaunchedEffect(code) { kotlinx.coroutines.delay(110L * i); shown.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
            if (i == 3) Box(Modifier.size(width = 10.dp, height = 2.dp))
            Box(
                Modifier.size(width = 44.dp, height = 58.dp).graphicsLayer { alpha = shown.value; translationY = (1f - shown.value) * 18.dp.toPx(); scaleX = 0.9f + 0.1f * shown.value; scaleY = 0.9f + 0.1f * shown.value }
                    .clip(RoundedCornerShape(14.dp)).background(DevxColors.Card),
                contentAlignment = Alignment.Center,
            ) {
                Text(ch.toString(), style = androidx.compose.ui.text.TextStyle(fontFamily = DevxFonts.Sans, fontSize = 28.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = DevxColors.TextPrimary)
            }
        }
    }
}

/** A check that draws itself inside a ring: the moment a computer is paired. */
@Composable
fun SuccessMark(modifier: Modifier = Modifier, size: Dp = 120.dp) {
    val ring = remember { Animatable(0f) }
    val check = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        ring.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
        check.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
    }
    val color = DevxColors.TextPrimary
    Canvas(modifier.size(size)) {
        val c = center
        val r = this.size.minDimension * 0.36f
        drawArc(color.copy(alpha = 0.9f), -90f, 360f * ring.value, false, topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        val path = Path().apply {
            moveTo(c.x - r * 0.42f, c.y + r * 0.02f)
            lineTo(c.x - r * 0.1f, c.y + r * 0.34f)
            lineTo(c.x + r * 0.46f, c.y - r * 0.3f)
        }
        val measure = PathMeasure().apply { setPath(path, false) }
        val out = Path()
        measure.getSegment(0f, measure.length * check.value, out, true)
        drawPath(out, color, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** The corners of the scan frame breathe, and a thin line sweeps up and down: the camera is looking. */
@Composable
fun ScanFrame(modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "scan")
    val sweep by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "sweep")
    val pulse by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val color = Color.White
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val arm = minOf(w, h) * 0.16f
        val stroke = Stroke(4.dp.toPx() + 1.5.dp.toPx() * pulse, cap = StrokeCap.Round)
        val a = 0.75f + 0.25f * pulse
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(color.copy(alpha = a), Offset(x, y), Offset(x + dx * arm, y), stroke.width, StrokeCap.Round)
            drawLine(color.copy(alpha = a), Offset(x, y), Offset(x, y + dy * arm), stroke.width, StrokeCap.Round)
        }
        corner(0f, 0f, 1f, 1f); corner(w, 0f, -1f, 1f); corner(0f, h, 1f, -1f); corner(w, h, -1f, -1f)
        val y = h * (0.08f + 0.84f * sweep)
        drawLine(Brush.horizontalGradient(listOf(Color.Transparent, color.copy(alpha = 0.7f), Color.Transparent)), Offset(w * 0.08f, y), Offset(w * 0.92f, y), 2.dp.toPx())
    }
}

/** A small dot that says if the computer is reachable; it breathes while connecting. */
@Composable
fun StatusDot(visual: LinkVisual, modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "dot")
    val a by infinite.animateFloat(0.35f, 1f, infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    val color = when (visual) {
        LinkVisual.Online -> DevxColors.Success
        LinkVisual.Connecting -> DevxColors.Warning
        LinkVisual.Offline -> DevxColors.TextDisabled
    }
    Box(modifier.size(10.dp).alpha(if (visual == LinkVisual.Connecting) a else 1f).clip(CircleShape).background(color))
}
