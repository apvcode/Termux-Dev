package com.apvcode.devx.ui.chat

import androidx.compose.ui.layout.onSizeChanged
import com.apvcode.devx.ui.components.rememberCopied
import com.apvcode.devx.ui.components.CopyIcon
import com.apvcode.devx.ui.components.pressable
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import kotlinx.coroutines.flow.sample
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.apvcode.devx.R
import com.apvcode.devx.data.Attachment
import com.apvcode.devx.data.AttachmentKind
import com.apvcode.devx.data.Msg
import com.apvcode.devx.data.parseBlocks
import com.apvcode.devx.model.Block
import com.apvcode.devx.model.Role
import com.apvcode.devx.ui.markdown.MarkdownText
import com.apvcode.devx.ui.components.DevxIcons
import com.apvcode.devx.ui.components.ErrorCard
import com.apvcode.devx.ui.components.MenuRow
import com.apvcode.devx.ui.components.PopupMenu
import com.apvcode.devx.ui.components.PressState
import com.apvcode.devx.ui.components.menuOffset
import com.apvcode.devx.ui.components.pressAnchor
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import java.util.Calendar
import java.util.Date

/** What a message can ask of the screen around it. */
class MessageActionsHost(
    val onSend: (String) -> Unit,
    val onSelectText: (String) -> Unit,
    val onEdit: (Msg) -> Unit,
    val onOpenSettings: () -> Unit,
    val onPickModel: () -> Unit,
    val onOpenPhoto: (Attachment) -> Unit,
    val onShowThinking: (Msg) -> Unit,
    val onShowSources: (Msg) -> Unit,
    val onOpenFile: (com.apvcode.devx.data.Artifact) -> Unit,
    val onShowSteps: (Msg) -> Unit,
    /** A long message of mine is about to fold up: if its beginning is above the screen, the list moves with it. */
    val onFold: (Msg) -> Unit = {},
)

/** [animate] is true only for a message that has just been added: old ones never replay it. */
@Composable
fun MessageItem(msg: Msg, vm: ChatViewModel, animate: Boolean, host: MessageActionsHost, modifier: Modifier = Modifier) {
    when (msg.role) {
        Role.User -> UserBubble(msg, vm, animate, host, modifier)
        Role.Assistant -> AssistantMessage(msg, vm, animate, host, modifier)
    }
}

// ---------- my messages ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(msg: Msg, vm: ChatViewModel, animate: Boolean, host: MessageActionsHost, modifier: Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val press = remember { PressState() }
    // A very long message of mine is folded to a few lines; it opens and closes with a tap. The state is kept while it scrolls away.
    val big = remember(msg.text) { looksBig(msg.text) }
    var open by androidx.compose.runtime.saveable.rememberSaveable(msg.id) { mutableStateOf(false) }
    val toggle = {
        if (open) host.onFold(msg)
        open = !open
    }

    // A message just sent from the composer flies out of the text field; any other new one just pops in.
    val origin = LocalSendOrigin.current
    val from = remember(msg.id) { if (animate) origin.take() else null }
    val flight = from?.let { rememberFlight(it) }

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Photos and videos stand big in the chat, above the bubble that holds only the words.
        if (msg.attachments.isNotEmpty()) {
            MessageMedia(
                msg.attachments, vm::previewOf, host.onOpenPhoto,
                Modifier.enterPop(animate).onSizeChanged { MediaMetrics.heights[msg.id] = it.height },
            )
        }
        if (msg.text.isNotBlank()) Box(Modifier.pressAnchor(press)) {
            Column(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .then(if (flight != null) Modifier.flying(flight) else Modifier.enterPop(animate))
                    .pressable(
                        scaleTo = 0.97f,
                        haptic = false,
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menu = true
                        },
                        onClick = { if (big) toggle() },
                    )
                    .clip(RoundedCornerShape(24.dp))
                    .then(if (flight != null) Modifier.flightBackground(flight, DevxColors.Card) else Modifier.background(DevxColors.Card))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (big) FoldedText(msg.text, open, toggle)
                else Text(msg.text, style = DevxType.Body, color = DevxColors.TextPrimary)
            }
            PopupMenu(expanded = menu, onDismiss = { menu = false }, offset = press.menuOffset()) {
                Text(
                    whenLabel(context, msg.createdAt),
                    style = DevxType.Caption,
                    color = DevxColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                )
                MenuRow(DevxIcons.Copy, stringResource(R.string.msg_copy)) {
                    clipboard.setText(AnnotatedString(msg.text))
                    menu = false
                }
                MenuRow(DevxIcons.FileText, stringResource(R.string.msg_select)) {
                    menu = false
                    host.onSelectText(msg.text)
                }
                MenuRow(DevxIcons.Pencil, stringResource(R.string.msg_edit), enabled = !vm.generating) {
                    menu = false
                    host.onEdit(msg)
                }
                MenuRow(DevxIcons.Share, stringResource(R.string.msg_share)) {
                    menu = false
                    share(context, msg.text)
                }
            }
        }
        vm.branchOf(msg)?.let { (index, count) ->
            BranchSwitcher(index, count, onPrev = { vm.switchBranch(msg, -1) }, onNext = { vm.switchBranch(msg, 1) })
        }
    }
}

