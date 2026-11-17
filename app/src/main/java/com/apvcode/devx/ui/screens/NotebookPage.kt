package com.apvcode.devx.ui.screens

import com.apvcode.devx.data.logged
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.foundation.layout.Arrangement
import com.apvcode.devx.ui.Ask
import com.apvcode.devx.ui.AskHost
import com.apvcode.devx.ui.ChatItem
import com.apvcode.devx.ui.SelectionBar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apvcode.devx.R
import com.apvcode.devx.data.Lesson
import com.apvcode.devx.data.ProjectInfo
import com.apvcode.devx.ui.chat.ChatViewModel
import com.apvcode.devx.ui.components.DevxDialog
import com.apvcode.devx.ui.components.DevxIcons
import com.apvcode.devx.ui.components.LocalGlass
import com.apvcode.devx.ui.components.MenuRow
import com.apvcode.devx.ui.components.PillButton
import com.apvcode.devx.ui.components.PopupMenu
import com.apvcode.devx.ui.components.glass
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.cos
import kotlin.math.sin
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.apvcode.devx.data.NotebookExport
import com.apvcode.devx.data.Pace
import com.apvcode.devx.data.StudyLog
import com.apvcode.devx.data.StudyPlan
import com.apvcode.devx.ui.components.TextEditorSheet
import java.util.Locale
import kotlinx.coroutines.Dispatchers

