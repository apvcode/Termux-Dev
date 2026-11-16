package com.apvcode.devx.data

/** A notebook or a project written out as one Markdown file: what it is, its plan, cards, notes, overview and the sources it holds. */
object NotebookExport {
    class Labels(
        val goal: String,
        val instructions: String,
        val plan: String,
        val cards: String,
        val notes: String,
        val summary: String,
        val sources: String,
        val streak: String,
    )

    class Source(val name: String, val summary: String, val url: String)

    fun markdown(
        name: String,
        description: String,
        goal: String,
        instructions: String,
        lessons: List<Triple<String, String, String>>,
        cards: List<Pair<String, String>>,
        notes: List<String>,
        summary: String,
        sources: List<Source>,
        labels: Labels,
        date: String = "",
        streak: Int = 0,
    ): String = buildString {
        append("# ").append(name.trim()).append('\n')
        if (date.isNotBlank()) append("*").append(date).append("*\n")
        if (description.isNotBlank()) append('\n').append(description.trim()).append('\n')
        if (goal.isNotBlank()) append("\n## ").append(labels.goal).append("\n\n").append(goal.trim()).append('\n')
        if (streak > 0) append("\n").append(labels.streak.format(streak)).append('\n')
        if (lessons.isNotEmpty()) {
            append("\n## ").append(labels.plan).append("\n\n")
            lessons.forEachIndexed { i, (title, status, note) ->
                append(i + 1).append(". [").append(if (status == Lesson.DONE) "x" else " ").append("] ").append(title.trim())
                if (note.isNotBlank()) append(" - ").append(note.trim().replace('\n', ' '))
                append('\n')
            }
        }
        if (summary.isNotBlank()) append("\n## ").append(labels.summary).append("\n\n").append(summary.trim()).append('\n')
        if (notes.isNotEmpty()) {
            append("\n## ").append(labels.notes).append("\n\n")
            notes.forEach { append("- ").append(it.trim().replace('\n', ' ')).append('\n') }
        }
        if (cards.isNotEmpty()) {
            append("\n## ").append(labels.cards).append("\n\n")
            cards.forEach { (q, a) -> append("**").append(q.trim().replace('\n', ' ')).append("**\n").append(a.trim()).append("\n\n") }
        }
        if (sources.isNotEmpty()) {
            append("\n## ").append(labels.sources).append("\n\n")
            sources.forEach { s ->
                append("- ").append(if (s.url.isNotBlank()) "[${s.name}](${s.url})" else s.name)
                if (s.summary.isNotBlank()) append(": ").append(s.summary.trim().replace('\n', ' '))
                append('\n')
            }
        }
        if (instructions.isNotBlank()) append("\n## ").append(labels.instructions).append("\n\n").append(instructions.trim()).append('\n')
    }.trimEnd() + "\n"

    fun fileName(name: String): String = ChatExport.fileName(name, "md")
}
