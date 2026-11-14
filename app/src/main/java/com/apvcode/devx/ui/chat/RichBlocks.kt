package com.apvcode.devx.ui.chat

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.apvcode.devx.R
import com.apvcode.devx.data.FolderTools
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.screens.PhotoViewer
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/*
 * Pictures the assistant puts in its answer: a map (places and a route, drawn from the free OpenStreetMap tiles), photos and
 * pictures from the phone, and charts. Each is a fenced block (devx-map, devx-image, devx-chart) holding one JSON object,
 * and may also stand inside a devx-card. Nothing here runs code from the answer.
 */

/** Finds the picture behind a source the assistant wrote: set by the message screen, which knows the chat. */
val LocalImageResolver = compositionLocalOf<(String) -> Any?> { { null } }

/** Finds a picture that has to be fetched first (a picture of the computer in a Devx Remote chat): called when the resolver has none. */
val LocalImageLoader = compositionLocalOf<suspend (String) -> Any?> { { null } }

/** gallery:<id>, folder:<path>, artifact:<id>, attachment:<name> or an https address. */
fun resolveImageSource(vm: ChatViewModel, context: Context, src: String): Any? {
    val s = src.trim()
    return when {
        s.startsWith("https://") -> s
        s.startsWith("gallery:") -> s.removePrefix("gallery:").trim().removePrefix("#").toLongOrNull()?.let { ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it) }
        s.startsWith("folder:") -> FolderTools(context).uriOf(s.removePrefix("folder:"))
        s.startsWith("artifact:") -> vm.artifact(s.removePrefix("artifact:").trim())?.let { vm.artifactFile(it) }
        s.startsWith("attachment:") -> {
            val name = s.removePrefix("attachment:").trim()
            vm.currentId?.let { id -> vm.uploadedFiles(id).firstOrNull { it.name.equals(name, true) } }?.let { vm.fileOf(it) }
        }
        else -> null
    }
}

private val http = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()
private const val AGENT = "Devx-Android/1.0 (personal assistant app)"

private fun JSONObject.text(name: String): String? = if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null

// ───────────────────────── map ─────────────────────────

private class LatLon(val lat: Double, val lon: Double)
private class PlaceSpec(val lat: Double?, val lon: Double?, val address: String?, val label: String?)
private class RouteSpec(val from: PlaceSpec, val to: PlaceSpec, val mode: String)
private class MapSpec(val title: String?, val zoom: Int?, val center: PlaceSpec?, val markers: List<PlaceSpec>, val route: RouteSpec?, val heightDp: Int)

private fun placeOf(o: JSONObject?): PlaceSpec? {
    o ?: return null
    val lat = if (o.has("lat")) o.optDouble("lat") else null
    val lon = if (o.has("lon")) o.optDouble("lon") else null
    val address = o.text("address") ?: o.text("name")
    if ((lat == null || lon == null || lat.isNaN() || lon.isNaN()) && address == null) return null
    return PlaceSpec(lat?.takeIf { !it.isNaN() }, lon?.takeIf { !it.isNaN() }, address, o.text("label"))
}

private fun parseMap(json: String): MapSpec? = try {
    val o = JSONObject(json)
    val markers = o.optJSONArray("markers")?.let { a -> (0 until minOf(a.length(), 12)).mapNotNull { placeOf(a.optJSONObject(it)) } }.orEmpty()
    val route = o.optJSONObject("route")?.let { r ->
        val from = placeOf(r.optJSONObject("from")); val to = placeOf(r.optJSONObject("to"))
        if (from != null && to != null) RouteSpec(from, to, r.optString("mode", "driving")) else null
    }
    val center = placeOf(o.optJSONObject("center")) ?: o.text("address")?.let { PlaceSpec(null, null, it, null) }
    if (markers.isEmpty() && route == null && center == null) null
    else MapSpec(o.text("title"), if (o.has("zoom")) o.optInt("zoom") else null, center, markers, route, o.optInt("height", 260).coerceIn(160, 420))
} catch (_: Exception) { null }

private object Geo {
    private val addresses = HashMap<String, LatLon?>()
    private val routes = HashMap<String, RouteResult?>()

