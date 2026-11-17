package com.apvcode.devx.ui.theme

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.hypot
import kotlin.math.max

/**
 * Changing the theme as a ripple: a picture of the screen as it is stays on top while the new theme is put on underneath, and a circle
 * that grows from the point that was tapped uncovers it, with a thin glowing rim in the new accent. The picture is taken from the window
 * only at the moment of the change, so nothing is paid for it the rest of the time.
 */
object ThemeReveal {
    class Request(val center: Offset, val apply: () -> Unit)

    internal var request by mutableStateOf<Request?>(null)

    /** Changes the theme with the ripple, starting at [center] (in window coordinates). [apply] does the change itself. */
    fun start(center: Offset, apply: () -> Unit) {
        // A second tap while one ripple is running just changes the theme.
        if (request != null) apply() else request = Request(center, apply)
    }
}

private val Soft = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/** Takes a copy of the window; null when the phone does not give one (then the theme just changes). */
private suspend fun capture(view: android.view.View): ImageBitmap? {
    val activity = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }.firstOrNull { it is Activity } as? Activity ?: return null
    if (view.width <= 0 || view.height <= 0) return null
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    return suspendCancellableCoroutine { cont ->
        runCatching {
            PixelCopy.request(activity.window, bitmap, { result -> if (cont.isActive) cont.resume(if (result == PixelCopy.SUCCESS) bitmap.asImageBitmap() else null) }, Handler(Looper.getMainLooper()))
        }.onFailure { if (cont.isActive) cont.resume(null) }
    }
}

@Composable
fun ThemeRevealHost(content: @Composable () -> Unit) {
    val view = LocalView.current
    var shot by remember { mutableStateOf<ImageBitmap?>(null) }
    var center by remember { mutableStateOf(Offset.Zero) }
    val progress = remember { Animatable(0f) }
    val pending = ThemeReveal.request
    LaunchedEffect(pending) {
        val r = pending ?: return@LaunchedEffect
        try {
            val picture = capture(view)
            if (picture == null) { r.apply(); return@LaunchedEffect }
            // The picture goes on top first, then the theme changes under it, so there is no flash of the new theme.
            center = r.center
            progress.snapTo(0f)
            shot = picture
            kotlinx.coroutines.android.awaitFrame()
            r.apply()
            kotlinx.coroutines.android.awaitFrame()
            progress.animateTo(1f, tween(720, easing = Soft))
        } finally {
            shot = null
            ThemeReveal.request = null
        }
    }
    Box(Modifier.fillMaxSize()) {
        content()
        shot?.let { picture ->
            val rim = DevxColors.Accent
            Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
                val far = max(max(hypot(center.x, center.y), hypot(size.width - center.x, center.y)), max(hypot(center.x, size.height - center.y), hypot(size.width - center.x, size.height - center.y)))
                val radius = far * progress.value
                drawImage(picture)
                // The hole: what the circle has reached shows the new theme.
                drawCircle(Color.Black, radius, center, blendMode = BlendMode.Clear)
                // A soft rim at the edge, fading as the circle reaches the corners.
                val fade = (1f - progress.value).coerceIn(0f, 1f)
                if (radius > 4f) drawCircle(
                    brush = Brush.radialGradient(
                        0f to Color.Transparent,
                        0.965f to Color.Transparent,
                        0.985f to rim.copy(alpha = 0.55f * fade),
                        1f to Color.Transparent,
                        center = center, radius = radius + 14.dp.toPx(),
                    ),
                    radius = radius + 14.dp.toPx(), center = center,
                )
                if (radius > 4f) drawCircle(rim.copy(alpha = 0.35f * fade), radius, center, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}
