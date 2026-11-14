package com.apvcode.devx.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.apvcode.devx.R
import com.apvcode.devx.ui.components.DevxIcons
import com.apvcode.devx.ui.components.glass
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType

/** The round mark of a chat that can be picked: an empty ring, which fills with a springy pop while a tick draws itself. */
@Composable
fun SelectDot(selected: Boolean, modifier: Modifier = Modifier) {
    val fill by animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 520f), label = "dot-fill")
    val tick by animateFloatAsState(if (selected) 1f else 0f, tween(300, delayMillis = if (selected) 80 else 0, easing = FastOutSlowInEasing), label = "dot-tick")
    val ring = DevxColors.TextDisabled
    val accent = DevxColors.Accent
    val onAccent = DevxColors.OnAccent
    Canvas(modifier.size(24.dp)) {
        val r = size.minDimension / 2f
        drawCircle(ring.copy(alpha = ring.alpha * (1f - fill.coerceIn(0f, 1f))), r - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
        if (fill > 0f) drawCircle(accent, r * fill.coerceAtLeast(0f))
        if (tick > 0f) {
            val w = size.width
            val path = Path().apply {
                moveTo(w * 0.27f, w * 0.52f)
                lineTo(w * 0.44f, w * 0.69f)
                lineTo(w * 0.75f, w * 0.33f)
            }
            val measure = PathMeasure().apply { setPath(path, false) }
            val shown = Path()
            measure.getSegment(0f, measure.length * tick, shown, true)
            drawPath(shown, onAccent, style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/**
 * The bar that takes the place of the buttons at the bottom while chats are being picked: cancel, how many are picked (the number
 * rolls), pick all or none, archive, delete. The two actions wake up when something is picked.
 */
@Composable
fun SelectionBar(count: Int, total: Int, onCancel: () -> Unit, onToggleAll: () -> Unit, onArchive: () -> Unit, onDelete: () -> Unit) {
    val ready by animateFloatAsState(if (count > 0) 1f else 0f, tween(240), label = "selection-ready")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(50.dp).pressable(scaleTo = 0.9f, onClick = onCancel).glass(CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Close, stringResource(R.string.select_cancel), tint = DevxColors.TextPrimary, modifier = Modifier.size(22.dp))
        }
        Row(
            Modifier.weight(1f).pressable(scaleTo = 0.96f, onClick = onToggleAll).clip(CircleShape).glass(CircleShape).padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AnimatedContent(
                targetState = count,
                transitionSpec = {
                    val up = targetState > initialState
                    (slideInVertically(spring(dampingRatio = 0.8f, stiffness = 500f)) { if (up) it else -it } + fadeIn(tween(160))) togetherWith
                        (slideOutVertically(tween(180)) { if (up) -it else it } + fadeOut(tween(120)))
                },
                label = "selected-count",
            ) { n ->
                Text(
                    if (n == 0) stringResource(R.string.select_pick) else stringResource(R.string.select_count, n),
                    style = DevxType.Body, color = DevxColors.TextPrimary, maxLines = 1,
                )
            }
            Box(Modifier.weight(1f))
            Text(stringResource(if (count == total && total > 0) R.string.select_none else R.string.select_all), style = DevxType.Caption, color = DevxColors.TextSecondary)
        }
        Box(
            Modifier.size(50.dp).graphicsLayer { alpha = 0.4f + 0.6f * ready }.pressable(enabled = count > 0, scaleTo = 0.9f, onClick = onArchive).glass(CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(DevxIcons.Archive, stringResource(R.string.menu_archive), tint = DevxColors.TextPrimary, modifier = Modifier.size(22.dp)) }
        Box(
            Modifier.size(50.dp).graphicsLayer { alpha = 0.4f + 0.6f * ready }.pressable(enabled = count > 0, scaleTo = 0.9f, onClick = onDelete).glass(CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(DevxIcons.ChatOff, stringResource(R.string.menu_delete), tint = DevxColors.Danger, modifier = Modifier.size(22.dp)) }
    }
}
