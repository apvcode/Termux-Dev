package com.apvcode.devx.ui.chat

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatIndentDecrease
import androidx.compose.material.icons.outlined.FormatIndentIncrease
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.StrikethroughS
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.apvcode.devx.R
import com.apvcode.devx.ui.components.DevxDialog
import com.apvcode.devx.ui.components.MenuRow
import com.apvcode.devx.ui.components.PillButton
import com.apvcode.devx.ui.components.PopupMenu
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.markdown.MarkdownText
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType

/**
 * The message, full screen: a big calm page to write on, with Markdown at hand (headings, bold, lists, checklists, quotes, code,
 * tables, formulas), a tidy view of how it will read, and undo and redo. Whatever is written here is the message in the chat's
 * field as well, so closing it just goes back to the field.
 */
@Composable
fun FullEditorDialog(initial: String, canSend: Boolean, onChange: (String) -> Unit, onSend: () -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        FullEditor(initial, canSend, onChange, onSend, onClose)
    }
}

@Composable
private fun FullEditor(initial: String, canSend: Boolean, onChange: (String) -> Unit, onSend: () -> Unit, onClose: () -> Unit) {
    var tf by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val undo = remember { ArrayDeque<TextFieldValue>() }
    val redo = remember { ArrayDeque<TextFieldValue>() }
    var undoCount by remember { mutableIntStateOf(0) }
    var redoCount by remember { mutableIntStateOf(0) }
    var lastPush by remember { mutableLongStateOf(0L) }
    var preview by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<String?>(null) }
    var tableDialog by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    /** A change: remembered for undo (typing in a burst counts as one step; a button counts as its own). */
    fun commit(new: TextFieldValue, step: Boolean) {
        if (new.text != tf.text) {
            val now = SystemClock.uptimeMillis()
            if (step || undo.isEmpty() || now - lastPush > 800) {
                undo.addLast(tf)
                if (undo.size > 120) undo.removeFirst()
            }
            lastPush = if (step) 0L else now
            redo.clear()
            undoCount = undo.size; redoCount = 0
            onChange(new.text)
        }
        tf = new
    }
    fun doUndo() { if (undo.isNotEmpty()) { redo.addLast(tf); tf = undo.removeLast(); undoCount = undo.size; redoCount = redo.size; lastPush = 0L; onChange(tf.text) } }
    fun doRedo() { if (redo.isNotEmpty()) { undo.addLast(tf); tf = redo.removeLast(); undoCount = undo.size; redoCount = redo.size; lastPush = 0L; onChange(tf.text) } }
    fun edit(f: (TextFieldValue) -> TextFieldValue) { commit(f(tf), step = true); preview = false; menu = null }

    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, tween(260, easing = FastOutSlowInEasing))
        runCatching { focus.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(DevxColors.Bg)
            .graphicsLayer { alpha = appear.value; translationY = (1f - appear.value) * 40.dp.toPx() }
            .systemBarsPadding()
            .imePadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            // The top: back to the field, "Edit | Preview", undo and redo.
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundTool(onClick = onClose) { Icon(painterResource(R.drawable.ic_arrows_diagonal_minimize), stringResource(R.string.editor_collapse), tint = DevxColors.TextPrimary, modifier = Modifier.size(22.dp)) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Row(Modifier.clip(CircleShape).background(DevxColors.Card).padding(3.dp)) {
                        Segment(stringResource(R.string.editor_edit), !preview) { preview = false }
                        Segment(stringResource(R.string.editor_preview), preview) { preview = true }
                    }
                }
                Row(Modifier.clip(CircleShape).background(DevxColors.Card)) {
                    Box(Modifier.size(44.dp).pressable(enabled = undoCount > 0, scaleTo = 0.9f, haptic = false) { doUndo() }, contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Outlined.Undo, null, tint = if (undoCount > 0) DevxColors.TextPrimary else DevxColors.TextDisabled, modifier = Modifier.size(22.dp)) }
                    Box(Modifier.size(44.dp).pressable(enabled = redoCount > 0, scaleTo = 0.9f, haptic = false) { doRedo() }, contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Outlined.Redo, null, tint = if (redoCount > 0) DevxColors.TextPrimary else DevxColors.TextDisabled, modifier = Modifier.size(22.dp)) }
                }
            }

            // The page.
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (preview) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp, vertical = 12.dp)) {
                        if (tf.text.isBlank()) Text(stringResource(R.string.editor_empty_preview), style = DevxType.Body, color = DevxColors.TextDisabled)
                        else MarkdownText(tf.text)
                    }
                } else {
                    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp, vertical = 12.dp)) {
                        if (tf.text.isEmpty()) Text(stringResource(R.string.editor_placeholder), style = DevxType.Body.copy(fontSize = 19.sp, lineHeight = 30.sp), color = DevxColors.TextDisabled)
                        BasicTextField(
                            value = tf,
                            onValueChange = { commit(it, step = false) },
                            textStyle = DevxType.Body.copy(fontSize = 19.sp, lineHeight = 30.sp, color = DevxColors.TextPrimary),
                            cursorBrush = SolidColor(DevxColors.Accent),
                            visualTransformation = MarkdownHighlight,
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    }
                }
            }

            // The bottom: how much is written, the tools and the send button.
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val words = remember(tf.text) { tf.text.split(Regex("\\s+")).count { it.isNotBlank() } }
                Text(stringResource(R.string.editor_count, tf.text.length, words), style = DevxType.Caption, color = DevxColors.TextDisabled, modifier = Modifier.padding(start = 10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.weight(1f).clip(CircleShape).background(DevxColors.Card).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Text styles.
                        ToolMenu(Icons.Outlined.TextFields, stringResource(R.string.fmt_text), menu == "text", { menu = if (menu == "text") null else "text" }) {
                            MenuRow(Icons.Outlined.TextFields, stringResource(R.string.fmt_plain)) { edit { setHeading(it, 0) } }
                            MenuRow(Icons.Outlined.Title, stringResource(R.string.fmt_h1)) { edit { setHeading(it, 1) } }
                            MenuRow(Icons.Outlined.Title, stringResource(R.string.fmt_h2)) { edit { setHeading(it, 2) } }
                            MenuRow(Icons.Outlined.Title, stringResource(R.string.fmt_h3)) { edit { setHeading(it, 3) } }
                            MenuRow(Icons.Outlined.FormatBold, stringResource(R.string.fmt_bold)) { edit { wrap(it, "**", "**") } }
                            MenuRow(Icons.Outlined.FormatItalic, stringResource(R.string.fmt_italic)) { edit { wrap(it, "*", "*") } }
                            MenuRow(Icons.Outlined.StrikethroughS, stringResource(R.string.fmt_strike)) { edit { wrap(it, "~~", "~~") } }
                            MenuRow(Icons.Outlined.Code, stringResource(R.string.fmt_inline_code)) { edit { wrap(it, "`", "`") } }
                            MenuRow(Icons.Outlined.FormatQuote, stringResource(R.string.fmt_quote)) { edit { toggleLines(it, QUOTE) { _ -> "> " } } }
                        }
                        // Lists.
                        ToolMenu(Icons.AutoMirrored.Outlined.FormatListBulleted, stringResource(R.string.fmt_lists), menu == "list", { menu = if (menu == "list") null else "list" }) {
                            MenuRow(Icons.AutoMirrored.Outlined.FormatListBulleted, stringResource(R.string.fmt_bullets)) { edit { toggleLines(it, BULLET) { _ -> "- " } } }
                            MenuRow(Icons.Outlined.FormatListNumbered, stringResource(R.string.fmt_numbers)) { edit { toggleLines(it, NUMBER) { i -> "${i + 1}. " } } }
                            MenuRow(Icons.Outlined.Checklist, stringResource(R.string.fmt_checklist)) { edit { toggleLines(it, CHECK) { _ -> "- [ ] " } } }
                            MenuRow(Icons.Outlined.FormatIndentIncrease, stringResource(R.string.fmt_indent)) { edit { indent(it, 1) } }
                            MenuRow(Icons.Outlined.FormatIndentDecrease, stringResource(R.string.fmt_outdent)) { edit { indent(it, -1) } }
                        }
                        ToolButton(Icons.Outlined.TableChart, stringResource(R.string.fmt_table)) { menu = null; tableDialog = true }
                        ToolButton(Icons.Outlined.Functions, stringResource(R.string.fmt_formula)) { edit { insertBlock(it, "$$\n", "\n$$") } }
                        ToolButton(Icons.Outlined.DataObject, stringResource(R.string.fmt_codeblock)) { edit { insertBlock(it, "```\n", "\n```") } }
                        ToolButton(Icons.Outlined.Link, stringResource(R.string.fmt_link)) { edit { link(it) } }
                    }
                    Box(
                        Modifier.size(52.dp).clip(CircleShape).background(if (canSend) DevxColors.Accent else DevxColors.Outline).pressable(enabled = canSend, scaleTo = 0.9f, onClick = onSend),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.ArrowUpward, stringResource(R.string.voice_send), tint = if (canSend) DevxColors.OnAccent else DevxColors.TextDisabled, modifier = Modifier.size(24.dp)) }
                }
            }
        }
    }

    if (tableDialog) TableDialog(onDismiss = { tableDialog = false }) { columns, rows -> tableDialog = false; edit { insertTable(it, columns, rows) } }
}

