package com.apvcode.devx.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import androidx.compose.runtime.CompositionLocalProvider


/**
 * Where the finger touched an item, and how tall the item is. The popup is placed relative to the
 * item's bottom edge, so [menuOffset] turns the touch point into "a little below the finger".
 * The popup itself never leaves the screen: if there is no room below it flips above the item,
 * and it slides sideways when it would cross the right edge.
 */
internal class PressState {
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
    var height by mutableIntStateOf(0)

    /** Left edge of the item in the window. */
    var left by mutableFloatStateOf(0f)
}

/** Remembers the touch position without taking the touch away from the item's own click handlers. */
internal fun Modifier.pressAnchor(state: PressState): Modifier = this
    .onSizeChanged { state.height = it.height }
    .onGloballyPositioned { state.left = it.positionInWindow().x }
    .pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            state.x = down.position.x
            state.y = down.position.y
        }
    }

@Composable
internal fun PressState.menuOffset(): DpOffset {
    val density = LocalDensity.current
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    return with(density) {
        // The popup is about this wide (it sizes itself to its longest label); keep a margin to the edges.
        val menuWidth = 260.dp
        val margin = 12.dp
        val itemLeft = left.toDp()
        val wanted = itemLeft + x.toDp() - 40.dp
        val clamped = wanted.coerceIn(margin, (screenWidth - menuWidth - margin).coerceAtLeast(margin))
        DpOffset(x = clamped - itemLeft, y = (y - height).toDp() + 28.dp)
    }
}

/**
 * How far the opening of the menu has got (0 closed, 1 open), shared with its rows: each row rises a little after the one above it.
 * [next] hands out the place of a row in the line (a row asks once, when it first appears).
 */
internal class MenuReveal(val progress: () -> Float) {
    var next = 0
}

internal val LocalMenuReveal = compositionLocalOf<MenuReveal?> { null }

/**
 * The menu: a card that grows out of the thing that was pressed (it keeps its corner there), with its rows following one after another;
 * on closing it folds back the same way. It is a window of its own, so the size of what is inside may change without moving it.
 */
@Composable
internal fun PopupMenu(expanded: Boolean, onDismiss: () -> Unit, offset: DpOffset, content: @Composable () -> Unit) {
    // A list holds many of these (one for every chat), and almost none is ever opened: nothing is made for a menu until it is.
    var opened by remember { mutableStateOf(false) }
    if (expanded) opened = true
    if (opened) OpenedPopupMenu(expanded, onDismiss, offset, content)
}

@Composable
private fun OpenedPopupMenu(expanded: Boolean, onDismiss: () -> Unit, offset: DpOffset, content: @Composable () -> Unit) {
    val progress = remember { Animatable(0f) }
    var present by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (expanded) {
            present = true
            progress.animateTo(1f, spring(dampingRatio = 0.74f, stiffness = 340f))
        } else if (present) {
            progress.animateTo(0f, tween(190, easing = FastOutLinearInEasing))
            present = false
        }
    }
    if (!present) return
    val density = LocalDensity.current
    val margin = with(density) { 8.dp.roundToPx() }
    val offsetPx = with(density) { IntOffset(offset.x.roundToPx(), offset.y.roundToPx()) }
    // Where the card ends up is known only when it is placed; the animation reads it as it draws.
    val origin = remember { floatArrayOf(0.5f, 0f) }
    val position = remember(offsetPx, margin) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
                val x = (anchorBounds.left + offsetPx.x).coerceIn(margin, maxX)
                val below = anchorBounds.bottom + offsetPx.y
                val above = anchorBounds.top - popupContentSize.height - offsetPx.y
                val fitsBelow = below + popupContentSize.height <= windowSize.height - margin
                val y = when {
                    fitsBelow -> below
                    above >= margin -> above
                    else -> (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)
                }
                val anchorX = ((anchorBounds.left + anchorBounds.right) / 2f - x) / popupContentSize.width.coerceAtLeast(1)
                origin[0] = anchorX.coerceIn(0f, 1f)
                origin[1] = if (y >= anchorBounds.top) 0f else 1f
                return IntOffset(x, y)
            }
        }
    }
    val maxHeight = (LocalConfiguration.current.screenHeightDp - 32).dp
    val reveal = remember { MenuReveal { progress.value } }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Surface(
            modifier = Modifier
                .padding(8.dp) // room for the shadow
                .graphicsLayer {
                    val p = progress.value
                    val scale = 0.7f + 0.3f * p
                    scaleX = scale
                    scaleY = scale
                    alpha = (p * 1.8f).coerceIn(0f, 1f)
                    transformOrigin = TransformOrigin(origin[0], origin[1])
                },
            shape = RoundedCornerShape(26.dp),
            color = DevxColors.Card,
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
        ) {
            CompositionLocalProvider(LocalMenuReveal provides reveal) {
                Column(
                    Modifier
                        .widthIn(min = 250.dp, max = 300.dp)
                        .width(IntrinsicSize.Max)
                        .heightIn(max = maxHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                ) { content() }
            }
        }
    }
}

@Composable
internal fun MenuRow(
    icon: ImageVector,
    label: String,
    danger: Boolean = false,
    trailing: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val color = when {
        !enabled -> DevxColors.TextDisabled
        danger -> DevxColors.Danger
        else -> DevxColors.TextPrimary
    }
    val reveal = LocalMenuReveal.current
    val place = remember { reveal?.let { it.next++ } ?: 0 }
    DropdownMenuItem(
        modifier = Modifier.graphicsLayer {
            val p = reveal?.let { ((it.progress() - minOf(place, 9) * 0.045f) / 0.55f).coerceIn(0f, 1f) } ?: 1f
            alpha = p
            translationY = (1f - p) * 10.dp.toPx()
        },
        text = { Text(label, style = DevxType.Body.copy(fontSize = 17.sp), color = color) },
        leadingIcon = { Icon(icon, null, tint = color, modifier = Modifier.size(24.dp)) },
        trailingIcon = trailing?.let { { Icon(it, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(22.dp)) } },
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
    )
}

