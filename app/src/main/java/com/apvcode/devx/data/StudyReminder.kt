package com.apvcode.devx.data

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.apvcode.devx.GenerationService
import com.apvcode.devx.MainActivity
import com.apvcode.devx.R

/**
 * The reminder to study: at its time a notification says how many cards are due in the notebook; a tap opens the cards. No chat is made
 * and nothing is asked of the model, so it costs nothing; when no card is due, nothing is shown.
 */
object StudyReminder {
    const val MARK = "[study:"

    fun isStudyTask(t: ScheduledTask) = t.project != null && t.prompt.startsWith(MARK)

    fun fire(context: Context, project: String) {
        val due = StudyDue.read(context)[project] ?: return
        runCatching {
            GenerationService.ensureChannels(context)
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            val text = context.resources.getQuantityString(R.plurals.nb_cards_due, due, due)
            val notification = NotificationCompat.Builder(context, GenerationService.CHANNEL_ANSWERS)
                .setSmallIcon(R.drawable.ic_message_circle)
                .setContentTitle(project)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(com.apvcode.devx.Widgets.open(context, MainActivity.ACTION_STUDY, ("study-$project").hashCode(), "project" to project))
                .build()
            @Suppress("MissingPermission")
            manager.notify(("study-$project").hashCode(), notification)
        }
    }
}