// ───────────────────────── pieces ─────────────────────────

@Composable
private fun RoundTool(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(DevxColors.Card).pressable(scaleTo = 0.9f, onClick = onClick), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun Segment(text: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text, style = DevxType.Caption.copy(fontWeight = FontWeight.Medium), color = if (on) DevxColors.OnAccent else DevxColors.TextSecondary,
        modifier = Modifier.clip(CircleShape).background(if (on) DevxColors.Accent else Color.Transparent).pressable(scaleTo = 0.95f, haptic = false, onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(46.dp).pressable(scaleTo = 0.88f, haptic = false, onClick = onClick), contentAlignment = Alignment.Center) { Icon(icon, label, tint = DevxColors.TextPrimary, modifier = Modifier.size(23.dp)) }
}

@Composable
private fun ToolMenu(icon: ImageVector, label: String, open: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    Box {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(if (open) Color.White.copy(alpha = 0.10f) else Color.Transparent).pressable(scaleTo = 0.88f, haptic = false, onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = DevxColors.TextPrimary, modifier = Modifier.size(23.dp)) }
        PopupMenu(expanded = open, onDismiss = onToggle, offset = DpOffset(0.dp, 0.dp)) { content() }
    }
}

/** How big a table to put in: columns and rows, with a small drawing of it. */
@Composable
private fun TableDialog(onDismiss: () -> Unit, onInsert: (Int, Int) -> Unit) {
    var columns by remember { mutableIntStateOf(3) }
    var rows by remember { mutableIntStateOf(2) }
    DevxDialog(stringResource(R.string.fmt_table), onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Counter(stringResource(R.string.fmt_table_cols), columns, 1, 6) { columns = it }
            Counter(stringResource(R.string.fmt_table_rows), rows, 1, 12) { rows = it }
            // The drawing: a header and the rows.
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, DevxColors.Outline, RoundedCornerShape(16.dp))) {
                for (r in 0..minOf(rows, 4)) {
                    Row(Modifier.fillMaxWidth().background(if (r == 0) Color.White.copy(alpha = 0.08f) else Color.Transparent)) {
                        for (c in 0 until columns) Box(Modifier.weight(1f).size(height = 26.dp, width = 10.dp).border(0.5.dp, DevxColors.Outline))
                    }
                }
            }
            PillButton(stringResource(R.string.fmt_insert), onClick = { onInsert(columns, rows) })
        }
    }
}

