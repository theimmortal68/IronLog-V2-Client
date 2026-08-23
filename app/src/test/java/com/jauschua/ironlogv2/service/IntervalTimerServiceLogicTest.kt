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
        assertEquals(RestTimerTone.WARNING, intervalTimerToneForTransition(current = 15))
        assertEquals(RestTimerTone.TICK, intervalTimerToneForTransition(current = 3))
        assertEquals(RestTimerTone.TICK, intervalTimerToneForTransition(current = 2))
        assertEquals(RestTimerTone.TICK, intervalTimerToneForTransition(current = 1))
        assertNull(intervalTimerToneForTransition(current = 0))
        assertNull(intervalTimerToneForTransition(current = null))
        assertNull(intervalTimerToneForTransition(current = 29))
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
    fun countdownSequence_withLeadIn_transitions_to_main_countdown() {
        val sequence = IntervalTimerSequence(
            IntervalTimerState.Countdown(seconds = 90, label = "Jump Rope", leadInSeconds = 5)
        )

        val initial = sequence.initialResult()
        assertEquals(5, initial.remainingSeconds)
        assertEquals("Get Ready", initial.phaseLabel)
        assertNull(initial.tone)
        assertFalse(initial.isFinished)

        var result = initial
        listOf(4, 3, 2, 1).forEach { remaining ->
            result = sequence.tick()
            assertEquals(remaining, result.remainingSeconds)
            assertEquals("Get Ready", result.phaseLabel)
            assertNull(result.tone)
            assertFalse(result.isFinished)
        }

        val main = sequence.tick()
        assertEquals(90, main.remainingSeconds)
        assertEquals("Jump Rope", main.phaseLabel)
        assertEquals(RestTimerTone.DONE, main.tone)
        assertFalse(main.isFinished)

        result = main
        repeat(89) { result = sequence.tick() }
        assertEquals(1, result.remainingSeconds)
        assertEquals("Jump Rope", result.phaseLabel)
        assertEquals(RestTimerTone.TICK, result.tone)
        assertFalse(result.isFinished)

        val done = sequence.tick()
        assertNull(done.remainingSeconds)
        assertNull(done.phaseLabel)
        assertEquals(RestTimerTone.DONE, done.tone)
        assertTrue(done.isFinished)
    }

    @Test
    fun countdownSequence_negativeLeadIn_behaves_like_plain_countdown() {
        val sequence = IntervalTimerSequence(
            IntervalTimerState.Countdown(seconds = 3, label = "Jump Rope", leadInSeconds = -5)
        )

        val initial = sequence.initialResult()
        assertEquals(3, initial.remainingSeconds)
        assertEquals("Jump Rope", initial.phaseLabel)
        assertNull(initial.tone)
        assertFalse(initial.isFinished)

        val tick1 = sequence.tick()
        assertEquals(2, tick1.remainingSeconds)
        assertEquals("Jump Rope", tick1.phaseLabel)
        assertEquals(RestTimerTone.TICK, tick1.tone)
        assertFalse(tick1.isFinished)

        val tick2 = sequence.tick()
        assertEquals(1, tick2.remainingSeconds)
        assertEquals("Jump Rope", tick2.phaseLabel)
        assertEquals(RestTimerTone.TICK, tick2.tone)
        assertFalse(tick2.isFinished)

        val tick3 = sequence.tick()
        assertNull(tick3.remainingSeconds)
        assertNull(tick3.phaseLabel)
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
    fun repBasedSequence_withLeadIn_transitions_to_first_minute_then_completes() {
        val sequence = IntervalTimerSequence(
            IntervalTimerState.RepBased(totalMinutes = 3, label = "EMOM", leadInSeconds = 5)
        )

        val initial = sequence.initialResult()
        assertEquals(5, initial.remainingSeconds)
        assertEquals("Get Ready", initial.phaseLabel)
        assertNull(initial.tone)
        assertFalse(initial.isFinished)

        var result = initial
        listOf(4, 3, 2, 1).forEach { remaining ->
            result = sequence.tick()
            assertEquals(remaining, result.remainingSeconds)
            assertEquals("Get Ready", result.phaseLabel)
            assertNull(result.tone)
            assertFalse(result.isFinished)
        }

        val round1 = sequence.tick()
        assertEquals(60, round1.remainingSeconds)
        assertEquals("Minute 1 of 3", round1.phaseLabel)
        assertEquals(RestTimerTone.DONE, round1.tone)
        assertFalse(round1.isFinished)

        result = round1
        repeat(59) { result = sequence.tick() }
        assertEquals(1, result.remainingSeconds)
        assertEquals("Minute 1 of 3", result.phaseLabel)
        assertEquals(RestTimerTone.TICK, result.tone)
        assertFalse(result.isFinished)

        val round2 = sequence.tick()
        assertEquals(60, round2.remainingSeconds)
        assertEquals("Minute 2 of 3", round2.phaseLabel)
        assertEquals(RestTimerTone.DONE, round2.tone)
        assertFalse(round2.isFinished)

        result = round2
        repeat(59) { result = sequence.tick() }
        assertEquals(1, result.remainingSeconds)
        assertEquals("Minute 2 of 3", result.phaseLabel)
        assertEquals(RestTimerTone.TICK, result.tone)
        assertFalse(result.isFinished)

        val round3 = sequence.tick()
        assertEquals(60, round3.remainingSeconds)
        assertEquals("Minute 3 of 3", round3.phaseLabel)
        assertEquals(RestTimerTone.DONE, round3.tone)
        assertFalse(round3.isFinished)

        result = round3
        repeat(59) { result = sequence.tick() }
        assertEquals(1, result.remainingSeconds)
        assertEquals("Minute 3 of 3", result.phaseLabel)
        assertEquals(RestTimerTone.TICK, result.tone)
        assertFalse(result.isFinished)

        val done = sequence.tick()
        assertNull(done.remainingSeconds)
        assertNull(done.phaseLabel)
        assertEquals(RestTimerTone.DONE, done.tone)
        assertTrue(done.isFinished)
    }

    @Test
    fun repBasedSequence_zero_and_negativeLeadIn_match_default_sequence() {
        val defaultSequence = drainSequence(
            IntervalTimerSequence(IntervalTimerState.RepBased(totalMinutes = 2, label = "EMOM"))
        )

        assertEquals(
            defaultSequence,
            drainSequence(
                IntervalTimerSequence(
                    IntervalTimerState.RepBased(totalMinutes = 2, label = "EMOM", leadInSeconds = 0)
                )
            ),
        )
        assertEquals(
            defaultSequence,
            drainSequence(
                IntervalTimerSequence(
                    IntervalTimerState.RepBased(totalMinutes = 2, label = "EMOM", leadInSeconds = -5)
                )
            ),
        )
    }

    @Test
    fun timeBasedSequence_splits_work_and_rest_clamped() {
        // totalMinutes = 1, workSeconds = 120 (clamps to 59s work), restSeconds = 200
        // (clamps to 59s rest) -- deliberately work + rest != 60 to prove rest is a real,
        // independent server-provided field, not derived as 60 - work.
        val sequence = IntervalTimerSequence(
            IntervalTimerState.TimeBased(totalMinutes = 1, workSeconds = 120, restSeconds = 200, label = "Finisher")
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

        // Transition to rest phase -- 59s (clamped restSeconds), NOT 60 - 59 = 1.
        val rest = sequence.tick()
        assertEquals(59, rest.remainingSeconds)
        assertEquals("Rest", rest.phaseLabel)
        assertEquals(RestTimerTone.DONE, rest.tone)
        assertFalse(rest.isFinished)

        // Advance 58 seconds to end of rest phase (remaining 1s)
        repeat(58) { result = sequence.tick() }
        assertEquals(1, result.remainingSeconds)
        assertEquals("Rest", result.phaseLabel)
        assertEquals(RestTimerTone.TICK, result.tone)

        // Transition to done (since totalMinutes = 1)
        val done = sequence.tick()
        assertNull(done.remainingSeconds)
        assertEquals(RestTimerTone.DONE, done.tone)
        assertTrue(done.isFinished)
    }

    @Test
    fun timeBasedSequence_rest_is_independent_of_work_not_derived_as_60_minus_work() {
        // sled_push-shaped params: 20s work, 30s rest -- 20 + 30 = 50 != 60, so the old
        // `60 - workSeconds` derivation (which would have produced 40s rest) is provably gone.
        val sequence = IntervalTimerSequence(
            IntervalTimerState.TimeBased(totalMinutes = 1, workSeconds = 20, restSeconds = 30, label = "Sled Push")
        )

        val initial = sequence.initialResult()
        assertEquals(20, initial.remainingSeconds)
        assertEquals("Work", initial.phaseLabel)

        var result = initial
        repeat(19) { result = sequence.tick() }
        assertEquals(1, result.remainingSeconds)

        val rest = sequence.tick()
        assertEquals(30, rest.remainingSeconds)
        assertEquals("Rest", rest.phaseLabel)
    }

    @Test
    fun timeBasedSequence_withLeadIn_transitions_to_work_then_completes() {
        val sequence = IntervalTimerSequence(
            IntervalTimerState.TimeBased(
                totalMinutes = 2,
                workSeconds = 30,
                restSeconds = 15,
                label = "Tabata",
                leadInSeconds = 5,
            )
        )

        val initial = sequence.initialResult()
        assertEquals(5, initial.remainingSeconds)
        assertEquals("Get Ready", initial.phaseLabel)
        assertNull(initial.tone)
        assertFalse(initial.isFinished)

        var result = initial
        listOf(4, 3, 2, 1).forEach { remaining ->
            result = sequence.tick()
            assertEquals(remaining, result.remainingSeconds)
            assertEquals("Get Ready", result.phaseLabel)
            assertNull(result.tone)
            assertFalse(result.isFinished)
        }

        val round1Work = sequence.tick()
        assertEquals(30, round1Work.remainingSeconds)
        assertEquals("Work", round1Work.phaseLabel)
        assertEquals(RestTimerTone.DONE, round1Work.tone)
        assertFalse(round1Work.isFinished)

        result = round1Work
        repeat(29) { result = sequence.tick() }
        assertEquals(1, result.remainingSeconds)
        assertEquals("Work", result.phaseLabel)
        assertEquals(RestTimerTone.TICK, result.tone)
        assertFalse(result.isFinished)

        val round1Rest = sequence.tick()
        assertEquals(15, round1Rest.remainingSeconds)
        assertEquals("Rest", round1Rest.phaseLabel)
        assertEquals(RestTimerTone.DONE, round1Rest.tone)
        assertFalse(round1Rest.isFinished)

        result = round1Rest
        repeat(14) { result = sequence.tick() }
        assertEquals(1, result.remainingSeconds)
        assertEquals("Rest", result.phaseLabel)
        assertEquals(RestTimerTone.TICK, result.tone)
        assertFalse(result.isFinished)

        val round2Work = sequence.tick()
        assertEquals(30, round2Work.remainingSeconds)
        assertEquals("Work", round2Work.phaseLabel)
        assertEquals(RestTimerTone.DONE, round2Work.tone)
        assertFalse(round2Work.isFinished)

        result = round2Work
        repeat(29) { result = sequence.tick() }
        assertEquals(1, result.remainingSeconds)
        assertEquals("Work", result.phaseLabel)
        assertEquals(RestTimerTone.TICK, result.tone)
        assertFalse(result.isFinished)

        val round2Rest = sequence.tick()
        assertEquals(15, round2Rest.remainingSeconds)
        assertEquals("Rest", round2Rest.phaseLabel)
        assertEquals(RestTimerTone.DONE, round2Rest.tone)
        assertFalse(round2Rest.isFinished)

        result = round2Rest
        repeat(14) { result = sequence.tick() }
        assertEquals(1, result.remainingSeconds)
        assertEquals("Rest", result.phaseLabel)
        assertEquals(RestTimerTone.TICK, result.tone)
        assertFalse(result.isFinished)

        val done = sequence.tick()
        assertNull(done.remainingSeconds)
        assertNull(done.phaseLabel)
        assertEquals(RestTimerTone.DONE, done.tone)
        assertTrue(done.isFinished)
    }

    @Test
    fun timeBasedSequence_zero_and_negativeLeadIn_match_default_sequence() {
        val defaultSequence = drainSequence(
            IntervalTimerSequence(
                IntervalTimerState.TimeBased(totalMinutes = 2, workSeconds = 30, restSeconds = 20, label = "Tabata")
            )
        )

        assertEquals(
            defaultSequence,
            drainSequence(
                IntervalTimerSequence(
                    IntervalTimerState.TimeBased(
                        totalMinutes = 2,
                        workSeconds = 30,
                        restSeconds = 20,
                        label = "Tabata",
                        leadInSeconds = 0,
                    )
                )
            ),
        )
        assertEquals(
            defaultSequence,
            drainSequence(
                IntervalTimerSequence(
                    IntervalTimerState.TimeBased(
                        totalMinutes = 2,
                        workSeconds = 30,
                        restSeconds = 20,
                        label = "Tabata",
                        leadInSeconds = -5,
                    )
                )
            ),
        )
    }

    @Test
    fun timeBasedSequence_loops_rounds_and_splits_work_rest() {
        // workSeconds=30, restSeconds=20 -- deliberately not 60-30=30, so a round-trip through
        // both rounds proves rest tracks the real restSeconds field, not the old derivation.
        val sequence = IntervalTimerSequence(
            IntervalTimerState.TimeBased(totalMinutes = 2, workSeconds = 30, restSeconds = 20, label = "Tabata")
        )

        // Round 1 Work Phase (30s)
        val initial = sequence.initialResult()
        assertEquals(30, initial.remainingSeconds)
        assertEquals("Work", initial.phaseLabel)
        assertEquals(RestTimerTone.DONE, initial.tone)
        assertFalse(initial.isFinished)

        // Advance 29 seconds in Round 1 Work (remaining 1s)
        var result = initial
        repeat(29) { result = sequence.tick() }
        assertEquals(1, result.remainingSeconds)
        assertEquals("Work", result.phaseLabel)
        assertEquals(RestTimerTone.TICK, result.tone)
        assertFalse(result.isFinished)

        // Transition to Round 1 Rest Phase (20s)
        val round1Rest = sequence.tick()
        assertEquals(20, round1Rest.remainingSeconds)
        assertEquals("Rest", round1Rest.phaseLabel)
        assertEquals(RestTimerTone.DONE, round1Rest.tone)
        assertFalse(round1Rest.isFinished)

        // Advance 19 seconds in Round 1 Rest (remaining 1s)
        repeat(19) { result = sequence.tick() }
        assertEquals(1, result.remainingSeconds)
        assertEquals("Rest", result.phaseLabel)
        assertEquals(RestTimerTone.TICK, result.tone)
        assertFalse(result.isFinished)

        // Transition to Round 2 Work Phase (immediately after round-1's REST phase ends)
        val round2Work = sequence.tick()
        assertEquals(30, round2Work.remainingSeconds)
        assertEquals("Work", round2Work.phaseLabel)
        assertEquals(RestTimerTone.DONE, round2Work.tone)
        assertFalse(round2Work.isFinished)

        // Advance 29 seconds in Round 2 Work (remaining 1s)
        repeat(29) { result = sequence.tick() }
        assertEquals(1, result.remainingSeconds)
        assertEquals("Work", result.phaseLabel)
        assertEquals(RestTimerTone.TICK, result.tone)
        assertFalse(result.isFinished)

        // Transition to Round 2 Rest Phase (20s)
        val round2Rest = sequence.tick()
        assertEquals(20, round2Rest.remainingSeconds)
        assertEquals("Rest", round2Rest.phaseLabel)
        assertEquals(RestTimerTone.DONE, round2Rest.tone)
        assertFalse(round2Rest.isFinished)

        // Advance 19 seconds in Round 2 Rest (remaining 1s)
        repeat(19) { result = sequence.tick() }
        assertEquals(1, result.remainingSeconds)
        assertEquals("Rest", result.phaseLabel)
        assertEquals(RestTimerTone.TICK, result.tone)
        assertFalse(result.isFinished)

        // Final completion after Round 2 Rest completes
        val done = sequence.tick()
        assertNull(done.remainingSeconds)
        assertEquals(RestTimerTone.DONE, done.tone)
        assertTrue(done.isFinished)
    }

    // ── EMOM: real reps-at-top-of-minute pacing, 60s/round, label shows target reps ──────

    @Test
    fun emomSequence_loops_totalMinutes_with_reps_label_and_emits_tones() {
        val sequence = IntervalTimerSequence(
            IntervalTimerState.Emom(totalMinutes = 2, repsPerMinute = 4, label = "Sandbag Load")
        )

        val initial = sequence.initialResult()
        assertEquals(60, initial.remainingSeconds)
        assertEquals("4 reps — Round 1 of 2", initial.phaseLabel)
        assertEquals(RestTimerTone.DONE, initial.tone)
        assertFalse(initial.isFinished)

        var result = initial
        repeat(59) { result = sequence.tick() }
        assertEquals(1, result.remainingSeconds)
        assertEquals("4 reps — Round 1 of 2", result.phaseLabel)
        assertEquals(RestTimerTone.TICK, result.tone)
        assertFalse(result.isFinished)

        val round2 = sequence.tick()
        assertEquals(60, round2.remainingSeconds)
        assertEquals("4 reps — Round 2 of 2", round2.phaseLabel)
        assertEquals(RestTimerTone.DONE, round2.tone)
        assertFalse(round2.isFinished)

        repeat(59) { result = sequence.tick() }
        assertEquals(1, result.remainingSeconds)
        assertEquals("4 reps — Round 2 of 2", result.phaseLabel)

        val done = sequence.tick()
        assertNull(done.remainingSeconds)
        assertNull(done.phaseLabel)
        assertEquals(RestTimerTone.DONE, done.tone)
        assertTrue(done.isFinished)
    }

    @Test
    fun emomSequence_withLeadIn_transitions_to_first_round_with_reps_label() {
        val sequence = IntervalTimerSequence(
            IntervalTimerState.Emom(totalMinutes = 1, repsPerMinute = 10, label = "EMOM", leadInSeconds = 5)
        )

        val initial = sequence.initialResult()
        assertEquals(5, initial.remainingSeconds)
        assertEquals("Get Ready", initial.phaseLabel)
        assertNull(initial.tone)
        assertFalse(initial.isFinished)

        var result = initial
        listOf(4, 3, 2, 1).forEach { remaining ->
            result = sequence.tick()
            assertEquals(remaining, result.remainingSeconds)
            assertEquals("Get Ready", result.phaseLabel)
        }

        val round1 = sequence.tick()
        assertEquals(60, round1.remainingSeconds)
        assertEquals("10 reps — Round 1 of 1", round1.phaseLabel)
        assertEquals(RestTimerTone.DONE, round1.tone)
        assertFalse(round1.isFinished)

        result = round1
        repeat(59) { result = sequence.tick() }
        assertEquals(1, result.remainingSeconds)

        val done = sequence.tick()
        assertNull(done.remainingSeconds)
        assertTrue(done.isFinished)
    }

    @Test
    fun emomSequence_defensiveNoOp_for_zero_or_negative_totalMinutes() {
        val zero = IntervalTimerSequence(IntervalTimerState.Emom(totalMinutes = 0, repsPerMinute = 5, label = "L"))
        assertTrue(zero.initialResult().isFinished)

        val negative = IntervalTimerSequence(IntervalTimerState.Emom(totalMinutes = -1, repsPerMinute = 5, label = "L"))
        assertTrue(negative.initialResult().isFinished)
    }

    // ── TabataBlocks: work -> rest -> next round -> ... -> inter-block-rest -> next block -> ──
    //    ... -> finished, walked start-to-finish for 2 rounds/block x 2 blocks.

    @Test
    fun tabataSequence_full_walk_two_rounds_per_block_two_blocks() {
        val sequence = IntervalTimerSequence(
            IntervalTimerState.TabataBlocks(
                workSeconds = 3,
                restSeconds = 2,
                roundsPerBlock = 2,
                blocks = 2,
                interBlockRestSeconds = 4,
                label = "Jump Rope",
            )
        )

        // Block 1, Round 1: Work (3s)
        val r1Work = sequence.initialResult()
        assertEquals(3, r1Work.remainingSeconds)
        assertEquals("Work — Round 1/2, Block 1/2", r1Work.phaseLabel)
        assertEquals(RestTimerTone.DONE, r1Work.tone)
        assertFalse(r1Work.isFinished)

        var result = sequence.tick() // 2
        assertEquals(2, result.remainingSeconds)
        result = sequence.tick() // 1
        assertEquals(1, result.remainingSeconds)
        assertEquals("Work — Round 1/2, Block 1/2", result.phaseLabel)
        assertEquals(RestTimerTone.TICK, result.tone)

        // Block 1, Round 1: Rest (2s)
        result = sequence.tick()
        assertEquals(2, result.remainingSeconds)
        assertEquals("Rest — Round 1/2, Block 1/2", result.phaseLabel)
        assertEquals(RestTimerTone.DONE, result.tone)
        assertFalse(result.isFinished)

        result = sequence.tick() // 1
        assertEquals(1, result.remainingSeconds)
        assertEquals("Rest — Round 1/2, Block 1/2", result.phaseLabel)
        assertEquals(RestTimerTone.TICK, result.tone)

        // Block 1, Round 2: Work (3s) -- round < roundsPerBlock, so straight back to work.
        result = sequence.tick()
        assertEquals(3, result.remainingSeconds)
        assertEquals("Work — Round 2/2, Block 1/2", result.phaseLabel)
        assertEquals(RestTimerTone.DONE, result.tone)
        assertFalse(result.isFinished)

        result = sequence.tick() // 2
        result = sequence.tick() // 1
        assertEquals(1, result.remainingSeconds)
        assertEquals("Work — Round 2/2, Block 1/2", result.phaseLabel)
        assertEquals(RestTimerTone.TICK, result.tone)

        // Block 1, Round 2: Rest (2s)
        result = sequence.tick()
        assertEquals(2, result.remainingSeconds)
        assertEquals("Rest — Round 2/2, Block 1/2", result.phaseLabel)
        assertEquals(RestTimerTone.DONE, result.tone)

        result = sequence.tick() // 1
        assertEquals(1, result.remainingSeconds)
        assertEquals("Rest — Round 2/2, Block 1/2", result.phaseLabel)
        assertEquals(RestTimerTone.TICK, result.tone)

        // round == roundsPerBlock, block < blocks -> Inter-Block-Rest (4s), block becomes 2.
        result = sequence.tick()
        assertEquals(4, result.remainingSeconds)
        assertEquals("Block Rest — Block 2/2", result.phaseLabel)
        assertEquals(RestTimerTone.DONE, result.tone)
        assertFalse(result.isFinished)

        result = sequence.tick() // 3
        result = sequence.tick() // 2
        result = sequence.tick() // 1
        assertEquals(1, result.remainingSeconds)
        assertEquals("Block Rest — Block 2/2", result.phaseLabel)
        assertEquals(RestTimerTone.TICK, result.tone)

        // Inter-block-rest ends -> Block 2, Round 1: Work (3s).
        result = sequence.tick()
        assertEquals(3, result.remainingSeconds)
        assertEquals("Work — Round 1/2, Block 2/2", result.phaseLabel)
        assertEquals(RestTimerTone.DONE, result.tone)
        assertFalse(result.isFinished)

        result = sequence.tick() // 2
        result = sequence.tick() // 1
        assertEquals(1, result.remainingSeconds)
        assertEquals("Work — Round 1/2, Block 2/2", result.phaseLabel)

        // Block 2, Round 1: Rest (2s)
        result = sequence.tick()
        assertEquals(2, result.remainingSeconds)
        assertEquals("Rest — Round 1/2, Block 2/2", result.phaseLabel)
        assertEquals(RestTimerTone.DONE, result.tone)

        result = sequence.tick() // 1
        assertEquals(1, result.remainingSeconds)
        assertEquals("Rest — Round 1/2, Block 2/2", result.phaseLabel)

        // Block 2, Round 2: Work (3s)
        result = sequence.tick()
        assertEquals(3, result.remainingSeconds)
        assertEquals("Work — Round 2/2, Block 2/2", result.phaseLabel)
        assertEquals(RestTimerTone.DONE, result.tone)
        assertFalse(result.isFinished)

        result = sequence.tick() // 2
        result = sequence.tick() // 1
        assertEquals(1, result.remainingSeconds)
        assertEquals("Work — Round 2/2, Block 2/2", result.phaseLabel)

        // Block 2, Round 2: Rest (2s) -- the LAST rest of the LAST round of the LAST block.
        result = sequence.tick()
        assertEquals(2, result.remainingSeconds)
        assertEquals("Rest — Round 2/2, Block 2/2", result.phaseLabel)
        assertEquals(RestTimerTone.DONE, result.tone)

        result = sequence.tick() // 1
        assertEquals(1, result.remainingSeconds)
        assertEquals("Rest — Round 2/2, Block 2/2", result.phaseLabel)
        assertEquals(RestTimerTone.TICK, result.tone)

        // round == roundsPerBlock AND block == blocks -> finished, no inter-block-rest.
        val done = sequence.tick()
        assertNull(done.remainingSeconds)
        assertNull(done.phaseLabel)
        assertEquals(RestTimerTone.DONE, done.tone)
        assertTrue(done.isFinished)
    }

    @Test
    fun tabataSequence_withLeadIn_transitions_to_first_work_phase() {
        val sequence = IntervalTimerSequence(
            IntervalTimerState.TabataBlocks(
                workSeconds = 20,
                restSeconds = 10,
                roundsPerBlock = 1,
                blocks = 1,
                interBlockRestSeconds = 60,
                label = "Jump Rope",
                leadInSeconds = 5,
            )
        )

        val initial = sequence.initialResult()
        assertEquals(5, initial.remainingSeconds)
        assertEquals("Get Ready", initial.phaseLabel)
        assertNull(initial.tone)
        assertFalse(initial.isFinished)

        var result = initial
        listOf(4, 3, 2, 1).forEach { remaining ->
            result = sequence.tick()
            assertEquals(remaining, result.remainingSeconds)
            assertEquals("Get Ready", result.phaseLabel)
        }

        val work = sequence.tick()
        assertEquals(20, work.remainingSeconds)
        assertEquals("Work — Round 1/1, Block 1/1", work.phaseLabel)
        assertEquals(RestTimerTone.DONE, work.tone)
        assertFalse(work.isFinished)
    }

    @Test
    fun tabataSequence_single_round_single_block_finishes_after_one_work_rest_cycle() {
        // roundsPerBlock=1, blocks=1: work -> rest -> finished, no inter-block-rest at all.
        val sequence = IntervalTimerSequence(
            IntervalTimerState.TabataBlocks(
                workSeconds = 5,
                restSeconds = 3,
                roundsPerBlock = 1,
                blocks = 1,
                interBlockRestSeconds = 30,
                label = "Solo",
            )
        )

        val work = sequence.initialResult()
        assertEquals(5, work.remainingSeconds)
        assertEquals("Work — Round 1/1, Block 1/1", work.phaseLabel)

        var result = work
        repeat(4) { result = sequence.tick() }
        assertEquals(1, result.remainingSeconds)

        val rest = sequence.tick()
        assertEquals(3, rest.remainingSeconds)
        assertEquals("Rest — Round 1/1, Block 1/1", rest.phaseLabel)

        result = rest
        repeat(2) { result = sequence.tick() }
        assertEquals(1, result.remainingSeconds)

        val done = sequence.tick()
        assertNull(done.remainingSeconds)
        assertTrue(done.isFinished)
    }

    @Test
    fun tabataSequence_defensiveNoOp_for_zero_blocks_or_roundsPerBlock() {
        val zeroBlocks = IntervalTimerSequence(
            IntervalTimerState.TabataBlocks(
                workSeconds = 20, restSeconds = 10, roundsPerBlock = 8, blocks = 0,
                interBlockRestSeconds = 60, label = "Jump Rope",
            )
        )
        assertTrue(zeroBlocks.initialResult().isFinished)

        val zeroRounds = IntervalTimerSequence(
            IntervalTimerState.TabataBlocks(
                workSeconds = 20, restSeconds = 10, roundsPerBlock = 0, blocks = 2,
                interBlockRestSeconds = 60, label = "Jump Rope",
            )
        )
        assertTrue(zeroRounds.initialResult().isFinished)
    }

    @Test
    fun defensiveNoOp_for_zero_or_negative_durations() {
        val zeroCountdown = IntervalTimerSequence(IntervalTimerState.Countdown(0, "Label"))
        assertTrue(zeroCountdown.initialResult().isFinished)

        val negRep = IntervalTimerSequence(IntervalTimerState.RepBased(-1, "Label"))
        assertTrue(negRep.initialResult().isFinished)

        val zeroTime = IntervalTimerSequence(IntervalTimerState.TimeBased(0, 30, 30, "Label"))
        assertTrue(zeroTime.initialResult().isFinished)
    }

    private fun drainSequence(sequence: IntervalTimerSequence): List<IntervalTickResult> {
        val results = mutableListOf<IntervalTickResult>()
        var result = sequence.initialResult()
        results += result
        while (!result.isFinished) {
            result = sequence.tick()
            results += result
        }
        return results
    }
}
