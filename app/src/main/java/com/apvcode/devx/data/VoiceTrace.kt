package com.apvcode.devx.data

import android.os.SystemClock
import android.util.Log

/** Marks in the log (tag DevxVoice) with the milliseconds since the assistant was called: where the time goes between a press and the first word. */
object VoiceTrace {
    @Volatile private var t0 = 0L

    fun start(label: String) {
        t0 = SystemClock.elapsedRealtime()
        Log.i("DevxVoice", "0 ms  $label")
    }

    fun mark(label: String) {
        if (t0 == 0L) return
        Log.i("DevxVoice", "${SystemClock.elapsedRealtime() - t0} ms  $label")
    }
}
