package com.apvcode.devx.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.AnimatedContent
import com.apvcode.devx.ui.components.pressable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apvcode.devx.R
import com.apvcode.devx.model.ChatStatus
import com.apvcode.devx.model.ChatSummary
import com.apvcode.devx.ui.chat.ChatViewModel
import com.apvcode.devx.ui.components.DevxIcons
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.hazeSource
import com.apvcode.devx.ui.components.LocalGlass
import com.apvcode.devx.ui.components.glass
import com.apvcode.devx.ui.components.MenuRow
import com.apvcode.devx.ui.components.PopupMenu
import com.apvcode.devx.ui.components.PressState
import com.apvcode.devx.ui.components.menuOffset
import com.apvcode.devx.ui.components.pressAnchor
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType

/** What the user is being asked about after picking something in a long-press menu. */
private sealed interface Ask {
    data class RenameChat(val chat: ChatSummary) : Ask
    data class DeleteChat(val chat: ChatSummary) : Ask
    data class DeleteChats(val ids: List<String>) : Ask
    data class RenameProject(val name: String) : Ask
    data class DeleteProject(val name: String, val chats: Int, val notebook: Boolean = false) : Ask
    data object NewProject : Ask
}

/**
 * Floating panel (not glued to the screen edge). Top: the connected computer and search. Middle:
 * projects and chats; long-press a chat or a project for its menu. Bottom: a new-chat button and
 * round buttons for tasks, MCP and settings.
 */
