package com.apvcode.devx.ui

import androidx.compose.ui.unit.dp
import com.apvcode.devx.ui.chat.irisHole
import com.apvcode.devx.ui.chat.WelcomeCeremony
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.Animatable
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.apvcode.devx.data.Attachment
import com.apvcode.devx.data.Msg
import com.apvcode.devx.data.Prefs
import com.apvcode.devx.ui.chat.ChatScreen
import com.apvcode.devx.ui.chat.ChatViewModel
import com.apvcode.devx.ui.screens.AboutScreen
import com.apvcode.devx.ui.remote.ComputerScreen
import com.apvcode.devx.ui.remote.PairScreen
import com.apvcode.devx.ui.remote.RemoteChatScreen
import com.apvcode.devx.ui.remote.RemoteHomeScreen
import com.apvcode.devx.ui.screens.EditMessageScreen
import com.apvcode.devx.ui.screens.McpScreen
import com.apvcode.devx.ui.screens.NotificationsScreen
import com.apvcode.devx.ui.screens.PhotoViewer
import com.apvcode.devx.ui.screens.ProviderEditScreen
import com.apvcode.devx.ui.screens.ProvidersScreen
import com.apvcode.devx.ui.screens.SearchScreen
import com.apvcode.devx.ui.screens.SelectTextScreen
import com.apvcode.devx.ui.screens.SettingsScreen
import com.apvcode.devx.ui.screens.TasksScreen
import com.apvcode.devx.ui.screens.ThinkingScreen
import com.apvcode.devx.ui.theme.DevxColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class Screen { Chat, Tasks, Computers, AddConnection, Computer, RemoteChat, Mcp, Settings, Providers, ProviderEdit, Notifications, About, Memory, MemorySummary, MemoryNotes, Project, ProjectEdit, ProjectKnowledge, ProjectSummary, Skills, SkillEdit, Assistant, VoiceAssistant, TaskEdit, AppLock, Persona }

private fun parentOf(screen: Screen): Screen = when (screen) {
    Screen.AddConnection, Screen.Computer -> Screen.Computers
    Screen.TaskEdit -> Screen.Tasks
    Screen.RemoteChat -> Screen.Computer
    Screen.ProviderEdit -> Screen.Providers
    Screen.Providers, Screen.Notifications, Screen.About, Screen.Memory, Screen.Skills, Screen.Assistant, Screen.VoiceAssistant, Screen.AppLock, Screen.Persona -> Screen.Settings
    Screen.MemorySummary, Screen.MemoryNotes -> Screen.Memory
    Screen.ProjectEdit, Screen.ProjectKnowledge, Screen.ProjectSummary -> Screen.Project
    Screen.SkillEdit -> Screen.Skills
    else -> Screen.Chat
}

/** A full-screen page that slides over the chat. */
private sealed interface Overlay {
    data class SelectText(val text: String) : Overlay
    data class Edit(val message: Msg) : Overlay
    data class Photo(val attachment: Attachment) : Overlay
    data class Thinking(val message: Msg) : Overlay
    data class Uploaded(val chatId: String) : Overlay
    data class Sources(val message: Msg) : Overlay
    data class Steps(val message: Msg) : Overlay
    data class File(val artifact: com.apvcode.devx.data.Artifact) : Overlay
}

