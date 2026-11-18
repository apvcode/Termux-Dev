package com.apvcode.devx.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apvcode.devx.R
import com.apvcode.devx.data.ConceptLink
import com.apvcode.devx.data.GraphLayout
import com.apvcode.devx.data.GraphSim
import com.apvcode.devx.data.StudyPlan
import com.apvcode.devx.ui.chat.ChatViewModel
import com.apvcode.devx.ui.components.CircleIconButton
import com.apvcode.devx.ui.components.DevxDialog
import com.apvcode.devx.ui.components.DevxTextField
import com.apvcode.devx.ui.components.PillButton
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** How well a concept is known, from the cards that carry its name as their topic. */
enum class Mastery { Unknown, Learning, Known, Weak }

/** The concepts of a plan with their state, and the weak ones first-class: a topic in the weak spots makes the concept weak. */
internal fun masteryOf(plan: StudyPlan, name: String): Mastery {
    val n = name.trim().lowercase()
    fun related(topic: String): Boolean {
        val t = topic.trim().lowercase()
        return t.isNotEmpty() && (t == n || (t.length >= 4 && n.contains(t)) || (n.length >= 4 && t.contains(n)))
    }
    if (plan.weakSpots(20).any { related(it.topic) }) return Mastery.Weak
    val cards = plan.cards.filter { related(it.topic) }
    return when {
        cards.isEmpty() -> Mastery.Unknown
        cards.all { it.reps >= 2 } -> Mastery.Known
        else -> Mastery.Learning
    }
}

/** The state's colour; a concept without cards takes a soft shade of the notebook's own colour instead of a dull grey. */
@Composable
private fun Mastery.color(tint: Color = Color.Unspecified): Color = when (this) {
    Mastery.Weak -> DevxColors.Danger
    Mastery.Learning -> DevxColors.Warning
    Mastery.Known -> DevxColors.Success
    Mastery.Unknown -> if (tint.isSpecified) lerp(DevxColors.TextSecondary, tint, 0.55f) else DevxColors.TextSecondary
}

@Composable
private fun Mastery.label(): String = stringResource(
    when (this) { Mastery.Weak -> R.string.graph_weak; Mastery.Learning -> R.string.graph_learning; Mastery.Known -> R.string.graph_known; Mastery.Unknown -> R.string.graph_new },
)

private fun easeOutBack(t: Float): Float { val c = 1.70158f; val u = t - 1f; return 1f + (c + 1f) * u * u * u + c * u * u }
private fun easeOutCubic(t: Float): Float { val u = 1f - t; return 1f - u * u * u }

/**
 * Everything the picture needs between frames: the physics, the camera (with its own easing and momentum), what is lit and what fades.
 * It lives outside composition on purpose: a frame changes dozens of numbers and only [frame] tells the canvas to draw again.
 * When the map changes (a concept or a link is added) the new view [from] the old one keeps the positions, the camera and the choice,
 * so that the picture does not start over.
 */
private class GraphView(val sim: GraphSim, val edgeA: IntArray, val edgeB: IntArray, val density: Float, val ids: List<String>, from: GraphView? = null) {
    var frame by mutableIntStateOf(0)
    var selected by mutableIntStateOf(-1)
    var filter by mutableStateOf<Mastery?>(null)
    var query by mutableStateOf("")
    var states: List<Mastery> = emptyList()

    /** The names and the names with notes, lower case, for the search. */
    var names: List<String> = emptyList()
    var haystack: List<String> = emptyList()

    var onLongPress: (hit: Int, at: Offset) -> Unit = { _, _ -> }
    var onLinkDrop: (from: Int, to: Int) -> Unit = { _, _ -> }

    var w = 0f
    var h = 0f
    var scale = density
    var px = 0f
    var py = 0f
    private var tScale = density
    private var tpx = 0f
    private var tpy = 0f
    private var vx = 0f
    private var vy = 0f
    var touching = false
    var followSelected = false
    private var followSearch = false
    var autoFit = true
    var time = 0f
    var entry = 0f
    var grabbed = -1
    var dragging = -1
    var dropTarget = -1
    var touched by mutableStateOf(false)

    val n = sim.count
    val nodeLit = FloatArray(n) { 1f }
    val grab = FloatArray(n)
    val born = FloatArray(n) { Float.NaN }
    val edgeLit = FloatArray(edgeA.size) { 1f }
    val edgeHot = FloatArray(edgeA.size)
    val matches = BooleanArray(n)
    var matchCount = 0
        private set
    private var best = -1
    private var seenQuery = ""
    private val neighbours = List(n) { sim.neighbours(it) }
    private var acc = 0f

    init {
        if (from != null) {
            scale = from.scale; px = from.px; py = from.py; tScale = from.tScale; tpx = from.tpx; tpy = from.tpy
            time = from.time; entry = 1f; touched = from.touched; autoFit = from.autoFit
            filter = from.filter; query = from.query; seenQuery = from.seenQuery; followSearch = from.followSearch
            val chosen = from.ids.getOrNull(from.selected)
            selected = if (chosen == null) -1 else ids.indexOf(chosen)
            followSelected = selected >= 0 && from.followSelected
            val before = from.ids.toSet()
            ids.forEachIndexed { i, id ->
                if (id !in before) born[i] = time
                val k = from.ids.indexOf(id)
                if (k >= 0) { nodeLit[i] = from.nodeLit[k]; grab[i] = 0f }
            }
        }
    }

