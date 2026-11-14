package com.apvcode.devx.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import com.apvcode.devx.R
import com.apvcode.devx.model.Block
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * More pictures for an answer, each a fenced block with one JSON object: weather, timer and stopwatch, a checklist whose ticks
 * are kept, a table with the best marked, a contact or place with buttons, a preview of a link; and mermaid diagrams.
 */

private fun JSONObject.str(name: String): String? = if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null

private val web = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 Devx"

@Composable
fun RichBlock(block: Block.Rich, modifier: Modifier = Modifier) {
    when (block.kind) {
        "weather" -> WeatherBlock(block.json, modifier)
        "timer" -> TimerBlock(block.json, modifier)
        "checklist" -> ChecklistBlock(block.json, modifier)
        "table" -> TableBlock(block.json, modifier)
        "contact" -> ContactBlock(block.json, modifier)
        "link" -> LinkBlock(block.json, modifier)
        "mermaid" -> MermaidBlock(block.json, modifier)
    }
}

/** What is kept between visits to a message: the ticks of a checklist and the end of a timer. */
private object Kept {
    private fun prefs(context: Context) = context.getSharedPreferences("rich_state", Context.MODE_PRIVATE)
    fun get(context: Context, key: String): String? = prefs(context).getString(key, null)
    fun put(context: Context, key: String, value: String?) { prefs(context).edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
}

private val cardShape = RoundedCornerShape(22.dp)

@Composable
private fun Pill(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text, style = DevxType.Caption, color = if (enabled) DevxColors.TextPrimary else DevxColors.TextDisabled,
        modifier = Modifier.clip(RoundedCornerShape(50)).border(BorderStroke(1.dp, DevxColors.Outline), RoundedCornerShape(50)).pressable(enabled = enabled, scaleTo = 0.96f, haptic = false, onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

// ───────────────────────── weather ─────────────────────────

private class Day(val label: String, val min: Int?, val max: Int?, val code: Int, val rain: Int?)
private class Forecast(val place: String?, val temp: Int, val feels: Int?, val code: Int, val wind: Int?, val days: List<Day>)

private fun weatherWord(code: Int) = when (code) {
    0 -> R.string.wx_clear; 1, 2 -> R.string.wx_mostly_clear; 3 -> R.string.wx_overcast; 45, 48 -> R.string.wx_fog
    51, 53, 55, 56, 57 -> R.string.wx_drizzle; 61, 63, 65, 66, 67, 80, 81, 82 -> R.string.wx_rain; 71, 73, 75, 77, 85, 86 -> R.string.wx_snow
    95, 96, 99 -> R.string.wx_storm; else -> R.string.wx_unknown
}

/** Weather for a place now and for the next days: written out in the block, or looked up by the app from "lat" and "lon". */
@Composable
fun WeatherBlock(json: String, modifier: Modifier = Modifier) {
    var forecast by remember(json) { mutableStateOf<Forecast?>(null) }
    var failed by remember(json) { mutableStateOf(false) }
    LaunchedEffect(json) {
        val o = runCatching { JSONObject(json) }.getOrNull()
        if (o == null) { failed = true; return@LaunchedEffect }
        val now = o.optJSONObject("now")
        if (now != null) {
            val days = o.optJSONArray("days")?.let { a -> (0 until minOf(a.length(), 7)).mapNotNull { a.optJSONObject(it) }.map { d -> Day(d.optString("label"), if (d.has("min")) d.optInt("min") else null, if (d.has("max")) d.optInt("max") else null, d.optInt("code", -1), if (d.has("rain")) d.optInt("rain") else null) } }.orEmpty()
            forecast = Forecast(o.str("place"), now.optDouble("temp").roundToInt(), if (now.has("feels")) now.optDouble("feels").roundToInt() else null, now.optInt("code", -1), if (now.has("wind")) now.optDouble("wind").roundToInt() else null, days)
        } else if (o.has("lat") && o.has("lon")) {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val url = "https://api.open-meteo.com/v1/forecast?latitude=${o.optDouble("lat")}&longitude=${o.optDouble("lon")}&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code&timezone=auto&forecast_days=4"
                    web.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
                        val b = JSONObject(r.body?.string().orEmpty())
                        val cur = b.getJSONObject("current"); val d = b.getJSONObject("daily")
                        val times = d.getJSONArray("time")
                        val days = (1 until times.length()).map { i ->
                            val label = java.time.LocalDate.parse(times.getString(i)).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
                            Day(label, d.getJSONArray("temperature_2m_min").optDouble(i).roundToInt(), d.getJSONArray("temperature_2m_max").optDouble(i).roundToInt(), d.getJSONArray("weather_code").optInt(i), d.getJSONArray("precipitation_probability_max").optInt(i))
                        }
                        Forecast(o.str("place"), cur.optDouble("temperature_2m").roundToInt(), cur.optDouble("apparent_temperature").roundToInt(), cur.optInt("weather_code"), cur.optDouble("wind_speed_10m").roundToInt(), days)
                    }
                }.getOrNull()
            }
            forecast = result
            if (result == null) failed = true
        } else failed = true
    }
    Column(modifier.fillMaxWidth().clip(cardShape).background(DevxColors.Card).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val f = forecast
        if (f == null) {
            Text(stringResource(if (failed) R.string.rich_weather_failed else R.string.rich_loading), style = DevxType.Caption, color = DevxColors.TextSecondary)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            WeatherIcon(f.code, Modifier.size(56.dp))
            Column(Modifier.weight(1f)) {
                if (f.place != null) Text(f.place, style = DevxType.Caption, color = DevxColors.TextSecondary)
                Text("${f.temp}°", style = DevxType.Large.copy(fontSize = 40.sp, lineHeight = 44.sp), color = DevxColors.TextPrimary)
                Text(stringResource(weatherWord(f.code)) + (f.feels?.let { " · " + stringResource(R.string.wx_feels, it) } ?: "") + (f.wind?.let { " · " + stringResource(R.string.wx_wind, it) } ?: ""), style = DevxType.Caption, color = DevxColors.TextSecondary)
            }
        }
        if (f.days.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                f.days.take(5).forEach { d ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(d.label, style = DevxType.Caption, color = DevxColors.TextSecondary)
                        WeatherIcon(d.code, Modifier.size(28.dp))
                        Text((d.max?.let { "$it°" } ?: "") + (d.min?.let { " / $it°" } ?: ""), style = DevxType.Caption, color = DevxColors.TextPrimary)
                        if (d.rain != null && d.rain > 0) Text("${d.rain}%", style = DevxType.Caption.copy(fontSize = 11.sp), color = DevxColors.TextDisabled)
                    }
                }
            }
        }
    }
}

