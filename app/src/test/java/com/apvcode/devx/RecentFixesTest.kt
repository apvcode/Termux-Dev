package com.apvcode.devx

import com.apvcode.devx.data.ArtifactStore
import com.apvcode.devx.data.SearchApi
import com.apvcode.devx.data.SearchText
import com.apvcode.devx.data.SystemPrompt
import com.apvcode.devx.ui.chat.UiNode
import com.apvcode.devx.ui.chat.UiParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The places that broke before: cards, searching, file names, the prompt. */
class RecentFixesTest {
    // ---- cards ----

    private fun fieldsOf(node: UiNode?): List<UiNode> = when (node) {
        is UiNode.Card -> node.children.flatMap { fieldsOf(it) }
        is UiNode.Section -> node.children.flatMap { fieldsOf(it) }
        null -> emptyList()
        else -> listOf(node)
    }

    @Test fun cardKeepsLabelHintAndExample() {
        val card = UiParser.parse("""{"type":"card","children":[{"type":"input","id":"a","label":"When do you sleep?","hint":"The real time","placeholder":"e.g. 1 am","required":true}]}""")
        val input = fieldsOf(card).single() as UiNode.Input
        assertEquals("When do you sleep?", input.label)
        assertEquals("The real time", input.hint)
        assertEquals("e.g. 1 am", input.placeholder)
        assertTrue(input.required)
    }

    @Test fun fieldWithoutIdIsNotDropped() {
        val card = UiParser.parse("""{"type":"card","children":[{"type":"input","label":"Name"},{"type":"choice","label":"Pick","options":["a","b"]}]}""")
        assertEquals(2, fieldsOf(card).size)
    }

    @Test fun otherNamesForTheSameThingAreUnderstood() {
        val card = UiParser.parse(
            """{"type":"card","children":[{"type":"textarea","id":"t","question":"Tell me"},{"type":"checkboxes","id":"c","options":["x","y"]},{"type":"radio","id":"r","options":[{"label":"One"},{"value":"Two"}]}]}""",
        )
        val f = fieldsOf(card)
        assertTrue((f[0] as UiNode.Input).multiline)
        assertEquals("Tell me", (f[0] as UiNode.Input).label)
        assertTrue((f[1] as UiNode.Choice).multiple)
        assertEquals(listOf("One", "Two"), (f[2] as UiNode.Choice).options)
    }

    @Test fun sliderAndToggleAreRead() {
        val card = UiParser.parse("""{"type":"card","children":[{"type":"slider","id":"s","label":"Hours","min":3,"max":12,"step":0.5,"unit":"h","value":7},{"type":"toggle","id":"t","label":"Sport?","value":true}]}""")
        val f = fieldsOf(card)
        val slider = f[0] as UiNode.Slider
        assertEquals(3f, slider.min, 0f)
        assertEquals(7f, slider.value, 0f)
        assertEquals("h", slider.unit)
        assertTrue((f[1] as UiNode.Toggle).value)
    }

    @Test fun brokenJsonGivesNothing() {
        assertNull(UiParser.parse("{not json"))
    }

    // ---- searching ----

    @Test fun queryIsTidied() {
        assertEquals("500k 500,000 Grok context window xAI", SearchText.tidy("\"500k\" OR \"500,000\" Grok context window xAI"))
        assertEquals(12, SearchText.tidy("a b c d e f g h i j k l m n o p").split(" ").size)
    }

    @Test fun termsKeepNumbersAndDropFiller() {
        val terms = SearchText.terms("why Grok 4.7 context window")
        assertTrue("grok" in terms)
        assertTrue("4.7" in terms)
        assertFalse("why" in terms)
    }

    @Test fun bestPassageFindsTheTalkingStretch() {
        val page = "Cookies and privacy settings are managed here for the whole site.\n" +
            "Home page navigation and many other unrelated words are written in this line.\n" +
            "The Grok 4 context window is 256000 tokens according to the documentation of the model.\n" +
            "Another line about something else entirely that has nothing to do with it at all."
        val passage = SearchText.bestPassage(page, SearchText.terms("Grok context window"))
        assertNotNull(passage)
        assertTrue(passage!!.contains("256000"))
        assertNull(SearchText.bestPassage("A line long enough but about nothing in particular.", SearchText.terms("Grok context window")))
    }

    @Test fun searchKeysAreToldApart() {
        assertEquals(SearchApi.Kind.Tavily, SearchApi.kindOf("tvly-abc"))
        assertEquals(SearchApi.Kind.Brave, SearchApi.kindOf("BSAabc"))
        assertNull(SearchApi.kindOf("sk-abc"))
    }

