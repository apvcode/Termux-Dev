package com.apvcode.devx.ui.remote

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.scaleOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apvcode.devx.R
import com.apvcode.devx.data.Prefs
import com.apvcode.devx.remote.Ask
import com.apvcode.devx.remote.LinkState
import com.apvcode.devx.remote.PlanCard
import com.apvcode.devx.remote.Permission
import com.apvcode.devx.remote.RItem
import com.apvcode.devx.remote.RemoteHub
import com.apvcode.devx.remote.RemoteSession
import com.apvcode.devx.ui.chat.AttachMenu
import com.apvcode.devx.ui.chat.CameraPanel
import com.apvcode.devx.ui.chat.hasCameraPermission
import com.apvcode.devx.ui.chat.LocalSendOrigin
import com.apvcode.devx.ui.chat.SendButton
import com.apvcode.devx.ui.chat.SendOrigin
import com.apvcode.devx.ui.chat.enterPop
import com.apvcode.devx.ui.chat.flightBackground
import com.apvcode.devx.ui.chat.flying
import com.apvcode.devx.ui.chat.rememberFlight
import com.apvcode.devx.ui.components.GlassIconButton
import com.apvcode.devx.ui.components.LocalGlass
import com.apvcode.devx.ui.components.MenuRow
import com.apvcode.devx.ui.components.PopupMenu
import com.apvcode.devx.ui.components.PressState
import com.apvcode.devx.ui.components.menuOffset
import com.apvcode.devx.ui.components.pressAnchor
import com.apvcode.devx.ui.components.CopyIcon
import com.apvcode.devx.ui.components.rememberCopied
import com.apvcode.devx.ui.screens.SelectTextScreen
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.animation.core.animateDpAsState
import coil3.compose.AsyncImage
import com.apvcode.devx.remote.Preview
import com.apvcode.devx.ui.components.fileIcon
import androidx.compose.ui.layout.ContentScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import com.apvcode.devx.ui.chat.ThinkingSparkle
import com.apvcode.devx.ui.components.CircleIconButton
import com.apvcode.devx.ui.components.DevxIcons
import com.apvcode.devx.ui.components.PillButton
import com.apvcode.devx.ui.components.glass
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.markdown.MarkdownText
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RemoteChatScreen(hostId: String, sessionId: String, draftCwd: String? = null, onBack: () -> Unit, onNewChat: () -> Unit = onBack) {
    val conn = remember(hostId) { RemoteHub.connection(hostId) }
    if (conn == null) { LaunchedEffect(Unit) { onBack() }; return }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember(conn, sessionId, draftCwd) { if (draftCwd != null) RemoteSession(conn, "", RemoteHub.work, draftCwd) else RemoteHub.sessionOf(conn, sessionId) }
    DisposableEffect(session) { session.start(); onDispose { session.stop() } }
    LaunchedEffect(conn) { conn.start() }

    var text by remember { mutableStateOf("") }
    var controls by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    val files = remember { mutableStateListOf<PendingFile>() }
    var commands by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var sending by remember { mutableStateOf(false) }
    val pin = remember { PinState() }
    val sendOrigin = remember { SendOrigin() }
    val state = session.state
    val running = state?.running == true

    LaunchedEffect(session, conn.state, session.draft) { if (conn.state == LinkState.Online && !session.draft && commands.isEmpty()) commands = runCatching { session.commands() }.getOrDefault(emptyList()) }

    var attachOpen by remember { mutableStateOf(false) }
    var cameraOpen by remember { mutableStateOf(false) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    fun attach(uris: List<android.net.Uri>, named: Pair<String, String>? = null) {
        for (uri in uris) {
            val (name, mime) = named ?: Uploads.describe(context, uri)
            val f = PendingFile(name, mime).also { it.uri = uri }
            files += f
            scope.launch { Uploads.send(context, conn, uri, f) }
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { attach(it) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { attach(it) }
    val openCamera = { keyboard?.hide(); focus.clearFocus(); cameraOpen = true }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openCamera() else android.widget.Toast.makeText(context, R.string.camera_denied, android.widget.Toast.LENGTH_LONG).show()
    }

    fun notice(t: String, error: Boolean = false) = session.notice(t, error)

    fun runCommand(line: String) {
        val cmd = line.trim().substringBefore(' ').lowercase()
        val arg = line.trim().substringAfter(' ', "").trim()
        // "/new" leaves for the list of chats, where the folder of the next one is chosen.
        if (cmd == "/new") { onNewChat(); return }
        session.echoCommand(line.trim())
        scope.launch {
            try {
                when (cmd) {
                    "/compact" -> session.startCompact()
                    "/clear" -> session.reset()
                    "/plan" -> session.setMode(plan = true)
                    "/agent" -> session.setMode(plan = false)
                    "/model", "/usage", "/status", "/tasks" -> controls = true
                    "/effort" -> if (arg.isEmpty()) controls = true else session.setEffort(arg)
                    "/mcp" -> { val r = session.mcp(arg); notice(r.optString("text"), !r.optBoolean("ok")) }
                    "/undo" -> { val r = session.undo(); notice(r.optString("text"), !r.optBoolean("ok")) }
                    "/rename" -> if (arg.isNotEmpty()) session.rename(arg) else notice(context.getString(R.string.remote_rename_hint))
                    else -> {
                        val expanded = session.expand(line.trim())
                        if (expanded == null) notice(context.getString(R.string.remote_unknown_command, cmd), true) else session.send(expanded)
                    }
                }
            } catch (e: Exception) { notice(e.message ?: "", true) }
        }
    }

    fun send() {
        val t = text.trim()
        if (sending || (t.isEmpty() && files.isEmpty())) return
        if (files.any { it.uid == null && it.error == null }) return
        if (t.startsWith("/") && files.isEmpty()) {
            if (session.draft) { notice(context.getString(R.string.remote_draft_command)); return }
            sendOrigin.mark()
            text = ""; runCommand(t); return
        }
        val ids = files.filter { it.ready }.map { it.uid!! }
        session.rememberOutgoing(files.filter { it.ready }.map { Preview(it.uri, it.name, it.isImage) })
        // The new bubble flies out of the text field, and (when the agent is free) is pinned to the top.
        sendOrigin.mark()
        pin.next = !running
        sending = true
        text = ""
        files.clear()
        scope.launch {
            try { session.send(t.ifEmpty { context.getString(R.string.remote_see_files) }, ids) } catch (e: Exception) { session.forgetOutgoing(); notice(e.message ?: "", true); text = t }
            sending = false
        }
    }

    val palette = remember(text, commands) {
        if (text.startsWith("/") && !text.contains(' ') && files.isEmpty()) commands.filter { it.first.startsWith(text.lowercase()) } else emptyList()
    }

    val haze = rememberHazeState()
    val density = LocalDensity.current
    var bottomHeight by remember { mutableStateOf(0.dp) }
    var topHeight by remember { mutableStateOf(0.dp) }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var composerHeight by remember { mutableStateOf(0.dp) }
    var selecting by remember { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler(enabled = selecting != null) { selecting = null }

    // A new chat arrives in one movement: the folder badge draws itself, the bars and the field glide in around it.
    val intro = draftCwd != null
    val timeline = remember { Animatable(if (intro) 0f else 1f) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(Unit) {
        if (intro) {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            timeline.animateTo(1f, tween(1700, easing = androidx.compose.animation.core.LinearEasing))
        }
    }
    val stage: (Float, Float) -> Float = { from, to -> androidx.compose.animation.core.FastOutSlowInEasing.transform(((timeline.value - from) / (to - from)).coerceIn(0f, 1f)) }

    CompositionLocalProvider(LocalGlass provides haze, LocalSendOrigin provides sendOrigin) {
        Box(Modifier.fillMaxSize().background(DevxColors.Bg).imePadding()) {
            Box(Modifier.fillMaxSize()) {
                ChatList(session, pin, topHeight + statusTop, bottomHeight, haze, onSelect = { selecting = it })
                if (session.loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ThinkingSparkle() }
            }
            // The bar hangs in the air over the conversation: one rounded glass panel.
            // The conversation goes under the status bar too: the clock and the signal show over it, with a soft shade behind them.
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(statusTop + 24.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(DevxColors.Bg.copy(alpha = 0.7f), Color.Transparent))))
            Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().graphicsLayer { val v = stage(0.12f, 0.5f); alpha = v; translationY = -(1f - v) * 28.dp.toPx() }.onSizeChanged { topHeight = with(density) { it.height.toDp() } }) {
                ChatTopBar(session, conn.state, onBack = onBack, onControls = { controls = true })
                if (conn.state != LinkState.Online) OfflineBanner(conn, Modifier.align(Alignment.CenterHorizontally))
            }
            // Everything at the bottom floats over the conversation, which shows through the glass.
            // The empty new chat says where it works; it goes away with the first message.
            AnimatedVisibility(
                visible = session.draftCwd != null && session.items.isEmpty() && !session.loading,
                modifier = Modifier.align(Alignment.Center).padding(bottom = 96.dp),
                enter = fadeIn(tween(1)),
                exit = fadeOut(tween(160)),
            ) { NewChatHero(session.draftCwd.orEmpty(), conn.computer.name) { stage(it.first, it.second) } }
            Column(Modifier.align(Alignment.BottomCenter).graphicsLayer { val v = stage(0f, 0.55f); alpha = v; translationY = (1f - v) * 72.dp.toPx() }.onSizeChanged { bottomHeight = with(density) { it.height.toDp() } }) {
                Rising(session.perm) { p -> PermissionCard(p) { answer -> scope.launch { runCatching { session.answerPermission(p.id, answer) } } } }
                Rising(session.ask) { a -> AskCardView(a) { answers -> scope.launch { runCatching { session.answerQuestions(a.id, answers) } } } }
                Rising(session.plan) { p -> PlanCardView(p) { choice -> scope.launch { runCatching { session.decidePlan(p.id, choice) } } } }
                if (state?.trusted == false) TrustBar { scope.launch { runCatching { session.trust() } } }
                CommandPalette(palette) { cmd ->
                    if (cmd in setOf("/compact", "/new", "/clear", "/plan", "/agent", "/model", "/usage", "/undo", "/tasks", "/mcp", "/mcp import", "/mcp reload")) { text = ""; runCommand(cmd) } else text = "$cmd "
                }
                RemoteComposer(
                    modifier = Modifier.onSizeChanged { composerHeight = with(density) { it.height.toDp() } },
                    text = text, onText = { text = it }, files = files, running = running,
                    attachOpen = attachOpen, onToggleAttach = { attachOpen = !attachOpen }, onRemoveFile = { files.remove(it) }, onSend = ::send,
                    onStop = { scope.launch { runCatching { session.stopTurn() } } },
                    canSend = conn.state == LinkState.Online,
                )
            }
            // The same menu as in the usual chat: it opens right over the "+", the camera rises from the bottom.
            AttachMenu(
                open = attachOpen,
                onDismiss = { attachOpen = false },
                onCamera = { attachOpen = false; if (hasCameraPermission(context)) openCamera() else cameraPermission.launch(android.Manifest.permission.CAMERA) },
                onPhotos = { attachOpen = false; photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                onFiles = { attachOpen = false; filePicker.launch(arrayOf("*/*")) },
                bottomPadding = composerHeight + 4.dp,
                modifier = Modifier.fillMaxSize(),
            )
            CameraPanel(
                open = cameraOpen,
                onClose = { cameraOpen = false },
                onCaptured = { uri -> cameraOpen = false; attach(listOf(uri), "photo_${System.currentTimeMillis() / 1000}.jpg" to "image/jpeg") },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.62f),
            )
            if (controls) RemoteControls(session, conn, onDismiss = { controls = false }, onCommand = { runCommand(it) })
            selecting?.let { t -> Box(Modifier.fillMaxSize().background(DevxColors.Bg)) { SelectTextScreen(t, onClose = { selecting = null }) } }
        }
    }

}

// ───────────────────────── the empty new chat ─────────────────────────

/** The folder badge draws its ring, then the name, the computer and the hint rise one after the other. [stage] gives 0..1 for a span of the timeline. */
@Composable
private fun NewChatHero(cwd: String, computer: String, stage: (Pair<Float, Float>) -> Float) {
    val where = cwd.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { cwd }
    val ring = DevxColors.TextPrimary
    val glow = DevxColors.TextPrimary
    fun rise(from: Float, to: Float): Modifier = Modifier.graphicsLayer { val v = stage(from to to); alpha = v; translationY = (1f - v) * 16.dp.toPx() }
    Column(Modifier.padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                val s = stage(0.10f to 0.60f)
                val halo = stage(0.20f to 0.80f)
                // a soft light that swells and settles behind the badge
                drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.13f * kotlin.math.sin(Math.PI * halo).toFloat()), Color.Transparent), center, size.minDimension / 2), size.minDimension / 2, center)
                val r = size.minDimension * 0.34f
                val sweep = stage(0.18f to 0.72f)
                drawArc(ring.copy(alpha = 0.85f - 0.55f * sweep), -90f, 360f * sweep, false, Offset(center.x - r, center.y - r), Size(r * 2, r * 2), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                drawCircle(ring.copy(alpha = 0.16f * s), r, center, style = Stroke(1.dp.toPx()))
            }
            Box(
                Modifier.size(64.dp).graphicsLayer { val v = stage(0.04f to 0.45f); val k = 0.6f + 0.4f * v; scaleX = k; scaleY = k; alpha = v }
                    .clip(CircleShape).background(DevxColors.Card).border(1.dp, DevxColors.Outline, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(DevxIcons.Folder, null, tint = DevxColors.TextPrimary, modifier = Modifier.size(28.dp).graphicsLayer { alpha = stage(0.2f to 0.5f) }) }
        }
        Text(where, style = DevxType.Title, color = DevxColors.TextPrimary, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = rise(0.42f, 0.74f))
        Text(computer, style = DevxType.Caption, color = DevxColors.TextSecondary, textAlign = TextAlign.Center, modifier = rise(0.54f, 0.84f))
        Text(stringResource(R.string.remote_draft_hint), style = DevxType.Body, color = DevxColors.TextSecondary, textAlign = TextAlign.Center, modifier = rise(0.66f, 0.96f))
    }
}

