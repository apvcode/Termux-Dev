package com.apvcode.devx.ui.remote

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import com.apvcode.devx.R
import com.apvcode.devx.remote.RItem
import com.apvcode.devx.remote.RemoteMedia
import com.apvcode.devx.remote.RemoteSession
import com.apvcode.devx.ui.chat.enterPop
import com.apvcode.devx.ui.components.DevxIcons
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.screens.PhotoViewer
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector

/** A picture the agent looked at, or a file it sent, in the chat: a picture opens full screen and zooms, a file can be opened, saved or shared. */
@Composable
fun MediaItem(session: RemoteSession, m: RItem.Media, animate: Boolean) {
    if (m.mime.startsWith("image/") && m.mime != "image/svg+xml") ImageCard(session, m, animate) else FileCard(session, m, animate)
}

@Composable
private fun ImageCard(session: RemoteSession, m: RItem.Media, animate: Boolean) {
    val context = LocalContext.current
    var file by remember(m.id) { mutableStateOf(RemoteMedia.cached(context, m)) }
    var failed by remember(m.id) { mutableStateOf(false) }
    var progress by remember(m.id) { mutableFloatStateOf(0f) }
    var viewing by remember { mutableStateOf(false) }
    LaunchedEffect(m.id) {
        if (file == null && m.size <= 15L * 1024 * 1024) {
            file = RemoteMedia.fetch(context, session, m) { progress = it }
            if (file == null) failed = true
        }
    }
    Column(Modifier.fillMaxWidth().enterPop(animate), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val shown = file
        if (shown != null) {
            AsyncImage(
                model = shown,
                contentDescription = m.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .heightIn(max = 280.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(DevxColors.CardInner)
                    .pressable(scaleTo = 0.98f, haptic = false) { viewing = true },
            )
        } else {
            Box(Modifier.fillMaxWidth().height160().clip(RoundedCornerShape(20.dp)).background(DevxColors.Card), contentAlignment = Alignment.Center) {
                Text(if (failed) stringResource(R.string.remote_media_failed) else (progress * 100).toInt().toString() + "%", style = DevxType.Caption, color = DevxColors.TextSecondary)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (m.sent) R.string.remote_media_sent else R.string.remote_media_looked, m.name),
                style = DevxType.Caption, color = DevxColors.TextDisabled, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (shown != null) {
                SmallAction(DevxIcons.Download) { saveToDownloads(context, shown, m.name, m.mime) }
                SmallAction(DevxIcons.Share) { share(context, shown, m.mime) }
            }
        }
    }
    val big = file
    if (viewing && big != null) {
        Dialog(onDismissRequest = { viewing = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            PhotoViewer(big, onClose = { viewing = false })
        }
    }
}

private fun Modifier.height160(): Modifier = this.heightIn(min = 160.dp, max = 160.dp)

@Composable
private fun FileCard(session: RemoteSession, m: RItem.Media, animate: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(m.id) { mutableStateOf(false) }
    var progress by remember(m.id) { mutableFloatStateOf(0f) }
    fun withFile(action: (File) -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            val f = RemoteMedia.fetch(context, session, m) { progress = it }
            busy = false
            if (f != null) action(f) else Toast.makeText(context, R.string.remote_media_failed, Toast.LENGTH_LONG).show()
        }
    }
    Row(
        Modifier.fillMaxWidth().enterPop(animate).clip(RoundedCornerShape(20.dp)).background(DevxColors.Card).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(DevxColors.CardInner), contentAlignment = Alignment.Center) {
            Icon(DevxIcons.FileText, null, tint = DevxColors.TextPrimary, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(m.name, style = DevxType.BodyStrong, color = DevxColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(sizeText(m.size) + if (busy) " · " + (progress * 100).toInt() + "%" else "", style = DevxType.Caption, color = DevxColors.TextSecondary)
        }
        SmallAction(DevxIcons.Download) { withFile { saveToDownloads(context, it, m.name, m.mime) } }
        SmallAction(DevxIcons.Share) { withFile { share(context, it, m.mime) } }
        SmallAction(DevxIcons.FileText) { withFile { open(context, it, m.mime) } }
    }
}

@Composable
private fun SmallAction(icon: ImageVector, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).pressable(scaleTo = 0.9f, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(20.dp))
    }
}

private fun sizeText(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1048576.0)
}

private fun uriOf(context: Context, file: File) = FileProvider.getUriForFile(context, context.packageName + ".files", file)

private fun open(context: Context, file: File, mime: String) {
    runCatching {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uriOf(context, file), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(view)
    }.onFailure { share(context, file, mime) }
}

private fun share(context: Context, file: File, mime: String) {
    runCatching {
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uriOf(context, file)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Puts the file into the phone's Downloads. */
private fun saveToDownloads(context: Context, file: File, name: String, mime: String) {
    runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no uri")
            context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        } else {
            file.copyTo(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name), overwrite = true)
        }
        Toast.makeText(context, R.string.art_saved, Toast.LENGTH_SHORT).show()
    }.onFailure { Toast.makeText(context, R.string.art_failed, Toast.LENGTH_LONG).show() }
}
