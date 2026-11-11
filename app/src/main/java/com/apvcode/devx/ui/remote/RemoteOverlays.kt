package com.apvcode.devx.ui.remote

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.apvcode.devx.ui.components.PillButton
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import kotlinx.coroutines.delay

class CornerItem(val icon: ImageVector, val label: String, val danger: Boolean = false, val onClick: () -> Unit)

/**
 * A small menu that opens out of the corner it hangs from: the card grows with a spring, its rows come in one after another.
 * It sits inside the screen (not in a window), rounded all around.
 */
@Composable
fun CornerMenu(open: Boolean, onDismiss: () -> Unit, items: List<CornerItem>, modifier: Modifier = Modifier) {
    if (open) BackHandler(onBack = onDismiss)
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 62.dp
    Box(modifier.fillMaxSize()) {
        if (open) Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
        AnimatedVisibility(
            visible = open,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = top, end = 16.dp),
            enter = scaleIn(spring(dampingRatio = 0.7f, stiffness = 420f), initialScale = 0.4f, transformOrigin = TransformOrigin(1f, 0f)) + fadeIn(tween(160)),
            exit = scaleOut(tween(170), targetScale = 0.8f, transformOrigin = TransformOrigin(1f, 0f)) + fadeOut(tween(130)),
        ) {
            val shape = RoundedCornerShape(30.dp)
            Column(
                Modifier.width(276.dp).shadow(18.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                    .clip(shape).background(DevxColors.Card).border(1.dp, DevxColors.Outline.copy(alpha = 0.7f), shape).padding(8.dp),
            ) {
                items.forEachIndexed { i, item -> CornerRow(i, item, onDismiss) }
            }
        }
    }
}

@Composable
private fun CornerRow(index: Int, item: CornerItem, onDone: () -> Unit) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(70L + index * 55L); appear.animateTo(1f, tween(300, easing = FastOutSlowInEasing)) }
    val ink = if (item.danger) DevxColors.Danger else DevxColors.TextPrimary
    Row(
        Modifier.fillMaxWidth().graphicsLayer { alpha = appear.value; translationX = (1f - appear.value) * 18.dp.toPx() }
            .clip(RoundedCornerShape(22.dp)).pressable(scaleTo = 0.97f, haptic = false) { onDone(); item.onClick() }.padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(if (item.danger) DevxColors.Danger.copy(alpha = 0.14f) else DevxColors.CardInner), contentAlignment = Alignment.Center) {
            Icon(item.icon, null, tint = ink, modifier = Modifier.size(20.dp))
        }
        Text(item.label, style = DevxType.Body, color = ink, maxLines = 1)
    }
}

/**
 * Asks "are you sure" in a card that rises into the middle of the screen with a spring while the screen dims.
 * The badge lands with a little overshoot and the texts and buttons follow it one after another.
 */
@Composable
fun ConfirmCard(open: Boolean, onDismiss: () -> Unit, icon: ImageVector, title: String, body: String, confirm: String, cancel: String, onConfirm: () -> Unit, modifier: Modifier = Modifier) {
    if (open) BackHandler(onBack = onDismiss)
    val scrim by animateFloatAsState(if (open) 0.62f else 0f, tween(280), label = "scrim")
    Box(modifier.fillMaxSize()) {
        if (scrim > 0.001f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim)).then(if (open) Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss) else Modifier))
        AnimatedVisibility(
            visible = open,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
            enter = scaleIn(spring(dampingRatio = 0.74f, stiffness = 360f), initialScale = 0.82f) + slideInVertically(spring(dampingRatio = 0.8f, stiffness = 340f)) { it / 5 } + fadeIn(tween(220)),
            exit = scaleOut(tween(200), targetScale = 0.92f) + fadeOut(tween(180)),
        ) {
            val shape = RoundedCornerShape(34.dp)
            Column(
                Modifier.fillMaxWidth().shadow(24.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                    .clip(shape).background(DevxColors.Card).border(1.dp, DevxColors.Outline.copy(alpha = 0.7f), shape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val badge = remember { Animatable(0.3f) }
                LaunchedEffect(Unit) { badge.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 300f)) }
                Box(
                    Modifier.size(68.dp).graphicsLayer { scaleX = badge.value; scaleY = badge.value; alpha = badge.value.coerceIn(0f, 1f) }
                        .clip(CircleShape).background(DevxColors.Danger.copy(alpha = 0.15f)).border(1.dp, DevxColors.Danger.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, null, tint = DevxColors.Danger, modifier = Modifier.size(30.dp)) }
                CardRise(80) { Text(title, style = DevxType.Title, color = DevxColors.TextPrimary, textAlign = TextAlign.Center) }
                CardRise(150) { Text(body, style = DevxType.Body, color = DevxColors.TextSecondary, textAlign = TextAlign.Center) }
                CardRise(230) {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton(cancel, onClick = onDismiss, filled = false, modifier = Modifier.weight(1f))
                        PillButton(confirm, onClick = onConfirm, container = DevxColors.Danger, content = Color.White, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CardRise(delayMs: Long, content: @Composable () -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(delayMs); a.animateTo(1f, tween(360, easing = FastOutSlowInEasing)) }
    Box(Modifier.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 14.dp.toPx() }) { content() }
}
