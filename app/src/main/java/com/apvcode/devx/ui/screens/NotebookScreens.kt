package com.apvcode.devx.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.apvcode.devx.R
import com.apvcode.devx.data.Attachment
import com.apvcode.devx.data.AttachmentKind
import com.apvcode.devx.data.Lesson
import com.apvcode.devx.data.ProjectInfo
import com.apvcode.devx.ui.chat.CameraPanel
import com.apvcode.devx.ui.chat.ChatViewModel
import com.apvcode.devx.ui.components.CircleIconButton
import com.apvcode.devx.ui.components.DevxDialog
import com.apvcode.devx.ui.components.DevxIcons
import com.apvcode.devx.ui.components.DevxTextField
import com.apvcode.devx.ui.components.MenuRow
import com.apvcode.devx.ui.components.PillButton
import com.apvcode.devx.ui.components.PopupMenu
import com.apvcode.devx.ui.components.PressState
import com.apvcode.devx.ui.components.SettingsRow
import com.apvcode.devx.ui.components.SettingsSwitchRow
import com.apvcode.devx.ui.components.fileIcon
import com.apvcode.devx.ui.components.glass
import com.apvcode.devx.ui.components.menuOffset
import com.apvcode.devx.ui.components.pressAnchor
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxFonts
import com.apvcode.devx.ui.theme.DevxType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ───────────────────────── a new notebook ─────────────────────────

/**
 * Making a notebook: what it is about, and what it is for. One question that changes with the choice, a big quiet field to answer it,
 * and two cards: organise ideas (chats grouped by topic, your own sources) or learn (a goal, lessons, progress).
 */
@Composable
fun NotebookNewScreen(vm: ChatViewModel, onClose: () -> Unit, onCreated: (String, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var study by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    var taken by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(250); runCatching { focus.requestFocus() } }
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(DevxColors.Bg).statusBarsPadding().imePadding().navigationBarsPadding()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.padding(top = 12.dp)) { CircleIconButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), onClose) }
            Icon(DevxIcons.Notebook, null, tint = DevxColors.TextPrimary, modifier = Modifier.size(30.dp))
            AnimatedContent(
                targetState = study,
                transitionSpec = { (fadeIn(tween(260, delayMillis = 80)) + slideInVertically(tween(300)) { it / 3 }) togetherWith (fadeOut(tween(140))) },
                label = "notebook-question",
            ) { s ->
                Text(stringResource(if (s) R.string.notebook_what_learning else R.string.notebook_what_working), style = DevxType.Title.copy(fontSize = 24.sp, lineHeight = 30.sp), color = DevxColors.TextPrimary)
            }
            BasicTextField(
                value = name,
                onValueChange = { name = it.take(60); taken = false },
                singleLine = true,
                textStyle = DevxType.Large.copy(fontSize = 38.sp, lineHeight = 46.sp, color = DevxColors.TextPrimary),
                cursorBrush = SolidColor(DevxColors.Accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                decorationBox = { inner ->
                    Box {
                        if (name.isEmpty()) Text(stringResource(R.string.notebook_name_hint), style = DevxType.Large.copy(fontSize = 38.sp, lineHeight = 46.sp), color = DevxColors.TextDisabled.copy(alpha = 0.7f))
                        inner()
                    }
                },
            )
            if (taken) Text(stringResource(R.string.notebook_name_taken), style = DevxType.Caption, color = DevxColors.Warning)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KindCard(R.string.notebook_kind_ideas, R.string.notebook_kind_ideas_hint, "bulb", !study, { study = false }, Modifier.weight(1f))
                KindCard(R.string.notebook_kind_study, R.string.notebook_kind_study_hint, "school", study, { study = true }, Modifier.weight(1f))
            }
            Spacer(Modifier.weight(1f))
            PillButton(
                stringResource(R.string.notebook_create),
                enabled = name.isNotBlank(),
                modifier = Modifier.padding(bottom = 16.dp),
                onClick = {
                    val ok = vm.createProject(name, icon = if (study) "school" else "bulb", color = ProjectColorFor(study), kind = if (study) ProjectInfo.KIND_STUDY else ProjectInfo.KIND_IDEAS)
                    if (ok) onCreated(name.trim(), study) else taken = true
                },
            )
        }
    }
}

private fun ProjectColorFor(study: Boolean): Long = if (study) 0xFF6C9BE8L else 0xFFE8A05CL

