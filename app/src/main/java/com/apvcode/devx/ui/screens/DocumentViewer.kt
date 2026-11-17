package com.apvcode.devx.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import com.apvcode.devx.R
import com.apvcode.devx.data.Artifact
import com.apvcode.devx.data.FileText
import com.apvcode.devx.data.LibKind
import com.apvcode.devx.data.libKindOf
import com.apvcode.devx.ui.chat.saveToDownloads
import com.apvcode.devx.ui.components.DevxIcons
import com.apvcode.devx.ui.components.MenuRow
import com.apvcode.devx.ui.components.PopupMenu
import com.apvcode.devx.ui.components.fileIcon
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.Color

/**
 * Any file of the app, opened to be read right here: pictures (pinch to zoom), PDFs (page by page), Word, PowerPoint and Excel documents
 * (their text, laid out as a page, as slides and as a table), text and code, and pages taken from the web. What cannot be shown can be
 * opened by another app. The menu saves it to Downloads, shares it or opens it elsewhere.
 */
@Composable
fun DocumentViewer(
    file: File,
    name: String,
    mime: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    url: String = "",
    page: Int = 0,
) {
    val context = LocalContext.current
    val kind = remember(name, mime, url) { libKindOf(name, mime, null, url) }
    var menu by remember { mutableStateOf(false) }
    val menuButton: @Composable () -> Unit = {
        Box {
            Box(Modifier.size(44.dp).pressable(scaleTo = 0.9f) { menu = true }.clip(CircleShape).background(DevxColors.Card), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.MoreVert, stringResource(R.string.cd_more), tint = DevxColors.TextPrimary, modifier = Modifier.size(22.dp))
            }
            PopupMenu(expanded = menu, onDismiss = { menu = false }, offset = DpOffset((-4).dp, 0.dp)) {
                val artifact = Artifact("view", name, mime, file.length())
                MenuRow(Icons.Outlined.FileDownload, stringResource(R.string.art_download)) { menu = false; saveToDownloads(context, file, artifact) }
                MenuRow(DevxIcons.Share, stringResource(R.string.msg_share)) { menu = false; com.apvcode.devx.ui.chat.shareFile(context, file, artifact) }
                MenuRow(Icons.Outlined.Link, stringResource(R.string.viewer_open_with)) { menu = false; openWithOtherApp(context, file, mime) }
                if (url.isNotEmpty()) MenuRow(Icons.Outlined.Link, stringResource(R.string.viewer_open_site)) {
                    menu = false
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
        }
    }
    when (kind) {
        LibKind.Pdf -> PdfViewer(file, name, onClose, modifier, trailing = menuButton, startPage = page)
        LibKind.Text, LibKind.Code, LibKind.Site -> TextFileViewer(file, name, onClose, modifier, trailing = menuButton)
        LibKind.Image -> ImageViewer(file, name, onClose, modifier, menuButton)
        LibKind.Word, LibKind.Sheet, LibKind.Slides -> OfficeViewer(file, name, kind, onClose, modifier, menuButton, context)
        else -> Column(modifier.fillMaxSize().background(DevxColors.Bg).pointerInput(Unit) { detectTapGestures { } }.statusBarsPadding()) {
            ViewerBar(name, null, onClose, menuButton)
            Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(fileIcon(name), null, tint = DevxColors.TextPrimary, modifier = Modifier.size(64.dp))
                Text(stringResource(R.string.viewer_no_preview), style = DevxType.Body, color = DevxColors.TextSecondary)
            }
        }
    }
}

/** A picture: pinch to zoom, drag when zoomed, double tap to come back. */
@Composable
private fun ImageViewer(file: File, name: String, onClose: () -> Unit, modifier: Modifier, trailing: @Composable () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    Column(modifier.fillMaxSize().background(DevxColors.Bg).statusBarsPadding()) {
        ViewerBar(name, null, onClose, trailing)
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        if (scale > 1f) { offsetX += pan.x; offsetY += pan.y } else { offsetX = 0f; offsetY = 0f }
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offsetX = 0f; offsetY = 0f }) },
        ) {
            AsyncImage(
                model = file, contentDescription = name, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(8.dp).navigationBarsPadding().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offsetX; translationY = offsetY },
            )
        }
    }
}

/** Word, PowerPoint and Excel documents, by their text: a page to read, slides, or a table with a tab for each sheet. */
@Composable
private fun OfficeViewer(file: File, name: String, kind: LibKind, onClose: () -> Unit, modifier: Modifier, trailing: @Composable () -> Unit, context: Context) {
    val ext = name.substringAfterLast('.', "").lowercase()
    val text by produceState<String?>(null, file) {
        value = withContext(Dispatchers.IO) {
            when (ext) {
                "csv", "tsv" -> runCatching { file.readText().take(600_000) }.getOrNull()?.let { "-- Sheet 1\n" + if (ext == "csv") it.lines().joinToString("\n") { l -> l.replace(Regex(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)"), "\t").replace("\"", "") } else it }
                else -> FileText.office(file, ext, 600_000) ?: ""
            }
        }
    }
    Column(modifier.fillMaxSize().background(DevxColors.Bg).pointerInput(Unit) { detectTapGestures { } }.statusBarsPadding()) {
        ViewerBar(name, null, onClose, trailing)
        val body = text
        when {
            body == null -> Unit
            body.isBlank() -> Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(fileIcon(name), null, tint = DevxColors.TextPrimary, modifier = Modifier.size(64.dp))
                Text(stringResource(R.string.viewer_no_preview), style = DevxType.Body, color = DevxColors.TextSecondary)
                Text(
                    stringResource(R.string.viewer_open_with),
                    style = DevxType.BodyStrong, color = DevxColors.OnAccent,
                    modifier = Modifier.pressable(onClick = { openWithOtherApp(context, file, "*/*") }).clip(CircleShape).background(DevxColors.Accent).padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }
            kind == LibKind.Slides -> SlidesBody(body)
            kind == LibKind.Sheet -> SheetBody(body)
            else -> PageBody(body)
        }
    }
}

