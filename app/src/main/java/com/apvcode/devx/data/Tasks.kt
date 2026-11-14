package com.apvcode.devx.data

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.apvcode.devx.GenerationService
import com.apvcode.devx.ui.chat.SharedChat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * A job the assistant does by itself at a set time ("every morning find the news about AI"): a prompt, a time, the days,
 * and the model to ask (none: the one that is chosen in the app then). Every run is an ordinary chat.
 */
class ScheduledTask(
    val id: Int,
    var title: String,
    var prompt: String,
    var hour: Int,
    var minute: Int,
    /** Bits 0..6 = Monday..Sunday. */
    var days: Int,
    var model: ModelRef?,
    var enabled: Boolean,
    var lastRun: Long = 0L,
    var lastChatId: String? = null,
) {
    val daily: Boolean get() = days and ALL == ALL

    companion object {
        const val ALL = 0b1111111
    }
}

object Tasks {
    private const val FILE = "tasks.json"
    const val ACTION_FIRE = "com.apvcode.devx.TASK"
    const val EXTRA_ID = "id"
    private val lock = Any()

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun all(context: Context): List<ScheduledTask> = synchronized(lock) { read(context) }

    fun get(context: Context, id: Int): ScheduledTask? = all(context).firstOrNull { it.id == id }

