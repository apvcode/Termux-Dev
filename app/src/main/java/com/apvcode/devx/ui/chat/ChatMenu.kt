package com.apvcode.devx.ui.chat

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.IconCompat
import com.apvcode.devx.MainActivity
import com.apvcode.devx.R
import com.apvcode.devx.data.Attachment
import com.apvcode.devx.data.AttachmentKind
import com.apvcode.devx.data.Chat
import com.apvcode.devx.data.activePath
import com.apvcode.devx.ui.components.CircleIconButton
import com.apvcode.devx.ui.components.DevxIcons
import com.apvcode.devx.ui.components.MenuRow
import com.apvcode.devx.ui.components.PillButton
import com.apvcode.devx.ui.components.PopupMenu
import com.apvcode.devx.ui.components.glass
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import java.io.File

/** What the chat menu hands back to the screen: things only the screen can do. */
class ChatMenuActions(
    val onShare: () -> Unit,
    val onToggleLock: () -> Unit,
    val onUploaded: () -> Unit,
    val onFind: () -> Unit,
    val onShorten: () -> Unit,
    val onExportMarkdown: () -> Unit,
    val onExportPdf: () -> Unit,
    val onAddToHome: () -> Unit,
    val onDelete: () -> Unit,
)

/**
 * The three dots of a chat and the menu they open: the chat's name, share, pin, lock, add to a
 * project (a second page that slides in), the files you uploaded, search in the chat, a shortcut on
 * the home screen, archive and delete.
 */