@Composable
fun DrawerContent(
    vm: ChatViewModel,
    onNewChat: () -> Unit,
    onOpenProject: (String) -> Unit,
    onNewProject: () -> Unit,
    onNewNotebook: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenChat: (String) -> Unit,
    onSearch: () -> Unit,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    val width = (LocalConfiguration.current.screenWidthDp * 0.8f).dp

    var ask by remember { mutableStateOf<Ask?>(null) }
    // Picking chats: tap them to tick, then archive or delete the lot.
    var selecting by remember { mutableStateOf(false) }
    val picked = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    fun toggle(id: String) { if (id in picked) picked.remove(id) else picked.add(id) }
    fun stopSelecting() { selecting = false; picked.clear() }
    androidx.activity.compose.BackHandler(enabled = selecting) { stopSelecting() }
    var projectsOpen by remember { mutableStateOf(true) }
    var notebooksOpen by remember { mutableStateOf(true) }
    var chatsOpen by remember { mutableStateOf(true) }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    // Every project, even an empty one, with its chats (pinned first).
    val projectChats by remember {
        derivedStateOf {
            val all = vm.chats
            vm.projects.filter { vm.projectInfo[it]?.isNotebook != true }.associateWith { name -> all.filter { it.project == name }.sortedByDescending { it.pinned } }
        }
    }
    // The notebooks are a section of their own: they are made to learn and to work from sources.
    val notebookChats by remember {
        derivedStateOf {
            val all = vm.chats
            vm.projects.filter { vm.projectInfo[it]?.isNotebook == true }.associateWith { name -> all.filter { it.project == name }.sortedByDescending { it.pinned } }
        }
    }
    val plainChats by remember {
        derivedStateOf { vm.chats.filter { it.project == null }.sortedByDescending { it.pinned } }
    }
    val projectNames = vm.projects.toList()
    val chatRow: @Composable (ChatSummary, Modifier) -> Unit = { chat, m ->
        ChatItem(chat, projectNames, vm, { onOpenChat(chat.id) }, { ask = it }, selecting, chat.id in picked, { toggle(chat.id) }, { selecting = true; if (chat.id !in picked) picked.add(chat.id) }, m)
    }

    // The search and the computers on top and the buttons at the bottom float over the list: the chats scroll under them
    // and fade into the panel's colour, so that the buttons stand in the air and never move.
    val density = LocalDensity.current
    var topHeight by remember { mutableStateOf(0.dp) }
    var bottomHeight by remember { mutableStateOf(0.dp) }
    val panel = DevxColors.Drawer
    val haze = rememberHazeState()
    androidx.compose.runtime.CompositionLocalProvider(LocalGlass provides haze) {
    Box(
        modifier
            .width(width)
            .fillMaxHeight()
            .background(panel)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        LazyColumn(
            Modifier.fillMaxSize().hazeSource(haze).padding(horizontal = 16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = topHeight, bottom = bottomHeight),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
                if (vm.chats.isEmpty() && vm.projects.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.drawer_empty),
                        style = DevxType.Body,
                        color = DevxColors.TextSecondary,
                        modifier = Modifier.padding(start = 6.dp, top = 24.dp),
                    )
                }
            }
            item(key = "notebooks") {
                SectionHeader(
                    icon = DevxIcons.Notebook,
                    title = stringResource(R.string.drawer_notebooks),
                    count = notebookChats.size,
                    expanded = notebooksOpen,
                    onClick = { notebooksOpen = !notebooksOpen },
                    actionIcon = DevxIcons.FilePlus,
                    actionDescription = stringResource(R.string.cd_new_notebook),
                    onAction = onNewNotebook,
                )
            }
            item(key = "notebooks-body") {
                FoldList(notebooksOpen, notebookChats.entries.toList()) { (book, chats), rowMod ->
                    Column(rowMod) {
                        val open = collapsed[book] == false
                        ProjectHeader(
                            title = book, count = chats.size, expanded = open, onToggle = { collapsed[book] = !open },
                            info = vm.projectInfo[book], onOpen = { onOpenProject(book) },
                            onNewChat = { vm.newChat(book); onNavigate(Screen.Chat) }, onAsk = { ask = it },
                        )
                        FoldList(open, chats) { chat, m -> chatRow(chat, m) }
                    }
                }
            }
            item(key = "projects") {
                SectionHeader(
                    icon = DevxIcons.Folder,
                    title = stringResource(R.string.drawer_projects),
                    count = projectChats.size,
                    expanded = projectsOpen,
                    onClick = { projectsOpen = !projectsOpen },
                    actionIcon = DevxIcons.FolderPlus,
                    actionDescription = stringResource(R.string.cd_new_project),
                    onAction = onNewProject,
                )
            }
            item(key = "projects-body") {
                FoldList(projectsOpen, projectChats.entries.toList()) { (project, chats), rowMod ->
                    Column(rowMod) {
                        val open = collapsed[project] != true
                        ProjectHeader(
                            title = project, count = chats.size, expanded = open, onToggle = { collapsed[project] = open },
                            info = vm.projectInfo[project], onOpen = { onOpenProject(project) },
                            onNewChat = { vm.newChat(project); onNavigate(Screen.Chat) }, onAsk = { ask = it },
                        )
                        FoldList(open, chats) { chat, m -> chatRow(chat, m) }
                    }
                }
            }
            if (plainChats.isNotEmpty()) {
                item(key = "chats") {
                    SectionHeader(
                        icon = DevxIcons.ChatList,
                        title = stringResource(R.string.drawer_chats),
                        count = plainChats.size,
                        expanded = chatsOpen,
                        onClick = { chatsOpen = !chatsOpen },
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            if (plainChats.size <= 40) {
                item(key = "chats-body") { FoldList(chatsOpen, plainChats) { chat, m -> chatRow(chat, m) } }
            } else if (chatsOpen) {
                // A very long list stays lazy (only what is on the screen is built); it folds with the lazy list's own animation.
                items(plainChats, key = { it.id }) { chat -> chatRow(chat, Modifier.animateItem(fadeInSpec = DrawerFadeIn, placementSpec = DrawerPlacement, fadeOutSpec = DrawerFadeOut)) }
            }
        }

        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(0f to panel.copy(alpha = 0.88f), 0.55f to panel.copy(alpha = 0.55f), 1f to panel.copy(alpha = 0f)))
                .onSizeChanged { topHeight = with(density) { it.height.toDp() } }
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 22.dp),
        ) {
            SearchButton(onClick = onSearch)
            RemoteButton(onClick = { onNavigate(Screen.Computers) }, modifier = Modifier.padding(top = 8.dp))
            LibraryButton(vm.library.size, onClick = onOpenLibrary, modifier = Modifier.padding(top = 8.dp))
        }

        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(0f to panel.copy(alpha = 0f), 0.5f to panel.copy(alpha = 0.55f), 1f to panel.copy(alpha = 0.9f)))
                .onSizeChanged { bottomHeight = with(density) { it.height.toDp() } }
                .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 14.dp),
        ) {
            // The buttons give way to the selection bar (and come back): one slides up while the other sinks.
            AnimatedContent(
                targetState = selecting,
                transitionSpec = {
                    (slideInVertically(spring(dampingRatio = 0.78f, stiffness = 420f)) { it / 2 } + fadeIn(tween(220, delayMillis = 60))) togetherWith
                        (slideOutVertically(tween(200)) { it / 2 } + fadeOut(tween(140)))
                },
                label = "drawer-bottom",
            ) { picking ->
                if (picking) {
                    val everyId = (plainChats + projectChats.values.flatten() + notebookChats.values.flatten()).map { it.id }
                    SelectionBar(
                        count = picked.size,
                        total = everyId.size,
                        onCancel = { stopSelecting() },
                        onToggleAll = {
                            if (picked.size == everyId.size) picked.clear() else { picked.clear(); picked.addAll(everyId) }
                        },
                        onArchive = {
                            picked.toList().forEach(vm::archiveChat)
                            stopSelecting()
                        },
                        onDelete = { ask = Ask.DeleteChats(picked.toList()) },
                    )
                } else {
                    // The buttons shrink to the room there is: on a narrow screen (or a large display scale) the round ones get smaller,
                    // and when even that is not enough the new-chat button keeps only its icon. The word never breaks into letters.
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val tight = maxWidth < 300.dp
                        val tiny = maxWidth < 236.dp
                        val round = if (tiny) 40.dp else if (tight) 44.dp else 50.dp
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(if (tight) 6.dp else 8.dp),
                        ) {
                            Row(
                                Modifier
                                    .weight(1f)
                                    .heightIn(min = round)
                                    .pressable(scaleTo = 0.95f) { onNewChat() }
                                    .clip(CircleShape)
                                    .background(DevxColors.Accent)
                                    .padding(horizontal = if (tight) 12.dp else 18.dp, vertical = if (tight) 10.dp else 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            ) {
                                Icon(DevxIcons.ChatPlus, stringResource(R.string.menu_new_chat), tint = DevxColors.OnAccent, modifier = Modifier.size(22.dp))
                                if (!tiny) Text(stringResource(R.string.menu_new_chat), style = DevxType.Body, color = DevxColors.OnAccent, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Clip)
                            }
                            RoundAction(DevxIcons.Clock, stringResource(R.string.menu_tasks), size = round) { onNavigate(Screen.Tasks) }
                            RoundAction(DevxIcons.Mcp, stringResource(R.string.menu_mcp), size = round) { onNavigate(Screen.Mcp) }
                            RoundAction(DevxIcons.Settings, stringResource(R.string.menu_settings), size = round) { onNavigate(Screen.Settings) }
                        }
                    }
                }
            }
        }
    }
    }

    AskHost(ask, onDismiss = { ask = null }, vm = vm, onDeleted = { stopSelecting() })
}

