package com.apvcode.devx.data

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.apvcode.devx.R
import java.util.Locale

/**
 * Speaking instead of typing, with our own screen. The listening is done by the phone's speech recognition (there is no
 * model of ours in the app), but the language is always asked for by name, and what it hears is shown as it comes. It goes
 * on listening through pauses until the person says it is done.
 */
@Stable
class VoiceInput(private val context: Context) {
    enum class State { Idle, Listening, Failed }

    var state by mutableStateOf(State.Idle)
        private set

    /** What has been heard so far, the finished sentences and the one still being said. */
    var heard by mutableStateOf("")
        private set

    /** The same in two parts: the sentences the recognizer has settled on, and the words it is still making up its mind about. */
    var confirmed by mutableStateOf("")
        private set
    var pending by mutableStateOf("")
        private set

    /** A string resource that says what went wrong (null when nothing did). */
    var error by mutableStateOf<Int?>(null)
        private set

    /** The last moments of loudness, oldest first, for the bars; [tick] changes each time they move. */
    val levels = FloatArray(LEVELS)
    var tick by mutableIntStateOf(0)
        private set

    /** In auto mode: the language the recogniser says it hears (a tag like "ru-RU"), when it says. */
    var detected by mutableStateOf("")
        private set

    /** When the listening began (elapsed-realtime milliseconds), for the clock. */
    var startedAt by mutableLongStateOf(0L)
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tag = "en-US"
    private var committed = ""
    private var partial = ""
    private var loud = 0f
    private var shown = 0f
    private var wanted = false
    private var finishing = false
    private var onFinished: ((String) -> Unit)? = null
    private var quietSince = 0L

    /** Quick mode (the voice assistant): the first finished sentence ends the listening and is handed over by itself. */
    private var quick = false
    private var onHeard: ((String) -> Unit)? = null
    private var idleLimit = IDLE_LIMIT_MS

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Starts listening. With [onSentence] set the listening is quick: after the first sentence and a short pause it ends and hands
     * the words over (the person does not press anything); [idleMs] is how long nothing at all may be said before it gives up.
     */
    fun start(languageTag: String, onSentence: ((String) -> Unit)? = null, idleMs: Long = IDLE_LIMIT_MS) {
        cleanup()
        quick = onSentence != null
        onHeard = onSentence
        idleLimit = idleMs
        tag = languageTag
        committed = ""
        partial = ""
        heard = ""
        confirmed = ""
        pending = ""
        detected = ""
        error = null
        wanted = true
        state = State.Listening
        startedAt = now()
        quietSince = now()
        listen()
        handler.post(ticker)
    }

    /** The language is changed while listening: what was heard stays, the listening starts again in the new one. */
    fun switchLanguage(languageTag: String) {
        if (state == State.Idle) return
        tag = languageTag
        partial = ""
        publish()
        recognizer?.cancel()
        listen()
    }

    /** The person is done: what is heard is handed over. If the phone is slow to give the last words, what is there is used. */
    fun finish(onResult: (String) -> Unit) {
        if (state == State.Idle) { onResult(""); return }
        finishing = true
        onFinished = onResult
        runCatching { recognizer?.stopListening() }
        handler.postDelayed(deliver, 1800)
    }

    fun cancel() = cleanup()

    fun release() = cleanup()

    private val deliver = Runnable {
        val done = onFinished ?: return@Runnable
        val text = join(committed, partial)
        cleanup()
        done(text)
    }

    private fun now() = android.os.SystemClock.elapsedRealtime()

