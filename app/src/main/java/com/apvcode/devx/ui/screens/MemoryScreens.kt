package com.apvcode.devx.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.apvcode.devx.R
import com.apvcode.devx.data.MemoryNote
import com.apvcode.devx.data.MemoryStore
import com.apvcode.devx.data.Prefs
import com.apvcode.devx.ui.chat.ChatViewModel
import com.apvcode.devx.ui.components.CircleIconButton
import com.apvcode.devx.ui.components.DevxDialog
import com.apvcode.devx.ui.components.DevxIcons
import com.apvcode.devx.ui.components.DevxTextField
import com.apvcode.devx.ui.components.MenuRow
import com.apvcode.devx.ui.components.PillButton
import com.apvcode.devx.ui.components.PopupMenu
import com.apvcode.devx.ui.components.ScreenScaffold
import com.apvcode.devx.ui.components.SettingsDivider
import com.apvcode.devx.ui.components.SettingsGroup
import com.apvcode.devx.ui.components.SettingsRow
import com.apvcode.devx.ui.components.SettingsSwitchRow
import com.apvcode.devx.ui.markdown.MarkdownText
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import java.util.Date
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import com.apvcode.devx.ui.components.glass
import com.apvcode.devx.ui.components.pressable
import dev.chrisbanes.haze.hazeSource

/** Settings, Memory: the switch, the overview, and the saved notes. */
@Composable
fun MemoryScreen(vm: ChatViewModel, onBack: () -> Unit, onSummary: () -> Unit, onNotes: () -> Unit) {
    var picking by remember { mutableStateOf(false) }
    if (picking) SummaryModelPicker(vm, onDismiss = { picking = false })
    ScreenScaffold(stringResource(R.string.memory_title), onBack) {
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    title = stringResource(R.string.memory_enable),
                    subtitle = null,
                    checked = Prefs.memory,
                    onCheckedChange = { Prefs.saveMemory(it) },
                )
            }
        }
        item { Hint(stringResource(R.string.memory_enable_hint)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    title = stringResource(R.string.memory_remote),
                    subtitle = null,
                    checked = Prefs.remoteMemory && Prefs.memory,
                    onCheckedChange = { Prefs.saveRemoteMemory(it) },
                )
            }
        }
        item { Hint(stringResource(R.string.memory_remote_hint)) }
        item {
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.memory_summary),
                    showChevron = true,
                    onClick = onSummary,
                )
            }
        }
        item { Hint(stringResource(R.string.memory_summary_hint)) }
        item {
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.memory_model),
                    value = Prefs.summaryModel?.model ?: stringResource(R.string.memory_model_auto),
                    showChevron = true,
                    onClick = { picking = true },
                )
            }
        }
        item { Hint(stringResource(R.string.memory_model_hint)) }
        item {
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.memory_notes),
                    value = vm.memory.notes.size.takeIf { it > 0 }?.let { stringResource(R.string.memory_count, it) },
                    showChevron = true,
                    onClick = onNotes,
                )
            }
        }
        item { Hint(stringResource(R.string.memory_notes_hint)) }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = Modifier.padding(horizontal = 12.dp))
}