@Composable
fun DevxApp(vm: ChatViewModel, requestedChat: String? = null, onChatHandled: () -> Unit = {}) {
    var splash by rememberSaveable { mutableStateOf(true) }

    // Wake-up: read the saved data, then ask every provider for its models, but never keep the
    // person waiting more than three seconds in total; late answers simply arrive while they use the app.
    LaunchedEffect(Unit) {
        val started = SystemClock.elapsedRealtime()
        snapshotFlow { vm.loaded }.first { it }
        val refresh = vm.refreshAllModels()
        val left = 3_000L - (SystemClock.elapsedRealtime() - started)
        withTimeoutOrNull(left.coerceAtLeast(0L)) { refresh.join() }
        val shown = SystemClock.elapsedRealtime() - started
        if (shown < 1_100L) delay(1_100L - shown) // let the opening animation finish
        splash = false
    }

    // Splash, then (first start only) the question for a name, then the app. After the name the chat is already
    // there under the greeting, and the greeting opens like an iris onto it.
    val iris = remember { Animatable(0f) }
    var welcomeShown by remember { mutableStateOf(!Prefs.onboarded) }
    val ceremonyScope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
    Crossfade(targetState = if (splash) 0 else 1, animationSpec = tween(450), label = "splash") { current ->
        if (current == 0) {
            SplashScreen()
        } else {
            Box(Modifier.fillMaxSize().background(DevxColors.Bg)) {
                if (Prefs.onboarded && !com.apvcode.devx.data.AppLock.hideContent) {
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            if (welcomeShown) {
                                // The chat comes into focus: from a soft blur and a little too big to sharp and in place.
                                val e = iris.value
                                val scale = 1.07f - 0.07f * e
                                scaleX = scale
                                scaleY = scale
                                val blur = (1f - e) * 26.dp.toPx()
                                if (blur > 0.5f) renderEffect = androidx.compose.ui.graphics.BlurEffect(blur, blur, androidx.compose.ui.graphics.TileMode.Decal)
                            }
                        },
                    ) { MainApp(vm, requestedChat, onChatHandled) }
                }
                if (welcomeShown) {
                    WelcomeScreen(
                        onDone = { name ->
                            Prefs.saveNickname(name)
                            ceremonyScope.launch {
                                WelcomeCeremony.running = true
                                WelcomeCeremony.glow = true
                                iris.animateTo(1f, tween(2100, easing = FastOutSlowInEasing))
                                welcomeShown = false
                                WelcomeCeremony.running = false
                                delay(1800)
                                WelcomeCeremony.glow = false
                            }
                        },
                        modifier = Modifier.irisHole { iris.value },
                    )
                }
            }
        }
    }
    // The lock of the app covers everything, even the opening animation.
    com.apvcode.devx.ui.lock.AppLockOverlay(ready = !splash)
    }
}