    private fun intent(): Intent {
        val auto = tag == AUTO
        // In auto mode the first language is the one the person most likely speaks; the others are named as well, and on
        // Android 13 and later the recogniser is asked to find out which one is being spoken (it may or may not be able to).
        val main = if (auto) fallbackTag() else tag
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, main)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, main)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, if (quick) 1300L else 2500L)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        if (auto) {
            val others = autoLanguages().filter { it != main }
            intent.putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", others.toTypedArray())
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                intent.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, ArrayList(autoLanguages()))
                intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
            }
        }
        return intent
    }

    private fun listen() {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        runCatching { r.startListening(intent()) }.onFailure { fail(R.string.voice_err_generic) }
    }

    private fun again(delayMs: Long = 120) {
        if (!wanted || finishing) return
        handler.postDelayed({ if (wanted && !finishing) listen() }, delayMs)
    }

    private fun publish() {
        heard = join(committed, partial)
        confirmed = committed
        pending = partial
    }

    private fun fail(message: Int) {
        error = message
        state = State.Failed
        wanted = false
        runCatching { recognizer?.cancel() }
        handler.removeCallbacks(ticker)
    }

    private fun cleanup() {
        handler.removeCallbacks(deliver)
        handler.removeCallbacks(ticker)
        wanted = false
        finishing = false
        onFinished = null
        onHeard = null
        quick = false
        runCatching { recognizer?.destroy() }
        recognizer = null
        heard = ""
        confirmed = ""
        pending = ""
        state = State.Idle
        loud = 0f
        shown = 0f
        levels.fill(0f)
        tick++
    }

    /** Every moment the bars move one step to the left and a new one comes in on the right. */
    private val ticker = object : Runnable {
        override fun run() {
            if (state != State.Listening) return
            shown += (loud - shown) * 0.55f
            System.arraycopy(levels, 1, levels, 0, levels.size - 1)
            levels[levels.size - 1] = shown
            tick++
            // Long silence with nothing said: the listening is dropped, so that the microphone is not left on.
            if (committed.isEmpty() && partial.isEmpty() && now() - quietSince > idleLimit) {
                cancel()
                return
            }
            handler.postDelayed(this, 55)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            loud = level(rmsdB)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank()) {
                partial = text
                quietSince = now()
                publish()
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
            if (text.isNotEmpty()) committed = join(committed, text)
            partial = ""
            quietSince = now()
            publish()
            if (quick && committed.isNotEmpty() && !finishing) {
                val done = onHeard
                val words = committed
                cleanup()
                done?.invoke(words)
            } else if (finishing) deliver.run() else again()
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}

        // Android 13 and later: the recogniser says which language it hears (only when it was asked to find out).
        @androidx.annotation.RequiresApi(33)
        override fun onLanguageDetection(results: Bundle) {
            val found = results.getString(SpeechRecognizer.DETECTED_LANGUAGE).orEmpty()
            if (found.isNotBlank()) detected = found
        }

        override fun onError(error: Int) {
            when (error) {
                // Nothing was said, or nothing was understood: a pause is not the end, it listens on.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> if (finishing) deliver.run() else again()
                SpeechRecognizer.ERROR_CLIENT -> if (finishing) deliver.run() else again(300)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    runCatching { recognizer?.destroy() }
                    recognizer = null
                    again(450)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail(R.string.voice_err_permission)
                SpeechRecognizer.ERROR_AUDIO -> fail(R.string.voice_err_audio)
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> fail(R.string.voice_err_network)
                ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> fail(R.string.voice_err_language)
                else -> fail(R.string.voice_err_generic)
            }
        }
    }

    companion object {
        const val LEVELS = 64
        private const val IDLE_LIMIT_MS = 45_000L
        // Added to the platform in API 31; the numbers are the same on every version that reports them.
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13

        /** The languages offered for speaking: tag and the name in that language. */
        val LANGUAGES = listOf(
            "ru-RU" to "Русский", "en-US" to "English", "uk-UA" to "Українська", "de-DE" to "Deutsch", "fr-FR" to "Français",
            "es-ES" to "Español", "it-IT" to "Italiano", "pt-BR" to "Português", "tr-TR" to "Türkçe", "pl-PL" to "Polski",
            "zh-CN" to "中文", "ja-JP" to "日本語", "ko-KR" to "한국어",
        )

        /** The setting that lets the recogniser pick among the languages the person is likely to speak. */
        const val AUTO = "auto"

        /** What the app's own language says the person speaks, else the phone's. */
        fun fallbackTag(): String = when (Prefs.language) {
            AppLanguage.Russian -> "ru-RU"
            AppLanguage.English -> "en-US"
            AppLanguage.System -> Locale.getDefault().toLanguageTag()
        }

        /** The languages auto mode chooses among: the app's, the phone's, Russian and English. */
        fun autoLanguages(): List<String> = listOf(fallbackTag(), Locale.getDefault().toLanguageTag(), "ru-RU", "en-US").distinct()

        /** The name of a choice of speaking language, for the settings and the chip's menu. */
        fun nameOf(tag: String, appDefault: String, auto: String): String = when {
            tag.isBlank() -> appDefault
            tag == AUTO -> auto
            else -> LANGUAGES.firstOrNull { it.first == tag }?.second ?: tag
        }

        /** The loudness the recognizer reports (about -2 to 10 dB) as 0 to 1. */
        fun level(rmsdB: Float): Float = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)

        fun join(a: String, b: String): String = listOf(a, b).filter { it.isNotBlank() }.joinToString(" ") { it.trim() }

        /**
         * The language to listen for: the one the person chose for speaking, else the language chosen for the app, else the
         * phone's. (The app's own choice matters: the phone may be in English while the person speaks Russian.)
         */
        fun currentTag(): String = Prefs.voiceLanguage.ifBlank { fallbackTag() }

        /** "ru-RU" as "RU". */
        fun shortName(tag: String): String = if (tag == AUTO) "AUTO" else tag.substringBefore('-').uppercase()
    }
}