    /** Where a node is now, by the id of the concept (null if it is not here). */
    fun posOf(id: String): Pair<Float, Float>? = ids.indexOf(id).takeIf { it >= 0 }?.let { sim.x[it] to sim.y[it] }

    fun spotlight(i: Int): Boolean = (selected >= 0 && (i == selected || i in neighbours[selected])) || (selected < 0 && query.isNotBlank() && matches[i])

    fun sx(wx: Float) = w / 2f + px + wx * scale
    fun sy(wy: Float) = h / 2f + py + wy * scale
    fun worldX(sx: Float) = (sx - w / 2f - px) / scale
    fun worldY(sy: Float) = (sy - h / 2f - py) / scale

    /** The size of a node on the screen: it grows with the zoom, but more slowly than the distances do. */
    fun radiusPx(i: Int): Float = sim.radius(i) * density * (scale / density).pow(0.72f)

    fun hit(at: Offset): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in 0 until n) {
            val d = hypot(at.x - sx(sim.x[i]), at.y - sy(sim.y[i]))
            if (d < radiusPx(i) + 14f * density && d < bestD) { bestD = d; best = i }
        }
        return best
    }

    // ---- touches ----
    fun pick(i: Int) { sim.pinned[i] = true; sim.ghost = i; dragging = i; sim.reheat(0.5f) }

    fun dragTo(i: Int, at: Offset) {
        sim.moveTo(i, worldX(at.x), worldY(at.y)); sim.reheat(0.3f)
        // Over another concept: letting go there links the two.
        var target = -1
        var bestD = Float.MAX_VALUE
        for (j in 0 until n) {
            if (j == i) continue
            val d = hypot(sx(sim.x[i]) - sx(sim.x[j]), sy(sim.y[i]) - sy(sim.y[j]))
            if (d < (radiusPx(i) + radiusPx(j)) * 1.05f + 8f * density && d < bestD) { bestD = d; target = j }
        }
        dropTarget = target
    }

    /** Lets a dragged node go; gives the concept it was dropped on, or -1. */
    fun drop(i: Int): Int {
        sim.pinned[i] = false; sim.ghost = -1; dragging = -1
        val target = dropTarget
        dropTarget = -1
        sim.reheat(0.45f)
        return target
    }

    fun panBy(dx: Float, dy: Float) { px += dx; py += dy; syncTargets() }

    fun zoomAbout(centroid: Offset, zoom: Float, pan: Offset) {
        val next = (scale * zoom).coerceIn(0.35f * density, 3.5f * density)
        val qx = centroid.x - w / 2f; val qy = centroid.y - h / 2f
        val k = next / scale
        px = qx - (qx - px) * k + pan.x
        py = qy - (qy - py) * k + pan.y
        scale = next
        syncTargets()
    }

    fun fling(v: Offset) { vx = v.x; vy = v.y }

    private fun syncTargets() { tScale = scale; tpx = px; tpy = py; vx = 0f; vy = 0f }

    fun release() { followSelected = false; followSearch = false; autoFit = false }

    fun select(i: Int) {
        selected = i
        if (i >= 0) { followSelected = true; autoFit = false } else { followSelected = false; if (query.isBlank()) autoFit = true else followSearch = matchCount > 0 }
    }

    fun refit() { select(-1); filter = null; query = ""; autoFit = true }

    /** The best match of the search becomes the chosen concept. */
    fun commitSearch() { if (best >= 0) select(best) }

    fun shake() {
        for (i in 0 until n) sim.moveTo(i, sim.x[i] + (((i * 7919) % 13) - 6) * 22f, sim.y[i] + (((i * 104729) % 11) - 5) * 22f)
        sim.reheat(1f)
        refit()
    }

    private fun fitTarget() {
        val b = sim.bounds()
        val bw = max(b[2] - b[0], 90f); val bh = max(b[3] - b[1], 90f)
        val top = 150f * density; val bottom = 56f * density
        val ts = min((w - 56f * density) / bw, (h - top - bottom) / bh).coerceIn(0.5f * density, 1.7f * density)
        tScale = ts
        tpx = -(b[0] + b[2]) / 2f * ts
        tpy = (top - bottom) / 2f - (b[1] + b[3]) / 2f * ts
    }

    /** Frames a few nodes in the free space between the header and the bottom (a card, or the keyboard). */
    private fun fitSet(set: Collection<Int>, bottomDp: Float) {
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (j in set) { x0 = min(x0, sim.x[j]); x1 = max(x1, sim.x[j]); y0 = min(y0, sim.y[j]); y1 = max(y1, sim.y[j]) }
        if (x0 > x1) return
        val bw = max(x1 - x0, 120f) + 90f; val bh = max(y1 - y0, 120f) + 90f
        val top = 150f * density; val bottom = bottomDp * density
        val ts = min((w - 24f * density) / bw, (h - top - bottom) / bh).coerceIn(0.5f * density, 1.6f * density)
        tScale = ts
        tpx = -(x0 + x1) / 2f * ts
        tpy = (top - bottom) / 2f - (y0 + y1) / 2f * ts
    }

    private fun findMatches() {
        val q = query.trim().lowercase()
        if (q != seenQuery) {
            seenQuery = q
            if (q.isEmpty()) { if (selected < 0) autoFit = true; followSearch = false } else { followSearch = true; autoFit = false }
        }
        val tokens = q.split(' ').filter { it.isNotEmpty() }
        matchCount = 0; best = -1
        var bestScore = 0
        for (i in 0 until n) {
            val hay = haystack.getOrNull(i).orEmpty()
            val ok = tokens.isNotEmpty() && tokens.all { hay.contains(it) }
            matches[i] = ok
            if (!ok) continue
            matchCount++
            val name = names.getOrNull(i).orEmpty()
            val score = if (name.startsWith(q)) 3 else if (name.contains(q)) 2 else 1
            if (score > bestScore) { bestScore = score; best = i }
        }
    }

    // ---- a frame ----
    fun advance(dt: Float) {
        time += dt
        entry = min(1f, entry + dt / 2.1f)
        acc += dt
        var steps = 0
        while (acc >= STEP && steps < 4) { sim.step(); acc -= STEP; steps++ }
        if (acc > STEP) acc = 0f
        findMatches()

        // What is lit: the selected concept with its neighbours, what the search found, or a state picked in the legend.
        val sel = selected
        val flt = filter
        val searching = query.isNotBlank()
        val ease = 1f - exp(-dt * 9f)
        for (i in 0 until n) {
            val target = when {
                sel >= 0 -> if (spotlight(i)) 1f else 0.14f
                searching -> if (matches[i]) 1f else 0.14f
                flt != null -> if (states.getOrNull(i) == flt) 1f else 0.14f
                else -> 1f
            }
            nodeLit[i] += (target - nodeLit[i]) * ease
            val g = if (i == grabbed) 1f else 0f
            grab[i] += (g - grab[i]) * (1f - exp(-dt * 14f))
        }
        for (k in edgeA.indices) {
            val a = edgeA[k]; val b = edgeB[k]
            val hotT = if (sel >= 0 && (a == sel || b == sel)) 1f else 0f
            val litT = when {
                sel >= 0 -> if (hotT > 0f) 1f else 0.07f
                searching -> if (matches[a] && matches[b]) 1f else 0.07f
                flt != null -> if (states.getOrNull(a) == flt && states.getOrNull(b) == flt) 1f else 0.07f
                else -> 1f
            }
            edgeHot[k] += (hotT - edgeHot[k]) * ease
            edgeLit[k] += (litT - edgeLit[k]) * ease
        }

        // The camera: it follows what the person looks at, and glides with the finger's momentum.
        if (!touching && w > 0f) {
            if (followSelected && sel >= 0) fitSet(neighbours[sel] + sel, 310f)
            else if (followSearch && sel < 0 && matchCount > 0) fitSet((0 until n).filter { matches[it] }, 340f)
            else if (autoFit) fitTarget()
            if (abs(vx) + abs(vy) > 12f) {
                px += vx * dt; py += vy * dt
                val damp = exp(-dt * 3.2f); vx *= damp; vy *= damp
                tScale = scale; tpx = px; tpy = py
            } else {
                val k = 1f - exp(-dt * if (autoFit && entry < 1f) 3.2f else 6.5f)
                scale += (tScale - scale) * k; px += (tpx - px) * k; py += (tpy - py) * k
            }
        }
        frame++
    }

    companion object { const val STEP = 1f / 60f }
}

