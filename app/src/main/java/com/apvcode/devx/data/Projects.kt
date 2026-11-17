package com.apvcode.devx.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONArray
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.time.LocalDate

/**
 * A notebook (it was called a project): a name, what it is for, what the assistant should always keep in mind ([instructions]), a
 * look (icon and colour), a knowledge base of its own ([memory]: short notes the assistant writes, and an overview), the files and
 * sites it works from ([sources]) and, for a study notebook, a goal and a plan of lessons that the assistant keeps up to date.
 * Chats keep the notebook by its name; the id only names the files of the knowledge base, so a rename loses nothing.
 */
class ProjectInfo(
    val id: String,
    description: String = "",
    instructions: String = "",
    icon: String = ProjectLook.DEFAULT_ICON,
    color: Long = ProjectLook.colors.first(),
    val memory: MemoryStore,
    kind: String = KIND_PROJECT,
    goal: String = "",
    useNotes: Boolean = true,
    sources: List<Attachment> = emptyList(),
    val plan: StudyPlan = StudyPlan(),
    deadline: Long = 0L,
) {
    var description by mutableStateOf(description)
    var instructions by mutableStateOf(instructions)
    var icon by mutableStateOf(icon)
    var color by mutableLongStateOf(color)

    /** [KIND_PROJECT] (a project: instructions, knowledge base, chats), or a notebook: [KIND_IDEAS] (ideas and sources by topic) or [KIND_STUDY] (learn: a goal, lessons, quizzes, progress). */
    var kind by mutableStateOf(kind)

    /** What a study notebook is for ("pass the exam in June", "learn Go for work"). */
    var goal by mutableStateOf(goal)

    /** The day the person wants to reach the goal by, as a day number (epoch day); 0 when there is none. The plan's pace is counted from it. */
    var deadline by mutableLongStateOf(deadline)

    /** Whether the assistant looks through the other chats of this notebook when it answers. */
    var useNotes by mutableStateOf(useNotes)

    /** Put away: it is not in the lists of the side panel (a finished notebook, an old project), but nothing is deleted. */
    var archived by mutableStateOf(false)

    /** Files, photos and pages the person added to the notebook itself (not to one chat). */
    val sources = mutableStateListOf<Attachment>().also { it.addAll(sources) }

    val isStudy: Boolean get() = kind == KIND_STUDY

    /** A notebook (made to learn and to work from sources), not a project. */
    val isNotebook: Boolean get() = kind != KIND_PROJECT

    companion object {
        const val KIND_PROJECT = "project"
        const val KIND_IDEAS = "ideas"
        const val KIND_STUDY = "study"
    }
}

/** One lesson of a study plan. */
class Lesson(val id: String, title: String, status: String = TODO, note: String = "") {
    var title by mutableStateOf(title)
    var status by mutableStateOf(status)
    var note by mutableStateOf(note)

    /** The day number (see [StudyLog.today]) the lesson was finished; 0 when it is not done or was done before this was kept. */
    var doneDay by androidx.compose.runtime.mutableIntStateOf(0)

    companion object {
        const val TODO = "todo"
        const val DOING = "doing"
        const val DONE = "done"
        fun clean(status: String): String = when (status.trim().lowercase()) { DONE, "complete", "completed", "finished" -> DONE; DOING, "in_progress", "current", "active" -> DOING; else -> TODO }
    }
}

/**
 * A card to learn by heart (a question and its answer) with the day it is due again. Each answer moves that day: a good one pushes
 * it further out (1, 3 days, then by the ease), a wrong one brings it back in minutes: the more it is known, the less it is asked.
 */
