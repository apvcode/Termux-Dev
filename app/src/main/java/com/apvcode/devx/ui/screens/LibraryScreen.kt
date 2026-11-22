package com.apvcode.devx.ui.screens

import android.graphics.Bitmap
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.apvcode.devx.R
import com.apvcode.devx.data.Artifact
import com.apvcode.devx.data.LibKind
import com.apvcode.devx.data.LibOrigin
import com.apvcode.devx.data.LibraryEntry
import com.apvcode.devx.data.LibraryUse
import com.apvcode.devx.data.realMime
import com.apvcode.devx.ui.chat.ChatViewModel
import com.apvcode.devx.ui.chat.saveToDownloads
import com.apvcode.devx.ui.components.CircleIconButton
import com.apvcode.devx.ui.components.DevxDialog
import com.apvcode.devx.ui.components.DevxIcons
import com.apvcode.devx.ui.components.MenuRow
import com.apvcode.devx.ui.components.PillButton
import com.apvcode.devx.ui.components.PopupMenu
import com.apvcode.devx.ui.components.PressState
import com.apvcode.devx.ui.components.fileIcon
import com.apvcode.devx.ui.components.glass
import com.apvcode.devx.ui.components.menuOffset
import com.apvcode.devx.ui.components.pressAnchor
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import java.util.Date
import androidx.compose.ui.text.style.TextAlign
import com.apvcode.devx.data.Library
import java.io.File

private enum class Filter(val label: Int) { All(R.string.lib_all), Images(R.string.lib_images), Documents(R.string.lib_documents), Sites(R.string.lib_sites), Made(R.string.lib_made) }

private fun Filter.accepts(e: LibraryEntry): Boolean = when (this) {
    Filter.All -> true
    Filter.Images -> e.kind == LibKind.Image || e.kind == LibKind.Video
    Filter.Documents -> e.kind in setOf(LibKind.Pdf, LibKind.Word, LibKind.Sheet, LibKind.Slides, LibKind.Text, LibKind.Code)
    Filter.Sites -> e.kind == LibKind.Site
    Filter.Made -> e.origin == LibOrigin.Made
}

/**
 * Every file ever attached in any chat or notebook, and every file the assistant made, in one place: searched by name and by what the
 * assistant says it is about, opened right here, downloaded, taken again into a new chat, or deleted for good. A long press on a file
 * shows where it was used, and goes to that very message.
 */
