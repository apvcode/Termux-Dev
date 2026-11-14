package com.apvcode.devx.ui.chat

import androidx.compose.ui.unit.sp
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.snap
import androidx.compose.animation.SizeTransform
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import android.Manifest
import com.apvcode.devx.model.Role
import androidx.compose.animation.Crossfade
import java.io.File
import com.apvcode.devx.data.AttachmentKind
import com.apvcode.devx.data.Attachment
import coil3.compose.AsyncImage
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.lazy.LazyRow
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.content.ClipboardManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.layout.onSizeChanged
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import com.apvcode.devx.ui.components.GlassIconButton
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.components.LocalGlass
import com.apvcode.devx.ui.components.glass
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apvcode.devx.R
import com.apvcode.devx.data.Msg
import com.apvcode.devx.ui.components.CircleIconButton
import com.apvcode.devx.ui.components.DevxIcons
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onOpenDrawer: () -> Unit,
    onOpenProviders: () -> Unit,
    onSelectText: (String) -> Unit,
    onEdit: (Msg) -> Unit,
    onOpenPhoto: (Attachment) -> Unit,
    onShowThinking: (Msg) -> Unit,
    onShowUploaded: (String) -> Unit,
    onShowSources: (Msg) -> Unit,
    onOpenFile: (com.apvcode.devx.data.Artifact) -> Unit,
    onShowSteps: (Msg) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // The first time in the app the bars arrive after the iris has begun to open, one after the other.
    val intro = remember { Animatable(if (WelcomeCeremony.running) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (intro.value < 1f) {
            kotlinx.coroutines.delay(650)
            intro.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = 120f))
        }
    }
    // The search in the chat (opened from the chat menu).
    var findOpen by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var findIndex by remember { mutableIntStateOf(0) }
    var highlightId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    // Words that another app shared arrive in the field of the new chat.
    LaunchedEffect(vm.sharedDraft) { vm.sharedDraft?.let { draft = it; vm.sharedDraft = null } }
    var pickerOpen by remember { mutableStateOf(false) }
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val path = vm.path
    val matches = remember(path.toList(), findQuery) {
        if (findQuery.isBlank()) emptyList() else path.filter { it.text.contains(findQuery.trim(), ignoreCase = true) }.map { it.id }
    }

    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { vm.attachMedia(it) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.attachFiles(it) }

    // Speaking instead of typing: our own listening bar (see VoiceBar); what is heard goes into the field when the person is done.
    val voice = remember { com.apvcode.devx.data.VoiceInput(context) }
    androidx.compose.runtime.DisposableEffect(voice) { onDispose { voice.release() } }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { voice.cancel() }
    val startVoice = {
        if (!voice.available()) {
            android.widget.Toast.makeText(context, R.string.voice_missing, android.widget.Toast.LENGTH_LONG).show()
        } else {
            voice.start(com.apvcode.devx.data.VoiceInput.currentTag())
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoice() else android.widget.Toast.makeText(context, R.string.voice_err_permission, android.widget.Toast.LENGTH_LONG).show()
    }
    // Opened from the widget's microphone: the chat is up, so listening starts.
    LaunchedEffect(com.apvcode.devx.data.PendingAsk.voice) {
        if (!com.apvcode.devx.data.PendingAsk.voice) return@LaunchedEffect
        kotlinx.coroutines.delay(450)
        com.apvcode.devx.data.PendingAsk.voice = false
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) startVoice()
        else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
    }
    // A picture in the phone's clipboard (copied from the gallery, a browser, a screenshot tool) is offered for pasting.
    val clipboardManager = remember { context.getSystemService(android.content.ClipboardManager::class.java) }
    var clipImageAt by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var clipDismissedAt by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    fun checkClipboard() {
        val d = runCatching { clipboardManager?.primaryClipDescription }.getOrNull()
        clipImageAt = if (d != null && d.hasMimeType("image/*")) d.timestamp else 0L
    }
    androidx.compose.runtime.DisposableEffect(clipboardManager) {
        val listener = android.content.ClipboardManager.OnPrimaryClipChangedListener { checkClipboard() }
        checkClipboard()
        clipboardManager?.addPrimaryClipChangedListener(listener)
        onDispose { clipboardManager?.removePrimaryClipChangedListener(listener) }
    }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { checkClipboard(); onPauseOrDispose { } }
    val pasteImages = {
        val clip = runCatching { clipboardManager?.primaryClip }.getOrNull()
        val uris = buildList { if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { add(it) } }
        if (uris.isNotEmpty()) vm.attachMedia(uris) else android.widget.Toast.makeText(context, R.string.paste_image_failed, android.widget.Toast.LENGTH_SHORT).show()
        clipDismissedAt = clipImageAt
    }
    var attachOpen by remember { mutableStateOf(false) }
    var cameraOpen by remember { mutableStateOf(false) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val openCamera = {
        keyboard?.hide()
        focus.clearFocus()
        cameraOpen = true
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openCamera() else android.widget.Toast.makeText(context, R.string.camera_denied, android.widget.Toast.LENGTH_LONG).show()
    }

    // ----- the chat menu: find in chat, lock, shortcut, delete -----
    var deleteAsk by remember { mutableStateOf(false) }
    // The first message asks (once) to be allowed to show notifications: they keep you informed when an
    // answer is written in the background.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val keyguard = remember { context.getSystemService(android.app.KeyguardManager::class.java) }
    val authLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) vm.current?.let { vm.unlock(it.id) }
    }
    val unlockNow = {
        val intent = keyguard?.createConfirmDeviceCredentialIntent(
            context.getString(R.string.lock_prompt_title),
            context.getString(R.string.lock_prompt_body),
        )
        if (intent != null) authLauncher.launch(intent) else vm.current?.let { vm.unlock(it.id) }
    }
    // Another chat opened: a search of the old one is over.
    LaunchedEffect(vm.currentId) {
        findOpen = false
        findQuery = ""
        findIndex = 0
    }
    // The chat written to a file the person picks the place for (Downloads, a cloud folder...).
    val exportMarkdown = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val chat = vm.current
        if (uri != null && chat != null) scope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val text = com.apvcode.devx.data.ChatExport.markdown(
                        chat, com.apvcode.devx.data.Prefs.nickname.ifBlank { context.getString(R.string.export_me) }, "Devx",
                        com.apvcode.devx.data.ChatExport.dateLabel(chat.updatedAt),
                    )
                    context.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }.isSuccess
            }
            android.widget.Toast.makeText(context, if (ok) R.string.export_saved else R.string.export_failed, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val exportPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val chat = vm.current
        if (uri != null && chat != null) scope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)!!.use {
                        com.apvcode.devx.data.ChatExport.pdf(chat, com.apvcode.devx.data.Prefs.nickname.ifBlank { context.getString(R.string.export_me) }, "Devx", it)
                    }
                }.isSuccess
            }
            android.widget.Toast.makeText(context, if (ok) R.string.export_saved else R.string.export_failed, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val menuActions = ChatMenuActions(
        onShare = { vm.current?.let { shareChat(context, vm.transcript(it.id), it.title) } },
        onToggleLock = {
            val chat = vm.current
            if (chat != null) {
                if (chat.locked) vm.setLocked(chat.id, false)
                else if (com.apvcode.devx.data.AppLock.locksChats) vm.setLocked(chat.id, true)
                else android.widget.Toast.makeText(context, if (com.apvcode.devx.data.AppLock.enabled) R.string.lock_need_chats else R.string.lock_need_screen_lock, android.widget.Toast.LENGTH_LONG).show()
            }
        },
        onUploaded = { vm.current?.let { onShowUploaded(it.id) } },
        onFind = { findOpen = true },
        onExportMarkdown = { vm.current?.let { exportMarkdown.launch(com.apvcode.devx.data.ChatExport.fileName(it.title, "md")) } },
        onExportPdf = { vm.current?.let { exportPdf.launch(com.apvcode.devx.data.ChatExport.fileName(it.title, "pdf")) } },
        onShorten = {
            val chat = vm.current
            // A good result is shown in the chat itself (the card with the notes); only a failure needs words.
            if (chat != null) vm.startCompaction(chat.id) {
                android.widget.Toast.makeText(context, R.string.shorten_failed, android.widget.Toast.LENGTH_LONG).show()
            }
        },
        onAddToHome = { vm.current?.let { addChatToHome(context, it) } },
        onDelete = { deleteAsk = true },
    )

    // With no models at all there is nothing to pick from: go straight to where they come from.
    val openPicker = { if (vm.modelCount == 0) onOpenProviders() else pickerOpen = true }

    val host = remember(vm, onOpenProviders, onSelectText, onEdit, onOpenPhoto, onShowThinking, onShowSources, onOpenFile, onShowSteps) {
        MessageActionsHost(
            onSend = { vm.send(it) },
            onSelectText = onSelectText,
            onEdit = onEdit,
            onOpenSettings = onOpenProviders,
            onPickModel = { if (vm.modelCount == 0) onOpenProviders() else pickerOpen = true },
            onOpenPhoto = onOpenPhoto,
            onShowThinking = onShowThinking,
            onShowSources = onShowSources,
            onOpenFile = onOpenFile,
            onShowSteps = onShowSteps,
            onFold = { m ->
                // A long message folds up while the reader is in the middle of it: the list glides to its beginning instead of jumping.
                val index = vm.path.indexOfFirst { it.id == m.id }
                val first = listState.firstVisibleItemIndex
                if (index >= 0 && (index < first || (index == first && listState.firstVisibleItemScrollOffset > 0))) {
                    scope.launch { listState.animateScrollToItem(index) }
                }
            },
        )
    }

    // While an answer is being written the screen follows its end, gently and all the time; a finger on the list lets go of it,
    // and it takes hold again when the reader comes back to the bottom (also after a fling that ends there).
    var following by remember { mutableStateOf(true) }
    var userScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) { following = false; userScrolling = true }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { moving ->
            if (!moving && userScrolling) {
                userScrolling = false
                following = distanceToBottom(listState) < 48f
            }
        }
    }
    // The message I just sent is pinned to the top of the screen and the answer grows below it,
    // like in ChatGPT: [pinNext] says "the next new message is one I sent", [pinId] is the pinned one.
    var pinNext by remember { mutableStateOf(false) }
    var pinId by remember { mutableStateOf<String?>(null) }
    var spacerPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // How far the pinned message sits above the top edge: with pictures, the words stay at the top and the
    // pictures peek out above them, instead of the pictures taking the whole top of the screen.
    var pinOffsetPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val pinDensity = androidx.compose.ui.platform.LocalDensity.current
    // Another chat opened: jump to its end at once (unless it is the very chat being started right now).
    LaunchedEffect(vm.currentId) {
        if (pinNext) return@LaunchedEffect
        pinId = null
        spacerPx = 0
        pinOffsetPx = 0
        if (path.isNotEmpty()) {
            listState.scrollToItem(path.lastIndex)
            listState.scrollBy(100_000f)
        }
        following = true
    }
    // A new message appeared: mine goes to the top, anything else (a regenerated answer) is glided to.
    LaunchedEffect(path.size) {
        if (path.isEmpty()) return@LaunchedEffect
        val mine = if (pinNext) path.lastOrNull { it.role == Role.User } else null
        if (mine != null) {
            pinNext = false
            pinId = mine.id
            following = false
            val index = path.indexOfFirst { it.id == mine.id }
            // The free space under it is measured a frame or two later; try again until it sits at the top.
            val peek = with(pinDensity) { 36.dp.roundToPx() }
            val gap = with(pinDensity) { 6.dp.roundToPx() }
            for (attempt in 0 until 6) {
                androidx.compose.runtime.withFrameNanos { }
                val media = if (mine.attachments.isNotEmpty()) MediaMetrics.heights[mine.id] ?: 0 else 0
                val target = if (media > 0 && mine.text.isNotBlank()) (media + gap - peek).coerceAtLeast(0) else 0
                pinOffsetPx = target
                listState.animateScrollToItem(index, target)
                val placed = listState.firstVisibleItemIndex == index && kotlin.math.abs(listState.firstVisibleItemScrollOffset - target) <= 3
                if (placed && (media > 0 || mine.attachments.isEmpty())) break
            }
        } else {
            listState.animateScrollToItem(path.lastIndex)
            following = true
        }
    }
    // The free space under the pinned message: exactly as much as makes it reachable at the top.
    // It shrinks as the answer grows, so the answer fills the screen without anything jumping.
    val gapPx = with(androidx.compose.ui.platform.LocalDensity.current) { 20.dp.roundToPx() }
    LaunchedEffect(pinId) {
        val id = pinId ?: return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow {
            val info = listState.layoutInfo
            val current = vm.path
            val from = current.indexOfFirst { it.id == id }
            if (from < 0) return@snapshotFlow null
            val segment = info.visibleItemsInfo.filter { it.index in from..current.lastIndex }
            if (segment.size != current.lastIndex - from + 1) return@snapshotFlow null
            val room = info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding
            (room + pinOffsetPx - segment.sumOf { it.size } - segment.size * gapPx).coerceAtLeast(0)
        }.collect { if (it != null) spacerPx = it }
    }
    // The follow itself: every frame the list moves a fraction of the way to the bottom, so that growing text, a step appearing
    // or the sign of work folding away are all taken in one smooth glide instead of jumps. It also finishes the last stretch after the answer.
    var settleUntil by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    LaunchedEffect(vm.generating) { if (!vm.generating) settleUntil = android.os.SystemClock.uptimeMillis() + 1400 }
    LaunchedEffect(following, vm.generating, settleUntil) {
        if (!following) return@LaunchedEffect
        while (vm.generating || android.os.SystemClock.uptimeMillis() < settleUntil) {
            androidx.compose.runtime.withFrameNanos { }
            val d = distanceToBottom(listState)
            if (d > 0.5f) listState.scrollBy(if (d == Float.MAX_VALUE) 900f else (d * 0.2f).coerceIn(1.5f, 1400f))
        }
    }
    // Stepping through the matches: bring the message into view and flash it once.
    LaunchedEffect(findQuery, findIndex, matches.size) {
        val id = matches.getOrNull(findIndex.coerceIn(0, (matches.size - 1).coerceAtLeast(0))) ?: return@LaunchedEffect
        val at = path.indexOfFirst { it.id == id }
        if (at >= 0) {
            following = false
            listState.animateScrollToItem(at)
            highlightId = id
            kotlinx.coroutines.delay(1100)
            highlightId = null
        }
    }
    // The card of a running compression is under the message where the cut is: bring it into view if it is not.
    LaunchedEffect(vm.compressAfter) {
        val id = vm.compressAfter ?: return@LaunchedEffect
        if (vm.compacting != vm.currentId) return@LaunchedEffect
        val at = path.indexOfFirst { it.id == id }
        if (at < 0) return@LaunchedEffect
        kotlinx.coroutines.delay(160)
        val info = listState.layoutInfo
        val seen = info.visibleItemsInfo.firstOrNull { it.index == at }
        val room = info.viewportEndOffset - info.afterContentPadding
        // The message is on the screen and there is room under it for the card: leave things as they are.
        if (seen == null || seen.offset + seen.size + 220 > room) {
            following = false
            listState.animateScrollToItem(at)
        }
    }
    // Coming from a search: bring the found message into view and flash it once.
    LaunchedEffect(vm.reveal, path.size) {
        val target = vm.reveal ?: return@LaunchedEffect
        if (target.first != vm.currentId) return@LaunchedEffect
        val at = path.indexOfFirst { it.id == target.second }
        if (at >= 0) {
            vm.reveal = null
            following = false
            listState.scrollToItem(at)
            highlightId = target.second
            kotlinx.coroutines.delay(1400)
            highlightId = null
        }
    }
    // The arrow shows when the end is well out of reach (a little hysteresis, so that it does not flicker while text grows).
    val showDown by remember { androidx.compose.runtime.derivedStateOf { distanceToBottom(listState) > 260f } }
    // The keyboard comes and goes: the list is as tall as the room above it, so when that changes the chat is moved by the same
    // amount. What was at the edge of the keyboard stays there, and when the keyboard goes away the chat is exactly where it was
    // (without this the messages stay shifted and the reader has to scroll back down by hand).
    // What is kept is how far the end of the chat is from the edge of the screen: it is the same before and after the keyboard moves.
    // (When the list gets taller the list itself pulls the end back to the edge, so only what is still missing is added.)
    LaunchedEffect(listState) {
        var heightBefore = -1
        var gapBefore = 0f
        snapshotFlow { listState.layoutInfo.viewportSize.height to distanceToBottom(listState) }.collect { (height, gap) ->
            if (heightBefore > 0 && height > 0 && height != heightBefore && !userScrolling) {
                val plain = (heightBefore - height).toFloat()
                val move = if (gapBefore == Float.MAX_VALUE || gap == Float.MAX_VALUE) plain else gap - gapBefore
                if (move != 0f) listState.scrollBy(move)
                heightBefore = height
                return@collect
            }
            heightBefore = height
            gapBefore = gap
        }
    }
    // A pull upwards at the very end of the chat brings the keyboard up (and the finger does not have to go to the field).
    val pullThreshold = with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.toPx() }
    val pullConnection = remember(listState) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            var pulled = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
                if (source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) return Offset.Zero
                if (available.y < 0f && !listState.canScrollForward) {
                    pulled -= available.y
                    if (pulled > pullThreshold) {
                        pulled = 0f
                        com.apvcode.devx.data.PendingAsk.keyboard = true
                    }
                } else if (available.y > 0f || consumed.y != 0f) pulled = 0f
                return Offset.Zero
            }
            override suspend fun onPostFling(consumed: androidx.compose.ui.unit.Velocity, available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                pulled = 0f
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }

    val haze = rememberHazeState()
    val sendOrigin = remember { SendOrigin() }
    var composerHeight by remember { mutableStateOf(0.dp) }
    val density = androidx.compose.ui.platform.LocalDensity.current

    // While the words of an answer are coming, the glass is plain (no blur behind it). The blur is drawn again with every frame
    // the text moves, several times over (the header, the buttons, the field), and on a 120 Hz screen the graphics chip of the
    // phone cannot keep up with that. When the answer is done the glass is as it was.
    // (Worked out in a derived state: reading the text here would make this whole screen be built again with every piece of it.)
    val lightGlass by remember { androidx.compose.runtime.derivedStateOf { vm.generating && vm.path.lastOrNull()?.let { it.role == Role.Assistant && it.text.isNotEmpty() } == true } }
    // While the words are coming the screen is held at 60 Hz, so that frames come at an even pace (see SteadyFrames).
    LaunchedEffect(lightGlass) { com.apvcode.devx.data.SteadyFrames.hold(context, lightGlass) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { com.apvcode.devx.data.SteadyFrames.hold(context, false) } }
    androidx.compose.runtime.CompositionLocalProvider(LocalGlass provides (if (lightGlass) null else haze), LocalSendOrigin provides sendOrigin) {
        Box(
            modifier
                .fillMaxSize()
                .background(DevxColors.Bg)
                .imePadding(),
        ) {
            // A locked chat shows nothing until the screen lock has been passed.
            val shut = vm.isShut(vm.current)
            // The chat under the cover is hidden from screen readers too, not only from the eyes.
            Box(Modifier.fillMaxSize().then(if (shut) Modifier.clearAndSetSemantics { } else Modifier)) {
            // While the answer is awaited the screen glows (until the first words arrive).
            val awaiting by remember { androidx.compose.runtime.derivedStateOf { vm.path.lastOrNull()?.let { it.role == Role.Assistant && it.text.isEmpty() && it.error == null } == true } }
            GenerationGlow(active = (com.apvcode.devx.data.Prefs.thinkingGlow && vm.generating && awaiting) || WelcomeCeremony.glow)

            if (path.isNotEmpty()) {
                com.apvcode.devx.ui.components.ProvideAskToolbar(onAsk = vm::attachSnippet) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().nestedScroll(pullConnection).then(if (lightGlass) Modifier else Modifier.hazeSource(haze)),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topInset + 72.dp, bottom = composerHeight + 12.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    items(path, key = { it.id }) { msg ->
                        val animate = remember(msg.id) { vm.consumeFresh(msg.id) }
                        val flash by animateFloatAsState(if (highlightId == msg.id) 0.14f else 0f, tween(300), label = "flash")
                        val flashColor = DevxColors.TextPrimary
                        Box(
                            Modifier.drawBehind {
                                if (flash > 0f) {
                                    drawRoundRect(
                                        flashColor.copy(alpha = flash),
                                        topLeft = Offset(-6.dp.toPx(), -6.dp.toPx()),
                                        size = Size(size.width + 12.dp.toPx(), size.height + 12.dp.toPx()),
                                        cornerRadius = CornerRadius(20.dp.toPx()),
                                    )
                                }
                            },
                        ) {
                            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                MessageItem(msg, vm, animate, host)
                                // Under the last shortened message: a line that says what comes before it is told short now.
                                val shortened = vm.current
                                // While the context is compressed the card is here, where the result will stay.
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = vm.compacting != null && vm.compacting == vm.currentId && vm.compressAfter == msg.id,
                                    enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 380f)) + androidx.compose.animation.fadeIn(tween(240)),
                                    exit = androidx.compose.animation.shrinkVertically(tween(260)) + androidx.compose.animation.fadeOut(tween(160)),
                                ) {
                                    CompressingCard(vm.compressText, vm.compressStartedAt, vm.compressCount, vm.compressTokens, onCancel = vm::cancelCompaction)
                                }
                                if (shortened != null && shortened.summary.isNotBlank() && shortened.summaryUpTo == msg.id) {
                                    // What was compressed: the messages just before the cut (as many as the compression took).
                                    val at = path.indexOfFirst { it.id == msg.id }
                                    val originals = if (shortened.summaryCount > 0 && at >= 0) path.subList((at + 1 - shortened.summaryCount).coerceAtLeast(0), at + 1).filter { it.text.isNotBlank() } else emptyList()
                                    CompressedCard(
                                        summary = shortened.summary, before = shortened.summaryBefore, after = shortened.summaryAfter,
                                        seconds = shortened.summarySeconds, originals = originals,
                                        fresh = vm.compactedNow == shortened.id, onSeen = { vm.compactedNow = null },
                                    )
                                }
                            }
                        }
                    }
                    if (pinId != null) {
                        item(key = "pin-space") {
                            androidx.compose.foundation.layout.Spacer(Modifier.height(with(density) { spacerPx.toDp() }))
                        }
                    }
                }
                }
            } else {
                Box(Modifier.fillMaxSize().hazeSource(haze))
            }

            // The empty chat greets you by name.
            val hello = com.apvcode.devx.data.Prefs.nickname
            AnimatedVisibility(
                visible = path.isEmpty() && !vm.isTemporary && hello.isNotBlank(),
                modifier = Modifier.align(Alignment.Center).padding(bottom = 96.dp),
                enter = fadeIn(tween(900, delayMillis = 700)) +
                    slideInVertically(tween(1100, delayMillis = 700, easing = FastOutSlowInEasing)) { it / 2 },
                exit = fadeOut(tween(160)),
            ) {
                Text(
                    stringResource(R.string.hello_name, hello),
                    style = DevxType.Large.copy(fontSize = 34.sp, lineHeight = 42.sp),
                    color = DevxColors.TextPrimary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }

            // The empty screen of a temporary chat says what it is.
            AnimatedVisibility(
                visible = path.isEmpty() && vm.isTemporary,
                modifier = Modifier.align(Alignment.Center),
                enter = fadeIn(tween(260)) + scaleIn(spring(dampingRatio = 0.8f, stiffness = 300f), initialScale = 0.9f),
                exit = fadeOut(tween(140)),
            ) {
                Column(
                    Modifier.padding(horizontal = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(DevxIcons.MessageCircle, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(34.dp))
                    Text(stringResource(R.string.temp_title), style = DevxType.Title, color = DevxColors.TextPrimary)
                    Text(
                        stringResource(R.string.temp_body),
                        style = DevxType.Body,
                        color = DevxColors.TextSecondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }

            AnimatedVisibility(
                visible = !findOpen,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer {
                        alpha = intro.value.coerceIn(0f, 1f)
                        translationY = -(1f - intro.value) * 48.dp.toPx()
                    },
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(120)),
            ) {
                TopBar(vm, onOpenDrawer, onPickModel = openPicker, menuActions = menuActions)
            }
            AnimatedVisibility(
                visible = findOpen,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(tween(200)) + androidx.compose.animation.slideInVertically(spring(dampingRatio = 0.85f, stiffness = 420f)) { -it / 2 },
                exit = fadeOut(tween(120)),
            ) {
                FindInChatBar(
                    query = findQuery,
                    onQuery = { findQuery = it; findIndex = 0 },
                    count = matches.size,
                    index = findIndex.coerceIn(0, (matches.size - 1).coerceAtLeast(0)),
                    onPrev = { if (matches.isNotEmpty()) findIndex = (findIndex - 1 + matches.size) % matches.size },
                    onNext = { if (matches.isNotEmpty()) findIndex = (findIndex + 1) % matches.size },
                    onClose = { findOpen = false; findQuery = "" },
                )
            }
            androidx.activity.compose.BackHandler(enabled = findOpen) { findOpen = false; findQuery = "" }

            AnimatedVisibility(
                visible = showDown && path.isNotEmpty(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = composerHeight + 8.dp),
                enter = fadeIn(tween(220)) + scaleIn(spring(dampingRatio = 0.55f, stiffness = 420f), initialScale = 0.35f) + slideInVertically(spring(dampingRatio = 0.7f, stiffness = 380f)) { it },
                exit = fadeOut(tween(160)) + scaleOut(tween(200, easing = FastOutSlowInEasing), targetScale = 0.4f) + slideOutVertically(tween(220)) { it / 2 },
            ) {
                GlassIconButton(
                    icon = Icons.Outlined.ArrowDownward,
                    contentDescription = stringResource(R.string.cd_scroll_down),
                    onClick = {
                        following = true
                        scope.launch {
                            if (distanceToBottom(listState) == Float.MAX_VALUE) listState.animateScrollToItem(path.lastIndex)
                            listState.animateScrollBy(distanceToBottom(listState).coerceAtLeast(0f), tween(480, easing = FastOutSlowInEasing))
                        }
                    },
                    size = 38.dp,
                )
            }

            Composer(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { composerHeight = with(density) { it.height.toDp() } }
                    .graphicsLayer {
                        alpha = intro.value.coerceIn(0f, 1f)
                        translationY = (1f - intro.value) * 96.dp.toPx()
                    },
                value = draft,
                generating = vm.generating,
                attachments = vm.draftAttachments,
                importing = vm.importing,
                fileOf = vm::previewOf,
                onValueChange = { draft = it },
                onSend = {
                    // Remember where the text stands: the new bubble flies out of this very spot.
                    sendOrigin.mark()
                    if (android.os.Build.VERSION.SDK_INT >= 33 && !com.apvcode.devx.data.Prefs.askedNotifications) {
                        com.apvcode.devx.data.Prefs.askedNotifications = true
                        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                    pinNext = true
                    vm.send(draft)
                    draft = ""
                },
                onStop = vm::stop,
                onRemoveAttachment = vm::removeDraftAttachment,
                onOpenAttachment = onOpenPhoto,
                attachOpen = attachOpen,
                onToggleAttach = { attachOpen = !attachOpen },
                clipImage = clipImageAt != 0L && clipImageAt != clipDismissedAt,
                onPasteImage = pasteImages,
                onDismissClip = { clipDismissedAt = clipImageAt },
                onMic = {
                    keyboard?.hide()
                    focus.clearFocus()
                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) startVoice()
                    else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                },
                voice = voice,
                onVoiceDone = {
                    voice.finish { heard ->
                        if (heard.isNotBlank()) draft = if (draft.isBlank()) heard else draft.trimEnd() + " " + heard
                    }
                },
                onVoiceCancel = { voice.cancel() },
                onVoiceLanguage = { tag ->
                    com.apvcode.devx.data.Prefs.saveVoiceLanguage(tag)
                    voice.switchLanguage(tag)
                },
            )

            // The attach menu opens right over the "+", the camera rises from the bottom.
            AttachMenu(
                open = attachOpen,
                onDismiss = { attachOpen = false },
                onCamera = {
                    attachOpen = false
                    if (hasCameraPermission(context)) openCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
                },
                onPhotos = {
                    attachOpen = false
                    imagePicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                },
                onFiles = {
                    attachOpen = false
                    filePicker.launch(arrayOf("*/*"))
                },
                bottomPadding = composerHeight + 4.dp,
                modifier = Modifier.fillMaxSize(),
            )
            CameraPanel(
                open = cameraOpen,
                onClose = { cameraOpen = false },
                onCaptured = {
                    cameraOpen = false
                    vm.attachImages(listOf(it))
                },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.62f),
            )

            } // the chat under the cover
            // A locked chat has no keyboard.
            LaunchedEffect(shut) { if (shut) { keyboard?.hide(); focus.clearFocus() } }
            // One cover per chat: opening a locked chat has it in place from the first frame, only locking the open chat animates it in.
            androidx.compose.runtime.key(vm.current?.id) {
                androidx.compose.animation.AnimatedVisibility(visible = shut, enter = fadeIn(tween(200)) + scaleIn(tween(320, easing = FastOutSlowInEasing), initialScale = 1.05f), exit = fadeOut(tween(420)) + scaleOut(tween(460, easing = FastOutSlowInEasing), targetScale = 1.1f)) {
                    if (com.apvcode.devx.data.AppLock.locksChats) {
                        // The app's own code (or the fingerprint): the padlock opens and the chat shows.
                        com.apvcode.devx.ui.lock.ChatLockCover(onUnlocked = { vm.current?.let { vm.unlock(it.id) } }, onMenu = onOpenDrawer)
                    } else {
                        LaunchedEffect(vm.currentId) { unlockNow() }
                        LockedCover(onUnlock = { unlockNow() })
                    }
                }
            }
        }
    }

    if (deleteAsk) {
        vm.current?.let { chat ->
            ConfirmDeleteChat(chat, onDismiss = { deleteAsk = false }) { vm.deleteChat(chat.id) }
        } ?: run { deleteAsk = false }
    }

    if (pickerOpen) {
        ModelPicker(vm, onDismiss = { pickerOpen = false }, onOpenProviders = {
            pickerOpen = false
            onOpenProviders()
        })
    }
}