// ───────────────────────── top bar ─────────────────────────

@Composable
private fun ChatTopBar(session: RemoteSession, link: LinkState, onBack: () -> Unit, onControls: () -> Unit) {
    val state = session.state
    val draftWhere = session.draftCwd?.trimEnd('/', '\\')?.substringAfterLast('/')?.substringAfterLast('\\')
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).glass(RoundedCornerShape(30.dp)).padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(Modifier.size(44.dp).pressable(onClick = onBack), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = DevxColors.TextPrimary, modifier = Modifier.size(24.dp)) }
        Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state?.title?.takeIf { it.isNotBlank() && it != "New Session" } ?: stringResource(R.string.remote_new_chat), style = DevxType.BodyStrong, color = DevxColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (state == null && session.draft && !draftWhere.isNullOrEmpty()) Text(draftWhere, style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (state != null) {
                val where = state.cwd.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
                Text(listOfNotNull(where.takeIf { it.isNotEmpty() }, state.model.takeIf { it.isNotEmpty() }, if (state.plan) stringResource(R.string.remote_mode_plan) else null).joinToString(" · "), style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (state != null && state.ctxLimit > 0) ContextRing(state.ctxUsed, state.ctxLimit, onClick = onControls)
        if (!session.draft) Box(Modifier.size(44.dp).pressable(onClick = onControls), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.remote_controls), tint = DevxColors.TextPrimary, modifier = Modifier.size(24.dp)) }
    }
}

