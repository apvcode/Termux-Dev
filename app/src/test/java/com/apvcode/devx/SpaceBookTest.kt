package com.apvcode.devx

import com.apvcode.devx.data.Attachment
import com.apvcode.devx.data.AttachmentKind
import com.apvcode.devx.data.Chat
import com.apvcode.devx.data.Flashcard
import com.apvcode.devx.data.Lesson
import com.apvcode.devx.data.MemoryStore
import com.apvcode.devx.data.Msg
import com.apvcode.devx.data.ProjectInfo
import com.apvcode.devx.model.Role
import com.apvcode.devx.data.SpaceBook
import com.apvcode.devx.data.SpaceRules
import com.apvcode.devx.data.StudyPlan
import com.apvcode.devx.data.ToolActivity
import com.apvcode.devx.data.ToolKind
import com.apvcode.devx.data.addContinuation
import com.apvcode.devx.data.branchPosition
import com.apvcode.devx.data.continuationNote
import com.apvcode.devx.data.WireMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpaceBookTest {
    @get:Rule val tmp = TemporaryFolder()

    private val names = ArrayList<String>()
    private val infos = LinkedHashMap<String, ProjectInfo>()
    private val chats = ArrayList<Chat>()
    private val book by lazy { SpaceBook(names, infos, chats) }
    private var next = 0

    private fun info(id: String, plan: StudyPlan = StudyPlan()) = ProjectInfo(id, memory = MemoryStore(tmp.root, "p_$id.json", "p_${id}_s.json"), plan = plan)
    private fun make(name: String) = book.create(name, "id${next++}") { info(it) }
    private fun chat(id: String, project: String?, vararg said: String) = Chat(id, "t$id", project, 1L).also { c ->
        said.forEachIndexed { i, t -> c.messages += Msg("$id-$i", null, Role.User, t, 1L) }
        chats += c
    }

    @Test fun aNameThatIsEmptyTakenOrReservedIsRefused() {
        assertTrue(make("Physics"))
        assertFalse(make("Physics"))
        assertFalse(make("   "))
        assertFalse(make("__format"))
        assertTrue(make("  Maths  "))
        assertEquals(listOf("Physics", "Maths"), names)
    }

    @Test fun renamingMovesTheChatsAndTheMentionsAndKeepsTheDetails() {
        make("Physics"); make("Maths")
        val inside = chat("c1", "Physics", "hello")
        val outside = chat("c2", "Maths", "see @Physics")
        outside.messages.single().mentions = listOf("Physics", "Maths")
        val before = infos["Physics"]!!
        val touched = book.rename("Physics", " Mechanics ")!!
        assertEquals(setOf("c1", "c2"), touched.map { it.id }.toSet())
        assertEquals("Mechanics", inside.project)
        assertEquals(listOf("Mechanics", "Maths"), outside.messages.single().mentions)
        assertEquals(listOf("Mechanics", "Maths"), names)
        assertTrue(before === infos["Mechanics"])
        assertNull(infos["Physics"])
    }

    @Test fun renamingIntoATakenNameChangesNothing() {
        make("Physics"); make("Maths")
        val c = chat("c1", "Physics")
        assertNull(book.rename("Physics", "Maths"))
        assertNull(book.rename("Physics", "Physics"))
        assertNull(book.rename("Physics", ""))
        assertNull(book.rename("Nothing", "Else"))
        assertEquals("Physics", c.project)
        assertEquals(listOf("Physics", "Maths"), names)
        assertEquals(2, infos.size)
    }

    @Test fun aCopyStartsAgainWithTheSourcesAndLessonsButNotTheChatsOrTheDays() {
        make("Spanish")
        val src = infos["Spanish"]!!
        src.goal = "pass B1"; src.instructions = "be strict"; src.description = "course"
        src.sources += Attachment("f1", AttachmentKind.Text, "notes.txt", "text/plain", "f1.txt", 10L)
        src.plan.lessons += Lesson("l1", "Verbs").also { it.status = Lesson.DONE }
        src.plan.cards += Flashcard("c1", "hola", "hello", 5L)
        chat("c1", "Spanish", "x")
        val copy = book.duplicate("Spanish", "Spanish copy", "new1") { id, plan -> info(id, plan) }!!
        assertEquals("Spanish copy", copy)
        val made = infos[copy]!!
        assertEquals("pass B1", made.goal); assertEquals("be strict", made.instructions)
        assertEquals(listOf("f1"), made.sources.map { it.id })
        assertEquals(listOf("Verbs"), made.plan.lessons.map { it.title })
        assertEquals(Lesson.TODO, made.plan.lessons.single().status)
        assertEquals(1, made.plan.cards.size)
        assertEquals(1, chats.size)
        assertEquals("new1", made.id)
        // a second copy gets a free name
        assertEquals("Spanish copy 2", book.duplicate("Spanish", "Spanish copy", "new2") { id, plan -> info(id, plan) })
        assertNull(book.duplicate("Nothing", "x", "y") { id, plan -> info(id, plan) })
    }

    @Test fun takingAProjectOutTakesItsChatsAndPuttingItBackWorks() {
        make("Physics"); make("Maths")
        chat("c1", "Physics"); chat("c2", "Physics"); chat("c3", "Maths")
        val taken = book.take("Physics")!!
        assertEquals(listOf("c1", "c2"), taken.chats.map { it.id })
        assertEquals(listOf("c3"), chats.map { it.id })
        assertEquals(listOf("Maths"), names)
        assertNull(book.take("Physics"))
        val now = book.restore("Physics", taken.info, taken.chats)
        assertEquals("Physics", now)
        assertEquals(3, chats.size)
        assertTrue(chats.filter { it.id != "c3" }.all { it.project == "Physics" })
        assertTrue(infos["Physics"] === taken.info)
    }

    @Test fun puttingBackIntoATakenNameGetsANumberAndNothingIsDoubled() {
        make("Physics")
        val other = info("zz")
        val c = chat("c1", "Physics")
        val now = book.restore("Physics", other, listOf(c, Chat("c9", "n", null, 1L)))
        assertEquals("Physics 2", now)
        assertEquals(2, chats.size) // c1 was already there, c9 was added
        assertEquals("Physics", c.project)
        assertEquals("Physics 2", chats.single { it.id == "c9" }.project)
    }

    @Test fun aChatComesBackToThePlainListWhenItsProjectIsGone() {
        make("Maths")
        val gone = Chat("x", "t", "Deleted", 1L)
        assertTrue(book.restore(gone))
        assertNull(gone.project)
        assertFalse(book.restore(gone))
        val fine = Chat("y", "t", "Maths", 1L)
        assertTrue(book.restore(fine))
        assertEquals("Maths", fine.project)
    }

    @Test fun aChatOfAMissingProjectGetsItBackWithDefaults() {
        make("Maths")
        chat("c1", "Lost"); chat("c2", "Lost"); chat("c3", "Maths"); chat("c4", null)
        var n = 0
        assertEquals(1, book.adoptOrphans({ "n${n++}" }) { info(it) })
        assertEquals(listOf("Maths", "Lost"), names)
        assertEquals("n0", infos["Lost"]!!.id)
        assertEquals(0, book.adoptOrphans({ "n${n++}" }) { info(it) })
    }

    @Test fun aRenameThatWasCutShortIsHealedFromTheProjectId() {
        make("Mechanics")
        val id = infos["Mechanics"]!!.id
        val stale = chat("c1", "Physics").also { it.projectId = id }
        val fine = chat("c2", "Mechanics").also { it.projectId = id }
        val none = chat("c3", "Physics")
        val healed = book.healRenamed()
        assertEquals(listOf("c1"), healed.map { it.id })
        assertEquals("Mechanics", stale.project)
        assertEquals("Mechanics", fine.project)
        assertEquals("Physics", none.project)
    }

    @Test fun theFilesAChatLeavesAreFoundInItsMessages() {
        val c = Chat("c", "t", null, 1L)
        c.messages += Msg("m1", null, Role.User, "look", 1L, attachments = listOf(Attachment("a1", AttachmentKind.Image, "p.png", "image/png", "a1.png", 3L)))
        c.messages += Msg("m2", "m1", Role.Assistant, "Made it:\n```devx-file\nart7\n```\nand\n```devx-file\nart8\n```", 2L)
        assertEquals(setOf("a1", "art7", "art8"), SpaceBook.filesOf(c))
        assertEquals(listOf("art7", "art8"), SpaceBook.madeFiles(c))
    }
}

