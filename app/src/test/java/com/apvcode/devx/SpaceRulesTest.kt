package com.apvcode.devx

import com.apvcode.devx.data.ProjectStore
import com.apvcode.devx.data.SpaceRules
import com.apvcode.devx.data.SpaceRules.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpaceRulesTest {
    private val names = listOf("Physics", "Go")

    @Test fun aNameThatIsEmptyTakenOrReservedIsRefused() {
        assertEquals(Problem.Empty, SpaceRules.problem("  ", null, names))
        assertEquals(Problem.Taken, SpaceRules.problem("Physics", null, names))
        assertEquals(Problem.Taken, SpaceRules.problem("Go", "Physics", names)) // renaming into another one's name
        assertEquals(Problem.Same, SpaceRules.problem("Physics", "Physics", names))
        assertEquals(Problem.Reserved, SpaceRules.problem(ProjectStore.VERSION_KEY, null, names))
        assertNull(SpaceRules.problem("Chemistry", null, names))
        assertNull(SpaceRules.problem("Physics 2", "Physics", names))
    }

    @Test fun aTakenNameGetsTheFirstFreeNumber() {
        assertEquals("New", SpaceRules.uniqueName("New") { it in names })
        assertEquals("Go 2", SpaceRules.uniqueName("Go") { it in names })
        assertEquals("Go 3", SpaceRules.uniqueName("Go") { it in names + "Go 2" })
    }

    @Test fun aChatCutOffByAnInterruptedRenameFindsItsProjectByItsId() {
        val byId = mapOf("p1" to "Physics II", "p2" to "Go")
        assertEquals("Physics II", SpaceRules.healedProject("Physics", "p1", byId))
        assertNull(SpaceRules.healedProject("Physics II", "p1", byId)) // already right
        assertNull(SpaceRules.healedProject("Physics", null, byId)) // an old chat that does not know its project's id
        assertNull(SpaceRules.healedProject("Physics", "gone", byId)) // the project was deleted: the chat is left to be adopted
    }
}
