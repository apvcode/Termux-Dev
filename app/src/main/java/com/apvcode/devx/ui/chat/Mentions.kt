package com.apvcode.devx.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.withLink
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apvcode.devx.R
import com.apvcode.devx.data.ProjectInfo
import com.apvcode.devx.ui.components.DevxIcons
import com.apvcode.devx.ui.components.glass
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.screens.ProjectBadge
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType

/** The "@word" being typed at the cursor: where it starts and ends in the text, and the letters after the @. */
data class MentionToken(val start: Int, val end: Int, val query: String)

/**
 * The @ the person is typing: an @ at the start or after a space, with no space between it and the cursor. Null when the cursor is
 * not at the end of such a word (an e-mail address, a handle in the middle of a word, plain text).
 */
fun mentionToken(text: CharSequence, cursor: Int): MentionToken? {
    val end = cursor.coerceIn(0, text.length)
    var i = end
    while (i > 0) {
        val c = text[i - 1]
        if (c == '@') {
            val before = if (i - 1 == 0) ' ' else text[i - 2]
            return if (before.isWhitespace()) MentionToken(i - 1, end, text.substring(i, end)) else null
        }
        if (c.isWhitespace() || end - i >= 40) return null
        i--
    }
    return null
}

/** What @ can point at. The menu lists the kinds in this order; a new kind is one more entry here and in [MentionKind.look]. */
enum class MentionKind(val tint: Color, val word: Int, val hint: Int) {
    Notebook(Color(0xFFE8A05C), R.string.mention_notebook, R.string.mention_notebook_hint),
    Project(Color(0xFF6C9BE8), R.string.mention_project, R.string.mention_project_hint),
    File(Color(0xFFB48CE8), R.string.mention_file, R.string.mention_file_hint),
    Tasks(Color(0xFF5CC8C0), R.string.mention_tasks, R.string.mention_tasks_hint),
    ;

    /** A kind with one thing to point at is chosen at once, without a list of one (none now; the menu keeps the way). */
    val direct: Boolean get() = false

    val icon: ImageVector
        @Composable get() = when (this) {
            Notebook -> DevxIcons.Notebook
            Project -> DevxIcons.Folder
            File -> DevxIcons.Paperclip
            Tasks -> DevxIcons.Clock
        }
}

/**
 * One row of the list. [key] is what a message keeps (a notebook's or project's name, "task:<id>"); a file is not kept as a mention but
 * joins the message as an attachment ([attachment]). [info] gives a notebook or project its own icon and colour.
 */
class MentionItem(val key: String, val title: String, val subtitle: String, val kind: MentionKind, val info: ProjectInfo? = null, val attachment: com.apvcode.devx.data.Attachment? = null)

/** How a kept mention looks: its name, its kind and (for a notebook or a project) its icon. Null look: it is gone. */
class MentionLook(val title: String, val kind: MentionKind, val info: ProjectInfo?)

/** The rows that fit the letters typed after the @ (the ones that start with them first). */
fun matchingItems(items: List<MentionItem>, query: String): List<MentionItem> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return items
    return items.filter { it.title.lowercase().contains(q) }.sortedBy { if (it.title.lowercase().startsWith(q)) 0 else 1 }
}

@Composable
private fun IconBadge(icon: ImageVector, tint: Color, size: androidx.compose.ui.unit.Dp = 40.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
private fun ItemBadge(item: MentionItem, size: androidx.compose.ui.unit.Dp) {
    if (item.info != null) ProjectBadge(item.info.icon, item.info.color, size) else IconBadge(item.kind.icon, item.kind.tint, size)
}

private class Held { var query = ""; var kind: MentionKind? = null }

/** Each thing in the list comes up a little after the one before it: it rises from below and fades in. */
@Composable
private fun Rise(index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((index * 34L).coerceAtMost(260))
        a.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 320f))
    }
    Box(modifier.graphicsLayer { alpha = a.value.coerceIn(0f, 1f); translationY = (1f - a.value) * 14.dp.toPx() }) { content() }
}

/**
 * What opens above the field when "@" is typed. It floats over the chat (the chat does not move): a menu of what can be pointed at
 * (a notebook, a project, a file), each kind with its colour, what it is for and how many there are. A kind opens its own list,
 * sliding in, each row rising after the one before it; the letters typed after the @ narrow it. Letters that are not a kind's name
 * search all kinds at once.
 */