/** Lines a folded message shows, and how long a message must be (in lines, as near as it can be told without laying it out) to be folded. */
private const val FOLDED_LINES = 4
private const val BIG_LINES = 16

private fun looksBig(text: String): Boolean {
    if (text.length < 500) return false
    var lines = 0
    for (line in text.lineSequence()) {
        lines += maxOf(1, (line.length + 33) / 34)
        if (lines > BIG_LINES) return true
    }
    return false
}

/**
 * A very long message: a few lines with the last one fading out and a round button, and the whole of it when opened. The height
 * glides between the two (the text is laid out once, the box around it grows and shrinks), the fade and the arrow follow the
 * same curve, so nothing jumps and the messages around it are only pushed.
 */
@Composable
private fun FoldedText(text: String, open: Boolean, onToggle: () -> Unit) {
    val glide = tween<Float>(340, easing = FastOutSlowInEasing)
    val fade by androidx.compose.animation.core.animateFloatAsState(if (open) 0f else 1f, glide, label = "fold-fade")
    val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, glide, label = "fold-turn")
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .animateContentSize(tween(340, easing = FastOutSlowInEasing))
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    if (fade > 0.001f) {
                        val strip = minOf(46.dp.toPx(), size.height)
                        drawRect(
                            brush = Brush.verticalGradient(
                                listOf(Color.Black, Color.Black.copy(alpha = 1f - fade)),
                                startY = size.height - strip,
                                endY = size.height,
                            ),
                            topLeft = Offset(0f, size.height - strip),
                            size = Size(size.width, strip),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                },
        ) {
            Text(
                text,
                style = DevxType.Body,
                color = DevxColors.TextPrimary,
                maxLines = if (open) Int.MAX_VALUE else FOLDED_LINES,
                overflow = TextOverflow.Clip,
            )
            // Room under the last line for the button, so it does not sit on the words once they are all shown.
            if (open) Spacer(Modifier.height(38.dp))
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(34.dp)
                .clip(CircleShape)
                .background(DevxColors.Outline)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                DevxIcons.ChevronDown,
                contentDescription = stringResource(if (open) R.string.msg_fold else R.string.msg_unfold),
                tint = DevxColors.TextPrimary,
                modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = turn },
            )
        }
    }
}

// ---------- the assistant's messages ----------

