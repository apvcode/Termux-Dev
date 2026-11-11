package com.apvcode.devx.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
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

@Composable
internal fun PopupMenu(expanded: Boolean, onDismiss: () -> Unit, offset: DpOffset, content: @Composable () -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = offset,
        containerColor = DevxColors.Card,
        shape = RoundedCornerShape(26.dp),
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
        modifier = Modifier.widthIn(min = 250.dp),
    ) { content() }
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
    DropdownMenuItem(
        text = { Text(label, style = DevxType.Body.copy(fontSize = 17.sp), color = color) },
        leadingIcon = { Icon(icon, null, tint = color, modifier = Modifier.size(24.dp)) },
        trailingIcon = trailing?.let { { Icon(it, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(22.dp)) } },
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
    )
}