// ---------- Rows ----------

/** The way to the library: every file ever attached, and the files the assistant made. */
@Composable
private fun LibraryButton(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().pressable(scaleTo = 0.97f, onClick = onClick).glass(CircleShape).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(DevxIcons.Paperclip, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(22.dp))
        Text(stringResource(R.string.library_title), style = DevxType.Body, color = DevxColors.TextSecondary, modifier = Modifier.weight(1f))
        if (count > 0) Text("$count", style = DevxType.Caption, color = DevxColors.TextDisabled)
    }
}

/** The way to the computers: the devx on a PC that this phone controls. A dot says if one is reachable right now. */
@Composable
private fun RemoteButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    // How many chats on the computers want a look; nothing is shown when none does.
    val waiting = com.apvcode.devx.remote.RemoteHub.store.items.sumOf { com.apvcode.devx.remote.RemoteHub.connection(it.hostId)?.attention ?: 0 }
    Row(
        modifier
            .fillMaxWidth()
            .pressable(scaleTo = 0.97f, onClick = onClick)
            .glass(CircleShape)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(DevxIcons.Desktop, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(22.dp))
        Text(stringResource(R.string.remote_title), style = DevxType.Body, color = DevxColors.TextSecondary, modifier = Modifier.weight(1f))
        androidx.compose.animation.AnimatedVisibility(visible = waiting > 0, enter = androidx.compose.animation.scaleIn(androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 500f)) + androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.scaleOut() + androidx.compose.animation.fadeOut()) {
            Text("$waiting", style = DevxType.Caption.copy(fontSize = 12.sp, lineHeight = 14.sp), color = DevxColors.OnAccent, modifier = Modifier.defaultMinSize(minWidth = 22.dp).clip(CircleShape).background(DevxColors.Accent).padding(horizontal = 7.dp, vertical = 3.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/** Looks like a search field, but opens the full-screen search instead of typing here. */
@Composable
private fun SearchButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .pressable(scaleTo = 0.97f, onClick = onClick)
            .glass(CircleShape)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(DevxIcons.ChatSearch, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(22.dp))
        Text(stringResource(R.string.drawer_search), style = DevxType.Body, color = DevxColors.TextSecondary)
    }
}

@Composable
private fun RoundAction(icon: ImageVector, description: String, badge: Int = 0, size: androidx.compose.ui.unit.Dp = 50.dp, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .pressable(onClick = onClick)
            .glass(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = DevxColors.TextPrimary, modifier = Modifier.size(22.dp))
        if (badge > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 4.dp)
                    .size(16.dp)
                    .background(DevxColors.Warning, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("$badge", style = DevxType.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp), color = DevxColors.OnAccent)
            }
        }
    }
}