@Composable
fun ChatMenuButton(chat: Chat, vm: ChatViewModel, actions: ChatMenuActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(width = 44.dp, height = 42.dp).pressable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.MoreVert, stringResource(R.string.cd_more), tint = DevxColors.TextPrimary, modifier = Modifier.size(22.dp))
        }
        var page by remember(open) { mutableIntStateOf(0) }
        // Counted in the background when the menu opens: it reads what the next request would carry.
        var estimate by remember(open) { mutableStateOf<Int?>(null) }
        LaunchedEffect(open) { if (open) estimate = runCatching { vm.contextEstimate(chat) }.getOrNull() }
        PopupMenu(expanded = open, onDismiss = { open = false }, offset = DpOffset((-4).dp, 0.dp)) {
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val deeper = targetState > initialState
                    val shift = { full: Int -> if (deeper) full / 3 else -full / 3 }
                    (slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = 520f), shift) + fadeIn(tween(200, delayMillis = 60))) togetherWith
                        (slideOutHorizontally(tween(220)) { -shift(it) } + fadeOut(tween(120))) using
                        SizeTransform(clip = true) { _, _ -> spring(dampingRatio = 0.88f, stiffness = 500f) }
                },
                label = "chat-actions-page",
            ) { shown ->
                Column {
                    if (shown == 0) {
                        // The name of this chat in full (it wraps instead of being cut), animated while the model is still writing it.
                        com.apvcode.devx.ui.components.AnimatedChatTitle(
                            title = chat.title,
                            naming = chat.naming,
                            style = DevxType.BodyStrong.copy(fontSize = 14.sp, lineHeight = 19.sp),
                            color = DevxColors.TextPrimary,
                            maxLines = 3,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = if (chat.messages.isEmpty()) 6.dp else 2.dp),
                        )
                        // What the provider counted, when it said; otherwise the rough estimate, set against the model's window.
                        val shown = chat.activePath()
                        val cutAt = chat.summaryUpTo?.let { id -> shown.indexOfFirst { it.id == id } } ?: -1
                        // A count from before a shortening no longer says how big the chat is.
                        val counted = shown.withIndex().lastOrNull { it.value.contextTokens > 0 && it.index > cutAt }?.value
                        val limit = remember(chat.model) { chat.model?.model?.let { com.apvcode.devx.data.ModelLimits.of(it) } }
                        val used = counted?.contextTokens ?: estimate
                        if (used != null && limit != null && chat.messages.isNotEmpty()) {
                            ContextMeter(used, limit, estimated = counted == null)
                            if (counted != null) {
                                Text(
                                    stringResource(R.string.menu_usage, tokenLabel(chat.activePath().sumOf { it.tokensIn }), tokenLabel(chat.activePath().sumOf { it.tokensOut })),
                                    style = DevxType.Caption,
                                    color = DevxColors.TextDisabled,
                                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 6.dp),
                                )
                            } else Spacer(Modifier.height(6.dp))
                        }
                        MenuRow(DevxIcons.Share, stringResource(R.string.msg_share)) { open = false; actions.onShare() }
                        MenuRow(DevxIcons.Pin, stringResource(if (chat.pinned) R.string.menu_unpin else R.string.menu_pin)) {
                            open = false
                            vm.togglePin(chat.id)
                        }
                        MenuRow(
                            if (chat.locked) DevxIcons.LockOpen else DevxIcons.Lock,
                            stringResource(if (chat.locked) R.string.menu_unlock else R.string.menu_lock),
                            // Locking needs the app lock to be on and to cover chats; a chat that is already locked can always be released.
                            enabled = chat.locked || com.apvcode.devx.data.AppLock.locksChats,
                        ) { open = false; actions.onToggleLock() }
                        MenuRow(
                            DevxIcons.Minimize,
                            stringResource(if (vm.compacting == chat.id) R.string.menu_shortening else R.string.menu_shorten),
                            // Only a chat that is long enough, and that is not being answered now.
                            enabled = chat.activePath().size >= 8 && !vm.generating && vm.compacting == null,
                        ) { open = false; actions.onShorten() }
                        MenuRow(DevxIcons.FolderPlus, stringResource(R.string.menu_add_to_project), trailing = DevxIcons.ChevronRight) { page = 1 }
                        MenuRow(DevxIcons.Download, stringResource(R.string.menu_export), trailing = DevxIcons.ChevronRight) { page = 2 }
                        MenuRow(DevxIcons.Paperclip, stringResource(R.string.menu_uploaded)) { open = false; actions.onUploaded() }
                        MenuRow(DevxIcons.Search, stringResource(R.string.menu_find)) { open = false; actions.onFind() }
                        MenuRow(DevxIcons.Home, stringResource(R.string.menu_add_home)) { open = false; actions.onAddToHome() }
                        MenuRow(DevxIcons.Archive, stringResource(R.string.menu_archive)) {
                            open = false
                            vm.archiveChat(chat.id)
                            vm.newChat()
                        }
                        MenuRow(DevxIcons.ChatOff, stringResource(R.string.menu_delete), danger = true) { open = false; actions.onDelete() }
                    } else if (shown == 2) {
                        MenuRow(DevxIcons.ArrowLeft, stringResource(R.string.menu_export)) { page = 0 }
                        HorizontalDivider(color = DevxColors.Outline, thickness = 0.5.dp)
                        MenuRow(DevxIcons.Book, stringResource(R.string.export_markdown)) { open = false; actions.onExportMarkdown() }
                        MenuRow(DevxIcons.Download, stringResource(R.string.export_pdf)) { open = false; actions.onExportPdf() }
                    } else {
                        MenuRow(DevxIcons.ArrowLeft, stringResource(R.string.menu_add_to_project)) { page = 0 }
                        HorizontalDivider(color = DevxColors.Outline, thickness = 0.5.dp)
                        vm.projects.forEach { name ->
                            MenuRow(
                                DevxIcons.Folder,
                                name,
                                trailing = if (chat.project == name) DevxIcons.Check else null,
                            ) {
                                vm.moveToProject(chat.id, name)
                                open = false
                            }
                        }
                        MenuRow(
                            DevxIcons.ChatList,
                            stringResource(R.string.menu_no_project),
                            trailing = if (chat.project == null) DevxIcons.Check else null,
                        ) {
                            vm.moveToProject(chat.id, null)
                            open = false
                        }
                    }
                }
            }
        }
    }
}

/** Ask once more before a chat is thrown away. */
@Composable
fun ConfirmDeleteChat(chat: Chat, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DevxColors.Card,
        shape = RoundedCornerShape(28.dp),
        iconContentColor = DevxColors.Danger,
        titleContentColor = DevxColors.TextPrimary,
        textContentColor = DevxColors.TextSecondary,
        icon = { Icon(DevxIcons.ChatOff, null, modifier = Modifier.size(34.dp)) },
        title = { Text(stringResource(R.string.dialog_delete_chat), style = DevxType.Title) },
        text = { Text(chat.title + "\n" + stringResource(R.string.dialog_delete_chat_text), style = DevxType.Body) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) { Text(stringResource(R.string.menu_delete), color = DevxColors.Danger) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel), color = DevxColors.TextSecondary) }
        },
    )
}

