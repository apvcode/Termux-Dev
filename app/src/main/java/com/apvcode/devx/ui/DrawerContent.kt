package com.apvcode.devx.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.navigationBarsPadding
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
    data class RenameProject(val name: String) : Ask
    data class DeleteProject(val name: String, val chats: Int) : Ask
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
    onOpenChat: (String) -> Unit,
    onSearch: () -> Unit,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    val width = (LocalConfiguration.current.screenWidthDp * 0.8f).dp
    val running = 0 // running background tasks: comes with the computer connection

    var ask by remember { mutableStateOf<Ask?>(null) }
    var projectsOpen by remember { mutableStateOf(true) }
    var chatsOpen by remember { mutableStateOf(true) }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    // Every project, even an empty one, with its chats (pinned first).
    val projectChats by remember {
        derivedStateOf {
            val all = vm.chats
            vm.projects.associateWith { name -> all.filter { it.project == name }.sortedByDescending { it.pinned } }
        }
    }
    val plainChats by remember {
        derivedStateOf { vm.chats.filter { it.project == null }.sortedByDescending { it.pinned } }
    }
    val projectNames = vm.projects.toList()

    Column(
        modifier
            .width(width)
            .fillMaxHeight()
            .background(DevxColors.Drawer)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        run {
            SearchButton(onClick = onSearch)
            RemoteButton(onClick = { onNavigate(Screen.Computers) }, modifier = Modifier.padding(top = 8.dp))

            LazyColumn(
                Modifier.weight(1f).padding(top = 14.dp),
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
                if (projectsOpen) {
                    projectChats.forEach { (project, chats) ->
                        val open = collapsed[project] != true
                        item(key = "p-$project") {
                            ProjectHeader(
                                title = project,
                                count = chats.size,
                                expanded = open,
                                onToggle = { collapsed[project] = open },
                                info = vm.projectInfo[project],
                                onOpen = { onOpenProject(project) },
                                onNewChat = {
                                    vm.newChat(project)
                                    onNavigate(Screen.Chat)
                                },
                                onAsk = { ask = it },
                                modifier = Modifier.animateItem(),
                            )
                        }
                        if (open) {
                            items(chats, key = { it.id }) { chat ->
                                ChatItem(chat, projectNames, vm, { onOpenChat(chat.id) }, { ask = it }, Modifier.animateItem())
                            }
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
                if (chatsOpen) {
                    items(plainChats, key = { it.id }) { chat ->
                        ChatItem(chat, projectNames, vm, { onOpenChat(chat.id) }, { ask = it }, Modifier.animateItem())
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .pressable(scaleTo = 0.95f) { onNewChat() }
                        .clip(CircleShape)
                        .background(DevxColors.Accent)
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(DevxIcons.ChatPlus, null, tint = DevxColors.OnAccent, modifier = Modifier.size(22.dp))
                    Text(stringResource(R.string.menu_new_chat), style = DevxType.Body, color = DevxColors.OnAccent)
                }
                RoundAction(Icons.Outlined.Checklist, stringResource(R.string.menu_tasks), badge = running) { onNavigate(Screen.Tasks) }
                RoundAction(DevxIcons.Mcp, stringResource(R.string.menu_mcp)) { onNavigate(Screen.Mcp) }
                RoundAction(DevxIcons.Settings, stringResource(R.string.menu_settings)) { onNavigate(Screen.Settings) }
            }
        }
    }

    AskHost(ask, onDismiss = { ask = null }, vm = vm)
}

// ---------- Rows ----------

/** The way to the computers: the devx on a PC that this phone controls. A dot says if one is reachable right now. */
@Composable
private fun RemoteButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    // How many chats on the computers want a look; nothing is shown when none does.
    val waiting = com.apvcode.devx.remote.RemoteHub.store.items.sumOf { com.apvcode.devx.remote.RemoteHub.connection(it.hostId)?.attention ?: 0 }
    Row(
        modifier
            .fillMaxWidth()
            .pressable(scaleTo = 0.97f, onClick = onClick)
            .clip(CircleShape)
            .background(DevxColors.Card)
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
            .clip(CircleShape)
            .background(DevxColors.Card)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(DevxIcons.ChatSearch, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(22.dp))
        Text(stringResource(R.string.drawer_search), style = DevxType.Body, color = DevxColors.TextSecondary)
    }
}

@Composable
private fun RoundAction(icon: ImageVector, description: String, badge: Int = 0, onClick: () -> Unit) {
    Box(
        Modifier
            .size(50.dp)
            .pressable(onClick = onClick)
            .clip(CircleShape)
            .background(DevxColors.Card),
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
    val angle by animateFloatAsState(if (expanded) 0f else -90f, label = "chevron")
    Icon(
        Icons.Outlined.ExpandMore,
        null,
        tint = DevxColors.TextSecondary,
        modifier = Modifier.size(22.dp).rotate(angle),
    )
}

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
                onAsk(Ask.DeleteProject(title, count))
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
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val press = remember { PressState() }
    Box(modifier.pressAnchor(press)) {
        ChatCard(
            chat,
            onClick = onOpen,
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                menu = true
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
                MenuRow(DevxIcons.Archive, stringResource(R.string.menu_archive)) {
                    vm.archiveChat(chat.id)
                    menu = false
                }
                MenuRow(DevxIcons.ChatOff, stringResource(R.string.menu_delete), danger = true) {
                    menu = false
                    onAsk(Ask.DeleteChat(chat))
                }
            } else {
                MenuRow(DevxIcons.ArrowLeft, stringResource(R.string.menu_add_to_project)) { page = 0 }
                HorizontalDivider(color = DevxColors.Outline, thickness = 0.5.dp)
                projects.forEach { name ->
                    MenuRow(
                        DevxIcons.Folder,
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
private fun ChatCard(chat: ChatSummary, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .pressable(scaleTo = 0.975f, haptic = false, onLongClick = onLongClick, onClick = onClick)
            .clip(shape)
            .background(DevxColors.Card)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (chat.status) {
                ChatStatus.Working -> Box(Modifier.size(8.dp).background(DevxColors.Success, CircleShape))
                ChatStatus.Replied -> Icon(DevxIcons.ChatAlert, null, tint = DevxColors.TextPrimary, modifier = Modifier.size(18.dp))
                ChatStatus.Waiting -> Icon(DevxIcons.ChatQuestion, null, tint = DevxColors.Warning, modifier = Modifier.size(18.dp))
                ChatStatus.Idle -> Unit
            }
            Text(
                chat.title,
                style = DevxType.BodyStrong,
                color = DevxColors.TextPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (chat.locked) {
                Icon(DevxIcons.Lock, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(15.dp))
            }
            if (chat.pinned) {
                Icon(DevxIcons.Pin, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(15.dp))
            }
            Text(chat.time, style = DevxType.Caption, color = DevxColors.TextDisabled)
        }
        Text(chat.preview, style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 1)
    }
}

// ---------- Dialogs ----------

@Composable
private fun AskHost(ask: Ask?, onDismiss: () -> Unit, vm: ChatViewModel) {
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
        is Ask.DeleteProject -> ConfirmDelete(
            icon = DevxIcons.FolderOff,
            title = stringResource(R.string.dialog_delete_project),
            text = ask.name + "\n" + stringResource(R.string.dialog_delete_project_text, ask.chats),
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