/** A choice card: it lights up and opens its line of explanation when chosen, and a tick springs into its corner. */
@Composable
private fun KindCard(title: Int, hint: Int, icon: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    val bg by androidx.compose.animation.animateColorAsState(if (selected) Color(0xFF1F2A55) else DevxColors.Card, tween(260), label = "kind-bg")
    val border by androidx.compose.animation.animateColorAsState(if (selected) Color(0xFF5A74D6).copy(alpha = 0.7f) else Color.Transparent, tween(260), label = "kind-border")
    Column(
        modifier.pressable(scaleTo = 0.97f, onClick = onClick).clip(shape).background(bg).border(1.5.dp, border, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(projectIcon(icon), null, tint = DevxColors.TextPrimary, modifier = Modifier.size(26.dp))
            Spacer(Modifier.weight(1f))
            AnimatedVisibility(selected, enter = scaleIn(spring(dampingRatio = 0.45f, stiffness = 500f), initialScale = 0.2f) + fadeIn(), exit = scaleOut(tween(140)) + fadeOut(tween(120))) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, null, tint = Color.Black, modifier = Modifier.size(16.dp))
                }
            }
        }
        Text(stringResource(title), style = DevxType.BodyStrong, color = DevxColors.TextPrimary)
        AnimatedVisibility(selected, enter = fadeIn(tween(220)) + expandVertically(spring(dampingRatio = 0.85f, stiffness = 420f)), exit = fadeOut(tween(100)) + shrinkVertically(tween(160))) {
            Text(stringResource(hint), style = DevxType.Caption, color = Color(0xFFB4BEE8))
        }
    }
}

/** The dialog that asks for the goal of the lessons. */
@Composable
fun GoalDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    DevxDialog(stringResource(R.string.notebook_goal_dialog), onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            DevxTextField(stringResource(R.string.notebook_goal), text, { text = it }, placeholder = stringResource(R.string.notebook_goal_hint), singleLine = false, minLines = 3)
            PillButton(stringResource(R.string.task_save), enabled = text.isNotBlank(), onClick = { onSave(text.trim()); onDismiss() })
            PillButton(stringResource(R.string.settings_cancel), filled = false, onClick = onDismiss)
        }
    }
}

// ───────────────────────── the sources ─────────────────────────