/** The overview of what the assistant knows, written by the model; it renews itself when the memory has changed. */
@Composable
fun MemorySummaryScreen(vm: ChatViewModel, onBack: () -> Unit, project: String? = null) {
    val memory = if (project == null) vm.memory else vm.projectInfo[project]?.memory ?: vm.memory
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    if (picking) SummaryModelPicker(vm, onDismiss = { picking = false })
    androidx.activity.compose.BackHandler(enabled = editing) { editing = false }
    LaunchedEffect(Unit) {
        if (vm.memorySummaryStale(project)) vm.refreshMemorySummary(project)
    }
    Column(Modifier.fillMaxSize().background(DevxColors.Bg).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            CircleIconButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.cd_back),
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(if (project == null) R.string.memory_summary else R.string.project_summary), style = DevxType.Title, color = DevxColors.TextPrimary)
                val subtitle = when {
                    memory.summarizing -> stringResource(R.string.memory_updating)
                    editing -> stringResource(R.string.memory_editing)
                    memory.summaryManual -> stringResource(R.string.memory_edited)
                    memory.summaryAt > 0 && memory.summary.isNotEmpty() -> updatedText(memory.summaryAt)
                    else -> null
                }
                if (subtitle != null) Text(subtitle, style = DevxType.Caption, color = DevxColors.TextSecondary)
            }
            Box(Modifier.align(Alignment.CenterEnd)) {
                CircleIconButton(
                    icon = Icons.Outlined.MoreVert,
                    contentDescription = null,
                    onClick = { menu = true },
                )
                PopupMenu(expanded = menu, onDismiss = { menu = false }, offset = DpOffset(0.dp, 0.dp)) {
                    MenuRow(DevxIcons.Pencil, stringResource(R.string.memory_edit), enabled = !memory.summarizing) {
                        menu = false
                        draft = memory.summary
                        editing = true
                    }
                    MenuRow(DevxIcons.Refresh, stringResource(R.string.memory_refresh), enabled = !memory.summarizing) {
                        menu = false
                        vm.refreshMemorySummary(project)
                    }
                    MenuRow(DevxIcons.Brain, stringResource(R.string.memory_model)) {
                        menu = false
                        picking = true
                    }
                    MenuRow(DevxIcons.Copy, stringResource(R.string.memory_copy), enabled = memory.summary.isNotEmpty()) {
                        menu = false
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("devx", memory.summary))
                    }
                }
            }
        }
        if (editing) {
            // The text of the overview itself (Markdown), as it is kept.
            Column(Modifier.weight(1f).imePadding().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp)).background(DevxColors.Card).padding(horizontal = 18.dp, vertical = 14.dp)) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = draft, onValueChange = { draft = it },
                        textStyle = DevxType.Body.copy(color = DevxColors.TextPrimary),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(DevxColors.Accent),
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        decorationBox = { inner ->
                            androidx.compose.foundation.layout.Box {
                                if (draft.isEmpty()) Text(stringResource(R.string.memory_edit_hint), style = DevxType.Body, color = DevxColors.TextDisabled)
                                inner()
                            }
                        },
                    )
                }
                Text(stringResource(R.string.memory_edit_note), style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = Modifier.padding(horizontal = 8.dp))
                PillButton(stringResource(R.string.memory_save), onClick = { memory.editSummary(draft); editing = false })
                PillButton(stringResource(R.string.settings_cancel), filled = false, onClick = { editing = false })
            }
        } else {
        val haze = dev.chrisbanes.haze.rememberHazeState()
        androidx.compose.runtime.CompositionLocalProvider(com.apvcode.devx.ui.components.LocalGlass provides haze) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
        Column(
            Modifier.fillMaxSize().hazeSource(haze).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = if (memory.summary.isNotEmpty()) 96.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (memory.summaryManual && memory.summary.isNotEmpty()) {
                Text(stringResource(R.string.memory_edited_hint), style = DevxType.Caption, color = DevxColors.TextSecondary)
            }
            if (memory.summary.isNotEmpty()) {
                MarkdownText(memory.summary)
            } else if (!memory.summarizing && memory.summaryError == null) {
                EmptyNote(stringResource(if (project != null) R.string.project_summary_empty else if (Prefs.memory) R.string.memory_summary_empty else R.string.memory_off_summary))
            }
            if (memory.summaryError != null) {
                Text(
                    stringResource(R.string.memory_summary_error),
                    style = DevxType.Caption,
                    color = DevxColors.Danger,
                )
            }
        }
        // To put something right: say it here and the overview is written again with it taken into account.
        if (memory.summary.isNotEmpty()) {
            AskDevxBar(
                busy = memory.summarizing,
                onSend = { vm.reviseMemorySummary(project, it) },
                modifier = Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        }
        }
        }
    }
}

/** The line at the bottom of the overview: a translucent capsule over the text, where a correction is written. */
@Composable
private fun AskDevxBar(busy: Boolean, onSend: (String) -> Unit, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val ready = text.isNotBlank() && !busy
    fun send() {
        if (!ready) return
        onSend(text)
        text = ""
        focus.clearFocus()
    }
    Row(
        modifier.fillMaxWidth().glass(CircleShape).padding(start = 20.dp, end = 6.dp).heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = text,
            onValueChange = { text = it },
            enabled = !busy,
            textStyle = DevxType.Body.copy(color = DevxColors.TextPrimary),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(DevxColors.Accent),
            maxLines = 4,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { send() }),
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) Text(stringResource(R.string.memory_ask_hint), style = DevxType.Body, color = DevxColors.TextDisabled, maxLines = 1)
                    inner()
                }
            },
        )
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (ready) DevxColors.Accent else Color.Transparent).pressable(enabled = ready, scaleTo = 0.9f, onClick = ::send),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.ArrowUpward, stringResource(R.string.voice_send), tint = if (ready) DevxColors.OnAccent else DevxColors.TextDisabled, modifier = Modifier.size(20.dp))
        }
    }
}

