package com.apvcode.devx.ui.screens

import com.apvcode.devx.ui.components.pressable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.apvcode.devx.R
import com.apvcode.devx.ui.components.CircleIconButton
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import kotlinx.coroutines.delay
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.viewinterop.AndroidView
import com.apvcode.devx.data.Msg
import com.apvcode.devx.data.ToolActivity
import com.apvcode.devx.ui.chat.StepIcon
import com.apvcode.devx.ui.chat.ThinkingSparkle
import com.apvcode.devx.ui.markdown.MarkdownText
import java.io.File

/**
 * Edit one of my messages. Sending starts a new branch of the conversation from this message;
 * what was written before stays, reachable with the "‹ 1/2 ›" switch.
 */
@Composable
fun EditMessageScreen(initial: String, onClose: () -> Unit, onSend: (String) -> Unit, modifier: Modifier = Modifier) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        delay(340)
        focus.requestFocus()
        keyboard?.show()
    }
    val canSend = value.text.isNotBlank()
    Column(
        modifier
            .fillMaxSize()
            .background(DevxColors.Bg)
            .pointerInput(Unit) { detectTapGestures { } }
            .statusBarsPadding()
            .imePadding(),
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            CircleIconButton(
                icon = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.cd_close),
                onClick = onClose,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            Text(stringResource(R.string.edit_title), style = DevxType.Title, color = DevxColors.TextPrimary)
        }
        val banner = RoundedCornerShape(20.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(banner)
                .background(DevxColors.Card)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Outlined.Info, null, tint = DevxColors.TextPrimary, modifier = Modifier.size(22.dp))
            Text(stringResource(R.string.edit_info), style = DevxType.Body, color = DevxColors.TextPrimary)
        }
        Box(Modifier.weight(1f))
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(DevxColors.Card)
                .padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                maxLines = 8,
                textStyle = DevxType.Body.copy(color = DevxColors.TextPrimary),
                cursorBrush = SolidColor(DevxColors.Accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.weight(1f).focusRequester(focus).padding(vertical = 11.dp),
            )
            Box(
                Modifier
                    .size(44.dp)
                    .pressable(enabled = canSend) { onSend(value.text) }
                    .clip(CircleShape)
                    .background(if (canSend) DevxColors.Accent else DevxColors.Outline),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.ArrowUpward,
                    stringResource(R.string.cd_send),
                    tint = if (canSend) DevxColors.OnAccent else DevxColors.TextDisabled,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/** A plain page with the message text, free to select, as in the apps people already know. */
@Composable
fun SelectTextScreen(text: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .background(DevxColors.Bg)
            .pointerInput(Unit) { detectTapGestures { } }
            .statusBarsPadding(),
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            CircleIconButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.cd_back),
                onClick = onClose,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            Text(stringResource(R.string.select_title), style = DevxType.Title, color = DevxColors.TextPrimary)
        }
        Box(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
            SelectionContainer {
                Text(text, style = DevxType.Reply, color = DevxColors.TextPrimary)
            }
        }
    }
}

/** A photo at full size: pinch to zoom, drag to move it when zoomed. */
@Composable
fun PhotoViewer(file: Any, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .fillMaxSize()
            .background(DevxColors.Bg)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            },
    ) {
        AsyncImage(
            model = file,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        )
        CircleIconButton(
            icon = Icons.Outlined.Close,
            contentDescription = stringResource(R.string.cd_close),
            onClick = onClose,
            modifier = Modifier.statusBarsPadding().padding(16.dp),
        )
    }
}

/** A video played at full size, with the usual play, pause and seek controls. */
@Composable
fun VideoViewer(file: File, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { context ->
                val frame = FrameLayout(context)
                val video = VideoView(context)
                frame.addView(
                    video,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER,
                    ),
                )
                val controller = MediaController(context)
                controller.setAnchorView(frame)
                video.setMediaController(controller)
                video.setVideoPath(file.absolutePath)
                video.setOnPreparedListener { it.start() }
                frame
            },
            onRelease = { frame -> (frame.getChildAt(0) as? VideoView)?.stopPlayback() },
            modifier = Modifier.fillMaxSize(),
        )
        CircleIconButton(
            icon = Icons.Outlined.Close,
            contentDescription = stringResource(R.string.cd_close),
            onClick = onClose,
            modifier = Modifier.statusBarsPadding().padding(16.dp),
        )
    }
}

/**
 * What the model thought before it answered, with how long it took. It is live: while the model is
 * still thinking the text keeps coming, and the page follows the end unless you scrolled up.
 */
