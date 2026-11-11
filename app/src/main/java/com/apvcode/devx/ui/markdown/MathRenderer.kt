package com.apvcode.devx.ui.markdown

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import ru.noties.jlatexmath.JLatexMathDrawable

/** LaTeX to a picture (JLatexMath, offline). Formulas it cannot parse come back as null. */
object MathRenderer {
    class Rendered(val bitmap: ImageBitmap, val width: Int, val height: Int)

    private val FAILED = Any()
    private val cache = LruCache<String, Any>(96)

    fun render(latex: String, sizePx: Float, colorArgb: Int): Rendered? {
        val key = "$sizePx|$colorArgb|$latex"
        cache.get(key)?.let { return it as? Rendered }
        val result = try {
            val drawable = JLatexMathDrawable.builder(latex).textSize(sizePx).color(colorArgb).padding(0).build()
            val w = drawable.intrinsicWidth
            val h = drawable.intrinsicHeight
            if (w <= 0 || h <= 0) {
                null
            } else {
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, w, h)
                drawable.draw(Canvas(bitmap))
                Rendered(bitmap.asImageBitmap(), w, h)
            }
        } catch (e: Exception) {
            null
        }
        cache.put(key, result ?: FAILED)
        return result
    }
}