/**
 * A notebook's own page. A calm glow in the notebook's colour behind its floating icon and name; what it holds at a glance (sources,
 * chats, lessons); the two things to do first (add sources, or let the assistant find them on the web); for a study notebook the goal,
 * a ring of progress and the lessons as a path; ideas to begin with; the sources as a strip of cards; the notebook's memory; and the
 * past chats. Everything comes in one after another, and the field at the bottom (glass over the page) asks right in the notebook.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotebookPage(
    vm: ChatViewModel,
    name: String,
    onBack: () -> Unit,
    onSources: () -> Unit,
    onSettings: () -> Unit,
    onKnowledge: () -> Unit,
    onSummary: () -> Unit,
    onEdit: () -> Unit,
    onOpenChat: (String) -> Unit,
    onNewChat: () -> Unit,
    onAsk: (String) -> Unit,
    /** Opens the cards screen: the cards that are due, one after another, without a chat. */
    onCards: () -> Unit = {},
    /** Opens the concept map of the notebook. */
    onGraph: () -> Unit = {},
) {
    val info = vm.projectInfo[name]
    if (info == null) { LaunchedEffect(Unit) { onBack() }; return }
    val tint = Color(info.color).let { if (info.color == 0xFFE6E6E6L) Color(0xFF8FA3D9) else it }
    val chats = vm.chats.filter { it.project == name }
    var menu by remember { mutableStateOf(false) }
    val exportContext = LocalContext.current
    val exportScope = androidx.compose.runtime.rememberCoroutineScope()
    val export = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) exportScope.launch {
            val ok = kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching { exportContext.contentResolver.openOutputStream(uri)!!.use { it.write(vm.exportNotebook(name).toByteArray(Charsets.UTF_8)) } }.isSuccess
            }
            Toast.makeText(exportContext, if (ok) R.string.export_saved else R.string.export_failed, Toast.LENGTH_SHORT).show()
        }
    }
    // Cards in and out: a file of cards (Anki text or package, CSV) is read into the notebook, and the cards are written as Anki text or CSV.
    val importCards = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) exportScope.launch {
            val r = vm.importCards(name, uri)
            val text = r.problem?.let { exportContext.getString(it) } ?: exportContext.getString(R.string.cards_imported, r.added, r.inFile)
            Toast.makeText(exportContext, text, Toast.LENGTH_LONG).show()
        }
    }
    var cardsCsv by remember { mutableStateOf(false) }
    val exportCards = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportScope.launch {
            val ok = kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching { exportContext.contentResolver.openOutputStream(uri)!!.use { it.write(vm.exportCards(name, cardsCsv).toByteArray(Charsets.UTF_8)) } }.isSuccess
            }
            Toast.makeText(exportContext, if (ok) R.string.export_saved else R.string.export_failed, Toast.LENGTH_SHORT).show()
        }
    }
    val exportApkg = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) exportScope.launch {
            val n = kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching { exportContext.contentResolver.openOutputStream(uri)!!.use { vm.exportApkg(name, it) } }.logged("notebookpage").getOrNull()
            }
            Toast.makeText(exportContext, if (n != null && n > 0) R.string.export_saved else R.string.export_failed, Toast.LENGTH_SHORT).show()
        }
    }
    var delete by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf("") }
    // Chats of the notebook: hold one for its menu (pick, pin, rename, move, archive, delete), tap the others to tick them.
    var chatAsk by remember { mutableStateOf<Ask?>(null) }
    var selecting by remember { mutableStateOf(false) }
    val picked = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    fun stopSelecting() { selecting = false; picked.clear() }
    BackHandler(enabled = selecting) { stopSelecting() }
    // A chat that is gone (deleted, moved to another notebook) is no longer ticked.
    LaunchedEffect(chats.map { it.id }) { picked.retainAll(chats.map { it.id }.toSet()); if (selecting && chats.isEmpty()) stopSelecting() }
    val haze = rememberHazeState()
    LaunchedEffect(name) { if (vm.memorySummaryStale(name)) vm.refreshMemorySummary(name) }

    val goalPrompt = stringResource(R.string.nb_goal_prompt, name)
    val findPrompt = stringResource(R.string.nb_find_sources_prompt, name)
    val ideas = when {
        !info.isNotebook -> listOf(R.string.nb_chip_p1, R.string.nb_chip_p2, R.string.nb_chip_p3, R.string.nb_chip_p4)
        info.isStudy -> listOf(R.string.nb_chip_s1, R.string.nb_chip_s2, R.string.nb_chip_s3, R.string.nb_chip_s4)
        else -> listOf(R.string.nb_chip_i1, R.string.nb_chip_i2, R.string.nb_chip_i3, R.string.nb_chip_i4)
    }
    var instructionsOpen by remember { mutableStateOf(false) }
    val openInstructions = { if (info.isNotebook) onSettings() else instructionsOpen = true }
    if (instructionsOpen) TextEditorSheet(
        title = stringResource(R.string.project_instructions), hint = stringResource(R.string.project_instructions_hint), initial = info.instructions,
        maxChars = 4000, onDismiss = { instructionsOpen = false }, onSave = { vm.setProjectInstructions(name, it) },
    )
    val ideaTexts = ideas.map { stringResource(it) }

    AskHost(chatAsk, onDismiss = { chatAsk = null }, vm = vm, onDeleted = { stopSelecting() })
    if (delete) {
        DevxDialog(stringResource(if (info.isNotebook) R.string.dialog_delete_notebook else R.string.dialog_delete_project), onDismiss = { delete = false }) {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(name + "\n" + stringResource(if (info.isNotebook) R.string.dialog_delete_notebook_text else R.string.dialog_delete_project_text, chats.size), style = DevxType.Body, color = DevxColors.TextSecondary)
                PillButton(stringResource(R.string.menu_delete), onClick = { delete = false; onBack(); vm.deleteProject(name) })
                PillButton(stringResource(R.string.settings_cancel), filled = false, onClick = { delete = false })
            }
        }
    }

    CompositionLocalProvider(LocalGlass provides haze) {
        Box(Modifier.fillMaxSize().background(DevxColors.Bg)) {
            LazyColumn(
                Modifier.fillMaxSize().hazeSource(haze),
                contentPadding = PaddingValues(bottom = 130.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                item { Appear(0) { Hero(info, name, tint) } }
                item {
                    Appear(1) {
                        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (info.isNotebook) StatChip(stringResource(R.string.notebook_sources), info.sources.size, onSources)
                                StatChip(stringResource(R.string.drawer_chats), chats.size, null)
                                if (!info.isNotebook) StatChip(stringResource(R.string.project_knowledge), info.memory.notes.size, onKnowledge)
                                if (info.isStudy && info.plan.total > 0) StatChip(stringResource(R.string.nb_lessons), info.plan.done, null, "/${info.plan.total}")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (info.isNotebook) {
                                    ActionPill(Icons.Outlined.Add, stringResource(R.string.notebook_sources), filled = true, tint = tint, modifier = Modifier.weight(1f), onClick = onSources)
                                    ActionPill(Icons.Outlined.TravelExplore, stringResource(R.string.nb_find_sources), filled = false, tint = tint, modifier = Modifier.weight(1f)) { onAsk(findPrompt) }
                                } else {
                                    ActionPill(Icons.Outlined.Add, stringResource(R.string.project_new_chat), filled = true, tint = tint, modifier = Modifier.weight(1f), onClick = onNewChat)
                                    ActionPill(DevxIcons.Settings, stringResource(R.string.project_instructions), filled = false, tint = tint, modifier = Modifier.weight(1f), onClick = openInstructions)
                                }
                            }
                            // An overview of the sources to listen to: written to be read aloud, then read.
                            if (info.isNotebook && info.sources.isNotEmpty()) {
                                val audio = stringResource(R.string.nb_prompt_audio)
                                ActionPill(DevxIcons.Speaker, stringResource(R.string.nb_audio), filled = false, tint = tint, modifier = Modifier.fillMaxWidth()) { vm.autoListen = true; onAsk(audio) }
                            }
                        }
                    }
                }
                if (info.isNotebook && info.isStudy) item {
                    Appear(2) {
                        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SectionTitle(stringResource(R.string.nb_progress))
                            if (info.goal.isBlank()) GoalCard(tint) { onAsk(goalPrompt) }
                            else ProgressCard(info, tint, onAsk, onCards, onGraph, onChangeGoal = { onAsk(goalPrompt) }, onLesson = { l, s -> vm.setStudyLesson(name, l.id, s) }, onDeadline = { vm.setNotebookDeadline(name, it) })
                        }
                    }
                }
                item {
                    Appear(3) {
                        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SectionTitle(stringResource(R.string.nb_ideas))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ideaTexts.forEach { t ->
                                    Row(
                                        Modifier.pressable(scaleTo = 0.95f) { onAsk(t) }.clip(CircleShape).background(DevxColors.Card).border(1.dp, DevxColors.Outline, CircleShape).padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(Icons.Outlined.AutoAwesome, null, tint = tint, modifier = Modifier.size(16.dp))
                                        Text(t, style = DevxType.Body.copy(fontSize = 15.sp), color = DevxColors.TextPrimary)
                                    }
                                }
                            }
                        }
                    }
                }
                if (info.isNotebook) item {
                    Appear(4) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SectionTitle(stringResource(R.string.notebook_sources), Modifier.padding(horizontal = 20.dp), action = if (info.sources.isNotEmpty()) stringResource(R.string.lib_all) else null, onAction = onSources)
                            if (info.sources.isEmpty()) {
                                Column(
                                    Modifier.padding(horizontal = 20.dp).fillMaxWidth().pressable(scaleTo = 0.98f, onClick = onSources).clip(RoundedCornerShape(24.dp))
                                        .border(1.5.dp, DevxColors.Outline, RoundedCornerShape(24.dp)).padding(22.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(DevxIcons.Paperclip, null, tint = tint, modifier = Modifier.size(26.dp))
                                    Text(stringResource(R.string.notebook_sources_empty), style = DevxType.Body, color = DevxColors.TextSecondary)
                                }
                            } else {
                                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    items(info.sources.toList().asReversed(), key = { it.id }) { a ->
                                        val summary = vm.libraryMeta.summaries[a.id]
                                        Column(
                                            Modifier.width(220.dp).heightIn(min = 150.dp).pressable(scaleTo = 0.97f, onClick = onSources).clip(RoundedCornerShape(22.dp)).background(DevxColors.Card).padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                SourceThumb(a, vm, 40.dp)
                                                Text(a.name, style = DevxType.BodyStrong.copy(fontSize = 14.sp), color = DevxColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            }
                                            if (summary != null) Text(summary, style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 4, overflow = TextOverflow.Ellipsis)
                                            else Shimmer()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Appear(5) {
                        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SectionTitle(stringResource(if (info.isNotebook) R.string.nb_memory else R.string.nb_project_memory))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                MemoryTile(DevxIcons.Notebook, stringResource(R.string.project_knowledge), if (info.memory.notes.isEmpty()) stringResource(R.string.project_knowledge_empty) else "${info.memory.notes.size}", Modifier.weight(1f), onKnowledge)
                                MemoryTile(DevxIcons.Settings, stringResource(R.string.project_instructions), stringResource(if (info.instructions.isBlank()) R.string.project_instructions_add else R.string.project_instructions_set), Modifier.weight(1f), openInstructions)
                            }
                            Column(
                                Modifier.fillMaxWidth().pressable(scaleTo = 0.98f, haptic = false, onClick = onSummary).clip(RoundedCornerShape(22.dp)).background(DevxColors.Card).padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(if (info.isNotebook) R.string.notebook_summary else R.string.project_summary), style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = Modifier.weight(1f))
                                    if (info.memory.summarizing) Text(stringResource(R.string.memory_updating), style = DevxType.Caption, color = DevxColors.TextDisabled)
                                    else Icon(DevxIcons.ChevronRight, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(18.dp))
                                }
                                val preview = info.memory.summary.lines().map { it.trim().trimStart('#', ' ') }.filter { it.isNotEmpty() }.joinToString(" ").take(220)
                                Text(preview.ifBlank { stringResource(R.string.project_summary_empty) }, style = DevxType.Body, color = if (preview.isBlank()) DevxColors.TextDisabled else DevxColors.TextPrimary, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                item {
                    Appear(6) {
                        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SectionTitle(stringResource(R.string.notebook_chats))
                            if (chats.isEmpty()) Text(stringResource(if (info.isNotebook) R.string.notebook_chats_empty else R.string.project_chats_empty), style = DevxType.Body, color = DevxColors.TextDisabled, modifier = Modifier.padding(top = 4.dp))
                            val projectNames = vm.projects.toList()
                            chats.forEach { c ->
                                androidx.compose.runtime.key(c.id) {
                                    ChatItem(
                                        c, projectNames, vm, { onOpenChat(c.id) }, { chatAsk = it },
                                        selecting, c.id in picked,
                                        { if (c.id in picked) picked.remove(c.id) else picked.add(c.id) },
                                        { selecting = true; if (c.id !in picked) picked.add(c.id) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // The top fades into the background, so that the page does not run under the clock.
            Box(Modifier.fillMaxWidth().statusBarsPadding().height(44.dp).offset(y = (-44).dp).background(DevxColors.Bg))
            Box(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).background(Brush.verticalGradient(listOf(DevxColors.Bg, Color.Transparent))))
            // The round buttons over the page, glass.
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Box(Modifier.size(46.dp).pressable(scaleTo = 0.9f, onClick = onBack).glass(CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = DevxColors.TextPrimary, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.weight(1f))
                Box {
                    Box(Modifier.size(46.dp).pressable(scaleTo = 0.9f) { menu = true }.glass(CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.MoreVert, stringResource(R.string.cd_more), tint = DevxColors.TextPrimary, modifier = Modifier.size(22.dp))
                    }
                    PopupMenu(expanded = menu, onDismiss = { menu = false }, offset = DpOffset(0.dp, 0.dp)) {
                        MenuRow(DevxIcons.ChatPlus, stringResource(R.string.project_new_chat)) { menu = false; onNewChat() }
                        MenuRow(DevxIcons.Pencil, stringResource(R.string.nb_edit)) { menu = false; onEdit() }
                        if (info.isNotebook) MenuRow(DevxIcons.Settings, stringResource(R.string.notebook_settings)) { menu = false; onSettings() }
                        else MenuRow(DevxIcons.Settings, stringResource(R.string.project_instructions)) { menu = false; instructionsOpen = true }
                        MenuRow(DevxIcons.Share, stringResource(R.string.nb_export)) { menu = false; export.launch(NotebookExport.fileName(name)) }
                        if (info.isStudy) {
                            MenuRow(DevxIcons.FilePlus, stringResource(R.string.cards_import)) { menu = false; importCards.launch(arrayOf("*/*")) }
                            MenuRow(DevxIcons.Share, stringResource(R.string.cards_export_anki)) { menu = false; cardsCsv = false; exportCards.launch("$name-cards.txt") }
                            MenuRow(DevxIcons.Share, stringResource(R.string.cards_export_apkg)) { menu = false; exportApkg.launch("$name.apkg") }
                            MenuRow(DevxIcons.Share, stringResource(R.string.cards_export_csv)) { menu = false; cardsCsv = true; exportCards.launch("$name-cards.csv") }
                        }
                        MenuRow(DevxIcons.Archive, stringResource(if (info.archived) R.string.space_unarchive else R.string.menu_archive)) { menu = false; vm.setArchived(name, !info.archived) }
                        MenuRow(DevxIcons.Copy, stringResource(R.string.space_duplicate)) {
                            menu = false
                            vm.duplicateProject(name)?.let { Toast.makeText(exportContext, exportContext.getString(R.string.space_duplicated, it), Toast.LENGTH_LONG).show() }
                        }
                        MenuRow(DevxIcons.ChatOff, stringResource(R.string.menu_delete), danger = true) { menu = false; delete = true }
                    }
                }
            }

            if (selecting) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    SelectionBar(
                        count = picked.size,
                        total = chats.size,
                        onCancel = { stopSelecting() },
                        onToggleAll = { if (picked.size == chats.size) picked.clear() else { picked.clear(); picked.addAll(chats.map { it.id }) } },
                        onArchive = { picked.toList().forEach(vm::archiveChat); stopSelecting() },
                        onDelete = { chatAsk = Ask.DeleteChats(picked.toList()) },
                    )
                }
            } else
            // Asking right in the notebook: glass over the page, the words start a chat of the notebook.
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 14.dp)
                    .glass(CircleShape).padding(start = 22.dp, end = 6.dp).heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicTextField(
                    value = ask, onValueChange = { ask = it }, maxLines = 4,
                    textStyle = DevxType.Body.copy(color = DevxColors.TextPrimary), cursorBrush = SolidColor(DevxColors.Accent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (ask.isNotBlank()) { onAsk(ask.trim()); ask = "" } }),
                    modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                    decorationBox = { inner -> Box { if (ask.isEmpty()) Text(stringResource(if (info.isNotebook) R.string.notebook_ask_hint else R.string.nb_ask_project), style = DevxType.Body, color = DevxColors.TextSecondary, maxLines = 1); inner() } },
                )
                val ready = ask.isNotBlank()
                Box(
                    Modifier.size(44.dp).pressable(enabled = ready, scaleTo = 0.9f) { onAsk(ask.trim()); ask = "" }.clip(CircleShape).background(if (ready) DevxColors.Accent else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.ArrowUpward, null, tint = if (ready) DevxColors.OnAccent else DevxColors.TextDisabled, modifier = Modifier.size(22.dp)) }
            }
        }
    }
}

/** A section that rises into place a moment after the one above it. */
@Composable
private fun Appear(index: Int, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = state,
        enter = fadeIn(tween(380, delayMillis = 60 + index * 70)) + slideInVertically(spring(dampingRatio = 0.86f, stiffness = 260f)) { it / 6 },
    ) { content() }
}

/** The top of the page: a slow glow in the notebook's colour, the icon floating in it, what kind of notebook it is, and its name. */
@Composable
private fun Hero(info: ProjectInfo, name: String, tint: Color) {
    // An even wash of the colour from the top that melts into the page, breathing very slowly: calm, never patchy.
    val breath by rememberInfiniteTransition(label = "hero").animateFloat(0.8f, 1f, infiniteRepeatable(tween(4200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "hero-breath")
    Box(Modifier.fillMaxWidth().heightIn(min = 300.dp)) {
        Box(
            Modifier.matchParentSize().graphicsLayer { alpha = breath }
                .background(Brush.verticalGradient(0f to tint.copy(alpha = 0.24f), 0.45f to tint.copy(alpha = 0.09f), 1f to DevxColors.Bg)),
        )
        Column(Modifier.statusBarsPadding().padding(start = 22.dp, end = 22.dp, top = 84.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val float by rememberInfiniteTransition(label = "float").animateFloat(-1f, 1f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "float-y")
            Box(Modifier.graphicsLayer { translationY = float * 5.dp.toPx() }) { ProjectBadge(info.icon, info.color, 76.dp) }
            Text(
                stringResource(if (!info.isNotebook) R.string.nb_kind_project else if (info.isStudy) R.string.nb_kind_study else R.string.nb_kind_ideas).uppercase(),
                style = DevxType.Caption.copy(fontWeight = FontWeight.Medium, letterSpacing = 1.4.sp), color = tint,
            )
            Text(name, style = DevxType.Large.copy(fontSize = 36.sp, lineHeight = 42.sp), color = DevxColors.TextPrimary)
            val line = if (info.isStudy && info.goal.isNotBlank()) info.goal else info.description
            if (line.isNotBlank()) Text(line, style = DevxType.Body, color = DevxColors.TextSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A number that counts up to its value, with what it counts. */
@Composable
private fun StatChip(label: String, value: Int, onClick: (() -> Unit)?, suffix: String = "") {
    val shown by animateIntAsState(value, tween(700, easing = FastOutSlowInEasing), label = "stat")
    Column(
        Modifier.then(if (onClick != null) Modifier.pressable(scaleTo = 0.95f, onClick = onClick) else Modifier).clip(RoundedCornerShape(18.dp)).background(DevxColors.Card).padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text("$shown$suffix", style = DevxType.Title.copy(fontSize = 20.sp, lineHeight = 24.sp), color = DevxColors.TextPrimary)
        Text(label, style = DevxType.Caption, color = DevxColors.TextSecondary)
    }
}

@Composable
private fun ActionPill(icon: ImageVector, label: String, filled: Boolean, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.pressable(scaleTo = 0.96f, onClick = onClick).clip(CircleShape)
            .then(if (filled) Modifier.background(tint.copy(alpha = 0.9f)) else Modifier.border(1.dp, tint.copy(alpha = 0.6f), CircleShape))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        Icon(icon, null, tint = if (filled) Color.Black else tint, modifier = Modifier.size(20.dp))
        Text(label, style = DevxType.BodyStrong, color = if (filled) Color.Black else DevxColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = DevxType.Title.copy(fontSize = 19.sp, lineHeight = 24.sp), color = DevxColors.TextPrimary, modifier = Modifier.weight(1f))
        if (action != null) Text(action, style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = Modifier.pressable(haptic = false, onClick = onAction).padding(6.dp))
    }
}

@Composable
private fun MemoryTile(icon: ImageVector, label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.pressable(scaleTo = 0.97f, haptic = false, onClick = onClick).clip(RoundedCornerShape(22.dp)).background(DevxColors.Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(20.dp))
        Text(label, style = DevxType.Caption, color = DevxColors.TextSecondary)
        Text(value, style = DevxType.BodyStrong, color = DevxColors.TextPrimary, maxLines = 1)
    }
}

/** No goal yet: one card that asks for it (the assistant helps to set it, in a chat). */
@Composable
private fun GoalCard(tint: Color, onSetGoal: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Brush.linearGradient(listOf(tint.copy(alpha = 0.20f), DevxColors.Card))).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.notebook_goal_title), style = DevxType.Title.copy(fontSize = 20.sp, lineHeight = 26.sp), color = DevxColors.TextPrimary)
        Text(stringResource(R.string.notebook_goal_text), style = DevxType.Body, color = DevxColors.TextSecondary)
        Text(
            stringResource(R.string.notebook_set_goal), style = DevxType.BodyStrong, color = Color.Black,
            modifier = Modifier.padding(top = 4.dp).pressable(onClick = onSetGoal).clip(CircleShape).background(tint).padding(horizontal = 22.dp, vertical = 12.dp),
        )
    }
}

/** The goal, a ring that fills with the lessons done, the next step, and the lessons as a path. */
@Composable
private fun ProgressCard(info: ProjectInfo, tint: Color, onAsk: (String) -> Unit, onCards: () -> Unit, onGraph: () -> Unit, onChangeGoal: () -> Unit, onLesson: (Lesson, String) -> Unit, onDeadline: (Long) -> Unit) {
    val plan = info.plan
    val fraction by animateFloatAsState(if (plan.total == 0) 0f else plan.done.toFloat() / plan.total, tween(1100, easing = FastOutSlowInEasing), label = "ring")
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(DevxColors.Card).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.size(86.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = 9.dp.toPx()
                    drawArc(DevxColors.Outline, -90f, 360f, false, style = Stroke(w, cap = StrokeCap.Round), topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w))
                    if (fraction > 0f) drawArc(tint, -90f, 360f * fraction, false, style = Stroke(w, cap = StrokeCap.Round), topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w))
                }
                Text("${(fraction * 100).toInt()}%", style = DevxType.BodyStrong, color = DevxColors.TextPrimary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.notebook_goal), style = DevxType.Caption, color = DevxColors.TextSecondary)
                Text(info.goal, style = DevxType.BodyStrong, color = DevxColors.TextPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.nb_change_goal), style = DevxType.Caption, color = tint, modifier = Modifier.pressable(haptic = false, onClick = onChangeGoal).padding(vertical = 4.dp))
            }
        }
        StudyStats(plan.log, tint)
        DeadlineRow(info, tint, onDeadline)
        val remindPrompt = stringResource(R.string.study_prompt_remind_setup)
        val next = plan.next
        val label = when {
            plan.total == 0 -> stringResource(R.string.notebook_start)
            next != null && next.status == Lesson.DOING -> stringResource(R.string.notebook_continue_lesson)
            next != null -> stringResource(R.string.notebook_next_lesson)
            else -> stringResource(R.string.notebook_review)
        }
        val prompt = when {
            plan.total == 0 -> stringResource(R.string.study_prompt_first, info.goal)
            next != null -> stringResource(R.string.study_prompt_lesson, next.title)
            else -> stringResource(R.string.study_prompt_review)
        }
        if (next != null) Text(next.title, style = DevxType.Body, color = DevxColors.TextPrimary)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label, style = DevxType.BodyStrong, color = Color.Black, modifier = Modifier.weight(1f, fill = false).pressable(onClick = { onAsk(prompt) }).clip(CircleShape).background(tint).padding(horizontal = 20.dp, vertical = 12.dp))
            if (plan.total > 0) {
                val quiz = stringResource(R.string.study_prompt_quiz)
                Text(stringResource(R.string.notebook_quiz), style = DevxType.BodyStrong, color = DevxColors.TextPrimary, modifier = Modifier.weight(1f, fill = false).pressable(onClick = { onAsk(quiz) }).clip(CircleShape).border(1.dp, DevxColors.Outline, CircleShape).padding(horizontal = 20.dp, vertical = 12.dp))
            }
        }
        if (plan.cards.isNotEmpty()) {
            val due = plan.due().size
            val review = stringResource(R.string.study_prompt_cards)
            Row(
                Modifier.fillMaxWidth().pressable(enabled = due > 0, scaleTo = 0.97f) { onCards() }.clip(RoundedCornerShape(18.dp))
                    .background(tint.copy(alpha = if (due > 0) 0.16f else 0.06f)).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(DevxIcons.Refresh, null, tint = if (due > 0) tint else DevxColors.TextDisabled, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (due > 0) pluralStringResource(R.plurals.nb_cards_due, due, due) else stringResource(R.string.nb_cards_none),
                        style = DevxType.BodyStrong, color = if (due > 0) DevxColors.TextPrimary else DevxColors.TextSecondary,
                    )
                    Text(pluralStringResource(R.plurals.nb_cards_total, plan.cards.size, plan.cards.size), style = DevxType.Caption, color = DevxColors.TextSecondary)
                }
            }
        }
        StudyModes(plan, tint, onAsk)
        // The map of concepts: a picture of the ideas and how they connect (the assistant builds it as it teaches).
        Row(
            Modifier.fillMaxWidth().pressable(scaleTo = 0.97f, haptic = false) { onGraph() }.clip(RoundedCornerShape(18.dp)).background(tint.copy(alpha = 0.08f)).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.Hub, null, tint = tint, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.graph_title), style = DevxType.BodyStrong, color = DevxColors.TextPrimary)
                Text(if (plan.concepts.isEmpty()) stringResource(R.string.graph_row_empty) else stringResource(R.string.graph_row_count, plan.concepts.size, plan.links.size), style = DevxType.Caption, color = DevxColors.TextSecondary)
            }
        }
        if (plan.total > 0) {
            Text(
                stringResource(R.string.nb_remind), style = DevxType.Caption, color = tint,
                modifier = Modifier.pressable(haptic = false) { onAsk(remindPrompt) }.padding(vertical = 2.dp),
            )
            // The lessons as a path: a line through dots, the done ones filled.
            val shown = if (open) plan.lessons.toList() else plan.lessons.toList().take(4)
            Column {
                shown.forEachIndexed { i, l -> PathStep(i + 1, l, tint, last = i == shown.lastIndex, onClick = { onLesson(l, if (l.status == Lesson.DONE) Lesson.TODO else Lesson.DONE) }) }
            }
            if (plan.total > 4) Text(
                stringResource(if (open) R.string.nb_show_less else R.string.nb_show_all, plan.total), style = DevxType.Caption, color = DevxColors.TextSecondary,
                modifier = Modifier.pressable(haptic = false) { open = !open }.padding(vertical = 4.dp),
            )
        }
    }
}