@Composable
internal fun ContextRing(used: Int, limit: Int, onClick: () -> Unit) {
    val frac = (used.toFloat() / limit).coerceIn(0f, 1f)
    val color = if (frac > 0.85f) DevxColors.Danger else if (frac > 0.65f) DevxColors.Warning else DevxColors.TextPrimary
    val track = DevxColors.Outline
    Box(Modifier.size(44.dp).pressable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(28.dp)) {
            val w = 3.dp.toPx()
            drawArc(track, 0f, 360f, false, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), style = Stroke(w))
            drawArc(color, -90f, 360f * frac, false, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun OfflineBanner(conn: com.apvcode.devx.remote.RemoteConnection, modifier: Modifier) {
    val connecting = conn.state == LinkState.Connecting
    Row(modifier.padding(top = 2.dp).clip(CircleShape).background(DevxColors.Card).border(1.dp, DevxColors.Outline, CircleShape).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusDot(if (connecting) LinkVisual.Connecting else LinkVisual.Offline)
        Text(if (connecting) stringResource(R.string.remote_reconnecting) else stringResource(R.string.remote_offline), style = DevxType.Caption, color = DevxColors.TextSecondary)
    }
}

@Composable
private fun TrustBar(onTrust: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(18.dp)).background(DevxColors.Card).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.remote_untrusted), style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.remote_trust), style = DevxType.Section, color = DevxColors.TextPrimary, modifier = Modifier.pressable(onClick = onTrust).padding(8.dp))
    }
}