@Composable
private fun Counter(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = DevxType.Body, color = DevxColors.TextPrimary, modifier = Modifier.weight(1f))
        Row(Modifier.clip(CircleShape).background(DevxColors.Card), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).pressable(enabled = value > min, scaleTo = 0.9f, haptic = false) { onChange(value - 1) }, contentAlignment = Alignment.Center) { Text("−", style = DevxType.Title, color = if (value > min) DevxColors.TextPrimary else DevxColors.TextDisabled) }
            Text("$value", style = DevxType.BodyStrong, color = DevxColors.TextPrimary, modifier = Modifier.width(28.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Box(Modifier.size(44.dp).pressable(enabled = value < max, scaleTo = 0.9f, haptic = false) { onChange(value + 1) }, contentAlignment = Alignment.Center) { Text("+", style = DevxType.Title, color = if (value < max) DevxColors.TextPrimary else DevxColors.TextDisabled) }
        }
    }
}

// ───────────────────────── Markdown colouring while writing ─────────────────────────

/** The marks of Markdown stay in the text but turn quiet, and what they mean shows (headings big, bold bold, code on its own ground). */
private object MarkdownHighlight : VisualTransformation {
    private val quiet = Color(0xFF6F6F78)
    private val HEADING = Regex("^(#{1,6})(\\s.*)$", RegexOption.MULTILINE)
    private val QUOTE_LINE = Regex("^(>)(.*)$", RegexOption.MULTILINE)
    private val LIST_MARK = Regex("^(\\s*)((?:[-*+]|\\d+\\.)(?:\\s+\\[[ xX]\\])?)(\\s)", RegexOption.MULTILINE)
    private val BOLD = Regex("(\\*\\*)(.+?)(\\*\\*)")
    private val ITALIC = Regex("(?<![*])(\\*)([^*\\n]+?)(\\*)(?![*])")
    private val STRIKE = Regex("(~~)(.+?)(~~)")
    private val CODE = Regex("(`)([^`\\n]+?)(`)")
    private val FENCE = Regex("^(```.*|\\$\\$)$", RegexOption.MULTILINE)
    private val TABLE_PIPE = Regex("^\\s*\\|.*\\|\\s*$", RegexOption.MULTILINE)