class Flashcard(
    val id: String,
    val front: String,
    val back: String,
    var due: Long,
    var interval: Int = 0,
    var ease: Double = 2.5,
    var reps: Int = 0,
    /** What the card is about (a word or two): the weak spots are found by it. */
    val topic: String = "",
    /** How many times it was forgotten. */
    var lapses: Int = 0,
    /** The name of a picture shown with the question (a file of [CardImages]); empty when there is none. */
    val image: String = "",
) {
    fun isDue(now: Long = System.currentTimeMillis()) = due <= now

    /** In how many days the card would come back after this answer (0: in minutes), without changing it: for the label on the button. */
    fun preview(result: String, now: Long = System.currentTimeMillis()): Int =
        Flashcard(id, front, back, due, interval, ease, reps, topic, lapses, image).also { it.review(result, now) }.interval

    /** Takes an answer ("again", "hard", "good", "easy") and sets when the card is asked next. False if the word is not one of those. */
    fun review(result: String, now: Long = System.currentTimeMillis()): Boolean {
        when (result.trim().lowercase()) {
            "again", "wrong", "forgot" -> { lapses++; reps = 0; interval = 0; ease = (ease - 0.2).coerceAtLeast(MIN_EASE); due = now + 10 * 60_000L; return true }
            "hard" -> { interval = maxOf(1, Math.round(interval * 1.2).toInt()); ease = (ease - 0.15).coerceAtLeast(MIN_EASE) }
            "good", "right", "correct" -> interval = when (reps) { 0 -> 1; 1 -> 3; else -> Math.round(interval * ease).toInt() }
            "easy" -> { interval = when (reps) { 0 -> 3; 1 -> 6; else -> Math.round(interval * ease * 1.3).toInt() }; ease = (ease + 0.15).coerceAtMost(3.0) }
            else -> return false
        }
        interval = interval.coerceIn(1, 365)
        reps += 1
        due = now + interval * DAY
        return true
    }

    companion object {
        const val DAY = 86_400_000L
        const val MIN_EASE = 1.3
    }
}

/** A card the assistant proposes (before it gets an id and a day). */
class CardDraft(val front: String, val back: String, val topic: String = "", val image: String = "")

/** A concept of the map of a study notebook (a term, an idea) with a line that says what it is. */
class Concept(val id: String, name: String, note: String = "") {
    var name by mutableStateOf(name)
    var note by mutableStateOf(note)
}

/** How two concepts are related ("is a kind of", "needs", "causes"): from one to the other, by their ids. */
class ConceptLink(val from: String, val to: String, val label: String = "")

/** One exam the assistant gave: how many answers were right, and the topics that went badly. */
class ExamResult(val at: Long, val title: String, val correct: Int, val total: Int, val weak: List<String>) {
    val percent: Int get() = if (total <= 0) 0 else Math.round(correct * 100f / total)
}

/** A topic that needs work, with a score (the higher, the weaker). */
class WeakSpot(val topic: String, val score: Int)

/** Where the person stands against the day they set: days left, lessons left, and how many lessons a day that takes. */
class Pace(val daysLeft: Int, val lessonsLeft: Int) {
    val overdue: Boolean get() = daysLeft < 0
    /** Lessons a day to be in time (counted over the days left, today included). */
    val perDay: Double get() = if (lessonsLeft <= 0) 0.0 else lessonsLeft.toDouble() / maxOf(1, daysLeft + 1)

    /** True when the lessons finished lately (a day's average) are well under what the deadline asks for. */
    fun behind(donePerDay: Double): Boolean = !overdue && lessonsLeft > 0 && donePerDay < perDay * 0.75

    companion object {
        /** Null when there is no deadline. */
        fun of(deadline: Long, lessonsLeft: Int, today: Int = StudyLog.today()): Pace? = if (deadline <= 0L) null else Pace((deadline - today).toInt(), lessonsLeft)
    }
}

/**
 * What was learned on which day: a count of things done (cards answered, lessons finished) by day number. From it: how many days in a
 * row the person has studied, and the last seven days for a small chart.
 */
class StudyLog {
    val days = mutableStateMapOf<Int, Int>()

    fun record(day: Int = today(), n: Int = 1) {
        days[day] = (days[day] ?: 0) + n
        // Only the last four months are kept.
        days.keys.filter { it < day - 120 }.forEach { days.remove(it) }
    }

    /** Days in a row up to [today]; a day not yet studied does not break it (there is still time), but a missed yesterday does. */
    fun streak(today: Int = today()): Int {
        var d = if ((days[today] ?: 0) > 0) today else today - 1
        var n = 0
        while ((days[d] ?: 0) > 0) { n++; d-- }
        return n
    }

