package com.apvcode.devx.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import com.apvcode.devx.AppState
import com.apvcode.devx.GenerationService
import com.apvcode.devx.MainActivity
import com.apvcode.devx.data.AppLock
import com.apvcode.devx.data.PermissionBridge
import com.apvcode.devx.data.Prefs
import com.apvcode.devx.ui.assistant.VoiceAssistantScreen
import com.apvcode.devx.ui.chat.ChatViewModel
import com.apvcode.devx.ui.chat.SharedChat
import com.apvcode.devx.ui.theme.DevxTheme
import java.util.Locale

/** Devx as the phone's digital assistant: the system starts it when the person long-presses the power button or swipes from a corner. */
class DevxInteractionService : VoiceInteractionService()

class DevxSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = DevxSession(this)
}

/** One call of the assistant: it does not draw anything itself, it opens the assistant's window and steps aside. */
class DevxSession(private val context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = AssistantActivity.intent(context)
        runCatching { context.startActivity(intent) }.onFailure { runCatching { startAssistantActivity(intent) } }
        hide()
    }
}

/**
 * The system wants a speech recognizer named in the assistant's description. Devx listens with the phone's own recognizer
 * and has none of its own: this one only says so, and nothing ever chooses it.
 */
class DevxRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        runCatching { listener?.error(SpeechRecognizer.ERROR_CLIENT) }
    }

    override fun onCancel(listener: Callback?) {}

    override fun onStopListening(listener: Callback?) {}
}

/**
 * The assistant's window: see-through, over whatever the person was doing (like the phone's own assistants), with the light
 * along the screen's edges, the answer in a card and the field to write in at the bottom. It shares the chats of the app.
 */
class AssistantActivity : ComponentActivity() {
    private val vm: ChatViewModel by lazy { SharedChat.acquire(application, "assistant") }
    private var started by mutableIntStateOf(0)
    private var say by mutableStateOf<String?>(null)
    private var permissionDone: ((Boolean) -> Unit)? = null
    private val permissionLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        permissionDone?.invoke(granted)
        permissionDone = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLock.init(applicationContext)
        // The app is locked: the assistant would open the chats behind the lock. Let the lock screen come first.
        if (AppLock.locksApp && AppLock.locked) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        takeSay(intent)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        window.setDimAmount(0f)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        setContent {
            DevxTheme {
                key(started) {
                    VoiceAssistantScreen(
                        vm = vm,
                        listenAtStart = true,
                        say = say.also { say = null },
                        onClose = { finish() },
                        onOpenChat = { openApp(MainActivity.ACTION_OPEN_CHAT, vm.currentId) },
                        onOpenSettings = { openApp(null, null) },
                    )
                }
            }
        }
    }

    /** The app, with the chat that the talk is saved in (or just the app). */
    private fun openApp(action: String?, chatId: String?) {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (action != null && chatId != null) intent.setAction(action).putExtra(MainActivity.EXTRA_CHAT_ID, chatId)
        startActivity(intent)
        finish()
    }

    /** Debug builds only: words that are taken as if they had been said (for testing without a microphone). */
    private fun takeSay(intent: Intent?) {
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) say = intent?.getStringExtra("say")
    }

    override fun attachBaseContext(newBase: Context) {
        Prefs.init(newBase)
        val tag = Prefs.language.tag
        if (tag == null) {
            super.attachBaseContext(newBase)
        } else {
            val config = android.content.res.Configuration(newBase.resources.configuration)
            config.setLocale(Locale.forLanguageTag(tag))
            super.attachBaseContext(newBase.createConfigurationContext(config))
        }
    }

    /** Called again while it is open (the power button once more): it starts afresh. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeSay(intent)
        started++
    }

    override fun onStart() {
        super.onStart()
        AppState.foreground = true
    }

    override fun onResume() {
        super.onResume()
        AppState.resumed = true
        GenerationService.stop(this)
        PermissionBridge.owner = this
        PermissionBridge.asker = { permission, done ->
            permissionDone?.invoke(false)
            permissionDone = done
            permissionLauncher.launch(permission)
        }
    }

    override fun onPause() {
        AppState.resumed = false
        // Closed in the middle of an answer: keep the app alive until the answer is finished and saved.
        if (vm.busy) GenerationService.start(this)
        super.onPause()
    }

    override fun onStop() {
        AppState.foreground = false
        super.onStop()
    }

    override fun onDestroy() {
        permissionDone = null
        if (PermissionBridge.owner === this) PermissionBridge.asker = null
        if (isFinishing) {
            SharedChat.release("assistant")
            if (SharedChat.idle && !vm.busy) GenerationService.stop(this)
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION = "com.apvcode.devx.ASSISTANT"

        fun intent(context: Context): Intent = Intent(context, AssistantActivity::class.java)
            .setAction(ACTION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        fun open(context: Context) = context.startActivity(intent(context))
    }
}
