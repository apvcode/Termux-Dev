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
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import kotlinx.coroutines.flow.collectLatest
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
import com.apvcode.devx.data.realMime
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
import androidx.compose.foundation.layout.padding
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.apvcode.devx.data.Artifact
import com.apvcode.devx.data.ArtifactStore
import com.apvcode.devx.data.AttachmentKind
import com.apvcode.devx.data.PendingAsk
import com.apvcode.devx.data.PendingShare
import com.apvcode.devx.data.PendingStudy
import com.apvcode.devx.remote.PendingPairing
import com.apvcode.devx.remote.PendingRemoteChat
import com.apvcode.devx.remote.RemoteHub
import com.apvcode.devx.remote.RemoteLink
import com.apvcode.devx.ui.assistant.VoiceSettingsScreen
import com.apvcode.devx.ui.chat.ArtifactViewer
import com.apvcode.devx.ui.chat.ChatGoneOverlay
import com.apvcode.devx.ui.chat.UploadedFilesScreen
import com.apvcode.devx.ui.components.ProvideAskToolbar
import com.apvcode.devx.ui.components.ShareTargetSheet
import com.apvcode.devx.ui.lock.AppLockOverlay
import com.apvcode.devx.ui.lock.AppLockScreen
import com.apvcode.devx.ui.markdown.Citations
import com.apvcode.devx.ui.screens.AssistantScreen
import com.apvcode.devx.ui.screens.CardsScreen
import com.apvcode.devx.ui.screens.DocumentViewer
import com.apvcode.devx.ui.screens.LibraryScreen
import com.apvcode.devx.ui.screens.MemoryNotesScreen
import com.apvcode.devx.ui.screens.MemoryScreen
import com.apvcode.devx.ui.screens.MemorySummaryScreen
import com.apvcode.devx.ui.screens.NewCardDialog
import com.apvcode.devx.ui.screens.NotebookNewScreen
import com.apvcode.devx.ui.screens.NotebookPage
import com.apvcode.devx.ui.screens.NotebookSettingsScreen
import com.apvcode.devx.ui.screens.PersonaScreen
import com.apvcode.devx.ui.screens.ProjectEditScreen
import com.apvcode.devx.ui.screens.RemindersScreen
import com.apvcode.devx.ui.screens.SkillEditScreen
import com.apvcode.devx.ui.screens.SkillsScreen
import com.apvcode.devx.ui.screens.TaskEditScreen
import com.apvcode.devx.ui.screens.TrashScreen
import com.apvcode.devx.ui.screens.UndoBar
import com.apvcode.devx.ui.screens.VideoViewer

enum class Screen { Chat, Tasks, Computers, AddConnection, Computer, RemoteChat, Mcp, Settings, Providers, ProviderEdit, Notifications, About, Memory, MemorySummary, MemoryNotes, Project, ProjectEdit, ProjectKnowledge, ProjectSummary, NotebookNew, Sources, NotebookSettings, Library, Skills, SkillEdit, Assistant, VoiceAssistant, TaskEdit, Reminders, AppLock, Persona, Cards, Trash, Appearance, Themes, Data, Graph }

private fun parentOf(screen: Screen): Screen = when (screen) {
    Screen.AddConnection, Screen.Computer -> Screen.Computers
    Screen.TaskEdit, Screen.Reminders -> Screen.Tasks
    Screen.RemoteChat -> Screen.Computer
    Screen.ProviderEdit -> Screen.Providers
    Screen.Providers, Screen.Notifications, Screen.About, Screen.Memory, Screen.Skills, Screen.Assistant, Screen.VoiceAssistant, Screen.Persona, Screen.Appearance, Screen.Data -> Screen.Settings
    Screen.AppLock, Screen.Trash -> Screen.Data
    Screen.Themes -> Screen.Appearance
    Screen.MemorySummary, Screen.MemoryNotes -> Screen.Memory
    Screen.ProjectEdit, Screen.ProjectKnowledge, Screen.ProjectSummary, Screen.Sources, Screen.NotebookSettings, Screen.Cards, Screen.Graph -> Screen.Project
    Screen.SkillEdit -> Screen.Skills
    else -> Screen.Chat
}

/** Where the system "back" leads: [screen] (null: the app is left), and whether a new chat is opened on the way. */
internal class BackStep(val screen: Screen?, val newChat: Boolean = false)

/**
 * The system "back" from [screen]. [inSpace]: the open chat belongs to a notebook or a project, so it is reached from that page and
 * "back" returns there (it used to close the app). Leaving the page opens a new chat: otherwise "back" would go from the page to
 * the chat and from the chat to the page, without end. [creatingProject]: the page for a new project, which has no page to return to.
 */