// ───────────────────────── the conversation ─────────────────────────

/** "The next message that appears is the one I just sent": it is pinned to the top of the screen. */
internal class PinState { var next = false }

@Composable
private fun ChatList(session: RemoteSession, pin: PinState, topPad: androidx.compose.ui.unit.Dp, bottomPad: androidx.compose.ui.unit.Dp, haze: HazeState, onSelect: (String) -> Unit) {
    val listState = rememberLazyListState()
    val items = session.items
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val base = if (session.moreBefore > 0) 1 else 0
    val running = session.state?.running == true

    // While an answer is written, stay at its end, unless the reader scrolled away.
    var following by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> following = false
                is DragInteraction.Stop, is DragInteraction.Cancel -> following = !listState.canScrollForward
                else -> Unit
            }
        }
    }
    var pinKey by remember { mutableStateOf<Long?>(null) }
    var spacerPx by remember { mutableIntStateOf(0) }

    // The conversation has been read: it opens at its end.
    LaunchedEffect(session.loading) {
        if (!session.loading) {
            pinKey = null
            spacerPx = 0
            if (items.isNotEmpty()) { listState.scrollToItem(base + items.lastIndex); listState.scrollBy(100_000f) }
            following = true
        }
    }
    // The message I just sent goes to the top of the screen and the answer grows below it, like in the usual chat.
    LaunchedEffect(items.size) {
        val last = items.lastOrNull()
        if (pin.next && last is RItem.User) {
            pin.next = false
            pinKey = last.key
            following = false
            val index = base + items.lastIndex
            for (attempt in 0 until 6) {
                withFrameNanos { }
                listState.animateScrollToItem(index, 0)
                if (listState.firstVisibleItemIndex == index && kotlin.math.abs(listState.firstVisibleItemScrollOffset) <= 3) break
            }
        }
    }
    // The free space under the pinned message: exactly as much as makes it reachable at the top. It shrinks as the answer grows.
    val gapPx = with(density) { 14.dp.roundToPx() }
    LaunchedEffect(pinKey) {
        val key = pinKey ?: return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val from = items.indexOfFirst { it.key == key }
            if (from < 0) return@snapshotFlow 0
            val lazyFrom = base + from
            val count = info.totalItemsCount - 1 - lazyFrom
            val segment = info.visibleItemsInfo.filter { it.index >= lazyFrom && it.key != "pin-space" }
            if (segment.size != count) return@snapshotFlow null
            val room = info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding
            (room - segment.sumOf { it.size } - segment.size * gapPx).coerceAtLeast(0)
        }.collect { if (it != null) spacerPx = it }
    }
    val tail = (items.lastOrNull() as? RItem.Assistant)?.text?.length ?: (items.lastOrNull() as? RItem.Thought)?.text?.length ?: 0
    LaunchedEffect(items.size, tail, session.queued.size, running, session.compacting) { if (following) listState.scrollBy(100_000f) }
    val canScrollDown by remember { derivedStateOf { listState.canScrollForward } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().hazeSource(haze), listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topPad + 8.dp, bottom = bottomPad + 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (session.moreBefore > 0) item(key = "more") { Text(stringResource(R.string.remote_earlier, session.moreBefore), style = DevxType.Caption, color = DevxColors.TextDisabled, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            items(items, key = { it.key }) { item ->
                val animate = remember(item.key) { session.consumeFresh(item.key) }
                when (item) {
                    is RItem.User -> UserItem(item, animate, onSelect)
                    is RItem.Assistant -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectionContainer { MarkdownText(item.text, animate = animate) }
                        val next = items.getOrNull(items.indexOf(item) + 1)
                        if ((next != null || !running) && (next == null || next is RItem.Summary || next is RItem.User)) AssistantActions(item.text, onSelect)
                    }
                    is RItem.Thought -> ThoughtItem(item)
                    is RItem.Tool -> ToolItem(item)
                    is RItem.Compact -> CompactItem(item)
                    is RItem.Notice -> if (item.text.contains('\n')) {
                        // An answer of several lines (the MCP servers) sits in a card, set from the left.
                        SelectionContainer { Text(item.text, style = DevxType.Caption.copy(fontSize = 14.sp, lineHeight = 21.sp), color = if (item.error) DevxColors.Danger else DevxColors.TextPrimary, modifier = Modifier.fillMaxWidth().enterPop(animate).clip(RoundedCornerShape(20.dp)).background(DevxColors.Card).padding(horizontal = 16.dp, vertical = 12.dp)) }
                    } else Text(item.text, style = DevxType.Caption, color = if (item.error) DevxColors.Danger else DevxColors.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    is RItem.Summary -> SummaryItem(item)
                }
            }
            items(session.queued.size, key = { "q$it" }) { i ->
                Column(Modifier.fillMaxWidth().enterPop(true), horizontalAlignment = Alignment.End) {
                    Text(session.queued[i], style = DevxType.Body, color = DevxColors.TextSecondary, modifier = Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(24.dp)).border(1.dp, DevxColors.Outline, RoundedCornerShape(24.dp)).padding(horizontal = 18.dp, vertical = 12.dp))
                    Text(stringResource(R.string.remote_queued), style = DevxType.Caption, color = DevxColors.TextDisabled, modifier = Modifier.padding(top = 4.dp, end = 8.dp))
                }
            }
            if (session.compacting) {
                item(key = "compacting") {
                    Row(Modifier.padding(start = 2.dp).enterPop(true), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ThinkingSparkle()
                        Text(stringResource(R.string.remote_compacting_context), style = DevxType.Body, color = DevxColors.TextSecondary)
                    }
                }
            } else if (running && items.lastOrNull().let { it !is RItem.Assistant && it !is RItem.Thought }) {
                item(key = "working") { Row(Modifier.padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { ThinkingSparkle(); Text(session.reconnectNote?.let { stringResource(R.string.remote_provider_retry, it) } ?: stringResource(R.string.remote_working), style = DevxType.Caption, color = DevxColors.TextSecondary) } }
            }
            if (pinKey != null) item(key = "pin-space") { Spacer(Modifier.height(with(density) { spacerPx.toDp() })) }
        }
        if (canScrollDown && items.isNotEmpty()) {
            GlassIconButton(
                icon = Icons.Outlined.ArrowDownward, contentDescription = stringResource(R.string.cd_scroll_down),
                onClick = { following = true; scope.launch { listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1); listState.scrollBy(100_000f) } },
                size = 38.dp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPad + 8.dp),
            )
        }
    }
}