/** Every note the assistant wrote down: read it, change it, delete it, or add your own. */
@Composable
fun MemoryNotesScreen(vm: ChatViewModel, onBack: () -> Unit, project: String? = null) {
    val memory = if (project == null) vm.memory else vm.projectInfo[project]?.memory ?: vm.memory
    var editing by remember { mutableStateOf<MemoryNote?>(null) }
    var adding by remember { mutableStateOf(false) }
    var wipe by remember { mutableStateOf(false) }
    if (adding) NoteDialog(null, onDismiss = { adding = false }, onSave = { memory.add(it) }, onDelete = null)
    editing?.let { note ->
        NoteDialog(note, onDismiss = { editing = null }, onSave = { memory.edit(note.id, it) }, onDelete = { memory.remove(note.id) })
    }
    if (wipe) {
        DevxDialog(stringResource(R.string.memory_delete_all), onDismiss = { wipe = false }) {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.memory_delete_all_text), style = DevxType.Body, color = DevxColors.TextSecondary)
                PillButton(stringResource(R.string.memory_delete_all), onClick = {
                    memory.remove("all")
                    wipe = false
                })
                PillButton(stringResource(R.string.settings_cancel), filled = false, onClick = { wipe = false })
            }
        }
    }
    ScreenScaffold(stringResource(if (project == null) R.string.memory_notes else R.string.project_knowledge), onBack) {
        item {
            SettingsGroup {
                SettingsRow(title = stringResource(R.string.memory_add), icon = Icons.Outlined.Add, onClick = { adding = true })
            }
        }
        if (memory.notes.isEmpty()) {
            item { EmptyNote(stringResource(R.string.memory_notes_empty)) }
        } else {
            item {
                SettingsGroup {
                    val sorted = memory.notes.sortedByDescending { it.updatedAt }
                    sorted.forEachIndexed { i, note ->
                        if (i > 0) SettingsDivider()
                        SettingsRow(
                            title = note.text,
                            titleMaxLines = 3,
                            subtitle = DateFormat.format("d MMM yyyy", Date(note.updatedAt)).toString(),
                            onClick = { editing = note },
                        )
                    }
                }
            }
            item {
                SettingsGroup {
                    SettingsRow(
                        title = stringResource(R.string.memory_delete_all),
                        titleColor = DevxColors.Danger,
                        onClick = { wipe = true },
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteDialog(note: MemoryNote?, onDismiss: () -> Unit, onSave: (String) -> Unit, onDelete: (() -> Unit)?) {
    var text by remember { mutableStateOf(note?.text.orEmpty()) }
    DevxDialog(stringResource(if (note == null) R.string.memory_add else R.string.memory_note), onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            DevxTextField(
                stringResource(R.string.memory_note_hint),
                text,
                { if (it.length <= MemoryStore.MAX_NOTE_CHARS) text = it },
                singleLine = false,
            )
            PillButton(
                stringResource(R.string.memory_save),
                enabled = text.isNotBlank(),
                onClick = {
                    onSave(text)
                    onDismiss()
                },
            )
            if (onDelete != null) {
                PillButton(
                    stringResource(R.string.menu_delete),
                    filled = false,
                    onClick = {
                        onDelete()
                        onDismiss()
                    },
                )
            }
        }
    }
}

@Composable
private fun updatedText(at: Long): String {
    val minutes = (System.currentTimeMillis() - at) / 60_000
    if (minutes < 1) return stringResource(R.string.memory_updated_now)
    val ago = when {
        minutes < 60 -> "$minutes ${stringResource(R.string.age_min)}"
        minutes < 24 * 60 -> "${minutes / 60} ${stringResource(R.string.age_hour)}"
        else -> "${minutes / (24 * 60)} ${stringResource(R.string.age_day)}"
    }
    return stringResource(R.string.memory_updated_ago, ago)
}

/** Which model writes the summary: any model of any provider, or "same as the chat". */
@Composable
private fun SummaryModelPicker(vm: ChatViewModel, onDismiss: () -> Unit) {
    com.apvcode.devx.ui.chat.ModelPicker(
        vm = vm,
        onDismiss = onDismiss,
        onOpenProviders = onDismiss,
        onPick = { Prefs.saveSummaryModel(it) },
        selected = Prefs.summaryModel,
        autoLabel = stringResource(R.string.memory_model_auto),
    )
}