    /** The last seven days, oldest first, ending with [today]. */
    fun week(today: Int = today()): List<Int> = (today - 6..today).map { days[it] ?: 0 }

    val isEmpty: Boolean get() = days.isEmpty()

    fun toJson(): JSONObject = JSONObject().also { o -> days.forEach { (d, n) -> o.put(d.toString(), n) } }

    companion object {
        fun today(): Int = LocalDate.now().toEpochDay().toInt()
        fun fromJson(o: JSONObject?): StudyLog = StudyLog().also { l -> o?.keys()?.forEach { k -> k.toIntOrNull()?.let { d -> l.days[d] = o.optInt(k) } } }
    }
}

/** The lessons of a study notebook, in order, and how far the person has got. The assistant writes it; the person sees it on the page. */
class StudyPlan {
    val lessons = mutableStateListOf<Lesson>()
    /** The cards to learn by heart (made by the assistant from the lessons). */
    val cards = mutableStateListOf<Flashcard>()
    /** The days of study (for the streak and the chart on the page). */
    val log = StudyLog()
    /** The exams that were given, oldest first (the last twenty are kept). */
    val exams = mutableStateListOf<ExamResult>()
    /** The map of the concepts of the notebook and how they connect: the assistant builds it as things are taught. */
    val concepts = mutableStateListOf<Concept>()
    val links = mutableStateListOf<ConceptLink>()
    var updatedAt by mutableLongStateOf(0L)

    fun conceptNamed(name: String): Concept? = concepts.firstOrNull { it.name.trim().equals(name.trim(), ignoreCase = true) }

    /** Adds a concept, or gives a new note to the one with that name. Null when the map is full or the name is empty. */
    fun upsertConcept(name: String, note: String = "", now: Long = System.currentTimeMillis()): Concept? {
        val n = name.trim().take(50)
        if (n.isEmpty()) return null
        conceptNamed(n)?.let { old -> if (note.isNotBlank()) old.note = note.trim().take(300); return old }
        if (concepts.size >= MAX_CONCEPTS) return null
        val c = Concept("k${now.toString(36)}${concepts.size}", n, note.trim().take(300))
        concepts += c
        updatedAt = now
        return c
    }

    /** Connects two concepts by name (one that is not on the map yet is added). False for a link to itself, one that is already there, or a full map. */
    fun link(fromName: String, toName: String, label: String = ""): Boolean {
        val a = upsertConcept(fromName) ?: return false
        val b = upsertConcept(toName) ?: return false
        if (a.id == b.id || links.size >= MAX_LINKS) return false
        val l = label.trim().take(30)
        if (links.any { ((it.from == a.id && it.to == b.id) || (it.from == b.id && it.to == a.id)) && it.label.equals(l, true) }) return false
        links += ConceptLink(a.id, b.id, l)
        updatedAt = System.currentTimeMillis()
        return true
    }

    /** Takes concepts off the map with their links; gives how many were removed. */
    fun removeConcepts(names: List<String>): Int {
        val gone = names.mapNotNull { conceptNamed(it) }
        gone.forEach { c -> concepts.remove(c); links.removeAll { it.from == c.id || it.to == c.id } }
        if (gone.isNotEmpty()) updatedAt = System.currentTimeMillis()
        return gone.size
    }

    fun clearGraph() { concepts.clear(); links.clear(); updatedAt = System.currentTimeMillis() }

    fun due(now: Long = System.currentTimeMillis()): List<Flashcard> = cards.filter { it.isDue(now) }.sortedBy { it.due }

    /** Adds cards (a question and an answer each); one that is already there (same question) is skipped. Gives how many were new. */
    fun addCards(pairs: List<Pair<String, String>>, now: Long = System.currentTimeMillis()): Int {
        var n = 0
        for ((front, back) in pairs) {
            val f = front.trim(); val b = back.trim()
            if (f.isEmpty() || b.isEmpty() || cards.size >= 300) continue
            if (cards.any { it.front.trim().equals(f, ignoreCase = true) }) continue
            cards += Flashcard("c${now.toString(36)}${cards.size}${n}", f.take(400), b.take(1200), now + Flashcard.DAY)
            n++
        }
        if (n > 0) updatedAt = now
        return n
    }