    @Test fun searchAnswersAreParsed() {
        val tavily = SearchApi.parseTavily("""{"results":[{"title":"T","url":"https://a.b","content":"the   matching  passage"}]}""")
        assertEquals("https://a.b", tavily.single().url)
        assertEquals("the matching passage", tavily.single().snippet)
        val brave = SearchApi.parseBrave("""{"web":{"results":[{"title":"B","url":"https://c.d","description":"a <strong>bold</strong> hit"}]}}""")
        assertEquals("a bold hit", brave.single().snippet)
        assertTrue(SearchApi.parseBrave("garbage").isEmpty())
    }

    // ---- file names ----

    @Test fun nameKeepsTheKindOfTheFile() {
        assertEquals("Resume.pdf", ArtifactStore.withExtension("Resume", "out.pdf"))
        assertEquals("Resume.pdf", ArtifactStore.withExtension("Resume.pdf", "out.pdf"))
        assertEquals("Report v2 final.docx", ArtifactStore.withExtension("Report v2 final", "x.docx"))
        assertEquals("notes", ArtifactStore.withExtension("notes", "noextension"))
    }

    // ---- the prompt ----

    @Test fun guidesAsToolMakeThePromptShorter() {
        val full = SystemPrompt.base(false)
        val short = SystemPrompt.base(true)
        assertTrue(short.length < full.length - 8_000)
        assertTrue(short.contains("guide"))
        assertTrue(full.contains("devx-card"))
        assertTrue(SystemPrompt.guide("cards").contains("\"label\""))
        assertTrue(SystemPrompt.guide("blocks").contains("devx-map"))
        assertTrue(SystemPrompt.guide("nonsense").startsWith("Unknown topic"))
    }

    @Test
    fun flashcardsComeBackLessOftenTheBetterTheyAreKnown() {
        val day = com.apvcode.devx.data.Flashcard.DAY
        val c = com.apvcode.devx.data.Flashcard("c1", "q", "a", due = 0)
        assertTrue(c.review("good", now = 0)); assertEquals(1, c.interval); assertEquals(day, c.due)
        assertTrue(c.review("good", now = day)); assertEquals(3, c.interval)
        assertTrue(c.review("good", now = 4 * day)); assertTrue(c.interval >= 7)
        val long = c.interval
        assertTrue(c.review("again", now = 100 * day)); assertEquals(0, c.interval); assertEquals(100 * day + 600_000L, c.due)
        assertTrue(c.review("good", now = 100 * day)); assertEquals(1, c.interval)
        assertTrue(long > 3)
        assertFalse(c.review("banana"))
    }

    @Test
    fun theDeckSkipsTheSameQuestionAndShowsWhatIsDue() {
        val plan = com.apvcode.devx.data.StudyPlan()
        assertEquals(2, plan.addCards(listOf("What is a ViewModel?" to "Holds UI state", "What is a Flow?" to "A cold stream", "what is a viewmodel?" to "again"), now = 1_000))
        assertTrue(plan.due(now = 1_000).isEmpty())
        assertEquals(2, plan.due(now = 1_000 + com.apvcode.devx.data.Flashcard.DAY).size)
        val back = com.apvcode.devx.data.StudyPlan.fromJson(plan.toJson())
        assertEquals(2, back.cards.size)
        assertEquals("A cold stream", back.cards[1].back)
    }

    @Test
    fun anAtOpensTheListOnlyAtTheStartOfAWord() {
        val t = com.apvcode.devx.ui.chat.mentionToken("@", 1)!!
        assertEquals(0, t.start); assertEquals("", t.query)
        val u = com.apvcode.devx.ui.chat.mentionToken("tell me about @Физ", 18)!!
        assertEquals(14, u.start); assertEquals("Физ", u.query)
        assertEquals(null, com.apvcode.devx.ui.chat.mentionToken("mail me at a@b.com", 18))
        assertEquals(null, com.apvcode.devx.ui.chat.mentionToken("hello @x world", 14))
        assertEquals(null, com.apvcode.devx.ui.chat.mentionToken("no at sign", 10))
    }

    @Test
    fun mentionedNotebooksGoIntoThePrompt() {
        val text = com.apvcode.devx.data.NotebookPrompt.mentions(
            listOf(
                com.apvcode.devx.data.NotebookPrompt.Mentioned(
                    "Physics", true, true, "Mechanics course", "", "Pass the exam",
                    listOf(com.apvcode.devx.data.NotebookPrompt.SourceLine("notes.pdf", com.apvcode.devx.data.LibKind.Pdf, "Lecture notes on forces")),
                    listOf(com.apvcode.devx.data.NotebookPrompt.ChatLine("Newton", "F = ma")), null, "- likes diagrams", "",
                ),
            ),
        )!!
        assertTrue(text.contains("@Physics (study notebook)"))
        assertTrue(text.contains("notes.pdf") && text.contains("Lecture notes on forces") && text.contains("Pass the exam"))
        assertEquals(null, com.apvcode.devx.data.NotebookPrompt.mentions(emptyList()))
    }

