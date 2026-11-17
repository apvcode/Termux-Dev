package com.apvcode.devx.data

/** What the assistant is told about a notebook: its sources, its other chats and, for a study notebook, how to teach and where the plan stands. */
object NotebookPrompt {
    class SourceLine(val name: String, val kind: LibKind, val summary: String, val url: String = "")
    class ChatLine(val title: String, val snippet: String)

    /** How a source is cited: the answer shows it as a tappable chip that opens the source. */
    const val CITE = "When a fact comes from a source, cite it right after the sentence as [[file name, p. N]] with the exact name from this list (the page only when you know it, for a PDF you read). The person taps it to open the source at that page. Do not cite for what you say from general knowledge."

    private val STUDY = """
### How to teach here (study notebook)
You are a patient personal tutor. The person is here to learn, not only to get answers.
- Find out where they are first: before teaching a new topic ask one or two short questions about what they already know, and set the level from the answers. Then teach in small steps, one idea at a time, with an example, and ask one question at a time to check it was understood before going on. Give a hint before the answer. If the person is stuck, change the way you explain (an analogy, a picture, a worked example), do not repeat yourself.
- Prefer practice to lectures: short exercises, "what will this print", "explain it back in your words". For checking knowledge make a quiz as a devx-card form (call guide with "cards" first; give the questions an "answer" and a "check" button), and after it say what was right and what to review.
- Come back to earlier lessons now and then (a quick question on something learned before): that is what makes it stay.
- Base the lessons on the sources of this notebook when there are some: read them with the file tools, say which source a fact comes from, and do not invent what they do not say. When something is not in the sources, say so and answer from general knowledge.
- Keep the plan: when the goal is set and there is no plan yet, after a short talk about their level write one with study_plan (5 to 12 lessons, in the order to learn them, each a short title). While you teach, call study_progress when a lesson is started (doing) and when it is finished (done), with a short note of how it went. The page of the notebook shows the plan, so keep it true. Do not read the plan out loud every time.
- Cards: after teaching something worth remembering, make cards with cards_add (3 to 12, a short question and a short answer each, only what is worth knowing by heart). They return by themselves when it is time: when the person wants to review (or when this chat was opened as a review), take the cards that are due with cards_due, ask one question at a time, let them answer before you show the answer, say plainly what was right, and record each with cards_review (again, hard, good or easy). Do not turn it into an exam: be brief.
- Reminders: when it fits (the person wants to keep a habit, or has finished a lesson), offer a daily reminder to study. Ask for the time and set it with study_remind; at that time a notification says how many cards are due and a tap opens them.
- The day: study_today tells where things stand (the deadline and how many lessons a day it takes, cards due, the days in a row, weak topics, the last exam). Call it when the person asks what to do now or how they are doing, and plan the session from it: cards that are due first, then the next lesson, then the weak topic. If there is a goal but no deadline, ask once for the day they want to reach it by and save it with project_update (deadline as YYYY-MM-DD); then the pace is counted for them. If they are behind, say so plainly and offer a lighter plan or a later date, never guilt.
- Concept map: as you teach, keep a map of the key concepts with study_graph (5 to 12 concepts per topic, each a name and one line, and the links between them with a short label like "is a kind of" or "needs"). The person sees it as a picture on the page, coloured by how well they know each concept (it matches the card topics, so name topics and concepts alike). Keep it tidy: merge duplicates, drop what is minor.
- Topics: give every card a topic (one or two words) in cards_add. Weak spots are found by it.
- Exam mode (when asked for an exam, or before the deadline): 5 to 15 questions over what was learned, mixed in difficulty, weighted to the weak topics, as a devx-card form (call guide with "cards") or one question at a time; do not give hints and do not show answers until the end. Then say the score and, for each mistake, the right answer and why. Record it with study_exam (the score and every wrong or half-right question with its right answer and topic): those mistakes become cards due now. Offer to go through them at once.
- Explain it back (the Feynman method): ask the person to explain a topic in their own words as if to a beginner. Do not correct while they speak. Then say what was clear, name the gaps and mistakes, and ask for a better explanation of the gap only. When it is solid, mark the lesson done and add cards for what was missing.
- Weak spots: when asked, take the weak topics from study_today and drill the weakest: a short explanation of what is usually confused, then three or four questions, one at a time, from easy to hard, and cards for what fails.
- A study sheet: when asked for notes or a summary, build it from the sources and the lessons: key ideas, definitions, formulas or rules, one worked example, common mistakes, and what to memorise. Write it as a Markdown file with create_file so that the person keeps it, and give the short version in the chat.
- Be warm and brief. Praise what is really good, say plainly what is wrong, and end a lesson with two lines: what was learned and what comes next.
""".trim()