/** Simple line pictures of the weather (sun, cloud, rain, snow, storm, fog), drawn, not letters. */
@Composable
private fun WeatherIcon(code: Int, modifier: Modifier) {
    val ink = DevxColors.TextPrimary
    Canvas(modifier) {
        val w = size.width; val stroke = Stroke(w * 0.07f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun cloud(dy: Float = 0f) {
            val p = Path().apply {
                moveTo(w * 0.25f, w * (0.68f + dy)); cubicTo(w * 0.05f, w * (0.68f + dy), w * 0.05f, w * (0.40f + dy), w * 0.27f, w * (0.40f + dy))
                cubicTo(w * 0.30f, w * (0.18f + dy), w * 0.66f, w * (0.18f + dy), w * 0.70f, w * (0.42f + dy))
                cubicTo(w * 0.95f, w * (0.40f + dy), w * 0.95f, w * (0.68f + dy), w * 0.75f, w * (0.68f + dy)); close()
            }
            drawPath(p, ink, style = stroke)
        }
        when (code) {
            0, 1 -> {
                drawCircle(ink, w * 0.2f, Offset(w / 2, w / 2), style = stroke)
                for (i in 0 until 8) { val a = i * PI / 4; drawLine(ink, Offset((w / 2 + cos(a) * w * 0.32f).toFloat(), (w / 2 + sin(a) * w * 0.32f).toFloat()), Offset((w / 2 + cos(a) * w * 0.44f).toFloat(), (w / 2 + sin(a) * w * 0.44f).toFloat()), w * 0.07f, StrokeCap.Round) }
            }
            2 -> { drawCircle(ink, w * 0.14f, Offset(w * 0.68f, w * 0.30f), style = stroke); cloud(0.08f) }
            45, 48 -> { for (i in 0 until 3) drawLine(ink, Offset(w * 0.15f, w * (0.33f + i * 0.18f)), Offset(w * 0.85f, w * (0.33f + i * 0.18f)), w * 0.07f, StrokeCap.Round) }
            51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> { cloud(-0.1f); for (i in 0 until 3) drawLine(ink, Offset(w * (0.3f + i * 0.2f), w * 0.72f), Offset(w * (0.25f + i * 0.2f), w * 0.9f), w * 0.07f, StrokeCap.Round) }
            71, 73, 75, 77, 85, 86 -> { cloud(-0.1f); for (i in 0 until 3) drawCircle(ink, w * 0.04f, Offset(w * (0.3f + i * 0.2f), w * (0.78f + (i % 2) * 0.08f))) }
            95, 96, 99 -> {
                cloud(-0.1f)
                val bolt = Path().apply { moveTo(w * 0.52f, w * 0.62f); lineTo(w * 0.40f, w * 0.80f); lineTo(w * 0.54f, w * 0.80f); lineTo(w * 0.44f, w * 0.96f) }
                drawPath(bolt, ink, style = stroke)
            }
            else -> cloud()
        }
    }
}

// ───────────────────────── timer ─────────────────────────

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

/** A countdown ({"minutes":5}, {"seconds":90}) or a stopwatch ({"kind":"stopwatch"}) in the answer. It keeps going while you scroll away. */
@Composable
fun TimerBlock(json: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val spec = remember(json) { runCatching { JSONObject(json) }.getOrNull() }
    if (spec == null) { CodeView("timer", json, modifier); return }
    val stopwatch = spec.optString("kind") == "stopwatch"
    val total = remember(json) { ((spec.optDouble("hours", 0.0) * 3600 + spec.optDouble("minutes", 0.0) * 60 + spec.optDouble("seconds", 0.0)) * 1000).toLong().coerceAtLeast(if (stopwatch) 0 else 1000) }
    val key = "timer:" + json.hashCode()
    // Kept as "running:<wall clock start>:<already elapsed ms>" or "paused:<elapsed ms>".
    var saved by remember(json) { mutableStateOf(Kept.get(context, key)) }
    var elapsed by remember(json) { mutableLongStateOf(0L) }
    var buzzed by remember(json) { mutableStateOf(false) }
    val running = saved?.startsWith("running") == true
    LaunchedEffect(saved) {
        val parts = saved?.split(":")
        while (true) {
            elapsed = when (parts?.firstOrNull()) {
                "running" -> (System.currentTimeMillis() - parts[1].toLong()) + parts[2].toLong()
                "paused" -> parts[1].toLong()
                else -> 0L
            }
            if (!stopwatch && elapsed >= total) {
                if (running && !buzzed) {
                    buzzed = true
                    runCatching { context.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 600), -1)) }
                }
                break
            }
            if (!running) break
            delay(250)
        }
    }
    val done = !stopwatch && elapsed >= total
    val shownMs = if (stopwatch) elapsed else (total - elapsed).coerceAtLeast(0)
    Column(modifier.fillMaxWidth().clip(cardShape).background(DevxColors.Card).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        spec.str("title")?.let { Text(it, style = DevxType.Caption, color = DevxColors.TextSecondary) }
        Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
            if (!stopwatch) Canvas(Modifier.size(150.dp)) {
                drawCircle(Color.White.copy(alpha = 0.10f), style = Stroke(8.dp.toPx()))
                drawArc(Color.White, -90f, 360f * (1f - elapsed.toFloat() / total).coerceIn(0f, 1f), false, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round), size = Size(size.width, size.height))
            }
            Text(clock(shownMs), style = DevxType.Large.copy(fontSize = 34.sp), color = if (done) DevxColors.Success else DevxColors.TextPrimary)
        }
        if (done) Text(stringResource(R.string.rich_time_up), style = DevxType.Caption, color = DevxColors.Success)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!done) Pill(stringResource(if (running) R.string.rich_pause else R.string.rich_start)) {
                saved = if (running) "paused:$elapsed" else "running:${System.currentTimeMillis()}:$elapsed"
                Kept.put(context, key, saved)
            }
            Pill(stringResource(R.string.rich_reset)) { saved = null; buzzed = false; Kept.put(context, key, null) }
        }
    }
}