    @Test
    fun mentionsInTextAreFoundByName() {
        val names = listOf("TypeScript с нуля", "Type", "Physics")
        val spans = com.apvcode.devx.ui.chat.mentionSpans("Look at @TypeScript с нуля, and @Physics please. mail a@Physics", names)
        assertEquals(listOf("TypeScript с нуля", "Physics"), spans.map { it.name })
        assertEquals(8, spans[0].start)
        // A name that is only the start of a longer word is not a mention.
        assertTrue(com.apvcode.devx.ui.chat.mentionSpans("about @Physicsx", names).isEmpty())
        assertTrue(com.apvcode.devx.ui.chat.mentionSpans("no mentions here", names).isEmpty())
    }

    @Test
    fun aSharedLinkAndItsTitleAreOneSource() {
        val (urls, rest) = com.apvcode.devx.data.splitShare("Great article about Kotlin\n\nhttps://example.com/kotlin?a=1.")
        assertEquals(listOf("https://example.com/kotlin?a=1"), urls)
        assertEquals("", rest) // the title beside the link is not a note of its own
        val (u2, r2) = com.apvcode.devx.data.splitShare("A long thought worth keeping as a note, written down on the phone, with no address at all in it.")
        assertTrue(u2.isEmpty()); assertTrue(r2.startsWith("A long thought"))
        val (u3, r3) = com.apvcode.devx.data.splitShare("see https://a.example/x and https://b.example/y")
        assertEquals(2, u3.size); assertEquals("", r3)
    }

    @Test
    fun spacesAreFoundByNameDescriptionOrGoal() {
        assertEquals(3, com.apvcode.devx.data.spaceMatch("TypeScript с нуля", "курс", "научиться", "type"))
        assertEquals(2, com.apvcode.devx.data.spaceMatch("Learn TypeScript", "", "", "typescript"))
        assertEquals(1, com.apvcode.devx.data.spaceMatch("Course", "about physics", "", "physics"))
        assertEquals(0, com.apvcode.devx.data.spaceMatch("Course", "about physics", "", "chemistry"))
        assertEquals(1, com.apvcode.devx.data.spaceMatch("Course", "about physics", "pass exam", "physics exam"))
    }

    @Test
    fun spokenNamesAreFoundAsWholeWords() {
        val names = listOf("Physics", "TypeScript с нуля", "Go")
        assertEquals(listOf("TypeScript с нуля"), com.apvcode.devx.ui.chat.spokenNames("расскажи про typescript с нуля, что дальше", names))
        assertEquals(listOf("Physics"), com.apvcode.devx.ui.chat.spokenNames("what is in my physics notebook", names))
        assertTrue(com.apvcode.devx.ui.chat.spokenNames("I will go home", names).isEmpty()) // "Go" is too short to look for
        assertTrue(com.apvcode.devx.ui.chat.spokenNames("astrophysicsx", names).isEmpty())
    }

    @Test
    fun theStreakCountsDaysInARowAndForgivesToday() {
        val log = com.apvcode.devx.data.StudyLog()
        log.record(100); log.record(101, 3); log.record(102)
        assertEquals(3, log.streak(today = 102))
        assertEquals(3, log.streak(today = 103)) // today is not over yet: yesterday still counts
        assertEquals(0, log.streak(today = 104)) // a missed day breaks it
        assertEquals(listOf(0, 0, 0, 0, 1, 3, 1), log.week(today = 102))
        val back = com.apvcode.devx.data.StudyLog.fromJson(log.toJson())
        assertEquals(3, back.streak(today = 102))
        log.record(300)
        assertTrue(log.days.keys.none { it < 180 }) // only the last four months are kept
    }

    @Test
    fun theWidgetCountsTheCardsThatAreDueInStudyNotebooksOnly() {
        val json = """{"Physics":{"kind":"study","plan":{"cards":[{"due":100},{"due":5000},{"due":50}]}},
            "Ideas":{"kind":"ideas","plan":{"cards":[{"due":1}]}},
            "Go":{"kind":"study","plan":{"cards":[{"due":9000}]}}}"""
        val due = com.apvcode.devx.data.StudyDue.counts(json, now = 1000)
        assertEquals(mapOf("Physics" to 2), due)
        assertTrue(com.apvcode.devx.data.StudyDue.counts("not json", 0).isEmpty())
    }