/** How far the end of the list is below the screen, in pixels (Float.MAX_VALUE when the last item is not even in view). */
internal fun distanceToBottom(state: androidx.compose.foundation.lazy.LazyListState): Float {
    val info = state.layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return 0f
    if (last.index < info.totalItemsCount - 1) return Float.MAX_VALUE
    return (last.offset + last.size - (info.viewportEndOffset - info.afterContentPadding)).toFloat().coerceAtLeast(0f)
}

@Composable
private fun TopBar(vm: ChatViewModel, onOpenDrawer: () -> Unit, onPickModel: () -> Unit, menuActions: ChatMenuActions, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(DevxColors.Bg.copy(alpha = 0.7f), Color.Transparent)))
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(DevxIcons.Menu4, stringResource(R.string.cd_menu), onOpenDrawer)
            Box(Modifier.size(8.dp))
            // The model chip grows and shrinks smoothly with its content, and the words change with a slide.
            Row(
                Modifier
                    .height(42.dp)
                    .widthIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * 0.66f).dp)
                    .pressable(scaleTo = 0.93f, onClick = onPickModel)
                    // The glass is OUTSIDE the size animation: the shape itself grows and shrinks. (The other way
                    // round, a finished pill is cut by a moving edge, and that is a wall.)
                    .glass(CircleShape)
                    .animateContentSize(spring(dampingRatio = 0.86f, stiffness = 260f), alignment = Alignment.CenterStart)
                    .padding(start = 16.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val noModel = stringResource(R.string.model_none)
                AnimatedContent(
                    targetState = vm.model?.model ?: noModel,
                    transitionSpec = {
                        (fadeIn(tween(340, delayMillis = 90)) + slideInVertically(tween(420, delayMillis = 60, easing = FastOutSlowInEasing)) { it / 4 }) togetherWith
                            (fadeOut(tween(240)) + slideOutVertically(tween(300, easing = FastOutSlowInEasing)) { -it / 4 }) using
                            // The size of the words changes at once: the chip itself glides to it.
                            SizeTransform(clip = false) { _, _ -> snap() }
                    },
                    modifier = Modifier.weight(1f, fill = false),
                    label = "model-name",
                ) { name ->
                    Text(name, style = DevxType.Body, color = DevxColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // The level part appears and goes away with the width of the chip, and its word slides when it changes.
                val level = vm.reasoningLevel
                var lastLevel by remember { mutableStateOf(level) }
                if (level != null) lastLevel = level
                AnimatedVisibility(
                    visible = level != null,
                    enter = fadeIn(tween(320, delayMillis = 120)) + androidx.compose.animation.expandHorizontally(spring(dampingRatio = 0.86f, stiffness = 260f)),
                    exit = fadeOut(tween(200)) + androidx.compose.animation.shrinkHorizontally(spring(dampingRatio = 0.9f, stiffness = 300f)),
                ) {
                    AnimatedContent(
                        targetState = lastLevel,
                        transitionSpec = {
                            (fadeIn(tween(320, delayMillis = 90)) + slideInVertically(tween(400, delayMillis = 60, easing = FastOutSlowInEasing)) { it / 4 }) togetherWith
                                (fadeOut(tween(220)) + slideOutVertically(tween(280, easing = FastOutSlowInEasing)) { -it / 4 }) using
                                SizeTransform(clip = false) { _, _ -> snap() }
                        },
                        label = "model-level",
                    ) { shown ->
                        Text(
                            shown?.let { " · " + stringResource(levelLabelRes(it)) }.orEmpty(),
                            style = DevxType.Body,
                            color = DevxColors.TextSecondary,
                            maxLines = 1,
                        )
                    }
                }
                Icon(Icons.Outlined.KeyboardArrowDown, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(20.dp))
            }
            Box(Modifier.weight(1f))
            // One button at a time, in a pill that never changes size: the temporary-chat button on the empty
            // screen and inside a temporary chat, the three dots in a saved chat. They trade places in
            // the same spot instead of the pill stretching to make room.
            val showTemporary = vm.current == null || vm.current?.temporary == true
            Box(Modifier.size(width = 44.dp, height = 42.dp).glass(CircleShape), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = showTemporary,
                    transitionSpec = {
                        (fadeIn(tween(200, delayMillis = 60)) + scaleIn(spring(dampingRatio = 0.8f, stiffness = 420f), initialScale = 0.7f)) togetherWith
                            (fadeOut(tween(110)) + scaleOut(tween(130), targetScale = 0.7f)) using SizeTransform(clip = false) { _, _ -> snap() }
                    },
                    label = "bar-button",
                ) { temporaryButton ->
                    if (temporaryButton) {
                        val active = vm.isTemporary
                        val fill by animateColorAsState(if (active) DevxColors.Accent else Color.Transparent, tween(220), label = "temp-fill")
                        val tint by animateColorAsState(if (active) DevxColors.OnAccent else DevxColors.TextPrimary, tween(220), label = "temp-tint")
                        Box(
                            Modifier.size(width = 44.dp, height = 42.dp).pressable(onClick = vm::toggleTemporary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(34.dp).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
                                Icon(DevxIcons.MessageCircle, stringResource(R.string.cd_temp_chat), tint = tint, modifier = Modifier.size(22.dp))
                            }
                        }
                    } else {
                        vm.current?.let { ChatMenuButton(it, vm, menuActions) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BarIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(width = 44.dp, height = 42.dp).pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = DevxColors.TextPrimary, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun Composer(
    modifier: Modifier,
    value: String,
    generating: Boolean,
    attachments: List<Attachment>,
    importing: Int,
    fileOf: (Attachment) -> File,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onOpenAttachment: (Attachment) -> Unit,
    attachOpen: Boolean,
    onToggleAttach: () -> Unit,
    onMic: () -> Unit,
    voice: com.apvcode.devx.data.VoiceInput,
    onVoiceDone: () -> Unit,
    onVoiceCancel: () -> Unit,
    onVoiceLanguage: (String) -> Unit,
    clipImage: Boolean = false,
    onPasteImage: () -> Unit = {},
    onDismissClip: () -> Unit = {},
) {
    val origin = LocalSendOrigin.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // On send the whole pill gives a little squeeze and springs back, like a pressed key.
    val pulse = remember { Animatable(1f) }
    val sendWithFeel = {
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        scope.launch {
            pulse.snapTo(0.965f)
            pulse.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 380f))
        }
        onSend()
    }
    var focused by remember { mutableStateOf(false) }
    var lines by remember { mutableIntStateOf(1) }
    val edge by animateFloatAsState(if (focused) 1f else 0f, tween(220), label = "focus")
    // Asked from the tile or the widget: the field takes the focus and the keyboard comes up.
    val fieldFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val fieldKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(com.apvcode.devx.data.PendingAsk.focus) {
        if (!com.apvcode.devx.data.PendingAsk.focus) return@LaunchedEffect
        kotlinx.coroutines.delay(380)
        runCatching { fieldFocus.requestFocus() }
        fieldKeyboard?.show()
        com.apvcode.devx.data.PendingAsk.focus = false
    }
    // A pull upwards at the end of the chat: the keyboard comes up at once.
    LaunchedEffect(com.apvcode.devx.data.PendingAsk.keyboard) {
        if (!com.apvcode.devx.data.PendingAsk.keyboard) return@LaunchedEffect
        com.apvcode.devx.data.PendingAsk.keyboard = false
        runCatching { fieldFocus.requestFocus() }
        fieldKeyboard?.show()
    }
    val canSend = (value.isNotBlank() || attachments.isNotEmpty()) && importing == 0
    Column(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The picture that waits in the clipboard: one tap puts it in the message.
        AnimatedVisibility(
            visible = clipImage,
            enter = fadeIn(tween(200)) + expandVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) + scaleIn(spring(dampingRatio = 0.7f, stiffness = 420f), initialScale = 0.8f),
            exit = fadeOut(tween(140)) + shrinkVertically(tween(180)),
        ) {
            Row(
                Modifier.padding(bottom = 2.dp).glass(CircleShape).pressable(scaleTo = 0.97f, onClick = onPasteImage).padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(DevxIcons.Photo, null, tint = DevxColors.TextPrimary, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.paste_image), style = DevxType.Body.copy(fontSize = 15.sp), color = DevxColors.TextPrimary, modifier = Modifier.padding(vertical = 9.dp))
                Box(Modifier.size(36.dp).pressable(onClick = onDismissClip), contentAlignment = Alignment.Center) {
                    Icon(androidx.compose.material.icons.Icons.Outlined.Close, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(18.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The plus turns into a cross while the menu is open.
            val turn by animateFloatAsState(if (attachOpen) 45f else 0f, spring(dampingRatio = 0.6f, stiffness = 420f), label = "plus")
            Box(
                Modifier.size(42.dp).pressable(onClick = onToggleAttach).glass(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Add,
                    stringResource(R.string.cd_attach),
                    tint = DevxColors.TextPrimary,
                    modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = turn },
                )
            }
            // With something attached the pill becomes a card: the pictures on top, the text below them.
            val hasMedia = attachments.isNotEmpty() || importing > 0
            val radius by androidx.compose.animation.core.animateDpAsState(if (hasMedia) 28.dp else 21.dp, spring(dampingRatio = 0.85f, stiffness = 420f), label = "radius")
            Column(
                Modifier
                    .weight(1f)
                    .graphicsLayer {
                        scaleX = pulse.value * (1f + 0.006f * edge)
                        scaleY = pulse.value * (1f + 0.006f * edge)
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .glass(RoundedCornerShape(radius))
                    .border(1.dp, DevxColors.TextPrimary.copy(alpha = 0.16f * edge), RoundedCornerShape(radius))
                    // A new line of text makes the pill taller. It grows quickly and without a bounce, anchored at the bottom where the
                    // text sits: a soft bouncing spring anchored at the top made the line being typed jump up and down.
                    .animateContentSize(tween(110, easing = androidx.compose.animation.core.FastOutSlowInEasing), alignment = Alignment.BottomStart),
            ) {
            AnimatedVisibility(
                visible = hasMedia,
                enter = fadeIn(tween(180)) + expandVertically(spring(dampingRatio = 0.85f, stiffness = 420f)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(180)),
            ) {
                ComposerAttachments(attachments, importing, fileOf, onRemoveAttachment, onOpen = onOpenAttachment)
            }
            androidx.compose.animation.Crossfade(targetState = voice.state != com.apvcode.devx.data.VoiceInput.State.Idle, animationSpec = tween(220), label = "composer-voice") { listening ->
                if (listening) {
                    VoiceBar(voice, onDone = onVoiceDone, onCancel = onVoiceCancel, onLanguage = onVoiceLanguage)
                } else {
                // The microphone and the send button share the corner: with nothing written the microphone is the one white button;
                // with a message it steps aside, to the left of the arrow, and for a long message it rises above the arrow.
                val showSend = canSend || generating
                val stacked = showSend && !generating && lines >= 3
                val endPad by androidx.compose.animation.core.animateDpAsState(
                    if (showSend && !stacked) 90.dp else 48.dp, spring(dampingRatio = 0.85f, stiffness = 420f), label = "field-end",
                )
                val micX by androidx.compose.animation.core.animateDpAsState(if (showSend && !stacked) (-42).dp else 0.dp, spring(dampingRatio = 0.8f, stiffness = 460f), label = "mic-x")
                val micY by androidx.compose.animation.core.animateDpAsState(if (stacked) (-42).dp else 0.dp, spring(dampingRatio = 0.8f, stiffness = 460f), label = "mic-y")
                val micQuiet by animateFloatAsState(if (showSend) 1f else 0f, tween(220), label = "mic-quiet")
                Box(Modifier.fillMaxWidth().heightIn(min = if (stacked) 90.dp else 42.dp)) {
                    // The text sits exactly in the middle of a one-line pill and grows upwards.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = endPad)
                            .heightIn(min = 42.dp)
                            .align(Alignment.BottomStart)
                            .onGloballyPositioned { origin.field = it.boundsInWindow() },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            textStyle = DevxType.Body.copy(color = DevxColors.TextPrimary),
                            cursorBrush = SolidColor(DevxColors.Accent),
                            maxLines = 6,
                            onTextLayout = { lines = it.lineCount },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).focusRequester(fieldFocus).onFocusChanged { focused = it.isFocused },
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (value.isEmpty()) {
                                        Text(stringResource(R.string.chat_placeholder), style = DevxType.Body, color = DevxColors.TextSecondary, maxLines = 1)
                                    }
                                    inner()
                                }
                            },
                        )
                    }
                    // Send (or stop) in the corner; while there is nothing to send, the microphone takes its place.
                    Box(Modifier.align(Alignment.BottomEnd).padding(end = 3.dp).size(42.dp), contentAlignment = Alignment.Center) {
                        androidx.compose.animation.AnimatedContent(
                            targetState = showSend,
                            transitionSpec = { (fadeIn(tween(180)) + scaleIn(spring(dampingRatio = 0.6f, stiffness = 420f), initialScale = 0.5f)) togetherWith (fadeOut(tween(120)) + androidx.compose.animation.scaleOut(tween(140), targetScale = 0.5f)) },
                            label = "send-or-mic",
                        ) { send ->
                            if (send) {
                                SendButton(canSend = canSend, generating = generating, onSend = sendWithFeel, onStop = onStop)
                            } else {
                                Box(
                                    Modifier.size(36.dp).pressable(onClick = onMic).clip(CircleShape).background(DevxColors.Accent),
                                    contentAlignment = Alignment.Center,
                                ) { Icon(Icons.Outlined.Mic, stringResource(R.string.voice_prompt), tint = DevxColors.OnAccent, modifier = Modifier.size(20.dp)) }
                            }
                        }
                    }
                    // The microphone next to the arrow, for adding more by voice.
                    if (micQuiet > 0.01f && !generating) {
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 3.dp)
                                .offset(x = micX, y = micY)
                                .size(42.dp)
                                .graphicsLayer { alpha = micQuiet }
                                .pressable(onClick = onMic),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Outlined.Mic, stringResource(R.string.voice_prompt), tint = DevxColors.TextSecondary, modifier = Modifier.size(22.dp)) }
                    }
                }
                }
            }
            }
        }
    }
}

