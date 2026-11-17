package com.apvcode.devx.ui.markdown

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apvcode.devx.ui.chat.CodeView
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import android.os.SystemClock
import androidx.compose.runtime.CompositionLocalProvider

/** The fading edge is wider the faster the writing goes, so that it never turns into a jump: between these widths, in characters. */
private const val FADE_MIN = 14f
private const val FADE_CHARS = 64

/** How wide the fading edge is right now (set by the pace of the writing, read by the drawing of every paragraph). */
private val LocalRevealFade = androidx.compose.runtime.compositionLocalOf { androidx.compose.runtime.mutableFloatStateOf(FADE_MIN) }

/**
 * Markdown the way a chat needs it: headings, lists (nested, numbered), quotes, tables, code,
 * links, strikethrough, and LaTeX formulas as pictures.
 *
 * With [animate] the text is revealed from the start and keeps chasing the text while it grows
 * (a streamed answer): the edge fades in and the layout never jumps, because the hidden
 * characters are already laid out. Code, tables and formulas appear whole when their turn comes.
 */
/** A block with the list of formulas it was read together with (each piece of the text has its own). */
private class Placed(val block: Md, val math: List<MathSpan>)

@Composable
fun MarkdownText(rawMarkdown: String, modifier: Modifier = Modifier, animate: Boolean = false) {
    // A citation the assistant wrote, [[file, p. 3]], becomes a link that opens the source.
    val markdown = remember(rawMarkdown) { Citations.rewrite(rawMarkdown) }
    val smooth = remember { animate }
    // The text is read in pieces; the pieces that are finished are read once and kept, so that only the end of an answer that
    // is still being written is read again with every update.
    val kept = remember { HashMap<String, MarkdownDoc>() }
    val placed = remember(markdown) {
        val pieces = MarkdownSegments.split(markdown)
        val docs = pieces.map { kept.getOrPut(it) { MarkdownParser.parse(it) } }
        kept.keys.retainAll(pieces.toSet())
        docs.flatMap { d -> d.blocks.map { Placed(it, d.math) } }
    }
    // Where each block begins in the running count of characters.
    val offsets = remember(placed) {
        var at = 0
        IntArray(placed.size).also { out -> placed.forEachIndexed { i, p -> out[i] = at; at += p.block.revealLength() } }
    }
    val total = remember(placed) { placed.sumOf { it.block.revealLength() } + FADE_CHARS }
    val reveal = remember { Animatable(if (smooth) 0f else total.toFloat()) }
    val goal = androidx.compose.runtime.rememberUpdatedState(total)
    val fade = remember { androidx.compose.runtime.mutableFloatStateOf(FADE_MIN) }
    LaunchedEffect(smooth) {
        if (!smooth) return@LaunchedEffect
        // One steady pace, frame by frame. It is kept across the arrival of new text (the loop below is restarted for every piece, but the
        // speed and the time of the last frame are not), so no frame is lost and the speed never starts over. The writing trails the
        // text by a short while, however fast the text comes (see [RevealPace]); the faster it goes, the wider the fading edge.
        var lastGrowth = SystemClock.uptimeMillis()
        var lastFrame = 0L
        var speed = RevealPace.MIN_CPS
        androidx.compose.runtime.snapshotFlow { goal.value }.collectLatest { target ->
            lastGrowth = SystemClock.uptimeMillis()
            while (reveal.value < target) {
                val now = androidx.compose.runtime.withFrameNanos { it }
                val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1_000_000_000f).coerceIn(0f, 0.05f)
                lastFrame = now
                val behind = target - reveal.value
                val growing = SystemClock.uptimeMillis() - lastGrowth < 400
                speed = RevealPace.next(speed, behind, growing, dt)
                fade.floatValue += ((speed * 0.16f).coerceIn(FADE_MIN, FADE_CHARS.toFloat()) - fade.floatValue) * 0.15f
                reveal.snapTo(minOf(target.toFloat(), reveal.value + speed * dt))
            }
            // Caught up: the next piece of text starts from the slowest pace, and its first frame only sets the clock.
            speed = RevealPace.MIN_CPS
            lastFrame = 0L
        }
    }
    LaunchedEffect(total) { if (!smooth) reveal.snapTo(total.toFloat()) }
    // The count of blocks that have begun to show: the layout is rebuilt only when a new block starts, not with every
    // character. The edge inside a block moves in the drawing (see [revealing]), so it costs no new layout.
    val begun by remember(placed) { derivedStateOf { val v = reveal.value.toInt(); offsets.count { it < v } } }

    CompositionLocalProvider(LocalRevealFade provides fade) {
    Column(modifier.fillMaxWidth()) {
        var previous: Md? = null
        placed.forEachIndexed { i, item ->
            val block = item.block
            if (i < begun) {
                val gap = when {
                    previous == null -> 0.dp
                    previous is Md.Item && block is Md.Item -> 5.dp
                    else -> 12.dp
                }
                val start = offsets[i]
                val end = start + block.revealLength() + FADE_CHARS
                androidx.compose.runtime.key(i) {
                    // A block that has been shown whole stops following the animation: otherwise every paragraph of a long
                    // answer would be drawn again with every frame, and the longer the answer the worse it would lag.
                    val finished by remember { derivedStateOf { reveal.value >= end } }
                    val follow: () -> Float = if (finished) ALL else { { reveal.value - start } }
                    Box(Modifier.padding(top = gap)) { BlockView(block, item.math, follow) }
                }
            }
            previous = block
        }
    }
    }
}

