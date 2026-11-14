package com.apvcode.devx.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Reads the assistant's answers aloud with the phone's own speech engine. Sentences are queued as they arrive, so the voice
 * starts while the rest is still being written. Other sound (music) is turned down while it speaks.
 */
@Stable
class Speaker(private val context: Context) {
    /** Something is being said or waits to be said. */
    var speaking by mutableStateOf(false)
        private set

    /** True when the phone has no speech engine at all (or it failed to start). */
    var unavailable by mutableStateOf(false)
        private set

    /** When the last word began (elapsed-realtime milliseconds): the animation bumps on it. Read every frame, not observed. */
    @Volatile
    var lastWordAt = 0L
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private val waiting = ArrayList<Pair<String, String>>()
    private val active = HashSet<String>()
    private var counter = 0
    private var languageTag = ""
    private var focus: AudioFocusRequest? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            handler.post {
                if (status != TextToSpeech.SUCCESS) {
                    unavailable = true
                    return@post
                }
                val engine = tts ?: return@post
                runCatching {
                    engine.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    engine.setSpeechRate(1.04f)
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) { lastWordAt = SystemClock.elapsedRealtime() }
                        override fun onDone(utteranceId: String?) { finished(utteranceId) }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { finished(utteranceId) }
                        override fun onError(utteranceId: String?, errorCode: Int) { finished(utteranceId) }
                        override fun onStop(utteranceId: String?, interrupted: Boolean) { finished(utteranceId) }
                        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) { lastWordAt = SystemClock.elapsedRealtime() }
                    })
                }
                ready = true
                val queued = ArrayList(waiting)
                waiting.clear()
                queued.forEach { (id, text) -> say(id, text) }
            }
        }
    }

    /** Adds a piece of text to what is being said. */
    fun speak(text: String) {
        val piece = text.trim()
        if (piece.isEmpty() || unavailable) return
        val id = "u${counter++}"
        active += id
        speaking = true
        if (ready) say(id, piece) else waiting += id to piece
    }

    private fun say(id: String, text: String) {
        val engine = tts ?: return
        takeFocus()
        val tag = localeFor(text)
        if (tag != languageTag) {
            languageTag = tag
            runCatching { engine.setLanguage(Locale.forLanguageTag(tag)) }
        }
        if (engine.speak(text, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.ERROR) finished(id)
    }

    /** Cuts the voice off at once and forgets what was waiting. */
    fun stop() {
        waiting.clear()
        active.clear()
        runCatching { tts?.stop() }
        speaking = false
        giveFocus()
    }

    fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    private fun finished(id: String?) {
        handler.post {
            if (id != null) active.remove(id)
            if (active.isEmpty() && waiting.isEmpty()) {
                speaking = false
                giveFocus()
            }
        }
    }

    private fun takeFocus() {
        if (focus != null) return
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { }
            .build()
        if (manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focus = request
    }

    private fun giveFocus() {
        val request = focus ?: return
        focus = null
        runCatching { context.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request) }
    }

    /** The language to read a piece in: Cyrillic is Russian; other letters take the phone's language, or English. */
    private fun localeFor(text: String): String {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return languageTag.ifEmpty { "en-US" }
        val cyrillic = letters.count { it in 'Ѐ'..'ӿ' }
        if (cyrillic * 2 >= letters.length) return if (Locale.getDefault().language == "uk") "uk-UA" else "ru-RU"
        val phone = Locale.getDefault()
        return if (phone.language in setOf("ru", "uk", "bg", "sr", "be")) "en-US" else phone.toLanguageTag()
    }
}

/**
 * Turns what a model wrote (Markdown) into what can be read aloud or shown on the small screen of the assistant: no
 * symbols, no code blocks, no links. [next] cuts the text into sentences as it grows.
 */
object SpeechText {
    private val link = Regex("""!?\[([^\]]*)]\([^)]*\)""")
    private val url = Regex("""https?://\S+""")
    private val heading = Regex("""^\s{0,3}#{1,6}\s*""")
    private val bullet = Regex("""^\s*(?:[-*•]|\d{1,2}[.)])\s+""")
    private val quote = Regex("""^\s*>+\s?""")
    private val underscores = Regex("""(?<=^|\s)_+|_+(?=$|\s|[.,!?;:])""")
    private val listNumber = Regex("""\d{1,2}[.)]""")
    private val tableRule = Regex("""^\s*\|?[\s:|-]{3,}\|?\s*$""")

    /** One line of Markdown as plain words. */
    fun line(raw: String): String {
        if (tableRule.matches(raw)) return ""
        var t = raw
        t = link.replace(t) { it.groupValues[1] }
        t = url.replace(t, "")
        t = heading.replace(t, "")
        t = quote.replace(t, "")
        t = bullet.replace(t, "")
        t = t.replace("`", "").replace("**", "").replace("__", "").replace("~~", "").replace("*", "")
        t = underscores.replace(t, "")
        t = t.replace("|", ", ").replace("$", "")
        t = buildString {
            for (c in t) {
                if (Character.isSurrogate(c) || c == '️' || c == '‍' || c in '☀'..'➿' || c in '⬀'..'⯿') continue
                append(c)
            }
        }
        return t.replace(Regex("""\s+"""), " ").trim().trim(',').trim()
    }

    /** Plain words for the screen: paragraphs kept, code blocks and cards left out. */
    fun display(raw: String): String {
        val out = StringBuilder()
        var i = 0
        val text = raw
        val body = StringBuilder()
        while (i < text.length) {
            if (text.startsWith("```", i)) {
                val close = text.indexOf("```", i + 3)
                i = if (close < 0) text.length else close + 3
                continue
            }
            body.append(text[i])
            i++
        }
        var blank = 0
        for (l in body.lines()) {
            val clean = line(l)
            if (clean.isEmpty()) { blank++; continue }
            if (out.isNotEmpty()) out.append(if (blank > 0) "\n\n" else "\n")
            blank = 0
            out.append(clean)
        }
        return out.toString()
    }

    /**
     * The next piece to say, from [from] on: a whole sentence (or line), cleaned, and where the next one begins. Null while
     * the sentence is still being written (unless [final]: then what is left is said). Code blocks are skipped whole.
     */
    fun next(raw: String, from: Int, final: Boolean): Pair<String, Int>? {
        var i = from
        val sb = StringBuilder()
        while (i < raw.length) {
            if (raw.startsWith("```", i)) {
                if (sb.isNotBlank()) return line(sb.toString()) to i
                val close = raw.indexOf("```", i + 3)
                if (close < 0) return if (final) "" to raw.length else null
                i = close + 3
                continue
            }
            val c = raw[i]
            sb.append(c)
            i++
            val boundary = when {
                c == '\n' -> true
                c == '.' || c == '!' || c == '?' || c == '…' -> (if (i < raw.length) raw[i].isWhitespace() else final) && !listNumber.matches(sb.trim())
                c == ' ' && sb.length >= 260 -> true
                else -> false
            }
            if (boundary) {
                val spoken = line(sb.toString())
                if (spoken.isNotEmpty() || c == '\n' || sb.length >= 260) return spoken to i
            }
        }
        if (final && sb.isNotBlank()) return line(sb.toString()) to raw.length
        return null
    }
}
