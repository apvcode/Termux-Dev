package com.apvcode.devx.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxFonts
import com.apvcode.devx.ui.theme.DevxType

/**
 * The first screen while the app wakes up and fetches the lists of models: the name fades and
 * settles in, and a thin line grows under it. It is not decoration for its own sake: the models
 * load behind it, for at most three seconds.
 */
@Composable
fun SplashScreen(modifier: Modifier = Modifier) {
    val word = remember { Animatable(0f) }
    val line = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        word.animateTo(1f, tween(800, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(Unit) {
        line.animateTo(1f, tween(1100, delayMillis = 250, easing = FastOutSlowInEasing))
    }
    Box(modifier.fillMaxSize().background(DevxColors.Bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "devx",
                style = DevxType.Large.copy(fontFamily = DevxFonts.Serif, fontSize = 52.sp, lineHeight = 60.sp),
                color = DevxColors.TextPrimary,
                modifier = Modifier.graphicsLayer {
                    alpha = word.value
                    val scale = 0.92f + 0.08f * word.value
                    scaleX = scale
                    scaleY = scale
                    translationY = (1f - word.value) * 12.dp.toPx()
                },
            )
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .width(64.dp)
                    .height(2.dp)
                    .graphicsLayer {
                        scaleX = line.value
                        alpha = line.value
                    }
                    .background(DevxColors.TextSecondary),
            )
        }
    }
}