    private fun read(context: Context): List<ScheduledTask> = runCatching {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val array = JSONArray(f.readText())
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            ScheduledTask(
                id = o.getInt("id"),
                title = o.optString("title"),
                prompt = o.optString("prompt"),
                hour = o.optInt("hour", 8),
                minute = o.optInt("minute", 0),
                days = o.optInt("days", ScheduledTask.ALL),
                model = o.optJSONObject("model")?.let { ModelRef(it.getString("p"), it.getString("m")) },
                enabled = o.optBoolean("enabled", true),
                lastRun = o.optLong("lastRun", 0L),
                lastChatId = o.optString("lastChat").takeIf { it.isNotEmpty() },
            )
        }
    }.getOrDefault(emptyList())

    private fun write(context: Context, list: List<ScheduledTask>) {
        val array = JSONArray()
        list.forEach { t ->
            val o = JSONObject().put("id", t.id).put("title", t.title).put("prompt", t.prompt).put("hour", t.hour).put("minute", t.minute)
                .put("days", t.days).put("enabled", t.enabled).put("lastRun", t.lastRun)
            t.model?.let { o.put("model", JSONObject().put("p", it.providerId).put("m", it.model)) }
            t.lastChatId?.let { o.put("lastChat", it) }
            array.put(o)
        }
        file(context).writeText(array.toString())
    }

    /** Saves a new task (its id is made here) and sets its alarm. */
    fun add(context: Context, title: String, prompt: String, hour: Int, minute: Int, days: Int, model: ModelRef?): ScheduledTask {
        val task: ScheduledTask
        synchronized(lock) {
            val list = read(context)
            task = ScheduledTask((list.maxOfOrNull { it.id } ?: 0) + 1, title.trim(), prompt.trim(), hour, minute, days.takeIf { it and ScheduledTask.ALL != 0 } ?: ScheduledTask.ALL, model, true)
            write(context, list + task)
        }
        schedule(context, task)
        return task
    }

    /** Writes changes of a task (it is looked up by its id) and sets its alarm again. */
    fun update(context: Context, task: ScheduledTask) {
        synchronized(lock) {
            val list = read(context).map { if (it.id == task.id) task else it }
            write(context, list)
        }
        schedule(context, task)
    }

    fun remove(context: Context, id: Int) {
        synchronized(lock) { write(context, read(context).filter { it.id != id }) }
        unschedule(context, id)
    }

    private fun markRun(context: Context, id: Int, chatId: String?) {
        synchronized(lock) {
            val list = read(context)
            list.firstOrNull { it.id == id }?.let {
                it.lastRun = System.currentTimeMillis()
                it.lastChatId = chatId
                write(context, list)
            }
        }
    }

    /** The next moment this task is due after [after]; null when it has no day. */
    fun nextRun(task: ScheduledTask, after: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (task.days and ScheduledTask.ALL == 0) return null
        val start = java.time.Instant.ofEpochMilli(after).atZone(zone).toLocalDate()
        for (offset in 0..7) {
            val day = start.plusDays(offset.toLong())
            if (task.days and (1 shl (day.dayOfWeek.value - 1)) == 0) continue
            val moment = LocalDateTime.of(day, LocalTime.of(task.hour, task.minute)).atZone(zone).toInstant().toEpochMilli()
            if (moment > after) return moment
        }
        return null
    }

    private fun pending(context: Context, id: Int): PendingIntent {
        val intent = Intent(context, TaskReceiver::class.java).setAction(ACTION_FIRE).setData(Uri.parse("devx-task://$id")).putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(context, 20_000 + id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun schedule(context: Context, task: ScheduledTask) {
        unschedule(context, task.id)
        if (!task.enabled) return
        val at = nextRun(task) ?: return
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pi = pending(context, task.id)
        val exact = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
        try {
            if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun unschedule(context: Context, id: Int) {
        runCatching { context.getSystemService(AlarmManager::class.java).cancel(pending(context, id)) }
    }

    /** After a restart or an update the alarms are gone: every enabled task gets its next one. */
    fun restoreAll(context: Context) {
        all(context).forEach { schedule(context, it) }
    }

    // ---------- running ----------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The alarm went off: the next one is set at once, and the task runs. */
    fun fire(context: Context, id: Int) {
        val app = context.applicationContext
        val task = get(app, id) ?: return
        if (!task.enabled) return
        schedule(app, task)
        GenerationService.start(app)
        scope.launch { runOnce(app, task) }
    }

    /** Runs it now (a tap on "run now", or the assistant asked): its chat is made and filled in the background. */
    fun runNow(context: Context, id: Int) {
        val app = context.applicationContext
        val task = get(app, id) ?: return
        GenerationService.start(app)
        scope.launch { runOnce(app, task) }
    }

    private suspend fun runOnce(app: Context, task: ScheduledTask) {
        val user = "task-${task.id}-${System.nanoTime()}"
        val vm = SharedChat.acquire(app as? Application ?: app.applicationContext as Application, user)
        try {
            withTimeoutOrNull(60_000) { androidx.compose.runtime.snapshotFlow { vm.loaded }.first { it } } ?: return
            val chatId = vm.startBackgroundChat(task.title, task.prompt, task.model) ?: return
            markRun(app, task.id, chatId)
            // Up to a quarter of an hour; a task that runs longer is stopped.
            val finished = withTimeoutOrNull(15 * 60_000L) {
                while (vm.isRunning(chatId)) delay(500)
                true
            }
            if (finished == null) vm.stopChat(chatId)
            // The chat is written to the disk a moment after the last word.
            delay(2_500)
        } finally {
            SharedChat.release(user)
            if (SharedChat.idle) GenerationService.stop(app)
        }
    }

    // ---------- words ----------

    private val DAY_NAMES = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

    fun daysFrom(names: List<String>): Int {
        var mask = 0
        for (n in names) {
            val key = n.trim().lowercase().take(3)
            val i = DAY_NAMES.indexOf(key)
            if (i >= 0) mask = mask or (1 shl i)
            else if (key == "dai" || key == "eve" || key == "all") return ScheduledTask.ALL
            else if (key == "wee") mask = mask or 0b0011111
            else if (key == "wen") mask = mask or 0b1100000
        }
        return mask
    }

    fun daysText(days: Int): String = when {
        days and ScheduledTask.ALL == ScheduledTask.ALL -> "every day"
        days and ScheduledTask.ALL == 0b0011111 -> "on weekdays"
        days and ScheduledTask.ALL == 0b1100000 -> "on weekends"
        else -> "on " + DAY_NAMES.indices.filter { days and (1 shl it) != 0 }.joinToString(", ") { DayOfWeek.of(it + 1).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) }
    }
}

/** Wakes at a task's time, and sets the alarms again after a restart. */
class TaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            Tasks.ACTION_FIRE -> Tasks.fire(app, intent.getIntExtra(Tasks.EXTRA_ID, -1))
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> { Tasks.restoreAll(app); Dnd.restore(app) }
        }
    }
}