    /**
     * Adds cards that carry a topic. [dueAt] is when they first come up: tomorrow for new knowledge (the default), now for what was just
     * answered wrongly. A card that already exists (same question) is skipped. Gives how many were new.
     */
    fun addDrafts(drafts: List<CardDraft>, now: Long = System.currentTimeMillis(), dueAt: Long = now + Flashcard.DAY, limit: Int = 300): Int {
        var n = 0
        val have = cards.mapTo(HashSet()) { it.front.trim().lowercase() }
        for (d in drafts) {
            val f = d.front.trim(); val b = d.back.trim()
            if (f.isEmpty() || b.isEmpty() || cards.size >= limit) continue
            if (!have.add(f.lowercase())) continue
            cards += Flashcard("c${now.toString(36)}${cards.size}${n}", f.take(400), b.take(1200), dueAt, topic = d.topic.trim().take(40), image = d.image)
            n++
        }
        if (n > 0) updatedAt = now
        return n
    }

    /**
     * What was answered wrongly in an exam becomes a card that is due now; one that already exists is brought back for today and counts
     * as forgotten once more. Gives how many cards were new.
     */
    fun recordMisses(drafts: List<CardDraft>, now: Long = System.currentTimeMillis()): Int {
        val fresh = ArrayList<CardDraft>()
        for (d in drafts) {
            val old = cards.firstOrNull { it.front.trim().equals(d.front.trim(), ignoreCase = true) }
            if (old == null) fresh += d else { old.due = now; old.lapses++; old.interval = 0; old.reps = 0; updatedAt = now }
        }
        return addDrafts(fresh, now, dueAt = now)
    }

    /** Writes down an exam, with the topics that went badly. */
    fun addExam(title: String, correct: Int, total: Int, weak: List<String>, now: Long = System.currentTimeMillis()): ExamResult {
        val exam = ExamResult(now, title.trim().take(80), correct.coerceIn(0, maxOf(total, 0)), total.coerceAtLeast(0), weak.map { it.trim().take(40) }.filter { it.isNotEmpty() }.distinct().take(8))
        exams += exam
        while (exams.size > 20) exams.removeAt(0)
        updatedAt = now
        return exam
    }

    /**
     * The topics that need work, weakest first: from the cards (each time one was forgotten, and a low ease) and from the last exams
     * (a topic that went badly there counts more). Topics with nothing against them are left out.
     */
    fun weakSpots(limit: Int = 5): List<WeakSpot> {
        val score = LinkedHashMap<String, Int>()
        val names = HashMap<String, String>()
        fun add(topic: String, n: Int) {
            val key = topic.trim().lowercase()
            if (key.isEmpty() || n <= 0) return
            names.getOrPut(key) { topic.trim() }
            score[key] = (score[key] ?: 0) + n
        }
        for (c in cards) add(c.topic, c.lapses * 2 + (if (c.ease < 2.2) Math.round((2.5 - c.ease) / 0.15).toInt() else 0))
        for (e in exams.takeLast(5)) for (t in e.weak) add(t, 3)
        return score.entries.sortedByDescending { it.value }.take(limit).map { WeakSpot(names[it.key] ?: it.key, it.value) }
    }

    /** A copy to start again from: the same lessons (not done), cards (new) and map, without the days of study, the exams or the dates. */
    fun template(now: Long = System.currentTimeMillis()): StudyPlan = StudyPlan().also { p ->
        concepts.forEach { p.concepts += Concept(it.id, it.name, it.note) }
        links.forEach { p.links += ConceptLink(it.from, it.to, it.label) }
        lessons.forEachIndexed { i, l -> p.lessons += Lesson("l${now.toString(36)}$i", l.title) }
        cards.forEachIndexed { i, c -> p.cards += Flashcard("c${now.toString(36)}$i", c.front, c.back, now + Flashcard.DAY, topic = c.topic, image = c.image) }
        p.updatedAt = now
    }