/** The files, photos and pages a notebook works from: each with what the assistant says it is about. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(vm: ChatViewModel, name: String, onBack: () -> Unit, onOpen: (Attachment) -> Unit) {
    val info = vm.projectInfo[name]
    if (info == null) { LaunchedEffect(Unit) { onBack() }; return }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var addOpen by remember { mutableStateOf(false) }
    var siteOpen by remember { mutableStateOf(false) }
    var cameraOpen by remember { mutableStateOf(false) }
    var aboutSource by remember { mutableStateOf<Attachment?>(null) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { vm.addSources(name, it) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addSources(name, it) }
    BackHandler(enabled = cameraOpen) { cameraOpen = false }

    Box(Modifier.fillMaxSize().background(DevxColors.Bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                CircleIconButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), onBack, Modifier.align(Alignment.CenterStart))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.notebook_sources), style = DevxType.Large.copy(fontSize = 32.sp, lineHeight = 38.sp), color = DevxColors.TextPrimary)
                    Text(stringResource(R.string.notebook_sources_hint), style = DevxType.Body, color = DevxColors.TextSecondary)
                }
                Box(Modifier.padding(start = 12.dp).size(52.dp).pressable(scaleTo = 0.9f) { addOpen = true }.clip(CircleShape).background(Color(0xFF2B3F9E)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Add, stringResource(R.string.notebook_add_source), tint = Color.White, modifier = Modifier.size(26.dp))
                }
            }
            if (info.sources.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(40.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(64.dp).clip(CircleShape).background(DevxColors.Card), contentAlignment = Alignment.Center) {
                        Icon(DevxIcons.Paperclip, null, tint = DevxColors.TextPrimary, modifier = Modifier.size(28.dp))
                    }
                    Text(stringResource(R.string.notebook_sources_empty), style = DevxType.Body, color = DevxColors.TextSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            } else {
                LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(info.sources.toList(), key = { _, a -> a.id }) { i, a ->
                        SourceRow(
                            a, vm, delayMs = (i * 45).coerceAtMost(360), modifier = Modifier.animateItem(),
                            onOpen = { onOpen(a) }, onAbout = { aboutSource = a },
                            onDescribeAgain = { vm.summarizeFile(a, force = true) }, onRemove = { vm.removeSource(name, a.id) },
                        )
                    }
                }
            }
        }
        CameraPanel(
            open = cameraOpen, onClose = { cameraOpen = false },
            onCaptured = { cameraOpen = false; vm.addSources(name, listOf(it)) },
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.62f),
        )
    }

    if (addOpen) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { addOpen = false }, sheetState = sheet, containerColor = DevxColors.Drawer, contentColor = DevxColors.TextPrimary, scrimColor = Color.Black.copy(alpha = 0.6f)) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
                SheetRow(DevxIcons.Camera, R.string.src_camera) { addOpen = false; cameraOpen = true }
                SheetRow(DevxIcons.Photo, R.string.src_gallery) { addOpen = false; gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
                SheetRow(DevxIcons.Paperclip, R.string.src_files) { addOpen = false; files.launch(arrayOf("*/*")) }
                SheetRow(Icons.Outlined.Link, R.string.src_site) { addOpen = false; siteOpen = true }
            }
        }
    }
    if (siteOpen) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var text by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        ModalBottomSheet(onDismissRequest = { if (!busy) siteOpen = false }, sheetState = sheet, containerColor = DevxColors.Drawer, contentColor = DevxColors.TextPrimary, scrimColor = Color.Black.copy(alpha = 0.6f)) {
            Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircleIconButton(Icons.Outlined.Close, stringResource(R.string.cd_close), { if (!busy) siteOpen = false })
                    Text(stringResource(R.string.site_title), style = DevxType.BodyStrong, color = DevxColors.TextPrimary, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Box(
                        Modifier.size(44.dp).pressable(enabled = text.isNotBlank() && !busy, scaleTo = 0.9f) {
                            busy = true
                            scope.launch {
                                val failed = vm.addSites(name, text.split(Regex("\\s+")).filter { it.isNotBlank() })
                                busy = false
                                if (failed > 0) Toast.makeText(context, context.getString(R.string.site_failed, failed), Toast.LENGTH_LONG).show()
                                if (failed == 0 || failed < text.split(Regex("\\s+")).count { it.isNotBlank() }) siteOpen = false
                            }
                        }.clip(CircleShape).background(if (text.isNotBlank()) Color(0xFF2B3F9E) else DevxColors.Card),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (busy) Box(Modifier.size(18.dp)) { com.apvcode.devx.ui.chat.ThinkingSparkle() }
                        else Icon(Icons.Outlined.Check, stringResource(R.string.task_save), tint = if (text.isNotBlank()) Color.White else DevxColors.TextDisabled, modifier = Modifier.size(22.dp))
                    }
                }
                Text(stringResource(R.string.site_hint), style = DevxType.Body, color = DevxColors.TextPrimary)
                BasicTextField(
                    value = text, onValueChange = { text = it }, enabled = !busy, minLines = 4,
                    textStyle = DevxType.Body.copy(color = DevxColors.TextPrimary), cursorBrush = SolidColor(DevxColors.Accent),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, DevxColors.Outline, RoundedCornerShape(8.dp)),
                    decorationBox = { inner -> Box(Modifier.padding(14.dp)) { if (text.isEmpty()) Text("https://example.com", style = DevxType.Body, color = DevxColors.TextDisabled); inner() } },
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(R.string.site_tip_many, R.string.site_tip_text, R.string.site_tip_paid).forEach { Text("•  " + stringResource(it), style = DevxType.Caption, color = DevxColors.TextSecondary) }
                }
            }
        }
    }
    aboutSource?.let { a ->
        val summary = vm.libraryMeta.summaries[a.id]
        DevxDialog(a.name, onDismiss = { aboutSource = null }) {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.source_ai_took), style = DevxType.Caption, color = DevxColors.TextSecondary)
                Text(summary ?: stringResource(R.string.source_failed), style = DevxType.Body, color = DevxColors.TextPrimary)
                PillButton(stringResource(R.string.source_describe_again), filled = false, onClick = { vm.summarizeFile(a, force = true); aboutSource = null })
                PillButton(stringResource(R.string.settings_cancel), onClick = { aboutSource = null })
            }
        }
    }
}

@Composable
private fun SheetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().pressable(scaleTo = 0.98f, haptic = false, onClick = onClick).padding(horizontal = 28.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        Icon(icon, null, tint = DevxColors.TextPrimary, modifier = Modifier.size(24.dp))
        Text(stringResource(label), style = DevxType.Body.copy(fontSize = 18.sp), color = DevxColors.TextPrimary)
    }
}

