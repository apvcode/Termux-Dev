package com.apvcode.devx.ui.chat

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apvcode.devx.R
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * The greeting of an empty chat: a few lines that change every five seconds. Each new line arrives word by word, every word
 * rising out of a soft blur, while the old one lifts and melts away.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun RotatingGreeting(name: String, modifier: Modifier = Modifier, startDelayMs: Long = 450L) {
    val general = stringArrayResource(R.array.greetings)
    val byTime = stringResource(
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> R.string.greet_morning
            in 12..17 -> R.string.greet_day
            in 18..22 -> R.string.greet_evening
            else -> R.string.greet_night
        },
        name,
    )
    // The first line is always the one for this time of day; the others come in a different order every time the app opens.
    val lines = remember(name, general.toList(), byTime) { listOf(byTime) + general.map { it.replace("%1\$s", name) }.shuffled() }
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(lines) {
        while (true) {
            delay(5000)
            index = (index + 1) % lines.size
        }
    }
    AnimatedContent(
        targetState = index,
        // A fixed height for up to two lines: the block does not change size (nor jump) when a shorter or longer line comes.
        modifier = modifier.heightIn(min = 92.dp),
        contentAlignment = Alignment.Center,
        transitionSpec = {
            // The new line brings its own animation (word by word); the old one lifts a little and fades. Nothing is clipped to the
            // box of the text, so that no edge of it shows while words rise or blur.
            (EnterTransition.None togetherWith (fadeOut(tween(520, easing = FastOutSlowInEasing)) + slideOutVertically(tween(520, easing = FastOutSlowInEasing)) { -it / 5 }))
                .using(SizeTransform(clip = false) { _, _ -> tween(1) })
        },
        label = "greeting",
    ) { i -> WordReveal(lines[i % lines.size], if (i == 0) startDelayMs else 0L) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordReveal(text: String, startDelayMs: Long) {
    val words = remember(text) { text.split(' ').filter { it.isNotEmpty() } }
    FlowRow(
        modifier = Modifier.padding(horizontal = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        words.forEachIndexed { n, word ->
            val progress = remember(text, n) { Animatable(0f) }
            LaunchedEffect(text, n) {
                delay(startDelayMs + 90L * n)
                progress.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
            }
            val p = progress.value
            Text(
                word,
                style = DevxType.Large.copy(fontSize = 34.sp, lineHeight = 42.sp),
                color = DevxColors.TextPrimary,
                modifier = Modifier
                    .graphicsLayer {
                        translationY = (1f - p) * 22.dp.toPx()
                        val s = 0.92f + 0.08f * p
                        scaleX = s
                        scaleY = s
                        alpha = p
                        // Out of a soft blur: drawn by the layer itself (it is not cut to the box of the word).
                        val blurPx = 14.dp.toPx() * (1f - p)
                        if (Build.VERSION.SDK_INT >= 31 && blurPx > 0.5f) renderEffect = BlurEffect(blurPx, blurPx, TileMode.Decal)
                        clip = false
                    },
            )
        }
    }
}
