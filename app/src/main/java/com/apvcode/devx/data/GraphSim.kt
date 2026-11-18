package com.apvcode.devx.data

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A live force simulation of a concept map, in the manner of Obsidian's graph: the nodes push each other apart, the links are springs,
 * a faint pull keeps everything near the middle. It is stepped on every frame, so a node that is dragged takes its neighbours along and
 * the whole thing settles again like jelly; once it is quiet ([asleep]) it costs nothing until it is touched.
 * Positions are in world units (about one dp at zoom 1), the middle at 0,0.
 */
class GraphSim(
    val count: Int,
    edges: List<Pair<Int, Int>>,
    seed: List<GraphLayout.Point>,
    /** How wide the finished picture is meant to be, in world units. */
    spread: Float = 520f,
    /** Where nodes already were (world units), by index: kept as they are, so that a map that gains a concept does not start over. */
    start: List<Pair<Float, Float>?> = emptyList(),
    /** How hot it starts: 1 for a new picture, less for one that only changed a little. */
    heat: Float = 1f,
) {
    val x = FloatArray(count)
    val y = FloatArray(count)
    private val vx = FloatArray(count)
    private val vy = FloatArray(count)
    val pinned = BooleanArray(count)
    val degree = IntArray(count)
    private val links: List<Pair<Int, Int>> = edges.filter { (a, b) -> a != b && a in 0 until count && b in 0 until count }.distinctBy { (a, b) -> if (a < b) a to b else b to a }

    /** The heat: how hard the forces work. It cools by itself and is raised again by [reheat]. */
    var alpha = heat
        private set

    /** A node being dragged: it passes through the others (they do not run away from it), so that it can be dropped onto one. */
    var ghost = -1

    val asleep: Boolean get() = alpha <= ALPHA_MIN && pinned.none { it }

    init {
        links.forEach { (a, b) -> degree[a]++; degree[b]++ }
        // Start close together, in the order of the layout, so that the picture blooms outwards.
        for (i in 0 until count) {
            val known = start.getOrNull(i)
            val p = seed.getOrNull(i)
            x[i] = known?.first ?: (((p?.x ?: 0.5f) - 0.5f) * spread * 0.3f)
            y[i] = known?.second ?: (((p?.y ?: 0.5f) - 0.5f) * spread * 0.3f)
        }
    }

    /** The radius a node is drawn with: the more it is connected, the bigger it is. */
    fun radius(i: Int): Float = 6f + 2.3f * sqrt(degree[i].toFloat())

    fun reheat(to: Float = 0.5f) { alpha = max(alpha, to) }

    fun moveTo(i: Int, px: Float, py: Float) { x[i] = px; y[i] = py; vx[i] = 0f; vy[i] = 0f }

    /** One step of 1/60 s. */
    fun step() {
        if (count == 0) return
        val a = alpha
        if (a > ALPHA_MIN) {
            for (i in 0 until count) for (j in i + 1 until count) {
                if (i == ghost || j == ghost) continue
                var dx = x[i] - x[j]; var dy = y[i] - y[j]
                var d2 = dx * dx + dy * dy
                if (d2 < 1f) { dx = 0.5f * (i - j); dy = 0.5f; d2 = dx * dx + dy * dy }
                val d = sqrt(d2)
                // Repulsion fades with distance; close neighbours also never overlap (a node's radius plus some air).
                var f = REPEL * a / max(d2, 900f)
                val gap = radius(i) + radius(j) + 16f
                if (d < gap) f += (gap - d) * 0.06f
                val fx = dx / d * f; val fy = dy / d * f
                vx[i] += fx; vy[i] += fy; vx[j] -= fx; vy[j] -= fy
            }
            for ((p, q) in links) {
                val dx = x[q] - x[p]; val dy = y[q] - y[p]
                val d = max(sqrt(dx * dx + dy * dy), 1f)
                // Spring: busy nodes keep their links longer, so a hub has room around it.
                val rest = REST + 7f * sqrt((degree[p] + degree[q]).toFloat())
                val f = (d - rest) * SPRING * a
                // The lighter end moves more, as in d3.
                val bias = degree[p].toFloat() / max(1, degree[p] + degree[q])
                vx[q] -= dx / d * f * bias; vy[q] -= dy / d * f * bias
                vx[p] += dx / d * f * (1f - bias); vy[p] += dy / d * f * (1f - bias)
            }
            for (i in 0 until count) { vx[i] -= x[i] * CENTER * a; vy[i] -= y[i] * CENTER * a }
        }
        for (i in 0 until count) {
            if (pinned[i]) { vx[i] = 0f; vy[i] = 0f; continue }
            vx[i] *= DAMP; vy[i] *= DAMP
            x[i] += min(max(vx[i], -MAX_SPEED), MAX_SPEED); y[i] += min(max(vy[i], -MAX_SPEED), MAX_SPEED)
        }
        alpha = if (pinned.any { it }) max(alpha * 0.995f, HELD) else if (alpha > ALPHA_MIN) alpha * COOL else 0f
    }

    /** The box around all the nodes: minX, minY, maxX, maxY. */
    fun bounds(): FloatArray {
        if (count == 0) return floatArrayOf(-1f, -1f, 1f, 1f)
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (i in 0 until count) { val r = radius(i); x0 = min(x0, x[i] - r); y0 = min(y0, y[i] - r); x1 = max(x1, x[i] + r); y1 = max(y1, y[i] + r) }
        return floatArrayOf(x0, y0, x1, y1)
    }

    /** The nodes joined to [i] by a link. */
    fun neighbours(i: Int): Set<Int> = links.mapNotNullTo(HashSet()) { (a, b) -> if (a == i) b else if (b == i) a else null }

    companion object {
        const val ALPHA_MIN = 0.004f
        private const val COOL = 0.9855f
        private const val HELD = 0.18f
        private const val REPEL = 52_000f
        private const val REST = 78f
        private const val SPRING = 0.055f
        private const val CENTER = 0.02f
        private const val DAMP = 0.78f
        private const val MAX_SPEED = 38f
    }
}