/** Everything that was uploaded into this chat, pictures and videos as tiles, other files as a list. */
@Composable
fun UploadedFilesScreen(
    files: List<Attachment>,
    previewOf: (Attachment) -> File,
    onOpen: (Attachment) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(DevxColors.Bg)
            .pointerInput(Unit) { detectTapGestures { } }
            .statusBarsPadding(),
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            CircleIconButton(
                icon = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.cd_close),
                onClick = onClose,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            Text(stringResource(R.string.uploaded_title), style = DevxType.Title, color = DevxColors.TextPrimary)
        }
        if (files.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(horizontal = 40.dp)) {
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(DevxColors.Card), contentAlignment = Alignment.Center) {
                        Icon(DevxIcons.Paperclip, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(28.dp))
                    }
                    Text(stringResource(R.string.uploaded_empty), style = DevxType.Body, color = DevxColors.TextPrimary, textAlign = TextAlign.Center)
                }
            }
        } else {
            val media = files.filter { it.kind == AttachmentKind.Image || it.kind == AttachmentKind.Video }
            val others = files.filter { it.kind != AttachmentKind.Image && it.kind != AttachmentKind.Video }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(media, key = { it.id }) { a ->
                    MediaTile(a, previewOf(a), Modifier.aspectRatio(1f), radius = 16.dp, onClick = { onOpen(a) })
                }
                items(others, key = { it.id }, span = { GridItemSpan(maxLineSpan) }) { a ->
                    FileChip(a, Modifier.fillMaxWidth(), onClick = if (a.canPreview()) ({ onOpen(a) }) else null)
                }
            }
        }
    }
}

/** The search field that replaces the top bar: type, and step through the matches with the arrows. */
@Composable
fun FindInChatBar(
    query: String,
    onQuery: (String) -> Unit,
    count: Int,
    index: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .heightIn(min = 42.dp)
            .glass(CircleShape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).pressable(onClick = onClose), contentAlignment = Alignment.Center) {
            Icon(DevxIcons.ArrowLeft, stringResource(R.string.cd_back), tint = DevxColors.TextPrimary, modifier = Modifier.size(22.dp))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text(stringResource(R.string.find_hint), style = DevxType.Body, color = DevxColors.TextSecondary, maxLines = 1)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = DevxType.Body.copy(color = DevxColors.TextPrimary),
                cursorBrush = SolidColor(DevxColors.Accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (query.isNotEmpty()) {
            Text(
                if (count == 0) stringResource(R.string.find_none) else "${index + 1}/$count",
                style = DevxType.Caption,
                color = DevxColors.TextSecondary,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
        Box(Modifier.size(40.dp).pressable(enabled = count > 0, onClick = onNext), contentAlignment = Alignment.Center) {
            Icon(DevxIcons.ChevronDown, null, tint = if (count > 0) DevxColors.TextPrimary else DevxColors.TextDisabled, modifier = Modifier.size(22.dp))
        }
        Box(Modifier.size(40.dp).pressable(enabled = count > 0, onClick = onPrev), contentAlignment = Alignment.Center) {
            Icon(DevxIcons.ChevronUp, null, tint = if (count > 0) DevxColors.TextPrimary else DevxColors.TextDisabled, modifier = Modifier.size(22.dp))
        }
    }
}

/** Shown instead of a locked chat until the screen lock has been passed. */
@Composable
fun LockedCover(onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(DevxColors.Bg)
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(horizontal = 40.dp),
        ) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(DevxColors.Card), contentAlignment = Alignment.Center) {
                Icon(DevxIcons.Lock, null, tint = DevxColors.TextPrimary, modifier = Modifier.size(32.dp))
            }
            Text(stringResource(R.string.locked_title), style = DevxType.Title, color = DevxColors.TextPrimary)
            Text(stringResource(R.string.locked_body), style = DevxType.Body, color = DevxColors.TextSecondary, textAlign = TextAlign.Center)
            PillButton(stringResource(R.string.locked_button), onClick = onUnlock, modifier = Modifier.width(220.dp))
        }
    }
}

/** An icon of the chat for the home screen: a white square with the chat bubble on it. */
private fun shortcutIcon(context: Context): IconCompat {
    val size = 432
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    canvas.drawColor(AndroidColor.WHITE)
    val drawable = DrawableCompat.wrap(ContextCompat.getDrawable(context, R.drawable.ic_message_circle)!!.mutate())
    DrawableCompat.setTint(drawable, AndroidColor.BLACK)
    val pad = (size * 0.29f).toInt()
    drawable.setBounds(pad, pad, size - pad, size - pad)
    drawable.draw(canvas)
    return IconCompat.createWithAdaptiveBitmap(bitmap)
}

/** Puts an icon of the chat on the home screen; the launcher asks the person to confirm. */
fun addChatToHome(context: Context, chat: Chat) {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
        Toast.makeText(context, R.string.home_unsupported, Toast.LENGTH_LONG).show()
        return
    }
    val intent = Intent(context, MainActivity::class.java)
        .setAction(MainActivity.ACTION_OPEN_CHAT)
        .putExtra(MainActivity.EXTRA_CHAT_ID, chat.id)
    val info = ShortcutInfoCompat.Builder(context, "chat-" + chat.id)
        .setShortLabel(chat.title.take(24).ifBlank { "Chat" })
        .setLongLabel(chat.title.ifBlank { "Chat" })
        .setIcon(shortcutIcon(context))
        .setIntent(intent)
        .build()
    ShortcutManagerCompat.requestPinShortcut(context, info, null)
}