    suspend fun resolve(p: PlaceSpec): LatLon? {
        if (p.lat != null && p.lon != null) return LatLon(p.lat, p.lon)
        val key = p.address.orEmpty().lowercase()
        if (key.isBlank()) return null
        synchronized(addresses) { if (addresses.containsKey(key)) return addresses[key] }
        val found = withContext(Dispatchers.IO) {
            runCatching {
                val url = okhttp3.HttpUrl.Builder().scheme("https").host("nominatim.openstreetmap.org").addPathSegment("search")
                    .addQueryParameter("q", p.address).addQueryParameter("format", "jsonv2").addQueryParameter("limit", "1").build()
                http.newCall(Request.Builder().url(url).header("User-Agent", AGENT).build()).execute().use { r ->
                    JSONArray(r.body?.string().orEmpty()).optJSONObject(0)?.let { LatLon(it.optString("lat").toDouble(), it.optString("lon").toDouble()) }
                }
            }.getOrNull()
        }
        synchronized(addresses) { addresses[key] = found }
        return found
    }

    class RouteResult(val line: List<LatLon>, val seconds: Double, val metres: Double)

    suspend fun route(from: LatLon, to: LatLon, mode: String): RouteResult? {
        val host = when (mode.lowercase()) { "walking", "walk", "foot" -> "routed-foot"; "bicycle", "bike", "cycling" -> "routed-bike"; else -> "routed-car" }
        val key = "$host:${from.lat},${from.lon};${to.lat},${to.lon}"
        synchronized(routes) { if (routes.containsKey(key)) return routes[key] }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://routing.openstreetmap.de/$host/route/v1/driving/${from.lon},${from.lat};${to.lon},${to.lat}?overview=full&geometries=geojson"
                http.newCall(Request.Builder().url(url).header("User-Agent", AGENT).build()).execute().use { r ->
                    val route = JSONObject(r.body?.string().orEmpty()).optJSONArray("routes")?.optJSONObject(0) ?: return@use null
                    val coords = route.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@use null
                    val step = max(1, coords.length() / 500)
                    val line = (0 until coords.length() step step).map { i -> coords.getJSONArray(i).let { LatLon(it.getDouble(1), it.getDouble(0)) } }
                    RouteResult(line, route.optDouble("duration"), route.optDouble("distance"))
                }
            }.getOrNull()
        }
        synchronized(routes) { routes[key] = result }
        return result
    }
}

private object Tiles {
    private val cache = object : android.util.LruCache<String, ImageBitmap>(96) {}

    suspend fun get(z: Int, x: Int, y: Int): ImageBitmap? {
        val key = "$z/$x/$y"
        cache.get(key)?.let { return it }
        val image = withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url("https://tile.openstreetmap.org/$z/$x/$y.png").header("User-Agent", AGENT).build()).execute().use { r ->
                    r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                }
            }.getOrNull()
        }
        if (image != null) cache.put(key, image)
        return image
    }
}

/** Web Mercator, in tiles at zoom [z]. */
private fun tileX(lon: Double, z: Int) = (lon + 180.0) / 360.0 * 2.0.pow(z)
private fun tileY(lat: Double, z: Int): Double {
    val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
    return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * 2.0.pow(z)
}

private class MapState(val centerLat: Double, val centerLon: Double, val zoom: Int, val markers: List<Pair<LatLon, String>>, val line: List<LatLon>, val seconds: Double?, val metres: Double?, val target: LatLon, val mode: String, val routeFrom: LatLon?)

