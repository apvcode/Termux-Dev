package com.apvcode.devx.data

import org.json.JSONArray
import org.json.JSONObject

/** The tools of notebooks and of the library: finding an old file, taking it into a chat, and keeping a study plan. */
class NotebookTools(
    private val notebook: (String) -> ProjectInfo?,
    private val library: () -> List<LibraryEntry>,
    private val useFile: (chatId: String, Attachment) -> Boolean,
    private val changed: () -> Unit,
    /** Changes a project or notebook (its name, look, description, instructions, goal); gives what was done, for the model. */
    private val update: suspend (project: String, JSONObject) -> String = { _, _ -> "Not available." },
    /** Reads web pages and adds them to a notebook as sources; gives how many could not be read. */
    private val addSites: suspend (project: String, List<String>) -> Int = { _, urls -> urls.size },
    /** Makes a notebook or a project from what the assistant proposed (after the person agreed) and puts the chat in it; gives words for the model. */
    private val create: suspend (chatId: String, JSONObject) -> String = { _, _ -> "Not available." },
    /** Texts of the confirmation card, in the app's language. */
    private val askTexts: (kind: String) -> Pair<String, String> = { kind -> "Create it?" to kind },
    /** Sets or clears the reminder to study; gives words for the model. */
    private val remind: (project: String, JSONObject) -> String = { _, _ -> "Not available." },
) {
    val libraryNames = setOf("library_search", "library_use")
    val studyNames = setOf("study_plan", "study_progress", "cards_add", "cards_due", "cards_review", "study_remind")
    val names = libraryNames + studyNames + setOf("project_update", "source_add_sites", "space_create")

    /** Offered in an ordinary chat: after the person has decided what to learn or work on, make a place for it. */
    fun createDefinition(): WireTool = tool(
        "space_create",
        "Make a notebook or a project for what the person has decided to do, after you have talked it through with them and they agreed (never before). The person confirms on a card, then it is made and this chat is moved into it. kind: study (a notebook to learn something: a goal, lessons, quizzes, progress), ideas (a notebook of sources and ideas on a topic) or project (ongoing work with its own instructions and knowledge). Give a short good name, a one or two sentence description, for study the goal, a fitting icon and colour.",
        schema(
            "kind" to str("study, ideas or project."),
            "title" to str("The name, short."),
            "description" to str("What it is for, one or two sentences."),
            "goal" to str("For study: the goal in one sentence (optional)."),
            "icon" to str("One of: " + ProjectLook.icons.joinToString(", ") + " (optional)."),
            "color" to str("One of: " + PALETTE.joinToString(", ") + " (optional)."),
            "instructions" to str("How the assistant should behave there (optional)."),
            required = listOf("kind", "title"),
        ),
    )

    fun definitions(inStudy: Boolean, inSpace: Boolean = false, inNotebook: Boolean = false): List<WireTool> = buildList {
        if (inSpace) add(
            tool(
                "project_update",
                "Change this " + (if (inNotebook) "notebook" else "project") + ": its name, icon, colour, description, instructions" + (if (inNotebook) " or goal" else "") +
                    ". Only what you give changes. Do it when the person asks; you may suggest it once when it clearly fits (a vague name, a goal that is now clear).",
                schema(
                    "title" to str("A new name (optional)."),
                    "icon" to str("One of: " + ProjectLook.icons.joinToString(", ") + " (optional)."),
                    "color" to str("One of: " + PALETTE.joinToString(", ") + " (optional)."),
                    "description" to str("What it is for, one or two sentences (optional)."),
                    "instructions" to str("The full new instructions the assistant follows here (optional; replaces the old ones)."),
                    "goal" to str("The goal of a study notebook, one sentence (optional)."),
                    required = emptyList(),
                ),
            ),
        )
        if (inNotebook) add(
            tool(
                "source_add_sites",
                "Add web pages to this notebook as sources: their visible text is kept and described, and you can read them later with the file tools. Use it after searching the web, with the best pages (reliable, on the topic, readable).",
                JSONObject().put("type", "object")
                    .put("properties", JSONObject().put("urls", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "Full addresses, 1 to 10.")))
                    .put("required", JSONArray(listOf("urls"))),
            ),
        )
        add(
            tool(
                "library_search",
                "Search the person's library: every file they ever attached in any chat or notebook, and the files you made. By words of the name, the description or the chat. Gives id, name, kind, what it is about and where it was used.",
                schema("query" to str("Words to look for; empty lists the newest."), "limit" to int("How many (default 8, at most 20)."), required = emptyList()),
            ),
        )
        add(
            tool(
                "library_use",
                "Take a file from the library into this conversation, so that you can read it with the file tools (read_pdf_pages, read_text, search_in_file, view_image).",
                schema("id" to str("The id from library_search."), required = listOf("id")),
            ),
        )
        if (inStudy) {
            add(
                tool(
                    "study_plan",
                    "Write or replace the study plan of this notebook: the lessons in the order to learn them (each a short title). Lessons that stay keep their progress. Optionally set the goal too.",
                    JSONObject().put("type", "object")
                        .put("properties", JSONObject()
                            .put("lessons", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "Lesson titles, 5 to 12, in order."))
                            .put("goal", str("The goal of the person in one sentence (optional).")))
                        .put("required", JSONArray(listOf("lessons"))),
                ),
            )
            add(
                tool(
                    "study_progress",
                    "Mark a lesson of the plan: doing (started) or done (finished), with a short note of how it went (what is solid, what to review).",
                    schema("lesson" to str("The lesson's number (from 1) or words of its title."), "status" to str("todo, doing or done."), "note" to str("A short note (optional)."), required = listOf("lesson", "status")),
                ),
            )
            add(
                tool(
                    "cards_add",
                    "Make cards to learn by heart from what was just taught (facts, terms, formulas, answers worth knowing): a short question and a short answer each. 3 to 12 at a time, only what is worth remembering. They come back for review when they are due.",
                    JSONObject().put("type", "object")
                        .put("properties", JSONObject().put("cards", JSONObject().put("type", "array").put("description", "The cards.").put("items", JSONObject().put("type", "object")
                            .put("properties", JSONObject().put("front", str("The question.")).put("back", str("The answer.")))
                            .put("required", JSONArray(listOf("front", "back"))))))
                        .put("required", JSONArray(listOf("cards"))),
                ),
            )
            add(tool("cards_due", "The cards that are due for review now, with their ids (most overdue first). Ask the person each question, one at a time, and check the answer against the card.", schema("limit" to int("How many (default 8, at most 20)."), required = emptyList())))
            add(
                tool(
                    "cards_review",
                    "Record how the person answered a card, so that it comes back at the right time: again (wrong or forgot), hard (got it with effort), good, easy.",
                    schema("id" to str("The card's id from cards_due."), "result" to str("again, hard, good or easy."), required = listOf("id", "result")),
                ),
            )
            add(
                tool(
                    "study_remind",
                    "Set a daily (or weekly) reminder to study: at that time a notification says how many cards are due, and a tap opens them. Ask the person for the time first. With off=true the reminder is removed.",
                    schema("time" to str("HH:mm, 24-hour."), "days" to strList("mon..sun, or daily (default daily)."), "off" to JSONObject().put("type", "boolean").put("description", "true to remove the reminder."), required = emptyList()),
                ),
            )
        }
    }

    suspend fun run(name: String, args: JSONObject, report: ToolReport, chatId: String, project: String?): ToolOutcome {
        val info = project?.let(notebook)
        return when (name) {
            "library_search" -> {
                report.activity(ToolActivity(ToolKind.History, args.optString("query")))
                val found = Library.search(library(), args.optString("query")).take(args.optInt("limit", 8).coerceIn(1, 20))
                if (found.isEmpty()) ToolOutcome("The library has nothing for that.")
                else ToolOutcome(found.joinToString("\n") { e ->
                    "${e.id} | ${e.name} | ${e.kind.name.lowercase()}" + (if (e.summary.isNotBlank()) " | ${e.summary}" else "") +
                        (if (e.uses.isNotEmpty()) " | used in: " + e.uses.map { it.chatTitle }.distinct().take(3).joinToString("; ") else "") +
                        (if (e.notebooks.isNotEmpty()) " | notebook: " + e.notebooks.joinToString(", ") else "")
                })
            }
            "library_use" -> {
                val entry = library().firstOrNull { it.id == args.optString("id").trim() } ?: return ToolOutcome("There is no such file in the library.")
                val a = entry.attachment ?: return ToolOutcome("${entry.name} is a file you made yourself: it was handed over in that chat, there is nothing to read again.")
                report.activity(ToolActivity(ToolKind.File, entry.name))
                if (useFile(chatId, a)) ToolOutcome("${entry.name} is now among the files of this conversation: read it with the file tools by that name.")
                else ToolOutcome("${entry.name} is already among the files of this conversation.")
            }
            "study_plan" -> {
                if (info == null || !info.isStudy) return ToolOutcome("This is not a study notebook.")
                report.activity(ToolActivity(ToolKind.Memory, "plan"))
                val list = args.optJSONArray("lessons") ?: JSONArray()
                val titles = (0 until list.length()).map { list.optString(it) }
                if (titles.none { it.isNotBlank() }) return ToolOutcome("Give the lessons.")
                info.plan.set(titles)
                args.optString("goal").takeIf { it.isNotBlank() }?.let { info.goal = it.trim() }
                changed()
                ToolOutcome("The plan has ${info.plan.total} lessons. It is shown on the notebook's page.")
            }
            "study_progress" -> {
                if (info == null || !info.isStudy) return ToolOutcome("This is not a study notebook.")
                val lesson = info.plan.mark(args.optString("lesson"), args.optString("status"), args.optString("note").takeIf { it.isNotBlank() })
                    ?: return ToolOutcome("There is no such lesson. The plan has ${info.plan.total} lessons; use a number from 1.")
                report.activity(ToolActivity(ToolKind.Memory, lesson.title))
                if (lesson.status == Lesson.DONE) info.plan.log.record()
                changed()
                ToolOutcome("Lesson \"${lesson.title}\" is ${lesson.status}. ${info.plan.done} of ${info.plan.total} done.")
            }
            "cards_add" -> {
                if (info == null || !info.isStudy) return ToolOutcome("This is not a study notebook.")
                val list = args.optJSONArray("cards") ?: JSONArray()
                val pairs = (0 until list.length()).mapNotNull { i -> list.optJSONObject(i)?.let { it.optString("front") to it.optString("back") } }
                report.activity(ToolActivity(ToolKind.Memory, "cards"))
                val n = info.plan.addCards(pairs)
                changed()
                ToolOutcome(if (n == 0) "Nothing new: those cards exist or were empty." else "$n card(s) added; ${info.plan.cards.size} in all. They come up for review tomorrow.")
            }
            "cards_due" -> {
                if (info == null || !info.isStudy) return ToolOutcome("This is not a study notebook.")
                report.activity(ToolActivity(ToolKind.Memory, "cards"))
                val all = info.plan.due()
                val due = all.take(args.optInt("limit", 8).coerceIn(1, 20))
                ToolOutcome(if (due.isEmpty()) "No cards are due (${info.plan.cards.size} in all)." else "${all.size} due:\n" + due.joinToString("\n") { "${it.id} | Q: ${it.front} | A: ${it.back}" })
            }
            "cards_review" -> {
                if (info == null || !info.isStudy) return ToolOutcome("This is not a study notebook.")
                val card = info.plan.cards.firstOrNull { it.id == args.optString("id").trim() } ?: return ToolOutcome("There is no such card.")
                if (!card.review(args.optString("result"))) return ToolOutcome("The result must be again, hard, good or easy.")
                info.plan.log.record()
                changed()
                ToolOutcome("Noted. It comes back " + (if (card.interval == 0) "in a few minutes." else "in ${card.interval} day(s)."))
            }
            "study_remind" -> {
                if (info == null || !info.isStudy || project == null) return ToolOutcome("This is not a study notebook.")
                report.activity(ToolActivity(ToolKind.Memory, "reminder"))
                ToolOutcome(remind(project, args))
            }
            "project_update" -> {
                if (project == null) return ToolOutcome("This chat is not in a project or notebook.")
                report.activity(ToolActivity(ToolKind.Memory, args.optString("title").ifBlank { project }))
                ToolOutcome(update(project, args))
            }
            "space_create" -> {
                val kind = args.optString("kind").trim().lowercase().let { if (it in setOf("study", "ideas", "project")) it else "project" }
                val title = args.optString("title").trim()
                if (title.isEmpty()) return ToolOutcome("Give the name.")
                report.activity(ToolActivity(ToolKind.Memory, title))
                val (header, label) = askTexts(kind)
                val detail = listOfNotNull(
                    args.optString("description").takeIf { it.isNotBlank() },
                    args.optString("goal").takeIf { it.isNotBlank() && kind == "study" }?.let { "→ $it" },
                ).joinToString("\n")
                val decision = report.approve(ToolApproval("devx-space", header, "$label «$title»", detail, changesData = false, plain = true))
                if (decision == ApprovalDecision.Deny) return ToolOutcome("The person declined. Do not make it; ask what they would change (the name, the goal, the kind) and offer again.")
                ToolOutcome(create(chatId, args))
            }
            "source_add_sites" -> {
                if (info == null || !info.isNotebook) return ToolOutcome("This chat is not in a notebook.")
                val list = args.optJSONArray("urls") ?: JSONArray()
                val urls = (0 until list.length()).map { list.optString(it).trim() }.filter { it.startsWith("http") }.take(10)
                if (urls.isEmpty()) return ToolOutcome("Give full addresses starting with https://.")
                report.activity(ToolActivity(ToolKind.Read, urls.size.toString()))
                val before = info.sources.size
                val failed = addSites(project, urls)
                val added = info.sources.size - before
                ToolOutcome("Added $added source(s)" + (if (failed > 0) "; $failed page(s) could not be read (paid, behind a login, or down)" else "") + ". Each is being described; they are on the notebook's page.")
            }
            else -> ToolOutcome("Unknown tool: $name")
        }
    }

    companion object {
        /** The colours of a project, by name, in the order of [ProjectLook.colors]. */
        val PALETTE = listOf("grey", "red", "orange", "yellow", "green", "teal", "blue", "violet", "pink")
    }
}