/**
 * The round send button. It fills in white when there is something to send, squeezes under the
 * finger, and on send the arrow flies up out of the circle and comes back from below. While an
 * answer is being written it turns into "stop".
 */
@Composable
internal fun SendButton(canSend: Boolean, generating: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    val scope = rememberCoroutineScope()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val active = canSend || generating
    val fill by animateColorAsState(if (active) DevxColors.Accent else DevxColors.Outline, tween(200), label = "fill")
    val arrow by animateColorAsState(if (active) DevxColors.OnAccent else DevxColors.TextDisabled, tween(200), label = "arrow")
    val squeeze by animateFloatAsState(
        if (pressed && active) 0.86f else 1f,
        spring(dampingRatio = 0.55f, stiffness = 500f),
        label = "squeeze",
    )
    val ready by animateFloatAsState(
        if (active) 1f else 0.92f,
        spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium),
        label = "ready",
    )
    val flight = remember { Animatable(0f) }
    Box(
        Modifier
            .size(36.dp)
            .graphicsLayer {
                scaleX = squeeze * ready
                scaleY = squeeze * ready
            }
            .clip(CircleShape)
            .background(fill)
            .clickable(interactionSource = source, indication = null, enabled = active) {
                if (generating) {
                    onStop()
                } else {
                    onSend()
                    scope.launch {
                        flight.snapTo(0f)
                        flight.animateTo(1f, tween(340, easing = FastOutSlowInEasing))
                        flight.snapTo(0f)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = generating, animationSpec = tween(180), label = "send-or-stop") { isStop ->
            if (isStop) {
                Icon(DevxIcons.Stop, stringResource(R.string.cd_stop), tint = arrow, modifier = Modifier.size(20.dp))
            } else {
                Icon(
                    Icons.Outlined.ArrowUpward,
                    stringResource(R.string.cd_send),
                    tint = arrow,
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer {
                            // 0..0.5: up and out; 0.5..1: in from below.
                            val f = flight.value
                            val travel = 26.dp.toPx()
                            if (f < 0.5f) {
                                val t = f * 2f
                                translationY = -travel * t
                                alpha = 1f - t
                            } else {
                                val t = (f - 0.5f) * 2f
                                translationY = travel * (1f - t)
                                alpha = t
                            }
                        },
                )
            }
        }
    }
}
