package com.apvcode.devx.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.ConcurrentHashMap

/** The questions that wait for a "yes" (per chat), so that a notification can answer them while the app is not on screen. */
object ApprovalBridge {
    private val waiting = ConcurrentHashMap<String, PendingApproval>()

    fun put(chatId: String, pending: PendingApproval) { waiting[chatId] = pending }
    fun remove(chatId: String) { waiting.remove(chatId) }
    fun answer(chatId: String, decision: ApprovalDecision) { waiting.remove(chatId)?.answer?.invoke(decision) }
}

/** The buttons of the notification "waits for your answer". */
class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val chatId = intent.getStringExtra(EXTRA_CHAT) ?: return
        val decision = runCatching { ApprovalDecision.valueOf(intent.getStringExtra(EXTRA_DECISION) ?: "") }.getOrNull() ?: return
        ApprovalBridge.answer(chatId, decision)
        androidx.core.app.NotificationManagerCompat.from(context).cancel(notificationId(chatId))
    }

    companion object {
        const val EXTRA_CHAT = "chat"
        const val EXTRA_DECISION = "decision"
        fun notificationId(chatId: String) = ("ask:" + chatId).hashCode()
    }
}
