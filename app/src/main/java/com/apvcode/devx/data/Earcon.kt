package com.apvcode.devx.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The sounds of the microphone. The phone's speech recognition plays a sound of its own when it opens the microphone and again when it
 * ends (the speech service of Google, on the notification volume); it cannot be told not to. So while the microphone is on, that volume is
 * kept at its lowest step, and Devx plays its own soft chimes instead: a rising one when listening begins, a falling one when it is done.
 *
 * The volume is put back as soon as the listening ends. Since a volume left low by mistake would be a nasty surprise, the value that
 * was there is also written down at once, and put back the next time the app starts if the app was killed in between.
 */
object Earcon {
    private const val RATE = 44_100
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var saved = -1
    private val restoreLater = Runnable { restoreNow(pending ?: return@Runnable) }
    private var pending: Context? = null

    private fun audio(context: Context): AudioManager? = context.applicationContext.getSystemService(AudioManager::class.java)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("earcon", Context.MODE_PRIVATE)

    /** The recognizer's own beeps are turned off (or down to the lowest step) while the microphone is on. */
    fun quiet(context: Context) {
        main.removeCallbacks(restoreLater)
        synchronized(lock) {
            if (saved >= 0) return
            val am = audio(context) ?: return
            val now = runCatching { am.getStreamVolume(AudioManager.STREAM_NOTIFICATION) }.getOrNull() ?: return
            if (now <= 0) return
            saved = now
            prefs(context).edit().putInt("saved", now).commit()
            // Silent if the phone lets an app do that; otherwise (it asks for the Do Not Disturb permission) the lowest step.
            val silenced = runCatching { am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0) }.isSuccess &&
                runCatching { am.getStreamVolume(AudioManager.STREAM_NOTIFICATION) == 0 }.getOrDefault(false)
            if (!silenced) runCatching { am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 1, 0) }
        }
    }

    /** The volume comes back a moment after the listening ends (the recognizer's closing beep is over by then). */
    fun restore(context: Context) {
        pending = context.applicationContext
        main.removeCallbacks(restoreLater)
        main.postDelayed(restoreLater, 450)
    }

    private fun restoreNow(context: Context) {
        synchronized(lock) {
            val was = if (saved >= 0) saved else prefs(context).getInt("saved", -1)
            if (was < 0) return
            val am = audio(context)
            // Only if nobody turned it up or down in the meantime.
            runCatching { if (am != null && am.getStreamVolume(AudioManager.STREAM_NOTIFICATION) <= 1) am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, was, 0) }
            saved = -1
            prefs(context).edit().remove("saved").apply()
        }
    }

    /** At the start of the app: a volume left low by an app that was killed while listening is put back. */
    fun recover(context: Context) {
        if (prefs(context).getInt("saved", -1) >= 0 && saved < 0) restoreNow(context.applicationContext)
    }

    /** Rising chime: the microphone is opening. */
    fun start(context: Context) = play(context, START)

    /** Falling chime: what was said has been taken. */
    fun done(context: Context) = play(context, DONE)

    private val START by lazy { chime(floatArrayOf(880f, 1318.5f), 0.34f, 0.075f) }
    private val DONE by lazy { chime(floatArrayOf(1318.5f, 880f), 0.26f, 0.065f) }

    /** Two soft bell notes, one after the other: a quick attack, a smooth fade, a little overtone for the glassy colour. */
    private fun chime(notes: FloatArray, gain: Float, gap: Float): ShortArray {
        val total = gap * (notes.size - 1) + 0.19f
        val out = FloatArray((total * RATE).toInt())
        notes.forEachIndexed { n, hz ->
            val begin = (n * gap * RATE).toInt()
            val length = (0.19f * RATE).toInt()
            for (i in 0 until length.coerceAtMost(out.size - begin)) {
                val t = i / RATE.toFloat()
                val attack = (t / 0.004f).coerceAtMost(1f)
                val fade = exp(-t / 0.048f)
                val tone = sin(2 * PI * hz * t) + 0.22 * sin(2 * PI * hz * 2 * t) + 0.06 * sin(2 * PI * hz * 3 * t)
                out[begin + i] += (tone * attack * fade * gain).toFloat()
            }
        }
        return ShortArray(out.size) { (out[it].coerceIn(-1f, 1f) * Short.MAX_VALUE * 0.9f).toInt().toShort() }
    }

    private fun play(context: Context, pcm: ShortArray) {
        val am = audio(context) ?: return
        // A silent or vibrating phone stays silent.
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            main.postDelayed({ runCatching { track.release() } }, 700)
        }
    }
}
