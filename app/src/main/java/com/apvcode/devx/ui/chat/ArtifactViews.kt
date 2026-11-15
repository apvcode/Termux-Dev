package com.apvcode.devx.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import com.apvcode.devx.R
import com.apvcode.devx.data.Artifact
import com.apvcode.devx.ui.components.DevxIcons
import com.apvcode.devx.ui.components.MenuRow
import com.apvcode.devx.ui.components.PopupMenu
import com.apvcode.devx.ui.components.fileIcon
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import java.io.File

private val CODE_EXTENSIONS = setOf(
    "py", "kt", "kts", "java", "js", "mjs", "ts", "tsx", "jsx", "go", "rs", "c", "h", "cpp", "hpp", "cs", "swift", "dart", "php", "rb", "lua", "sh", "bash",
    "zsh", "bat", "ps1", "sql", "html", "htm", "css", "scss", "xml", "json", "yml", "yaml", "toml", "ini", "gradle", "vue", "r",
)
private val TEXT_EXTENSIONS = setOf("txt", "md", "markdown", "log", "csv", "tsv", "conf", "cfg", "env", "properties", "srt", "vtt", "tex", "rst")
private val ARCHIVE_EXTENSIONS = setOf("zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "apk", "jar", "aab")

/** What kind of thing a file is, for the line under its name: "Code · PY". */
@Composable
private fun kindLabel(a: Artifact): String {
    val ext = a.extension
    val kind = when {
        ext in CODE_EXTENSIONS -> stringResource(R.string.art_code)
        ext in TEXT_EXTENSIONS -> stringResource(R.string.art_text)
        a.mime.startsWith("image/") -> stringResource(R.string.art_image)
        ext in ARCHIVE_EXTENSIONS -> stringResource(R.string.art_archive)
        ext in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "epub") -> stringResource(R.string.art_document)
        ext in setOf("db", "sqlite", "sqlite3", "parquet") -> stringResource(R.string.art_data)
        a.mime.startsWith("audio/") || a.mime.startsWith("video/") -> stringResource(R.string.art_media)
        else -> stringResource(R.string.art_file)
    }
    return if (ext.isEmpty()) kind else "$kind · ${ext.uppercase()}"
}

/** A file the assistant handed over: a card in the answer. Pressing it opens the file; the arrow saves it. */
@Composable
fun ArtifactCard(id: String, vm: ChatViewModel, onOpen: (Artifact) -> Unit, modifier: Modifier = Modifier) {
    val artifact = remember(id) { vm.artifact(id) }
    val context = LocalContext.current
    val shape = RoundedCornerShape(22.dp)
    if (artifact == null) {
        Text(stringResource(R.string.art_missing), style = DevxType.Caption, color = DevxColors.TextDisabled, modifier = modifier)
        return
    }
    Row(
        modifier
            .fillMaxWidth()
            .pressable(scaleTo = 0.98f, haptic = false) { onOpen(artifact) }
            .clip(shape)
            .background(DevxColors.Card)
            .border(1.dp, DevxColors.Outline, shape)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(DevxColors.CardInner),
            contentAlignment = Alignment.Center,
        ) {
            Icon(fileIcon(artifact.name), null, tint = DevxColors.TextPrimary, modifier = Modifier.size(30.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                artifact.name,
                style = DevxType.BodyStrong,
                color = DevxColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(kindLabel(artifact), style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 1)
        }
        Box(
            Modifier.size(44.dp).pressable(haptic = true) { saveToDownloads(context, vm.artifactFile(artifact), artifact) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.FileDownload, stringResource(R.string.art_download), tint = DevxColors.TextPrimary, modifier = Modifier.size(24.dp))
        }
    }
}

/**
 * The file in full: code with colours (always on a dark page, like a code editor), a picture as a picture,
 * anything else as its details. The menu copies, downloads or shares it.
 */
@Composable
fun ArtifactViewer(artifact: Artifact, vm: ChatViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val file = remember(artifact.id) { vm.artifactFile(artifact) }
    val text = remember(artifact.id) { readText(file, artifact) }
    var menu by remember { mutableStateOf(false) }
    val page = Color(0xFF161616)
    Column(
        modifier
            .fillMaxSize()
            .background(page)
            .statusBarsPadding(),
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.align(Alignment.CenterStart).size(48.dp).pressable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Close, stringResource(R.string.cd_close), tint = Color.White, modifier = Modifier.size(26.dp))
            }
            Text(artifact.name, style = DevxType.Title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 56.dp))
            Box(Modifier.align(Alignment.CenterEnd)) {
                Box(Modifier.size(48.dp).pressable { menu = true }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.MoreVert, stringResource(R.string.cd_more), tint = Color.White, modifier = Modifier.size(24.dp))
                }
                PopupMenu(expanded = menu, onDismiss = { menu = false }, offset = DpOffset((-8).dp, 0.dp)) {
                    if (text != null) {
                        MenuRow(DevxIcons.Copy, stringResource(R.string.msg_copy)) {
                            menu = false
                            copyText(context, text)
                        }
                    }
                    MenuRow(Icons.Outlined.FileDownload, stringResource(R.string.art_download)) {
                        menu = false
                        saveToDownloads(context, file, artifact)
                    }
                    MenuRow(DevxIcons.Share, stringResource(R.string.msg_share)) {
                        menu = false
                        shareFile(context, file, artifact)
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                text != null -> {
                    val colored = remember(text) { highlight(text.take(60_000), artifact.extension) }
                    SelectionContainer {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp).navigationBarsPadding()) {
                            Text(colored, style = DevxType.Mono.copy(color = Color(0xFFE6E6E6)))
                        }
                    }
                }
                artifact.mime.startsWith("image/") -> AsyncImage(
                    model = file,
                    contentDescription = artifact.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                )
                else -> Column(
                    Modifier.fillMaxSize().padding(40.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(fileIcon(artifact.name), null, tint = Color.White, modifier = Modifier.size(64.dp))
                    Text("${artifact.name}\n${humanSize(artifact.size)}", style = DevxType.Body, color = Color.White, textAlign = TextAlign.Center)
                    Text(stringResource(R.string.art_binary), style = DevxType.Caption, color = Color(0xFF9A9A9A), textAlign = TextAlign.Center)
                }
            }
        }
    }
}