@Composable
fun ThinkingScreen(msg: Msg, streaming: Boolean, onClose: () -> Unit, modifier: Modifier = Modifier) {
    // The keyboard of the chat goes away as the page opens.
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    LaunchedEffect(Unit) {
        focus.clearFocus()
        keyboard?.hide()
    }
    // Everything in the order it happened: thoughts, then the step they led to, then more thoughts. The thoughts are cut
    // into small pieces, and only the pieces on the screen are drawn: a long train of thought must not slow the page down.
    val text = msg.reasoning
    val steps = msg.steps.toList()
    val items = remember(text, steps) { thinkingItems(text, steps) }
    // The page opens at its very end, where the latest thoughts are (the first item is the heading, so the last one is [items.size]).
    val list = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = items.size)
    var follow by remember { mutableStateOf(true) }
    // Until the end has been reached the list is not shown, so that nobody sees it jump down.
    var placed by remember { mutableStateOf(false) }
    val shown by androidx.compose.animation.core.animateFloatAsState(if (placed) 1f else 0f, androidx.compose.animation.core.tween(140), label = "thinking-shown")
    LaunchedEffect(Unit) {
        if (items.isNotEmpty()) {
            list.scrollToItem(items.size)
            list.scrollBy(100_000f)
        }
        placed = true
    }
    LaunchedEffect(list) {
        androidx.compose.runtime.snapshotFlow { list.isScrollInProgress }.collect { moving ->
            // When the finger lets go: keep following only if it was left at the very end.
            if (!moving) follow = !list.canScrollForward
        }
    }
    val activeLast = streaming && msg.activity != null
    LaunchedEffect(items.size, text.length / 300) {
        if (follow && streaming && items.isNotEmpty()) {
            list.scrollToItem(items.size)
            list.scrollBy(100_000f)
        }
    }
    Column(
        modifier
            .fillMaxSize()
            .background(DevxColors.Bg)
            .pointerInput(Unit) { detectTapGestures { } }
            .statusBarsPadding(),
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            CircleIconButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.cd_back),
                onClick = onClose,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            Text(stringResource(R.string.thinking_title), style = DevxType.Title, color = DevxColors.TextPrimary)
        }
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = shown },
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "head") {
                    if (streaming) {
                        ThinkingSparkle()
                    } else if (msg.thoughtSeconds > 0) {
                        Text(stringResource(R.string.thought_for, msg.thoughtSeconds), style = DevxType.Caption, color = DevxColors.TextSecondary)
                    }
                }
                items(items.size, key = { it }) { i ->
                    when (val item = items[i]) {
                        is ThinkingItem.Words -> MarkdownText(item.text)
                        is ThinkingItem.Step -> StepRow(item.step, active = activeLast && i == items.lastIndex)
                    }
                }
            }
        }
    }
}

/** One thing on the thinking page: a piece of the thoughts, or a step the assistant took. */
internal sealed interface ThinkingItem {
    data class Words(val text: String) : ThinkingItem
    data class Step(val step: ToolActivity) : ThinkingItem
}

internal fun thinkingItems(text: String, steps: List<ToolActivity>): List<ThinkingItem> {
    val out = ArrayList<ThinkingItem>()
    var from = 0
    steps.forEach { step ->
        val to = step.at.coerceIn(from, text.length)
        if (to > from) reasoningChunks(text.substring(from, to)).forEach { out += ThinkingItem.Words(it) }
        from = to
        out += ThinkingItem.Step(step)
    }
    if (from < text.length) reasoningChunks(text.substring(from)).forEach { out += ThinkingItem.Words(it) }
    return out
}

/**
 * The thoughts cut into pieces of a few paragraphs each, never inside a fenced block of code. The cut points depend
 * only on the text before them, so while the thoughts grow the earlier pieces stay as they are.
 */
internal fun reasoningChunks(text: String, soft: Int = 700, hard: Int = 3_000): List<String> {
    if (text.isBlank()) return emptyList()
    val out = ArrayList<String>()
    val current = StringBuilder()
    var fenced = false
    for (line in text.lines()) {
        if (line.trimStart().startsWith("```")) fenced = !fenced
        if (!fenced) {
            val blank = line.isBlank()
            if ((blank && current.length >= soft) || current.length >= hard) {
                if (current.isNotBlank()) out += current.toString().trim()
                current.setLength(0)
                if (blank) continue
            }
        }
        current.append(line).append('\n')
    }
    if (current.isNotBlank()) out += current.toString().trim()
    return out
}

/** One thing the assistant did: what, on what, and what came of it. */
@Composable
private fun StepRow(step: ToolActivity, active: Boolean) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "step")
    val pulse by transition.animateFloat(
        0.5f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(900, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    Row(
        Modifier.fillMaxWidth().graphicsLayer { alpha = if (active) pulse else 1f },
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { StepIcon(step.kind) }
        Column(Modifier.weight(1f)) {
            Text(com.apvcode.devx.ui.chat.stepLabel(step.kind), style = DevxType.Body, color = DevxColors.TextPrimary)
            if (step.detail.isNotBlank()) Text(step.detail, style = DevxType.Caption, color = DevxColors.TextSecondary)
            if (step.note.isNotBlank()) Text(step.note, style = DevxType.Caption, color = DevxColors.TextDisabled)
        }
    }
}