@Composable
private fun AssistantMessage(msg: Msg, vm: ChatViewModel, animate: Boolean, host: MessageActionsHost, modifier: Modifier) {
    val streaming = vm.isStreaming(msg)
    // While the answer is written it is taken up about twelve times a second, not with every little piece that arrives: the
    // words still come in smoothly (that is the job of the reveal), but the text is not parsed and laid out for each piece.
    val text = sampledText(msg, streaming)
    val blocks = remember(text) { parseBlocks(text) }
    Column(modifier.fillMaxWidth()) {
    // While the answer is being made there is always a sign of it, whatever the model does this moment (thinking, searching,
    // waiting for the line). It stays at the top of the message, above the words, and does not travel down after the text;
    // when the answer is whole, it folds away softly and the text settles into its place.
    AnimatedVisibility(
        visible = streaming && msg.error == null,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(220)) + shrinkVertically(tween(360)),
    ) {
        Column(Modifier.padding(bottom = 12.dp)) {
            when {
                msg.reconnect > 0 -> ReconnectRow(msg.reconnect)
                msg.activity != null -> ToolActivityRow(msg, onClick = { host.onShowThinking(msg) })
                else -> {
                    val headline = rememberThinkingHeadline(msg)
                    ThinkingLabel(
                        text = headline,
                        enabled = msg.reasoning.isNotBlank() || msg.steps.isNotEmpty(),
                        onClick = { host.onShowThinking(msg) },
                    )
                    if (msg.text.isEmpty() && msg.sources.isNotEmpty()) ToolActivityRow(msg, onClick = { host.onShowThinking(msg) })
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEach { block ->
            when (block) {
                is Block.Text -> SelectionContainer { MarkdownText(block.markdown, animate = animate) }
                is Block.Code -> CodeView(block.language, block.code)
                is Block.File -> RevealBlock(animate) { ArtifactCard(block.id, vm, host.onOpenFile) }
                is Block.Svg -> RevealBlock(animate) { SvgView(block.caption, block.svg) }
                is Block.Ui -> RevealBlock(animate) { GenerativeCard(block.json, host.onSend) }
                is Block.Approval -> ApprovalCard(block.command)
                Block.Building -> BuildingCard()
            }
        }
        if (msg.stopped && !streaming) {
            Text(stringResource(R.string.stopped_note), style = DevxType.Caption, color = DevxColors.TextDisabled)
        }
        // The assistant waits for a "yes" before it uses a server: the card rises under the text, and sinks when it is answered.
        val asked = msg.approval
        var lastAsk by remember { mutableStateOf<com.apvcode.devx.data.PendingApproval?>(null) }
        if (asked != null) lastAsk = asked
        AnimatedVisibility(
            visible = asked != null,
            enter = androidx.compose.animation.slideInVertically(androidx.compose.animation.core.spring(dampingRatio = 0.72f, stiffness = 320f)) { it / 2 } +
                androidx.compose.animation.scaleIn(androidx.compose.animation.core.spring(dampingRatio = 0.78f, stiffness = 360f), initialScale = 0.9f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)) +
                androidx.compose.animation.expandVertically(androidx.compose.animation.core.spring(dampingRatio = 0.86f, stiffness = 360f)) + fadeIn(tween(240)),
            exit = androidx.compose.animation.slideOutVertically(tween(260)) { it / 3 } + androidx.compose.animation.scaleOut(tween(240), targetScale = 0.94f) + shrinkVertically(tween(280)) + fadeOut(tween(200)),
        ) { lastAsk?.let { ToolApprovalCard(it) } }
        msg.error?.let { kind ->
            ErrorCard(
                kind = kind,
                detail = msg.errorDetail,
                canContinue = msg.text.isNotBlank() || msg.steps.isNotEmpty(),
                onRetry = { vm.retry(msg) },
                onOpenSettings = host.onOpenSettings,
                onPickModel = host.onPickModel,
            )
        }
        if (!streaming && (msg.text.isNotBlank() || msg.error != null)) {
            AssistantActions(msg, vm, host)
        }
    }
    }
}

/** The line to the provider broke and is being tried again: what was written stays, the answer goes on when the line is back. */
@Composable
private fun ReconnectRow(attempt: Int) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ThinkingSparkle()
        Text(stringResource(R.string.chat_reconnecting, attempt), style = DevxType.Body, color = DevxColors.TextSecondary)
    }
}

/** Shown while a card or a picture is still being written by the model. */
@Composable
private fun BuildingCard() {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(DevxColors.Card)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ThinkingSparkle()
        Text(stringResource(R.string.card_building), style = DevxType.Body, color = DevxColors.TextSecondary)
    }
}

/** A non-text block that rises and fades in once. */
@Composable
private fun RevealBlock(animate: Boolean, content: @Composable () -> Unit) {
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (animate) progress.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
    }
    Box(
        Modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * 28.dp.toPx()
        },
    ) { content() }
}

