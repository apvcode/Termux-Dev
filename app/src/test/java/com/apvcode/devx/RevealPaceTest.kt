package com.apvcode.devx

import com.apvcode.devx.ui.markdown.RevealPace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The writing of an answer must keep up with a fast model without jumps (see RevealPace). */
class RevealPaceTest {
    private class Run(val maxLag: Float, val maxSpeed: Float, val maxStep: Float, val speedAtEnd: Float, val finishSeconds: Float, val behindAtEnd: Float)

    /** A model that writes [cps] characters a second for [seconds]; the text is taken up every 80 ms, the screen draws 60 frames a second. */
    private fun simulate(cps: Float, seconds: Float): Run {
        val dt = 1f / 60f
        var reveal = 0f
        var target = 0f
        var speed = RevealPace.MIN_CPS
        var maxLag = 0f
        var maxSpeed = 0f
        var maxStep = 0f
        var speedAtEnd = 0f
        var behindAtEnd = 0f
        var t = 0f
        var sampled = 0f
        var finish = 0f
        var lastGrowth = 0f
        var streamEnded = false
        while (t < seconds + 10f) {
            val producing = t < seconds
            if (!producing && !streamEnded) { streamEnded = true; speedAtEnd = speed; behindAtEnd = target - reveal }
            if (t - sampled >= 0.08f) {
                sampled = t
                val next = if (producing) cps * t else cps * seconds
                if (next > target) lastGrowth = t
                target = next
            }
            val growing = t - lastGrowth < 0.4f && producing
            if (reveal < target) {
                val next = RevealPace.next(speed, target - reveal, growing, dt)
                maxStep = maxOf(maxStep, abs(next - speed))
                speed = next
                reveal = minOf(target, reveal + speed * dt)
                maxSpeed = maxOf(maxSpeed, speed)
            } else {
                speed = RevealPace.MIN_CPS
            }
            if (producing && t > 1f) maxLag = maxOf(maxLag, target - reveal)
            if (!producing && reveal >= cps * seconds - 0.5f && finish == 0f) finish = t - seconds
            t += dt
        }
        return Run(maxLag, maxSpeed, maxStep, speedAtEnd, finish, behindAtEnd)
    }

    @Test fun keepsUpWithAVeryFastModel() {
        val run = simulate(cps = 1500f, seconds = 6f)
        // The old ceiling was 420 characters a second; here the writing goes as fast as the model.
        assertTrue("fastest ${run.maxSpeed}", run.maxSpeed > 1000f)
        // It trails the text by about a second of it, not by more and more.
        assertTrue("lag ${run.maxLag}", run.maxLag < 1500f * 1.2f)
    }

    @Test fun neverEndsWithARush() {
        val run = simulate(cps = 1500f, seconds = 6f)
        assertTrue("what is left at the end: ${run.behindAtEnd}", run.behindAtEnd < 1500f * 1.2f)
        assertTrue("it is written out in ${run.finishSeconds} s", run.finishSeconds < 1.2f)
        // The pace at the end is the one of the writing before it, not a sudden several times faster.
        assertTrue("speed at the end ${run.speedAtEnd}", run.speedAtEnd > 800f)
    }

    @Test fun theSpeedNeverJumps() {
        for (cps in listOf(60f, 300f, 900f, 2500f)) {
            val run = simulate(cps, seconds = 5f)
            assertTrue("$cps cps: a step of ${run.maxStep}", run.maxStep < 0.04f * RevealPace.MAX_CPS)
        }
    }

    @Test fun aSlowModelIsWrittenAtOnce() {
        val run = simulate(cps = 30f, seconds = 5f)
        assertTrue("lag ${run.maxLag}", run.maxLag < 30f)
        assertTrue(run.finishSeconds < 0.5f)
    }

    @Test fun neverSlowerThanTheFloorNorFasterThanTheCeiling() {
        assertEquals(RevealPace.MIN_CPS, RevealPace.next(RevealPace.MIN_CPS, 0f, true, 10f), 0.5f)
        assertEquals(RevealPace.MAX_CPS, RevealPace.next(RevealPace.MAX_CPS, 1_000_000f, true, 10f), 0.5f)
    }
}