/** A map with places and/or a route; buttons open it in the phone's map apps. */
@Composable
fun MapBlock(json: String, modifier: Modifier = Modifier) {
    val spec = remember(json) { parseMap(json) }
    if (spec == null) { CodeView("map", json, modifier); return }
    val context = LocalContext.current
    val density = LocalDensity.current
    var state by remember(json) { mutableStateOf<MapState?>(null) }
    var failed by remember(json) { mutableStateOf(false) }
    val tiles = remember(json) { mutableStateMapOf<String, ImageBitmap>() }
    val tilePx = with(density) { 256.dp.toPx() }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { spec.heightDp.dp.toPx() }
        LaunchedEffect(json, widthPx) {
            // Every place and the route are found first; then the view is fitted around all of them.
            val markers = coroutineScope { spec.markers.map { async { Geo.resolve(it) } }.awaitAll() }
            val named = spec.markers.mapIndexedNotNull { i, p -> markers[i]?.let { it to (p.label ?: ((65 + i).toChar().toString())) } }
            val centre = spec.center?.let { Geo.resolve(it) }
            var line = emptyList<LatLon>(); var seconds: Double? = null; var metres: Double? = null
            var from: LatLon? = null; var to: LatLon? = null
            spec.route?.let { r ->
                from = Geo.resolve(r.from); to = Geo.resolve(r.to)
                if (from != null && to != null) Geo.route(from!!, to!!, r.mode)?.let { line = it.line; seconds = it.seconds; metres = it.metres }
            }
            val ends = listOfNotNull(from?.let { it to (spec.route?.from?.label ?: "A") }, to?.let { it to (spec.route?.to?.label ?: "B") })
            val shown = named + ends
            val points = shown.map { it.first } + line + listOfNotNull(centre)
            if (points.isEmpty()) { failed = true; return@LaunchedEffect }
            // The zoom at which all of it fits (or the one the assistant asked for, when there is just one point).
            var zoom = spec.zoom ?: 15
            val lat0 = (points.minOf { it.lat } + points.maxOf { it.lat }) / 2
            val lon0 = (points.minOf { it.lon } + points.maxOf { it.lon }) / 2
            if (points.size > 1) {
                zoom = 3
                for (z in 17 downTo 3) {
                    val w = (points.maxOf { tileX(it.lon, z) } - points.minOf { tileX(it.lon, z) }) * tilePx
                    val h = (points.maxOf { tileY(it.lat, z) } - points.minOf { tileY(it.lat, z) }) * tilePx
                    if (w <= widthPx * 0.82 && h <= heightPx * 0.78) { zoom = z; break }
                }
            }
            val target = to ?: shown.firstOrNull()?.first ?: centre ?: LatLon(lat0, lon0)
            state = MapState(lat0, lon0, zoom, shown, line, seconds, metres, target, spec.route?.mode ?: "driving", from)
        }
        val s = state
        LaunchedEffect(s, widthPx) {
            if (s == null) return@LaunchedEffect
            val cx = tileX(s.centerLon, s.zoom); val cy = tileY(s.centerLat, s.zoom)
            val max = 2.0.pow(s.zoom).toInt()
            val x0 = floor(cx - widthPx / 2 / tilePx).toInt(); val x1 = floor(cx + widthPx / 2 / tilePx).toInt()
            val y0 = floor(cy - heightPx / 2 / tilePx).toInt(); val y1 = floor(cy + heightPx / 2 / tilePx).toInt()
            for (ty in y0..y1) for (tx in x0..x1) {
                if (ty !in 0 until max) continue
                val wrapped = ((tx % max) + max) % max
                val key = "${s.zoom}/$tx/$ty"
                if (tiles.containsKey(key)) continue
                Tiles.get(s.zoom, wrapped, ty)?.let { tiles[key] = it }
            }
        }

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (spec.title != null) Text(spec.title, style = DevxType.BodyStrong, color = DevxColors.TextPrimary)
            Box(Modifier.fillMaxWidth().height(spec.heightDp.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFFE9E6DF))) {
                if (s != null) {
                    val measurer = rememberTextMeasurer()
                    Canvas(Modifier.fillMaxWidth().height(spec.heightDp.dp)) {
                        val cx = tileX(s.centerLon, s.zoom); val cy = tileY(s.centerLat, s.zoom)
                        for ((key, image) in tiles) {
                            val parts = key.split("/")
                            if (parts[0].toInt() != s.zoom) continue
                            val left = size.width / 2 + ((parts[1].toDouble() - cx) * tilePx).toFloat()
                            val top = size.height / 2 + ((parts[2].toDouble() - cy) * tilePx).toFloat()
                            drawImage(image, dstOffset = IntOffset(left.roundToInt(), top.roundToInt()), dstSize = IntSize(tilePx.roundToInt() + 1, tilePx.roundToInt() + 1))
                        }
                        fun px(p: LatLon) = Offset(size.width / 2 + ((tileX(p.lon, s.zoom) - cx) * tilePx).toFloat(), size.height / 2 + ((tileY(p.lat, s.zoom) - cy) * tilePx).toFloat())
                        if (s.line.size > 1) {
                            val path = Path().apply { s.line.forEachIndexed { i, p -> val o = px(p); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
                            drawPath(path, Color.White, style = Stroke(9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                            drawPath(path, Color(0xFF1B1B1F), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                        s.markers.forEach { (p, label) ->
                            val o = px(p)
                            drawCircle(Color.White, 15.dp.toPx(), o)
                            drawCircle(Color(0xFF1B1B1F), 12.dp.toPx(), o)
                            val layout = measurer.measure(label.take(2), TextStyle(color = Color.White, fontSize = 12.sp))
                            drawText(layout, topLeft = Offset(o.x - layout.size.width / 2f, o.y - layout.size.height / 2f))
                        }
                    }
                    Text("© OpenStreetMap", style = DevxType.Caption.copy(fontSize = 10.sp), color = Color(0xFF3C3C3C), modifier = Modifier.align(Alignment.BottomStart).background(Color(0xCCFFFFFF)).padding(horizontal = 6.dp, vertical = 2.dp))
                    if (s.seconds != null && s.metres != null) {
                        val minutes = (s.seconds / 60).roundToInt()
                        Text(
                            (if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min") + " · " + (if (s.metres >= 1000) "%.1f km".format(Locale.US, s.metres / 1000) else "${s.metres.roundToInt()} m"),
                            style = DevxType.BodyStrong, color = Color.White,
                            modifier = Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(50)).background(Color(0xE61B1B1F)).padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                } else {
                    Text(if (failed) stringResource(R.string.rich_map_failed) else stringResource(R.string.rich_map_loading), style = DevxType.Caption, color = Color(0xFF555555), modifier = Modifier.align(Alignment.Center))
                }
            }
            if (s != null) {
                val label = spec.markers.firstOrNull()?.label ?: spec.route?.to?.label ?: spec.title
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionPill(stringResource(R.string.rich_open_maps)) { openInMaps(context, s.target, label) }
                    if (s.line.isNotEmpty() || spec.route != null) ActionPill(stringResource(R.string.rich_route)) { startNavigation(context, s.target, s.mode) }
                    ActionPill("2GIS") { openIn2Gis(context, s.target, spec.route != null, s.mode) }
                }
            }
        }
    }
}

@Composable
private fun ActionPill(text: String, onClick: () -> Unit) {
    Text(
        text, style = DevxType.Caption, color = DevxColors.TextPrimary,
        modifier = Modifier.clip(RoundedCornerShape(50)).border(BorderStroke(1.dp, DevxColors.Outline), RoundedCornerShape(50)).pressable(scaleTo = 0.96f, haptic = false, onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

private fun openInMaps(context: Context, p: LatLon, label: String?) {
    val q = Uri.encode("${p.lat},${p.lon}" + (label?.let { "($it)" } ?: ""))
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${p.lat},${p.lon}?q=$q")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(context, R.string.rich_no_maps, Toast.LENGTH_SHORT).show() }
}

private fun startNavigation(context: Context, p: LatLon, mode: String) {
    val m = when (mode.lowercase()) { "walking", "walk", "foot" -> "w"; "bicycle", "bike", "cycling" -> "b"; else -> "d" }
    val navigate = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${p.lat},${p.lon}&mode=$m")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(navigate) }.onFailure { openInMaps(context, p, null) }
}

private fun openIn2Gis(context: Context, p: LatLon, route: Boolean, mode: String) {
    val kind = when (mode.lowercase()) { "walking", "walk", "foot" -> "pedestrian"; "bicycle", "bike", "cycling" -> "bicycle"; else -> "car" }
    val uri = if (route) "dgis://2gis.ru/routeSearch/rsType/$kind/to/${p.lon},${p.lat}" else "dgis://2gis.ru/geo/${p.lon},${p.lat}"
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(context, R.string.rich_no_2gis, Toast.LENGTH_SHORT).show() }
}

// ───────────────────────── pictures ─────────────────────────

/** One picture or a strip of them, from the phone (gallery, the chosen folder, files of the chat) or the web. A tap opens one full screen. */
@Composable
fun ImageBlock(json: String, modifier: Modifier = Modifier) {
    val resolver = LocalImageResolver.current
    val items = remember(json) {
        runCatching {
            val o = JSONObject(json)
            val array = o.optJSONArray("images")
            if (array != null) (0 until minOf(array.length(), 12)).mapNotNull { i -> array.optJSONObject(i)?.let { it.text("src") to it.text("caption") } ?: array.optString(i).takeIf { s -> s.isNotBlank() }?.let { s -> s to null } }
            else listOf((o.text("src") ?: "") to o.text("caption"))
        }.getOrElse { json.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { it to null } }.filter { !it.first.isNullOrBlank() }
    }
    var viewing by remember { mutableStateOf<Any?>(null) }
    if (items.isEmpty()) return
    val single = items.size == 1
    val row = if (single) modifier.fillMaxWidth() else modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (single) {
            ImageItem(items[0].first!!, items[0].second, resolver, Modifier.fillMaxWidth(), true) { viewing = it }
        } else {
            Row(row, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items.forEach { (src, caption) -> ImageItem(src!!, caption, resolver, Modifier.height(190.dp), false) { viewing = it } }
            }
        }
    }
    val full = viewing
    if (full != null) {
        Dialog(onDismissRequest = { viewing = null }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            PhotoViewer(full, onClose = { viewing = null })
        }
    }
}

@Composable
private fun ImageItem(src: String, caption: String?, resolver: (String) -> Any?, modifier: Modifier, wide: Boolean, onOpen: (Any) -> Unit) {
    val loader = LocalImageLoader.current
    val model by androidx.compose.runtime.produceState<Any?>(initialValue = remember(src) { resolver(src) }, src) {
        if (value == null) value = loader(src)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (model == null) {
            Text(stringResource(R.string.rich_image_missing, src), style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(DevxColors.Card).padding(14.dp))
        } else {
            AsyncImage(
                model = model, contentDescription = caption,
                contentScale = if (wide) ContentScale.Fit else ContentScale.Crop,
                modifier = (if (wide) Modifier.fillMaxWidth().heightIn(max = 420.dp) else Modifier.height(190.dp).size(width = 150.dp, height = 190.dp))
                    .clip(RoundedCornerShape(20.dp)).background(DevxColors.CardInner)
                    .pressable(scaleTo = 0.98f, haptic = false) { model?.let(onOpen) },
            )
        }
        if (caption != null) Text(caption, style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

// ───────────────────────── charts ─────────────────────────

private val CHART_COLORS = listOf(Color(0xFFEDEDED), Color(0xFF8AB4F8), Color(0xFFF2A65A), Color(0xFF81C995), Color(0xFFF28B82), Color(0xFFC58AF9))

private class Series(val name: String, val values: List<Double>)

/** A bar, line or pie chart from numbers: {"kind":"bar","title":"…","labels":["Mon","Tue"],"series":[{"name":"Steps","values":[3,5]}]}. */
@Composable
fun ChartBlock(json: String, modifier: Modifier = Modifier) {
    val parsed = remember(json) {
        runCatching {
            val o = JSONObject(json)
            val labels = o.optJSONArray("labels")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            val series = o.optJSONArray("series")?.let { a -> (0 until minOf(a.length(), 6)).mapNotNull { i -> a.optJSONObject(i)?.let { s -> Series(s.optString("name"), s.optJSONArray("values")?.let { v -> (0 until minOf(v.length(), 60)).map { v.optDouble(it, 0.0) } }.orEmpty()) } } }.orEmpty()
            Triple(o.optString("kind", "bar").lowercase(), o.text("title"), labels to series)
        }.getOrNull()
    }
    if (parsed == null || parsed.third.second.isEmpty() || parsed.third.second.all { it.values.isEmpty() }) { CodeView("chart", json, modifier); return }
    val (kind, title, data) = parsed
    val (labels, series) = data
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = DevxColors.TextSecondary, fontSize = 11.sp)
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(DevxColors.Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (title != null) Text(title, style = DevxType.BodyStrong, color = DevxColors.TextPrimary)
        if (kind == "pie") {
            val values = series.first().values.map { max(0.0, it) }
            val total = values.sum().takeIf { it > 0 } ?: 1.0
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(130.dp)) {
                    var start = -90f
                    values.forEachIndexed { i, v ->
                        val sweep = (v / total * 360).toFloat()
                        drawArc(CHART_COLORS[i % CHART_COLORS.size], start, sweep - 1.5f, true, size = Size(size.width, size.height))
                        start += sweep
                    }
                    drawCircle(DevxColors.Card, size.minDimension * 0.28f)
                }
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    values.forEachIndexed { i, v ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(CHART_COLORS[i % CHART_COLORS.size]))
                            Text((labels.getOrNull(i) ?: "") + "  " + "%.0f%%".format(v / total * 100), style = DevxType.Caption, color = DevxColors.TextPrimary)
                        }
                    }
                }
            }
        } else {
            val maxValue = (series.flatMap { it.values }.maxOrNull() ?: 1.0).let { if (it <= 0) 1.0 else it }
            val minValue = if (kind == "line") (series.flatMap { it.values }.minOrNull() ?: 0.0).coerceAtMost(0.0) else 0.0
            val count = series.maxOf { it.values.size }
            Canvas(Modifier.fillMaxWidth().height(180.dp)) {
                val bottom = size.height - 22.dp.toPx(); val top = 14.dp.toPx()
                val span = (maxValue - minValue).takeIf { it > 0 } ?: 1.0
                fun yOf(v: Double) = bottom - ((v - minValue) / span * (bottom - top)).toFloat()
                drawLine(DevxColors.Outline, Offset(0f, bottom), Offset(size.width, bottom), 1.dp.toPx())
                val slot = size.width / count
                for (i in 0 until count) {
                    val label = labels.getOrNull(i) ?: continue
                    val layout = measurer.measure(label.take(8), labelStyle)
                    drawText(layout, topLeft = Offset(slot * i + (slot - layout.size.width) / 2, bottom + 4.dp.toPx()))
                }
                if (kind == "line") {
                    series.forEachIndexed { si, s ->
                        val color = CHART_COLORS[si % CHART_COLORS.size]
                        val path = Path()
                        s.values.forEachIndexed { i, v -> val o = Offset(slot * i + slot / 2, yOf(v)); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
                        drawPath(path, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        s.values.forEachIndexed { i, v -> drawCircle(color, 4.dp.toPx(), Offset(slot * i + slot / 2, yOf(v))) }
                    }
                } else {
                    val group = slot * 0.7f / series.size
                    series.forEachIndexed { si, s ->
                        s.values.forEachIndexed { i, v ->
                            val left = slot * i + slot * 0.15f + group * si
                            drawRect(CHART_COLORS[si % CHART_COLORS.size], Offset(left, yOf(max(v, 0.0))), Size(group - 2.dp.toPx(), bottom - yOf(max(v, 0.0))))
                            val text = measurer.measure(if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(Locale.US, v), labelStyle)
                            drawText(text, topLeft = Offset(left + (group - text.size.width) / 2, yOf(max(v, 0.0)) - text.size.height - 1.dp.toPx()))
                        }
                    }
                }
            }
            if (series.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                series.forEachIndexed { i, s ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(CHART_COLORS[i % CHART_COLORS.size]))
                        Text(s.name, style = DevxType.Caption, color = DevxColors.TextSecondary)
                    }
                }
            }
        }
    }
}


// ───────────────────────── quick answers ─────────────────────────

/** Short answers under a question: a tap sends the words as the person's message, and the row is spent. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RepliesBlock(json: String, onSend: (String) -> Unit, modifier: Modifier = Modifier) {
    val options = remember(json) {
        runCatching {
            val raw = json.trim()
            if (raw.startsWith("[")) JSONArray(raw).let { a -> (0 until a.length()).map { a.optString(it) } }
            else JSONObject(raw).optJSONArray("options")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        }.getOrElse { json.lines().map { it.trim().trimStart('-', '*', ' ') } }.map { it.trim() }.filter { it.isNotEmpty() }.take(6)
    }
    var chosen by remember(json) { mutableStateOf<String?>(null) }
    if (options.isEmpty()) return
    androidx.compose.foundation.layout.FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val picked = chosen == option
            Text(
                option,
                style = DevxType.Body,
                color = if (picked) DevxColors.OnAccent else if (chosen != null) DevxColors.TextDisabled else DevxColors.TextPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (picked) DevxColors.Accent else Color.Transparent)
                    .border(BorderStroke(1.dp, if (picked) DevxColors.Accent else DevxColors.Outline), RoundedCornerShape(50))
                    .pressable(enabled = chosen == null, scaleTo = 0.95f, haptic = false) {
                        chosen = option
                        onSend(option)
                    }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}