/** The whole chat as text, to send on. */
fun shareChat(context: Context, text: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, title)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private const val BUBBLE_PATH =
    "M3 20l1.3 -3.9c-2.324 -3.437 -1.426 -7.872 2.1 -10.374c3.526 -2.501 8.59 -2.296 11.845 .48c3.255 2.777 3.695 7.266 1.029 10.501c-2.666 3.235 -7.615 4.215 -11.574 2.293l-4.7 1"

/**
 * "This chat was deleted": a card rises in, the chat bubble is drawn line by line, then a cross is
 * struck through the middle of it and the words fade in.
 */
@Composable
fun ChatGoneOverlay(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val card = remember { Animatable(0f) }
    val bubble = remember { Animatable(0f) }
    val cross = remember { Animatable(0f) }
    val words = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        card.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 300f))
        bubble.animateTo(1f, tween(900, easing = LinearEasing))
        cross.animateTo(1f, tween(450))
        words.animateTo(1f, tween(400))
    }
    val bubblePath = remember { PathParser().parsePathString(BUBBLE_PATH).toPath() }
    val measure = remember { PathMeasure().also { it.setPath(bubblePath, false) } }
    val line = DevxColors.TextPrimary
    val danger = DevxColors.Danger
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f * card.value))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .graphicsLayer {
                    val s = 0.85f + 0.15f * card.value
                    scaleX = s
                    scaleY = s
                    alpha = card.value.coerceIn(0f, 1f)
                }
                .width(300.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(DevxColors.Card)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Canvas(Modifier.size(104.dp)) {
                val unit = size.minDimension / 24f
                scale(unit, pivot = Offset.Zero) {
                    val part = Path()
                    measure.getSegment(0f, measure.length * bubble.value, part, true)
                    drawPath(part, line, style = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    val c = cross.value
                    if (c > 0f) {
                        val a = Path().apply { moveTo(9.2f, 9.2f); lineTo(9.2f + 5.6f * c, 9.2f + 5.6f * c) }
                        val b = Path().apply { moveTo(14.8f, 9.2f); lineTo(14.8f - 5.6f * c, 9.2f + 5.6f * c) }
                        drawPath(a, danger, style = Stroke(width = 1.8f, cap = StrokeCap.Round))
                        drawPath(b, danger, style = Stroke(width = 1.8f, cap = StrokeCap.Round))
                    }
                }
            }
            Column(
                Modifier.graphicsLayer {
                    alpha = words.value
                    translationY = (1f - words.value) * 12.dp.toPx()
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.gone_title), style = DevxType.Title, color = DevxColors.TextPrimary, textAlign = TextAlign.Center)
                Text(stringResource(R.string.gone_body), style = DevxType.Body, color = DevxColors.TextSecondary, textAlign = TextAlign.Center)
            }
            PillButton(stringResource(R.string.gone_ok), onClick = onClose, modifier = Modifier.graphicsLayer { alpha = words.value })
        }
    }
}

private fun tokenLabel(tokens: Int): String = com.apvcode.devx.data.formatTokens(tokens)

/** The room a chat takes in the model's window: the numbers and a thin bar that fills (and turns red when it is nearly full). */
@Composable
private fun ContextMeter(used: Int, limit: com.apvcode.devx.data.ContextLimit, estimated: Boolean) {
    val fraction = (used.toFloat() / limit.tokens).coerceIn(0f, 1f)
    val shown by androidx.compose.animation.core.animateFloatAsState(fraction, androidx.compose.animation.core.tween(600, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "context-fill")
    val tint = when { fraction > 0.85f -> DevxColors.Danger; fraction > 0.65f -> DevxColors.Warning; else -> DevxColors.TextPrimary }
    Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(
                    R.string.menu_context_of,
                    (if (estimated) "≈ " else "") + tokenLabel(used),
                    (if (limit.approximate) "~" else "") + tokenLabel(limit.tokens),
                ),
                style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = Modifier.weight(1f),
            )
            Text(percentLabel(fraction), style = DevxType.Caption, color = tint)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(DevxColors.Outline)) {
            Box(Modifier.fillMaxWidth(shown.coerceAtLeast(0.01f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(tint))
        }
    }
}

private fun percentLabel(fraction: Float): String {
    val p = fraction * 100f
    return if (p in 0.01f..0.99f) "<1%" else "${p.toInt()}%"
}