@Composable
private fun Chevron(expanded: Boolean) {
    val angle by animateFloatAsState(if (expanded) 0f else -90f, androidx.compose.animation.core.spring(dampingRatio = 0.62f, stiffness = 380f), label = "chevron")
    Icon(
        Icons.Outlined.ExpandMore,
        null,
        tint = DevxColors.TextSecondary,
        modifier = Modifier.size(22.dp).rotate(angle),
    )
}

/**
 * A folding part of the list. Opening lets it grow from the top on a soft spring while its rows come down one after another, each
 * rising out of the line above it and fading in; closing is the same backwards: the last row goes first, then the space closes
 * smoothly. Nothing jumps: the rows below glide with the height.
 */
@Composable
private fun <T> FoldList(open: Boolean, items: List<T>, row: @Composable (T, Modifier) -> Unit) {
    androidx.compose.animation.AnimatedVisibility(
        visible = open,
        enter = androidx.compose.animation.expandVertically(spring(dampingRatio = 0.92f, stiffness = 240f), expandFrom = Alignment.Top) + fadeIn(tween(200)),
        exit = androidx.compose.animation.shrinkVertically(tween(380, delayMillis = 70, easing = androidx.compose.animation.core.FastOutSlowInEasing), shrinkTowards = Alignment.Top) + fadeOut(tween(220, delayMillis = 60)),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEachIndexed { i, item ->
                val a by transition.animateFloat(
                    transitionSpec = {
                        val appearing = targetState == androidx.compose.animation.EnterExitState.Visible
                        tween(320, delayMillis = if (appearing) (i * 38).coerceAtMost(300) else ((items.size - 1 - i) * 22).coerceAtMost(180), easing = androidx.compose.animation.core.FastOutSlowInEasing)
                    },
                    label = "fold-row",
                ) { if (it == androidx.compose.animation.EnterExitState.Visible) 1f else 0f }
                row(item, Modifier.graphicsLayer { alpha = a; translationY = (1f - a) * -16.dp.toPx(); val sc = 0.96f + 0.04f * a; scaleX = sc; scaleY = sc })
            }
        }
    }
}

/** How the rows of the list come and go when a section is folded or opened: they fade, and the rest glides to its new place. */
private val DrawerFadeIn = androidx.compose.animation.core.tween<Float>(320, delayMillis = 60, easing = androidx.compose.animation.core.FastOutSlowInEasing)
private val DrawerFadeOut = androidx.compose.animation.core.tween<Float>(180, easing = androidx.compose.animation.core.FastOutLinearInEasing)
private val DrawerPlacement = androidx.compose.animation.core.spring(dampingRatio = 0.88f, stiffness = 300f, visibilityThreshold = androidx.compose.ui.unit.IntOffset(1, 1))

/** Top-level section of the list ("Projects", "Chats"): can be folded away. */
@Composable
private fun SectionHeader(
    icon: ImageVector,
    title: String,
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    actionIcon: ImageVector? = null,
    actionDescription: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .pressable(scaleTo = 0.97f, haptic = false, onClick = onClick)
            .clip(RoundedCornerShape(16.dp))
            .padding(start = 6.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = DevxColors.TextPrimary, modifier = Modifier.size(22.dp))
        Text(title, style = DevxType.BodyStrong, color = DevxColors.TextPrimary, modifier = Modifier.weight(1f))
        Text("$count", style = DevxType.Caption, color = DevxColors.TextDisabled)
        if (actionIcon != null && onAction != null) {
            Box(
                Modifier.size(40.dp).pressable(onClick = onAction).clip(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(actionIcon, actionDescription, tint = DevxColors.TextPrimary, modifier = Modifier.size(22.dp))
            }
        }
        Chevron(expanded)
    }
}

