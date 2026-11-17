package com.apvcode.devx.data

import java.time.LocalDate
import java.util.Locale

/** Where a study notebook stands today, in words for the assistant (the tool's answer and a part of the notebook's prompt). */
object StudyToday {
    fun text(plan: StudyPlan, deadline: Long, today: Int = StudyLog.today(), now: Long = System.currentTimeMillis()): String = buildString {
        val left = plan.total - plan.done
        val pace = Pace.of(deadline, left, today)
        when {
            pace == null -> append("No deadline is set (when it fits, ask for the day and save it with project_update: deadline).\n")
            pace.overdue -> append("The deadline (").append(LocalDate.ofEpochDay(deadline)).append(") passed ").append(-pace.daysLeft).append(" day(s) ago; ").append(left).append(" lesson(s) are left. Offer to move it.\n")
            left == 0 -> append("Deadline ").append(LocalDate.ofEpochDay(deadline)).append(" (in ").append(pace.daysLeft).append(" day(s)): every lesson is done, time to review.\n")
            else -> append("Deadline ").append(LocalDate.ofEpochDay(deadline)).append(" (in ").append(pace.daysLeft).append(" day(s)); ").append(left)
                .append(" lesson(s) left: about ").append(number(pace.perDay)).append(" a day to be in time.\n")
        }
        if (pace != null && plan.hasHistory(today)) {
            val rate = plan.doneSince(7, today) / 7.0
            if (pace.behind(rate)) append("BEHIND THE PACE: ").append(number(rate)).append(" lesson(s) a day over the last week, ").append(number(pace.perDay))
                .append(" are needed. Say it plainly and kindly, then offer: fewer or merged lessons (study_plan), a later deadline, or extra time on the days that are free. Keep the last days before the deadline for review and an exam.\n")
            else if (pace.lessonsLeft > 0 && !pace.overdue) append("On the pace: ").append(number(rate)).append(" lesson(s) a day over the last week.\n")
        }
        plan.next?.let { append("Next lesson: ").append(it.title).append(" [").append(it.status).append("].\n") }
        append("Cards due now: ").append(plan.due(now).size).append(" of ").append(plan.cards.size).append(".\n")
        val streak = plan.log.streak(today)
        append("Days in a row: ").append(streak).append(if (plan.log.days[today] ?: 0 > 0) " (studied today).\n" else " (nothing yet today).\n")
        val weak = plan.weakSpots()
        append("Weak spots: ").append(if (weak.isEmpty()) "none known yet." else weak.joinToString("; ") { "${it.topic} (${it.score})" } + ".").append('\n')
        plan.exams.lastOrNull()?.let { e ->
            append("Last exam: ").append(e.title.ifBlank { "exam" }).append(", ").append(e.correct).append(" of ").append(e.total).append(" (").append(e.percent).append("%)")
            if (e.weak.isNotEmpty()) append(", weak: ").append(e.weak.joinToString(", "))
            append(".\n")
        } ?: append("No exam has been given yet.\n")
    }.trimEnd()

    private fun number(d: Double): String = String.format(Locale.US, "%.1f", d).removeSuffix(".0")
}
