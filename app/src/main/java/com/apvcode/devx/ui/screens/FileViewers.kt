package com.apvcode.devx.ui.screens

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apvcode.devx.R
import com.apvcode.devx.ui.components.CircleIconButton
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_LINES = 5_000
private const val MAX_LINE = 2_000
private const val MAX_READ = 1_000_000

/** The header every viewer has: a round back button and the file's name. */
@Composable
fun ViewerBar(name: String, caption: String?, onClose: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        CircleIconButton(
            icon = Icons.AutoMirrored.Outlined.ArrowBack,
            contentDescription = stringResource(R.string.cd_back),
            onClick = onClose,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        if (trailing != null) Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
        Column(Modifier.padding(horizontal = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(name, style = DevxType.Title, color = DevxColors.TextPrimary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (caption != null) Text(caption, style = DevxType.Caption, color = DevxColors.TextSecondary)
        }
    }
}

/** A text or code file: monospace, with line numbers, scrolling both ways, words selectable. */
@Composable
fun TextFileViewer(file: File, name: String, onClose: () -> Unit, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val lines by produceState<List<String>?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                file.inputStream().use { it.readNBytes(MAX_READ) }.toString(Charsets.UTF_8).lines().map { it.take(MAX_LINE) }
            }.getOrNull()
        }
    }
    Column(modifier.fillMaxSize().background(DevxColors.Bg).pointerInput(Unit) { detectTapGestures { } }.statusBarsPadding()) {
        ViewerBar(name, lines?.let { "${it.size}" }, onClose, trailing)
        val all = lines
        when {
            all == null -> Unit
            all.isEmpty() -> Text(stringResource(R.string.viewer_failed), style = DevxType.Body, color = DevxColors.TextSecondary, modifier = Modifier.padding(24.dp))
            else -> {
                val shown = all.take(MAX_LINES)
                val digits = shown.size.toString().length
                val code = DevxType.Mono.copy(fontSize = 13.sp, lineHeight = 20.sp, fontFamily = FontFamily.Monospace)
                SelectionContainer(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                        LazyColumn(Modifier.widthIn(min = 360.dp).navigationBarsPadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                            itemsIndexed(shown) { i, line ->
                                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Text((i + 1).toString().padStart(digits), style = code, color = DevxColors.TextDisabled)
                                    Text(line.ifEmpty { " " }, style = code, color = DevxColors.TextPrimary, softWrap = false)
                                }
                            }
                            if (all.size > MAX_LINES) item {
                                Text(stringResource(R.string.viewer_lines_cut, MAX_LINES), style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = Modifier.padding(top = 12.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The pages of a PDF, one under another, drawn as they come into view. */
@Composable
fun PdfViewer(file: File, name: String, onClose: () -> Unit, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null, startPage: Int = 0) {
    val doc = remember(file) { PdfDoc(file) }
    DisposableEffect(doc) { onDispose { doc.close() } }
    // Opened from a citation, the PDF starts at the page that was named (counted from 1).
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (startPage - 1).coerceAtLeast(0).coerceAtMost((doc.pages - 1).coerceAtLeast(0)))
    val current by remember { derivedStateOf { list.firstVisibleItemIndex + 1 } }
    Column(modifier.fillMaxSize().background(DevxColors.Bg).pointerInput(Unit) { detectTapGestures { } }.statusBarsPadding()) {
        ViewerBar(name, if (doc.pages > 0) stringResource(R.string.viewer_pdf_page, current.coerceAtMost(doc.pages), doc.pages) else null, onClose, trailing)
        if (doc.pages == 0) {
            Text(stringResource(R.string.viewer_failed), style = DevxType.Body, color = DevxColors.TextSecondary, modifier = Modifier.padding(24.dp))
        } else {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val widthPx = with(LocalDensity.current) { (maxWidth - 24.dp).roundToPx() }.coerceIn(400, 1600)
                LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(doc.pages) { index ->
                        val page by produceState<Bitmap?>(null, index, widthPx) { value = doc.render(index, widthPx) }
                        val bitmap = page
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(bitmap?.let { it.width.toFloat() / it.height } ?: 0.707f).clip(RoundedCornerShape(6.dp)).background(androidx.compose.ui.graphics.Color.White),
                        ) {
                            if (bitmap != null) Image(bitmap.asImageBitmap(), null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

/** A PDF open for reading: only one page can be drawn at a time, so the drawing takes turns. */
private class PdfDoc(file: File) {
    private val fd: ParcelFileDescriptor? = runCatching { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull()
    private val renderer: PdfRenderer? = fd?.let { runCatching { PdfRenderer(it) }.getOrNull() }
    private val turn = Mutex()
    val pages: Int = renderer?.pageCount ?: 0

    suspend fun render(index: Int, width: Int): Bitmap? = withContext(Dispatchers.IO) {
        val r = renderer ?: return@withContext null
        turn.withLock {
            runCatching {
                r.openPage(index).use { p ->
                    val height = (width.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(AndroidColor.WHITE)
                    p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }.getOrNull()
        }
    }

    fun close() {
        // Wait for a page that is being drawn: closing under it would crash.
        kotlinx.coroutines.runBlocking {
            turn.withLock {
                runCatching { renderer?.close() }
                runCatching { fd?.close() }
            }
        }
    }
}

/** The first page of a PDF as a small picture (for the library's cards); null when it cannot be drawn. */
suspend fun pdfFirstPage(file: File, width: Int): Bitmap? {
    val doc = PdfDoc(file)
    return try { doc.render(0, width) } finally { doc.close() }
}