@Composable
private fun AssistantActions(msg: Msg, vm: ChatViewModel, host: MessageActionsHost) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    val press = remember { PressState() }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (msg.text.isNotBlank()) {
            var copied by rememberCopied()
            ActionButton(onClick = {
                clipboard.setText(AnnotatedString(msg.text))
                copied = true
            }) {
                CopyIcon(copied, DevxColors.TextSecondary, stringResource(R.string.cd_copy), Modifier.size(20.dp))
            }
        }
        if (msg.error == null) {
            ActionIcon(DevxIcons.Refresh, stringResource(R.string.cd_regenerate), enabled = !vm.generating) { vm.regenerate(msg) }
        }
        vm.branchOf(msg)?.let { (index, count) ->
            BranchSwitcher(index, count, onPrev = { vm.switchBranch(msg, -1) }, onNext = { vm.switchBranch(msg, 1) })
        }
        if (msg.text.isNotBlank() || msg.reasoning.isNotBlank()) {
            Box(Modifier.pressAnchor(press)) {
                ActionIcon(Icons.Outlined.MoreVert, stringResource(R.string.cd_more)) { menu = true }
                PopupMenu(expanded = menu, onDismiss = { menu = false }, offset = press.menuOffset()) {
                    // The thoughts and the steps are one story now: one page shows both.
                    if (msg.reasoning.isNotBlank() || msg.steps.isNotEmpty()) {
                        MenuRow(DevxIcons.Brain, stringResource(R.string.msg_thinking)) {
                            menu = false
                            host.onShowThinking(msg)
                        }
                    }
                    // Right under "Thinking": the sites the assistant went to.
                    if (msg.sources.isNotEmpty()) {
                        MenuRow(DevxIcons.World, stringResource(R.string.menu_sources)) {
                            menu = false
                            host.onShowSources(msg)
                        }
                    }
                    MenuRow(DevxIcons.FileText, stringResource(R.string.msg_select)) {
                        menu = false
                        host.onSelectText(msg.text)
                    }
                    MenuRow(DevxIcons.Share, stringResource(R.string.msg_share)) {
                        menu = false
                        share(context, msg.text)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    ActionButton(enabled, onClick) {
        Icon(
            icon,
            description,
            tint = if (enabled) DevxColors.TextSecondary else DevxColors.TextDisabled,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ActionButton(enabled: Boolean = true, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .pressable(enabled = enabled, onClick = onClick)
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** "‹ 2/3 ›": the other versions of an edited message or a regenerated answer. */
@Composable
private fun BranchSwitcher(index: Int, count: Int, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).pressable(onClick = onPrev).clip(CircleShape), contentAlignment = Alignment.Center) {
            Icon(
                DevxIcons.ChevronRight,
                null,
                tint = DevxColors.TextSecondary,
                modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = 180f },
            )
        }
        Text("$index/$count", style = DevxType.Caption, color = DevxColors.TextSecondary)
        Box(Modifier.size(32.dp).pressable(onClick = onNext).clip(CircleShape), contentAlignment = Alignment.Center) {
            Icon(DevxIcons.ChevronRight, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(18.dp))
        }
    }
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** "Today, 3:12 AM" / "Yesterday, …" / "9 Nov, …". */
private fun whenLabel(context: Context, timestamp: Long): String {
    val time = DateFormat.getTimeFormat(context).format(Date(timestamp))
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }
    val now = Calendar.getInstance()
    fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        sameDay(then, now) -> context.getString(R.string.today_at, time)
        sameDay(then, yesterday) -> context.getString(R.string.yesterday_at, time)
        else -> DateFormat.format("d MMM", Date(timestamp)).toString() + ", " + time
    }
}

/** The photos and files of a message: photos as tiles that open full screen, files as chips. */

/** The text of a message; while [active] (it is being written) it follows the message at most every 80 ms. */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
private fun sampledText(msg: Msg, active: Boolean): String {
    var shown by remember(msg.id) { mutableStateOf(msg.text) }
    LaunchedEffect(msg.id, active) {
        if (active) {
            androidx.compose.runtime.snapshotFlow { msg.text }.sample(80).collect { shown = it }
        }
    }
    return if (active) shown else msg.text
}