private fun shareText(context: android.content.Context, text: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, text) }
    context.startActivity(android.content.Intent.createChooser(send, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Copy and the rest of the actions under a finished answer. */
@Composable
private fun AssistantActions(text: String, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    val press = remember { PressState() }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        var copied by rememberCopied()
        Box(Modifier.size(40.dp).pressable { clipboard.setText(AnnotatedString(text)); copied = true }.clip(CircleShape), contentAlignment = Alignment.Center) {
            CopyIcon(copied, DevxColors.TextSecondary, stringResource(R.string.cd_copy), Modifier.size(20.dp))
        }
        Box(Modifier.pressAnchor(press)) {
            Box(Modifier.size(40.dp).pressable { menu = true }.clip(CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.MoreVert, stringResource(R.string.cd_more), tint = DevxColors.TextSecondary, modifier = Modifier.size(20.dp))
            }
            PopupMenu(expanded = menu, onDismiss = { menu = false }, offset = press.menuOffset()) {
                MenuRow(DevxIcons.FileText, stringResource(R.string.msg_select)) { menu = false; onSelect(text) }
                MenuRow(DevxIcons.Share, stringResource(R.string.msg_share)) { menu = false; shareText(context, text) }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun UserItem(m: RItem.User, animate: Boolean, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val press = remember { PressState() }
    // A message just sent from the composer flies out of the text field; any other new one just pops in.
    val origin = LocalSendOrigin.current
    val from = remember(m.key) { if (animate) origin.take() else null }
    val flight = from?.let { rememberFlight(it) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (m.text.isNotBlank()) Box(Modifier.pressAnchor(press)) {
            Text(
                m.text, style = DevxType.Body, color = DevxColors.TextPrimary,
                modifier = Modifier.widthIn(max = 320.dp)
                    .then(if (flight != null) Modifier.flying(flight) else Modifier.enterPop(animate))
                    .pressable(scaleTo = 0.97f, haptic = false, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menu = true }, onClick = {})
                    .clip(RoundedCornerShape(24.dp))
                    .then(if (flight != null) Modifier.flightBackground(flight, DevxColors.Card) else Modifier.background(DevxColors.Card))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            )
            PopupMenu(expanded = menu, onDismiss = { menu = false }, offset = press.menuOffset()) {
                MenuRow(DevxIcons.Copy, stringResource(R.string.msg_copy)) { clipboard.setText(AnnotatedString(m.text)); menu = false }
                MenuRow(DevxIcons.FileText, stringResource(R.string.msg_select)) { menu = false; onSelect(m.text) }
                MenuRow(DevxIcons.Share, stringResource(R.string.msg_share)) { menu = false; shareText(context, m.text) }
            }
        }
        if (m.previews.isNotEmpty()) {
            // What this phone sent looks the way it does in the usual chat: pictures as tiles, files as cards.
            FlowRow(Modifier.enterPop(animate), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                m.previews.forEach { p ->
                    if (p.image && p.uri != null) AsyncImage(model = p.uri, contentDescription = p.name, contentScale = ContentScale.Crop, modifier = Modifier.size(width = 150.dp, height = 190.dp).clip(RoundedCornerShape(22.dp)).background(DevxColors.CardInner))
                    else Row(Modifier.clip(RoundedCornerShape(18.dp)).background(DevxColors.CardInner).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(fileIcon(p.name), null, tint = DevxColors.TextSecondary, modifier = Modifier.size(26.dp))
                        Text(p.name, style = DevxType.Caption, color = DevxColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
                    }
                }
            }
        } else if (m.files.isNotEmpty() || m.images > 0) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (m.images > 0) Chip(stringResource(R.string.remote_photos, m.images), DevxIcons.Camera)
                m.files.forEach { Chip(it, DevxIcons.FileText) }
            }
        }
    }
}

@Composable
private fun Chip(label: String, icon: ImageVector) {
    Row(Modifier.clip(CircleShape).border(1.dp, DevxColors.Outline, CircleShape).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(16.dp))
        Text(label, style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
    }
}

@Composable
private fun ThoughtItem(t: RItem.Thought) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.pressable(scaleTo = 0.98f, haptic = false) { open = !open }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (t.live) ThinkingSparkle() else Icon(DevxIcons.Brain, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(18.dp))
            Text(if (t.live) stringResource(R.string.remote_thinking) else t.sec?.let { stringResource(R.string.remote_thought_for, it) } ?: stringResource(R.string.remote_thought), style = DevxType.Caption, color = DevxColors.TextSecondary)
            Icon(Icons.Outlined.ExpandMore, null, tint = DevxColors.TextDisabled, modifier = Modifier.size(18.dp).rotate(if (open) 180f else 0f))
        }
        if (open) SelectionContainer { Text(t.text.trim(), style = DevxType.Caption.copy(lineHeight = 20.sp), color = DevxColors.TextSecondary, modifier = Modifier.padding(start = 26.dp)) }
    }
}