private fun humanSize(bytes: Long): String = if (bytes >= 1_048_576) "%.1f MB".format(bytes / 1_048_576.0) else "${(bytes + 1023) / 1024} KB"

/** The text of a file if it is a text one and not huge; null otherwise. */
private fun readText(file: File, artifact: Artifact): String? {
    if (!file.isFile || artifact.mime.startsWith("image/") || file.length() > 3_000_000) return null
    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
    if (bytes.take(4096).any { it.toInt() == 0 }) return null
    return String(bytes, Charsets.UTF_8)
}

private fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("devx", text))
    Toast.makeText(context, R.string.art_copied, Toast.LENGTH_SHORT).show()
}

/** Puts the file into the phone's Downloads. */
fun saveToDownloads(context: Context, file: File, artifact: Artifact) {
    runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, artifact.name)
                put(MediaStore.Downloads.MIME_TYPE, artifact.mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no uri")
            context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        } else {
            val target = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), artifact.name)
            file.copyTo(target, overwrite = true)
        }
        Toast.makeText(context, R.string.art_saved, Toast.LENGTH_SHORT).show()
    }.onFailure { Toast.makeText(context, R.string.art_failed, Toast.LENGTH_LONG).show() }
}

fun shareFile(context: Context, file: File, artifact: Artifact) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).setType(artifact.mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

// ---------- colours for code ----------

private val KEYWORD = Color(0xFFE8A55A)
private val FUNCTION = Color(0xFFF0C674)
private val STRING = Color(0xFF98C379)
private val NUMBER = Color(0xFF79B8FF)
private val COMMENT = Color(0xFF7F848E)

private val KEYWORDS = setOf(
    "def", "class", "return", "if", "elif", "else", "for", "while", "in", "not", "and", "or", "import", "from", "as", "with", "try", "except", "finally", "raise",
    "pass", "break", "continue", "lambda", "None", "True", "False", "yield", "async", "await", "global", "is", "del", "assert",
    "fun", "val", "var", "when", "object", "interface", "override", "private", "public", "protected", "internal", "data", "sealed", "package", "this", "null", "true", "false",
    "function", "const", "let", "new", "typeof", "export", "default", "switch", "case", "do", "catch", "throw", "static", "void", "int", "long", "float", "double", "bool",
    "boolean", "string", "char", "struct", "enum", "extends", "implements", "super", "final", "abstract", "func", "type", "map", "chan", "go", "defer", "select", "range",
    "fn", "let", "mut", "impl", "trait", "pub", "use", "mod", "match", "loop", "self", "Self", "crate", "where", "echo", "fi", "then", "done", "esac", "SELECT", "FROM",
    "WHERE", "INSERT", "UPDATE", "DELETE", "CREATE", "TABLE", "JOIN", "ON", "GROUP", "BY", "ORDER", "LIMIT", "AND", "OR", "NOT", "NULL",
)
private val HASH_COMMENTS = setOf("py", "sh", "bash", "zsh", "rb", "yml", "yaml", "toml", "ini", "conf", "cfg", "r", "properties", "env", "ps1")
private val SLASH_COMMENTS = setOf("kt", "kts", "java", "js", "mjs", "ts", "tsx", "jsx", "go", "rs", "c", "h", "cpp", "hpp", "cs", "swift", "dart", "php", "scss", "css", "vue", "gradle", "sql")

/** A light colouring of code: comments, strings, numbers, keywords and the names being called. */
internal fun highlight(code: String, extension: String): AnnotatedString {
    val comments = when {
        extension in HASH_COMMENTS -> "#[^\\n]*"
        extension in SLASH_COMMENTS -> "//[^\\n]*|/\\*[\\s\\S]*?\\*/"
        extension == "html" || extension == "htm" || extension == "xml" -> "<!--[\\s\\S]*?-->"
        else -> null
    }
    val strings = "\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'|`(?:\\\\.|[^`\\\\])*`"
    val pattern = Regex((if (comments != null) "(?<c>$comments)|" else "") + "(?<s>$strings)|(?<n>\\b\\d[\\d_.]*\\b)|(?<w>[A-Za-z_][A-Za-z0-9_]*)")
    return buildAnnotatedString {
        var last = 0
        for (m in pattern.findAll(code)) {
            if (m.range.first > last) append(code.substring(last, m.range.first))
            val text = m.value
            val color = when {
                m.groups["c"] != null -> COMMENT
                m.groups["s"] != null -> STRING
                m.groups["n"] != null -> NUMBER
                text in KEYWORDS -> KEYWORD
                code.getOrNull(m.range.last + 1) == '(' -> FUNCTION
                else -> null
            }
            if (color != null) withStyle(SpanStyle(color = color)) { append(text) } else append(text)
            last = m.range.last + 1
        }
        if (last < code.length) append(code.substring(last))
    }
}