    override fun filter(text: AnnotatedString): TransformedText {
        val src = text.text
        val styled = buildAnnotatedString {
            append(src)
            HEADING.findAll(src).forEach { m ->
                val size = when (m.groupValues[1].length) { 1 -> 26.sp; 2 -> 23.sp; else -> 21.sp }
                addStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = size), m.range.first, m.range.last + 1)
                addStyle(SpanStyle(color = quiet), m.groups[1]!!.range.first, m.groups[1]!!.range.last + 1)
            }
            QUOTE_LINE.findAll(src).forEach { m ->
                addStyle(SpanStyle(color = quiet), m.groups[1]!!.range.first, m.groups[1]!!.range.last + 1)
                addStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color(0xFFB9B9C2)), m.groups[2]!!.range.first, m.groups[2]!!.range.last + 1)
            }
            LIST_MARK.findAll(src).forEach { m -> addStyle(SpanStyle(color = quiet), m.groups[2]!!.range.first, m.groups[2]!!.range.last + 1) }
            BOLD.findAll(src).forEach { m ->
                addStyle(SpanStyle(fontWeight = FontWeight.Bold), m.groups[2]!!.range.first, m.groups[2]!!.range.last + 1)
                addStyle(SpanStyle(color = quiet), m.groups[1]!!.range.first, m.groups[1]!!.range.last + 1)
                addStyle(SpanStyle(color = quiet), m.groups[3]!!.range.first, m.groups[3]!!.range.last + 1)
            }
            ITALIC.findAll(src).forEach { m ->
                addStyle(SpanStyle(fontStyle = FontStyle.Italic), m.groups[2]!!.range.first, m.groups[2]!!.range.last + 1)
                addStyle(SpanStyle(color = quiet), m.groups[1]!!.range.first, m.groups[1]!!.range.last + 1)
                addStyle(SpanStyle(color = quiet), m.groups[3]!!.range.first, m.groups[3]!!.range.last + 1)
            }
            STRIKE.findAll(src).forEach { m ->
                addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), m.groups[2]!!.range.first, m.groups[2]!!.range.last + 1)
                addStyle(SpanStyle(color = quiet), m.groups[1]!!.range.first, m.groups[1]!!.range.last + 1)
                addStyle(SpanStyle(color = quiet), m.groups[3]!!.range.first, m.groups[3]!!.range.last + 1)
            }
            CODE.findAll(src).forEach { m ->
                addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color.White.copy(alpha = 0.09f), fontSize = 17.sp), m.groups[2]!!.range.first, m.groups[2]!!.range.last + 1)
                addStyle(SpanStyle(color = quiet), m.groups[1]!!.range.first, m.groups[1]!!.range.last + 1)
                addStyle(SpanStyle(color = quiet), m.groups[3]!!.range.first, m.groups[3]!!.range.last + 1)
            }
            FENCE.findAll(src).forEach { m -> addStyle(SpanStyle(color = quiet, fontFamily = FontFamily.Monospace), m.range.first, m.range.last + 1) }
            TABLE_PIPE.findAll(src).forEach { m -> addStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 17.sp), m.range.first, m.range.last + 1) }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }
}