/** The day the person wants to reach the goal by, and the pace that takes; a tap picks or changes the day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeadlineRow(info: ProjectInfo, tint: Color, onDeadline: (Long) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val plan = info.plan
    val pace = Pace.of(info.deadline, plan.total - plan.done)
    val line = when {
        pace == null -> stringResource(R.string.nb_deadline_set)
        pace.overdue -> stringResource(R.string.nb_deadline_over)
        pace.daysLeft == 0 -> stringResource(R.string.nb_deadline_today)
        else -> stringResource(R.string.nb_deadline_days, pace.daysLeft)
    }
    val detail = if (pace != null && !pace.overdue && plan.total - plan.done > 0)
        stringResource(R.string.nb_pace, String.format(Locale.getDefault(), "%.1f", pace.perDay).replace(Regex("[.,]0$"), "")) else null
    val rate = plan.doneSince() / 7.0
    val behind = pace != null && plan.hasHistory() && pace.behind(rate)
    val late = (pace?.overdue == true && plan.total - plan.done > 0) || behind
    Row(
        Modifier.fillMaxWidth().pressable(scaleTo = 0.97f, haptic = false) { picking = true }.clip(RoundedCornerShape(18.dp))
            .background(if (late) DevxColors.Danger.copy(alpha = 0.12f) else tint.copy(alpha = 0.08f)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.CalendarMonth, null, tint = if (late) DevxColors.Danger else tint, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(line, style = DevxType.BodyStrong, color = if (pace == null) DevxColors.TextSecondary else DevxColors.TextPrimary)
            if (detail != null) Text(detail, style = DevxType.Caption, color = DevxColors.TextSecondary)
            if (behind) Text(stringResource(R.string.nb_behind, String.format(Locale.getDefault(), "%.1f", rate).replace(Regex("[.,]0$"), "")), style = DevxType.Caption, color = DevxColors.Danger)
        }
    }
    if (picking) {
        val day = 86_400_000L
        val start = if (info.deadline > 0) info.deadline else StudyLog.today() + 30L
        val state = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = start * day)
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = { state.selectedDateMillis?.let { onDeadline(it / day) }; picking = false }) { Text(stringResource(R.string.dialog_save)) }
            },
            dismissButton = {
                Row {
                    if (info.deadline > 0) TextButton(onClick = { onDeadline(0L); picking = false }) { Text(stringResource(R.string.cd_remove)) }
                    TextButton(onClick = { picking = false }) { Text(stringResource(R.string.dialog_cancel)) }
                }
            },
        ) { DatePicker(state = state) }
    }
}

/** Ways to practise (an exam, explaining it back, the weak spots, today's plan, a study sheet), the weak topics themselves and the last exam. */
@Composable
private fun StudyModes(plan: StudyPlan, tint: Color, onAsk: (String) -> Unit) {
    if (plan.total == 0 && plan.cards.isEmpty()) return
    val modes = listOf(
        Triple(Icons.Outlined.Quiz, R.string.study_mode_exam, R.string.study_prompt_exam),
        Triple(Icons.Outlined.RecordVoiceOver, R.string.study_mode_feynman, R.string.study_prompt_feynman),
        Triple(Icons.Outlined.Today, R.string.study_mode_today, R.string.study_prompt_today),
        Triple(Icons.Outlined.TrackChanges, R.string.study_mode_weak, R.string.study_prompt_weak),
        Triple(Icons.Outlined.Description, R.string.study_mode_notes, R.string.study_prompt_notes),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            modes.forEach { (icon, label, prompt) ->
                val text = stringResource(prompt)
                Row(
                    Modifier.pressable(scaleTo = 0.95f) { onAsk(text) }.clip(CircleShape).border(1.dp, DevxColors.Outline, CircleShape).padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
                    Text(stringResource(label), style = DevxType.Body.copy(fontSize = 14.sp), color = DevxColors.TextPrimary, maxLines = 1)
                }
            }
        }
        val weak = plan.weakSpots(4)
        if (weak.isNotEmpty()) {
            Text(stringResource(R.string.nb_weak_title), style = DevxType.Caption, color = DevxColors.TextSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                weak.forEach { w ->
                    val drill = stringResource(R.string.study_prompt_drill, w.topic)
                    Text(
                        w.topic, style = DevxType.Body.copy(fontSize = 14.sp), color = DevxColors.TextPrimary,
                        modifier = Modifier.pressable(scaleTo = 0.95f) { onAsk(drill) }.clip(CircleShape).background(tint.copy(alpha = 0.16f)).padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
        plan.exams.lastOrNull()?.let { e ->
            Text(stringResource(R.string.nb_exam_last, e.correct, e.total) + " · ${e.percent}%", style = DevxType.Caption, color = DevxColors.TextSecondary)
        }
    }
}

/** How the study goes: the days in a row, and the last seven days as small bars (today on the right). */
@Composable
private fun StudyStats(log: StudyLog, tint: Color) {
    if (log.isEmpty) return
    val streak = log.streak()
    val week = log.week()
    val top = (week.maxOrNull() ?: 0).coerceAtLeast(1)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tint.copy(alpha = 0.08f)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (streak > 0) pluralStringResource(R.plurals.nb_streak, streak, streak) else stringResource(R.string.nb_streak_none),
                style = DevxType.BodyStrong, color = if (streak > 0) tint else DevxColors.TextSecondary,
            )
            Text(stringResource(R.string.nb_week_label), style = DevxType.Caption, color = DevxColors.TextSecondary)
        }
        Row(Modifier.height(36.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
            week.forEachIndexed { i, n ->
                val target = if (n == 0) 0.12f else 0.3f + 0.7f * n / top
                val h by animateFloatAsState(target, spring(dampingRatio = 0.7f, stiffness = 260f), label = "bar")
                Box(
                    Modifier.width(8.dp).fillMaxHeight(h)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (n == 0) DevxColors.Outline else tint.copy(alpha = if (i == week.lastIndex) 1f else 0.6f)),
                )
            }
        }
    }
}

