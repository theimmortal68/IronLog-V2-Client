package com.jauschua.ironlogv2.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntervalTimerServiceLogicTest {

    @Test
    fun clampedIntervalWorkSeconds_clamps_values_to_valid_range() {
        assertEquals(1, clampedIntervalWorkSeconds(-10))
        assertEquals(1, clampedIntervalWorkSeconds(0))
        assertEquals(1, clampedIntervalWorkSeconds(1))
        assertEquals(30, clampedIntervalWorkSeconds(30))
        assertEquals(59, clampedIntervalWorkSeconds(59))
        assertEquals(59, clampedIntervalWorkSeconds(60))
        assertEquals(59, clampedIntervalWorkSeconds(120))
    }

    @Test
    fun intervalTimerRepBasedLabel_formats_minute_progress() {
        assertEquals("Minute 1 of 5", intervalTimerRepBasedLabel(1, 5))
        assertEquals("Minute 3 of 3", intervalTimerRepBasedLabel(3, 3))
    }

    @Test
    fun intervalTimerToneForTransition_maps_warning_ticks_and_done() {
        assertEquals(RestTimerTone.WARNING, intervalTimerToneForTransition(previous = 16, current = 15))
        assertEquals(RestTimerTone.TICK, intervalTimerToneForTransition(previous = 4, current = 3))
        assertEquals(RestTimerTone.TICK, intervalTimerToneForTransition(previous = 3, current = 2))
        assertEquals(RestTimerTone.TICK, intervalTimerToneForTransition(previous = 2, current = 1))
        assertNull(intervalTimerToneForTransition(previous = 1, current = 0))
        assertNull(intervalTimerToneForTransition(previous = 10, current = null))
        assertNull(intervalTimerToneForTransition(previous = 30, current = 29))
    }

    @Test
    fun countdownSequence_counts_down_to_completion() {
        val sequence = IntervalTimerSequence(IntervalTimerState.Countdown(seconds = 3, label = "Jump Rope"))
        
        val initial = sequence.initialResult()
        assertEquals(3, initial.remainingSeconds)
        assertEquals("Jump Rope", initial.phaseLabel)
        assertNull(initial.tone)
        assertFalse(initial.isFinished)

        val tick1 = sequence.tick()
        assertEquals(2, tick1.remainingSeconds)
        assertEquals(RestTimerTone.TICK, tick1.tone)
        assertFalse(tick1.isFinished)

        val tick2 = sequence.tick()
        assertEquals(1, tick2.remainingSeconds)
        assertEquals(RestTimerTone.TICK, tick2.tone)
        assertFalse(tick2.isFinished)

        val tick3 = sequence.tick()
        assertNull(tick3.remainingSeconds)
        assertEquals(RestTimerTone.DONE, tick3.tone)
        assertTrue(tick3.isFinished)
    }

    @Test
    fun repBasedSequence_loops_totalMinutes_and_emits_tones() {
        val sequence = IntervalTimerSequence(IntervalTimerState.RepBased(totalMinutes = 2, label = "EMOM"))

        val initial = sequence.initialResult()
        assertEquals(60, initial.remainingSeconds)
        assertEquals("Minute 1 of 2", initial.phaseLabel)
        assertEquals(RestTimerTone.DONE, initial.tone)
        assertFalse(initial.isFinished)

        // Advance 59 seconds in round 1
        var result = initial
        repeat(59) { result = sequence.tick() }
        assertEquals(1, result.remainingSeconds)
        assertEquals("Minute 1 of 2", result.phaseLabel)
        assertEquals(RestTimerTone.TICK, result.tone)
        assertFalse(result.isFinished)

        // Tick to round 2
        val round2 = sequence.tick()
        assertEquals(60, round2.remainingSeconds)
        assertEquals("Minute 2 of 2", round2.phaseLabel)
        assertEquals(RestTimerTone.DONE, round2.tone)
        assertFalse(round2.isFinished)

        // Advance 59 seconds in round 2
        repeat(59) { result = sequence.tick() }
        assertEquals(1, result.remainingSeconds)
        assertEquals("Minute 2 of 2", result.phaseLabel)

        // Final completion
        val done = sequence.tick()
        assertNull(done.remainingSeconds)
        assertEquals(RestTimerTone.DONE, done.tone)
        assertTrue(done.isFinished)
    }

    @Test
    fun timeBasedSequence_splits_work_and_rest_clamped() {
        // totalMinutes = 1, workSeconds = 120 (should clamp to 59s work, 1s rest)
        val sequence = IntervalTimerSequence(
            IntervalTimerState.TimeBased(totalMinutes = 1, workSeconds = 120, label = "Finisher")
        )

        val initial = sequence.initialResult()
        assertEquals(59, initial.remainingSeconds)
        assertEquals("Work", initial.phaseLabel)
        assertEquals(RestTimerTone.DONE, initial.tone)
        assertFalse(initial.isFinished)

        // Advance 58 seconds to end of work phase (remaining 1s)
        var result = initial
        repeat(58) { result = sequence.tick() }
        assertEquals(1, result.remainingSeconds)
        assertEquals("Work", result.phaseLabel)
        assertEquals(RestTimerTone.TICK, result.tone)

        // Transition to rest phase
        val rest = sequence.tick()
        assertEquals(1, rest.remainingSeconds)
        assertEquals("Rest", rest.phaseLabel)
        assertEquals(RestTimerTone.DONE, rest.tone)
        assertFalse(rest.isFinished)

        // Transition to done (since totalMinutes = 1)
        val done = sequence.tick()
        assertNull(done.remainingSeconds)
        assertEquals(RestTimerTone.DONE, done.tone)
        assertTrue(done.isFinished)
    }

    @Test
    fun defensiveNoOp_for_zero_or_negative_durations() {
        val zeroCountdown = IntervalTimerSequence(IntervalTimerState.Countdown(0, "Label"))
        assertTrue(zeroCountdown.initialResult().isFinished)

        val negRep = IntervalTimerSequence(IntervalTimerState.RepBased(-1, "Label"))
        assertTrue(negRep.initialResult().isFinished)

        val zeroTime = IntervalTimerSequence(IntervalTimerState.TimeBased(0, 30, "Label"))
        assertTrue(zeroTime.initialResult().isFinished)
    }
}
