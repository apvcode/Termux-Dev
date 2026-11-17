package com.apvcode.devx.data

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.apvcode.devx.GenerationService
import com.apvcode.devx.ui.chat.SharedChat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The overviews of the memory, of the projects and of the notebooks renew themselves in the background: twice a day an alarm wakes the
 * app, which writes again every overview that is out of date (new notes, new chats) and goes back to sleep. Not at all when the memory
 * is off, and never an overview the person wrote by hand.
 */
object MemoryRefresh {
    private const val ACTION = "com.apvcode.devx.MEMORY_REFRESH"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 7311, Intent(context, MemoryRefreshReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Sets the alarm (the same one again if it is already set; the system picks a convenient moment, so it costs no battery). */
    fun schedule(context: Context) {
        val app = context.applicationContext
        attempt("memoryrefresh") {
            app.getSystemService(AlarmManager::class.java).setInexactRepeating(
                AlarmManager.RTC, System.currentTimeMillis() + AlarmManager.INTERVAL_HALF_DAY, AlarmManager.INTERVAL_HALF_DAY, pending(app),
            )
        }
    }

    fun run(context: Context) {
        val app = context.applicationContext
        // The twice-a-day wake-up also takes the weekly copy when it is due.
        Thread { runCatching { AutoBackup.runIfDue(app) } }.start()
        if (!Prefs.memory) return
        GenerationService.start(app)
        scope.launch {
            val user = "memory-${System.nanoTime()}"
            val vm = SharedChat.acquire(app as? Application ?: app.applicationContext as Application, user)
            try {
                withTimeoutOrNull(60_000) { androidx.compose.runtime.snapshotFlow { vm.loaded }.first { it } } ?: return@launch
                vm.refreshStaleSummaries()
                // Until they are written (a few minutes at most).
                withTimeoutOrNull(4 * 60_000L) {
                    delay(1_500)
                    while (vm.summarizingAny) delay(500)
                }
            } finally {
                SharedChat.release(user)
                if (SharedChat.idle && !vm.busy) GenerationService.stop(app)
            }
        }
    }
}

class MemoryRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        Prefs.init(app)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> MemoryRefresh.schedule(app)
            else -> MemoryRefresh.run(app)
        }
    }
}
