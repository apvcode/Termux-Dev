package com.apvcode.devx

import com.apvcode.devx.data.Lesson
import com.apvcode.devx.data.Pace
import com.apvcode.devx.data.StudyLog
import com.apvcode.devx.data.StudyPlan
import com.apvcode.devx.data.StudyToday
import com.apvcode.devx.data.Trash
import com.apvcode.devx.data.VoiceDrill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OrganizeTest {
    @get:Rule val tmp = TemporaryFolder()
    private val day = 24L * 60 * 60 * 1000

    @Test fun aChatInTheTrashComesBackAndGoesAfterThirtyDays() {
        val trash = Trash(tmp.newFolder("trash"))
        val now = 1_700_000_000_000L
        val item = trash.putChat("Plan", "{\"id\":\"a\"}", setOf("att1"), now)
        assertEquals(listOf(item.id), trash.list().map { it.id })
        assertEquals("{\"id\":\"a\"}", trash.chat(item.id))
        assertEquals(setOf("att1"), trash.list().single().keep)
        assertTrue(trash.expired(now + 29 * day).isEmpty())
        assertEquals(listOf(item.id), trash.expired(now + 31 * day).map { it.id })
        assertEquals(30, item.daysLeft(now))
        assertEquals(0, item.daysLeft(now + 40 * day))
        trash.remove(item.id)
        assertTrue(trash.list().isEmpty())
        assertNull(trash.chat(item.id))
    }

    @Test fun aProjectInTheTrashKeepsItsChatsAndMemory() {
        val trash = Trash(tmp.newFolder("trash2"))
        val item = trash.putProject("Physics", "{\"id\":\"p1\"}", listOf("{\"id\":\"c1\"}", "{\"id\":\"c2\"}"), mapOf("project_p1.json" to "[1]".toByteArray()), setOf("f1"), 5L)
        assertTrue(item.isProject)
        assertEquals(2, trash.list().single().chats)
        val back = trash.project(item.id)!!
        assertEquals("Physics", back.name)
        assertEquals(listOf("{\"id\":\"c1\"}", "{\"id\":\"c2\"}"), back.chats)
        assertEquals("[1]", back.files["project_p1.json"]!!.decodeToString())
    }

    @Test fun halfWrittenOrOddFoldersAreNeverListed() {
        val dir = tmp.newFolder("trash3")
        java.io.File(dir, "x.tmp").mkdirs()
        java.io.File(dir, "broken").mkdirs()
        assertTrue(Trash(dir).list().isEmpty())
        Trash(dir).remove("../etc")
        assertTrue(dir.exists())
    }

    @Test fun spokenAnswersAreMatchedAndReadAsVerdicts() {
        assertTrue(VoiceDrill.score("the mitochondria produces energy for the cell", "Mitochondria produce energy") >= 0.6)
        assertTrue(VoiceDrill.score("I have no idea", "Mitochondria produce energy") < 0.35)
        assertEquals("good", VoiceDrill.suggest("mitochondria produces energy", "Mitochondria produce energy"))
        assertEquals("again", VoiceDrill.suggest("something else entirely", "Mitochondria produce energy"))
        assertNull(VoiceDrill.suggest("", "anything"))
        assertEquals("good", VoiceDrill.command("yes I knew it"))
        assertEquals("again", VoiceDrill.command("no, wrong"))
        assertEquals("again", VoiceDrill.command("не знал"))
        assertEquals("good", VoiceDrill.command("знал"))
        assertEquals("easy", VoiceDrill.command("это было легко"))
        assertEquals("hard", VoiceDrill.command("hard one"))
        assertNull(VoiceDrill.command("hmm let me think"))
        assertNull(VoiceDrill.command(""))
    }

    @Test fun theTimeALessonIsFinishedIsKeptAndTheBehindPaceIsSaid() {
        val today = 20_000
        val plan = StudyPlan()
        plan.set(listOf("A", "B", "C", "D", "E", "F"))
        // Studying began ten days ago; nothing was finished in the last week.
        plan.log.record(today - 10, 3)
        plan.log.record(today - 1, 1)
        assertTrue(plan.hasHistory(today))
        val pace = Pace.of(today + 5L, 6, today)!!
        assertTrue(pace.behind(plan.doneSince(7, today) / 7.0))
        val text = StudyToday.text(plan, today + 5L, today)
        assertTrue(text, text.contains("BEHIND THE PACE"))

        // Two lessons marked done today count, and survive a save.
        plan.lessons[0].status = Lesson.DONE; plan.lessons[0].doneDay = today
        plan.lessons[1].status = Lesson.DONE; plan.lessons[1].doneDay = today - 2
        assertEquals(2, plan.doneSince(7, today))
        val back = StudyPlan.fromJson(plan.toJson())
        assertEquals(2, back.doneSince(7, today))
        assertEquals(today, back.lessons[0].doneDay)
    }

    @Test fun noPaceIsJudgedBeforeThereIsAHistory() {
        val today = 20_000
        val plan = StudyPlan()
        plan.set(listOf("A", "B"))
        plan.log.record(today, 1)
        assertFalse(plan.hasHistory(today))
        assertFalse(StudyToday.text(plan, today + 3L, today).contains("BEHIND"))
    }

    @Test fun lessonsFinishedBeforeTheirDayWasKeptAreNotHeldAgainstThePace() {
        val today = 20_000
        val plan = StudyPlan()
        plan.set(listOf("A", "B", "C"))
        plan.lessons[0].status = Lesson.DONE // no day: finished before it was kept
        plan.log.record(today - 8, 2)
        assertFalse(plan.hasHistory(today))
        plan.lessons[1].status = Lesson.DONE; plan.lessons[1].doneDay = today - 1
        assertTrue(plan.hasHistory(today))
    }

    @Test fun aCopyOfAPlanStartsAgain() {
        val plan = StudyPlan()
        plan.set(listOf("A", "B"))
        plan.lessons[0].status = Lesson.DONE
        plan.addCards(listOf("q" to "a"), 1000L)
        plan.cards.single().review("good", 2000L)
        plan.log.record(StudyLog.today(), 4)
        plan.addExam("t", 1, 2, listOf("x"), 5L)
        val copy = plan.template(9_000L)
        assertEquals(listOf("A", "B"), copy.lessons.map { it.title })
        assertTrue(copy.lessons.all { it.status == Lesson.TODO })
        assertEquals(0, copy.cards.single().reps)
        assertTrue(copy.log.isEmpty)
        assertTrue(copy.exams.isEmpty())
    }
}