// ───────────────────────── the edits ─────────────────────────

private val HEADING_LINE = Regex("^#{1,6}\\s+")
private val QUOTE = Regex("^>\\s?")
private val BULLET = Regex("^(\\s*)[-*+]\\s+(?!\\[[ xX]\\])")
private val NUMBER = Regex("^(\\s*)\\d+\\.\\s+")
private val CHECK = Regex("^(\\s*)[-*+]\\s+\\[[ xX]\\]\\s+")
private val ANY_LIST = Regex("^(\\s*)(?:[-*+]|\\d+\\.)(?:\\s+\\[[ xX]\\])?\\s+")

private fun TextFieldValue.with(text: String, selection: TextRange) = TextFieldValue(text, selection)

/** Puts marks round the selection (taking them off when they are already there); with nothing selected it puts the pair and stands between. */
private fun wrap(v: TextFieldValue, left: String, right: String): TextFieldValue {
    val text = v.text; val a = v.selection.min; val b = v.selection.max
    val selected = text.substring(a, b)
    return when {
        selected.length >= left.length + right.length && selected.startsWith(left) && selected.endsWith(right) -> {
            val inner = selected.substring(left.length, selected.length - right.length)
            v.with(text.substring(0, a) + inner + text.substring(b), TextRange(a, a + inner.length))
        }
        a != b -> v.with(text.substring(0, a) + left + selected + right + text.substring(b), TextRange(a + left.length, a + left.length + selected.length))
        else -> v.with(text.substring(0, a) + left + right + text.substring(b), TextRange(a + left.length))
    }
}

/** The lines the selection touches: where they start and end. */
private fun lineSpan(text: String, sel: TextRange): Pair<Int, Int> {
    val start = text.lastIndexOf('\n', (sel.min - 1).coerceAtLeast(0)).let { if (sel.min == 0) 0 else it + 1 }
    val endAt = if (sel.max > sel.min && sel.max > 0 && text.getOrNull(sel.max - 1) == '\n') sel.max - 1 else sel.max
    val end = text.indexOf('\n', endAt).let { if (it < 0) text.length else it }
    return start to end
}