internal fun backStep(screen: Screen, inSpace: Boolean, creatingProject: Boolean = false): BackStep = when {
    screen == Screen.Chat -> if (inSpace) BackStep(Screen.Project) else BackStep(null)
    screen == Screen.ProjectEdit && creatingProject -> BackStep(Screen.Chat)
    screen == Screen.Project -> BackStep(Screen.Chat, newChat = inSpace)
    else -> BackStep(parentOf(screen))
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
    data class File(val artifact: Artifact) : Overlay
    data class Viewer(val file: java.io.File, val name: String, val mime: String, val url: String = "", val page: Int = 0) : Overlay
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
        // Overviews that went out of date while the app was shut are renewed quietly.
        vm.refreshStaleSummaries()
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
                                if (blur > 0.5f) renderEffect = BlurEffect(blur, blur, TileMode.Decal)
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
    vm.cardDraft?.let { (book, text) -> NewCardDialog(book, text, vm, onDismiss = { vm.cardDraft = null }) }
    UndoBar(vm, Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp))
    // The lock of the app covers everything, even the opening animation.
    AppLockOverlay(ready = !splash)
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
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
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
    var shareChoice by remember { mutableStateOf<PendingShare.Item?>(null) }
    // "Share" from another app: a new chat opens with what was shared.
    LaunchedEffect(PendingShare.item) {
        val shared = PendingShare.item ?: return@LaunchedEffect
        PendingShare.item = null
        // With notebooks the person chooses where it goes; without any it is a new chat, as it always was.
        if (vm.projectInfo.values.any { it.isNotebook }) { shareChoice = shared; overlay = null; drawerState.close() }
        else { vm.startFromShare(shared.text, shared.uris); screen = Screen.Chat; overlay = null; drawerState.close() }
    }
    shareChoice?.let { shared ->
        ShareTargetSheet(
            text = shared.text,
            files = shared.uris.size,
            notebooks = vm.projects.mapNotNull { n -> vm.projectInfo[n]?.takeIf { it.isNotebook }?.let { n to it } },
            onNewChat = { shareChoice = null; vm.startFromShare(shared.text, shared.uris); screen = Screen.Chat },
            onNotebook = { name ->
                shareChoice = null
                vm.addShared(name, shared.text, shared.uris)
                projectName = name; screen = Screen.Sources
            },
            onDismiss = { shareChoice = null },
        )
    }
    // The cards widget: the review starts in the notebook that had cards due.
    LaunchedEffect(PendingStudy.project) {
        val name = PendingStudy.project ?: return@LaunchedEffect
        PendingStudy.project = null
        overlay = null; drawerState.close()
        if (name in vm.projectInfo) { projectName = name; screen = Screen.Cards }
    }
    // "Ask devx" from the tile or the widget: a fresh chat, and the keyboard up on it.
    LaunchedEffect(PendingAsk.request) {
        if (!PendingAsk.request) return@LaunchedEffect
        PendingAsk.request = false
        vm.newChat()
        screen = Screen.Chat; overlay = null; drawerState.close()
        PendingAsk.focus = true
    }
    // The same from the widgets: a chat that starts listening, a temporary one, the computers.
    LaunchedEffect(PendingAsk.voice) {
        if (!PendingAsk.voice) return@LaunchedEffect
        vm.newChat()
        screen = Screen.Chat; overlay = null; drawerState.close()
        // The chat screen picks the flag up and opens the microphone once it is on the screen.
    }
    LaunchedEffect(PendingAsk.temporary) {
        if (!PendingAsk.temporary) return@LaunchedEffect
        PendingAsk.temporary = false
        vm.newChat()
        if (!vm.isTemporary) vm.toggleTemporary()
        screen = Screen.Chat; overlay = null; drawerState.close()
        PendingAsk.focus = true
    }
    LaunchedEffect(PendingAsk.computers) {
        if (!PendingAsk.computers) return@LaunchedEffect
        PendingAsk.computers = false
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
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.relockAll() }
    LaunchedEffect(com.apvcode.devx.data.AppLock.panic) { if (com.apvcode.devx.data.AppLock.panic > 0) vm.relockAll() }
    // A notification of the agent (a permission, an answer) asked for its chat.
    LaunchedEffect(PendingRemoteChat.target) {
        val t = PendingRemoteChat.target ?: return@LaunchedEffect
        PendingRemoteChat.target = null
        remoteHost = t.first; remoteSession = t.second; remoteDraft = null; screen = Screen.RemoteChat; overlay = null; drawerState.close()
    }
    // A pairing code came as a link: straight to the pairing screen.
    LaunchedEffect(PendingPairing.code) {
        if (PendingPairing.code != null) { screen = Screen.AddConnection; overlay = null; drawerState.close() }
    }
    // Back in the app: the computers are tried at once, without waiting for the next round.
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        RemoteLink.host?.let { h -> RemoteHub.connection(h)?.let { it.start(); it.nudge() } }
    }
    // The connection to a computer (and its notification) exists only while its list of chats or one of its chats is open.
    val remoteContext = LocalContext.current
    LaunchedEffect(screen, remoteHost) {
        val host = remoteHost
        if ((screen == Screen.Computer || screen == Screen.RemoteChat) && host != null) {
            RemoteLink.connect(remoteContext, host)
        } else {
            RemoteLink.disconnect(remoteContext)
            // Out of the whole remote section: no computer is tried any more.
            if (screen != Screen.Computers && screen != Screen.AddConnection) RemoteHub.stopAll()
        }
    }
    // "Disconnect" in the notification pressed while the app is open on the computer: back to the list of computers.
    LaunchedEffect(RemoteLink.dropped) {
        if (RemoteLink.dropped > 0 && (screen == Screen.Computer || screen == Screen.RemoteChat)) screen = Screen.Computers
    }

    // The notebook or project the open chat belongs to (see backStep).
    val chatSpace = (vm.current?.takeIf { !it.temporary }?.project ?: vm.draftProject.takeIf { vm.current == null })?.takeIf { vm.projectInfo[it] != null }
    val leaveSpacePage = { if (chatSpace != null) vm.newChat(); screen = Screen.Chat }

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    val back = backStep(screen, chatSpace != null, editingProject == null)
    BackHandler(enabled = back.screen != null && !drawerState.isOpen) {
        if (back.newChat) vm.newChat()
        if (screen == Screen.Chat) projectName = chatSpace
        screen = back.screen ?: Screen.Chat
    }
    BackHandler(enabled = searchOpen) { searchOpen = false }
    BackHandler(enabled = overlay != null) { overlay = null }

    // A citation in an answer ([[file, p. 3]]) opens that source; every other link goes where it always went.
    val defaultUri = LocalUriHandler.current
    val appContext = LocalContext.current
    val citeHandler = remember(defaultUri) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val cited = Citations.decode(uri) ?: return defaultUri.openUri(uri)
                val a = vm.findSource(cited.first)
                if (a == null) Toast.makeText(appContext, appContext.getString(com.apvcode.devx.R.string.cite_missing, cited.first), Toast.LENGTH_SHORT).show()
                else overlay = Overlay.Viewer(vm.attachmentFile(a), a.name, if (a.original.isNotEmpty()) ArtifactStore.mimeOf(a.name) else a.mime, a.url, cited.second)
            }
        }
    }
    CompositionLocalProvider(LocalUriHandler provides citeHandler) {
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
                    onNewNotebook = {
                        screen = Screen.NotebookNew
                        scope.launch { drawerState.close() }
                    },
                    onOpenLibrary = {
                        screen = Screen.Library
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
            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    // Going deeper the new screen comes up from slightly smaller; going back the old one sinks away. Never a hard cut.
                    val deeper = targetState != Screen.Chat && (initialState == Screen.Chat || parentOf(targetState) == initialState)
                    (androidx.compose.animation.fadeIn(tween(300, delayMillis = 40)) + androidx.compose.animation.scaleIn(tween(360, easing = FastOutSlowInEasing), initialScale = if (deeper) 0.96f else 1.03f)) togetherWith
                        (androidx.compose.animation.fadeOut(tween(200)) + androidx.compose.animation.scaleOut(tween(300, easing = FastOutSlowInEasing), targetScale = if (deeper) 1.03f else 0.96f))
                },
                label = "screen",
                modifier = Modifier.fillMaxSize().background(DevxColors.Bg),
            ) { current ->
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
                        onOpenProject = { name -> projectName = name; screen = Screen.Project },
                    )
                    Screen.Tasks -> TasksScreen(vm, onBack = { screen = Screen.Chat }, onEdit = { id -> editingTask = id; screen = Screen.TaskEdit }, onReminders = { screen = Screen.Reminders })
                    Screen.Reminders -> RemindersScreen(onBack = { screen = Screen.Tasks })
                    Screen.TaskEdit -> TaskEditScreen(vm, editingTask, onBack = { screen = Screen.Tasks }, onOpenChat = { id -> vm.openChat(id); screen = Screen.Chat })
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
                        onPersona = { screen = Screen.Persona },
                        onData = { screen = Screen.Data },
                        onAppearance = { screen = Screen.Appearance },
                    )
                    Screen.Persona -> PersonaScreen(onBack = { screen = Screen.Settings })
                    Screen.Data -> com.apvcode.devx.ui.screens.DataScreen(vm, onBack = { screen = Screen.Settings }, onAppLock = { screen = Screen.AppLock }, onTrash = { screen = Screen.Trash })
                    Screen.Appearance -> com.apvcode.devx.ui.screens.AppearanceScreen(onBack = { screen = Screen.Settings }, onOthers = { screen = Screen.Themes })
                    Screen.Themes -> com.apvcode.devx.ui.screens.ThemesScreen(onBack = { screen = Screen.Appearance })
                    Screen.Trash -> TrashScreen(vm, onBack = { screen = Screen.Data })
                    Screen.AppLock -> AppLockScreen(onBack = { screen = Screen.Data })
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
                    Screen.Memory -> MemoryScreen(
                        vm,
                        onBack = { screen = Screen.Settings },
                        onSummary = { screen = Screen.MemorySummary },
                        onNotes = { screen = Screen.MemoryNotes },
                    )
                    Screen.Project -> if (vm.projectInfo[projectName.orEmpty()] != null) NotebookPage(
                        vm, projectName.orEmpty(),
                        onBack = leaveSpacePage,
                        onSources = { screen = Screen.Sources },
                        onSettings = { screen = Screen.NotebookSettings },
                        onKnowledge = { screen = Screen.ProjectKnowledge },
                        onSummary = { screen = Screen.ProjectSummary },
                        onEdit = { editingProject = projectName; screen = Screen.ProjectEdit },
                        onOpenChat = { id -> vm.openChat(id); screen = Screen.Chat },
                        onNewChat = { vm.newChat(projectName); screen = Screen.Chat },
                        onCards = { screen = Screen.Cards },
                        onGraph = { screen = Screen.Graph },
                        onAsk = { text -> vm.newChat(projectName); screen = Screen.Chat; vm.send(text) },
                    ) else LaunchedEffect(Unit) { screen = Screen.Chat }
                    Screen.Graph -> com.apvcode.devx.ui.screens.GraphScreen(
                        vm, projectName.orEmpty(),
                        onClose = { screen = Screen.Project },
                        onAsk = { text -> vm.newChat(projectName); screen = Screen.Chat; vm.send(text) },
                    )
                    Screen.Cards -> CardsScreen(
                        vm, projectName.orEmpty(),
                        onClose = { screen = Screen.Project },
                        onAsk = { text -> vm.newChat(projectName); screen = Screen.Chat; vm.send(text) },
                    )
                    Screen.NotebookNew -> NotebookNewScreen(
                        vm,
                        onClose = { screen = Screen.Chat },
                        onCreated = { name, study -> projectName = name; screen = if (study) Screen.Project else Screen.Project },
                    )
                    Screen.Sources -> com.apvcode.devx.ui.screens.SourcesScreen(
                        vm, projectName.orEmpty(), onBack = { screen = Screen.Project },
                        onOpen = { a -> overlay = Overlay.Viewer(vm.attachmentFile(a), a.name, if (a.original.isNotEmpty()) ArtifactStore.mimeOf(a.name) else a.mime, a.url) },
                    )
                    Screen.NotebookSettings -> NotebookSettingsScreen(vm, projectName.orEmpty(), onBack = { screen = Screen.Project }, onSources = { screen = Screen.Sources })
                    Screen.Library -> LibraryScreen(
                        vm, onBack = { screen = Screen.Chat },
                        onOpen = { e ->
                            val f = e.attachment?.let { vm.attachmentFile(it) } ?: e.artifact?.let { vm.artifactFile(it) }
                            if (f != null) overlay = Overlay.Viewer(f, e.name, e.realMime(), e.url)
                        },
                        onOpenUse = { u -> vm.openChatAt(u.chatId, u.messageId); screen = Screen.Chat },
                        onAttached = { vm.newChat(); screen = Screen.Chat },
                    )
                    Screen.ProjectEdit -> ProjectEditScreen(
                        vm,
                        editingProject,
                        onClose = { screen = if (editingProject == null) Screen.Chat else Screen.Project },
                        onDone = { name -> projectName = name; screen = Screen.Project },
                    )
                    Screen.ProjectKnowledge -> MemoryNotesScreen(vm, onBack = { screen = Screen.Project }, project = projectName)
                    Screen.ProjectSummary -> MemorySummaryScreen(vm, onBack = { screen = Screen.Project }, project = projectName)
                    Screen.VoiceAssistant -> VoiceSettingsScreen(vm, onBack = { screen = Screen.Settings })
                    Screen.Assistant -> AssistantScreen(vm, onBack = { screen = Screen.Settings })
                    Screen.Skills -> SkillsScreen(vm, onBack = { screen = Screen.Settings }, onWrite = { screen = Screen.SkillEdit })
                    Screen.SkillEdit -> SkillEditScreen(vm, onClose = { screen = Screen.Skills }, onDone = { screen = Screen.Skills })
                    Screen.MemorySummary -> MemorySummaryScreen(vm, onBack = { screen = Screen.Memory })
                    Screen.MemoryNotes -> MemoryNotesScreen(vm, onBack = { screen = Screen.Memory })
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
                searchSpaces = vm::searchSpaces,
                searchInside = vm::searchInside,
                searchFiles = vm::searchFiles,
                onOpenSpace = { name -> projectName = name; screen = Screen.Project; searchOpen = false },
                onOpenMemory = { screen = Screen.Memory; searchOpen = false },
                onOpenFile = { e ->
                    val f = e.attachment?.let { vm.attachmentFile(it) } ?: e.artifact?.let { vm.artifactFile(it) }
                    if (f != null) { overlay = Overlay.Viewer(f, e.name, e.realMime(), e.url); searchOpen = false }
                },
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
                is Overlay.SelectText -> {
                    // In a study notebook the picked words can become a card.
                    val notebook = vm.current?.project?.takeIf { vm.projectInfo[it]?.isStudy == true }
                    ProvideAskToolbar(
                        onAsk = { vm.attachSnippet(it); overlay = null },
                        extraLabel = if (notebook != null) androidx.compose.ui.res.stringResource(com.apvcode.devx.R.string.card_from_selection) else null,
                        onExtra = { text -> notebook?.let { vm.cardDraft = it to text.trim().take(1200) }; overlay = null },
                    ) {
                        SelectTextScreen(page.text, onClose = { overlay = null })
                    }
                }
                is Overlay.Edit -> EditMessageScreen(
                    initial = page.message.text,
                    onClose = { overlay = null },
                    onSend = {
                        vm.edit(page.message, it)
                        overlay = null
                    },
                )
                is Overlay.Thinking -> ProvideAskToolbar(onAsk = { vm.attachSnippet(it); overlay = null }) {
                    ThinkingScreen(page.message, streaming = vm.isStreaming(page.message), onClose = { overlay = null })
                }
                is Overlay.Uploaded -> UploadedFilesScreen(
                    files = vm.uploadedFiles(page.chatId),
                    previewOf = vm::previewOf,
                    onOpen = { overlay = Overlay.Photo(it) },
                    onClose = { overlay = null },
                )
                is Overlay.Steps -> ThinkingScreen(page.message, streaming = vm.isStreaming(page.message), onClose = { overlay = null })
                is Overlay.File -> ArtifactViewer(page.artifact, vm, onClose = { overlay = null })
                is Overlay.Viewer -> DocumentViewer(page.file, page.name, page.mime, onClose = { overlay = null }, url = page.url, page = page.page)
                is Overlay.Sources -> com.apvcode.devx.ui.chat.SourcesScreen(
                    sources = page.message.sources.toList(),
                    onClose = { overlay = null },
                )
                is Overlay.Photo -> {
                    val a = page.attachment
                    val file = if (a.kind == AttachmentKind.Image || a.kind == AttachmentKind.Video) vm.fileOf(a) else vm.attachmentFile(a)
                    when (a.kind) {
                        AttachmentKind.Video -> VideoViewer(file, onClose = { overlay = null })
                        AttachmentKind.Image -> PhotoViewer(file, onClose = { overlay = null })
                        // Documents (PDF, Word, slides, tables, text, pages from the web) open in the document viewer, with its menu.
                        else -> DocumentViewer(file, a.name, if (a.original.isNotEmpty()) ArtifactStore.mimeOf(a.name) else a.mime, onClose = { overlay = null }, url = a.url)
                    }
                }
                null -> Unit
            }
        }

        AnimatedVisibility(
            visible = chatGone,
            enter = androidx.compose.animation.fadeIn(tween(220)),
            exit = androidx.compose.animation.fadeOut(tween(260)),
        ) {
            ChatGoneOverlay(onClose = { chatGone = false })
        }
    }
    }
}
