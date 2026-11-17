package com.apvcode.devx.remote

import com.apvcode.devx.data.attempt
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.BroadcastReceiver
import androidx.core.content.ContextCompat

/**
 * Keeps the connection to the computer the person is in (its list of chats, or one chat) while the app is in the background,
 * and tells them when the agent needs them (a permission, a question, a plan) or has finished. A quiet notification says
 * "connected to <computer>" and has a button to disconnect. It exists only while that connection does: it is started when the
 * person opens a computer, and gone when they leave it, disconnect, or close the app.
 */
class RemoteService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val titles = HashMap<String, String>()
    private var watching: String? = null
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val hostId = intent?.getStringExtra(EXTRA_HOST) ?: RemoteLink.host
        val computer = hostId?.let { RemoteHub.store.find(it) }
        if (hostId == null || computer == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        GenerationService.ensureChannels(this)
        val disconnect = PendingIntent.getBroadcast(
            this, 0,
            Intent(this, RemoteDisconnectReceiver::class.java).setAction(RemoteDisconnectReceiver.ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, GenerationService.CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_desktop)
            .setContentTitle(getString(R.string.remote_title))
            .setContentText(getString(R.string.remote_service_text, computer.name))
            .setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(GenerationService.openApp(this, null))
            .addAction(0, getString(R.string.remote_disconnect), disconnect)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        attempt("remoteservice") { ServiceCompat.startForeground(this, ID, notification, type) }
        watch(hostId)
        return START_NOT_STICKY
    }

    /** The person swiped the app away: nothing of it keeps running. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        RemoteLink.disconnect(this)
        RemoteHub.stopAll()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun watch(hostId: String) {
        if (watching == hostId) return
        watcher?.cancel()
        watching = hostId
        val conn = RemoteHub.connection(hostId) ?: return
        conn.start()
        watcher = scope.launch { conn.events.collect { onEvent(hostId, conn, it) } }
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
        attempt("remoteservice") {
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
        const val EXTRA_HOST = "host"

        /** Starts the keep-alive (and its notification) for the computer the person has just opened. Returns whether it was started. */
        fun startFor(context: Context, hostId: String): Boolean {
            if (!Prefs.remoteKeepAlive) return false
            if (!RemoteHubReady.ready || RemoteHub.store.find(hostId) == null) return false
            return runCatching {
                ContextCompat.startForegroundService(context, Intent(context, RemoteService::class.java).putExtra(EXTRA_HOST, hostId))
            }.isSuccess
        }

        fun stop(context: Context) { context.stopService(Intent(context, RemoteService::class.java)) }
    }
}

/**
 * Which computer the person is connected to right now: the one whose list of chats (or a chat) is open. Only then is a
 * connection held in the background, and only then is there a notification. Leaving it, pressing "Disconnect" or closing the
 * app ends it.
 */
object RemoteLink {
    @Volatile
    var host: String? = null
        private set

    /** The notification's "Disconnect" was pressed: the screen of the computer leaves (it counts the presses). */
    var dropped by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    /** The computer the person disconnected by hand: the list of computers does not reconnect it by itself. */
    var offHost by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set

    /** The person opened a computer. */
    fun connect(context: Context, hostId: String) {
        if (offHost == hostId) offHost = null
        if (host == hostId) return
        host = hostId
        // The others that were reached (the list of computers tries them all) are let go.
        RemoteHub.stopOthers(hostId)
        RemoteHub.connection(hostId)?.let { it.start(); it.nudge() }
        if (!RemoteService.startFor(context, hostId)) RemoteService.stop(context)
    }

    /**
     * The person left the computer's own screens: no notification, and nothing is held in the background. The connection itself is
     * not cut here (the list of computers shows it); it ends when the whole remote section is left, or the app goes away.
     */
    fun disconnect(context: Context) {
        host = null
        RemoteService.stop(context)
    }

    /** "Disconnect" in the notification: that computer is cut off and stays so until it is opened again. */
    fun disconnectByPerson(context: Context) {
        val had = host
        disconnect(context)
        if (had != null) {
            offHost = had
            RemoteHub.connection(had)?.stop()
        }
        dropped++
    }

    /** True while a computer is held in the background by the keep-alive service. */
    val held: Boolean get() = host != null && Prefs.remoteKeepAlive
}

/** The "Disconnect" button of the notification. */
class RemoteDisconnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION) RemoteLink.disconnectByPerson(context.applicationContext)
    }

    companion object { const val ACTION = "com.apvcode.devx.REMOTE_DISCONNECT" }
}

/** Whether RemoteHub.init has run (the service may start before it, after a restart of the process). */
object RemoteHubReady { @Volatile var ready = false }