class TurnsTest {
    @Test fun aNameThatIsTakenGetsANumberBeforeItsExtension() {
        assertEquals("a.txt", SpaceRules.numberedName("a.txt", emptyList()))
        assertEquals("a (2).txt", SpaceRules.numberedName("a.txt", listOf("A.TXT")))
        assertEquals("a (3).txt", SpaceRules.numberedName("a.txt", listOf("a.txt", "a (2).txt")))
        assertEquals("notes (2)", SpaceRules.numberedName("notes", listOf("notes")))
        assertEquals(".env (2)", SpaceRules.numberedName(".env", listOf(".env")))
    }

    @Test fun aMessageKnowsItsPlaceAmongItsAlternatives() {
        val c = Chat("c", "t", null, 1L)
        val q = Msg("q", null, Role.User, "hi", 1L)
        val a1 = Msg("a1", "q", Role.Assistant, "one", 2L)
        val a2 = Msg("a2", "q", Role.Assistant, "two", 3L)
        c.messages += listOf(q, a1, a2)
        assertEquals(1 to 2, c.branchPosition(a1))
        assertEquals(2 to 2, c.branchPosition(a2))
        assertNull(c.branchPosition(q))
    }

    @Test fun aBrokenAnswerIsContinuedFromItsSteps() {
        val done = Msg("m", "q", Role.Assistant, "partial", 1L)
        assertTrue(continuationNote(done).startsWith("(The connection broke"))
        assertFalse(continuationNote(done).contains("steps you had"))
        done.steps += ToolActivity(ToolKind.Search, "kotlin flows", "3 sites")
        assertTrue(continuationNote(done).contains("- search: kotlin flows (3 sites)"))
    }

