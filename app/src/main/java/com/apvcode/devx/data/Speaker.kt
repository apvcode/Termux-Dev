package com.apvcode.devx.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
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

    /** True once the speech engine has started (the list of voices can be read then). */
    var engineReady by mutableStateOf(false)
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var firstQueued = true
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
                    engine.setSpeechRate(Prefs.voiceRate)
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) { lastWordAt = SystemClock.elapsedRealtime(); VoiceTrace.mark("voice starts") }
                        override fun onDone(utteranceId: String?) { finished(utteranceId) }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { finished(utteranceId) }
                        override fun onError(utteranceId: String?, errorCode: Int) { finished(utteranceId) }
                        override fun onStop(utteranceId: String?, interrupted: Boolean) { finished(utteranceId) }
                        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) { lastWordAt = SystemClock.elapsedRealtime() }
                    })
                }
                ready = true
                engineReady = true
                VoiceTrace.mark("speech engine ready")
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
        if (firstQueued) { firstQueued = false; VoiceTrace.mark("first words to the voice") }
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
            applyVoice(engine, tag)
        }
        if (engine.speak(text, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.ERROR) finished(id)
    }

    /** The voice for a language: the one the person chose, else the best the phone has (highest quality, then quickest to start). */
    private fun applyVoice(engine: TextToSpeech, tag: String) {
        val chosen = voicesFor(tag).let { list -> list.firstOrNull { it.name == Prefs.voiceName } ?: list.maxWithOrNull(compareBy<Voice>({ it.quality }, { -it.latency })) }
        if (chosen != null) runCatching { engine.voice = chosen } else runCatching { engine.setLanguage(Locale.forLanguageTag(tag)) }
    }

    /** The installed voices of a language, best first. */
    fun voicesFor(tag: String): List<Voice> {
        val language = Locale.forLanguageTag(tag).language
        val all = runCatching { tts?.voices }.getOrNull().orEmpty()
        return all.filter { it.locale.language == language && !it.features.contains("notInstalled") }
            .sortedWith(compareBy<Voice>({ -it.quality }, { it.latency }, { it.name }))
    }

    /** The speed or the voice was changed in the settings: it counts from the next words. */
    fun refresh() {
        languageTag = ""
        runCatching { tts?.setSpeechRate(Prefs.voiceRate) }
    }

    /** Reads a whole text aloud (a message, an audio overview) from the beginning, stopping what was being said. */
    fun read(text: String) {
        stop()
        SpeechText.chunks(text).forEach { speak(it) }
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

    /** The language to read a piece in: by the script (Cyrillic, kana, Han), else the app's language, else the phone's, else English. */
    private fun localeFor(text: String): String {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return languageTag.ifEmpty { "en-US" }
        val cyrillic = letters.count { it in 'Ѐ'..'ӿ' }
        if (cyrillic * 2 >= letters.length) {
            val ukrainian = text.any { it in "іїєґІЇЄҐ" } || Prefs.language == AppLanguage.Ukrainian || (Prefs.language == AppLanguage.System && Locale.getDefault().language == "uk")
            return if (ukrainian) "uk-UA" else "ru-RU"
        }
        if (letters.any { it in '぀'..'ヿ' }) return "ja-JP"
        if (letters.count { it in '一'..'鿿' } * 2 >= letters.length) return "zh-CN"
        val app = Prefs.language.tag?.let { Locale.forLanguageTag(it) }
        val phone = app ?: Locale.getDefault()
        return if (phone.language in setOf("ru", "uk", "bg", "sr", "be")) "en-US" else VoiceInput.fallbackTag().takeIf { app != null } ?: phone.toLanguageTag()
    }
}

/**
 * Turns what a model wrote (Markdown) into what can be read aloud or shown on the small screen of the assistant: no
 * symbols, no code blocks, no links. [next] cuts the text into sentences as it grows.
 */
object SpeechText {
    /** A longer text as pieces to be spoken one after another: by paragraph, then by sentence, none longer than [max] letters. */
    fun chunks(raw: String, max: Int = 380): List<String> {
        val out = ArrayList<String>()
        for (para in display(raw).split(Regex("\\n+")).map { it.trim() }.filter { it.isNotEmpty() }) {
            var cur = StringBuilder()
            for (sentence in para.split(Regex("(?<=[.!?…])\\s+"))) {
                if (cur.isNotEmpty() && cur.length + sentence.length + 1 > max) { out += cur.toString(); cur = StringBuilder() }
                if (sentence.length > max) { sentence.chunked(max).forEach { out += it }; continue }
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(sentence)
            }
            if (cur.isNotEmpty()) out += cur.toString()
        }
        return out
    }

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
                // A long sentence is said from its first clause on: the voice starts sooner.
                (c == ',' || c == ';' || c == ':' || c == '—') && sb.length >= 70 && (i >= raw.length || raw[i].isWhitespace()) -> true
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