/** A block that is shown whole: how far its reveal has gone is so large that nothing is hidden. */
private val ALL: () -> Float = { Float.MAX_VALUE / 2 }

@Composable
private fun BlockView(block: Md, math: List<MathSpan>, local: () -> Float) {
    when (block) {
        is Md.Para -> RichText(block.rich, math, DevxType.Reply, local)
        is Md.Head -> RichText(block.rich, math, headingStyle(block.level), local)
        is Md.Item -> Row(Modifier.padding(start = (block.depth * 22).dp)) {
            Text(
                block.marker,
                style = DevxType.Reply,
                color = DevxColors.TextSecondary,
                modifier = Modifier.width(if (block.marker.length > 2) 34.dp else 24.dp),
            )
            RichText(block.rich, math, DevxType.Reply, local, Modifier.weight(1f))
        }
        is Md.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(DevxColors.Outline, RoundedCornerShape(2.dp)))
            Column(Modifier.padding(start = 14.dp)) {
                block.blocks.forEachIndexed { i, inner ->
                    Box(Modifier.padding(top = if (i == 0) 0.dp else 8.dp)) { BlockView(inner, math, ALL) }
                }
            }
        }
        is Md.CodeBlock -> CodeView(block.language, block.code)
        is Md.Table -> TableView(block, math)
        is Md.MathBlock -> Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
            MathPicture(block.math.latex, with(LocalDensity.current) { 22.sp.toPx() })
        }
        Md.Rule -> HorizontalDivider(color = DevxColors.Outline, thickness = 1.dp, modifier = Modifier.padding(vertical = 6.dp))
    }
}

private fun headingStyle(level: Int): TextStyle = DevxType.Reply.copy(
    fontWeight = FontWeight.SemiBold,
    fontSize = when (level) { 1 -> 26.sp; 2 -> 22.sp; 3 -> 19.sp; else -> 17.sp },
    lineHeight = when (level) { 1 -> 32.sp; 2 -> 28.sp; 3 -> 25.sp; else -> 24.sp },
)

/** Text with formulas in it: each formula is a picture sitting in the line. */
@Composable
private fun RichText(rich: Rich, math: List<MathSpan>, style: TextStyle, local: () -> Float, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val color = DevxColors.TextPrimary.toArgb()
    val inline = remember(rich, density.density) {
        val px = with(density) { style.fontSize.toPx() }
        rich.mathIds.distinct().associate { id -> "m$id" to inlineMath(math[id], px, density, color) }
    }
    val holder = remember { LayoutHolder() }
    val length = rich.text.length
    val fadePx = with(density) { (if (style.fontSize.isSp) style.fontSize.toPx() else 16.sp.toPx()) * 5f }
    val fade = LocalRevealFade.current
    Text(
        rich.text, style = style, color = DevxColors.TextPrimary, inlineContent = inline,
        onTextLayout = { holder.layout = it },
        modifier = modifier.revealing(length, { fade.floatValue }, local, holder),
    )
}

/** The last layout of a text, kept for the drawing (it is not state: the drawing reads it, nothing is rebuilt for it). */
private class LayoutHolder { var layout: TextLayoutResult? = null }

/**
 * Shows the first [shown] characters of a text that is already laid out, with the last few fading in: the lines above the
 * edge are drawn whole, the line of the edge is drawn through a mask that fades to nothing at the edge, the rest is not
 * drawn. Only the drawing changes from frame to frame; the text is not rebuilt and not laid out again.
 */
private fun Modifier.revealing(length: Int, fade: () -> Float, shown: () -> Float, holder: LayoutHolder): Modifier =
    if (shown === ALL) this else revealingDraw(length, fade, shown, holder)