    /** How many lessons were finished in the last [days] days, today included. */
    fun doneSince(days: Int = 7, today: Int = StudyLog.today()): Int = lessons.count { it.status == Lesson.DONE && it.doneDay > today - days && it.doneDay <= today }

    /** True once the person has been studying for a few days: before that there is no pace to judge. */
    fun hasHistory(today: Int = StudyLog.today()): Boolean =
        log.days.keys.any { it <= today - 3 } &&
            // Lessons finished before their day was kept have no date: the pace is not judged from them (until a new one has).
            (lessons.none { it.status == Lesson.DONE && it.doneDay == 0 } || lessons.any { it.doneDay > 0 })

    val done: Int get() = lessons.count { it.status == Lesson.DONE }
    val total: Int get() = lessons.size

    /** The lesson to go on with: the one that was begun, else the first not done. */
    val next: Lesson? get() = lessons.firstOrNull { it.status == Lesson.DOING } ?: lessons.firstOrNull { it.status == Lesson.TODO }

    /** Replaces the lessons (keeping the state of those that stay under the same title). */
    fun set(titles: List<String>) {
        val old = lessons.associateBy { it.title.trim().lowercase() }
        val fresh = titles.map { it.trim() }.filter { it.isNotEmpty() }.take(40).mapIndexed { i, t ->
            old[t.lowercase()]?.let { Lesson(it.id, t, it.status, it.note).also { n -> n.doneDay = it.doneDay } } ?: Lesson("l${System.currentTimeMillis().toString(36)}$i", t)
        }
        lessons.clear()
        lessons.addAll(fresh)
        updatedAt = System.currentTimeMillis()
    }

    /** Finds a lesson by its number (from 1) or by words of its title. */
    fun find(which: String): Lesson? {
        lessons.firstOrNull { it.id == which.trim() }?.let { return it }
        val n = which.trim().toIntOrNull()
        if (n != null) return lessons.getOrNull(n - 1)
        val w = which.trim().lowercase()
        return lessons.firstOrNull { it.title.lowercase() == w } ?: lessons.firstOrNull { w.isNotEmpty() && it.title.lowercase().contains(w) }
    }

    fun mark(which: String, status: String, note: String?): Lesson? {
        val l = find(which) ?: return null
        val was = l.status
        l.status = Lesson.clean(status)
        if (l.status == Lesson.DONE && was != Lesson.DONE) l.doneDay = StudyLog.today() else if (l.status != Lesson.DONE) l.doneDay = 0
        if (!note.isNullOrBlank()) l.note = note.trim()
        updatedAt = System.currentTimeMillis()
        return l
    }

    fun toJson(): JSONObject = JSONObject().put("at", updatedAt).put(
        "lessons",
        JSONArray().also { a -> lessons.forEach { a.put(JSONObject().put("id", it.id).put("title", it.title).put("status", it.status).put("note", it.note).put("dd", it.doneDay)) } },
    ).put("log", log.toJson()).put(
        "cards",
        JSONArray().also { a -> cards.forEach { a.put(JSONObject().put("id", it.id).put("q", it.front).put("a", it.back).put("due", it.due).put("i", it.interval).put("e", it.ease).put("r", it.reps).put("t", it.topic).put("l", it.lapses).put("im", it.image)) } },
    ).put(
        "graph",
        JSONObject()
            .put("n", JSONArray().also { a -> concepts.forEach { a.put(JSONObject().put("i", it.id).put("n", it.name).put("t", it.note)) } })
            .put("e", JSONArray().also { a -> links.forEach { a.put(JSONObject().put("a", it.from).put("b", it.to).put("l", it.label)) } }),
    ).put(
        "exams",
        JSONArray().also { a -> exams.forEach { a.put(JSONObject().put("at", it.at).put("title", it.title).put("ok", it.correct).put("of", it.total).put("weak", JSONArray(it.weak))) } },
    )

