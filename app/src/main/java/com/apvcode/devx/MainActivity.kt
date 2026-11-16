package com.apvcode.devx

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

class MainActivity : ComponentActivity() {
    /** The chat that a home-screen icon or a notification asked for; the app takes it and clears it. */
    private val requestedChat = mutableStateOf<String?>(null)
    private val viewModel: com.apvcode.devx.ui.chat.ChatViewModel by lazy { com.apvcode.devx.ui.chat.SharedChat.acquire(application, "app") }

    /** A permission the assistant's tools asked for (contacts, calendar): the answer goes back to the tool. */
    private var permissionDone: ((Boolean) -> Unit)? = null
    private val permissionLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        permissionDone?.invoke(granted)
        permissionDone = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.apvcode.devx.data.AppLock.init(applicationContext)
        com.apvcode.devx.data.Earcon.recover(applicationContext)
        com.apvcode.devx.data.MemoryRefresh.schedule(applicationContext)
        // After a restore from a backup the tasks are back but their alarms are not: every enabled task gets its next one.
        Thread { runCatching { com.apvcode.devx.data.Tasks.restoreAll(applicationContext) }; runCatching { com.apvcode.devx.data.AutoBackup.runIfDue(applicationContext) } }.start()
        takeChatFrom(intent)
        com.apvcode.devx.remote.RemoteHub.init(applicationContext)
        com.apvcode.devx.remote.RemoteHub.memory = viewModel.memory
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
        if (intent?.action == ACTION_ASK) com.apvcode.devx.data.PendingAsk.request = true
        if (intent?.action == ACTION_STUDY) com.apvcode.devx.data.PendingStudy.project = intent.getStringExtra("project").orEmpty().ifEmpty { "\u0000" }
        if (intent?.action == ACTION_TEMP) com.apvcode.devx.data.PendingAsk.temporary = true
        if (intent?.action == ACTION_COMPUTERS) com.apvcode.devx.data.PendingAsk.computers = true
        if (intent?.action == ACTION_OPEN_CHAT) requestedChat.value = intent.getStringExtra(EXTRA_CHAT_ID)
        if (intent?.action == ACTION_OPEN_REMOTE) {
            val host = intent.getStringExtra(EXTRA_HOST)
            val session = intent.getStringExtra(EXTRA_SESSION)
            if (host != null && session != null) com.apvcode.devx.remote.PendingRemoteChat.target = host to session
        }
        if (intent?.action == Intent.ACTION_VIEW) intent.dataString?.takeIf { it.startsWith("devx1:") }?.let { com.apvcode.devx.remote.PendingPairing.code = it }
    }

    /** Words (with a title, if the other app gave one) and/or files from the Share menu. */
    @Suppress("DEPRECATION")
    private fun takeShare(intent: Intent) {
        val words = listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() }, intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }).joinToString("\n\n")
        val uris = if (intent.action == Intent.ACTION_SEND_MULTIPLE) intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM).orEmpty()
        else listOfNotNull(intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
        if (words.isNotEmpty() || uris.isNotEmpty()) com.apvcode.devx.data.PendingShare.item = com.apvcode.devx.data.PendingShare.Item(words, uris)
    }

    override fun onStart() {
        super.onStart()
        // Back after a while: the lock may shut. (The picture in the list of recent apps is hidden while a lock is on.)
        com.apvcode.devx.data.AppLock.back()
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!com.apvcode.devx.data.AppLock.enabled)
        AppState.foreground = true
    }

    override fun onStop() {
        AppState.foreground = false
        com.apvcode.devx.data.AppLock.leave()
        // The widgets show the chats and honour the lock: bring them up to date as the app goes.
        runCatching { Widgets.refreshAll(this) }
        // Leaving the app: only a computer the person is connected to (with its notification) stays reachable, so that approvals
        // and answers still arrive. Every other connection ends with the screen.
        if (!com.apvcode.devx.remote.RemoteLink.held) com.apvcode.devx.remote.RemoteHub.stopAll()
        super.onStop()
    }

    override fun onDestroy() {
        permissionDone = null
        if (com.apvcode.devx.data.PermissionBridge.owner === this) com.apvcode.devx.data.PermissionBridge.asker = null
        // The app is closed (not turned, not sent to the background): nothing of it goes on working.
        if (isFinishing) {
            com.apvcode.devx.remote.RemoteLink.disconnect(this)
            com.apvcode.devx.remote.RemoteHub.stopAll()
            com.apvcode.devx.ui.chat.SharedChat.release("app")
            if (com.apvcode.devx.ui.chat.SharedChat.idle) GenerationService.stop(this)
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        AppState.resumed = true
        com.apvcode.devx.data.PermissionBridge.owner = this
        com.apvcode.devx.data.PermissionBridge.asker = { permission, done ->
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
