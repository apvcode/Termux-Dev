package com.apvcode.devx.data

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject

/**
 * The phone's screen brightness and Do Not Disturb. Android lets an app change them only after the person allows it once
 * in the phone's settings (a page for "change system settings", and one for "Do Not Disturb access"); until then the tool
 * opens that page and says so.
 */
class DeviceTools(private val context: Context, private val openPage: suspend (Intent, String) -> Boolean) {
    val names = setOf("get_brightness", "set_brightness", "do_not_disturb")

    fun definitions(): List<WireTool> = listOf(
        tool("get_brightness", "The screen's brightness now (percent) and whether it is automatic. Also says whether Devx is allowed to change it.", schema()),
        tool(
            "set_brightness",
            "Set the screen's brightness, or turn automatic brightness on. Needs a one-time permission that the person gives in the phone's settings (the tool opens that page the first time).",
            schema(
                "percent" to int("Brightness, 0 to 100 (0 is the dimmest the screen goes, not off)."),
                "auto" to phoneBool("true to switch to automatic brightness."),
                required = emptyList(),
            ),
        ),
        tool(
            "do_not_disturb",
            "Turn Do Not Disturb on or off, or ask its state. Modes: alarms (default), priority (what the phone's settings allow), total (nothing, not even alarms: only when asked for total silence). minutes makes it end by itself. Needs a one-time permission (the tool opens that page).",
            schema(
                "action" to str("\"on\", \"off\" or \"status\" (default on)."),
                "mode" to str("alarms, priority or total (default alarms)."),
                "minutes" to int("How long, in minutes (optional; without it it stays on until turned off)."),
                required = emptyList(),
            ),
        ),
    )

    suspend fun run(name: String, args: JSONObject, report: ToolReport): ToolOutcome {
        fun act(detail: String) = report.activity(ToolActivity(ToolKind.Phone, detail))
        return when (name) {
            "get_brightness" -> { act(""); ToolOutcome(brightnessInfo()) }
            "set_brightness" -> setBrightness(args, ::act)
            "do_not_disturb" -> dnd(args, ::act)
            else -> ToolOutcome("Unknown tool: $name")
        }
    }

    // ---------- brightness ----------

    private fun canWrite() = Settings.System.canWrite(context)

    private fun brightnessInfo(): String {
        val cr = context.contentResolver
        val value = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        val auto = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        return "Brightness ${(value * 100 / 255.0).toInt().coerceIn(0, 100)}%, automatic brightness is ${if (auto) "on" else "off"}. Devx is ${if (canWrite()) "allowed" else "not yet allowed"} to change it."
    }

