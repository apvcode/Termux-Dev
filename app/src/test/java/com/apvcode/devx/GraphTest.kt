package com.apvcode.devx

import com.apvcode.devx.data.GraphLayout
import com.apvcode.devx.data.StudyPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class GraphTest {
    @Test fun conceptsAreMergedByNameAndLinksSkipRepeatsAndSelfLinks() {
        val p = StudyPlan()
        p.upsertConcept("Cell", "the unit of life")
        p.upsertConcept("cell", "the smallest living unit")
        assertEquals(1, p.concepts.size)
        assertEquals("the smallest living unit", p.concepts[0].note)
        assertTrue(p.link("Cell", "Nucleus", "has"))
        assertEquals(2, p.concepts.size) // the nucleus was added by the link
        assertFalse(p.link("nucleus", "cell", "HAS")) // the same, the other way round
        assertFalse(p.link("Cell", "cell", "is"))
        assertTrue(p.link("Cell", "Nucleus", "contains DNA in"))
        assertEquals(2, p.links.size)
    }

    @Test fun theMapIsKeptAndRemovedWithItsLinks() {
        val p = StudyPlan()
        p.link("A", "B", "to"); p.link("B", "C", "to")
        val back = StudyPlan.fromJson(p.toJson())
        assertEquals(3, back.concepts.size); assertEquals(2, back.links.size)
        assertEquals(1, back.removeConcepts(listOf("b")))
        assertEquals(2, back.concepts.size); assertEquals(0, back.links.size)
        assertEquals(3, back.template().let { p.template().concepts.size })
    }

    @Test fun theMapIsCapped() {
        val p = StudyPlan()
        repeat(80) { p.upsertConcept("c$it") }
        assertEquals(StudyPlan.MAX_CONCEPTS, p.concepts.size)
    }

    @Test fun aLayoutIsInsideTheSquareAndSameEveryTime() {
        val edges = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 4 to 5)
        val a = GraphLayout.layout(6, edges)
        val b = GraphLayout.layout(6, edges)
        assertEquals(6, a.size)
        a.forEachIndexed { i, p ->
            assertTrue(p.x in 0.05f..0.95f && p.y in 0.05f..0.95f && !p.x.isNaN() && !p.y.isNaN())
            assertEquals(p.x, b[i].x, 0f); assertEquals(p.y, b[i].y, 0f)
        }
        // No two nodes on top of each other.
        for (i in a.indices) for (j in i + 1 until a.size) assertTrue(hypot(a[i].x - a[j].x, a[i].y - a[j].y) > 0.03f)
    }

    @Test fun relatedConceptsEndUpNearerThanUnrelatedOnes() {
        // Two groups of four, joined inside each group only.
        val edges = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0, 4 to 5, 5 to 6, 6 to 7, 7 to 4)
        val p = GraphLayout.layout(8, edges)
        fun d(i: Int, j: Int) = hypot(p[i].x - p[j].x, p[i].y - p[j].y)
        val inside = listOf(0 to 1, 1 to 2, 4 to 5, 5 to 6).map { d(it.first, it.second) }.average()
        val across = listOf(0 to 4, 1 to 5, 2 to 6, 3 to 7).map { d(it.first, it.second) }.average()
        assertTrue("inside $inside across $across", inside < across)
    }

    @Test fun tinyGraphs() {
        assertEquals(0, GraphLayout.layout(0, emptyList()).size)
        assertNotNull(GraphLayout.layout(1, emptyList()).single())
        assertEquals(2, GraphLayout.layout(2, listOf(0 to 1, 5 to 9)).size)
    }

    @Test fun byHandRenameNeverDuplicatesAndNoteIsKept() {
        val p = StudyPlan()
        val a = p.addConcept("Cell", "the unit")!!
        val b = p.addConcept("Nucleus")!!
        assertEquals(null, p.addConcept("cell")) // taken
        assertFalse(p.renameConcept(b.id, " CELL ")) // taken by the other one
        assertTrue(p.renameConcept(b.id, "Core"))
        assertEquals("Core", b.name)
        p.setConceptNote(a.id, "  smallest living unit ")
        assertEquals("smallest living unit", a.note)
    }

    @Test fun byHandLinksByIdAndDeletingAConceptTakesItsLinks() {
        val p = StudyPlan()
        val a = p.addConcept("A")!!; val b = p.addConcept("B")!!; val c = p.addConcept("C")!!
        assertTrue(p.linkIds(a.id, b.id, "needs"))
        assertFalse(p.linkIds(b.id, a.id, "NEEDS")) // the same link the other way round
        assertTrue(p.linkIds(a.id, b.id, "causes")) // another relation between the same two
        assertFalse(p.linkIds(a.id, a.id))
        assertFalse(p.linkIds(a.id, "nope"))
        assertTrue(p.linkIds(b.id, c.id))
        assertEquals(3, p.links.size)
        p.removeLink(p.links.first())
        assertEquals(2, p.links.size)
        p.removeConceptById(b.id)
        assertEquals(2, p.concepts.size)
        assertEquals(0, p.links.size) // both remaining links touched B
    }
}