@Composable
private fun MainApp(vm: ChatViewModel, requestedChat: String?, onChatHandled: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf(Screen.Chat) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var editingProvider by rememberSaveable { mutableStateOf<String?>(null) }
    var projectName by rememberSaveable { mutableStateOf<String?>(null) }
    var remoteHost by rememberSaveable { mutableStateOf<String?>(null) }
    var remoteSession by rememberSaveable { mutableStateOf<String?>(null) }
    var remoteDraft by rememberSaveable { mutableStateOf<String?>(null) }
    var pickFolder by remember { mutableStateOf(false) }
    var editingProject by rememberSaveable { mutableStateOf<String?>(null) }
    var editingTask by rememberSaveable { mutableStateOf<Int?>(null) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var lastOverlay by remember { mutableStateOf<Overlay?>(null) }
    if (overlay != null) lastOverlay = overlay
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    // The list of chats opening takes the keyboard away: from the moment the panel starts to slide out (a swipe or the button).
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(drawerState) {
        var closedAt: Float? = null
        androidx.compose.runtime.snapshotFlow { drawerState.currentOffset to (drawerState.targetValue != DrawerValue.Closed) }.collect { (offset, opening) ->
            if (closedAt == null) closedAt = offset.takeIf { drawerState.currentValue == DrawerValue.Closed }
            val moved = closedAt?.let { offset > it + 6f } ?: false
            if (moved || opening) { keyboard?.hide(); focusManager.clearFocus() }
        }
    }
    val scope = rememberCoroutineScope()

    // A home-screen icon or a notification asked for a chat: open it, or say that it is gone.
    var chatGone by remember { mutableStateOf(false) }
    // "Share" from another app: a new chat opens with what was shared.
    LaunchedEffect(com.apvcode.devx.data.PendingShare.item) {
        val shared = com.apvcode.devx.data.PendingShare.item ?: return@LaunchedEffect
        com.apvcode.devx.data.PendingShare.item = null
        vm.startFromShare(shared.text, shared.uris)
        screen = Screen.Chat; overlay = null; drawerState.close()
    }
    // "Ask devx" from the tile or the widget: a fresh chat, and the keyboard up on it.
    LaunchedEffect(com.apvcode.devx.data.PendingAsk.request) {
        if (!com.apvcode.devx.data.PendingAsk.request) return@LaunchedEffect
        com.apvcode.devx.data.PendingAsk.request = false
        vm.newChat()
        screen = Screen.Chat; overlay = null; drawerState.close()
        com.apvcode.devx.data.PendingAsk.focus = true
    }
    // The same from the widgets: a chat that starts listening, a temporary one, the computers.
    LaunchedEffect(com.apvcode.devx.data.PendingAsk.voice) {
        if (!com.apvcode.devx.data.PendingAsk.voice) return@LaunchedEffect
        vm.newChat()
        screen = Screen.Chat; overlay = null; drawerState.close()
        // The chat screen picks the flag up and opens the microphone once it is on the screen.
    }
    LaunchedEffect(com.apvcode.devx.data.PendingAsk.temporary) {
        if (!com.apvcode.devx.data.PendingAsk.temporary) return@LaunchedEffect
        com.apvcode.devx.data.PendingAsk.temporary = false
        vm.newChat()
        if (!vm.isTemporary) vm.toggleTemporary()
        screen = Screen.Chat; overlay = null; drawerState.close()
        com.apvcode.devx.data.PendingAsk.focus = true
    }
    LaunchedEffect(com.apvcode.devx.data.PendingAsk.computers) {
        if (!com.apvcode.devx.data.PendingAsk.computers) return@LaunchedEffect
        com.apvcode.devx.data.PendingAsk.computers = false
        screen = Screen.Computers; overlay = null; drawerState.close()
    }
    LaunchedEffect(requestedChat) {
        val id = requestedChat ?: return@LaunchedEffect
        if (vm.chatExists(id)) {
            vm.openChat(id)
            screen = Screen.Chat
            overlay = null
            drawerState.close()
        } else {
            chatGone = true
        }
        onChatHandled()
    }
    // Locked chats shut again as soon as the app is left.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { vm.relockAll() }
    LaunchedEffect(com.apvcode.devx.data.AppLock.panic) { if (com.apvcode.devx.data.AppLock.panic > 0) vm.relockAll() }
    // A notification of the agent (a permission, an answer) asked for its chat.
    LaunchedEffect(com.apvcode.devx.remote.PendingRemoteChat.target) {
        val t = com.apvcode.devx.remote.PendingRemoteChat.target ?: return@LaunchedEffect
        com.apvcode.devx.remote.PendingRemoteChat.target = null
        remoteHost = t.first; remoteSession = t.second; remoteDraft = null; screen = Screen.RemoteChat; overlay = null; drawerState.close()
    }
    // A pairing code came as a link: straight to the pairing screen.
    LaunchedEffect(com.apvcode.devx.remote.PendingPairing.code) {
        if (com.apvcode.devx.remote.PendingPairing.code != null) { screen = Screen.AddConnection; overlay = null; drawerState.close() }
    }
    // Back in the app: the computers are tried at once, without waiting for the next round.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_START) {
        com.apvcode.devx.remote.RemoteLink.host?.let { h -> com.apvcode.devx.remote.RemoteHub.connection(h)?.let { it.start(); it.nudge() } }
    }
    // The connection to a computer (and its notification) exists only while its list of chats or one of its chats is open.
    val remoteContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(screen, remoteHost) {
        val host = remoteHost
        if ((screen == Screen.Computer || screen == Screen.RemoteChat) && host != null) {
            com.apvcode.devx.remote.RemoteLink.connect(remoteContext, host)
        } else {
            com.apvcode.devx.remote.RemoteLink.disconnect(remoteContext)
            // Out of the whole remote section: no computer is tried any more.
            if (screen != Screen.Computers && screen != Screen.AddConnection) com.apvcode.devx.remote.RemoteHub.stopAll()
        }
    }
    // "Disconnect" in the notification pressed while the app is open on the computer: back to the list of computers.
    LaunchedEffect(com.apvcode.devx.remote.RemoteLink.dropped) {
        if (com.apvcode.devx.remote.RemoteLink.dropped > 0 && (screen == Screen.Computer || screen == Screen.RemoteChat)) screen = Screen.Computers
    }

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    BackHandler(enabled = screen != Screen.Chat && !drawerState.isOpen) {
        screen = if (screen == Screen.ProjectEdit && editingProject == null) Screen.Chat else parentOf(screen)
    }
    BackHandler(enabled = searchOpen) { searchOpen = false }
    BackHandler(enabled = overlay != null) { overlay = null }

    Box(Modifier.fillMaxSize().background(DevxColors.Bg)) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = screen == Screen.Chat || drawerState.isOpen,
            scrimColor = Color.Black.copy(alpha = 0.6f),
            drawerContent = {
                DrawerContent(
                    vm = vm,
                    onSearch = {
                        searchOpen = true
                        scope.launch { drawerState.close() }
                    },
                    onOpenProject = { name ->
                        projectName = name
                        screen = Screen.Project
                        scope.launch { drawerState.close() }
                    },
                    onNewProject = {
                        editingProject = null
                        screen = Screen.ProjectEdit
                        scope.launch { drawerState.close() }
                    },
                    onNewChat = {
                        vm.newChat()
                        screen = Screen.Chat
                        scope.launch { drawerState.close() }
                    },
                    onOpenChat = { id ->
                        vm.openChat(id)
                        screen = Screen.Chat
                        scope.launch { drawerState.close() }
                    },
                    onNavigate = {
                        screen = it
                        scope.launch { drawerState.close() }
                    },
                )
            },
        ) {
            Crossfade(targetState = screen, label = "screen", modifier = Modifier.fillMaxSize().background(DevxColors.Bg)) { current ->
                when (current) {
                    Screen.Chat -> ChatScreen(
                        vm,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onOpenProviders = { screen = Screen.Providers },
                        onSelectText = { overlay = Overlay.SelectText(it) },
                        onEdit = { overlay = Overlay.Edit(it) },
                        onOpenPhoto = { overlay = Overlay.Photo(it) },
                        onShowThinking = { overlay = Overlay.Thinking(it) },
                        onShowUploaded = { overlay = Overlay.Uploaded(it) },
                        onShowSources = { overlay = Overlay.Sources(it) },
                        onOpenFile = { overlay = Overlay.File(it) },
                        onShowSteps = { overlay = Overlay.Steps(it) },
                    )
                    Screen.Tasks -> TasksScreen(vm, onBack = { screen = Screen.Chat }, onEdit = { id -> editingTask = id; screen = Screen.TaskEdit })
                    Screen.TaskEdit -> com.apvcode.devx.ui.screens.TaskEditScreen(vm, editingTask, onBack = { screen = Screen.Tasks }, onOpenChat = { id -> vm.openChat(id); screen = Screen.Chat })
                    Screen.Computers -> RemoteHomeScreen(
                        onBack = { screen = Screen.Chat },
                        onOpen = { remoteHost = it; screen = Screen.Computer },
                        onAdd = { screen = Screen.AddConnection },
                    )
                    Screen.AddConnection -> PairScreen(onBack = { screen = Screen.Computers }, onDone = { remoteHost = it; screen = Screen.Computer })
                    Screen.Computer -> ComputerScreen(
                        remoteHost.orEmpty(),
                        onBack = { screen = Screen.Computers },
                        onOpenSession = { remoteSession = it; remoteDraft = null; screen = Screen.RemoteChat },
                        onNewSession = { remoteSession = ""; remoteDraft = it; screen = Screen.RemoteChat },
                        onForgotten = { screen = Screen.Computers },
                        openPicker = pickFolder,
                        onPickerShown = { pickFolder = false },
                    )
                    Screen.RemoteChat -> RemoteChatScreen(remoteHost.orEmpty(), remoteSession.orEmpty(), draftCwd = remoteDraft, onBack = { screen = Screen.Computer }, onNewChat = { pickFolder = true; screen = Screen.Computer })
                    Screen.Mcp -> McpScreen(vm, onBack = { screen = Screen.Chat })
                    Screen.Settings -> SettingsScreen(
                        vm,
                        onBack = { screen = Screen.Chat },
                        onProviders = { screen = Screen.Providers },
                        onNotifications = { screen = Screen.Notifications },
                        onAbout = { screen = Screen.About },
                        onMemory = { screen = Screen.Memory },
                        onSkills = { screen = Screen.Skills },
                        onAssistant = { screen = Screen.Assistant },
                        onVoiceAssistant = { screen = Screen.VoiceAssistant },
                        onAppLock = { screen = Screen.AppLock },
                        onPersona = { screen = Screen.Persona },
                    )
                    Screen.Persona -> com.apvcode.devx.ui.screens.PersonaScreen(onBack = { screen = Screen.Settings })
                    Screen.AppLock -> com.apvcode.devx.ui.lock.AppLockScreen(onBack = { screen = Screen.Settings })
                    Screen.Providers -> ProvidersScreen(
                        vm,
                        onBack = { screen = Screen.Settings },
                        onAdd = {
                            editingProvider = null
                            screen = Screen.ProviderEdit
                        },
                        onEdit = {
                            editingProvider = it
                            screen = Screen.ProviderEdit
                        },
                    )
                    Screen.ProviderEdit -> ProviderEditScreen(vm, editingProvider, onBack = { screen = Screen.Providers })
                    Screen.Notifications -> NotificationsScreen(onBack = { screen = Screen.Settings })
                    Screen.About -> AboutScreen(onBack = { screen = Screen.Settings })
                    Screen.Memory -> com.apvcode.devx.ui.screens.MemoryScreen(
                        vm,
                        onBack = { screen = Screen.Settings },
                        onSummary = { screen = Screen.MemorySummary },
                        onNotes = { screen = Screen.MemoryNotes },
                    )
                    Screen.Project -> com.apvcode.devx.ui.screens.ProjectScreen(
                        vm,
                        projectName.orEmpty(),
                        onBack = { screen = Screen.Chat },
                        onEdit = { editingProject = projectName; screen = Screen.ProjectEdit },
                        onKnowledge = { screen = Screen.ProjectKnowledge },
                        onSummary = { screen = Screen.ProjectSummary },
                        onOpenChat = { id -> vm.openChat(id); screen = Screen.Chat },
                        onNewChat = { vm.newChat(projectName); screen = Screen.Chat },
                    )
                    Screen.ProjectEdit -> com.apvcode.devx.ui.screens.ProjectEditScreen(
                        vm,
                        editingProject,
                        onClose = { screen = if (editingProject == null) Screen.Chat else Screen.Project },
                        onDone = { name -> projectName = name; screen = Screen.Project },
                    )
                    Screen.ProjectKnowledge -> com.apvcode.devx.ui.screens.MemoryNotesScreen(vm, onBack = { screen = Screen.Project }, project = projectName)
                    Screen.ProjectSummary -> com.apvcode.devx.ui.screens.MemorySummaryScreen(vm, onBack = { screen = Screen.Project }, project = projectName)
                    Screen.VoiceAssistant -> com.apvcode.devx.ui.assistant.VoiceSettingsScreen(onBack = { screen = Screen.Settings })
                    Screen.Assistant -> com.apvcode.devx.ui.screens.AssistantScreen(vm, onBack = { screen = Screen.Settings })
                    Screen.Skills -> com.apvcode.devx.ui.screens.SkillsScreen(vm, onBack = { screen = Screen.Settings }, onWrite = { screen = Screen.SkillEdit })
                    Screen.SkillEdit -> com.apvcode.devx.ui.screens.SkillEditScreen(vm, onClose = { screen = Screen.Skills }, onDone = { screen = Screen.Skills })
                    Screen.MemorySummary -> com.apvcode.devx.ui.screens.MemorySummaryScreen(vm, onBack = { screen = Screen.Memory })
                    Screen.MemoryNotes -> com.apvcode.devx.ui.screens.MemoryNotesScreen(vm, onBack = { screen = Screen.Memory })
                }
            }
        }

        // Pages that slide up over everything: each one is its own layer.
        AnimatedVisibility(
            visible = searchOpen,
            enter = fadeIn(tween(220)) + slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it / 8 },
            exit = fadeOut(tween(180)) + slideOutVertically(tween(260, easing = FastOutSlowInEasing)) { it / 8 },
        ) {
            SearchScreen(
                chats = vm.chats,
                search = vm::search,
                onClose = { searchOpen = false },
                onOpenChat = { id, messageId ->
                    vm.openChatAt(id, messageId)
                    screen = Screen.Chat
                    searchOpen = false
                },
            )
        }

        AnimatedVisibility(
            visible = overlay != null,
            enter = fadeIn(tween(220)) + slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it / 8 },
            exit = fadeOut(tween(180)) + slideOutVertically(tween(260, easing = FastOutSlowInEasing)) { it / 8 },
        ) {
            when (val page = lastOverlay) {
                is Overlay.SelectText -> com.apvcode.devx.ui.components.ProvideAskToolbar(onAsk = { vm.attachSnippet(it); overlay = null }) {
                    SelectTextScreen(page.text, onClose = { overlay = null })
                }
                is Overlay.Edit -> EditMessageScreen(
                    initial = page.message.text,
                    onClose = { overlay = null },
                    onSend = {
                        vm.edit(page.message, it)
                        overlay = null
                    },
                )
                is Overlay.Thinking -> com.apvcode.devx.ui.components.ProvideAskToolbar(onAsk = { vm.attachSnippet(it); overlay = null }) {
                    ThinkingScreen(page.message, streaming = vm.isStreaming(page.message), onClose = { overlay = null })
                }
                is Overlay.Uploaded -> com.apvcode.devx.ui.chat.UploadedFilesScreen(
                    files = vm.uploadedFiles(page.chatId),
                    previewOf = vm::previewOf,
                    onOpen = { overlay = Overlay.Photo(it) },
                    onClose = { overlay = null },
                )
                is Overlay.Steps -> ThinkingScreen(page.message, streaming = vm.isStreaming(page.message), onClose = { overlay = null })
                is Overlay.File -> com.apvcode.devx.ui.chat.ArtifactViewer(page.artifact, vm, onClose = { overlay = null })
                is Overlay.Sources -> com.apvcode.devx.ui.chat.SourcesScreen(
                    sources = page.message.sources.toList(),
                    onClose = { overlay = null },
                )
                is Overlay.Photo -> {
                    val a = page.attachment
                    val file = vm.fileOf(a)
                    when (a.kind) {
                        com.apvcode.devx.data.AttachmentKind.Video -> com.apvcode.devx.ui.screens.VideoViewer(file, onClose = { overlay = null })
                        com.apvcode.devx.data.AttachmentKind.Text -> com.apvcode.devx.ui.screens.TextFileViewer(file, a.name, onClose = { overlay = null })
                        com.apvcode.devx.data.AttachmentKind.Pdf -> com.apvcode.devx.ui.screens.PdfViewer(file, a.name, onClose = { overlay = null })
                        else -> PhotoViewer(file, onClose = { overlay = null })
                    }
                }
                null -> Unit
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = chatGone,
            enter = androidx.compose.animation.fadeIn(tween(220)),
            exit = androidx.compose.animation.fadeOut(tween(260)),
        ) {
            com.apvcode.devx.ui.chat.ChatGoneOverlay(onClose = { chatGone = false })
        }
    }
}
