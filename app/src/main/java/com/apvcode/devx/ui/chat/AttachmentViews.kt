package com.apvcode.devx.ui.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.apvcode.devx.R
import com.apvcode.devx.data.Attachment
import com.apvcode.devx.data.AttachmentKind
import com.apvcode.devx.ui.components.fileIcon
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.layout.IntrinsicSize

private fun Attachment.isMedia() = kind == AttachmentKind.Image || kind == AttachmentKind.Video

/** Pictures, videos, PDFs and text can be looked at; any other file only has a name. */
internal fun Attachment.canPreview() = kind != AttachmentKind.File

private fun duration(ms: Long): String {
    val total = (ms / 1000).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

/** A picture or a video's still, rounded; a video gets a play mark and its length. */
@Composable
internal fun MediaTile(
    a: Attachment,
    preview: File,
    modifier: Modifier,
    radius: Dp = 22.dp,
    more: Int = 0,
    onClick: (() -> Unit)? = null,
) {
    val base = if (onClick != null) modifier.pressable(scaleTo = 0.97f, onClick = onClick) else modifier
    Box(base.clip(RoundedCornerShape(radius)).background(DevxColors.CardInner)) {
        AsyncImage(model = preview, contentDescription = a.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (a.kind == AttachmentKind.Video) {
            Box(
                Modifier.align(Alignment.Center).size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            if (a.durationMs > 0) {
                Text(
                    duration(a.durationMs),
                    style = DevxType.Caption,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        if (more > 0) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                Text("+$more", style = DevxType.Title, color = Color.White)
            }
        }
    }
}

/** Words quoted from a message, shown the way a reply shows them: a bar at the side and the first lines. */
@Composable
internal fun QuoteCard(file: File, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val text by produceState("", file) {
        value = withContext(Dispatchers.IO) { runCatching { file.readText().replace(Regex("\\s+"), " ").trim().take(400) }.getOrDefault("") }
    }
    val tappable = if (onClick != null) modifier.pressable(scaleTo = 0.97f, onClick = onClick) else modifier
    Row(
        tappable
            .clip(RoundedCornerShape(16.dp))
            .background(DevxColors.CardInner)
            .padding(start = 12.dp)
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .padding(vertical = 10.dp)
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(DevxColors.TextSecondary),
        )
        Text(
            text,
            style = DevxType.Caption.copy(lineHeight = 19.sp),
            color = DevxColors.TextSecondary,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).padding(top = 10.dp, bottom = 10.dp, end = 14.dp),
        )
    }
}

@Composable
internal fun FileChip(a: Attachment, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val tappable = if (onClick != null) modifier.pressable(scaleTo = 0.97f, onClick = onClick) else modifier
    Row(
        tappable.clip(RoundedCornerShape(18.dp)).background(DevxColors.CardInner).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(fileIcon(a.name), null, tint = DevxColors.TextSecondary, modifier = Modifier.size(26.dp))
        Text(a.name, style = DevxType.Caption, color = DevxColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
    }
}

/** Width over height of a picture, read from its header only; a guess until then. */
@Composable
private fun rememberAspect(file: File): Float {
    val aspect by produceState(0.75f, file) {
        value = withContext(Dispatchers.IO) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }
            if (options.outWidth > 0 && options.outHeight > 0) options.outWidth.toFloat() / options.outHeight else 0.75f
        }
    }
    return aspect.coerceIn(0.62f, 1.5f)
}

/**
 * The photos and videos of one of my messages: big, right in the chat, above the text bubble.
 * One fills most of the width in its own proportions, two stand side by side, more make a grid.
 * Files (text, PDF) go under them as chips.
 */
@Composable
fun MessageMedia(
    attachments: List<Attachment>,
    previewOf: (Attachment) -> File,
    onOpen: (Attachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    val media = attachments.filter { it.isMedia() }
    val files = attachments.filter { !it.isMedia() && !it.isQuote }
    val quotes = attachments.filter { it.isQuote }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        quotes.forEach { q -> QuoteCard(previewOf(q), Modifier.widthIn(max = 300.dp), onClick = { onOpen(q) }) }
        if (media.isNotEmpty()) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val gap = 6.dp
                val half = (maxWidth - gap) / 2
                val shown = media.take(6)
                val rest = media.size - shown.size
                when (shown.size) {
                    1 -> {
                        val a = shown[0]
                        val aspect = rememberAspect(previewOf(a))
                        MediaTile(
                            a, previewOf(a),
                            Modifier.align(Alignment.CenterEnd).width(maxWidth * 0.78f).aspectRatio(aspect),
                            onClick = { onOpen(a) },
                        )
                    }
                    2 -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        shown.forEach { a ->
                            MediaTile(a, previewOf(a), Modifier.weight(1f).aspectRatio(0.74f), onClick = { onOpen(a) })
                        }
                    }
                    else -> Column(verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = Alignment.End) {
                        shown.chunked(2).forEachIndexed { row, pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                pair.forEachIndexed { column, a ->
                                    val last = row * 2 + column == shown.lastIndex
                                    MediaTile(
                                        a, previewOf(a), Modifier.width(half).aspectRatio(1f),
                                        more = if (last && rest > 0) rest + 1 else 0,
                                        onClick = { onOpen(a) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        files.forEach { f -> FileChip(f, onClick = if (f.canPreview()) ({ onOpen(f) }) else null) }
    }
}

/**
 * What is about to be sent, inside the composer's card above the text: pictures and video stills as
 * tiles, files as cards, each with a cross to take it off; a spinner while something is still being copied.
 */
@Composable
fun ComposerAttachments(
    attachments: List<Attachment>,
    importing: Int,
    previewOf: (Attachment) -> File,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpen: (Attachment) -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { a ->
            Box {
                if (a.isMedia()) {
                    MediaTile(a, previewOf(a), Modifier.width(92.dp).height(116.dp), radius = 18.dp, onClick = { onOpen(a) })
                } else if (a.isQuote) {
                    QuoteCard(previewOf(a), Modifier.width(230.dp).padding(end = 30.dp), onClick = { onOpen(a) })
                } else {
                    Column(
                        Modifier
                            .width(140.dp)
                            .height(116.dp)
                            .then(if (a.canPreview()) Modifier.pressable(scaleTo = 0.97f) { onOpen(a) } else Modifier)
                            .clip(RoundedCornerShape(18.dp))
                            .background(DevxColors.CardInner)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Icon(fileIcon(a.name), null, tint = DevxColors.TextPrimary, modifier = Modifier.size(34.dp))
                        Text(a.name, style = DevxType.Caption, color = DevxColors.TextPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .size(26.dp)
                        .pressable { onRemove(a.id) }
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.72f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.cd_remove), tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
        repeat(importing) {
            Box(Modifier.width(92.dp).height(116.dp).clip(RoundedCornerShape(18.dp)).background(DevxColors.CardInner), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), color = DevxColors.TextSecondary, strokeWidth = 2.dp)
            }
        }
    }
}

/** The height of the pictures and files of a message once drawn, so that the chat can pin the words under them. */
object MediaMetrics {
    val heights = HashMap<String, Int>()
}