private fun toolIcon(name: String): String = name.lowercase()

@Composable
private fun ToolItem(t: RItem.Tool) {
    var open by remember { mutableStateOf(false) }
    val icon = when {
        t.name in setOf("bash", "background", "run_command", "exec") -> DevxIcons.Terminal
        t.name.contains("search") || t.name.contains("web") || t.name == "fetch_url" -> DevxIcons.ChatSearch
        t.name.contains("plan") || t.name.contains("todo") -> DevxIcons.ListCheck
        else -> DevxIcons.FileText
    }
    val spin by rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "spin")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(DevxColors.Card).animateContentSize()) {
        Row(Modifier.fillMaxWidth().pressable(scaleTo = 0.99f, haptic = false) { open = !open }.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(18.dp))
            Text(t.desc.removePrefix("→ ").ifBlank { t.name }, style = DevxType.Caption.copy(fontSize = 14.sp), color = DevxColors.TextPrimary, maxLines = if (open) 6 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (t.result == null) Icon(DevxIcons.Loader, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(16.dp).rotate(spin))
            else Icon(Icons.Outlined.ExpandMore, null, tint = DevxColors.TextDisabled, modifier = Modifier.size(18.dp).rotate(if (open) 180f else 0f))
        }
        if (open && t.result != null) {
            Text(t.result!!.trim().take(2500), style = DevxType.Mono.copy(fontSize = 12.sp, lineHeight = 17.sp), color = DevxColors.TextSecondary, modifier = Modifier.fillMaxWidth().background(DevxColors.CardInner).padding(horizontal = 14.dp, vertical = 10.dp).heightIn(max = 260.dp).verticalScroll(rememberScrollState()))
        }
    }
}