    private suspend fun setBrightness(args: JSONObject, act: (String) -> Unit): ToolOutcome {
        val auto = args.optBoolean("auto", false)
        if (!auto && !args.has("percent")) return ToolOutcome("Give percent (0 to 100) or auto.")
        if (!canWrite()) {
            val opened = openPage(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.packageName)), "Brightness")
            return ToolOutcome(
                if (opened) "Devx is not allowed to change the brightness yet. The phone's page for it is open: tell the person to switch \"Allow modifying system settings\" on for Devx, and then ask again."
                else "Devx is not allowed to change the brightness yet. Tell the person to allow it: Settings, Apps, Devx, \"Modify system settings\" (a notification with the page was posted if they can tap it), and then ask again.",
            )
        }
        val cr = context.contentResolver
        return try {
            if (auto) {
                act("auto")
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                ToolOutcome("Automatic brightness is on.")
            } else {
                val percent = args.optInt("percent").coerceIn(0, 100)
                act("$percent%")
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (percent * 255 / 100).coerceIn(1, 255))
                ToolOutcome("The brightness is set to $percent%.")
            }
        } catch (e: SecurityException) {
            ToolOutcome("The phone refused to change the brightness.")
        }
    }

    // ---------- do not disturb ----------

    private fun manager() = context.getSystemService(NotificationManager::class.java)

    private suspend fun dnd(args: JSONObject, act: (String) -> Unit): ToolOutcome {
        val nm = manager()
        val action = args.optString("action", "on").lowercase().ifBlank { "on" }
        if (action == "status") {
            act("")
            val filter = nm.currentInterruptionFilter
            return ToolOutcome("Do Not Disturb is " + filterName(filter) + ". Devx is " + (if (nm.isNotificationPolicyAccessGranted) "allowed" else "not yet allowed") + " to change it." + (Dnd.until(context)?.let { " It ends by itself at ${java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalTime().toString().take(5)}." } ?: ""))
        }
        if (!nm.isNotificationPolicyAccessGranted) {
            val opened = openPage(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS), "Do Not Disturb")
            return ToolOutcome(
                if (opened) "Devx is not allowed to control Do Not Disturb yet. The phone's page for it is open: tell the person to allow Devx there, and then ask again."
                else "Devx is not allowed to control Do Not Disturb yet. Tell the person to allow it: Settings, Notifications, Do Not Disturb access, Devx, and then ask again.",
            )
        }
        if (action == "off") {
            act("off")
            Dnd.cancel(context)
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            return ToolOutcome("Do Not Disturb is off.")
        }
        val mode = args.optString("mode", "alarms").lowercase()
        val filter = when {
            mode.startsWith("pri") -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            mode.startsWith("tot") || mode == "none" -> NotificationManager.INTERRUPTION_FILTER_NONE
            else -> NotificationManager.INTERRUPTION_FILTER_ALARMS
        }
        val minutes = args.optInt("minutes", 0)
        act(filterName(filter) + if (minutes > 0) ", $minutes min" else "")
        nm.setInterruptionFilter(filter)
        if (minutes > 0) Dnd.endIn(context, minutes) else Dnd.cancel(context)
        return ToolOutcome("Do Not Disturb is on (${filterName(filter)})" + if (minutes > 0) ", and it ends by itself in $minutes minutes." else ", until it is turned off.")
    }

    private fun filterName(filter: Int) = when (filter) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> "off"
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "on (priority only)"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "on (alarms only)"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "on (total silence)"
        else -> "unknown"
    }

    companion object {
        fun promptSection(): String = """
## Screen and silence
set_brightness changes the screen's brightness (a percent, or automatic) and do_not_disturb turns Do Not Disturb on or off, for a while if asked ("silence the phone for an hour" is do_not_disturb with minutes 60). Both need a one-time permission: when a tool says it is not allowed yet, it has opened the phone's page for it, so tell the person in one sentence what to switch on there and to ask again. Use alarms-only mode by default, and total silence only when asked for it.
""".trim()
    }
}

/** Do Not Disturb that ends by itself: an alarm turns it off at the time (and again after a restart, if the time has passed). */
object Dnd {
    private const val PREFS = "dnd"
    private const val KEY = "until"
    const val ACTION = "com.apvcode.devx.DND_OFF"

    fun until(context: Context): Long? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY, 0L).takeIf { it > System.currentTimeMillis() }

    private fun pending(context: Context): PendingIntent =
        PendingIntent.getBroadcast(context, 30_001, Intent(context, DndReceiver::class.java).setAction(ACTION), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun endIn(context: Context, minutes: Int) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY, at).apply()
        schedule(context, at)
    }

    private fun schedule(context: Context, at: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val exact = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
        try {
            if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context)) else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context))
        } catch (e: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context))
        }
    }

    fun cancel(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        runCatching { context.getSystemService(AlarmManager::class.java).cancel(pending(context)) }
    }

    /** The time came (or the phone was restarted after it): Do Not Disturb goes off. */
    fun end(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.isNotificationPolicyAccessGranted) runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    fun restore(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong(KEY, 0L)
        if (at == 0L) return
        if (at <= System.currentTimeMillis()) end(context) else schedule(context, at)
    }
}

class DndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Dnd.end(context.applicationContext)
    }
}

private fun phoneBool(description: String): JSONObject = JSONObject().put("type", "boolean").put("description", description)