    companion object {
        const val MAX_CONCEPTS = 60
        const val MAX_LINKS = 150

        fun fromJson(o: JSONObject?): StudyPlan = StudyPlan().also { p ->
            if (o == null) return@also
            p.updatedAt = o.optLong("at")
            o.optJSONArray("lessons")?.let { a ->
                for (i in 0 until a.length()) {
                    val l = a.optJSONObject(i) ?: continue
                    p.lessons += Lesson(l.optString("id").ifBlank { "l$i" }, l.optString("title"), Lesson.clean(l.optString("status")), l.optString("note")).also { it.doneDay = l.optInt("dd") }
                }
            }
            o.optJSONObject("log")?.let { l -> StudyLog.fromJson(l).days.forEach { (d, n) -> p.log.days[d] = n } }
            o.optJSONArray("cards")?.let { a ->
                for (i in 0 until a.length()) {
                    val c = a.optJSONObject(i) ?: continue
                    p.cards += Flashcard(c.optString("id").ifBlank { "c$i" }, c.optString("q"), c.optString("a"), c.optLong("due"), c.optInt("i"), c.optDouble("e", 2.5), c.optInt("r"), c.optString("t"), c.optInt("l"), c.optString("im"))
                }
            }
            o.optJSONObject("graph")?.let { g ->
                g.optJSONArray("n")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { c -> p.concepts += Concept(c.optString("i").ifBlank { "k$i" }, c.optString("n"), c.optString("t")) } }
                val ids = p.concepts.map { it.id }.toSet()
                g.optJSONArray("e")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { e -> if (e.optString("a") in ids && e.optString("b") in ids) p.links += ConceptLink(e.optString("a"), e.optString("b"), e.optString("l")) } }
            }
            o.optJSONArray("exams")?.let { a ->
                for (i in 0 until a.length()) {
                    val e = a.optJSONObject(i) ?: continue
                    val weak = e.optJSONArray("weak")?.let { w -> (0 until w.length()).map { w.optString(it) } } ?: emptyList()
                    p.exams += ExamResult(e.optLong("at"), e.optString("title"), e.optInt("ok"), e.optInt("of"), weak)
                }
            }
        }
    }
}

/** The icons and colours a project can have. */
object ProjectLook {
    const val DEFAULT_ICON = "folder"

    /** Icon ids; each one has a drawable on the screen side. */
    val icons = listOf(
        "folder", "code", "terminal", "book", "school",
        "music", "palette", "briefcase", "chart", "flask",
        "heart", "plane", "tool", "paw", "bulb",
        "rocket", "gamepad", "cart", "dollar", "language",
        "news", "bolt", "star", "coffee", "brain",
    )

    /** Calm colours: the first is the plain white-grey of the app, the others are used sparingly. */
    val colors = listOf(0xFFE6E6E6L, 0xFFE5736AL, 0xFFE8A05CL, 0xFFD9C25AL, 0xFF6FBF8EL, 0xFF5FB3C4L, 0xFF6C9BE8L, 0xFFA48BE0L, 0xFFD981B5L)
}