@Composable
fun LibraryScreen(
    vm: ChatViewModel,
    onBack: () -> Unit,
    onOpen: (LibraryEntry) -> Unit,
    onOpenUse: (LibraryUse) -> Unit,
    onAttached: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(Filter.All) }
    var deleting by remember { mutableStateOf<LibraryEntry?>(null) }
    var uses by remember { mutableStateOf<LibraryEntry?>(null) }
    var about by remember { mutableStateOf<LibraryEntry?>(null) }
    // Choosing several files: tap to mark, then delete what is marked in one go.
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var deletingMany by remember { mutableStateOf(false) }
    val all by remember { derivedStateOf { vm.library } }
    val shown = remember(all, query, filter) { Library.search(all.filter { filter.accepts(it) }, query) }
    // Only what is on the screen counts: a file hidden by the search or the filter is never deleted by accident.
    val chosen = remember(shown, selected) { shown.filter { it.id in selected } }
    fun stopSelecting() { selecting = false; selected = emptySet() }
    BackHandler(enabled = selecting) { stopSelecting() }

    Column(Modifier.fillMaxSize().background(DevxColors.Bg).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            CircleIconButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), onBack, Modifier.align(Alignment.CenterStart))
            if (all.isNotEmpty()) Text(
                stringResource(if (selecting) R.string.settings_cancel else R.string.library_select), style = DevxType.Caption.copy(fontWeight = FontWeight.Medium), color = DevxColors.TextPrimary,
                modifier = Modifier.align(Alignment.CenterEnd).pressable(scaleTo = 0.95f, haptic = false) { if (selecting) stopSelecting() else selecting = true }.clip(CircleShape).background(DevxColors.Card).padding(horizontal = 18.dp, vertical = 11.dp),
            )
        }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.library_title), style = DevxType.Large.copy(fontSize = 32.sp, lineHeight = 38.sp), color = DevxColors.TextPrimary)
            Row(
                Modifier.fillMaxWidth().clip(CircleShape).background(DevxColors.Card).padding(horizontal = 18.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(DevxIcons.Search, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(22.dp))
                BasicTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    textStyle = DevxType.Body.copy(color = DevxColors.TextPrimary), cursorBrush = SolidColor(DevxColors.Accent),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner -> Box { if (query.isEmpty()) Text(stringResource(R.string.library_search), style = DevxType.Body, color = DevxColors.TextSecondary); inner() } },
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        // The chips run edge to edge and dissolve at the screen's sides instead of being cut off.
        LazyRow(
            Modifier.fillMaxWidth().fadeEdges(horizontal = true, length = 20.dp),
            contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rowItems(Filter.entries) { f ->
                val on = f == filter
                Box(
                    Modifier.height(38.dp).pressable(scaleTo = 0.95f, haptic = false) { filter = f }.clip(CircleShape)
                        .background(if (on) DevxColors.Accent else DevxColors.Card).padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(f.label), style = DevxType.Caption.copy(fontWeight = FontWeight.Medium), maxLines = 1,
                        color = if (on) DevxColors.OnAccent else DevxColors.TextSecondary,
                    )
                }
            }
        }
        if (shown.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(40.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(DevxColors.Card), contentAlignment = Alignment.Center) { Icon(DevxIcons.Paperclip, null, tint = DevxColors.TextPrimary, modifier = Modifier.size(28.dp)) }
                Text(stringResource(if (all.isEmpty()) R.string.library_empty else R.string.library_nothing), style = DevxType.Body, color = DevxColors.TextSecondary, textAlign = TextAlign.Center)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2), modifier = Modifier.weight(1f).fadeEdges(horizontal = false, length = 16.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(span = { GridItemSpan(2) }) { Text(stringResource(R.string.library_count, shown.size), style = DevxType.Caption, color = DevxColors.TextDisabled, modifier = Modifier.padding(start = 4.dp)) }
                items(shown, key = { it.id }) { e ->
                    LibraryCard(
                        e, vm, Modifier.animateItem(),
                        selecting = selecting, selected = e.id in selected,
                        onToggle = { selected = if (e.id in selected) selected - e.id else selected + e.id },
                        onSelect = { selecting = true; selected = selected + e.id },
                        onOpen = { onOpen(e) },
                        onDownload = { fileOf(vm, e)?.let { f -> saveToDownloads(context, f, Artifact(e.id, e.name, e.realMime(), f.length())) } },
                        onWhere = { if (e.uses.size == 1) onOpenUse(e.uses.first()) else uses = e },
                        onAttach = { if (vm.attachFromLibrary(e)) { Toast.makeText(context, R.string.library_attached, Toast.LENGTH_SHORT).show(); onAttached() } },
                        onAbout = { about = e },
                        onDelete = { deleting = e },
                    )
                }
            }
        }
        if (selecting) Row(
            Modifier.fillMaxWidth().background(DevxColors.Card).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.library_selected, chosen.size), style = DevxType.BodyStrong, color = DevxColors.TextPrimary, modifier = Modifier.weight(1f))
            val everything = shown.isNotEmpty() && chosen.size == shown.size
            Text(
                stringResource(if (everything) R.string.library_select_none else R.string.library_select_all), style = DevxType.Caption.copy(fontWeight = FontWeight.Medium), color = DevxColors.TextPrimary,
                modifier = Modifier.pressable(scaleTo = 0.95f, haptic = false) { selected = if (everything) selected - shown.map { it.id }.toSet() else selected + shown.map { it.id } }.clip(CircleShape).background(DevxColors.CardInner).padding(horizontal = 16.dp, vertical = 10.dp),
            )
            Text(
                stringResource(R.string.library_delete_selected), style = DevxType.Caption.copy(fontWeight = FontWeight.Medium), color = if (chosen.isEmpty()) DevxColors.TextDisabled else Color.White,
                modifier = Modifier.pressable(scaleTo = 0.95f, haptic = false) { if (chosen.isNotEmpty()) deletingMany = true }.clip(CircleShape).background(if (chosen.isEmpty()) DevxColors.CardInner else DevxColors.Danger).padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }

    if (deletingMany && chosen.isNotEmpty()) {
        DevxDialog(stringResource(R.string.library_selected, chosen.size), onDismiss = { deletingMany = false }) {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.library_delete_many_text, chosen.size), style = DevxType.Body, color = DevxColors.TextSecondary)
                PillButton(stringResource(R.string.library_delete), container = DevxColors.Danger, content = Color.White, onClick = {
                    chosen.forEach { vm.deleteLibraryEntry(it) }
                    deletingMany = false
                    stopSelecting()
                })
                PillButton(stringResource(R.string.settings_cancel), filled = false, onClick = { deletingMany = false })
            }
        }
    }
    deleting?.let { e ->
        DevxDialog(e.name, onDismiss = { deleting = null }) {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.library_delete_text), style = DevxType.Body, color = DevxColors.TextSecondary)
                PillButton(stringResource(R.string.library_delete), container = DevxColors.Danger, content = Color.White, onClick = { vm.deleteLibraryEntry(e); deleting = null })
                PillButton(stringResource(R.string.settings_cancel), filled = false, onClick = { deleting = null })
            }
        }
    }
    uses?.let { e ->
        DevxDialog(stringResource(R.string.library_where), onDismiss = { uses = null }) {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                e.uses.take(12).forEach { u ->
                    Column(Modifier.fillMaxWidth().pressable(scaleTo = 0.98f, haptic = false) { uses = null; onOpenUse(u) }.clip(RoundedCornerShape(14.dp)).background(DevxColors.Card).padding(14.dp)) {
                        Text(u.chatTitle, style = DevxType.BodyStrong, color = DevxColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(DateFormat.format("d MMM, HH:mm", Date(u.at)).toString(), style = DevxType.Caption, color = DevxColors.TextSecondary)
                    }
                }
            }
        }
    }
    about?.let { e ->
        DevxDialog(e.name, onDismiss = { about = null }) {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.source_ai_took), style = DevxType.Caption, color = DevxColors.TextSecondary)
                Text(e.summary.ifBlank { stringResource(R.string.source_failed) }, style = DevxType.Body, color = DevxColors.TextPrimary)
                e.attachment?.let { a -> PillButton(stringResource(R.string.source_describe_again), filled = false, onClick = { vm.summarizeFile(a, force = true); about = null }) }
                PillButton(stringResource(R.string.settings_cancel), onClick = { about = null })
            }
        }
    }
}

