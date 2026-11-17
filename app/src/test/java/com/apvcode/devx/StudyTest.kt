package com.apvcode.devx

import com.apvcode.devx.data.CardDraft
import com.apvcode.devx.data.Flashcard
import com.apvcode.devx.data.Pace
import com.apvcode.devx.data.StudyPlan
import com.apvcode.devx.data.StudyToday
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyTest {
    private val now = 1_000_000_000L

    @Test fun forgettingACardIsCountedAndKept() {
        val plan = StudyPlan()
        plan.addDrafts(listOf(CardDraft("2+2?", "4", "arithmetic")), now)
        val c = plan.cards.single()
        c.review("again", now)
        c.review("again", now)
        assertEquals(2, c.lapses)
        val back = StudyPlan.fromJson(plan.toJson()).cards.single()
        assertEquals("arithmetic", back.topic)
        assertEquals(2, back.lapses)
    }

    @Test fun mistakesInAnExamBecomeCardsDueNow() {
        val plan = StudyPlan()
        plan.addDrafts(listOf(CardDraft("Known?", "yes", "a")), now)
        val known = plan.cards.single()
        assertFalse(known.isDue(now))
        val made = plan.recordMisses(listOf(CardDraft("Known?", "yes", "a"), CardDraft("New?", "no", "b")), now)
        assertEquals(1, made)
        assertEquals(2, plan.cards.size)
        assertTrue(plan.cards.all { it.isDue(now) })
        assertEquals(1, known.lapses)
    }

    @Test fun weakSpotsAreOrderedByHowOftenTheyFailed() {
        val plan = StudyPlan()
        plan.addDrafts(listOf(CardDraft("q1", "a", "Loops"), CardDraft("q2", "a", "Types"), CardDraft("q3", "a", "")), now)
        plan.cards[0].lapses = 2
        plan.cards[1].lapses = 1
        plan.cards[2].lapses = 9 // no topic: not a spot
        val spots = plan.weakSpots()
        assertEquals(listOf("Loops", "Types"), spots.map { it.topic })
        // A topic that went badly in an exam moves up.
        plan.addExam("Basics", 5, 10, listOf("Types", "Types", "Recursion"), now)
        assertEquals("Types", plan.weakSpots().first().topic)
        assertTrue(plan.weakSpots().any { it.topic == "Recursion" })
    }

    @Test fun anExamSurvivesTheJson() {
        val plan = StudyPlan()
        plan.addExam("Midterm", 7, 10, listOf("Loops"), now)
        val e = StudyPlan.fromJson(plan.toJson()).exams.single()
        assertEquals("Midterm", e.title)
        assertEquals(70, e.percent)
        assertEquals(listOf("Loops"), e.weak)
    }

    @Test fun onlyTheLastTwentyExamsAreKept() {
        val plan = StudyPlan()
        repeat(25) { plan.addExam("e$it", 1, 2, emptyList(), now + it) }
        assertEquals(20, plan.exams.size)
        assertEquals("e24", plan.exams.last().title)
    }

    @Test fun paceFollowsTheDeadline() {
        assertNull(Pace.of(0L, 5, today = 100))
        val p = Pace.of(110L, 5, today = 100)!!
        assertEquals(10, p.daysLeft)
        assertFalse(p.overdue)
        assertEquals(5.0 / 11, p.perDay, 1e-9)
        assertTrue(Pace.of(99L, 5, today = 100)!!.overdue)
        assertEquals(0.0, Pace.of(110L, 0, today = 100)!!.perDay, 0.0)
    }

    @Test fun todayTellsTheAssistantWhereThingsStand() {
        val plan = StudyPlan()
        plan.set(listOf("One", "Two", "Three"))
        plan.addDrafts(listOf(CardDraft("q", "a", "T")), now, dueAt = now)
        plan.cards[0].lapses = 2
        plan.addExam("Quiz", 3, 4, listOf("T"), now)
        val text = StudyToday.text(plan, deadline = 110L, today = 100, now = now)
        assertTrue(text, "Deadline" in text && "in 10 day(s)" in text)
        assertTrue(text, "3 lesson(s) left" in text)
        assertTrue(text, "Cards due now: 1 of 1" in text)
        assertTrue(text, "Weak spots: T" in text)
        assertTrue(text, "Last exam: Quiz, 3 of 4 (75%)" in text)
        assertTrue("No deadline" in StudyToday.text(plan, deadline = 0L, today = 100, now = now))
    }

    @Test fun reviewPreviewDoesNotChangeTheCard() {
        val c = Flashcard("c", "q", "a", due = 0, topic = "t")
        c.preview("again", now)
        assertEquals(0, c.lapses)
    }
}