private fun Modifier.revealingDraw(length: Int, fadeWidth: () -> Float, shown: () -> Float, holder: LayoutHolder): Modifier = drawWithContent {
    val s = shown()
    val fadeChars = fadeWidth()
    val layout = holder.layout
    if (s >= length + fadeChars || length == 0 || (layout != null && layout.layoutInput.layoutDirection == LayoutDirection.Rtl)) {
        drawContent()
        return@drawWithContent
    }
    if (layout == null || s <= 0f) return@drawWithContent

    fun lineOf(at: Float) = layout.getLineForOffset(at.toInt().coerceIn(0, length))
    // Where a character offset (with a fraction) falls on its line, from the left of the text.
    fun xAt(at: Float): Float {
        val whole = at.toInt().coerceIn(0, length)
        val line = layout.getLineForOffset(whole)
        val x0 = layout.getHorizontalPosition(whole, true)
        if (whole >= length) return x0
        val next = layout.getLineForOffset(whole + 1)
        val x1 = if (next == line) layout.getHorizontalPosition(whole + 1, true) else layout.getLineRight(line)
        return x0 + (x1 - x0) * (at - whole)
    }

    val edge = s.coerceIn(0f, length.toFloat())
    val oldEdge = (s - fadeChars).coerceIn(0f, length.toFloat())
    val line = lineOf(edge)
    val top = layout.getLineTop(line)
    val bottom = layout.getLineBottom(line)
    val xs = xAt(edge)
    // The fade covers the last few characters; when they sit on the line before, this line starts from its left.
    val xo = if (lineOf(oldEdge) == line) xAt(oldEdge) else layout.getLineLeft(line)
    val fadeFrom = minOf(xo, xs).coerceAtLeast(0f)

    // Everything down to the end of the line of the edge is drawn; what comes after is not. On that line the part to the right of
    // the edge is covered with the background colour and the fade is a gradient of it: no offscreen layer is needed (on a phone
    // each of those costs a whole extra pass of the graphics chip, with every frame).
    val bg = DevxColors.Bg
    clipRect(0f, 0f, size.width, bottom) { this@drawWithContent.drawContent() }
    val w = size.width.coerceAtLeast(1f)
    drawRect(
        brush = Brush.horizontalGradient(
            0f to bg.copy(alpha = 0f),
            (fadeFrom / w).coerceIn(0f, 1f) to bg.copy(alpha = 0f),
            (xs / w).coerceIn(0f, 1f) to bg,
            1f to bg,
            startX = 0f, endX = size.width,
        ),
        topLeft = Offset(0f, top),
        size = Size(size.width, bottom - top),
    )
}

private fun inlineMath(span: MathSpan, fontPx: Float, density: Density, color: Int): InlineTextContent {
    val rendered = MathRenderer.render(span.latex, fontPx, color)
    val width = rendered?.let { with(density) { it.width.toSp() } } ?: (span.latex.length * 9).sp
    val height = rendered?.let { with(density) { it.height.toSp() } } ?: 24.sp
    return InlineTextContent(Placeholder(width, height, PlaceholderVerticalAlign.TextCenter)) {
        if (rendered != null) {
            Image(rendered.bitmap, span.latex, Modifier.fillMaxSize())
        } else {
            Text(span.latex, style = DevxType.Mono, color = DevxColors.TextSecondary)
        }
    }
}

@Composable
private fun MathPicture(latex: String, textSizePx: Float) {
    val density = LocalDensity.current
    val color = DevxColors.TextPrimary.toArgb()
    val rendered = remember(latex, textSizePx) { MathRenderer.render(latex, textSizePx, color) }
    if (rendered != null) {
        Image(
            rendered.bitmap,
            latex,
            Modifier.size(with(density) { rendered.width.toDp() }, with(density) { rendered.height.toDp() }),
        )
    } else {
        Text(latex, style = DevxType.Mono, color = DevxColors.TextSecondary)
    }
}

@Composable
private fun TableView(table: Md.Table, math: List<MathSpan>) {
    val columns = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    val widths = remember(table) {
        (0 until columns).map { c ->
            val longest = (listOf(table.header) + table.rows).maxOf { row -> row.getOrNull(c)?.rich?.text?.length ?: 0 }
            (longest * 9 + 32).coerceIn(96, 260).dp
        }
    }
    val shape = RoundedCornerShape(14.dp)
    BoxWithConstraints(Modifier.fillMaxWidth().clip(shape).border(1.dp, DevxColors.Outline, shape)) {
        // A narrow table is stretched to the full width; a wide one scrolls sideways.
        val extra = ((maxWidth - widths.fold(0.dp) { sum, w -> sum + w }) / columns).coerceAtLeast(0.dp)
        val fitted = widths.map { it + extra }
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            val all = (if (table.header.isNotEmpty()) listOf(table.header) else emptyList()) + table.rows
            all.forEachIndexed { r, cells ->
                Row(Modifier.background(if (r == 0 && table.header.isNotEmpty()) DevxColors.Card else Color.Transparent)) {
                    (0 until columns).forEach { c ->
                        val cell = cells.getOrNull(c)
                        Box(Modifier.width(fitted[c]).padding(horizontal = 12.dp, vertical = 10.dp)) {
                            if (cell != null) {
                                val style = DevxType.Body.copy(
                                    textAlign = cell.align,
                                    fontWeight = if (r == 0 && table.header.isNotEmpty()) FontWeight.Medium else FontWeight.Normal,
                                )
                                RichText(cell.rich, math, style, ALL, Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                if (r < all.lastIndex) HorizontalDivider(color = DevxColors.Outline, thickness = 0.5.dp)
            }
        }
    }
}