/** One source: its picture or icon, its name, and a line about what it is (a light sweeps over the line while it is being read). */
@Composable
private fun SourceRow(
    a: Attachment, vm: ChatViewModel, delayMs: Int, modifier: Modifier,
    onOpen: () -> Unit, onAbout: () -> Unit, onDescribeAgain: () -> Unit, onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val press = remember { PressState() }
    val shown = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
    val summary = vm.libraryMeta.summaries[a.id]
    val state = vm.libraryMeta.states[a.id]
    val shape = RoundedCornerShape(20.dp)
    AnimatedVisibility(visibleState = shown, enter = fadeIn(tween(260, delayMillis = delayMs)) + slideInVertically(spring(dampingRatio = 0.8f, stiffness = 380f)) { it / 4 }, modifier = modifier) {
        Box(Modifier.pressAnchor(press)) {
            Row(
                Modifier.fillMaxWidth()
                    .pressable(scaleTo = 0.98f, haptic = false, onLongClick = { menu = true }, onClick = onOpen)
                    .clip(shape).background(DevxColors.Card).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SourceThumb(a, vm)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(a.name, style = DevxType.BodyStrong, color = DevxColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    when {
                        summary != null -> Text(summary, style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        state == "failed" -> Text(stringResource(R.string.source_failed), style = DevxType.Caption, color = DevxColors.TextDisabled)
                        else -> Shimmer()
                    }
                }
            }
            PopupMenu(expanded = menu, onDismiss = { menu = false }, offset = press.menuOffset()) {
                MenuRow(DevxIcons.Eye, stringResource(R.string.source_open)) { menu = false; onOpen() }
                MenuRow(DevxIcons.Refresh, stringResource(R.string.source_describe_again)) { menu = false; onDescribeAgain() }
                MenuRow(DevxIcons.ChatOff, stringResource(R.string.source_remove), danger = true) { menu = false; onRemove() }
            }
        }
    }
}

/** What is not written yet: two quiet bars with a light moving over them. */
@Composable
fun Shimmer(modifier: Modifier = Modifier) {
    val phase by rememberInfiniteTransition(label = "shimmer").animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "shimmer-phase")
    val sweep = androidx.compose.ui.graphics.Brush.horizontalGradient(
        listOf(DevxColors.Outline.copy(alpha = 0.5f), DevxColors.TextDisabled.copy(alpha = 0.55f), DevxColors.Outline.copy(alpha = 0.5f)),
        startX = -200f + phase * 900f, endX = 100f + phase * 900f,
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth(0.9f).height(9.dp).clip(CircleShape).background(sweep))
        Box(Modifier.fillMaxWidth(0.55f).height(9.dp).clip(CircleShape).background(sweep))
    }
}

/** The small picture of a source: the photo itself, or the icon of the file's kind. */
@Composable
fun SourceThumb(a: Attachment, vm: ChatViewModel, size: androidx.compose.ui.unit.Dp = 52.dp) {
    val shape = RoundedCornerShape(14.dp)
    Box(Modifier.size(size).clip(shape).background(DevxColors.CardInner), contentAlignment = Alignment.Center) {
        if (a.kind == AttachmentKind.Image) {
            AsyncImage(model = vm.attachmentFile(a), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(if (a.url.isNotEmpty()) Icons.Outlined.Link else fileIcon(a.name), null, tint = DevxColors.TextPrimary, modifier = Modifier.size(size * 0.5f))
        }
    }
}

// ───────────────────────── the settings of a notebook ─────────────────────────

@Composable
fun NotebookSettingsScreen(vm: ChatViewModel, name: String, onBack: () -> Unit, onSources: () -> Unit) {
    val info = vm.projectInfo[name]
    if (info == null) { LaunchedEffect(Unit) { onBack() }; return }
    var goalDialog by remember { mutableStateOf(false) }
    var text by remember(name) { mutableStateOf(info.instructions) }
    if (goalDialog) GoalDialog(info.goal, { goalDialog = false }) { vm.setNotebookGoal(name, it) }
    Column(Modifier.fillMaxSize().background(DevxColors.Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            CircleIconButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), onBack, Modifier.align(Alignment.CenterStart))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.notebook_settings), style = DevxType.Large.copy(fontSize = 32.sp, lineHeight = 38.sp), color = DevxColors.TextPrimary)
            SettingsSwitchRow(
                title = stringResource(R.string.notebook_use_notes),
                subtitle = stringResource(R.string.notebook_use_notes_hint),
                checked = info.useNotes,
                onCheckedChange = { vm.setNotebookUseNotes(name, it) },
            )
            if (info.isStudy) {
                SettingsRow(title = stringResource(R.string.notebook_goal), value = info.goal.ifBlank { stringResource(R.string.settings_twogis_key_none) }, showChevron = true, onClick = { goalDialog = true })
            }
            SettingsRow(title = stringResource(R.string.notebook_sources), value = "${info.sources.size}", showChevron = true, onClick = onSources)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.project_instructions), style = DevxType.BodyStrong, color = DevxColors.TextPrimary)
                Text(stringResource(R.string.notebook_instructions_hint), style = DevxType.Caption, color = DevxColors.TextSecondary)
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it; vm.setProjectInstructions(name, it) },
                textStyle = DevxType.Body.copy(color = DevxColors.TextPrimary),
                cursorBrush = SolidColor(DevxColors.Accent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp).clip(RoundedCornerShape(24.dp)).background(DevxColors.Card),
                decorationBox = { inner ->
                    Box(Modifier.padding(20.dp)) {
                        if (text.isEmpty()) Text(stringResource(R.string.notebook_instructions_placeholder), style = DevxType.Body, color = DevxColors.TextDisabled)
                        inner()
                    }
                },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