/** Makes both ends of a scrolling strip dissolve into the background, so what scrolls past the edge is not cut by a hard line. */
private fun Modifier.fadeEdges(horizontal: Boolean, length: androidx.compose.ui.unit.Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val l = length.toPx()
        val total = if (horizontal) size.width else size.height
        if (total <= 2 * l) return@drawWithContent
        val f = l / total
        val mask = Brush.linearGradient(
            0f to Color.Transparent, f to Color.Black, 1f - f to Color.Black, 1f to Color.Transparent,
            start = androidx.compose.ui.geometry.Offset.Zero,
            end = if (horizontal) androidx.compose.ui.geometry.Offset(size.width, 0f) else androidx.compose.ui.geometry.Offset(0f, size.height),
        )
        drawRect(mask, blendMode = BlendMode.DstIn)
    }

private fun fileOf(vm: ChatViewModel, e: LibraryEntry): File? = e.attachment?.let { vm.attachmentFile(it) } ?: e.artifact?.let { vm.artifactFile(it) }

@Composable
private fun LibraryCard(
    e: LibraryEntry, vm: ChatViewModel, modifier: Modifier,
    selecting: Boolean, selected: Boolean, onToggle: () -> Unit, onSelect: () -> Unit,
    onOpen: () -> Unit, onDownload: () -> Unit, onWhere: () -> Unit, onAttach: () -> Unit, onAbout: () -> Unit, onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val press = remember { PressState() }
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    val shape = RoundedCornerShape(22.dp)
    AnimatedVisibility(visibleState = shown, enter = fadeIn(tween(240)) + scaleIn(spring(dampingRatio = 0.8f, stiffness = 420f), initialScale = 0.92f), modifier = modifier) {
        Box(Modifier.pressAnchor(press)) {
            Column(
                Modifier.fillMaxWidth().pressable(scaleTo = 0.97f, haptic = false, onLongClick = { if (selecting) onToggle() else menu = true }, onClick = { if (selecting) onToggle() else onOpen() }).clip(shape).background(DevxColors.Card)
                    .then(if (selected) Modifier.border(2.dp, DevxColors.Accent, shape) else Modifier),
            ) {
                Box(Modifier.fillMaxWidth().aspectRatio(1.25f).background(DevxColors.CardInner), contentAlignment = Alignment.Center) {
                    LibraryThumb(e, vm)
                    if (e.uses.size > 1) Text(
                        "${e.uses.size}", style = DevxType.Caption.copy(fontSize = 11.sp), color = DevxColors.TextPrimary,
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                    if (selecting) Box(
                        Modifier.align(Alignment.TopStart).padding(8.dp).size(24.dp).clip(CircleShape)
                            .background(if (selected) DevxColors.Accent else Color.Black.copy(alpha = 0.45f))
                            .border(1.5.dp, if (selected) DevxColors.Accent else Color.White.copy(alpha = 0.8f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { if (selected) Icon(DevxIcons.Check, null, tint = DevxColors.OnAccent, modifier = Modifier.size(15.dp)) }
                    else if (e.origin == LibOrigin.Made) Icon(DevxIcons.Brain, null, tint = DevxColors.TextPrimary, modifier = Modifier.align(Alignment.TopStart).padding(8.dp).size(18.dp))
                }
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(e.name, style = DevxType.BodyStrong.copy(fontSize = 14.sp, lineHeight = 19.sp), color = DevxColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val state = vm.libraryMeta.states[e.id]
                    when {
                        e.summary.isNotBlank() -> Text(e.summary, style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        state == "pending" -> Shimmer()
                        else -> Text(DateFormat.format("d MMM", Date(e.at)).toString() + " · " + sizeText(e.size), style = DevxType.Caption, color = DevxColors.TextDisabled)
                    }
                }
            }
            PopupMenu(expanded = menu, onDismiss = { menu = false }, offset = press.menuOffset()) {
                MenuRow(DevxIcons.Eye, stringResource(R.string.source_open)) { menu = false; onOpen() }
                MenuRow(DevxIcons.ListCheck, stringResource(R.string.library_select)) { menu = false; onSelect() }
                MenuRow(Icons.Outlined.FileDownload, stringResource(R.string.art_download)) { menu = false; onDownload() }
                if (e.uses.isNotEmpty()) MenuRow(DevxIcons.ChatSearch, stringResource(R.string.library_open_chat)) { menu = false; onWhere() }
                if (e.attachment != null) MenuRow(DevxIcons.Paperclip, stringResource(R.string.library_attach)) { menu = false; onAttach() }
                MenuRow(DevxIcons.ChatOff, stringResource(R.string.library_delete), danger = true) { menu = false; onDelete() }
            }
        }
    }
}

/** What a card shows: the picture itself, the first page of a PDF, or the icon of the kind of file. */
@Composable
private fun LibraryThumb(e: LibraryEntry, vm: ChatViewModel) {
    val file = remember(e.id) { fileOf(vm, e) }
    when {
        e.kind == LibKind.Image && file != null -> AsyncImage(model = file, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        e.kind == LibKind.Video && e.attachment != null -> AsyncImage(model = vm.attachmentThumb(e.attachment), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        e.kind == LibKind.Pdf && file != null -> {
            val page by produceState<Bitmap?>(null, file) { value = pdfFirstPage(file, 420) }
            val bmp = page
            if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
            else Icon(fileIcon(e.name), null, tint = DevxColors.TextSecondary, modifier = Modifier.size(40.dp))
        }
        else -> {
            val tint = when (e.kind) {
                LibKind.Word -> Color(0xFF6C9BE8)
                LibKind.Sheet -> Color(0xFF6FBF8E)
                LibKind.Slides -> Color(0xFFE8A05C)
                LibKind.Site -> Color(0xFF5FB3C4)
                else -> DevxColors.TextSecondary
            }
            Box(Modifier.fillMaxSize().background(tint.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
                Icon(if (e.kind == LibKind.Site) Icons.Outlined.Link else fileIcon(e.name), null, tint = tint, modifier = Modifier.size(44.dp))
            }
        }
    }
}

private fun sizeText(bytes: Long): String = if (bytes >= 1_048_576) "%.1f MB".format(bytes / 1_048_576.0) else "${(bytes + 1023) / 1024} KB"
