package com.apvcode.devx.ui.chat

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * The one [ChatViewModel] of the process. The app and the assistant (a see-through window over other apps) are two
 * activities, but they talk to the same chats: what the assistant says is a chat that the app already has.
 * It lives while at least one of the two exists, and goes when the last one is closed.
 */
object SharedChat : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    private val users = HashSet<String>()

    @Synchronized
    fun acquire(app: Application, user: String): ChatViewModel {
        users += user
        return ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[ChatViewModel::class.java]
    }

    /** Called when an activity (named by [user]: "app" or "assistant") is really gone; the last one out clears the model (and stops what it was doing). */
    @Synchronized
    fun release(user: String) {
        users -= user
        if (users.isEmpty()) viewModelStore.clear()
    }

    /** True while no activity uses the model. */
    @get:Synchronized
    val idle: Boolean get() = users.isEmpty()
}
