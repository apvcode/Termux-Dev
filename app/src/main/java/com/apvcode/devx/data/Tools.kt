package com.apvcode.devx.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** What a tool gives back to the model: words, and sometimes pictures. */
class ToolOutcome(val text: String, val images: List<WireImage> = emptyList())

/** How a running tool tells the screen what it is doing and which pages it has touched. */
interface ToolReport {
    fun activity(activity: ToolActivity?)
    fun source(source: Source)

    /** The assistant handed a file over: it appears as a card in the answer. */
    fun artifact(artifact: Artifact)
}

/**
 * The tools the assistant can use.
 *
 * Files: only the ones the person attached to this chat, never anything else on the phone. Instead of
 * pouring a whole PDF into the request, the model is told the file exists and looks at it itself: how big
 * it is, which page, what a word's context is.
 *
 * Web (when the person has switched it on): searching, and opening a page.
 */
class Toolbox(
    private val context: Context,
    private val store: AttachmentStore,
    private val web: WebTools,
    private val work: WorkTools,
    private val memory: MemoryTools,
    private val skillTools: SkillTools,
) {
    init {
        runCatching { PDFBoxResourceLoader.init(context.applicationContext) }
    }

    fun definitions(files: List<Attachment>, webOn: Boolean, workOn: Boolean = false, terminalOn: Boolean = false, memoryOn: Boolean = false, inProject: Boolean = false, skillsOn: Boolean = false): List<WireTool> {
        val out = ArrayList<WireTool>()
        if (files.isNotEmpty()) {
            out += tool("list_files", "List the files attached to this conversation, with their type and size (and the number of pages of a PDF).", schema())
            out += tool(
                "file_info",
                "Details of one attached file: type, size, pages of a PDF, length of a text, size of a picture, length of a video.",
                schema("name" to str("The file's name, as listed by list_files.")),
            )
            out += tool(
                "read_pdf_pages",
                "Read the text of pages of an attached PDF. Up to 10 pages per call; pages count from 1. If a page has no text it is probably a scan: use render_pdf_page.",
                schema(
                    "name" to str("The PDF's name."),
                    "first_page" to int("First page to read (from 1)."),
                    "last_page" to int("Last page to read (default: the first page)."),
                    required = listOf("name", "first_page"),
                ),
            )
            out += tool(
                "render_pdf_page",
                "Look at one page of an attached PDF as a picture (for scans, drawings, tables, layout).",
                schema("name" to str("The PDF's name."), "page" to int("The page (from 1).")),
            )
            out += tool(
                "read_text",
                "Read part of an attached text file (also text taken from Word, Excel, PowerPoint documents and databases). Gives up to 20000 characters from the offset, and says how long the whole text is.",
                schema(
                    "name" to str("The file's name."),
                    "offset" to int("Where to start, in characters (default 0)."),
                    "length" to int("How many characters (default 12000, at most 20000)."),
                    required = listOf("name"),
                ),
            )
            out += tool(
                "search_in_file",
                "Find a word or phrase in an attached PDF or text file. Gives where it occurs (the page of a PDF, the offset in a text) with the words around it.",
                schema("name" to str("The file's name."), "query" to str("What to look for.")),
            )
            out += tool(
                "view_image",
                "Look at an attached picture (or the first picture of a video) again.",
                schema("name" to str("The picture's name.")),
            )
        }
        if (webOn) out += web.definitions()
        if (workOn || terminalOn) out += work.definitions(workOn, terminalOn)
        if (memoryOn) out += memory.definitions(inProject)
        if (skillsOn) out += skillTools.definitions(copyToo = workOn || terminalOn)
        return out
    }

    suspend fun run(call: WireToolCall, files: List<Attachment>, report: ToolReport, chatId: String = "", project: String? = null): ToolOutcome {
        val args = runCatching { JSONObject(call.arguments.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        return try {
            when (call.name) {
                "web_search", "open_url" -> web.run(call.name, args, report)
                in work.names -> work.run(call.name, args, chatId, report)
                in memory.names -> memory.run(call.name, args, report, chatId, project)
                in skillTools.names -> skillTools.run(call.name, args, chatId, report)
                else -> withContext(Dispatchers.IO) { runFileTool(call.name, args, files, report) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolOutcome("The tool failed: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: OutOfMemoryError) {
            ToolOutcome("The tool ran out of memory: the file is too large to handle this way.")
        }
    }

    // ---------- files ----------

    private fun runFileTool(name: String, args: JSONObject, files: List<Attachment>, report: ToolReport): ToolOutcome {
        if (name == "list_files") {
            report.activity(ToolActivity(ToolKind.File, ""))
            return ToolOutcome(
                files.joinToString("\n") { "- ${it.name}: ${describe(it)}" }.ifBlank { "No files are attached." },
            )
        }
        val file = resolve(args.optString("name"), files) ?: return ToolOutcome(
            "No attached file matches \"${args.optString("name")}\". The files are: " + files.joinToString { it.name },
        )
        report.activity(ToolActivity(ToolKind.File, file.name))
        return when (name) {
            "file_info" -> ToolOutcome(describe(file, detailed = true))
            "read_pdf_pages" -> readPdf(file, args.optInt("first_page", 1), args.optInt("last_page", args.optInt("first_page", 1)))
            "render_pdf_page" -> renderPage(file, args.optInt("page", 1))
            "read_text" -> readText(file, args.optInt("offset", 0), args.optInt("length", 12_000))
            "search_in_file" -> search(file, args.optString("query"))
            "view_image" -> viewImage(file)
            else -> ToolOutcome("Unknown tool: $name")
        }
    }

    private fun resolve(wanted: String, files: List<Attachment>): Attachment? {
        val key = wanted.trim().lowercase()
        if (key.isEmpty()) return files.singleOrNull()
        files.firstOrNull { it.name.lowercase() == key }?.let { return it }
        val partial = files.filter { it.name.lowercase().contains(key) }
        return partial.singleOrNull()
    }

    private fun describe(a: Attachment, detailed: Boolean = false): String {
        val size = if (a.size >= 1_048_576) "%.1f MB".format(a.size / 1_048_576.0) else "${a.size / 1024} KB"
        return when (a.kind) {
            AttachmentKind.Pdf -> "PDF, ${pdfPageCount(a)?.let { "$it pages" } ?: "pages unknown"}, $size"
            AttachmentKind.Text -> {
                val text = runCatching { store.readText(a) }.getOrDefault("")
                "text, ${text.length} characters, ${text.count { it == '\n' } + 1} lines" + if (detailed) ", $size" else ""
            }
            AttachmentKind.Image -> {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(store.fileOf(a).absolutePath, o)
                "picture, ${o.outWidth}x${o.outHeight}, $size"
            }
            AttachmentKind.Video -> "video, ${a.durationMs / 1000} s, $size"
            AttachmentKind.File -> "file (${a.mime}), $size; its contents cannot be read"
        }
    }

    private fun pdfPageCount(a: Attachment): Int? = runCatching {
        ParcelFileDescriptor.open(store.fileOf(a), ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }
    }.getOrNull()

    private fun readPdf(a: Attachment, first: Int, last: Int): ToolOutcome {
        if (a.kind != AttachmentKind.Pdf) return ToolOutcome("${a.name} is not a PDF. Use read_text for text files.")
        val total = pdfPageCount(a) ?: return ToolOutcome("The PDF could not be opened.")
        val from = first.coerceIn(1, total)
        val to = last.coerceIn(from, minOf(total, from + 9))
        val out = StringBuilder("Pages $from to $to of $total of ${a.name}:\n")
        PDDocument.load(store.fileOf(a)).use { doc ->
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            for (page in from..to) {
                stripper.startPage = page
                stripper.endPage = page
                val text = stripper.getText(doc).trim()
                out.append("\n--- Page $page ---\n")
                out.append(if (text.isEmpty()) "(no text: it may be a scan or a picture; use render_pdf_page)" else text).append('\n')
                if (out.length > MAX_TOOL_TEXT) {
                    out.setLength(MAX_TOOL_TEXT)
                    out.append("\n[cut here: ask for the next pages separately]")
                    break
                }
            }
        }
        return ToolOutcome(out.toString())
    }

    private fun renderPage(a: Attachment, page: Int): ToolOutcome {
        if (a.kind != AttachmentKind.Pdf) return ToolOutcome("${a.name} is not a PDF.")
        ParcelFileDescriptor.open(store.fileOf(a), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                val index = (page - 1).coerceIn(0, renderer.pageCount - 1)
                renderer.openPage(index).use { p ->
                    val scale = 1400f / p.width
                    val bitmap = Bitmap.createBitmap(1400, (p.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    Canvas(bitmap).drawColor(Color.WHITE)
                    p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val bytes = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 82, bytes)
                    bitmap.recycle()
                    return ToolOutcome(
                        "Page ${index + 1} of ${renderer.pageCount} of ${a.name}, as a picture.",
                        listOf(WireImage("image/jpeg", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))),
                    )
                }
            }
        }
    }

    private fun readText(a: Attachment, offset: Int, length: Int): ToolOutcome {
        if (a.kind != AttachmentKind.Text) return ToolOutcome("${a.name} has no text to read (${describe(a)}).")
        val text = store.readText(a)
        val start = offset.coerceIn(0, text.length)
        val end = (start + length.coerceIn(1, MAX_TOOL_TEXT)).coerceAtMost(text.length)
        val more = if (end < text.length) "\n[the text goes on: ${text.length - end} more characters, from offset $end]" else "\n[end of the text]"
        return ToolOutcome("Characters $start to $end of ${text.length} of ${a.name}:\n" + text.substring(start, end) + more)
    }

    private fun search(a: Attachment, query: String): ToolOutcome {
        if (query.isBlank()) return ToolOutcome("Give a word or phrase to look for.")
        val out = StringBuilder()
        var found = 0
        fun note(where: String, text: String, at: Int) {
            val from = (at - 90).coerceAtLeast(0)
            val to = (at + query.length + 90).coerceAtMost(text.length)
            out.append("- ").append(where).append(": ...").append(text.substring(from, to).replace(Regex("\\s+"), " ")).append("...\n")
            found++
        }
        when (a.kind) {
            AttachmentKind.Text -> {
                val text = store.readText(a)
                var at = text.indexOf(query, ignoreCase = true)
                while (at >= 0 && found < 20) {
                    note("offset $at", text, at)
                    at = text.indexOf(query, at + query.length, ignoreCase = true)
                }
            }
            AttachmentKind.Pdf -> PDDocument.load(store.fileOf(a)).use { doc ->
                val stripper = PDFTextStripper().apply { sortByPosition = true }
                for (page in 1..doc.numberOfPages.coerceAtMost(500)) {
                    if (found >= 20) break
                    stripper.startPage = page
                    stripper.endPage = page
                    val text = stripper.getText(doc)
                    var at = text.indexOf(query, ignoreCase = true)
                    while (at >= 0 && found < 20) {
                        note("page $page", text, at)
                        at = text.indexOf(query, at + query.length, ignoreCase = true)
                    }
                }
            }
            else -> return ToolOutcome("${a.name} cannot be searched (${describe(a)}).")
        }
        return ToolOutcome(if (found == 0) "\"$query\" was not found in ${a.name}." else "Found $found place(s) in ${a.name}:\n$out")
    }

    private fun viewImage(a: Attachment): ToolOutcome {
        val file: File = when (a.kind) {
            AttachmentKind.Image -> store.fileOf(a)
            AttachmentKind.Video -> store.thumbOf(a)
            else -> return ToolOutcome("${a.name} is not a picture.")
        }
        val bytes = file.readBytes()
        return ToolOutcome("${a.name}, as a picture.", listOf(WireImage("image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP))))
    }

    private companion object {
        const val MAX_TOOL_TEXT = 20_000
    }
}

// ---------- small helpers for the tool descriptions ----------

internal fun tool(name: String, description: String, parameters: JSONObject) = WireTool(name, description, parameters)

internal fun str(description: String): JSONObject = JSONObject().put("type", "string").put("description", description)

internal fun int(description: String): JSONObject = JSONObject().put("type", "integer").put("description", description)

/** A JSON schema for the arguments: the pairs are the properties; [required] defaults to all of them. */
internal fun schema(vararg props: Pair<String, JSONObject>, required: List<String> = props.map { it.first }): JSONObject {
    val properties = JSONObject()
    props.forEach { (k, v) -> properties.put(k, v) }
    return JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(required))
}
