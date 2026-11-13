package com.apvcode.devx.data

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.apvcode.devx.model.Role
import java.io.OutputStream
import java.text.DateFormat
import java.util.Date

/** A chat written out as a file: Markdown (the text as it is) or PDF (pages to read or print). */
object ChatExport {
    /** The messages that are shown, from the first to the last, as Markdown. Thinking and tool steps are left out. */
    fun markdown(chat: Chat, me: String, devx: String, date: String = ""): String = buildString {
        append("# ").append(chat.title.ifBlank { "Chat" }).append("\n\n")
        if (date.isNotBlank()) append("*").append(date).append("*\n\n")
        for (m in chat.activePath()) {
            if (m.text.isBlank() && m.attachments.isEmpty()) continue
            append("### ").append(if (m.role == Role.User) me else devx).append("\n\n")
            if (m.text.isNotBlank()) append(m.text.trim()).append("\n\n")
            if (m.attachments.isNotEmpty()) append(m.attachments.joinToString(", ", prefix = "_", postfix = "_\n\n") { it.name })
        }
    }.trimEnd() + "\n"

    /** One piece of a page: a paragraph, a heading, a line of code block, or the name of a speaker. */
    internal class Block(val text: String, val kind: Kind)
    internal enum class Kind { Speaker, Heading, Body, Code }

    /** Markdown cut into blocks; the marks are taken out, so that the page shows the words, not the signs. */
    internal fun blocks(chat: Chat, me: String, devx: String): List<Block> {
        val out = ArrayList<Block>()
        out += Block(chat.title.ifBlank { "Chat" }, Kind.Heading)
        for (m in chat.activePath()) {
            if (m.text.isBlank() && m.attachments.isEmpty()) continue
            out += Block(if (m.role == Role.User) me else devx, Kind.Speaker)
            var inCode = false
            val code = StringBuilder()
            for (raw in m.text.trim().lines()) {
                if (raw.trimStart().startsWith("```")) {
                    if (inCode && code.isNotEmpty()) out += Block(code.toString().trimEnd(), Kind.Code)
                    code.clear()
                    inCode = !inCode
                    continue
                }
                if (inCode) { code.appendLine(raw); continue }
                val line = raw.trimEnd()
                if (line.isBlank()) continue
                val head = Regex("^#{1,6}\\s+(.*)$").matchEntire(line)
                if (head != null) { out += Block(plain(head.groupValues[1]), Kind.Heading); continue }
                val bullet = Regex("^\\s*[-*+]\\s+(.*)$").matchEntire(line)
                out += Block(if (bullet != null) "•  " + plain(bullet.groupValues[1]) else plain(line), Kind.Body)
            }
            if (inCode && code.isNotEmpty()) out += Block(code.toString().trimEnd(), Kind.Code)
            if (m.attachments.isNotEmpty()) out += Block(m.attachments.joinToString(", ") { it.name }, Kind.Body)
        }
        return out
    }

    /** Bold, italic and code marks taken out of a line of Markdown; links keep their words. */
    internal fun plain(line: String): String = line
        .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("__(.+?)__"), "$1")
        .replace(Regex("(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])"), "$1")
        .replace(Regex("`([^`]+)`"), "$1")

    /** Writes the chat as a PDF of A4 pages. Long lines wrap, long blocks go on to the next page. */
    fun pdf(chat: Chat, me: String, devx: String, out: OutputStream) {
        val width = 595
        val height = 842
        val margin = 48
        val textWidth = width - margin * 2
        val document = PdfDocument()
        var number = 0
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f

        fun newPage() {
            page?.let { document.finishPage(it) }
            number++
            page = document.startPage(PdfDocument.PageInfo.Builder(width, height, number).create())
            canvas = page!!.canvas
            y = margin.toFloat()
        }

        fun paint(size: Float, color: Int, bold: Boolean = false, mono: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = when {
                mono -> Typeface.MONOSPACE
                bold -> Typeface.DEFAULT_BOLD
                else -> Typeface.DEFAULT
            }
        }

        val body = paint(11f, Color.rgb(30, 30, 30))
        val code = paint(9.5f, Color.rgb(40, 40, 40), mono = true)
        val heading = paint(14f, Color.BLACK, bold = true)
        val title = paint(20f, Color.BLACK, bold = true)
        val speaker = paint(9f, Color.rgb(110, 110, 110), bold = true)
        val shade = Paint().apply { color = Color.rgb(242, 242, 242) }

        newPage()
        var first = true
        for (block in blocks(chat, me, devx)) {
            val p = when (block.kind) {
                Kind.Speaker -> speaker
                Kind.Heading -> if (first) title else heading
                Kind.Body -> body
                Kind.Code -> code
            }
            val gapBefore = when (block.kind) { Kind.Speaker -> 16f; Kind.Heading -> if (first) 0f else 8f; Kind.Body -> 5f; Kind.Code -> 6f }
            first = false
            val label = if (block.kind == Kind.Speaker) block.text.uppercase() else block.text
            val layout = StaticLayout.Builder.obtain(label, 0, label.length, p, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.18f).setIncludePad(false).build()
            y += gapBefore
            var line = 0
            while (line < layout.lineCount) {
                // As many lines as fit on what is left of the page.
                val room = height - margin - y
                var end = line
                while (end < layout.lineCount && layout.getLineBottom(end) - layout.getLineTop(line) <= room) end++
                if (end == line) {
                    // Not even one line fits: a new page (a speaker's name is not left alone at the foot of a page).
                    newPage()
                    continue
                }
                val c = canvas!!
                val top = layout.getLineTop(line)
                val bottom = layout.getLineBottom(end - 1)
                if (block.kind == Kind.Code) c.drawRect(margin - 4f, y - 2f, (margin + textWidth + 4).toFloat(), y + (bottom - top) + 2f, shade)
                c.save()
                c.translate(margin.toFloat(), y - top)
                c.clipRect(0f, top.toFloat(), textWidth.toFloat(), bottom.toFloat())
                layout.draw(c)
                c.restore()
                y += (bottom - top)
                line = end
                if (line < layout.lineCount) newPage()
            }
        }
        page?.let { document.finishPage(it) }
        document.writeTo(out)
        document.close()
    }

    fun fileName(title: String, extension: String): String {
        val clean = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r]+"), " ").replace(Regex("\\s+"), " ").trim().take(60).ifBlank { "chat" }
        return "$clean.$extension"
    }

    fun dateLabel(millis: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
}
