package com.apvcode.devx.ui.markdown

import kotlin.math.exp

/**
 * How fast the writing of an answer goes, frame by frame (see [MarkdownText]).
 *
 * The writing trails the text that has arrived by about [LAG_SECONDS], whatever the speed of the model: the more text is waiting,
 * the faster it is written, so it keeps up with a model that is quick and never has to be rushed at the end. The speed itself
 * is eased, so that a burst of text arriving at once does not make the writing jump.
 */
internal object RevealPace {
    /** The slowest the writing goes while there is something to write, in characters a second. */
    const val MIN_CPS = 80f

    /** The fastest it goes (a model can give thousands of characters a second; the eye cannot follow more, and the edge would be a jump). */
    const val MAX_CPS = 4000f

    /** While text is still coming, the writing is about this far behind it. */
    const val LAG_SECONDS = 0.7f

    /** When no more text is coming, what is left is written out in about this long. */
    const val CATCH_UP_SECONDS = 0.6f

    /** How quickly the speed follows what it should be (the time constant of the easing). */
    const val EASE_SECONDS = 0.3f

    /**
     * The speed for the next frame. [speed] is the one of the frame before, [behind] how many characters are waiting,
     * [growing] whether text is still arriving, [dt] the time of the frame in seconds.
     */
    fun next(speed: Float, behind: Float, growing: Boolean, dt: Float): Float {
        val wanted = (if (growing) behind / LAG_SECONDS else behind / CATCH_UP_SECONDS).coerceIn(MIN_CPS, MAX_CPS)
        return speed + (wanted - speed) * (1f - exp(-dt / EASE_SECONDS))
    }
}
