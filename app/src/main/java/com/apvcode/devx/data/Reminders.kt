package com.apvcode.devx.data

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.apvcode.devx.GenerationService
import com.apvcode.devx.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One reminder the assistant set: a moment and the words to show then. */
class Reminder(val id: Int, val at: Long, val text: String)

/**
 * Reminders that the assistant sets for the person ("remind me in an hour"). They live in a small file, are woken by the
 * system's alarm clock (so they come on time with the phone asleep) and are set again after a restart.
 */
object Reminders {
    private const val FILE = "reminders.json"
    private const val CHANNEL = "reminders"
    const val ACTION_FIRE = "com.apvcode.devx.REMINDER"
    const val EXTRA_ID = "id"
    private val lock = Any()

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun all(context: Context): List<Reminder> = synchronized(lock) { read(context) }.sortedBy { it.at }

    private fun read(context: Context): List<Reminder> = runCatching {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val array = JSONArray(f.readText())
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Reminder(o.getInt("id"), o.getLong("at"), o.optString("text"))
        }
    }.getOrDefault(emptyList())

    private fun write(context: Context, list: List<Reminder>) {
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("id", it.id).put("at", it.at).put("text", it.text)) }
        file(context).writeText(array.toString())
    }

    /** Saves a reminder and sets its alarm. Returns it, and whether the alarm is exact (false: the phone may be a few minutes late). */
    fun add(context: Context, at: Long, text: String): Pair<Reminder, Boolean> {
        val reminder: Reminder
        synchronized(lock) {
            val list = read(context)
            reminder = Reminder((list.maxOfOrNull { it.id } ?: 0) + 1, at, text.trim())
            write(context, list + reminder)
        }
        return reminder to schedule(context, reminder)
    }

    /** Takes a reminder away (and its alarm); the ones removed. [key] is an id, or words from the text. */
    fun cancel(context: Context, key: String): List<Reminder> {
        val removed: List<Reminder>
        synchronized(lock) {
            val list = read(context)
            val id = key.trim().removePrefix("#").toIntOrNull()
            val words = key.trim().lowercase()
            removed = when {
                key.trim().equals("all", true) -> list
                id != null && list.any { it.id == id } -> list.filter { it.id == id }
                words.isNotEmpty() -> list.filter { it.text.lowercase().contains(words) }
                else -> emptyList()
            }
            if (removed.isNotEmpty()) write(context, list - removed.toSet())
        }
        removed.forEach { unschedule(context, it.id) }
        return removed
    }

    private fun pending(context: Context, id: Int): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_FIRE)
            .setData(Uri.parse("devx-reminder://$id"))
            .putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun schedule(context: Context, r: Reminder): Boolean {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pi = pending(context, r.id)
        val exact = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
        return try {
            if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
            exact
        } catch (e: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
            false
        }
    }

    private fun unschedule(context: Context, id: Int) {
        runCatching { context.getSystemService(AlarmManager::class.java).cancel(pending(context, id)) }
    }

    /** After a restart or an update the alarms are gone: set them again; what was missed in the meantime is shown now. */
    fun restore(context: Context) {
        val now = System.currentTimeMillis()
        for (r in all(context)) {
            if (r.at <= now) fire(context, r.id) else schedule(context, r)
        }
    }

    /** Shows the reminder as a notification and forgets it. */
    fun fire(context: Context, id: Int) {
        val reminder: Reminder?
        synchronized(lock) {
            val list = read(context)
            reminder = list.firstOrNull { it.id == id }
            if (reminder != null) write(context, list - reminder)
        }
        if (reminder == null) return
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_HIGH),
                )
            }
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_message_circle)
                .setContentTitle(context.getString(R.string.reminder_title))
                .setContentText(reminder.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.text))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(GenerationService.openApp(context, null))
                .build()
            if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                @Suppress("MissingPermission")
                NotificationManagerCompat.from(context).notify(NOTIFY_BASE + id, notification)
            }
        }
    }

    private const val NOTIFY_BASE = 7_000
}

/** Wakes at the reminder's time, and after a restart sets the reminders again. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            Reminders.ACTION_FIRE -> Reminders.fire(app, intent.getIntExtra(Reminders.EXTRA_ID, -1))
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Reminders.restore(app)
        }
    }
}
