package com.apvcode.devx.data

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import kotlin.math.abs

/**
 * Holds the screen at 60 Hz while an answer is being written. On a 120 Hz screen the phone's graphics chip often needs more than
 * one beat of the screen for a frame and less than two, so the frames come at an uneven pace (two beats, then three) and the
 * movement looks jerky; at 60 Hz every frame has the same time, and the pace is even. Nothing changes otherwise.
 */
object SteadyFrames {
    private fun activityOf(context: Context): Activity? {
        var c = context
        while (c is ContextWrapper && c !is Activity) c = c.baseContext
        return c as? Activity
    }

    fun hold(context: Context, steady: Boolean) {
        val activity = activityOf(context) ?: return
        val window = activity.window
        runCatching {
            @Suppress("DEPRECATION")
            val display = activity.windowManager.defaultDisplay
            val current = display.mode
            // The 60 Hz mode of the same size as the one in use (the screen switches between them without a blink).
            val sixty = display.supportedModes
                .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight && abs(it.refreshRate - 60f) < 1f }
                .minByOrNull { abs(it.refreshRate - 60f) }
            val params: WindowManager.LayoutParams = window.attributes
            val wanted = if (steady && sixty != null) sixty.modeId else 0
            if (params.preferredDisplayModeId != wanted) {
                params.preferredDisplayModeId = wanted
                window.attributes = params
            }
        }
    }
}
