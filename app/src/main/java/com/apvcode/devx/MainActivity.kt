package com.apvcode.devx

import com.apvcode.devx.data.logged
import androidx.compose.runtime.mutableStateOf
import android.content.Intent
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.compose.runtime.DisposableEffect
import com.apvcode.devx.data.Prefs
import com.apvcode.devx.ui.theme.DevxColors
import java.util.Locale
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.apvcode.devx.ui.DevxApp
import com.apvcode.devx.ui.theme.DevxTheme
import android.net.Uri
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import com.apvcode.devx.data.AppLock
import com.apvcode.devx.data.AutoBackup
import com.apvcode.devx.data.Earcon
import com.apvcode.devx.data.MemoryRefresh
import com.apvcode.devx.data.PendingAsk
import com.apvcode.devx.data.PendingShare
import com.apvcode.devx.data.PendingStudy
import com.apvcode.devx.data.PermissionBridge
import com.apvcode.devx.data.Tasks
import com.apvcode.devx.remote.PendingPairing
import com.apvcode.devx.remote.PendingRemoteChat
import com.apvcode.devx.remote.RemoteHub
import com.apvcode.devx.remote.RemoteLink
import com.apvcode.devx.ui.chat.ChatViewModel
import com.apvcode.devx.ui.chat.SharedChat

class MainActivity : ComponentActivity() {
    /** The chat that a home-screen icon or a notification asked for; the app takes it and clears it. */
    private val requestedChat = mutableStateOf<String?>(null)
    private val viewModel: ChatViewModel by lazy { SharedChat.acquire(application, "app") }

