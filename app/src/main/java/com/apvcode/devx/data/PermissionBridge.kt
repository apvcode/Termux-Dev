package com.apvcode.devx.data

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.apvcode.devx.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Lets a tool ask the person for a permission (contacts, calendar, notifications) in the middle of an answer: the screen
 * shows the system's question, the tool waits for it. Only while the app is in front; otherwise it just says no.
 */
object PermissionBridge {
    /** Set by the main screen while it exists: shows the question and reports the answer. */
    @Volatile
    var asker: ((String, (Boolean) -> Unit) -> Unit)? = null

    /** The screen that set [asker] (the app or the assistant); it takes the question away when it goes. */
    @Volatile
    var owner: Any? = null

    fun has(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    suspend fun ensure(context: Context, permission: String): Boolean {
        if (has(context, permission)) return true
        val ask = asker ?: return false
        if (!AppState.resumed) return false
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                ask(permission) { granted -> if (cont.isActive) cont.resume(granted) }
            }
        }
    }
}
