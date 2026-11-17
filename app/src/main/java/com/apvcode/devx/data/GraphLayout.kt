package com.apvcode.devx.data

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where to put the concepts of a map: the nodes push each other apart and the links pull the nodes they join together (Fruchterman and
 * Reingold), so related concepts end up near each other. The same input always gives the same picture (it starts from a fixed ring).
 * Gives a position for each node, as fractions of a square (0.06..0.94), the whole picture fitted and centred.
 */
object GraphLayout {
    class Point(val x: Float, val y: Float)

    fun layout(count: Int, edges: List<Pair<Int, Int>>, iterations: Int = 260): List<Point> {
        if (count == 0) return emptyList()
        if (count == 1) return listOf(Point(0.5f, 0.5f))
        val x = FloatArray(count); val y = FloatArray(count)
        for (i in 0 until count) {
            // A ring, a little uneven so that no two nodes start on a line through the centre.
            val a = 2.0 * Math.PI * i / count
            val r = 0.3f + 0.08f * ((i * 7) % 5) / 4f
            x[i] = (0.5 + r * cos(a)).toFloat(); y[i] = (0.5 + r * sin(a)).toFloat()
        }
        val k = sqrt(1.0 / count).toFloat() * 0.9f
        var temp = 0.12f
        val dx = FloatArray(count); val dy = FloatArray(count)
        repeat(iterations) {
            dx.fill(0f); dy.fill(0f)
            for (i in 0 until count) for (j in i + 1 until count) {
                var vx = x[i] - x[j]; var vy = y[i] - y[j]
                var d = sqrt(vx * vx + vy * vy)
                if (d < 1e-4f) { vx = 1e-3f * (i + 1); vy = 1e-3f * (j + 1); d = sqrt(vx * vx + vy * vy) }
                val f = k * k / d
                dx[i] += vx / d * f; dy[i] += vy / d * f
                dx[j] -= vx / d * f; dy[j] -= vy / d * f
            }
            for ((a, b) in edges) {
                if (a == b || a !in 0 until count || b !in 0 until count) continue
                val vx = x[a] - x[b]; val vy = y[a] - y[b]
                val d = max(sqrt(vx * vx + vy * vy), 1e-4f)
                val f = d * d / k
                dx[a] -= vx / d * f; dy[a] -= vy / d * f
                dx[b] += vx / d * f; dy[b] += vy / d * f
            }
            for (i in 0 until count) {
                // A light pull to the middle keeps separate groups on the page.
                dx[i] += (0.5f - x[i]) * 0.4f * k; dy[i] += (0.5f - y[i]) * 0.4f * k
                val d = max(sqrt(dx[i] * dx[i] + dy[i] * dy[i]), 1e-6f)
                val step = min(d, temp)
                x[i] += dx[i] / d * step; y[i] += dy[i] / d * step
            }
            temp = max(temp * 0.985f, 0.002f)
        }
        // Fit into the square without stretching, and centre.
        val minX = x.min(); val maxX = x.max(); val minY = y.min(); val maxY = y.max()
        val span = max(max(maxX - minX, maxY - minY), 1e-4f)
        val scale = 0.88f / span
        val offX = 0.5f - (minX + maxX) / 2f * scale
        val offY = 0.5f - (minY + maxY) / 2f * scale
        return List(count) { Point((x[it] * scale + offX).coerceIn(0.06f, 0.94f), (y[it] * scale + offY).coerceIn(0.06f, 0.94f)) }
    }
}