/** One project: tap opens its page, the arrow folds its chats, long-press opens the menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    info: com.apvcode.devx.data.ProjectInfo?,
    onOpen: () -> Unit,
    onNewChat: () -> Unit,
    onAsk: (Ask) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val press = remember { PressState() }
    Box(modifier.pressAnchor(press)) {
        Row(
            Modifier
                .fillMaxWidth()
                .pressable(
                    scaleTo = 0.97f,
                    haptic = false,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                    onClick = onOpen,
                )
                .clip(RoundedCornerShape(14.dp))
                .padding(start = 10.dp, end = 0.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (info != null) {
                com.apvcode.devx.ui.screens.ProjectBadge(info.icon, info.color, 28.dp)
            } else {
                Icon(DevxIcons.Folder, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(20.dp))
            }
            Text(title, style = DevxType.Section, color = DevxColors.TextPrimary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("$count", style = DevxType.Caption, color = DevxColors.TextDisabled)
            Box(Modifier.size(40.dp).pressable(haptic = false, onClick = onToggle).clip(CircleShape), contentAlignment = Alignment.Center) {
                Chevron(expanded)
            }
        }
        PopupMenu(expanded = menu, onDismiss = { menu = false }, offset = press.menuOffset()) {
            MenuRow(DevxIcons.ChatPlus, stringResource(R.string.project_new_chat)) {
                menu = false
                onNewChat()
            }
            MenuRow(DevxIcons.FolderOff, stringResource(R.string.menu_delete), danger = true) {
                menu = false
                onAsk(Ask.DeleteProject(title, count, info?.isNotebook == true))
            }
        }
    }
}

/** One chat: tap opens it, long-press opens the menu. */
@Composable
private fun ChatItem(
    chat: ChatSummary,
    projects: List<String>,
    vm: ChatViewModel,
    onOpen: () -> Unit,
    onAsk: (Ask) -> Unit,
    selecting: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onStartSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val press = remember { PressState() }
    Box(modifier.pressAnchor(press)) {
        ChatCard(
            chat,
            current = vm.currentId == chat.id,
            selecting = selecting,
            selected = selected,
            onClick = if (selecting) onToggle else onOpen,
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                if (selecting) onToggle() else menu = true
            },
        )
        // The submenu "Add to project" swaps the content of the same popup.
        var page by remember(menu) { mutableIntStateOf(0) }
        PopupMenu(expanded = menu, onDismiss = { menu = false }, offset = press.menuOffset()) {
          // Going deeper slides the old rows out to the left and the new ones in from the right
          // (back is the mirror image); the card grows or shrinks to the new height on a spring.
          AnimatedContent(
            targetState = page,
            transitionSpec = {
                val deeper = targetState > initialState
                val shift = { full: Int -> if (deeper) full / 3 else -full / 3 }
                (slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = 520f), shift) + fadeIn(tween(200, delayMillis = 60))) togetherWith
                    (slideOutHorizontally(tween(220)) { -shift(it) } + fadeOut(tween(120))) using
                    SizeTransform(clip = true) { _, _ -> spring(dampingRatio = 0.88f, stiffness = 500f) }
            },
            label = "chat-menu-page",
          ) { shown ->
            Column {
            if (shown == 0) {
                MenuRow(DevxIcons.ListCheck, stringResource(R.string.menu_select)) {
                    menu = false
                    onStartSelect()
                }
                MenuRow(DevxIcons.Pin, stringResource(if (chat.pinned) R.string.menu_unpin else R.string.menu_pin)) {
                    vm.togglePin(chat.id)
                    menu = false
                }
                MenuRow(DevxIcons.Pencil, stringResource(R.string.menu_rename)) {
                    menu = false
                    onAsk(Ask.RenameChat(chat))
                }
                MenuRow(DevxIcons.FolderPlus, stringResource(R.string.menu_add_to_project), trailing = DevxIcons.ChevronRight) {
                    page = 1
                }
                MenuRow(DevxIcons.Notebook, stringResource(R.string.menu_add_to_notebook), trailing = DevxIcons.ChevronRight) {
                    page = 3
                }
                MenuRow(DevxIcons.Archive, stringResource(R.string.menu_archive)) {
                    vm.archiveChat(chat.id)
                    menu = false
                }
                MenuRow(DevxIcons.ChatOff, stringResource(R.string.menu_delete), danger = true) {
                    menu = false
                    onAsk(Ask.DeleteChat(chat))
                }
            } else {
                MenuRow(DevxIcons.ArrowLeft, stringResource(if (shown == 3) R.string.menu_add_to_notebook else R.string.menu_add_to_project)) { page = 0 }
                HorizontalDivider(color = DevxColors.Outline, thickness = 0.5.dp)
                projects.filter { (vm.projectInfo[it]?.isNotebook == true) == (shown == 3) }.forEach { name ->
                    MenuRow(
                        if (shown == 3) DevxIcons.Notebook else DevxIcons.Folder,
                        name,
                        trailing = if (chat.project == name) DevxIcons.Check else null,
                    ) {
                        vm.moveToProject(chat.id, name)
                        menu = false
                    }
                }
                MenuRow(
                    DevxIcons.ChatList,
                    stringResource(R.string.menu_no_project),
                    trailing = if (chat.project == null) DevxIcons.Check else null,
                ) {
                    vm.moveToProject(chat.id, null)
                    menu = false
                }
            }
            }
          }
        }
    }
}