/**
 * The concept map of a notebook, alive: the concepts float like in Obsidian's graph, pull on their neighbours when dragged and settle
 * again; pinch to zoom, drag the background to move around, tap a concept to light up its connections and open its card. The person can
 * also work on it: hold a concept to rename or delete it, hold an empty place to add one, drop one onto another to link them, search.
 */
@Composable
fun GraphScreen(vm: ChatViewModel, name: String, onClose: () -> Unit, onAsk: (String) -> Unit) {
    val info = vm.projectInfo[name]
    if (info == null) { LaunchedEffect(Unit) { onClose() }; return }
    val plan = info.plan
    val study = info.isStudy
    val concepts = plan.concepts.toList()
    val links = plan.links.toList()
    val tint = Color(info.color).let { if (info.color == 0xFFE6E6E6L) Color(0xFF8FA3D9) else it }
    val density = LocalDensity.current.density
    val haptic = LocalHapticFeedback.current
    val focus = LocalFocusManager.current

    val index = remember(concepts.map { it.id }) { concepts.mapIndexed { i, c -> c.id to i }.toMap() }
    val resolved = remember(concepts.map { it.id }, links.map { it.from + it.to + it.label }) {
        links.mapNotNull { l -> index[l.from]?.let { a -> index[l.to]?.let { b -> if (a != b) Triple(a, b, l.label) else null } } }
    }
    val last = remember { arrayOfNulls<GraphView>(1) }
    val spawn = remember { HashMap<String, Pair<Float, Float>>() }
    val view = remember(concepts.map { it.id }, resolved.map { it.first to it.second }, density) {
        val edges = resolved.map { it.first to it.second }
        val ids = concepts.map { it.id }
        val old = last[0]
        // Where each concept starts: where it was; a new one near what it is linked to (or where it was made).
        val start = ArrayList<Pair<Float, Float>?>(ids.size)
        ids.forEach { start += old?.posOf(it) ?: spawn[it] }
        ids.forEachIndexed { i, _ ->
            if (start[i] == null && old != null) {
                val near = edges.mapNotNull { (a, b) -> if (a == i) start[b] else if (b == i) start[a] else null }
                start[i] = if (near.isNotEmpty()) near.map { it.first }.average().toFloat() + 24f to near.map { it.second }.average().toFloat() + 24f else 0f to 0f
            }
        }
        val sim = GraphSim(concepts.size, edges, GraphLayout.layout(concepts.size, edges), start = if (old == null) emptyList() else start, heat = if (old == null) 1f else 0.55f)
        GraphView(sim, IntArray(resolved.size) { resolved[it].first }, IntArray(resolved.size) { resolved[it].second }, density, ids, old).also { last[0] = it }
    }
    val states = remember(concepts.map { it.name }, plan.cards.size, plan.cards.sumOf { it.reps + it.lapses }, study) {
        concepts.map { if (study) masteryOf(plan, it.name) else Mastery.Unknown }
    }
    view.states = states
    view.names = concepts.map { it.name.trim().lowercase() }
    view.haystack = concepts.map { (it.name + " " + it.note).trim().lowercase() }

    val measurer = rememberTextMeasurer()
    val textColor = DevxColors.TextPrimary
    val labelStyle = remember(textColor) { TextStyle(fontSize = 11.5.sp, color = textColor, textAlign = TextAlign.Center) }
    val edgeStyle = remember(textColor) { TextStyle(fontSize = 10.sp, color = textColor, textAlign = TextAlign.Center) }
    val labels: List<TextLayoutResult> = remember(concepts.map { it.name }, labelStyle, density) {
        concepts.map { measurer.measure(it.name, labelStyle, maxLines = 2, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = (112 * density).toInt())) }
    }
    val edgeLabels: List<TextLayoutResult?> = remember(resolved.map { it.third }, edgeStyle, density) {
        resolved.map { (_, _, label) -> if (label.isBlank()) null else measurer.measure(label, edgeStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = (120 * density).toInt())) }
    }

    LaunchedEffect(view) {
        var lastNanos = 0L
        while (true) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            val dt = if (lastNanos == 0L) 1f / 60f else ((now - lastNanos) / 1e9f).coerceIn(0.001f, 0.05f)
            lastNanos = now
            view.advance(dt)
        }
    }

    // What the person is doing by hand.
    var editing by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf<Offset?>(null) }
    var pendingLink by remember { mutableStateOf<Pair<String, String>?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    view.onLongPress = { hit, at -> if (hit >= 0) editing = concepts[hit].id else creating = Offset(view.worldX(at.x), view.worldY(at.y)) }
    view.onLinkDrop = { a, b -> pendingLink = concepts[a].id to concepts[b].id }

    val nodeColors = states.map { it.color(tint) }
    val bg = DevxColors.Bg
    val lineColor = DevxColors.TextSecondary
    val textPrimary = DevxColors.TextPrimary

    Box(Modifier.fillMaxSize().background(bg)) {
        if (concepts.isEmpty()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(if (study) R.string.graph_empty else R.string.graph_empty_ideas), style = DevxType.Body, color = DevxColors.TextSecondary, textAlign = TextAlign.Center)
                PillButton(stringResource(R.string.graph_build), onClick = { onAsk(vm.text(if (study) R.string.graph_build_prompt else R.string.graph_build_prompt_ideas)) }, container = tint, modifier = Modifier.padding(top = 20.dp).fillMaxWidth())
                PillButton(stringResource(R.string.graph_add), filled = false, onClick = { creating = Offset.Zero }, modifier = Modifier.padding(top = 10.dp).fillMaxWidth())
            }
            Box(Modifier.statusBarsPadding().padding(16.dp)) { CircleIconButton(Icons.Outlined.Close, stringResource(R.string.cd_close), onClose) }
        } else {
            Canvas(
                Modifier.fillMaxSize()
                    .onSizeChanged { view.w = it.width.toFloat(); view.h = it.height.toFloat() }
                    .pointerInput(view) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            view.touching = true
                            view.touched = true
                            val slop = viewConfiguration.touchSlop
                            val longMs = viewConfiguration.longPressTimeoutMillis
                            val startedAt = System.currentTimeMillis()
                            val hit = view.hit(down.position)
                            if (hit >= 0) view.grabbed = hit
                            var mode = 0 // 0: not decided (a tap, if it stays so), 1: a node is dragged, 2: the picture is moved, 3: a long press was made
                            var multi = false
                            var last = down.position
                            var pressedBefore = 1
                            val tracker = VelocityTracker()
                            tracker.addPosition(down.uptimeMillis, down.position)
                            while (true) {
                                val event = if (mode == 0) {
                                    val left = longMs - (System.currentTimeMillis() - startedAt)
                                    withTimeoutOrNull(left.coerceAtLeast(1L)) { awaitPointerEvent() }
                                } else awaitPointerEvent()
                                if (event == null) {
                                    // Held still long enough: a concept is edited, an empty place gets a new one.
                                    mode = 3
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    view.onLongPress(hit, down.position)
                                    continue
                                }
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break
                                if (pressed.size >= 2 && mode != 3) {
                                    if (mode == 1) { view.drop(hit); view.grabbed = -1 }
                                    mode = 2; multi = true
                                    view.release()
                                    view.zoomAbout(event.calculateCentroid(), event.calculateZoom(), event.calculatePan())
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                } else if (pressed.size == 1) {
                                    val ch: PointerInputChange = pressed[0]
                                    val pos = ch.position
                                    if (pressedBefore != 1) last = pos
                                    if (mode == 0 && (pos - down.position).getDistance() > slop) {
                                        mode = if (hit >= 0) 1 else 2
                                        if (mode == 1) { view.pick(hit); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                                        else view.release()
                                    }
                                    when (mode) {
                                        1 -> view.dragTo(hit, pos)
                                        2 -> { view.panBy(pos.x - last.x, pos.y - last.y); tracker.addPosition(ch.uptimeMillis, pos) }
                                    }
                                    last = pos
                                    if (ch.positionChanged() && mode != 0) ch.consume()
                                }
                                pressedBefore = pressed.size
                            }
                            view.touching = false
                            view.grabbed = -1
                            when (mode) {
                                0 -> if (hit >= 0 && hit != view.selected) { view.select(hit); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } else view.select(-1)
                                1 -> { val onto = view.drop(hit); if (onto >= 0) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); view.onLinkDrop(hit, onto) } }
                                2 -> if (!multi) tracker.calculateVelocity().let { view.fling(Offset(it.x, it.y)) }
                            }
                        }
                    },
            ) {
                view.frame // a frame was made: draw again
                val t = view.time
                val entry = view.entry
                val d = density

                // The ground is the theme's own background (the Box behind the canvas): no glow and no grid.

                fun bobX(i: Int) = sin(t * 0.8f + i * 1.9f) * 1.8f * d
                fun bobY(i: Int) = sin(t * 0.7f + i * 2.7f + 1f) * 1.8f * d
                fun px(i: Int) = view.sx(view.sim.x[i]) + bobX(i)
                fun py(i: Int) = view.sy(view.sim.y[i]) + bobY(i)
                val appear = FloatArray(view.n) { i ->
                    val b = view.born[i]
                    if (!b.isNaN()) easeOutBack(((t - b) / 0.55f).coerceIn(0f, 1f)).coerceAtLeast(0f)
                    else easeOutBack(((entry - i.toFloat() / view.n * 0.45f) / 0.5f).coerceIn(0f, 1f)).coerceAtLeast(0f)
                }

                // The links: thin and quiet; the ones of the chosen concept glow in the colours of both ends, with sparks running along them.
                val growth = easeOutCubic(((entry - 0.18f) / 0.55f).coerceIn(0f, 1f))
                for (k in view.edgeA.indices) {
                    val a = view.edgeA[k]; val b = view.edgeB[k]
                    val ax = px(a); val ay = py(a); val bx = px(b); val by = py(b)
                    val len = hypot(bx - ax, by - ay)
                    if (len < 1f) continue
                    val ux = (bx - ax) / len; val uy = (by - ay) / len
                    val ra = view.radiusPx(a) * appear[a].coerceAtMost(1f); val rb = view.radiusPx(b) * appear[b].coerceAtMost(1f)
                    val start = Offset(ax + ux * ra * 0.6f, ay + uy * ra * 0.6f)
                    val full = Offset(bx - ux * rb * 0.6f, by - uy * rb * 0.6f)
                    val end = Offset(start.x + (full.x - start.x) * growth, start.y + (full.y - start.y) * growth)
                    val lit = view.edgeLit[k]; val hot = view.edgeHot[k]
                    drawLine(lineColor.copy(alpha = (0.30f * lit * (1f - hot * 0.6f)).coerceIn(0f, 1f)), start, end, strokeWidth = 1.1f * d, cap = StrokeCap.Round)
                    if (hot > 0.02f) {
                        drawLine(Brush.linearGradient(listOf(nodeColors[a].copy(alpha = 0.95f * hot), nodeColors[b].copy(alpha = 0.95f * hot)), start = start, end = end), start, end, strokeWidth = (1.2f + 1.5f * hot) * d, cap = StrokeCap.Round)
                        for (j in 0..1) {
                            val ph = (t * 0.5f + k * 0.37f + j * 0.5f) % 1f
                            drawCircle(nodeColors[a].copy(alpha = sin(ph * PI.toFloat()) * hot), 2.4f * d, Offset(start.x + (end.x - start.x) * ph, start.y + (end.y - start.y) * ph))
                        }
                    }
                    // An arrow head at the end that the link points to.
                    if (growth > 0.95f && len > (ra + rb) * 1.4f) {
                        val tip = Offset(bx - ux * (rb + 3f * d), by - uy * (rb + 3f * d))
                        val s = (4.6f + 1.8f * hot) * d
                        val head = Path().apply {
                            moveTo(tip.x, tip.y)
                            lineTo(tip.x - ux * s * 1.5f - uy * s, tip.y - uy * s * 1.5f + ux * s)
                            lineTo(tip.x - ux * s * 1.5f + uy * s, tip.y - uy * s * 1.5f - ux * s)
                            close()
                        }
                        drawPath(head, if (hot > 0.05f) nodeColors[b].copy(alpha = 0.95f * hot) else lineColor.copy(alpha = 0.34f * lit), style = Fill)
                    }
                }

                // A concept being dragged over another: a dashed thread and a ring show that letting go will link them.
                val dragFrom = view.dragging
                val dragTo = view.dropTarget
                if (dragFrom >= 0 && dragTo >= 0) {
                    val a = Offset(px(dragFrom), py(dragFrom)); val b = Offset(px(dragTo), py(dragTo))
                    drawLine(tint.copy(alpha = 0.9f), a, b, strokeWidth = 2.2f * d, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f * d, 8f * d), -t * 40f * d))
                    val pulse = 1f + 0.12f * sin(t * 9f)
                    drawCircle(tint.copy(alpha = 0.9f), view.radiusPx(dragTo) * 1.9f * pulse, b, style = Stroke(2.4f * d))
                }

                // The concepts.
                for (i in 0 until view.n) {
                    val ap = appear[i]
                    if (ap <= 0.001f) continue
                    val lit = view.nodeLit[i] * ap.coerceAtMost(1f)
                    val p = Offset(px(i), py(i))
                    val grab = view.grab[i]
                    val sel = i == view.selected
                    val r = view.radiusPx(i) * ap * (1f + 0.22f * grab + if (sel) 0.12f else 0f)
                    val c = nodeColors[i]
                    val state = states[i]
                    val breathe = 1f + 0.1f * sin(t * 1.5f + i * 1.3f)
                    drawCircle(Brush.radialGradient(listOf(c.copy(alpha = (0.42f + 0.25f * grab) * lit), Color.Transparent), center = p, radius = r * 3.4f * breathe), r * 3.4f * breathe, p)
                    if (state == Mastery.Weak) {
                        val ph = (t * 0.65f + i * 0.31f) % 1f
                        drawCircle(c.copy(alpha = (1f - ph) * 0.55f * lit), r * (1.25f + ph * 2.1f), p, style = Stroke(1.6f * d))
                    }
                    drawCircle(Brush.radialGradient(listOf(lerp(c, Color.White, 0.42f), c, lerp(c, Color.Black, 0.22f)), center = Offset(p.x - r * 0.3f, p.y - r * 0.35f), radius = r * 1.5f), r, p, alpha = lit)
                    drawCircle(Color.White.copy(alpha = 0.28f * lit), r, p, style = Stroke(1f * d))
                    if (sel) {
                        val rr = r * 1.55f
                        drawArc(c.copy(alpha = 0.95f), startAngle = t * 140f, sweepAngle = 250f, useCenter = false, topLeft = Offset(p.x - rr, p.y - rr), size = Size(rr * 2f, rr * 2f), style = Stroke(2.2f * d, cap = StrokeCap.Round))
                        drawArc(textPrimary.copy(alpha = 0.55f), startAngle = -t * 90f + 180f, sweepAngle = 60f, useCenter = false, topLeft = Offset(p.x - rr, p.y - rr), size = Size(rr * 2f, rr * 2f), style = Stroke(1.6f * d, cap = StrokeCap.Round))
                    } else if (view.query.isNotBlank() && view.matches[i]) {
                        val rr = r * (1.5f + 0.1f * sin(t * 4f))
                        drawCircle(tint.copy(alpha = 0.9f * lit), rr, p, style = Stroke(2f * d))
                    }
                }

                // Names: all of them when zoomed in, only the busiest ones when zoomed out, always for what is lit.
                val zoom = view.scale / d
                for (i in 0 until view.n) {
                    val ap = appear[i].coerceIn(0f, 1f)
                    if (ap < 0.6f) continue
                    val threshold = if (view.n <= 30) 0.25f else 0.78f - 0.07f * min(view.sim.degree[i], 5)
                    val base = ((zoom - threshold) / 0.25f).coerceIn(0f, 1f)
                    val vis = max(base, if (view.spotlight(i)) 1f else 0f) * (0.2f + 0.8f * view.nodeLit[i].coerceIn(0f, 1f)).coerceAtMost(1f) * ((ap - 0.6f) / 0.4f)
                    if (vis < 0.03f) continue
                    val l = labels[i]
                    val r = view.radiusPx(i)
                    drawText(l, color = textPrimary.copy(alpha = vis), topLeft = Offset(px(i) - l.size.width / 2f, py(i) + r * 1.25f + 4f * d), shadow = Shadow(bg.copy(alpha = vis), Offset.Zero, 10f))
                }

                // What a lit link says, on a small plate in the middle of it.
                for (k in view.edgeA.indices) {
                    val hot = view.edgeHot[k]
                    val l = edgeLabels.getOrNull(k) ?: continue
                    if (hot < 0.5f) continue
                    val a = view.edgeA[k]; val b = view.edgeB[k]
                    val mx = (px(a) + px(b)) / 2f; val my = (py(a) + py(b)) / 2f
                    val a2 = ((hot - 0.5f) * 2f)
                    val pw = l.size.width + 14f * d; val ph = l.size.height + 6f * d
                    drawRoundRect(bg.copy(alpha = 0.82f * a2), Offset(mx - pw / 2f, my - ph / 2f), Size(pw, ph), CornerRadius(ph / 2f))
                    drawRoundRect(lineColor.copy(alpha = 0.35f * a2), Offset(mx - pw / 2f, my - ph / 2f), Size(pw, ph), CornerRadius(ph / 2f), style = Stroke(1f * d))
                    drawText(l, color = textPrimary.copy(alpha = a2), topLeft = Offset(mx - l.size.width / 2f, my - l.size.height / 2f))
                }
            }

            // A soft veil under the header, so that names drifting past it do not clash with the title.
            Box(Modifier.fillMaxWidth().height(190.dp).background(Brush.verticalGradient(listOf(bg.copy(alpha = 0.96f), bg.copy(alpha = 0.78f), Color.Transparent))))

            // The top: the way back, the title, the search; under it the search field or the legend.
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircleIconButton(Icons.Outlined.Close, stringResource(R.string.cd_close), onClose)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.graph_title), style = DevxType.BodyStrong, color = DevxColors.TextPrimary, maxLines = 1)
                        Text(
                            name + " · " + stringResource(R.string.graph_row_count, concepts.size, resolved.size),
                            style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    CircleIconButton(
                        Icons.Outlined.Search, stringResource(R.string.graph_search),
                        { if (searchOpen) { searchOpen = false; view.query = ""; focus.clearFocus() } else searchOpen = true },
                        tint = if (searchOpen) tint else DevxColors.TextPrimary,
                    )
                }
                AnimatedVisibility(visible = searchOpen, enter = fadeIn(tween(220)) + expandVertically(tween(260)), exit = fadeOut(tween(160)) + shrinkVertically(tween(200))) {
                    val requester = remember { FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(50)).background(DevxColors.Card.copy(alpha = 0.92f))
                            .border(1.dp, if (view.query.isNotBlank() && view.matchCount == 0) DevxColors.Danger.copy(alpha = 0.6f) else DevxColors.Outline, RoundedCornerShape(50))
                            .padding(start = 16.dp, end = 14.dp).height(48.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Outlined.Search, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(20.dp))
                        BasicTextField(
                            value = view.query, onValueChange = { view.query = it }, singleLine = true,
                            textStyle = DevxType.Body.copy(color = DevxColors.TextPrimary), cursorBrush = SolidColor(DevxColors.Accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { view.commitSearch(); focus.clearFocus() }),
                            modifier = Modifier.weight(1f).focusRequester(requester),
                            decorationBox = { inner -> Box { if (view.query.isEmpty()) Text(stringResource(R.string.graph_search_hint), style = DevxType.Body, color = DevxColors.TextDisabled); inner() } },
                        )
                        if (view.query.isNotBlank()) Text(
                            if (view.matchCount == 0) stringResource(R.string.graph_search_none) else view.matchCount.toString(),
                            style = DevxType.Caption, color = if (view.matchCount == 0) DevxColors.Danger else DevxColors.TextSecondary,
                        )
                    }
                }
                if (study) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Mastery.Weak, Mastery.Learning, Mastery.Known, Mastery.Unknown).forEach { m ->
                        val count = states.count { it == m }
                        if (count > 0) LegendChip(m, count, tint, active = view.filter == m) { view.select(-1); view.filter = if (view.filter == m) null else m }
                    }
                }
            }

            // The tools at the edge: add a concept, shake the picture loose, show it all.
            Column(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 128.dp, end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircleIconButton(Icons.Outlined.Add, stringResource(R.string.graph_add), { creating = Offset(view.worldX(view.w / 2f), view.worldY(view.h / 2f)) }, container = DevxColors.Card.copy(alpha = 0.9f))
                CircleIconButton(Icons.Outlined.Shuffle, stringResource(R.string.graph_shuffle), { view.shake() }, container = DevxColors.Card.copy(alpha = 0.9f))
                CircleIconButton(Icons.Outlined.CenterFocusStrong, stringResource(R.string.graph_fit), { view.refit() }, container = DevxColors.Card.copy(alpha = 0.9f))
            }

            // First time here: how to move around, until the first touch.
            AnimatedVisibility(
                visible = !view.touched && view.selected < 0,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp),
                enter = fadeIn(tween(900, delayMillis = 1400)), exit = fadeOut(tween(300)),
            ) {
                Text(
                    stringResource(R.string.graph_hint), style = DevxType.Caption, color = DevxColors.TextSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(DevxColors.Card.copy(alpha = 0.85f)).padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }

            // The chosen concept.
            var shown by remember { mutableIntStateOf(-1) }
            LaunchedEffect(view.selected) { if (view.selected >= 0) shown = view.selected }
            AnimatedVisibility(
                visible = view.selected >= 0 && shown in concepts.indices,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(tween(420, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(320)),
                exit = slideOutVertically(tween(260, easing = FastOutSlowInEasing)) { it / 2 } + fadeOut(tween(200)),
            ) {
                val at = shown.coerceIn(0, concepts.lastIndex)
                val chosen = concepts[at]
                val state = states[at]
                val stateColor = nodeColors[at]
                val relatedIdx = resolved.mapNotNull { (a, b, _) -> if (a == at) b else if (b == at) a else null }.distinct()
                val related = relatedIdx.map { concepts[it].name }
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp)
                        .clip(RoundedCornerShape(28.dp)).background(DevxColors.Card.copy(alpha = 0.94f))
                        .border(1.dp, stateColor.copy(alpha = 0.35f), RoundedCornerShape(28.dp)).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(stateColor))
                        Text(chosen.name, style = DevxType.Title, color = DevxColors.TextPrimary, modifier = Modifier.weight(1f), maxLines = 2)
                        if (study) Text(
                            state.label(), style = DevxType.Caption, color = stateColor,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(stateColor.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                        CircleIconButton(Icons.Outlined.Edit, stringResource(R.string.graph_edit), { editing = chosen.id }, size = 36.dp, container = DevxColors.CardInner)
                    }
                    if (chosen.note.isNotBlank()) Text(chosen.note, style = DevxType.Body, color = DevxColors.TextSecondary)
                    if (relatedIdx.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            relatedIdx.forEach { j ->
                                Row(
                                    Modifier.clip(RoundedCornerShape(50)).border(1.dp, DevxColors.Outline, RoundedCornerShape(50)).pressable(scaleTo = 0.95f, haptic = false) { view.select(j) }.padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
                                ) {
                                    Box(Modifier.size(7.dp).clip(CircleShape).background(nodeColors[j]))
                                    Text(concepts[j].name, style = DevxType.Caption, color = DevxColors.TextPrimary, maxLines = 1)
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f)) { PillButton(stringResource(R.string.graph_explain), onClick = { onAsk(vm.text(R.string.graph_explain_prompt, chosen.name, related.joinToString(", ").ifEmpty { "…" })) }, container = tint) }
                        if (study) Box(Modifier.weight(1f)) { PillButton(stringResource(R.string.graph_quiz), filled = false, onClick = { onAsk(vm.text(R.string.graph_quiz_prompt, chosen.name)) }) }
                        else Box(Modifier.weight(1f)) { PillButton(stringResource(R.string.graph_in_sources), filled = false, onClick = { onAsk(vm.text(R.string.graph_sources_prompt, chosen.name)) }) }
                    }
                }
            }
        }
    }

    // Changing a concept: its name and line, its links, and taking it off the map.
    editing?.let { id ->
        val concept = concepts.firstOrNull { it.id == id }
        if (concept == null) editing = null
        else {
            val connections = links.mapNotNull { l ->
                val other = if (l.from == id) l.to else if (l.to == id) l.from else return@mapNotNull null
                concepts.firstOrNull { it.id == other }?.let { l to it.name }
            }
            ConceptDialog(
                title = stringResource(R.string.graph_edit_title), initialName = concept.name, initialNote = concept.note, connections = connections, tint = tint,
                onSave = { n, note -> vm.editGraph(name) { p -> val ok = p.renameConcept(id, n); if (ok) p.setConceptNote(id, note); ok }.also { if (it) editing = null } },
                onDelete = { view.select(-1); vm.editGraph(name) { p -> p.removeConceptById(id); true }; editing = null },
                onRemoveLink = { l -> vm.editGraph(name) { p -> p.removeLink(l); true } },
                onDismiss = { editing = null },
            )
        }
    }
    creating?.let { at ->
        ConceptDialog(
            title = stringResource(R.string.graph_new_title), initialName = "", initialNote = "", connections = null, tint = tint,
            onSave = { n, note -> vm.editGraph(name) { p -> p.addConcept(n, note)?.also { spawn[it.id] = at.x to at.y } != null }.also { if (it) creating = null } },
            onDelete = null, onRemoveLink = {}, onDismiss = { creating = null },
        )
    }
    pendingLink?.let { (a, b) ->
        val from = concepts.firstOrNull { it.id == a }
        val to = concepts.firstOrNull { it.id == b }
        if (from == null || to == null) pendingLink = null
        else {
            var label by remember(a, b) { mutableStateOf("") }
            DevxDialog(stringResource(R.string.graph_link_title, from.name, to.name), onDismiss = { pendingLink = null }) {
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    DevxTextField(stringResource(R.string.graph_link_hint), label, { label = it.take(30) })
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f)) { PillButton(stringResource(R.string.graph_link_make), onClick = { vm.editGraph(name) { p -> p.linkIds(a, b, label) }; pendingLink = null }, container = tint) }
                        Box(Modifier.weight(1f)) { PillButton(stringResource(R.string.settings_cancel), filled = false, onClick = { pendingLink = null }) }
                    }
                }
            }
        }
    }
}