/** Where the details of the projects are kept (one small file); the names of the projects are in projects.json. */
class ProjectStore(private val context: Context) {
    private val file = File(context.filesDir, "projects_meta.json")
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "devx-projects").apply { isDaemon = true } }

    /** The details by project name; a project without any gets the plain defaults. */
    fun load(names: List<String>): Map<String, ProjectInfo> {
        val root = readRoot()
        // A project that has details but is missing from the list of names (the list was lost or damaged) is still a project.
        val all = names + root.keys().asSequence().filter { it !in names && it != VERSION_KEY }.toList()
        return all.associateWith { name -> decode(root.optJSONObject(name)) }
    }

    /** One project from its saved form (null: a project that has no details yet gets the plain defaults). */
    fun decode(o: JSONObject?): ProjectInfo {
        val id = o?.optString("id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().take(8)
        return ProjectInfo(
            id = id,
            description = o?.optString("description").orEmpty(),
            instructions = o?.optString("instructions").orEmpty(),
            icon = o?.optString("icon")?.takeIf { it in ProjectLook.icons } ?: ProjectLook.DEFAULT_ICON,
            color = o?.optLong("color", ProjectLook.colors.first()) ?: ProjectLook.colors.first(),
            memory = memoryOf(id),
            kind = kindOf(o),
            goal = o?.optString("goal").orEmpty(),
            useNotes = o?.optBoolean("useNotes", true) ?: true,
            sources = o?.optJSONArray("sources")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::attachmentFromJson) } } ?: emptyList(),
            plan = StudyPlan.fromJson(o?.optJSONObject("plan")),
            deadline = o?.optLong("deadline") ?: 0L,
        ).also { it.archived = o?.optBoolean("archived") ?: false }
    }

    /** The saved details. A file that cannot be read is put aside (not overwritten by the next save), so nothing is lost for good. */
    private fun readRoot(): JSONObject {
        if (!file.exists()) return JSONObject()
        return runCatching { JSONObject(file.readText()) }.logged("projects").getOrElse { e ->
            ErrorLog.warn("projects", "projects_meta.json could not be read; the copy from a few minutes ago is used", e)
            attempt("projects") { file.copyTo(File(file.parentFile, file.name + ".damaged"), overwrite = true) }
            // The copy made a little earlier is better than nothing.
            runCatching { JSONObject(backup.readText()) }.logged("projects").getOrDefault(JSONObject())
        }
    }

    /** The details as they were some minutes ago; used only when the main file cannot be read. */
    private val backup = File(context.filesDir, "projects_meta.json.bak")

    /**
     * What a saved entry is. Notebooks are marked ("nb") since this build. An older entry is a study notebook if it says so; an "ideas"
     * entry from the build that called every project a notebook is a notebook only if it was made on the notebook screen (that gave it
     * the bulb and the orange), and a project otherwise.
     */
    private fun kindOf(o: JSONObject?): String {
        if (o == null) return ProjectInfo.KIND_PROJECT
        val kind = o.optString("kind")
        if (o.has("nb")) return if (o.optBoolean("nb")) (if (kind == ProjectInfo.KIND_STUDY) ProjectInfo.KIND_STUDY else ProjectInfo.KIND_IDEAS) else ProjectInfo.KIND_PROJECT
        return when {
            kind == ProjectInfo.KIND_STUDY -> ProjectInfo.KIND_STUDY
            kind == ProjectInfo.KIND_IDEAS && o.optString("icon") == "bulb" && o.optLong("color") == 0xFFE8A05CL -> ProjectInfo.KIND_IDEAS
            else -> ProjectInfo.KIND_PROJECT
        }
    }

    fun memoryOf(id: String) = MemoryStore(context, "project_$id.json", "project_${id}_summary.json")

    companion object {
        private const val BACKUP_EVERY_MS = 10 * 60 * 1000L

        /** A key of the file that is not a project (so no project may have this name). */
        const val VERSION_KEY = "__format"
        private const val FORMAT = 2
    }

    /** One project as it is saved. */
    fun encode(p: ProjectInfo): JSONObject =
        JSONObject().put("id", p.id).put("description", p.description).put("instructions", p.instructions)
            .put("icon", p.icon).put("color", p.color).put("kind", p.kind).put("nb", p.isNotebook).put("goal", p.goal).put("deadline", p.deadline).put("useNotes", p.useNotes).put("archived", p.archived)
            .put("sources", JSONArray().also { a -> p.sources.forEach { a.put(it.toJson()) } })
            .put("plan", p.plan.toJson())

    fun save(all: Map<String, ProjectInfo>) {
        val json = JSONObject().also { root ->
            // The format of the file: a later build reads an older one by this number instead of guessing from missing fields.
            root.put(VERSION_KEY, FORMAT)
            all.forEach { (name, p) -> root.put(name, encode(p)) }
        }.toString()
        writer.execute {
            // The good file that is about to be replaced is kept aside, but not on every save (a bad save would copy itself over it).
            if (file.length() > 2 && System.currentTimeMillis() - backup.lastModified() > BACKUP_EVERY_MS) runCatching { file.copyTo(backup, overwrite = true) }.logged("projects")
            AtomicFile.writeQuietly("projects", file, json)
        }
    }
}