@Composable
private fun CompactItem(c: RItem.Compact) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).border(1.dp, DevxColors.Outline, RoundedCornerShape(18.dp)).animateContentSize().pressable(scaleTo = 0.99f, haptic = false) { open = !open }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.remote_compacted), style = DevxType.Section, color = DevxColors.TextSecondary)
        if (open) Text(c.text.trim(), style = DevxType.Caption.copy(lineHeight = 20.sp), color = DevxColors.TextSecondary) else Text(c.text.trim(), style = DevxType.Caption, color = DevxColors.TextDisabled, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SummaryItem(s: RItem.Summary) {
    val sec = s.ms / 1000
    val parts = listOfNotNull(if (sec >= 60) "${sec / 60} min ${sec % 60} s" else "$sec s", if (s.tools > 0) stringResource(R.string.remote_tools_count, s.tools) else null, if (s.cost > 0) "$" + (if (s.cost >= 1) "%.2f".format(s.cost) else "%.4f".format(s.cost)) else null)
    Text(parts.joinToString(" · "), style = DevxType.Caption, color = DevxColors.TextDisabled, modifier = Modifier.fillMaxWidth())
}

// ───────────────────────── what the agent asks ─────────────────────────

@Composable
private fun CardFrame(modifier: Modifier = Modifier, danger: Boolean = false, borderColor: Color? = null, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(shape).background(DevxColors.Card).border(1.dp, borderColor ?: if (danger) DevxColors.Danger.copy(alpha = 0.7f) else DevxColors.Outline, shape).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/** A card that rises out of the composer with a spring, and sinks back when it is answered. It stays drawn while it leaves. */
@Composable
private fun <T : Any> Rising(value: T?, content: @Composable (T) -> Unit) {
    var last by remember { mutableStateOf<T?>(null) }
    if (value != null) last = value
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(value != null) { if (value != null) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    AnimatedVisibility(
        visible = value != null,
        enter = slideInVertically(spring(dampingRatio = 0.72f, stiffness = 320f)) { it / 2 } +
            scaleIn(spring(dampingRatio = 0.78f, stiffness = 360f), initialScale = 0.9f, transformOrigin = TransformOrigin(0.5f, 1f)) +
            expandVertically(spring(dampingRatio = 0.86f, stiffness = 360f), expandFrom = Alignment.Bottom) + fadeIn(tween(240)),
        exit = slideOutVertically(tween(260)) { it / 3 } + scaleOut(tween(240), targetScale = 0.94f, transformOrigin = TransformOrigin(0.5f, 1f)) +
            shrinkVertically(tween(280), shrinkTowards = Alignment.Bottom) + fadeOut(tween(200)),
    ) { last?.let { content(it) } }
}

/** One part of a card that comes in a little after the one above it. */
@Composable
private fun Rise(delayMs: Long, content: @Composable () -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(delayMs); a.animateTo(1f, tween(380, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    Box(Modifier.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 14.dp.toPx() }) { content() }
}

@Composable
private fun PermissionCard(p: Permission, onAnswer: (String) -> Unit) {
    // The edge breathes slowly: the card asks for attention without shouting.
    val breathe by rememberInfiniteTransition(label = "perm").animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = androidx.compose.animation.core.FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val edge = (if (p.danger) DevxColors.Danger else DevxColors.TextPrimary).copy(alpha = 0.16f + 0.26f * breathe)
    // The badge lands with a little overshoot.
    val badge = remember { Animatable(0.4f) }
    LaunchedEffect(Unit) { badge.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 320f)) }
    CardFrame(danger = p.danger, borderColor = edge) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(40.dp).graphicsLayer { scaleX = badge.value; scaleY = badge.value; alpha = badge.value.coerceIn(0f, 1f) }
                    .clip(CircleShape).background(if (p.danger) DevxColors.Danger.copy(alpha = 0.16f) else DevxColors.CardInner),
                contentAlignment = Alignment.Center,
            ) { Icon(DevxIcons.ShieldLock, null, tint = if (p.danger) DevxColors.Danger else DevxColors.TextPrimary, modifier = Modifier.size(22.dp)) }
            Text(stringResource(R.string.remote_perm_title, p.title), style = DevxType.BodyStrong, color = DevxColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Rise(90) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.lines.take(10).joinToString("\n"), style = DevxType.Mono.copy(fontSize = 12.sp, lineHeight = 17.sp), color = DevxColors.TextPrimary, maxLines = 10, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(DevxColors.CardInner).padding(12.dp))
                p.notes.forEach { Text(it, style = DevxType.Caption, color = if (p.danger) DevxColors.Danger else DevxColors.Warning) }
            }
        }
        Rise(190) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(stringResource(R.string.remote_deny), onClick = { onAnswer("deny") }, filled = false, modifier = Modifier.weight(1f))
                PillButton(stringResource(R.string.remote_allow), onClick = { onAnswer("allow") }, modifier = Modifier.weight(1f))
            }
        }
        if (p.canSession || p.canAlways) Rise(280) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (p.canSession) Text(stringResource(R.string.remote_allow_session), style = DevxType.Caption, color = DevxColors.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).clip(CircleShape).border(1.dp, DevxColors.Outline, CircleShape).pressable(scaleTo = 0.97f) { onAnswer("session") }.padding(vertical = 10.dp))
                if (p.canAlways) Text(stringResource(R.string.remote_allow_always), style = DevxType.Caption, color = DevxColors.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).clip(CircleShape).border(1.dp, DevxColors.Outline, CircleShape).pressable(scaleTo = 0.97f) { onAnswer("always") }.padding(vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun AskCardView(a: Ask, onSubmit: (List<String?>) -> Unit) {
    val picked = remember(a.id) { mutableStateListOf<String?>().apply { repeat(a.questions.size) { add(null) } } }
    val custom = remember(a.id) { mutableStateListOf<String>().apply { repeat(a.questions.size) { add("") } } }
    val maxHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.58f).dp
    CardFrame(Modifier.heightIn(max = maxHeight)) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            a.questions.forEachIndexed { i, q ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(q.question, style = DevxType.BodyStrong, color = DevxColors.TextPrimary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        q.options.forEach { o ->
                            val on = picked[i] == o && custom[i].isEmpty()
                            Text(o, style = DevxType.Caption.copy(fontSize = 14.sp), color = if (on) DevxColors.OnAccent else DevxColors.TextPrimary, modifier = Modifier.clip(CircleShape).background(if (on) DevxColors.Accent else Color.Transparent).border(1.dp, if (on) DevxColors.Accent else DevxColors.Outline, CircleShape).pressable(scaleTo = 0.96f) { picked[i] = if (on) null else o; custom[i] = "" }.padding(horizontal = 14.dp, vertical = 9.dp))
                        }
                    }
                    if (q.allowCustom) Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, DevxColors.Outline, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) {
                        BasicTextField(custom[i], { custom[i] = it; if (it.isNotEmpty()) picked[i] = null }, textStyle = DevxType.Caption.copy(fontSize = 14.sp, color = DevxColors.TextPrimary), cursorBrush = SolidColor(DevxColors.Accent), modifier = Modifier.fillMaxWidth())
                        if (custom[i].isEmpty()) Text(stringResource(R.string.remote_own_answer), style = DevxType.Caption.copy(fontSize = 14.sp), color = DevxColors.TextDisabled)
                    }
                }
            }
        }
        PillButton(stringResource(R.string.remote_send_answers), onClick = { onSubmit(a.questions.indices.map { i -> custom[i].trim().ifEmpty { picked[i] } }) })
    }
}

@Composable
private fun PlanCardView(p: PlanCard, onDecide: (String) -> Unit) {
    CardFrame(Modifier.heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.58f).dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(DevxIcons.ListCheck, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.remote_plan_ready), style = DevxType.BodyStrong, color = DevxColors.TextPrimary)
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionContainer { MarkdownText(p.summary, animate = false) }
            if (p.steps.isNotEmpty()) p.steps.forEachIndexed { i, s -> Text("${i + 1}. $s", style = DevxType.Caption.copy(fontSize = 14.sp), color = DevxColors.TextSecondary) }
            if (p.files.isNotEmpty()) Text(p.files.joinToString("\n"), style = DevxType.Mono.copy(fontSize = 12.sp), color = DevxColors.TextDisabled)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(stringResource(R.string.remote_plan_change), onClick = { onDecide("edit") }, filled = false, modifier = Modifier.weight(1f))
            PillButton(stringResource(R.string.remote_plan_go), onClick = { onDecide("go") }, modifier = Modifier.weight(1f))
        }
    }
}

// ───────────────────────── input ─────────────────────────

