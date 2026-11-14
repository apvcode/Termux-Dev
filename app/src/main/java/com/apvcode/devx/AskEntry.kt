package com.apvcode.devx

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews

/** The way into a fresh chat from outside the app: the intent the tile and the widget send. */
internal fun askIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_ASK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

/** A tile in the quick settings: pull the shade down, tap, and a new chat opens with the keyboard up. */
class AskTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.tile_label)
            updateTile()
        }
    }

    @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = askIntent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            startActivityAndCollapse(intent)
        }
    }
}

/** A tile that opens the voice assistant and starts listening. */
class VoiceTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.tile_voice_label)
            updateTile()
        }
    }

    @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = com.apvcode.devx.assist.AssistantActivity.intent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            startActivityAndCollapse(intent)
        }
    }
}

/** A bar on the home screen: tap it to ask devx something, or the white button to speak, without opening the app first. */
class AskWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_ask)
        views.setOnClickPendingIntent(R.id.widget_root, Widgets.open(context, MainActivity.ACTION_ASK, 0))
        views.setOnClickPendingIntent(R.id.widget_mic, Widgets.assistant(context, 1))
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    companion object {
        /** Used by the app to refresh widgets after a language change. */
        fun refresh(context: Context) = Widgets.refresh(context, AskWidget::class.java)
    }
}

/** Four big buttons: a new chat, voice, a temporary chat and the computers. */
class QuickWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_quick)
        views.setOnClickPendingIntent(R.id.q_chat, Widgets.open(context, MainActivity.ACTION_ASK, 10))
        views.setOnClickPendingIntent(R.id.q_voice, Widgets.assistant(context, 11))
        views.setOnClickPendingIntent(R.id.q_temp, Widgets.open(context, MainActivity.ACTION_TEMP, 12))
        views.setOnClickPendingIntent(R.id.q_computers, Widgets.open(context, MainActivity.ACTION_COMPUTERS, 13))
        ids.forEach { manager.updateAppWidget(it, views) }
    }
}

/** The latest chats: a tap opens one. Titles stay hidden while the app lock covers them. */
class RecentWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = Widgets.recentViews(context)
        ids.forEach { manager.updateAppWidget(it, views) }
    }
}

internal object Widgets {
    private val ROWS = intArrayOf(R.id.row1, R.id.row2, R.id.row3, R.id.row4)
    private val ICONS = intArrayOf(R.id.icon1, R.id.icon2, R.id.icon3, R.id.icon4)
    private val TITLES = intArrayOf(R.id.title1, R.id.title2, R.id.title3, R.id.title4)
    private val TIMES = intArrayOf(R.id.time1, R.id.time2, R.id.time3, R.id.time4)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pending: Runnable? = null

    fun open(context: Context, action: String, code: Int, extra: Pair<String, String>? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        extra?.let { intent.putExtra(it.first, it.second) }
        return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** The voice assistant's window (over whatever is open), with the microphone on. */
    fun assistant(context: Context, code: Int): PendingIntent =
        PendingIntent.getActivity(context, code, com.apvcode.devx.assist.AssistantActivity.intent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun refresh(context: Context, provider: Class<*>) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, provider))
        if (ids.isEmpty()) return
        val widget: AppWidgetProvider = when (provider) {
            RecentWidget::class.java -> RecentWidget()
            QuickWidget::class.java -> QuickWidget()
            else -> AskWidget()
        }
        widget.onUpdate(context, manager, ids)
    }

    /** Every widget that shows something of the app: after a change of the chats, the lock or the language. */
    fun refreshAll(context: Context) {
        val app = context.applicationContext
        listOf(AskWidget::class.java, QuickWidget::class.java, RecentWidget::class.java).forEach { refresh(app, it) }
    }

    /** Chats are written often while an answer streams: one refresh a little after the last write is enough. */
    fun refreshSoon(context: Context) {
        val app = context.applicationContext
        pending?.let { handler.removeCallbacks(it) }
        pending = Runnable { runCatching { refresh(app, RecentWidget::class.java) } }.also { handler.postDelayed(it, 1500) }
    }

    private class Row(val id: String, val title: String, val at: Long, val pinned: Boolean, val locked: Boolean)

    /** The newest chats, read straight from the files (the app may not be running). Only the few newest are opened. */
    private fun latest(context: Context): List<Row> {
        val dir = java.io.File(context.filesDir, "chats")
        val files = (dir.listFiles() ?: return emptyList()).filter { it.extension == "json" }.sortedByDescending { it.lastModified() }.take(12)
        return files.mapNotNull { f ->
            runCatching {
                val o = org.json.JSONObject(f.readText())
                if (o.optBoolean("archived")) return@runCatching null
                Row(o.getString("id"), o.optString("title"), o.optLong("updatedAt", f.lastModified()), o.optBoolean("pinned"), o.optBoolean("locked"))
            }.getOrNull()
        }.sortedWith(compareByDescending<Row> { it.pinned }.thenByDescending { it.at }).take(4)
    }

    private fun age(context: Context, at: Long): String {
        val minutes = ((System.currentTimeMillis() - at) / 60_000).coerceAtLeast(0)
        return when {
            minutes < 1 -> context.getString(R.string.widget_now)
            minutes < 60 -> context.getString(R.string.widget_min, minutes.toInt())
            minutes < 60 * 24 -> context.getString(R.string.widget_hour, (minutes / 60).toInt())
            else -> context.getString(R.string.widget_day, (minutes / (60 * 24)).toInt())
        }
    }

    fun recentViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_recent)
        views.setOnClickPendingIntent(R.id.widget_new, open(context, MainActivity.ACTION_ASK, 20))
        // With the lock on, what the chats are called is nobody's business on a home screen.
        // The widget can run with the app not started: the lock has to be read first. If that fails, hide.
        val lockKnown = runCatching { com.apvcode.devx.data.AppLock.init(context) }.isSuccess
        val hidden = !lockKnown || com.apvcode.devx.data.AppLock.locksApp
        val rows = if (hidden) emptyList() else latest(context)
        for (i in ROWS.indices) {
            val row = rows.getOrNull(i)
            views.setViewVisibility(ROWS[i], if (row == null) (if (rows.isEmpty()) android.view.View.GONE else android.view.View.INVISIBLE) else android.view.View.VISIBLE)
            if (row == null) continue
            val shown = !row.locked || !com.apvcode.devx.data.AppLock.locksChats
            views.setTextViewText(TITLES[i], if (shown) row.title.ifBlank { context.getString(R.string.chat_placeholder) } else "••••••••")
            views.setTextViewText(TIMES[i], age(context, row.at))
            views.setImageViewResource(ICONS[i], when { row.pinned -> R.drawable.ic_pin; !shown -> R.drawable.ic_lock; else -> R.drawable.ic_message_circle })
            views.setOnClickPendingIntent(ROWS[i], open(context, MainActivity.ACTION_OPEN_CHAT, 30 + i, MainActivity.EXTRA_CHAT_ID to row.id))
        }
        val note = when { hidden -> R.string.widget_locked; rows.isEmpty() -> R.string.widget_empty; else -> 0 }
        views.setViewVisibility(R.id.note_box, if (note == 0) android.view.View.GONE else android.view.View.VISIBLE)
        if (note != 0) {
            views.setTextViewText(R.id.note, context.getString(note))
            views.setImageViewResource(R.id.note_icon, if (hidden) R.drawable.ic_lock else R.drawable.ic_message_circle)
        }
        return views
    }
}
