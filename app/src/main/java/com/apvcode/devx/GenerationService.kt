package com.apvcode.devx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.app.NotificationManagerCompat

/** Whether the app is on screen; the answer that finishes while it is not is announced by a notification. */
object AppState {
    @Volatile
    var foreground = false

    /** The screen of the app is in front of you right now (false the moment you leave it, even for a second). */
    @Volatile
    var resumed = false
}

/**
 * Keeps the app alive while an answer is being written. The work itself stays in the view model; this
 * service only makes Android treat the app as busy: it is not frozen or killed when you switch to
 * another app or turn the screen off, and the connection to the provider stays up.
 */
class GenerationService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannels(this)
        val notification = NotificationCompat.Builder(this, CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_message_circle)
            .setContentTitle(getString(R.string.work_title))
            .setContentText(getString(R.string.work_text))
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(openApp(this, null))
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        runCatching { ServiceCompat.startForeground(this, WORK_ID, notification, type) }
        if (wake == null) {
            // The screen may go off while a long answer is written; the processor must not fall asleep with it.
            val power = getSystemService(PowerManager::class.java)
            wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "devx:generation").apply {
                setReferenceCounted(false)
                acquire(30 * 60 * 1000L)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        wake = null
        super.onDestroy()
    }

    companion object {
        /**
         * Starts the keep-alive when you leave the app in the middle of an answer, and only then: while the
         * app is in front it needs no help, and so there is nothing to see in the notification shade.
         */
        fun start(context: Context) {
            runCatching { androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, GenerationService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GenerationService::class.java))
        }

        const val CHANNEL_WORK = "work_quiet"
        const val CHANNEL_ANSWERS = "answers"
        const val WORK_ID = 1

        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val manager = context.getSystemService(NotificationManager::class.java)
            runCatching { manager.deleteNotificationChannel("work") }
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_WORK, context.getString(R.string.channel_work), NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ANSWERS, context.getString(R.string.channel_answers), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }

        /** Opens the app, on a chat when [chatId] is given. */
        fun openApp(context: Context, chatId: String?): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                if (chatId != null) {
                    action = MainActivity.ACTION_OPEN_CHAT
                    putExtra(MainActivity.EXTRA_CHAT_ID, chatId)
                }
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(context, chatId?.hashCode() ?: 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        /** "The answer is ready", for when the app is not on screen. */
        fun announceAnswer(context: Context, chatId: String, title: String, preview: String) {
            runCatching {
                ensureChannels(context)
                val manager = NotificationManagerCompat.from(context)
                if (!manager.areNotificationsEnabled()) return
                // The answer's own words, without the Markdown marks (and nothing of the cards, code or pictures in it).
                val words = com.apvcode.devx.data.NotificationText.plain(preview).ifBlank { context.getString(R.string.notif_answer_ready) }
                val notification = NotificationCompat.Builder(context, CHANNEL_ANSWERS)
                    .setSmallIcon(R.drawable.ic_message_circle)
                    .setContentTitle(title.ifBlank { context.getString(R.string.app_name) })
                    .setContentText(com.apvcode.devx.data.NotificationText.short(words, 90))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(com.apvcode.devx.data.NotificationText.short(words, 320)))
                    .setAutoCancel(true)
                    .setContentIntent(openApp(context, chatId))
                    .build()
                @Suppress("MissingPermission")
                manager.notify(chatId.hashCode(), notification)
            }
        }
    }
}
