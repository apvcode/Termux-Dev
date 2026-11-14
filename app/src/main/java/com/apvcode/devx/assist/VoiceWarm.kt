package com.apvcode.devx.assist

import android.app.Application
import android.content.Context
import com.apvcode.devx.data.Prefs
import com.apvcode.devx.data.Speaker
import com.apvcode.devx.ui.chat.SharedChat

/**
 * What the voice assistant needs ready before it is called: the speech engine, the chat model and its connection. When Devx is the
 * phone's assistant the system keeps its service alive, which warms these up in advance, so that the window opens and listens at
 * once. Called from the tile or the widget they are made on the way, in parallel with the window itself.
 */
object VoiceWarm {
    @Volatile private var speaker: Speaker? = null
    @Volatile private var model = false

    /** The one speech engine of the process: it is kept between talks (starting it takes a moment). */
    fun speaker(context: Context): Speaker = speaker ?: synchronized(this) { speaker ?: Speaker(context.applicationContext).also { speaker = it } }

    fun warm(app: Application) {
        Prefs.init(app)
        speaker(app)
        if (model) return
        model = true
        // The model of the chats is made off the main thread: it reads its files and sets up its tools.
        Thread {
            runCatching { SharedChat.acquire(app, "warm") }.onFailure { model = false }
        }.apply { name = "devx-voice-warm"; isDaemon = true }.start()
    }

    fun cool() {
        if (!model) return
        model = false
        SharedChat.release("warm")
    }
}