@Composable
private fun CommandPalette(items: List<Pair<String, String>>, onPick: (String) -> Unit) {
    AnimatedVisibility(items.isNotEmpty(), enter = fadeIn(tween(120)) + expandVertically(tween(180)), exit = fadeOut(tween(100)) + shrinkVertically(tween(140))) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).heightIn(max = 260.dp).clip(RoundedCornerShape(22.dp)).background(DevxColors.Card).border(1.dp, DevxColors.Outline, RoundedCornerShape(22.dp)).verticalScroll(rememberScrollState())) {
            items.forEach { (cmd, desc) ->
                Row(Modifier.fillMaxWidth().pressable(scaleTo = 0.99f, haptic = false) { onPick(cmd) }.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(cmd, style = DevxType.Mono.copy(fontSize = 14.sp), color = DevxColors.TextPrimary)
                    Text(desc, style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RemoteComposer(modifier: Modifier, text: String, onText: (String) -> Unit, files: List<PendingFile>, running: Boolean, attachOpen: Boolean, onToggleAttach: () -> Unit, onRemoveFile: (PendingFile) -> Unit, onSend: () -> Unit, onStop: () -> Unit, canSend: Boolean) {
    val busy = files.any { it.uid == null && it.error == null }
    val enabled = canSend && (text.isNotBlank() || files.any { it.ready }) && !busy
    val origin = LocalSendOrigin.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // On send the whole pill gives a little squeeze and springs back, like a pressed key.
    val pulse = remember { Animatable(1f) }
    val sendWithFeel = {
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        scope.launch { pulse.snapTo(0.965f); pulse.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 380f)) }
        onSend()
    }
    var focused by remember { mutableStateOf(false) }
    val edge by animateFloatAsState(if (focused) 1f else 0f, tween(220), label = "focus")
    // The tiles stay in place while the card closes after a send.
    var shown by remember { mutableStateOf<List<PendingFile>>(emptyList()) }
    if (files.isNotEmpty()) shown = files.toList()
    Column(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The plus turns into a cross while the menu is open.
            val turn by animateFloatAsState(if (attachOpen) 45f else 0f, spring(dampingRatio = 0.6f, stiffness = 420f), label = "plus")
            Box(Modifier.size(42.dp).pressable(onClick = onToggleAttach).glass(CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Add, stringResource(R.string.cd_attach), tint = DevxColors.TextPrimary, modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = turn })
            }
            // With something attached the pill becomes a card: the pictures on top, the text below them.
            val hasMedia = files.isNotEmpty()
            val radius by animateDpAsState(if (hasMedia) 28.dp else 21.dp, spring(dampingRatio = 0.85f, stiffness = 420f), label = "radius")
            Column(
                Modifier.weight(1f)
                    .graphicsLayer { scaleX = pulse.value * (1f + 0.006f * edge); scaleY = pulse.value * (1f + 0.006f * edge); transformOrigin = TransformOrigin(0.5f, 1f) }
                    .glass(RoundedCornerShape(radius))
                    .border(1.dp, DevxColors.TextPrimary.copy(alpha = 0.16f * edge), RoundedCornerShape(radius))
                    .animateContentSize(spring(dampingRatio = 0.85f, stiffness = 420f)),
            ) {
                AnimatedVisibility(visible = hasMedia, enter = fadeIn(tween(180)) + expandVertically(spring(dampingRatio = 0.85f, stiffness = 420f)), exit = fadeOut(tween(120)) + shrinkVertically(tween(180))) {
                    RemoteAttachments(shown, onRemoveFile)
                }
                Row(Modifier.padding(start = 16.dp, end = 3.dp), verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.weight(1f).heightIn(min = 42.dp).onGloballyPositioned { origin.field = it.boundsInWindow() }, contentAlignment = Alignment.CenterStart) {
                        BasicTextField(text, onText, textStyle = DevxType.Body.copy(color = DevxColors.TextPrimary), cursorBrush = SolidColor(DevxColors.Accent), maxLines = 6, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).onFocusChanged { focused = it.isFocused }, decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (text.isEmpty()) Text(if (running) stringResource(R.string.remote_placeholder_running) else stringResource(R.string.remote_placeholder), style = DevxType.Body, color = DevxColors.TextSecondary, maxLines = 1)
                                inner()
                            }
                        })
                    }
                    Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                        // While the agent works, an empty field offers Stop; a typed message waits its turn.
                        SendButton(canSend = enabled, generating = running && text.isBlank() && files.isEmpty(), onSend = sendWithFeel, onStop = onStop)
                    }
                }
            }
        }
    }
}

/** What is about to be sent, inside the composer's card above the text: pictures as tiles, files as cards, each with a cross; a ring while it travels. */
@Composable
private fun RemoteAttachments(files: List<PendingFile>, onRemove: (PendingFile) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        files.forEach { f ->
            Box {
                if (f.isImage && f.uri != null) {
                    AsyncImage(model = f.uri, contentDescription = f.name, contentScale = ContentScale.Crop, modifier = Modifier.size(width = 92.dp, height = 116.dp).clip(RoundedCornerShape(18.dp)).background(DevxColors.CardInner))
                } else {
                    Column(Modifier.size(width = 140.dp, height = 116.dp).clip(RoundedCornerShape(18.dp)).background(DevxColors.CardInner).padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Icon(fileIcon(f.name), null, tint = DevxColors.TextPrimary, modifier = Modifier.size(34.dp))
                        Text(f.name, style = DevxType.Caption, color = DevxColors.TextPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                val w = if (f.isImage && f.uri != null) 92.dp else 140.dp
                if (f.error != null) {
                    Box(Modifier.size(width = w, height = 116.dp).clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.55f)).border(1.dp, DevxColors.Danger, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                        Text(f.error ?: "", style = DevxType.Caption, color = DevxColors.Danger, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp))
                    }
                } else if (f.uid == null) {
                    Box(Modifier.size(width = w, height = 116.dp).clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(progress = { f.progress.coerceIn(0.04f, 1f) }, modifier = Modifier.size(28.dp), color = Color.White, trackColor = Color.White.copy(alpha = 0.25f), strokeWidth = 2.5.dp)
                    }
                }
                Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(26.dp).pressable { onRemove(f) }.clip(CircleShape).background(Color.Black.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.cd_remove), tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
