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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

/** How fast new text appears, and how many characters at the edge are still fading in. */
private const val CHARS_PER_SECOND = 75f
private const val FADE_CHARS = 14

/**
 * Markdown the way a chat needs it: headings, lists (nested, numbered), quotes, tables, code,
 * links, strikethrough, and LaTeX formulas as pictures.
 *
 * With [animate] the text is revealed from the start and keeps chasing the text while it grows
 * (a streamed answer): the edge fades in and the layout never jumps, because the hidden
 * characters are already laid out. Code, tables and formulas appear whole when their turn comes.
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier, animate: Boolean = false) {
    val smooth = remember { animate }
    val doc = remember(markdown) { MarkdownParser.parse(markdown) }
    val total = remember(doc) { doc.blocks.sumOf { it.revealLength() } + FADE_CHARS }
    val reveal = remember { Animatable(if (smooth) 0f else total.toFloat()) }
    LaunchedEffect(total) {
        if (!smooth) {
            reveal.snapTo(total.toFloat())
        } else {
            val remaining = total - reveal.value
            if (remaining > 0.5f) {
                // Catch up faster when the text arrives faster than it can be shown.
                val speed = maxOf(CHARS_PER_SECOND, remaining * 1.6f)
                reveal.animateTo(total.toFloat(), tween((remaining * 1000f / speed).toInt().coerceAtLeast(16), easing = LinearEasing))
            }
        }
    }
    // Read as a whole number, so the text is rebuilt only when a new character appears.
    val visible by remember { derivedStateOf { reveal.value.toInt() } }

    Column(modifier.fillMaxWidth()) {
        var offset = 0
        var previous: Md? = null
        doc.blocks.forEach { block ->
            val length = block.revealLength()
            val local = visible - offset
            offset += length
            if (local > 0) {
                val gap = when {
                    previous == null -> 0.dp
                    previous is Md.Item && block is Md.Item -> 5.dp
                    else -> 12.dp
                }
                Box(Modifier.padding(top = gap)) { BlockView(block, doc.math, local) }
            }
            previous = block
        }
    }
}

@Composable
private fun BlockView(block: Md, math: List<MathSpan>, local: Int) {
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
                    Box(Modifier.padding(top = if (i == 0) 0.dp else 8.dp)) { BlockView(inner, math, Int.MAX_VALUE / 2) }
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
private fun RichText(rich: Rich, math: List<MathSpan>, style: TextStyle, local: Int, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val color = DevxColors.TextPrimary.toArgb()
    val inline = remember(rich, density.density) {
        val px = with(density) { style.fontSize.toPx() }
        rich.mathIds.distinct().associate { id -> "m$id" to inlineMath(math[id], px, density, color) }
    }
    val shown = remember(rich, local) {
        if (local >= rich.text.length + FADE_CHARS) rich.text else rich.text.revealed(local)
    }
    Text(shown, style = style, color = DevxColors.TextPrimary, inlineContent = inline, modifier = modifier)
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
                                RichText(cell.rich, math, style, Int.MAX_VALUE / 2, Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                if (r < all.lastIndex) HorizontalDivider(color = DevxColors.Outline, thickness = 0.5.dp)
            }
        }
    }
}

/** Characters before [shown] are visible, the last few fade in, the rest are invisible (but laid out). */
private fun AnnotatedString.revealed(shown: Int): AnnotatedString = buildAnnotatedString {
    append(this@revealed)
    val end = length
    val edge = shown.coerceIn(0, end)
    if (edge < end) addStyle(SpanStyle(color = Color.Transparent), edge, end)
    for (i in (edge - FADE_CHARS).coerceAtLeast(0) until edge) {
        val alpha = ((shown - i).toFloat() / FADE_CHARS).coerceIn(0f, 1f)
        if (alpha < 1f) addStyle(SpanStyle(color = DevxColors.TextPrimary.copy(alpha = alpha)), i, i + 1)
    }
}