/** A document as a page: the lines that look like titles are set stronger. */
@Composable
private fun PageBody(text: String) {
    val paragraphs = remember(text) { text.split(Regex("\n+")).map { it.trim() }.filter { it.isNotEmpty() } }
    SelectionContainer(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 28.dp),
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(DevxColors.Card).padding(horizontal = 22.dp, vertical = 24.dp).navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    paragraphs.forEach { p ->
                        val title = p.length in 2..70 && !p.endsWith(".") && !p.endsWith(",") && !p.endsWith(";") && !p.endsWith(":")
                        if (title) Text(p, style = DevxType.Title.copy(fontSize = 19.sp), color = DevxColors.TextPrimary)
                        else Text(p, style = DevxType.Reply, color = DevxColors.TextPrimary)
                    }
                }
            }
        }
    }
}

/** Slides: a card for each, the first line of a slide is its title. */
@Composable
private fun SlidesBody(text: String) {
    val slides = remember(text) {
        text.split(Regex("(?m)^-- Slide \\d+\\s*$")).map { it.trim() }.filter { it.isNotEmpty() }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(slides) { i, slide ->
            val lines = slide.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val shown = remember { MutableTransitionState(false).apply { targetState = true } }
            AnimatedVisibility(visibleState = shown, enter = fadeIn(tween(260, delayMillis = (i * 40).coerceAtMost(400))) + slideInVertically(spring(dampingRatio = 0.85f, stiffness = 380f)) { it / 5 }) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(DevxColors.Card).border(1.dp, DevxColors.Outline, RoundedCornerShape(18.dp)).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("${i + 1}", style = DevxType.Caption, color = DevxColors.TextDisabled)
                    lines.firstOrNull()?.let { Text(it, style = DevxType.Title.copy(fontSize = 19.sp), color = DevxColors.TextPrimary) }
                    lines.drop(1).forEach { Text("•  $it", style = DevxType.Body, color = DevxColors.TextSecondary) }
                }
            }
        }
    }
}

/** A spreadsheet: a tab for every sheet, the first row as the header, the table scrolls sideways. */
@Composable
private fun SheetBody(text: String) {
    val sheets = remember(text) {
        Regex("(?m)^-- Sheet (\\d+)\\s*$").let { re ->
            val marks = re.findAll(text).toList()
            if (marks.isEmpty()) listOf("1" to text)
            else marks.mapIndexed { i, m -> m.groupValues[1] to text.substring(m.range.last + 1, marks.getOrNull(i + 1)?.range?.first ?: text.length).trim() }
        }
    }
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        if (sheets.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sheets.forEachIndexed { i, (n, _) ->
                    Text(
                        stringResource(R.string.viewer_sheet, n), style = DevxType.Caption.copy(fontWeight = FontWeight.Medium),
                        color = if (i == tab) DevxColors.OnAccent else DevxColors.TextSecondary,
                        modifier = Modifier.pressable(scaleTo = 0.95f, haptic = false) { tab = i }.clip(CircleShape).background(if (i == tab) DevxColors.Accent else DevxColors.Card).padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
        val rows = remember(tab, sheets) { sheets.getOrNull(tab)?.second.orEmpty().lines().filter { it.isNotBlank() }.map { it.split('\t') } }
        val columns = remember(rows) { rows.maxOfOrNull { it.size } ?: 0 }
        Box(Modifier.weight(1f).fillMaxWidth().horizontalScroll(rememberScrollState())) {
            LazyColumn(Modifier.width((columns * 132 + 28).dp.coerceAtLeast(300.dp)).navigationBarsPadding(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                itemsIndexed(rows) { r, cells ->
                    Row(Modifier.fillMaxWidth().background(if (r == 0) DevxColors.Card else Color.Transparent).border(0.5.dp, DevxColors.Outline.copy(alpha = 0.6f))) {
                        for (c in 0 until columns) {
                            Text(
                                cells.getOrElse(c) { "" }, style = if (r == 0) DevxType.BodyStrong else DevxType.Body, color = DevxColors.TextPrimary,
                                maxLines = 3, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(132.dp).padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Offers the file to another app (when this one cannot show it well). */
fun openWithOtherApp(context: Context, file: File, mime: String) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime.ifBlank { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(Intent.createChooser(view, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
