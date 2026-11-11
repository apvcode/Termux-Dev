package com.apvcode.devx.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apvcode.devx.R
import com.apvcode.devx.data.ProjectInfo
import com.apvcode.devx.data.ProjectLook
import com.apvcode.devx.ui.chat.ChatViewModel
import com.apvcode.devx.ui.components.CircleIconButton
import com.apvcode.devx.ui.components.DevxDialog
import com.apvcode.devx.ui.components.DevxIcons
import com.apvcode.devx.ui.components.DevxTextField
import com.apvcode.devx.ui.components.MenuRow
import com.apvcode.devx.ui.components.PillButton
import com.apvcode.devx.ui.components.PopupMenu
import com.apvcode.devx.ui.components.SectionLabel
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType

/** The icon of a project by its id. */
@Composable
fun projectIcon(id: String): ImageVector = ImageVector.vectorResource(
    when (id) {
        "code" -> R.drawable.ic_p_code
        "terminal" -> R.drawable.ic_p_terminal
        "book" -> R.drawable.ic_p_book
        "school" -> R.drawable.ic_p_school
        "music" -> R.drawable.ic_p_music
        "palette" -> R.drawable.ic_p_palette
        "briefcase" -> R.drawable.ic_p_briefcase
        "chart" -> R.drawable.ic_p_chart_bar
        "flask" -> R.drawable.ic_p_flask
        "heart" -> R.drawable.ic_p_heart
        "plane" -> R.drawable.ic_p_plane
        "tool" -> R.drawable.ic_p_tool
        "paw" -> R.drawable.ic_p_paw
        "bulb" -> R.drawable.ic_p_bulb
        "rocket" -> R.drawable.ic_p_rocket
        "gamepad" -> R.drawable.ic_p_device_gamepad
        "cart" -> R.drawable.ic_p_shopping_cart
        "dollar" -> R.drawable.ic_p_currency_dollar
        "language" -> R.drawable.ic_p_language
        "news" -> R.drawable.ic_p_news
        "bolt" -> R.drawable.ic_p_bolt
        "star" -> R.drawable.ic_p_star
        "coffee" -> R.drawable.ic_p_coffee
        "brain" -> R.drawable.ic_brain
        else -> R.drawable.ic_folder
    },
)