    /** A permission the assistant's tools asked for (contacts, calendar): the answer goes back to the tool. */
    private var permissionDone: ((Boolean) -> Unit)? = null
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDone?.invoke(granted)
        permissionDone = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLock.init(applicationContext)
        Earcon.recover(applicationContext)
        MemoryRefresh.schedule(applicationContext)
        // After a restore from a backup the tasks are back but their alarms are not: every enabled task gets its next one.
        Thread { runCatching { Tasks.restoreAll(applicationContext) }.logged("mainactivity"); runCatching { AutoBackup.runIfDue(applicationContext) }.logged("mainactivity") }.start()
        takeChatFrom(intent)
        RemoteHub.init(applicationContext)
        RemoteHub.memory = viewModel.memory
        setContent {
            DevxTheme {
                // Bar icons follow the theme: light icons on dark themes, dark ones on the light theme.
                val light = DevxColors.palette.light
                DisposableEffect(light) {
                    val style = if (light) SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT) else SystemBarStyle.dark(Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                    onDispose { }
                }
                DevxApp(vm = viewModel, requestedChat = requestedChat.value, onChatHandled = { requestedChat.value = null })
            }
        }
    }

    /** The chosen language (or the system one) applies to every string of the app. */
    override fun attachBaseContext(newBase: Context) {
        Prefs.init(newBase)
        val tag = Prefs.language.tag
        if (tag == null) {
            super.attachBaseContext(newBase)
        } else {
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(Locale.forLanguageTag(tag))
            super.attachBaseContext(newBase.createConfigurationContext(config))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeChatFrom(intent)
    }

    private fun takeChatFrom(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE) takeShare(intent)
        if (intent?.action == ACTION_ASK) PendingAsk.request = true
        if (intent?.action == ACTION_STUDY) PendingStudy.project = intent.getStringExtra("project").orEmpty().ifEmpty { "\u0000" }
        if (intent?.action == ACTION_TEMP) PendingAsk.temporary = true
        if (intent?.action == ACTION_COMPUTERS) PendingAsk.computers = true
        if (intent?.action == ACTION_OPEN_CHAT) requestedChat.value = intent.getStringExtra(EXTRA_CHAT_ID)
        if (intent?.action == ACTION_OPEN_REMOTE) {
            val host = intent.getStringExtra(EXTRA_HOST)
            val session = intent.getStringExtra(EXTRA_SESSION)
            if (host != null && session != null) PendingRemoteChat.target = host to session
        }
        if (intent?.action == Intent.ACTION_VIEW) intent.dataString?.takeIf { it.startsWith("devx1:") }?.let { PendingPairing.code = it }
    }

    /** Words (with a title, if the other app gave one) and/or files from the Share menu. */
    @Suppress("DEPRECATION")
    private fun takeShare(intent: Intent) {
        val words = listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() }, intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }).joinToString("\n\n")
        val uris = if (intent.action == Intent.ACTION_SEND_MULTIPLE) intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        else listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        if (words.isNotEmpty() || uris.isNotEmpty()) PendingShare.item = PendingShare.Item(words, uris)
    }

    override fun onStart() {
        super.onStart()
        // Back after a while: the lock may shut. (The picture in the list of recent apps is hidden while a lock is on.)
        AppLock.back()
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!AppLock.enabled)
        AppState.foreground = true
    }

    override fun onStop() {
        AppState.foreground = false
        AppLock.leave()
        // The widgets show the chats and honour the lock: bring them up to date as the app goes.
        runCatching { Widgets.refreshAll(this) }
        // Leaving the app: only a computer the person is connected to (with its notification) stays reachable, so that approvals
        // and answers still arrive. Every other connection ends with the screen.
        if (!RemoteLink.held) RemoteHub.stopAll()
        super.onStop()
    }

    override fun onDestroy() {
        // The app is closed for good: the voice stops with it (a sample or an answer must not go on playing).
        if (isFinishing) com.apvcode.devx.assist.VoiceWarm.speaker(applicationContext).stop()
        permissionDone = null
        if (PermissionBridge.owner === this) PermissionBridge.asker = null
        // The app is closed (not turned, not sent to the background): nothing of it goes on working.
        if (isFinishing) {
            RemoteLink.disconnect(this)
            RemoteHub.stopAll()
            SharedChat.release("app")
            if (SharedChat.idle) GenerationService.stop(this)
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        AppState.resumed = true
        PermissionBridge.owner = this
        PermissionBridge.asker = { permission, done ->
            permissionDone?.invoke(false)
            permissionDone = done
            permissionLauncher.launch(permission)
        }
        // Back in front: the keep-alive and its notification are not needed any more.
        GenerationService.stop(this)
        requestHighestRefreshRate()
    }

    override fun onPause() {
        AppState.resumed = false
        // Left in the middle of an answer: from now on keep the app alive, so that the answer is finished.
        if (viewModel.busy) GenerationService.start(this)
        super.onPause()
    }

    companion object {
        const val ACTION_ASK = "com.apvcode.devx.ASK"
        const val ACTION_STUDY = "com.apvcode.devx.STUDY"
        const val ACTION_TEMP = "com.apvcode.devx.TEMP"
        const val ACTION_COMPUTERS = "com.apvcode.devx.COMPUTERS"
        const val ACTION_OPEN_CHAT = "com.apvcode.devx.OPEN_CHAT"
        const val EXTRA_CHAT_ID = "chatId"
        const val ACTION_OPEN_REMOTE = "com.apvcode.devx.OPEN_REMOTE"
        const val EXTRA_HOST = "host"
        const val EXTRA_SESSION = "session"
    }

    /**
     * Many phones (this one is 60/120 Hz) keep ordinary apps on 60 Hz unless they ask for more.
     * Ask for the fastest mode that keeps the current resolution.
     */
    @Suppress("DEPRECATION")
    private fun requestHighestRefreshRate() {
        val display = windowManager.defaultDisplay ?: return
        val current = display.mode
        val best = display.supportedModes
            .filter {
                it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
            }
            .maxByOrNull { it.refreshRate } ?: return
        val params = window.attributes
        if (params.preferredDisplayModeId != best.modeId) {
            params.preferredDisplayModeId = best.modeId
            window.attributes = params
        }
    }
}