    @Test
    fun aNotebookIsWrittenOutAsMarkdown() {
        val labels = com.apvcode.devx.data.NotebookExport.Labels("Goal", "Instructions", "Plan", "Cards", "Notes", "Overview", "Sources", "%d days in a row")
        val text = com.apvcode.devx.data.NotebookExport.markdown(
            "Physics", "Mechanics course", "Pass the exam", "Be brief",
            listOf(Triple("Forces", "done", "solid"), Triple("Energy", "todo", "")),
            listOf("What is F?" to "ma"), listOf("likes diagrams"), "A short overview",
            listOf(com.apvcode.devx.data.NotebookExport.Source("notes.pdf", "lecture notes", ""), com.apvcode.devx.data.NotebookExport.Source("Site", "", "https://x.example")),
            labels, "Nov 16, 2026", streak = 4,
        )
        assertTrue(text.startsWith("# Physics\n"))
        assertTrue(text.contains("1. [x] Forces - solid") && text.contains("2. [ ] Energy"))
        assertTrue(text.contains("**What is F?**\nma") && text.contains("[Site](https://x.example)") && text.contains("4 days in a row"))
        assertTrue(text.indexOf("## Plan") < text.indexOf("## Sources"))
    }

    @Test
    fun aLongTextIsCutIntoPiecesToBeSpokenInOrder() {
        val sentence = "This is a sentence that is about forty letters. "
        val text = sentence.repeat(20) + "\n\nSecond paragraph here."
        val pieces = com.apvcode.devx.data.SpeechText.chunks(text, max = 200)
        assertTrue(pieces.size > 3)
        assertTrue(pieces.all { it.length <= 200 })
        assertEquals("Second paragraph here.", pieces.last())
        assertTrue(com.apvcode.devx.data.SpeechText.chunks("   ").isEmpty())
    }

    @Test
    fun aCitationBecomesALinkThatOpensTheSource() {
        val text = com.apvcode.devx.ui.markdown.Citations.rewrite("Forces add up [[notes.pdf, p. 12]] and mass is constant [[Лекция 1.docx]].")
        assertTrue(text.contains("[notes.pdf · 12](devx-src://open?n=notes.pdf&p=12)"))
        val link = Regex("""\(devx-src://[^)]+\)""").findAll(text).map { it.value.trim('(', ')') }.toList()
        assertEquals("notes.pdf" to 12, com.apvcode.devx.ui.markdown.Citations.decode(link[0]))
        assertEquals("Лекция 1.docx" to 0, com.apvcode.devx.ui.markdown.Citations.decode(link[1]))
        assertEquals(null, com.apvcode.devx.ui.markdown.Citations.decode("https://example.com"))
        assertEquals("plain text, no marks", com.apvcode.devx.ui.markdown.Citations.rewrite("plain text, no marks"))
        assertEquals("a [[x]] b", com.apvcode.devx.ui.markdown.Citations.rewrite("a [[x]] b")) // a name of one letter is not a source
    }

    @Test
    fun theButtonsShowWhenACardWouldReturnWithoutChangingIt() {
        val c = com.apvcode.devx.data.Flashcard("c", "q", "a", due = 0)
        assertEquals(1, c.preview("good", now = 0)); assertEquals(3, c.preview("easy", now = 0)); assertEquals(0, c.preview("again", now = 0))
        assertEquals(0, c.reps); assertEquals(0, c.interval) // nothing was changed by looking
    }

    @Test
    fun theWeeklyCopyIsDueAfterAWeekAndKeepsTheLastFew() {
        val week = com.apvcode.devx.data.AutoBackup.WEEK
        assertTrue(com.apvcode.devx.data.AutoBackup.isDue(0, 5))
        assertFalse(com.apvcode.devx.data.AutoBackup.isDue(1000, 1000 + week - 1))
        assertTrue(com.apvcode.devx.data.AutoBackup.isDue(1000, 1000 + week))
        val files = (1..6).map { "devx-backup-2026-11-0$it.zip" to it * 1000L } + ("holiday.zip" to 1L) + ("notes.txt" to 2L)
        assertEquals(listOf("devx-backup-2026-11-02.zip", "devx-backup-2026-11-01.zip"), com.apvcode.devx.data.AutoBackup.toRemove(files))
        assertEquals("devx-backup-1970-01-01.zip", com.apvcode.devx.data.AutoBackup.backupName(0).replace(Regex("1970-01-0[12]"), "1970-01-01"))
    }

    @Test
    fun aReminderToStudyIsToldApartFromOtherTasks() {
        fun task(prompt: String, project: String?) = com.apvcode.devx.data.ScheduledTask(1, "t", prompt, 8, 0, 127, null, true, project = project)
        assertTrue(com.apvcode.devx.data.StudyReminder.isStudyTask(task("[study:Physics] go", "Physics")))
        assertFalse(com.apvcode.devx.data.StudyReminder.isStudyTask(task("send me the news", null)))
        assertFalse(com.apvcode.devx.data.StudyReminder.isStudyTask(task("[study:Physics] go", null)))
    }
}
