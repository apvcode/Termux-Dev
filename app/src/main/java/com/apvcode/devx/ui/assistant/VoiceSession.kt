package com.apvcode.devx.ui.assistant

import android.Manifest
import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.apvcode.devx.AppState
import com.apvcode.devx.data.Msg
import com.apvcode.devx.data.PermissionBridge
import com.apvcode.devx.data.Prefs
import com.apvcode.devx.data.SpeechText
import com.apvcode.devx.data.Speaker
import com.apvcode.devx.data.VoiceInput
import com.apvcode.devx.model.Role
import com.apvcode.devx.ui.chat.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class VoicePhase { Idle, Listening, Thinking, Speaking }

/**
 * One conversation with the voice assistant: it listens, hands the words to the chat (so that every talk is saved as an
 * ordinary chat), reads the answer aloud as it is written, and listens again. All of it can also be done by typing.
 */
@Stable
class VoiceSession(private val context: Context, private val vmSource: () -> ChatViewModel?, private val scope: CoroutineScope) {
    /** The chat model is made while the window is already open (it takes a moment); what needs it waits for it. */
    private val vm: ChatViewModel get() = vmSource() ?: error("the chat model is not ready")

    private suspend fun awaitModel(): ChatViewModel = androidx.compose.runtime.snapshotFlow { vmSource() }.filterNotNull().first()

    val voice = VoiceInput(context, keepWarm = true)
    val speaker = com.apvcode.devx.assist.VoiceWarm.speaker(context).also { it.refresh() }

    /** The person switched the voice of the answers off: they are only shown. */
    var muted by mutableStateOf(Prefs.assistantMuted)
        private set

    /** What the person asked last (said or typed), and the answer to it. */
    var asked by mutableStateOf("")
        private set
    var reply by mutableStateOf<Msg?>(null)
        private set

    /** The phone has no speech recognition: only typing works. */
    var noRecognizer by mutableStateOf(false)
        private set

    /** The person refused the microphone. */
    var micDenied by mutableStateOf(false)

    /** Set by the screen: asks the system for the microphone. */
    var askForMic: (() -> Unit)? = null

    init {
        scope.launch {
            val model = awaitModel()
            androidx.compose.runtime.snapshotFlow { model.loaded }.first { it }
            model.warmConnection()
        }
    }

    private var spokenTurn = false
    private var spokenIdx = 0
    private var turn: Job? = null
    private var userId: String? = null
    private var closed = false
    private var fresh = true

    /** The chat that was open in the app when the assistant came: it is given back when the assistant leaves. */
    private var before: String? = null
    private var kept = false

    /** The talk is going on in the chat of the app now, so that chat is not given back. */
    fun keepChat() { kept = true }

    /**
     * Whether the assistant's own answer is being written. Before the first question the assistant has no chat yet, and whatever
     * the app is busy with (an answer in the chat that was open) is not its business: it is never shown as the assistant's
     * thinking, and nothing the assistant does can stop it.
     */
    private val busy: Boolean get() = !fresh && vmSource()?.generating == true

    val phase: VoicePhase
        get() = when {
            speaker.speaking -> VoicePhase.Speaking
            busy -> VoicePhase.Thinking
            voice.state == VoiceInput.State.Listening -> VoicePhase.Listening
            else -> VoicePhase.Idle
        }

    fun toggleMuted() {
        muted = !muted
        Prefs.saveAssistantMuted(muted)
        if (muted) speaker.stop()
    }

    /** Starts listening (if the microphone is allowed); what is said is sent by itself after a pause. */
    fun listen() {
        if (closed) return
        if (!voice.available()) { noRecognizer = true; return }
        if (!PermissionBridge.has(context, Manifest.permission.RECORD_AUDIO)) { askForMic?.invoke(); return }
        if (busy) return
        micDenied = false
        speaker.stop()
        tick()
        voice.start(VoiceInput.currentTag(), onSentence = { submit(it, byVoice = true) }, idleMs = 12_000)
    }

    /** The person went for the keyboard: the microphone lets go, and nothing listens again by itself until they ask. */
    fun stopListening() {
        voice.cancel()
    }

