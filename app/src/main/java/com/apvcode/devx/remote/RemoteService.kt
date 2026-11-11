package com.apvcode.devx.remote

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.apvcode.devx.AppState
import com.apvcode.devx.GenerationService
import com.apvcode.devx.MainActivity
import com.apvcode.devx.R
import com.apvcode.devx.data.NotificationText
import com.apvcode.devx.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Keeps the connection to the computers while the app is in the background, and tells the person when the agent
 * needs them (a permission, a question, a plan) or has finished. A quiet notification says that it runs.
 */
class RemoteService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val titles = HashMap<String, String>()
    private val watched = HashSet<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        GenerationService.ensureChannels(this)
        val names = RemoteHub.store.items.joinToString(", ") { it.name }
        val notification = NotificationCompat.Builder(this, GenerationService.CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_desktop)
            .setContentTitle(getString(R.string.remote_title))
            .setContentText(getString(R.string.remote_service_text, names))
            .setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(GenerationService.openApp(this, null))
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        runCatching { ServiceCompat.startForeground(this, ID, notification, type) }
        watch()
        return START_NOT_STICKY
    }

    private fun watch() {
        for (c in RemoteHub.store.items) {
            if (!watched.add(c.hostId)) continue
            val conn = RemoteHub.connection(c.hostId) ?: continue
            conn.start()
            scope.launch { conn.events.collect { onEvent(c.hostId, conn, it) } }
        }
    }

    private fun onEvent(hostId: String, conn: RemoteConnection, ev: SessionEvent) {
        val e = ev.event
        when (e.optString("e")) {
            "state" -> titles[ev.session] = e.optString("title")
            "perm" -> notify(hostId, conn, ev.session, getString(R.string.remote_notif_perm, e.optString("title")), e.optJSONArray("lines")?.optString(0).orEmpty(), urgent = true)
            "ask" -> notify(hostId, conn, ev.session, getString(R.string.remote_notif_ask), e.optJSONArray("questions")?.optJSONObject(0)?.optString("question").orEmpty(), urgent = true)
            "plan" -> notify(hostId, conn, ev.session, getString(R.string.remote_plan_ready), e.optString("summary"), urgent = true)
            "turn_end" -> if (!e.optBoolean("aborted")) notify(hostId, conn, ev.session, getString(R.string.remote_notif_done), "", urgent = false)
            "perm_done", "ask_done", "plan_done" -> NotificationManagerCompat.from(this).cancel(("r" + ev.session).hashCode())
        }
    }

    private fun notify(hostId: String, conn: RemoteConnection, session: String, title: String, text: String, urgent: Boolean) {
        // The screen of the app is in front: the cards are right there.
        if (AppState.resumed) return
        runCatching {
            val manager = NotificationManagerCompat.from(this)
            if (!manager.areNotificationsEnabled()) return
            val chat = titles[session]?.takeIf { it.isNotBlank() && it != "New Session" } ?: conn.computer.name
            val open = Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_REMOTE
                putExtra(MainActivity.EXTRA_HOST, hostId); putExtra(MainActivity.EXTRA_SESSION, session)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(this, session.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val body = NotificationText.short(NotificationText.plain(text).ifBlank { conn.computer.name }, 120)
            val n = NotificationCompat.Builder(this, GenerationService.CHANNEL_ANSWERS)
                .setSmallIcon(R.drawable.ic_desktop)
                .setContentTitle("$chat · $title")
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true).setContentIntent(pending)
                .build()
            @Suppress("MissingPermission")
            manager.notify(("r" + session).hashCode(), n)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ID = 7

        /** Starts the keep-alive when the app goes to the background, if the person wants it and a computer is paired. */
        fun startIfNeeded(context: Context) {
            if (!Prefs.remoteKeepAlive) return
            if (!RemoteHubReady.ready || RemoteHub.store.items.isEmpty()) return
            runCatching { androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, RemoteService::class.java)) }
        }

        fun stop(context: Context) { context.stopService(Intent(context, RemoteService::class.java)) }
    }
}

/** Whether RemoteHub.init has run (the service may start before it, after a restart of the process). */
object RemoteHubReady { @Volatile var ready = false }