@Composable
private fun PathStep(number: Int, lesson: Lesson, tint: Color, last: Boolean, onClick: () -> Unit) {
    val done = lesson.status == Lesson.DONE
    val doing = lesson.status == Lesson.DOING
    val fill by animateFloatAsState(if (done) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "step")
    Row(Modifier.fillMaxWidth().pressable(scaleTo = 0.98f, haptic = false, onClick = onClick), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(26.dp).clip(CircleShape).background(if (done) tint.copy(alpha = fill) else Color.Transparent)
                    .border(1.8.dp, if (done) Color.Transparent else if (doing) tint else DevxColors.TextDisabled, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (done) Icon(Icons.Filled.Check, null, tint = Color.Black, modifier = Modifier.size(15.dp).graphicsLayer { scaleX = fill; scaleY = fill })
                else if (doing) Box(Modifier.size(10.dp).clip(CircleShape).background(tint))
                else Text("$number", style = DevxType.Caption.copy(fontSize = 11.sp), color = DevxColors.TextDisabled)
            }
            if (!last) Box(Modifier.width(2.dp).height(26.dp).background(if (done) tint.copy(alpha = 0.6f) else DevxColors.Outline))
        }
        Column(Modifier.weight(1f).padding(top = 3.dp, bottom = 10.dp)) {
            Text(lesson.title, style = DevxType.Body, color = if (done) DevxColors.TextSecondary else DevxColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (lesson.note.isNotBlank()) Text(lesson.note, style = DevxType.Caption, color = DevxColors.TextDisabled, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
