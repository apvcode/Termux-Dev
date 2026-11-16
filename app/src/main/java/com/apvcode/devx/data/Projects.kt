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
) {
    var description by mutableStateOf(description)
    var instructions by mutableStateOf(instructions)
    var icon by mutableStateOf(icon)
    var color by mutableLongStateOf(color)

    /** [KIND_PROJECT] (a project: instructions, knowledge base, chats), or a notebook: [KIND_IDEAS] (ideas and sources by topic) or [KIND_STUDY] (learn: a goal, lessons, quizzes, progress). */
    var kind by mutableStateOf(kind)

    /** What a study notebook is for ("pass the exam in June", "learn Go for work"). */
    var goal by mutableStateOf(goal)

    /** Whether the assistant looks through the other chats of this notebook when it answers. */
    var useNotes by mutableStateOf(useNotes)

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
) {
    fun isDue(now: Long = System.currentTimeMillis()) = due <= now

    /** In how many days the card would come back after this answer (0: in minutes), without changing it: for the label on the button. */
    fun preview(result: String, now: Long = System.currentTimeMillis()): Int =
        Flashcard(id, front, back, due, interval, ease, reps).also { it.review(result, now) }.interval

    /** Takes an answer ("again", "hard", "good", "easy") and sets when the card is asked next. False if the word is not one of those. */
    fun review(result: String, now: Long = System.currentTimeMillis()): Boolean {
        when (result.trim().lowercase()) {
            "again", "wrong", "forgot" -> { reps = 0; interval = 0; ease = (ease - 0.2).coerceAtLeast(MIN_EASE); due = now + 10 * 60_000L; return true }
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
        fun today(): Int = java.time.LocalDate.now().toEpochDay().toInt()
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
    var updatedAt by mutableLongStateOf(0L)

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

    val done: Int get() = lessons.count { it.status == Lesson.DONE }
    val total: Int get() = lessons.size

    /** The lesson to go on with: the one that was begun, else the first not done. */
    val next: Lesson? get() = lessons.firstOrNull { it.status == Lesson.DOING } ?: lessons.firstOrNull { it.status == Lesson.TODO }

    /** Replaces the lessons (keeping the state of those that stay under the same title). */
    fun set(titles: List<String>) {
        val old = lessons.associateBy { it.title.trim().lowercase() }
        val fresh = titles.map { it.trim() }.filter { it.isNotEmpty() }.take(40).mapIndexed { i, t ->
            old[t.lowercase()]?.let { Lesson(it.id, t, it.status, it.note) } ?: Lesson("l${System.currentTimeMillis().toString(36)}$i", t)
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
        l.status = Lesson.clean(status)
        if (!note.isNullOrBlank()) l.note = note.trim()
        updatedAt = System.currentTimeMillis()
        return l
    }

    fun toJson(): JSONObject = JSONObject().put("at", updatedAt).put(
        "lessons",
        JSONArray().also { a -> lessons.forEach { a.put(JSONObject().put("id", it.id).put("title", it.title).put("status", it.status).put("note", it.note)) } },
    ).put("log", log.toJson()).put(
        "cards",
        JSONArray().also { a -> cards.forEach { a.put(JSONObject().put("id", it.id).put("q", it.front).put("a", it.back).put("due", it.due).put("i", it.interval).put("e", it.ease).put("r", it.reps)) } },
    )

    companion object {
        fun fromJson(o: JSONObject?): StudyPlan = StudyPlan().also { p ->
            if (o == null) return@also
            p.updatedAt = o.optLong("at")
            o.optJSONArray("lessons")?.let { a ->
                for (i in 0 until a.length()) {
                    val l = a.optJSONObject(i) ?: continue
                    p.lessons += Lesson(l.optString("id").ifBlank { "l$i" }, l.optString("title"), Lesson.clean(l.optString("status")), l.optString("note"))
                }
            }
            o.optJSONObject("log")?.let { l -> StudyLog.fromJson(l).days.forEach { (d, n) -> p.log.days[d] = n } }
            o.optJSONArray("cards")?.let { a ->
                for (i in 0 until a.length()) {
                    val c = a.optJSONObject(i) ?: continue
                    p.cards += Flashcard(c.optString("id").ifBlank { "c$i" }, c.optString("q"), c.optString("a"), c.optLong("due"), c.optInt("i"), c.optDouble("e", 2.5), c.optInt("r"))
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
        val root = runCatching { JSONObject(file.takeIf { it.exists() }?.readText() ?: "{}") }.getOrDefault(JSONObject())
        return names.associateWith { name ->
            val o = root.optJSONObject(name)
            val id = o?.optString("id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().take(8)
            ProjectInfo(
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
            )
        }
    }

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

    fun save(all: Map<String, ProjectInfo>) {
        val json = JSONObject().also { root ->
            all.forEach { (name, p) ->
                root.put(
                    name,
                    JSONObject().put("id", p.id).put("description", p.description).put("instructions", p.instructions)
                        .put("icon", p.icon).put("color", p.color).put("kind", p.kind).put("nb", p.isNotebook).put("goal", p.goal).put("useNotes", p.useNotes)
                        .put("sources", JSONArray().also { a -> p.sources.forEach { a.put(it.toJson()) } })
                        .put("plan", p.plan.toJson()),
                )
            }
        }.toString()
        writer.execute {
            runCatching {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }
        }
    }
}
