package com.apvcode.devx.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.text.appendInlineContent
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/** A piece of rich text: the styled string and which formulas (by index) sit inside it as pictures. */
class Rich(val text: AnnotatedString, val mathIds: List<Int>)

sealed interface Md {
    class Para(val rich: Rich) : Md
    class Head(val level: Int, val rich: Rich) : Md
    class Item(val depth: Int, val marker: String, val rich: Rich) : Md
    class Quote(val blocks: List<Md>) : Md
    class CodeBlock(val language: String, val code: String) : Md
    class Table(val header: List<Cell>, val rows: List<List<Cell>>) : Md
    class MathBlock(val math: MathSpan) : Md
    data object Rule : Md
}

class Cell(val rich: Rich, val align: TextAlign)

class MarkdownDoc(val blocks: List<Md>, val math: List<MathSpan>)

/** How much of the reveal animation a block takes: its characters, or one step for pictures and code. */
fun Md.revealLength(): Int = when (this) {
    is Md.Para -> rich.text.length
    is Md.Head -> rich.text.length
    is Md.Item -> rich.text.length + 1
    is Md.Quote -> blocks.sumOf { it.revealLength() }.coerceAtLeast(1)
    else -> 1
}

object MarkdownParser {
    private val parser: Parser = Parser.builder()
        .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
        .build()

    fun parse(markdown: String): MarkdownDoc {
        val extracted = MathExtract.extract(markdown)
        val document = parser.parse(extracted.text)
        return MarkdownDoc(blocks(document, extracted.math, 0), extracted.math)
    }

    private fun blocks(parent: Node, math: List<MathSpan>, depth: Int): List<Md> {
        val out = ArrayList<Md>()
        var node = parent.firstChild
        while (node != null) {
            when (node) {
                is Paragraph -> {
                    val only = (node.firstChild as? Text)?.takeIf { it.next == null }
                        ?.let { MathExtract.token.matchEntire(it.literal.trim()) }
                    val span = only?.groupValues?.get(1)?.toIntOrNull()?.let { math.getOrNull(it) }
                    if (span != null && span.display) out += Md.MathBlock(span) else out += Md.Para(rich(node, math))
                }
                is Heading -> out += Md.Head(node.level, rich(node, math))
                is BulletList -> items(node, math, depth, out, ordered = false, start = 1)
                is OrderedList -> items(node, math, depth, out, ordered = true, start = node.markerStartNumber ?: 1)
                is BlockQuote -> out += Md.Quote(blocks(node, math, depth))
                is FencedCodeBlock -> out += Md.CodeBlock(node.info.orEmpty().trim(), node.literal.trimEnd('\n'))
                is IndentedCodeBlock -> out += Md.CodeBlock("", node.literal.trimEnd('\n'))
                is ThematicBreak -> out += Md.Rule
                is TableBlock -> table(node, math)?.let { out += it }
                is HtmlBlock -> out += Md.Para(Rich(AnnotatedString(node.literal.trim()), emptyList()))
                else -> Unit
            }
            node = node.next
        }
        return out
    }

    private fun items(list: Node, math: List<MathSpan>, depth: Int, out: MutableList<Md>, ordered: Boolean, start: Int) {
        var n = start
        var item = list.firstChild
        while (item != null) {
            if (item is ListItem) {
                val marker = if (ordered) "${n++}." else if (depth % 2 == 0) "•" else "◦"
                var child = item.firstChild
                var first = true
                while (child != null) {
                    when (child) {
                        is Paragraph -> {
                            if (first) out += Md.Item(depth, marker, rich(child, math))
                            else out += Md.Para(rich(child, math))
                        }
                        is BulletList -> items(child, math, depth + 1, out, false, 1)
                        is OrderedList -> items(child, math, depth + 1, out, true, child.markerStartNumber ?: 1)
                        else -> out += otherBlock(child, math, depth)
                    }
                    first = false
                    child = child.next
                }
            }
            item = item.next
        }
    }

    /** A block inside a list item that is not a paragraph or a list (a code block, a quote, a table). */
    private fun otherBlock(node: Node, math: List<MathSpan>, depth: Int): List<Md> = when (node) {
        is FencedCodeBlock -> listOf(Md.CodeBlock(node.info.orEmpty().trim(), node.literal.trimEnd('\n')))
        is IndentedCodeBlock -> listOf(Md.CodeBlock("", node.literal.trimEnd('\n')))
        is BlockQuote -> listOf(Md.Quote(blocks(node, math, depth)))
        is TableBlock -> listOfNotNull(table(node, math))
        else -> emptyList()
    }

    private fun table(node: TableBlock, math: List<MathSpan>): Md.Table? {
        var header: List<Cell> = emptyList()
        val rows = ArrayList<List<Cell>>()
        var section = node.firstChild
        while (section != null) {
            var row = section.firstChild
            while (row != null) {
                if (row is TableRow) {
                    val cells = ArrayList<Cell>()
                    var cell = row.firstChild
                    while (cell is TableCell) {
                        val align = when (cell.alignment) {
                            TableCell.Alignment.CENTER -> TextAlign.Center
                            TableCell.Alignment.RIGHT -> TextAlign.End
                            else -> TextAlign.Start
                        }
                        cells += Cell(rich(cell, math), align)
                        cell = cell.next
                    }
                    if (section is TableHead) header = cells else rows += cells
                }
                row = row.next
            }
            section = section.next
        }
        return if (header.isEmpty() && rows.isEmpty()) null else Md.Table(header, rows)
    }

    // ---------- inline ----------

    private fun rich(parent: Node, math: List<MathSpan>): Rich {
        val ids = ArrayList<Int>()
        val text = buildAnnotatedString { inline(parent.firstChild, math, ids) }
        return Rich(text, ids)
    }

    private fun AnnotatedString.Builder.inline(first: Node?, math: List<MathSpan>, ids: MutableList<Int>) {
        var cursor = first
        while (cursor != null) {
            val node: Node = cursor
            when (node) {
                is Text -> appendWithMath(node.literal, math, ids)
                is SoftLineBreak -> append(' ')
                is HardLineBreak -> append('\n')
                is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { inline(node.firstChild, math, ids) }
                is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { inline(node.firstChild, math, ids) }
                is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { inline(node.firstChild, math, ids) }
                is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFF2A2A2A))) { append(node.literal) }
                is Link -> withLink(
                    LinkAnnotation.Url(node.destination, TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))),
                ) { inline(node.firstChild, math, ids) }
                is Image -> inline(node.firstChild, math, ids)
                is HtmlInline -> Unit
                else -> inline(node.firstChild, math, ids)
            }
            cursor = node.next
        }
    }

    /** Text may carry formula tokens: each becomes an inline picture slot (`appendInlineContent`). */
    private fun AnnotatedString.Builder.appendWithMath(literal: String, math: List<MathSpan>, ids: MutableList<Int>) {
        var last = 0
        for (match in MathExtract.token.findAll(literal)) {
            append(literal.substring(last, match.range.first))
            val id = match.groupValues[1].toInt()
            if (id in math.indices) {
                ids += id
                appendInlineContent("m$id", math[id].latex)
            }
            last = match.range.last + 1
        }
        append(literal.substring(last))
    }
}