@Composable
fun MentionPicker(
    token: MentionToken?,
    kind: MentionKind?,
    items: List<MentionItem>,
    onKind: (MentionKind?) -> Unit,
    onPick: (MentionItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val show = remember { MutableTransitionState(false) }
    show.targetState = token != null
    if (!show.currentState && !show.targetState) return
    // While the card goes away it keeps what it showed.
    val held = remember { Held() }
    if (token != null) { held.query = token.query; held.kind = kind }
    val shape = RoundedCornerShape(28.dp)
    val settle = spring<androidx.compose.ui.unit.IntSize>(dampingRatio = 0.9f, stiffness = 380f)
    AnimatedVisibility(
        visibleState = show,
        modifier = modifier,
        enter = fadeIn(tween(180)) + slideInVertically(spring(dampingRatio = 0.8f, stiffness = 380f)) { it / 4 } +
            scaleIn(spring(dampingRatio = 0.85f, stiffness = 400f), initialScale = 0.94f, transformOrigin = TransformOrigin(0.5f, 1f)),
        exit = fadeOut(tween(120)) + slideOutVertically(tween(160)) { it / 5 } + scaleOut(tween(160), targetScale = 0.97f, transformOrigin = TransformOrigin(0.5f, 1f)),
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp)) {
            // One thing animates the size of the card (here); what is inside only crossfades and slides, so nothing pulls two ways.
            Box(
                Modifier
                    .shadow(20.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                    .clip(shape)
                    .background(DevxColors.Card)
                    .border(1.dp, DevxColors.Outline, shape)
                    .animateContentSize(settle),
            ) {
                AnimatedContent(
                    targetState = held.kind,
                    transitionSpec = {
                        val deeper = targetState != null
                        (fadeIn(tween(240, delayMillis = 60)) + slideInHorizontally(tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { if (deeper) it / 6 else -it / 6 }) togetherWith
                            (fadeOut(tween(120)) + slideOutHorizontally(tween(200, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { if (deeper) -it / 8 else it / 8 }) using
                            androidx.compose.animation.SizeTransform(clip = false) { _, _ -> androidx.compose.animation.core.snap() }
                    },
                    label = "mention-level",
                ) { k ->
                    if (k == null) Menu(held.query, items, onKind, onPick) else Listing(k, held.query, items.filter { it.kind == k }, onKind, onPick)
                }
            }
        }
    }
}

@Composable
private fun Menu(query: String, items: List<MentionItem>, onKind: (MentionKind?) -> Unit, onPick: (MentionItem) -> Unit) {
    val q = query.trim().lowercase()
    val words = MentionKind.entries.associateWith { stringResource(it.word) }
    val kinds = MentionKind.entries.filter { q.isEmpty() || words.getValue(it).lowercase().startsWith(q) }
    val found = if (q.isEmpty()) emptyList() else matchingItems(items, query).take(12)
    Column(Modifier.padding(vertical = 8.dp)) {
        if (q.isEmpty()) Text(
            stringResource(R.string.mention_title), style = DevxType.Caption, color = DevxColors.TextDisabled,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 4.dp),
        )
        Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
            kinds.forEachIndexed { i, k ->
                val n = items.count { it.kind == k }
                Rise(i) { KindRow(k, words.getValue(k), n) { if (k.direct) items.firstOrNull { it.kind == k }?.let(onPick) else onKind(k) } }
            }
            found.forEachIndexed { i, item -> androidx.compose.runtime.key(item.key) { Rise(kinds.size + i) { ItemRow(item, onPick) } } }
            if (kinds.isEmpty() && found.isEmpty()) Text(stringResource(R.string.mention_nothing), style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
        }
    }
}

@Composable
private fun KindRow(kind: MentionKind, word: String, count: Int, onClick: () -> Unit) {
    val has = count > 0
    Row(
        Modifier.fillMaxWidth().pressable(enabled = has, scaleTo = 0.97f, onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.graphicsLayer { alpha = if (has) 1f else 0.45f }) { IconBadge(kind.icon, kind.tint, 44.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(word, style = DevxType.BodyStrong, color = if (has) DevxColors.TextPrimary else DevxColors.TextSecondary)
            Text(stringResource(if (has) kind.hint else R.string.mention_empty), style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (has && !kind.direct) {
            Text(count.toString(), style = DevxType.Caption, color = DevxColors.TextDisabled)
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = DevxColors.TextDisabled, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun Listing(kind: MentionKind, query: String, items: List<MentionItem>, onKind: (MentionKind?) -> Unit, onPick: (MentionItem) -> Unit) {
    val found = matchingItems(items, query)
    Column {
        Row(
            Modifier.fillMaxWidth().pressable(scaleTo = 0.98f, haptic = false) { onKind(null) }.padding(start = 12.dp, end = 18.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(DevxColors.CardInner), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = DevxColors.TextPrimary, modifier = Modifier.size(18.dp))
            }
            Icon(kind.icon, null, tint = kind.tint, modifier = Modifier.size(20.dp))
            Text(stringResource(kind.word), style = DevxType.BodyStrong, color = DevxColors.TextPrimary, modifier = Modifier.weight(1f))
            Text(items.size.toString(), style = DevxType.Caption, color = DevxColors.TextDisabled)
        }
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
            found.forEachIndexed { i, item -> androidx.compose.runtime.key(item.key) { Rise(i) { ItemRow(item, onPick) } } }
            if (found.isEmpty()) Text(stringResource(R.string.mention_nothing), style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
        }
    }
}

@Composable
private fun ItemRow(item: MentionItem, onPick: (MentionItem) -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(scaleTo = 0.97f) { onPick(item) }.padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ItemBadge(item, 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(item.title, style = DevxType.BodyStrong, color = DevxColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.subtitle.isNotBlank()) Text(item.subtitle, style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A mention found in a text: where "@Name" starts and ends, and the name. */
class MentionSpan(val start: Int, val end: Int, val name: String)

/**
 * The "@Name" written in [text] for each of the known [names] (a "@" at the start or after a space; the name may have spaces, so the
 * longest names are tried first and the word after it must end there). Overlaps are not reported twice.
 */
fun mentionSpans(text: String, names: Collection<String>): List<MentionSpan> {
    val out = ArrayList<MentionSpan>()
    val taken = BooleanArray(text.length + 1)
    for (name in names.filter { it.isNotBlank() }.sortedByDescending { it.length }) {
        var from = 0
        while (true) {
            val at = text.indexOf("@$name", from)
            if (at < 0) break
            val end = at + 1 + name.length
            val startsWord = at == 0 || text[at - 1].isWhitespace()
            val endsWord = end == text.length || !text[end].isLetterOrDigit()
            if (startsWord && endsWord && (at until end).none { taken[it] }) {
                out += MentionSpan(at, end, name)
                for (i in at until end) taken[i] = true
            }
            from = at + 1
        }
    }
    return out.sortedBy { it.start }
}

/**
 * A message's text with its mentions drawn as part of the sentence: the notebook's own little icon, then "@Name" in its colour (a tap
 * opens it). [looks] are the mentions the message kept; a name that matches none is left as plain text.
 */
@Composable
fun mentionedText(text: String, looks: List<Pair<String, MentionLook>>, onOpen: (String) -> Unit): Pair<androidx.compose.ui.text.AnnotatedString, Map<String, androidx.compose.foundation.text.InlineTextContent>> {
    val byName = looks.associate { it.second.title to it }
    val spans = mentionSpans(text, byName.keys)
    if (spans.isEmpty()) return androidx.compose.ui.text.AnnotatedString(text) to emptyMap()
    val inline = HashMap<String, androidx.compose.foundation.text.InlineTextContent>()
    val built = androidx.compose.ui.text.buildAnnotatedString {
        var cursor = 0
        spans.forEachIndexed { i, sp ->
            append(text.substring(cursor, sp.start))
            val (key, look) = byName.getValue(sp.name)
            val tint = look.info?.let { Color(it.color) } ?: look.kind.tint
            val id = "m$i"
            inline[id] = androidx.compose.foundation.text.InlineTextContent(
                androidx.compose.ui.text.Placeholder(22.sp, 22.sp, androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter),
            ) { if (look.info != null) ProjectBadge(look.info.icon, look.info.color, 20.dp) else IconBadge(look.kind.icon, tint, 20.dp) }
            appendInlineContent(id, "◻")
            append(" ")
            withLink(androidx.compose.ui.text.LinkAnnotation.Clickable(key, androidx.compose.ui.text.TextLinkStyles(androidx.compose.ui.text.SpanStyle(color = tint, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))) { onOpen(key) }) {
                append(text.substring(sp.start, sp.end))
            }
            cursor = sp.end
        }
        append(text.substring(cursor))
    }
    return built to inline
}

/**
 * The names (of notebooks and projects) a spoken sentence has in it: there is no "@" in speech, so a name is found as a whole word or
 * phrase, whatever its case. Short names (under three letters) are not looked for: they would be found in any sentence.
 */
fun spokenNames(text: String, names: Collection<String>): List<String> {
    val low = text.lowercase()
    val found = ArrayList<String>()
    val taken = BooleanArray(low.length + 1)
    for (name in names.filter { it.trim().length >= 3 }.sortedByDescending { it.length }) {
        val n = name.trim().lowercase()
        var from = 0
        while (true) {
            val at = low.indexOf(n, from)
            if (at < 0) break
            val end = at + n.length
            val starts = at == 0 || !low[at - 1].isLetterOrDigit()
            val ends = end == low.length || !low[end].isLetterOrDigit()
            if (starts && ends && (at until end).none { taken[it] }) {
                found += name
                for (i in at until end) taken[i] = true
                break
            }
            from = at + 1
        }
    }
    return found
}