// ───────────────────────── checklist ─────────────────────────

/** A list with ticks that stay ticked. */
@Composable
fun ChecklistBlock(json: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val parsed = remember(json) {
        runCatching {
            val o = JSONObject(json)
            val items = o.optJSONArray("items")?.let { a -> (0 until minOf(a.length(), 40)).map { i -> a.optJSONObject(i)?.let { it.optString("text") to it.optBoolean("done", false) } ?: (a.optString(i) to false) } }.orEmpty()
            o.str("title") to items
        }.getOrNull()
    }
    if (parsed == null || parsed.second.isEmpty()) { CodeView("checklist", json, modifier); return }
    val key = "check:" + json.hashCode()
    val done = remember(json) { mutableStateListOf<Boolean>().apply { val kept = Kept.get(context, key)?.split(",")?.mapNotNull { it.toIntOrNull() }?.toSet(); parsed.second.forEachIndexed { i, it -> add(kept?.contains(i) ?: it.second) } } }
    Column(modifier.fillMaxWidth().clip(cardShape).background(DevxColors.Card).padding(vertical = 8.dp)) {
        parsed.first?.let { Text(it, style = DevxType.BodyStrong, color = DevxColors.TextPrimary, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) }
        parsed.second.forEachIndexed { i, (text, _) ->
            Row(
                Modifier.fillMaxWidth().pressable(scaleTo = 0.99f, haptic = false) {
                    done[i] = !done[i]
                    Kept.put(context, key, done.withIndex().filter { it.value }.joinToString(",") { it.index.toString() })
                }.padding(horizontal = 18.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(22.dp).clip(CircleShape).border(2.dp, if (done[i]) DevxColors.Accent else DevxColors.TextDisabled, CircleShape).background(if (done[i]) DevxColors.Accent else Color.Transparent), contentAlignment = Alignment.Center) {
                    if (done[i]) Canvas(Modifier.size(12.dp)) {
                        val p = Path().apply { moveTo(size.width * 0.12f, size.height * 0.55f); lineTo(size.width * 0.4f, size.height * 0.82f); lineTo(size.width * 0.9f, size.height * 0.2f) }
                        drawPath(p, Color.Black, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
                Text(text, style = DevxType.Body.copy(textDecoration = if (done[i]) TextDecoration.LineThrough else TextDecoration.None), color = if (done[i]) DevxColors.TextDisabled else DevxColors.TextPrimary)
            }
        }
    }
}

// ───────────────────────── table ─────────────────────────

/** A table; "highlight" rows stand out, and "best" marks the largest or smallest number of a column. */
@Composable
fun TableBlock(json: String, modifier: Modifier = Modifier) {
    val parsed = remember(json) {
        runCatching {
            val o = JSONObject(json)
            // The model writes tables in several shapes: columns/headers, rows as lists or as objects, a header as the first row.
            val rowsArr = o.optJSONArray("rows") ?: o.optJSONArray("data") ?: error("no rows")
            fun cell(v: Any?) = if (v == null || v == JSONObject.NULL) "" else v.toString()
            var cols = (o.optJSONArray("columns") ?: o.optJSONArray("headers") ?: o.optJSONArray("header"))?.let { a -> (0 until a.length()).map { cell(a.opt(it)) } }.orEmpty()
            var from = 0
            val first = rowsArr.opt(0)
            if (cols.isEmpty() && first is JSONObject) cols = first.keys().asSequence().toList()
            if (cols.isEmpty() && first is JSONArray) { cols = (0 until first.length()).map { cell(first.opt(it)) }; from = 1 }
            if (cols.isEmpty()) error("no columns")
            val rows = (from until minOf(rowsArr.length(), 40 + from)).map { r ->
                when (val row = rowsArr.opt(r)) {
                    is JSONArray -> (0 until cols.size).map { i -> cell(row.opt(i)) }
                    is JSONObject -> cols.map { c -> cell(row.opt(c)) }
                    else -> listOf(cell(row)) + List(cols.size - 1) { "" }
                }
            }
            val highlight = o.optJSONArray("highlight")?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty().toSet()
            val best = o.optJSONArray("best")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty().associate { it.optInt("column") to (it.optString("mode", "max") == "max") }
            val bestCells = HashSet<Pair<Int, Int>>()
            best.forEach { (col, maxMode) ->
                val numbers = rows.mapIndexedNotNull { r, row -> row.getOrNull(col)?.replace(Regex("[^0-9.,-]"), "")?.replace(',', '.')?.toDoubleOrNull()?.let { r to it } }
                val target = if (maxMode) numbers.maxOfOrNull { it.second } else numbers.minOfOrNull { it.second }
                numbers.filter { it.second == target }.forEach { bestCells += it.first to col }
            }
            arrayOf(o.str("title"), cols, rows, highlight, bestCells)
        }.getOrNull()
    }
    if (parsed == null) { CodeView("table", json, modifier); return }
    @Suppress("UNCHECKED_CAST") val cols = parsed[1] as List<String>
    @Suppress("UNCHECKED_CAST") val rows = parsed[2] as List<List<String>>
    @Suppress("UNCHECKED_CAST") val highlight = parsed[3] as Set<Int>
    @Suppress("UNCHECKED_CAST") val bestCells = parsed[4] as Set<Pair<Int, Int>>
    val widths = cols.indices.map { c -> (max(cols[c].length, rows.maxOfOrNull { it[c].length } ?: 0) * 8 + 28).coerceIn(80, 190).dp }
    Column(modifier.fillMaxWidth().clip(cardShape).background(DevxColors.Card), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        (parsed[0] as String?)?.let { Text(it, style = DevxType.BodyStrong, color = DevxColors.TextPrimary, modifier = Modifier.padding(start = 18.dp, top = 14.dp, end = 18.dp)) }
        Column(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            Row(Modifier.padding(horizontal = 8.dp)) { cols.forEachIndexed { c, name -> Text(name, style = DevxType.Caption.copy(fontWeight = FontWeight.SemiBold), color = DevxColors.TextSecondary, modifier = Modifier.width(widths[c]).padding(horizontal = 10.dp, vertical = 8.dp)) } }
            rows.forEachIndexed { r, row ->
                Row(Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp)).background(if (r in highlight) Color.White.copy(alpha = 0.10f) else Color.Transparent)) {
                    row.forEachIndexed { c, cell ->
                        val best = (r to c) in bestCells
                        Text(cell, style = DevxType.Body.copy(fontWeight = if (best || r in highlight) FontWeight.SemiBold else FontWeight.Normal), color = if (best) DevxColors.Success else DevxColors.TextPrimary, modifier = Modifier.width(widths[c]).padding(horizontal = 10.dp, vertical = 10.dp))
                    }
                }
            }
        }
    }
}

// ───────────────────────── contact or place ─────────────────────────

/** A person or a place with what to do about it: call, write, mail, go there, copy. */
@Composable
fun ContactBlock(json: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val o = remember(json) { runCatching { JSONObject(json) }.getOrNull() }
    if (o == null) { CodeView("contact", json, modifier); return }
    val name = o.str("name") ?: o.str("title") ?: ""
    val phone = o.str("phone"); val email = o.str("email"); val address = o.str("address"); val note = o.str("note")
    val lat = if (o.has("lat")) o.optDouble("lat") else null; val lon = if (o.has("lon")) o.optDouble("lon") else null
    fun go(intent: Intent) = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { Toast.makeText(context, R.string.rich_cannot_open, Toast.LENGTH_SHORT).show() }
    Column(modifier.fillMaxWidth().clip(cardShape).background(DevxColors.Card).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(46.dp).clip(CircleShape).background(DevxColors.CardInner), contentAlignment = Alignment.Center) { Text(name.take(1).uppercase().ifBlank { "·" }, style = DevxType.Title, color = DevxColors.TextPrimary) }
            Column(Modifier.weight(1f)) {
                Text(name, style = DevxType.BodyStrong, color = DevxColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                listOfNotNull(phone, email, address).forEach { Text(it, style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 2) }
            }
        }
        note?.let { Text(it, style = DevxType.Caption, color = DevxColors.TextSecondary) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (phone != null) {
                Pill(stringResource(R.string.rich_call)) { go(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(phone)))) }
                Pill(stringResource(R.string.rich_message)) { go(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(phone)))) }
            }
            if (email != null) Pill(stringResource(R.string.rich_email)) { go(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email"))) }
            if (address != null || (lat != null && lon != null)) Pill(stringResource(R.string.rich_go)) {
                val q = if (lat != null && lon != null) "$lat,$lon" else address.orEmpty()
                go(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q))))
            }
            Pill(stringResource(R.string.rich_copy)) {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(name, listOfNotNull(name, phone, email, address).joinToString("\n")))
                Toast.makeText(context, R.string.rich_copied, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

// ───────────────────────── link preview ─────────────────────────

private class Preview(val title: String, val description: String, val image: String?, val host: String)

private val previews = HashMap<String, Preview?>()

private fun publicHttps(url: String): Boolean {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    if (uri.scheme != "https") return false
    return !(host == "localhost" || host.endsWith(".local") || host.endsWith(".internal") || Regex("^(\\d{1,3}\\.){3}\\d{1,3}$").matches(host) || host.contains(":"))
}

/** A page's picture, title and first words (from its own description tags), as a card; a tap opens the page. */
@Composable
fun LinkBlock(json: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val url = remember(json) { runCatching { JSONObject(json).optString("url") }.getOrElse { json }.trim().ifBlank { json.trim() } }
    var preview by remember(url) { mutableStateOf<Preview?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        if (!publicHttps(url)) { failed = true; return@LaunchedEffect }
        val cached = synchronized(previews) { if (previews.containsKey(url)) previews[url] else null }
        if (cached != null) { preview = cached; return@LaunchedEffect }
        val p = withContext(Dispatchers.IO) {
            runCatching {
                web.newCall(Request.Builder().url(url).header("User-Agent", UA).header("Accept", "text/html").build()).execute().use { r ->
                    if (!r.isSuccessful || r.header("Content-Type")?.contains("html", true) == false) return@use null
                    val html = r.body?.byteStream()?.readNBytes(400_000)?.toString(Charsets.UTF_8).orEmpty()
                    val doc = Jsoup.parse(html, url)
                    fun meta(vararg names: String) = names.firstNotNullOfOrNull { n -> doc.selectFirst("meta[property=$n], meta[name=$n]")?.attr("content")?.takeIf { it.isNotBlank() } }
                    Preview(meta("og:title", "twitter:title") ?: doc.title(), (meta("og:description", "description", "twitter:description") ?: "").take(220), meta("og:image", "twitter:image")?.let { doc.select("meta[property=og:image]").firstOrNull()?.absUrl("content")?.takeIf { u -> u.startsWith("https://") } ?: it.takeIf { u -> u.startsWith("https://") } }, Uri.parse(url).host.orEmpty())
                }
            }.getOrNull()
        }
        synchronized(previews) { previews[url] = p }
        preview = p
        if (p == null) failed = true
    }
    val p = preview
    Column(modifier.fillMaxWidth().clip(cardShape).background(DevxColors.Card).pressable(scaleTo = 0.99f, haptic = false) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }) {
        if (p?.image != null) AsyncImage(model = p.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(150.dp).background(DevxColors.CardInner))
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(p?.host ?: Uri.parse(url).host.orEmpty(), style = DevxType.Caption, color = DevxColors.TextDisabled)
            if (p != null) {
                Text(p.title, style = DevxType.BodyStrong, color = DevxColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (p.description.isNotBlank()) Text(p.description, style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            } else Text(if (failed) url else stringResource(R.string.rich_loading), style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ───────────────────────── mermaid ─────────────────────────

private var mermaidScript: String? = null

private fun mermaidPage(context: Context, code: String): String {
    val script = mermaidScript ?: context.assets.open("mermaid.min.js").bufferedReader().use { it.readText() }.also { mermaidScript = it }
    val escaped = JSONObject.quote(code)
    return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
        "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval'; style-src 'unsafe-inline'; img-src data:; font-src data:\">" +
        "<style>html,body{margin:0;background:transparent;color:#eee;font-family:sans-serif}#out{padding:8px}#out svg{max-width:100%;height:auto}</style></head><body><div id=\"out\"></div>" +
        "<script>$script</script><script>" +
        "mermaid.initialize({startOnLoad:false,theme:'dark',securityLevel:'strict',fontFamily:'sans-serif'});" +
        "mermaid.render('g',$escaped).then(function(r){document.getElementById('out').innerHTML=r.svg;" +
        "setTimeout(function(){DevxBridge.height(document.documentElement.scrollHeight);},50);})" +
        ".catch(function(e){DevxBridge.failed();});</script></body></html>"
}

/** A flowchart, sequence diagram, state chart and the like, drawn from Mermaid text by a copy of Mermaid inside the app (no network). */
@android.annotation.SuppressLint("SetJavaScriptEnabled")
@Composable
fun MermaidBlock(code: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var heightDp by remember(code) { mutableStateOf(220) }
    var failed by remember(code) { mutableStateOf(false) }
    if (failed) { CodeView("mermaid", code, modifier); return }
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF101010)).border(1.dp, DevxColors.Outline, RoundedCornerShape(20.dp))) {
        AndroidView(
            factory = { ctx ->
                android.webkit.WebView(ctx).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
                    settings.apply { javaScriptEnabled = true; domStorageEnabled = false; allowFileAccess = false; allowContentAccess = false; blockNetworkLoads = true }
                    addJavascriptInterface(object {
                        @JavascriptInterface fun height(px: Int) { post { heightDp = (px / ctx.resources.displayMetrics.density).toInt().coerceIn(80, 900) + 8 } }
                        @JavascriptInterface fun failed() { post { failed = true } }
                    }, "DevxBridge")
                    webViewClient = object : android.webkit.WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?) = true
                    }
                }
            },
            update = { web -> if (web.tag != code) { web.tag = code; web.loadDataWithBaseURL("about:blank", mermaidPage(context, code), "text/html", "utf-8", null) } },
            onRelease = { it.stopLoading(); it.removeJavascriptInterface("DevxBridge"); it.destroy() },
            modifier = Modifier.fillMaxWidth().height(heightDp.dp),
        )
    }
}