private fun replaceLines(v: TextFieldValue, transform: (List<String>) -> List<String>): TextFieldValue {
    val (start, end) = lineSpan(v.text, v.selection)
    val lines = transform(v.text.substring(start, end).split("\n")).joinToString("\n")
    val text = v.text.substring(0, start) + lines + v.text.substring(end)
    return v.with(text, if (v.selection.collapsed) TextRange(start + lines.length) else TextRange(start, start + lines.length))
}

/** A list or quote mark on every touched line: put on, or taken off when every line has it already. */
private fun toggleLines(v: TextFieldValue, kind: Regex, mark: (Int) -> String): TextFieldValue = replaceLines(v) { lines ->
    val filled = lines.filter { it.isNotBlank() }
    val all = filled.isNotEmpty() && filled.all { kind.containsMatchIn(it) }
    var n = 0
    lines.map { line ->
        if (line.isBlank()) line
        else if (all) line.replaceFirst(kind, if (kind === QUOTE) "" else "$1")
        else {
            val indent = line.takeWhile { it == ' ' || it == '\t' }
            val bare = line.removePrefix(indent).replaceFirst(Regex("^(?:[-*+]|\\d+\\.)(?:\\s+\\[[ xX]\\])?\\s+"), "").replaceFirst(QUOTE, "")
            indent + mark(n++) + bare
        }
    }
}

private fun setHeading(v: TextFieldValue, level: Int): TextFieldValue = replaceLines(v) { lines ->
    lines.map { line ->
        val bare = line.replaceFirst(HEADING_LINE, "")
        val current = HEADING_LINE.find(line)?.value?.count { it == '#' } ?: 0
        if (level == 0 || current == level) bare else "#".repeat(level) + " " + bare
    }
}

private fun indent(v: TextFieldValue, direction: Int): TextFieldValue = replaceLines(v) { lines ->
    lines.map { if (direction > 0) "  $it" else it.removePrefix("  ").removePrefix(" ").removePrefix("\t") }
}

/** A block (a formula, a code block) on lines of its own, with the cursor inside; a selection goes inside it. */
private fun insertBlock(v: TextFieldValue, open: String, close: String): TextFieldValue {
    val text = v.text; val a = v.selection.min; val b = v.selection.max
    val before = text.substring(0, a); val inner = text.substring(a, b); val after = text.substring(b)
    val lead = if (before.isEmpty() || before.endsWith("\n")) "" else "\n"
    val tail = if (after.isEmpty() || after.startsWith("\n")) "" else "\n"
    val block = lead + open + inner + close + tail
    val cursor = before.length + lead.length + open.length + inner.length
    return v.with(before + block + after, TextRange(cursor))
}

private fun link(v: TextFieldValue): TextFieldValue {
    val text = v.text; val a = v.selection.min; val b = v.selection.max
    val selected = text.substring(a, b).ifEmpty { "text" }
    val out = text.substring(0, a) + "[" + selected + "](https://)" + text.substring(b)
    val urlStart = a + selected.length + 4
    return v.with(out, TextRange(urlStart, urlStart + 8))
}

private fun insertTable(v: TextFieldValue, columns: Int, rows: Int): TextFieldValue {
    val text = v.text; val a = v.selection.min; val b = v.selection.max
    val before = text.substring(0, a); val after = text.substring(b)
    val lead = if (before.isEmpty() || before.endsWith("\n")) "" else "\n\n"
    val tail = if (after.isEmpty() || after.startsWith("\n")) "\n" else "\n\n"
    val headers = (1..columns).map { "Column $it" }
    val table = buildString {
        append("| ").append(headers.joinToString(" | ")).append(" |\n")
        append("|").append(" --- |".repeat(columns)).append("\n")
        repeat(rows) { append("| ").append(List(columns) { " " }.joinToString(" | ")).append(" |\n") }
    }.trimEnd('\n')
    val start = before.length + lead.length + 2
    return v.with(before + lead + table + tail + after, TextRange(start, start + headers.first().length))
}