    @Test fun theContinuationJoinsTheLastUserTurnOrAddsOne() {
        val list = arrayListOf(WireMessage("user", "question"))
        list.addContinuation("go on")
        assertEquals(1, list.size)
        assertEquals("question\n\ngo on", list[0].text)
        val after = arrayListOf(WireMessage("user", "q"), WireMessage("assistant", "half"))
        after.addContinuation("go on")
        assertEquals(listOf("user", "assistant", "user"), after.map { it.role })
        assertEquals("go on", after.last().text)
    }
}

class InsideSearchTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun theSearchFindsTheNotesOfTheMemoryOfTheWholeAppToo() {
        val info = ProjectInfo("p1", memory = MemoryStore(tmp.root, "a.json", "a_s.json"), plan = StudyPlan().also { it.cards += Flashcard("c", "capital of Spain", "Madrid", 1L) })
        val general = listOf(
            com.apvcode.devx.data.MemoryNote("1", "Lives in Madrid", 1L, 1L),
            com.apvcode.devx.data.MemoryNote("2", "Madrid trip is tomorrow", 1L, 1L, expiresAt = 5L),
        )
        val hits = com.apvcode.devx.data.InsideFinder.run(mapOf("Spanish" to info), "madrid", general = general)
        assertEquals(listOf(com.apvcode.devx.data.InsideFinder.GENERAL, "Spanish"), hits.map { it.space })
        assertEquals(com.apvcode.devx.data.InsideFinder.NOTE, hits[0].kind)
        assertEquals(com.apvcode.devx.data.InsideFinder.CARD, hits[1].kind)
        assertTrue(com.apvcode.devx.data.InsideFinder.run(emptyMap(), "m", general = general).isEmpty())
    }
}