    /** A small tap in the hand when the microphone opens: it says \"speak now\" with no delay (a sound would be picked up by the microphone). */
    private fun tick() {
        runCatching { context.getSystemService(android.os.Vibrator::class.java)?.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_TICK)) }
    }

    /** The orb was touched: what that means depends on what is going on. */
    fun tapOrb() {
        when (phase) {
            VoicePhase.Listening -> voice.finish { if (it.isNotBlank()) submit(it, true) }
            VoicePhase.Speaking -> { speaker.stop(); spokenTurn = false; listen() }
            VoicePhase.Thinking -> if (busy) vmSource()?.stop()
            VoicePhase.Idle -> listen()
        }
    }

    fun submit(text: String, byVoice: Boolean) {
        val t = text.trim()
        if (t.isEmpty() || busy || closed) return
        com.apvcode.devx.data.VoiceTrace.mark("question sent")
        // Opened from cold: the chat model and the saved data may still be coming in. The question is shown at once and sent when they are.
        val model = vmSource()
        if (model == null || !model.loaded) {
            voice.cancel()
            asked = t
            reply = null
            scope.launch {
                val ready = awaitModel()
                androidx.compose.runtime.snapshotFlow { ready.loaded }.first { it }
                submit(t, byVoice)
            }
            return
        }
        voice.cancel()
        speaker.stop()
        turn?.cancel()
        vm.voiceMode = true
        asked = t
        reply = null
        spokenTurn = byVoice && !muted
        spokenIdx = 0
        // Each talk is a chat of its own: the first question starts it, the follow-ups go on in it.
        if (fresh) {
            before = vm.currentId
            vm.newChat()
            fresh = false
        }
        vm.send(t)
        val user = vm.current?.messages?.lastOrNull { it.role == Role.User } ?: return
        userId = user.id
        turn = scope.launch { watch(user.id) }
    }

    /** Try the failed answer again. */
    fun retry() {
        val failed = reply ?: return
        val id = userId ?: return
        spokenIdx = 0
        vm.retry(failed)
        turn?.cancel()
        turn = scope.launch { watch(id) }
    }

    private suspend fun watch(user: String) {
        val msg = withTimeoutOrNull(4_000) {
            var found: Msg? = null
            while (found == null) {
                found = vm.current?.messages?.lastOrNull { it.role == Role.Assistant && it.parentId == user }
                if (found == null) delay(40)
            }
            found
        } ?: return
        reply = msg
        var firstWords = true
        while (vm.generating) {
            if (firstWords && msg.text.isNotEmpty()) { firstWords = false; com.apvcode.devx.data.VoiceTrace.mark("answer begins") }
            if (spokenTurn) feed(msg, final = false)
            delay(100)
        }
        if (spokenTurn) feed(msg, final = true)
        // After the voice has said it all, listen again: a conversation without touching the phone.
        if (spokenTurn && msg.error == null && Prefs.voiceHandsFree) {
            while (speaker.speaking) delay(120)
            if (!closed && AppState.resumed && !vm.generating) listen()
        }
    }

    private fun feed(msg: Msg, final: Boolean) {
        val raw = msg.text
        while (true) {
            val (piece, next) = SpeechText.next(raw, spokenIdx, final) ?: break
            spokenIdx = next
            if (piece.isNotEmpty()) speaker.speak(piece)
            if (next >= raw.length) break
        }
    }

    /** The screen went out of sight: the microphone is let go (the voice may finish what it is saying). */
    fun pause() {
        voice.cancel()
    }

    /** Leaves: everything stops. The answer, if still being written, is finished into the chat. */
    fun close() {
        closed = true
        turn?.cancel()
        voice.release()
        speaker.stop()
        val model = vmSource()
        model?.voiceMode = false
        // The chat the person had open in the app comes back (it went on being written all this time); the talk stays in the history.
        if (model != null && !fresh && !kept) {
            val back = before
            if (back != null && model.chats.any { it.id == back }) model.openChat(back) else if (back == null) model.newChat()
        }
    }
}