/** A round badge with the project's icon, tinted with its colour. */
@Composable
fun ProjectBadge(icon: String, color: Long, size: androidx.compose.ui.unit.Dp = 44.dp, modifier: Modifier = Modifier) {
    val tint = Color(color)
    Box(
        modifier.size(size).clip(CircleShape).background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(projectIcon(icon), null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** A project's page: what it is, its instructions, knowledge base and summary, and its chats. */
@Composable
fun ProjectScreen(
    vm: ChatViewModel,
    name: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onKnowledge: () -> Unit,
    onSummary: () -> Unit,
    onOpenChat: (String) -> Unit,
    onNewChat: () -> Unit,
) {
    val info = vm.projectInfo[name]
    if (info == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    var menu by remember { mutableStateOf(false) }
    var instructions by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val chats = vm.chats.filter { it.project == name }
    LaunchedEffect(name) {
        if (vm.memorySummaryStale(name)) vm.refreshMemorySummary(name)
    }
    if (instructions) InstructionsDialog(info.instructions, onDismiss = { instructions = false }) { vm.setProjectInstructions(name, it) }
    if (delete) {
        DevxDialog(stringResource(R.string.dialog_delete_project), onDismiss = { delete = false }) {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(name + "\n" + stringResource(R.string.dialog_delete_project_text, chats.size), style = DevxType.Body, color = DevxColors.TextSecondary)
                PillButton(stringResource(R.string.menu_delete), onClick = {
                    delete = false
                    onBack()
                    vm.deleteProject(name)
                })
                PillButton(stringResource(R.string.settings_cancel), filled = false, onClick = { delete = false })
            }
        }
    }
    Box(Modifier.fillMaxSize().background(DevxColors.Bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                CircleIconButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), onBack, Modifier.align(Alignment.CenterStart))
                Box(Modifier.align(Alignment.CenterEnd)) {
                    CircleIconButton(Icons.Outlined.MoreVert, null, { menu = true })
                    PopupMenu(expanded = menu, onDismiss = { menu = false }, offset = DpOffset(0.dp, 0.dp)) {
                        MenuRow(DevxIcons.ChatPlus, stringResource(R.string.project_new_chat)) { menu = false; onNewChat() }
                        MenuRow(DevxIcons.Pencil, stringResource(R.string.project_edit)) { menu = false; onEdit() }
                        MenuRow(DevxIcons.Settings, stringResource(R.string.project_instructions)) { menu = false; instructions = true }
                        MenuRow(DevxIcons.FolderOff, stringResource(R.string.menu_delete), danger = true) { menu = false; delete = true }
                    }
                }
            }
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ProjectBadge(info.icon, info.color, 56.dp)
                        Text(name, style = DevxType.Title.copy(fontSize = 26.sp, lineHeight = 32.sp), color = DevxColors.TextPrimary)
                        if (info.description.isNotBlank()) {
                            Text(info.description, style = DevxType.Body, color = DevxColors.TextSecondary, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        InfoCard(
                            stringResource(R.string.project_knowledge),
                            if (info.memory.notes.isEmpty()) stringResource(R.string.project_knowledge_empty) else "${info.memory.notes.size}",
                            onKnowledge,
                            Modifier.weight(1f),
                        )
                        InfoCard(
                            stringResource(R.string.project_instructions),
                            stringResource(if (info.instructions.isBlank()) R.string.project_instructions_add else R.string.project_instructions_set),
                            { instructions = true },
                            Modifier.weight(1f),
                        )
                    }
                }
                item { SummaryCard(info, onSummary) }
                item { SectionLabel(stringResource(R.string.project_chats), Modifier.padding(top = 8.dp)) }
                if (chats.isEmpty()) {
                    item {
                        Text(stringResource(R.string.project_chats_empty), style = DevxType.Body, color = DevxColors.TextDisabled, modifier = Modifier.padding(start = 12.dp))
                    }
                } else {
                    items(chats.size) { i ->
                        val chat = chats[i]
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .pressable(scaleTo = 0.98f, haptic = false) { onOpenChat(chat.id) }
                                .clip(RoundedCornerShape(18.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(chat.title, style = DevxType.Body, color = DevxColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (chat.preview.isNotBlank()) {
                                    Text(chat.preview, style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Text(chat.time, style = DevxType.Caption, color = DevxColors.TextDisabled)
                        }
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp)) {
            PillButton(stringResource(R.string.project_new_chat), onClick = onNewChat, modifier = Modifier.width(220.dp))
        }
    }
}

@Composable
private fun InfoCard(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .pressable(scaleTo = 0.97f, haptic = false, onClick = onClick)
            .clip(shape)
            .background(DevxColors.Card)
            .border(BorderStroke(1.dp, DevxColors.Outline), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label, style = DevxType.Caption, color = DevxColors.TextSecondary)
        Text(value, style = DevxType.Body, color = DevxColors.TextPrimary, maxLines = 1)
    }
}

@Composable
private fun SummaryCard(info: ProjectInfo, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    val memory = info.memory
    Column(
        Modifier
            .fillMaxWidth()
            .pressable(scaleTo = 0.98f, haptic = false, onClick = onClick)
            .clip(shape)
            .background(DevxColors.Card)
            .border(BorderStroke(1.dp, DevxColors.Outline), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.project_summary), style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = Modifier.weight(1f))
            if (memory.summarizing) Text(stringResource(R.string.memory_updating), style = DevxType.Caption, color = DevxColors.TextDisabled)
            else Icon(DevxIcons.ChevronRight, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(20.dp))
        }
        // A few lines of it, plain: the whole overview is one tap away.
        val preview = memory.summary.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.joinToString(" ").replace(Regex("[*_`]"), "")
        Text(
            preview.ifBlank { stringResource(R.string.project_summary_empty) },
            style = DevxType.Body,
            color = if (preview.isBlank()) DevxColors.TextDisabled else DevxColors.TextPrimary,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InstructionsDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    DevxDialog(stringResource(R.string.project_instructions), onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            DevxTextField(
                stringResource(R.string.project_instructions_hint),
                text,
                { if (it.length <= 4000) text = it },
                singleLine = false,
                minLines = 4,
            )
            PillButton(stringResource(R.string.memory_save), onClick = { onSave(text); onDismiss() })
        }
    }
}

/** Creating a project, or (when [name] is given) changing it: a name with an icon, and what it is for. */
@Composable
fun ProjectEditScreen(vm: ChatViewModel, name: String?, onClose: () -> Unit, onDone: (String) -> Unit) {
    val info = name?.let { vm.projectInfo[it] }
    var title by remember { mutableStateOf(name.orEmpty()) }
    var description by remember { mutableStateOf(info?.description.orEmpty()) }
    var icon by remember { mutableStateOf(info?.icon ?: ProjectLook.DEFAULT_ICON) }
    var color by remember { mutableStateOf(info?.color ?: ProjectLook.colors.first()) }
    var picker by remember { mutableStateOf(false) }
    val trimmed = title.trim()
    val taken = trimmed.isNotEmpty() && trimmed != name && trimmed in vm.projects
    if (picker) LookPicker(icon, color, onDismiss = { picker = false }) { i, c -> icon = i; color = c }
    Column(Modifier.fillMaxSize().background(DevxColors.Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            CircleIconButton(Icons.Outlined.Close, stringResource(R.string.cd_close), onClose, Modifier.align(Alignment.CenterStart))
            Text(stringResource(if (name == null) R.string.project_new else R.string.project_edit), style = DevxType.Title, color = DevxColors.TextPrimary)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProjectBadge(icon, color, 54.dp, Modifier.pressable(scaleTo = 0.94f, onClick = { picker = true }))
                    DevxTextField(
                        stringResource(R.string.project_name_label),
                        title,
                        { if (it.length <= 40) title = it.replace("\n", "") },
                        Modifier.weight(1f),
                        placeholder = stringResource(R.string.project_name_hint),
                    )
                }
                if (taken) Text(stringResource(R.string.project_name_taken), style = DevxType.Caption, color = DevxColors.Danger, modifier = Modifier.padding(start = 6.dp))
            }
            DevxTextField(
                stringResource(R.string.project_desc_label),
                description,
                { if (it.length <= 600) description = it },
                placeholder = stringResource(R.string.project_desc_hint),
                singleLine = false,
                minLines = 4,
            )
        }
        PillButton(
            stringResource(if (name == null) R.string.project_create else R.string.memory_save),
            enabled = trimmed.isNotEmpty() && !taken,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            onClick = {
                val ok = if (name == null) vm.createProject(trimmed, description, icon, color) else vm.editProject(name, trimmed, description, icon, color)
                if (ok) onDone(trimmed)
            },
        )
    }
}

/** Colours in a row, and a grid of icons under them. */
@Composable
private fun LookPicker(icon: String, color: Long, onDismiss: () -> Unit, onPick: (String, Long) -> Unit) {
    var i by remember { mutableStateOf(icon) }
    var c by remember { mutableStateOf(color) }
    DevxDialog(stringResource(R.string.project_icon), onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ProjectBadge(i, c, 64.dp) }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProjectLook.colors.forEach { col ->
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(col))
                            .pressable(scaleTo = 0.9f) { c = col },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (col == c) Icon(DevxIcons.Check, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ProjectLook.icons.chunked(5).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        row.forEach { id ->
                            Box(
                                Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(if (id == i) DevxColors.Outline else Color.Transparent)
                                    .pressable(scaleTo = 0.9f) { i = id },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(projectIcon(id), null, tint = DevxColors.TextPrimary, modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
            PillButton(stringResource(R.string.project_done), onClick = { onPick(i, c); onDismiss() })
        }
    }
}