/** One chat as its own card: title, one line of preview, time, and an icon for its status. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatCard(chat: ChatSummary, current: Boolean, selecting: Boolean, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    // The chat that is open now stands out: a little lighter, with a quiet edge and a bright bar on the left, and its name rolls
    // past if it is too long to be seen whole.
    val lift by androidx.compose.animation.animateColorAsState(
        when {
            selected -> androidx.compose.ui.graphics.Color.White.copy(alpha = 0.10f).compositeOver(DevxColors.Card)
            current -> androidx.compose.ui.graphics.Color.White.copy(alpha = 0.06f).compositeOver(DevxColors.Card)
            else -> DevxColors.Card
        },
        tween(260), label = "card-color",
    )
    val border by androidx.compose.animation.animateColorAsState(
        when {
            selected -> DevxColors.Accent.copy(alpha = 0.85f)
            current -> androidx.compose.ui.graphics.Color.White.copy(alpha = 0.20f)
            else -> androidx.compose.ui.graphics.Color.Transparent
        },
        tween(260), label = "card-border",
    )
    val barHeight by androidx.compose.animation.core.animateDpAsState(if (current && !selecting) 22.dp else 0.dp, spring(dampingRatio = 0.7f, stiffness = 420f), label = "here-bar")
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(scaleTo = 0.975f, haptic = false, onLongClick = onLongClick, onClick = onClick)
            .clip(shape)
            .background(lift)
            .border(1.5.dp, border, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The mark of a pickable chat slides in from the left and pushes the words aside.
        androidx.compose.animation.AnimatedVisibility(
            visible = selecting,
            enter = androidx.compose.animation.expandHorizontally(spring(dampingRatio = 0.75f, stiffness = 420f), expandFrom = Alignment.Start) +
                androidx.compose.animation.scaleIn(spring(dampingRatio = 0.5f, stiffness = 500f), initialScale = 0.3f) + fadeIn(tween(160)),
            exit = androidx.compose.animation.shrinkHorizontally(tween(220), shrinkTowards = Alignment.Start) + androidx.compose.animation.scaleOut(tween(180), targetScale = 0.3f) + fadeOut(tween(140)),
        ) {
            SelectDot(selected, Modifier.padding(end = 12.dp))
        }
        // The bar that says "you are here".
        Box(Modifier.padding(end = if (barHeight > 0.dp) 10.dp else 0.dp).size(width = if (barHeight > 0.dp) 4.dp else 0.dp, height = barHeight).clip(CircleShape).background(DevxColors.Accent))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The name has its own place at the start of the line and never moves for anything: the marks of the chat stand after it.
                com.apvcode.devx.ui.components.AnimatedChatTitle(
                    title = chat.title,
                    naming = chat.naming,
                    style = DevxType.BodyStrong,
                    color = DevxColors.TextPrimary,
                    mark = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                when (chat.status) {
                    ChatStatus.Replied -> Icon(DevxIcons.ChatAlert, null, tint = DevxColors.TextPrimary, modifier = Modifier.size(18.dp))
                    ChatStatus.Waiting -> Icon(DevxIcons.ChatQuestion, null, tint = DevxColors.Warning, modifier = Modifier.size(18.dp))
                    else -> Unit
                }
                if (chat.locked) {
                    Icon(DevxIcons.Lock, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(15.dp))
                }
                if (chat.pinned) {
                    Icon(DevxIcons.Pin, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(15.dp))
                }
                Text(chat.time, style = DevxType.Caption, color = DevxColors.TextDisabled)
                // An answer is being written: a small green light that breathes, at the end of the line.
                if (chat.status == ChatStatus.Working) {
                    val glow by androidx.compose.animation.core.rememberInfiniteTransition(label = "working").animateFloat(
                        0.35f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "glow",
                    )
                    Box(Modifier.size(7.dp).graphicsLayer { alpha = glow }.background(DevxColors.Success, CircleShape))
                }
            }
            Text(chat.preview, style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 1)
        }
    }
}

// ---------- Dialogs ----------

@Composable
private fun AskHost(ask: Ask?, onDismiss: () -> Unit, vm: ChatViewModel, onDeleted: () -> Unit = {}) {
    when (ask) {
        null -> Unit
        Ask.NewProject -> RenameDialog(stringResource(R.string.dialog_new_project), "", onDismiss) { vm.createProject(it) }
        is Ask.RenameChat -> RenameDialog(stringResource(R.string.dialog_rename_chat), ask.chat.title, onDismiss) {
            vm.renameChat(ask.chat.id, it)
        }
        is Ask.RenameProject -> RenameDialog(stringResource(R.string.dialog_rename_project), ask.name, onDismiss) {
            vm.renameProject(ask.name, it)
        }
        is Ask.DeleteChat -> ConfirmDelete(
            icon = DevxIcons.ChatOff,
            title = stringResource(R.string.dialog_delete_chat),
            text = ask.chat.title,
            onDismiss = onDismiss,
        ) { vm.deleteChat(ask.chat.id) }
        is Ask.DeleteChats -> ConfirmDelete(
            icon = DevxIcons.ChatOff,
            title = stringResource(R.string.dialog_delete_chats),
            text = stringResource(R.string.dialog_delete_chats_text, ask.ids.size),
            onDismiss = onDismiss,
        ) { ask.ids.forEach(vm::deleteChat); onDeleted() }
        is Ask.DeleteProject -> ConfirmDelete(
            icon = DevxIcons.FolderOff,
            title = stringResource(if (ask.notebook) R.string.dialog_delete_notebook else R.string.dialog_delete_project),
            text = ask.name + "\n" + stringResource(if (ask.notebook) R.string.dialog_delete_notebook_text else R.string.dialog_delete_project_text, ask.chats),
            onDismiss = onDismiss,
        ) { vm.deleteProject(ask.name) }
    }
}

@Composable
private fun RenameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DevxColors.Card,
        shape = RoundedCornerShape(28.dp),
        titleContentColor = DevxColors.TextPrimary,
        title = { Text(title, style = DevxType.Title) },
        text = {
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = DevxType.Body.copy(color = DevxColors.TextPrimary),
                cursorBrush = SolidColor(DevxColors.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, DevxColors.Outline, RoundedCornerShape(18.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(value.text); onDismiss() }) {
                Text(stringResource(R.string.dialog_save), color = DevxColors.TextPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel), color = DevxColors.TextSecondary)
            }
        },
    )
}

@Composable
private fun ConfirmDelete(icon: ImageVector, title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DevxColors.Card,
        shape = RoundedCornerShape(28.dp),
        iconContentColor = DevxColors.Danger,
        titleContentColor = DevxColors.TextPrimary,
        textContentColor = DevxColors.TextSecondary,
        icon = { Icon(icon, null, modifier = Modifier.size(34.dp)) },
        title = { Text(title, style = DevxType.Title) },
        text = { Text(text, style = DevxType.Body) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(stringResource(R.string.menu_delete), color = DevxColors.Danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel), color = DevxColors.TextSecondary)
            }
        },
    )
}
