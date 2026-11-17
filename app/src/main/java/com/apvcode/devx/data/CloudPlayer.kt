package com.apvcode.devx.data

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import kotlin.coroutines.resume

/**
 * Plays the text the [CloudVoice] makes. Each piece of text is asked for as soon as it is queued (two at a time), so the next piece is
 * ready while the one before it is being said. If the provider fails, the rest of what is queued goes back to the phone's own voice
 * and the reason is kept for the settings page.
 */
class CloudPlayer(private val context: Context, private val host: Host) {
    /** What the speaker has to do for the player. */
    interface Host {
        fun speaking(on: Boolean)
        fun wordNow()
        fun focus(take: Boolean)
        /** The cloud voice could not be used: say this with the phone's voice instead. */
        fun fallback(text: String, reason: String)
    }

    private class Item(val text: String, val audio: Deferred<Result<CloudVoice.Audio>>)

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = ArrayDeque<Item>()
    private var worker: Job? = null
    private val limit = Semaphore(2)
    private val client by lazy { CloudVoice.newClient() }
    private var count = 0
    private var player: MediaPlayer? = null

    /** The setup that is in use: the provider (found by id), its key, the voice. Null when the cloud voice cannot be used now. */
    class Setup(val provider: Provider, val key: String, val model: String, val voice: String)

    private var cached: Pair<Long, Provider?>? = null

    /** Why the natural voice cannot be used right now, as a string resource; null when it can (or is not switched on). */
    fun problem(): Int? {
        if (Prefs.voiceEngine != Prefs.ENGINE_CLOUD) return null
        if (Prefs.cloudProvider.isBlank()) return com.apvcode.devx.R.string.voice_problem_provider
        val provider = Store(context).loadProviders().firstOrNull { it.id == Prefs.cloudProvider } ?: return com.apvcode.devx.R.string.voice_problem_provider
        if (CloudVoice.backendOf(provider) == null) return com.apvcode.devx.R.string.voice_problem_provider
        if (Secrets(context).get(provider.id).isNullOrBlank()) return com.apvcode.devx.R.string.voice_problem_key
        if (Prefs.cloudModel.isBlank() && CloudVoice.newestModel(provider) == null) return com.apvcode.devx.R.string.voice_problem_model
        return null
    }

    fun setup(): Setup? {
        if (Prefs.voiceEngine != Prefs.ENGINE_CLOUD || Prefs.cloudProvider.isBlank()) return null
        val now = SystemClock.elapsedRealtime()
        val provider = cached?.takeIf { now - it.first < 30_000 && it.second?.id == Prefs.cloudProvider }?.second
            ?: Store(context).loadProviders().firstOrNull { it.id == Prefs.cloudProvider }.also { cached = now to it }
        provider ?: return null
        val key = Secrets(context).get(provider.id).orEmpty()
        if (key.isBlank() || CloudVoice.backendOf(provider) == null) return null
        // The model chosen in the settings if the provider still lists it, else the newest speech model it lists.
        // A model typed by hand is used as it is (a gateway may serve speech without listing it).
        val model = Prefs.cloudModel.takeIf { it.isNotBlank() } ?: CloudVoice.newestModel(provider) ?: return null
        return Setup(provider, key, model, Prefs.cloudVoice)
    }

    val active: Boolean get() = queue.isNotEmpty() || worker?.isActive == true

    /** Queues a piece of text to be said. */
    fun enqueue(text: String, setup: Setup) {
        val speed = Prefs.voiceRate
        val audio = scope.async(Dispatchers.IO) {
            limit.withPermit { runCatching { CloudVoice.synthesize(client, setup.provider, setup.key, setup.model, text, setup.voice.ifBlank { CloudVoice.defaultVoice(CloudVoice.backendOf(setup.provider)!!) }, speed) }.logged("cloudplayer") }
        }
        queue.addLast(Item(text, audio))
        if (worker?.isActive != true) worker = scope.launch { run() }
    }

    private suspend fun run() {
        host.speaking(true)
        host.focus(true)
        try {
            while (queue.isNotEmpty()) {
                val item = queue.removeFirst()
                val result = item.audio.await()
                val audio = result.getOrNull()
                if (audio == null) {
                    val reason = result.exceptionOrNull()?.message ?: "unknown"
                    ErrorLog.warn("cloudvoice", "the cloud voice failed ($reason); the phone's voice takes over", result.exceptionOrNull())
                    // This piece and everything queued after it go to the phone's voice, in order.
                    host.fallback(item.text, reason)
                    while (queue.isNotEmpty()) { val rest = queue.removeFirst(); rest.audio.cancel(); host.fallback(rest.text, reason) }
                    return
                }
                play(audio)
            }
        } finally {
            host.speaking(false)
            host.focus(false)
        }
    }

    private suspend fun play(audio: CloudVoice.Audio) {
        val file = File(context.cacheDir, "cv_${count++}.${audio.extension}")
        try {
            kotlinx.coroutines.withContext(Dispatchers.IO) { file.writeBytes(audio.bytes) }
            val mp = MediaPlayer()
            player = mp
            try {
                mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                mp.setDataSource(file.path)
                suspendCancellableCoroutine { cont -> mp.setOnPreparedListener { if (cont.isActive) cont.resume(Unit) }; mp.setOnErrorListener { _, _, _ -> if (cont.isActive) cont.resume(Unit); true }; mp.prepareAsync() }
                // Gemini speaks at one speed; the person's speed setting is applied while playing.
                if (audio.extension == "wav") runCatching { mp.playbackParams = mp.playbackParams.setSpeed(Prefs.voiceRate.coerceIn(0.5f, 2f)) }
                val ended = suspendCancellableCoroutine<Unit> { cont ->
                    mp.setOnCompletionListener { if (cont.isActive) cont.resume(Unit) }
                    mp.setOnErrorListener { _, _, _ -> if (cont.isActive) cont.resume(Unit); true }
                    mp.start()
                    host.wordNow()
                    cont.invokeOnCancellation { runCatching { mp.stop() } }
                }
                ended
            } finally {
                player = null
                runCatching { mp.release() }
            }
        } finally {
            file.delete()
        }
    }

    /** Cuts the voice off at once and forgets what was waiting. */
    fun stop() {
        val had = active
        worker?.cancel()
        queue.forEach { it.audio.cancel() }
        queue.clear()
        runCatching { player?.stop() }
        player = null
        context.cacheDir.listFiles { f -> f.name.startsWith("cv_") }?.forEach { it.delete() }
        if (had) { host.speaking(false); host.focus(false) }
    }

    fun shutdown() { stop(); scope.cancel() }
}
