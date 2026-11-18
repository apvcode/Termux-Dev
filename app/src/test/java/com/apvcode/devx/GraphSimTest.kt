package com.apvcode.devx

import com.apvcode.devx.data.GraphLayout
import com.apvcode.devx.data.GraphSim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class GraphSimTest {
    private fun sim(n: Int, edges: List<Pair<Int, Int>>) = GraphSim(n, edges, GraphLayout.layout(n, edges))
    private fun settle(s: GraphSim, steps: Int = 900) = repeat(steps) { s.step() }
    private fun dist(s: GraphSim, a: Int, b: Int) = hypot(s.x[a] - s.x[b], s.y[a] - s.y[b])

    @Test fun itSettlesAndFallsAsleep() {
        val s = sim(6, listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 4 to 5))
        assertFalse(s.asleep)
        settle(s)
        assertTrue(s.asleep)
    }

    @Test fun linkedNodesEndUpNearerThanStrangers() {
        val s = sim(4, listOf(0 to 1, 2 to 3))
        settle(s)
        assertTrue(dist(s, 0, 1) < dist(s, 0, 2))
        assertTrue(dist(s, 2, 3) < dist(s, 1, 3))
    }

    @Test fun noTwoNodesSitOnTopOfEachOther() {
        val s = sim(12, (1 until 12).map { 0 to it })
        settle(s)
        for (i in 0 until 12) for (j in i + 1 until 12) assertTrue("$i $j", dist(s, i, j) > 20f)
    }

    @Test fun aDraggedNodeStaysWhereItIsHeldAndPullsItsNeighbour() {
        val s = sim(3, listOf(0 to 1, 1 to 2))
        settle(s)
        val before = dist(s, 1, 0)
        s.pinned[0] = true
        s.moveTo(0, s.x[0] + 400f, s.y[0])
        s.reheat(0.6f)
        val held = s.x[0]
        repeat(240) { s.step() }
        assertEquals(held, s.x[0], 0.001f)
        assertTrue(dist(s, 1, 0) < before + 200f) // the neighbour followed instead of staying far behind
        assertFalse(s.asleep) // held: the picture stays alive
        s.pinned[0] = false
        settle(s)
        assertTrue(s.asleep)
    }

    @Test fun busyNodesAreBigger() {
        val s = sim(5, listOf(0 to 1, 0 to 2, 0 to 3, 3 to 4))
        assertTrue(s.radius(0) > s.radius(1))
    }

    @Test fun emptyAndSingleMapsDoNotBreak() {
        sim(0, emptyList()).step()
        val one = sim(1, emptyList()); settle(one)
        assertEquals(0f, one.x[0], 1f)
    }

    @Test fun aGhostNodePassesThroughWithoutPushingOthersAway() {
        val s = sim(3, listOf(0 to 1, 1 to 2))
        settle(s)
        val before = hypot(s.x[2] - s.x[1], s.y[2] - s.y[1])
        s.pinned[0] = true; s.ghost = 0
        s.moveTo(0, s.x[1], s.y[1]) // right on top of node 1
        val x1 = s.x[1]; val y1 = s.y[1]
        repeat(5) { s.step() }
        // with repulsion the two would fly apart at once; as a ghost, 1 only feels its links
        assertTrue(hypot(s.x[1] - x1, s.y[1] - y1) < 40f)
        assertTrue(before > 0f)
    }

    @Test fun nodesThatAlreadyHavePlacesKeepThem() {
        val edges = listOf(0 to 1, 1 to 2)
        val s = GraphSim(3, edges, GraphLayout.layout(3, edges), start = listOf(100f to 50f, -80f to 20f, null), heat = 0.5f)
        assertEquals(100f, s.x[0], 0.001f); assertEquals(50f, s.y[0], 0.001f)
        assertEquals(-80f, s.x[1], 0.001f)
        assertEquals(0.5f, s.alpha, 0.001f)
    }
}