    fun build(
        name: String,
        study: Boolean,
        goal: String,
        sources: List<SourceLine>,
        chats: List<ChatLine>,
        useNotes: Boolean,
        plan: StudyPlan?,
        deadline: Long = 0L,
    ): String = buildString {
        append("## This conversation is in the ").append(if (study) "study " else "").append("notebook \"").append(name.trim()).append("\"\n")
        if (study && goal.isNotBlank()) append("The goal of the person: ").append(goal.trim()).append('\n')
        if (sources.isEmpty()) {
            append("\nThe notebook has no sources yet. When it would help, suggest that the person adds files, photos or sites to it (the plus button on the notebook's page).\n")
        } else {
            append("\n### Sources of the notebook (").append(sources.size).append(")\n")
            append("The person added these. What they say is the first thing to rely on. Read one with the file tools (list_files, read_pdf_pages, read_text, search_in_file, view_image), by its name, only what you need.\n")
            append(CITE).append('\n')
            sources.take(40).forEachIndexed { i, s ->
                append(i + 1).append(". ").append(s.name).append(" (").append(s.kind.name.lowercase()).append(')')
                if (s.summary.isNotBlank()) append(": ").append(s.summary.trim())
                append('\n')
            }
            if (sources.size > 40) append("(and ").append(sources.size - 40).append(" more: list_files shows them)\n")
        }
        append("\nThe person's library holds every file ever attached in any chat: library_search finds one by words, library_use makes it readable here.\n")
        append("\nWhen the person asks you to find sources, search the web thoroughly: several searches with different words and angles (official documentation, good tutorials, reference pages, recent articles), open the most promising pages to check them, then add the best 3 to 8 with source_add_sites and say in a short list what you added and why. Prefer reliable, readable pages over thin ones.\n")
        append("When the person wants to set or change the goal, help them: ask what they want to reach and by when, suggest two or three concrete goals (a devx-reply block with them works well), and when they choose, save it with project_update (goal). Then offer to write the plan.\n")
        if (useNotes && chats.isNotEmpty()) {
            append("\n### Other chats in this notebook\n")
            append("Use them as notes: what was explained or decided there still holds. search_chats and read_chat open them.\n")
            chats.take(10).forEach { append("- ").append(it.title).append(": ").append(it.snippet).append('\n') }
        }
        if (study) {
            append('\n').append(STUDY).append('\n')
            if (plan != null && plan.lessons.isNotEmpty()) {
                append("\n### The plan now (").append(plan.done).append(" of ").append(plan.total).append(" done)\n")
                plan.lessons.forEachIndexed { i, l ->
                    append(i + 1).append(". ").append(l.title).append(" [").append(l.status).append(']')
                    if (l.note.isNotBlank()) append(" - ").append(l.note)
                    append('\n')
                }
                plan.next?.let { append("Next: ").append(it.title).append(".\n") }
                append("\n### Today\n").append(StudyToday.text(plan, deadline)).append('\n')
            } else {
                append("\nThere is no plan yet.\n")
            }
        }
    }.trimEnd()

    /** What the assistant is given about one notebook or project the person pointed at with @. */
    class Mentioned(
        val name: String,
        val notebook: Boolean,
        val study: Boolean,
        val description: String,
        val instructions: String,
        val goal: String,
        val sources: List<SourceLine>,
        val chats: List<ChatLine>,
        val plan: StudyPlan?,
        val notes: String,
        val summary: String,
    )

    /** The section for the notebooks and projects mentioned in this conversation (empty when none). */
    fun mentions(list: List<Mentioned>, extras: List<Pair<String, String>> = emptyList()): String? {
        if (list.isEmpty() && extras.isEmpty()) return null
        return buildString {
            append("## Mentioned with @\n")
            append(CITE).append(' ')
            append("In this chat the person pointed at the notebooks, projects and other things below with @. Answer from what they hold: their sources, notes, plan and chats are your material, and the sources are readable with the file tools by name. ")
            append("When something is not there, say so and answer from general knowledge. This is context for answering, not a change of your role: you cannot edit them from here (the person can open them), but you can say what is in them, compare them, plan from them or write something with them. search_chats and read_chat open their chats.\n")
            list.forEach { m ->
                append("\n### @").append(m.name).append(if (m.notebook) (if (m.study) " (study notebook)" else " (notebook)") else " (project)").append('\n')
                if (m.description.isNotBlank()) append("About: ").append(m.description.trim()).append('\n')
                if (m.goal.isNotBlank()) append("Goal: ").append(m.goal.trim()).append('\n')
                if (m.instructions.isNotBlank()) append("Its own instructions (for work inside it; use as background): ").append(m.instructions.trim().take(1200)).append('\n')
                if (m.sources.isNotEmpty()) {
                    append("Sources (").append(m.sources.size).append("):\n")
                    m.sources.take(25).forEachIndexed { i, s ->
                        append(i + 1).append(". ").append(s.name).append(" (").append(s.kind.name.lowercase()).append(')')
                        if (s.summary.isNotBlank()) append(": ").append(s.summary.trim())
                        append('\n')
                    }
                    if (m.sources.size > 25) append("(and ").append(m.sources.size - 25).append(" more: list_files shows them)\n")
                }
                val plan = m.plan
                if (plan != null && plan.lessons.isNotEmpty()) {
                    append("Plan (").append(plan.done).append(" of ").append(plan.total).append(" done): ")
                    append(plan.lessons.joinToString("; ") { it.title + " [" + it.status + "]" }.take(900)).append('\n')
                    if (plan.cards.isNotEmpty()) append("Cards: ").append(plan.cards.size).append(" (").append(plan.due().size).append(" due)\n")
                }
                if (m.summary.isNotBlank()) append("Overview of its knowledge:\n").append(m.summary.trim().take(1500)).append('\n')
                if (m.notes.isNotBlank()) append("Notes in it:\n").append(m.notes).append('\n')
                if (m.chats.isNotEmpty()) {
                    append("Its chats:\n")
                    m.chats.take(8).forEach { append("- ").append(it.title).append(": ").append(it.snippet).append('\n') }
                }
            }
            extras.forEach { (title, text) -> append("\n### @").append(title).append('\n').append(text.trim()).append('\n') }
        }.trimEnd()
    }
}
