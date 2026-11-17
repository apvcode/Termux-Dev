package com.apvcode.devx.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What the assistant can do with the pictures it was given: look closer at a part (zoom), turn a picture, make a dim one
 * brighter or a faint scan sharper, pull a picture out of a PDF, take a frame of a video. It works from the biggest copy
 * that was kept (not the reduced one that went into the request), so a small print or a far sign can still be read.
 */
class ImageTools(
    private val context: Context,
    private val store: AttachmentStore,
    private val artifacts: ArtifactStore,
) {
    val names: Set<String> = setOf("edit_image", "list_pdf_images")

    fun definitions(files: List<Attachment>): List<WireTool> {
        val out = ArrayList<WireTool>()
        if (files.any { it.kind == AttachmentKind.Image || it.kind == AttachmentKind.Pdf || it.kind == AttachmentKind.Video }) {
            out += tool(
                "edit_image",
                "Look closer at an attached picture, a page of a PDF, a picture inside a PDF, or a moment of a video, and change how it looks. " +
                    "You get the result as a picture to look at. Use it whenever something is too small, dark, faint, blurred or sideways to make out: " +
                    "cut out the part (left/top/right/bottom, in percent of the picture as you first saw it) and it is shown larger and sharper, " +
                    "taken from the full-size original, not from the reduced copy; turn it (rotate), brighten it, raise the contrast, make it grey or black and white. " +
                    "Steps run in this order: cut out the area, flip and rotate, enlarge or shrink to max_side, then the colour steps (auto_levels, brightness, contrast, grayscale, invert), sharpen, threshold. " +
                    "For a PDF give the page; to take a picture that is inside the page (see list_pdf_images) also give its number as image: you get it at its own full resolution. " +
                    "For a video give the second. With keep the result is also handed to the person as a file (a cut-out, a turned or cleaned photo they asked for).",
                schema(
                    "name" to str("The file's name (a picture, a PDF or a video)."),
                    "page" to int("PDF only: the page (from 1). Without image, the page as it is drawn."),
                    "image" to int("PDF only: the number of a picture on that page, from list_pdf_images."),
                    "second" to numProp("Video only: the moment, in seconds from the start."),
                    "left" to numProp("Left edge of the area to look at, percent of the width (0 to 100, default 0)."),
                    "top" to numProp("Top edge, percent of the height (default 0)."),
                    "right" to numProp("Right edge, percent of the width (default 100)."),
                    "bottom" to numProp("Bottom edge, percent of the height (default 100)."),
                    "rotate" to numProp("Degrees clockwise (90, 180, 270, or a small angle to straighten a crooked one)."),
                    "flip" to str("\"horizontal\" or \"vertical\"."),
                    "brightness" to int("-100 (darker) to 100 (lighter)."),
                    "contrast" to int("-100 (flatter) to 200 (much stronger)."),
                    "auto_levels" to boolProp("Stretch the tones to the full range: for dim, hazy or faded photos and scans."),
                    "grayscale" to boolProp("Turn the picture grey."),
                    "invert" to boolProp("Swap light and dark (for white text on black)."),
                    "sharpen" to boolProp("Make edges and small text crisper."),
                    "threshold" to int("1 to 254: pure black and white, darker than this is black. For faint print or handwriting; try 110 to 160."),
                    "max_side" to int("The longer side of the result in pixels (default 1600, at most 2400; with keep, at most 4096)."),
                    "keep" to boolProp("Also hand the result to the person as a file (default false)."),
                    required = listOf("name"),
                ),
            )
        }
        if (files.any { it.kind == AttachmentKind.Pdf }) {
            out += tool(
                "list_pdf_images",
                "List the pictures inside pages of an attached PDF (photos, scans, drawings): their number on the page, size in pixels and kind. Up to 20 pages per call. " +
                    "Then take one with edit_image (name, page, image).",
                schema(
                    "name" to str("The PDF's name."),
                    "first_page" to int("First page (from 1)."),
                    "last_page" to int("Last page (default: the first page)."),
                    required = listOf("name", "first_page"),
                ),
            )
        }
        return out
    }

    fun run(name: String, args: JSONObject, file: Attachment, report: ToolReport): ToolOutcome = when (name) {
        "edit_image" -> edit(file, args, report)
        "list_pdf_images" -> listPdfImages(file, args.optInt("first_page", 1), args.optInt("last_page", args.optInt("first_page", 1)))
        else -> ToolOutcome("Unknown tool: $name")
    }

    // ---------- the pictures inside a PDF ----------

    /** The pictures on a page, in a fixed order (the numbers that list_pdf_images shows and edit_image takes). */
    private fun pageImages(page: PDPage): List<PDImageXObject> {
        val out = ArrayList<PDImageXObject>()
        fun walk(resources: PDResources?, depth: Int) {
            if (resources == null) return
            for (n in resources.xObjectNames) {
                val x = runCatching { resources.getXObject(n) }.getOrNull() ?: continue
                if (x is PDImageXObject) out += x
                else if (x is PDFormXObject && depth < 3) walk(runCatching { x.resources }.getOrNull(), depth + 1)
            }
        }
        walk(page.resources, 0)
        return out
    }

    private fun listPdfImages(a: Attachment, first: Int, last: Int): ToolOutcome {
        if (a.kind != AttachmentKind.Pdf) return ToolOutcome("${a.name} is not a PDF.")
        PDDocument.load(store.fileOf(a)).use { doc ->
            val total = doc.numberOfPages
            val from = first.coerceIn(1, total)
            val to = last.coerceIn(from, min(total, from + 19))
            val out = StringBuilder("Pictures inside pages $from to $to of $total of ${a.name}:\n")
            var any = false
            for (p in from..to) {
                val images = runCatching { pageImages(doc.getPage(p - 1)) }.logged("imagetools").getOrDefault(emptyList())
                if (images.isEmpty()) continue
                any = true
                out.append("Page $p: ")
                out.append(images.take(25).withIndex().joinToString("; ") { (i, img) ->
                    val kind = runCatching { img.suffix }.getOrNull()?.uppercase()?.let { " $it" }.orEmpty()
                    "#${i + 1} ${img.width}x${img.height}$kind" + if (img.width < 48 || img.height < 48) " (tiny)" else ""
                })
                if (images.size > 25) out.append("; and ${images.size - 25} more")
                out.append('\n')
            }
            if (!any) out.append("(none in these pages: a page may still be drawn as vector graphics or be a plain scan; look at it with render_pdf_page or edit_image with page only)\n")
            else out.append("A page with one big picture is usually a scan: edit_image with image=1 gives it at its own resolution.")
            return ToolOutcome(out.toString())
        }
    }

    // ---------- editing ----------

    private class Loaded(val bitmap: Bitmap, val what: String, val cropped: Boolean)

    private fun percent(args: JSONObject, key: String, default: Double): Double =
        args.optDouble(key, default).let { if (it.isNaN()) default else it }.coerceIn(0.0, 100.0)

    private fun edit(a: Attachment, args: JSONObject, report: ToolReport): ToolOutcome {
        val left = percent(args, "left", 0.0)
        val top = percent(args, "top", 0.0)
        val right = percent(args, "right", 100.0)
        val bottom = percent(args, "bottom", 100.0)
        if (right - left < 0.5 || bottom - top < 0.5) {
            return ToolOutcome("The area is empty: right must be bigger than left and bottom bigger than top (percent of the picture, 0 to 100).")
        }
        val keep = args.optBoolean("keep", false)
        val sideCap = if (keep) (if (a.kind == AttachmentKind.Pdf) 3000 else 4096) else 2400
        val side = args.optInt("max_side", if (keep) sideCap else 1600).coerceIn(256, sideCap)
        val area = Area(left, top, right, bottom)

        val loaded = when (a.kind) {
            AttachmentKind.Image -> loadImage(a, area, side)
            AttachmentKind.Pdf -> if (args.has("image")) loadPdfImage(a, args.optInt("page", 1), args.optInt("image", 1), area, side) else loadPdfPage(a, args.optInt("page", 1), area, side)
            AttachmentKind.Video -> loadVideoFrame(a, args.optDouble("second", 0.0).let { if (it.isNaN()) 0.0 else it }, area)
            else -> return ToolOutcome("${a.name} is not a picture, a PDF or a video.")
        }

        val steps = ArrayList<String>()
        if (loaded.cropped) steps += "area ${fmt(left)}-${fmt(right)}% across, ${fmt(top)}-${fmt(bottom)}% down"
        var bitmap = loaded.bitmap

        val turn = args.optDouble("rotate", 0.0).let { if (it.isNaN()) 0.0 else it % 360.0 }
        val flip = args.optString("flip").lowercase()
        if (turn != 0.0 || flip.startsWith("h") || flip.startsWith("v")) {
            val m = Matrix()
            if (flip.startsWith("h")) { m.postScale(-1f, 1f); steps += "flipped sideways" }
            if (flip.startsWith("v")) { m.postScale(1f, -1f); steps += "flipped upside down" }
            if (turn != 0.0) { m.postRotate(turn.toFloat()); steps += "turned ${fmt(turn)}° clockwise" }
            val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
            if (turned !== bitmap) bitmap.recycle()
            // The corners that a crooked angle opens are white, not black.
            bitmap = if (turn % 90.0 != 0.0) onWhite(turned) else turned
        }

        bitmap = fit(bitmap, side, enlarge = loaded.cropped)

        val matrix = ColorMatrix()
        var changed = false
        if (args.optBoolean("grayscale", false)) {
            matrix.postConcat(ColorMatrix().apply { setSaturation(0f) }); changed = true; steps += "grey"
        }
        if (args.optBoolean("auto_levels", false)) {
            matrix.postConcat(levels(bitmap)); changed = true; steps += "tones stretched"
        }
        val contrast = args.optInt("contrast", 0).coerceIn(-100, 200)
        val brightness = args.optInt("brightness", 0).coerceIn(-100, 100)
        if (contrast != 0 || brightness != 0) {
            val c = 1f + contrast / 100f
            val shift = 128f * (1f - c) + brightness * 2.55f
            matrix.postConcat(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, shift, 0f, c, 0f, 0f, shift, 0f, 0f, c, 0f, shift, 0f, 0f, 0f, 1f, 0f)))
            changed = true
            if (brightness != 0) steps += "brightness $brightness"
            if (contrast != 0) steps += "contrast $contrast"
        }
        if (args.optBoolean("invert", false)) {
            matrix.postConcat(ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))
            changed = true; steps += "inverted"
        }
        if (changed) bitmap = colored(bitmap, matrix)
        if (args.optBoolean("sharpen", false)) { bitmap = sharpened(bitmap); steps += "sharpened" }
        val threshold = args.optInt("threshold", 0)
        if (threshold in 1..254) { bitmap = blackAndWhite(bitmap, threshold); steps += "black and white at $threshold" }

        var handed = ""
        if (keep) {
            val base = a.name.substringBeforeLast('.').ifBlank { "picture" }
            val temp = File(context.cacheDir, "edit-${System.nanoTime()}.jpg")
            try {
                FileOutputStream(temp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                val artifact = artifacts.add(temp, "$base-edited.jpg")
                report.artifact(artifact)
                handed = " It was also handed to the person as a file card (${artifact.name}, ${artifact.size / 1024} KB)."
            } finally {
                temp.delete()
            }
        }

        // What the model looks at is never bigger than 1600 px a side unless it asked for more (a request asked for the size).
        val shown = fit(bitmap, if (keep) min(side, 1600) else side, enlarge = false)
        val bytes = ByteArrayOutputStream()
        shown.compress(Bitmap.CompressFormat.JPEG, 86, bytes)
        val width = shown.width
        val height = shown.height
        if (shown !== bitmap) shown.recycle()
        bitmap.recycle()
        val text = "${loaded.what}, shown at ${width}x$height" + (if (steps.isEmpty()) "" else " (" + steps.joinToString(", ") + ")") + "." + handed
        return ToolOutcome(text, listOf(WireImage("image/jpeg", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))))
    }

    private class Area(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        val whole: Boolean get() = left <= 0.0 && top <= 0.0 && right >= 100.0 && bottom >= 100.0
        fun rect(width: Int, height: Int): Rect {
            val l = (left / 100 * width).roundToInt().coerceIn(0, width - 1)
            val t = (top / 100 * height).roundToInt().coerceIn(0, height - 1)
            val r = (right / 100 * width).roundToInt().coerceIn(l + 1, width)
            val b = (bottom / 100 * height).roundToInt().coerceIn(t + 1, height)
            return Rect(l, t, r, b)
        }
    }

    private fun fmt(v: Double): String = if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else "%.1f".format(v)

    /** A photo from the biggest kept copy, only the area asked for, decoded at no more detail than will be shown. */
    private fun loadImage(a: Attachment, area: Area, side: Int): Loaded {
        val file = store.originalOf(a)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) throw IllegalStateException("The picture could not be read.")
        val rect = area.rect(bounds.outWidth, bounds.outHeight)
        var sample = 1
        val longSide = max(rect.width(), rect.height())
        while (longSide / (sample * 2) >= side) sample *= 2
        val decoder = BitmapRegionDecoder.newInstance(file.path, false) ?: throw IllegalStateException("The picture could not be read.")
        val decoded = try {
            decoder.decodeRegion(rect, BitmapFactory.Options().apply { inSampleSize = sample })
        } finally {
            decoder.recycle()
        }
        val bitmap = decoded ?: throw IllegalStateException("The picture could not be decoded.")
        return Loaded(bitmap, "${a.name} (the whole picture is ${bounds.outWidth}x${bounds.outHeight})", !area.whole)
    }

    /** A page of a PDF drawn at the size the area needs: only the area is drawn, so a small part comes out sharp. */
    private fun loadPdfPage(a: Attachment, page: Int, area: Area, side: Int): Loaded {
        ParcelFileDescriptor.open(store.fileOf(a), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                val index = (page - 1).coerceIn(0, renderer.pageCount - 1)
                renderer.openPage(index).use { p ->
                    val rect = area.rect(p.width * 10, p.height * 10)
                    val regionW = rect.width() / 10f
                    val regionH = rect.height() / 10f
                    val scale = side / max(regionW, regionH)
                    val w = (regionW * scale).roundToInt().coerceAtLeast(1)
                    val h = (regionH * scale).roundToInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    Canvas(bitmap).drawColor(Color.WHITE)
                    val m = Matrix().apply {
                        setScale(scale, scale)
                        postTranslate(-rect.left / 10f * scale, -rect.top / 10f * scale)
                    }
                    p.render(bitmap, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return Loaded(bitmap, "Page ${index + 1} of ${renderer.pageCount} of ${a.name}", !area.whole)
                }
            }
        }
    }

    /** A picture that lies inside a page of a PDF, at its own resolution (only the area asked for is decoded). */
    private fun loadPdfImage(a: Attachment, page: Int, number: Int, area: Area, side: Int): Loaded {
        PDDocument.load(store.fileOf(a)).use { doc ->
            val p = page.coerceIn(1, doc.numberOfPages)
            val images = pageImages(doc.getPage(p - 1))
            if (images.isEmpty()) throw IllegalStateException("Page $p has no pictures inside. Use edit_image with page only to look at the page as it is drawn.")
            if (number !in 1..images.size) throw IllegalStateException("Page $p has ${images.size} picture(s): number 1 to ${images.size}.")
            val img = images[number - 1]
            val rect = area.rect(img.width, img.height)
            val sub = (max(rect.width(), rect.height()) / side).coerceAtLeast(1)
            val bitmap = try {
                if (area.whole && sub == 1) img.image else img.getImage(rect, sub)
            } catch (e: Exception) {
                throw IllegalStateException("This picture could not be decoded (${e.message ?: e.javaClass.simpleName}). Look at the page with edit_image (page only) instead.")
            }
            return Loaded(bitmap, "Picture $number on page $p of ${a.name} (${img.width}x${img.height} in the file)", !area.whole)
        }
    }

    private fun loadVideoFrame(a: Attachment, seconds: Double, area: Area): Loaded {
        val frame = store.videoFrameAt(a, seconds) ?: throw IllegalStateException("No frame could be taken from the video at that moment.")
        if (area.whole) return Loaded(frame, "${a.name} at ${fmt(seconds)} s", false)
        val rect = area.rect(frame.width, frame.height)
        val part = Bitmap.createBitmap(frame, rect.left, rect.top, rect.width(), rect.height())
        if (part !== frame) frame.recycle()
        return Loaded(part, "${a.name} at ${fmt(seconds)} s", true)
    }

    // ---------- pixels ----------

    /** Makes the picture's longer side [side]: smaller always; bigger only when [enlarge] (a cut-out), up to 3 times. */
    private fun fit(bitmap: Bitmap, side: Int, enlarge: Boolean): Bitmap {
        val longSide = max(bitmap.width, bitmap.height)
        val scale = when {
            longSide > side -> side.toFloat() / longSide
            enlarge && longSide < side -> min(3f, side.toFloat() / longSide)
            else -> return bitmap
        }
        if (scale > 0.97f && scale < 1.03f) return bitmap
        val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1), (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun onWhite(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
        bitmap.recycle()
        return flat
    }

    private fun colored(bitmap: Bitmap, matrix: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bitmap, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) })
        bitmap.recycle()
        return out
    }

    /** A matrix that stretches the tones so that the darkest 1% become black and the lightest 1% white. */
    private fun levels(bitmap: Bitmap): ColorMatrix {
        val histogram = IntArray(256)
        var count = 0
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height step max(1, bitmap.height / 400)) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in row.indices step max(1, bitmap.width / 400)) {
                val c = row[x]
                histogram[((Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000).coerceIn(0, 255)]++
                count++
            }
        }
        if (count == 0) return ColorMatrix()
        var low = 0
        var seen = 0
        while (low < 255 && seen + histogram[low] < count / 100) { seen += histogram[low]; low++ }
        var high = 255
        seen = 0
        while (high > 0 && seen + histogram[high] < count / 100) { seen += histogram[high]; high-- }
        if (high - low < 16) return ColorMatrix()
        val s = 255f / (high - low)
        val shift = -low * s
        return ColorMatrix(floatArrayOf(s, 0f, 0f, 0f, shift, 0f, s, 0f, 0f, shift, 0f, 0f, s, 0f, shift, 0f, 0f, 0f, 1f, 0f))
    }

    private fun sharpened(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 3 || h < 3) return bitmap
        val src = IntArray(w * h)
        bitmap.getPixels(src, 0, w, 0, 0, w, h)
        val out = src.copyOf()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val c = src[i]
                var r = 5 * Color.red(c)
                var g = 5 * Color.green(c)
                var b = 5 * Color.blue(c)
                for (n in intArrayOf(i - 1, i + 1, i - w, i + w)) {
                    val p = src[n]
                    r -= Color.red(p); g -= Color.green(p); b -= Color.blue(p)
                }
                out[i] = Color.argb(255, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
            }
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        bitmap.recycle()
        return result
    }

    private fun blackAndWhite(bitmap: Bitmap, threshold: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val grey = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c)) / 1000
            px[i] = if (grey >= threshold) Color.WHITE else Color.BLACK
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(px, 0, w, 0, 0, w, h)
        bitmap.recycle()
        return result
    }
}

private fun numProp(description: String): JSONObject = JSONObject().put("type", "number").put("description", description)

private fun boolProp(description: String): JSONObject = JSONObject().put("type", "boolean").put("description", description)