/** One window for a concept, new or old: its name, a line about it, and (for an old one) its links and a way to delete it. */
@Composable
private fun ConceptDialog(
    title: String,
    initialName: String,
    initialNote: String,
    connections: List<Pair<ConceptLink, String>>?,
    tint: Color,
    onSave: (name: String, note: String) -> Boolean,
    onDelete: (() -> Unit)?,
    onRemoveLink: (ConceptLink) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var note by remember(initialNote) { mutableStateOf(initialNote) }
    var taken by remember { mutableStateOf(false) }
    DevxDialog(title, onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // The fields scroll when there are many links; the buttons stay in view below them.
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                DevxTextField(stringResource(R.string.graph_name_label), name, { name = it.take(50); taken = false })
                if (taken) Text(stringResource(R.string.graph_name_taken), style = DevxType.Caption, color = DevxColors.Danger, modifier = Modifier.padding(start = 6.dp))
                DevxTextField(stringResource(R.string.graph_note_label), note, { note = it.take(300) }, singleLine = false, minLines = 2)
                if (!connections.isNullOrEmpty()) {
                    Text(stringResource(R.string.graph_links), style = DevxType.Section, color = DevxColors.TextSecondary, modifier = Modifier.padding(start = 6.dp))
                    connections.forEach { (l, other) ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, DevxColors.Outline, RoundedCornerShape(16.dp)).padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(other + if (l.label.isNotBlank()) " · " + l.label else "", style = DevxType.Body, color = DevxColors.TextPrimary, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            CircleIconButton(Icons.Outlined.Close, stringResource(R.string.menu_delete), { onRemoveLink(l) }, size = 36.dp, container = DevxColors.CardInner)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { PillButton(stringResource(R.string.memory_save), enabled = name.isNotBlank(), onClick = { if (!onSave(name, note)) taken = true }, container = tint) }
                Box(Modifier.weight(1f)) { PillButton(stringResource(R.string.settings_cancel), filled = false, onClick = onDismiss) }
            }
            if (onDelete != null) PillButton(stringResource(R.string.menu_delete), filled = false, onClick = onDelete, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun LegendChip(state: Mastery, count: Int, tint: Color, active: Boolean, onClick: () -> Unit) {
    val c = state.color(tint)
    Row(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (active) c.copy(alpha = 0.2f) else DevxColors.Card.copy(alpha = 0.82f))
            .border(1.dp, if (active) c.copy(alpha = 0.7f) else DevxColors.Outline, RoundedCornerShape(50))
            .pressable(scaleTo = 0.94f, haptic = false, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(c))
        Text(state.label() + " " + count, style = DevxType.Caption.copy(fontSize = 12.sp), color = if (active) DevxColors.TextPrimary else DevxColors.TextSecondary, maxLines = 1)
    }
}
