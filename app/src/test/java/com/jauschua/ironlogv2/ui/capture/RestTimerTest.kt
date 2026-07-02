// RestTimerTest.kt
package com.jauschua.ironlogv2.ui.capture

import com.jauschua.ironlogv2.data.api.dto.ExerciseOut
import com.jauschua.ironlogv2.data.api.dto.FeedbackTap
import com.jauschua.ironlogv2.data.api.dto.GroupOut
import com.jauschua.ironlogv2.data.api.dto.PlannedSetOut
import com.jauschua.ironlogv2.ui.screens.capture.formatRestTime
import com.jauschua.ironlogv2.ui.screens.capture.restSeconds
import com.jauschua.ironlogv2.ui.screens.capture.shouldStartRest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 6 — client rest timer.
 *
 * [restSeconds] is the pure RPE-adaptive duration function (T1/T1b scale by tap, everything
 * else is fixed). [shouldStartRest] decides the auto-start trigger: STRAIGHT groups rest after
 * every set, GIANT_SET groups only after the round's last item — which, because
 * [com.jauschua.ironlogv2.ui.screens.capture.flattenPrescription] flattens round-major, is
 * always the group's LAST exercise.
 */
class RestTimerTest {

    // ── restSeconds: T1 (and T1b) are RPE-adaptive, everything else is fixed ────────────

    @Test
    fun restSeconds_t1_too_easy_scales_down_to_075x() {
        assertEquals(90, restSeconds(120, "T1", FeedbackTap.TOO_EASY, false))
    }

    @Test
    fun restSeconds_t1_on_target_keeps_base() {
        assertEquals(120, restSeconds(120, "T1", FeedbackTap.ON_TARGET, false))
    }

    @Test
    fun restSeconds_t1_too_hard_scales_up_to_15x() {
        assertEquals(180, restSeconds(120, "T1", FeedbackTap.TOO_HARD, false))
    }

    @Test
    fun restSeconds_t1b_is_adaptive_like_t1() {
        assertEquals(120, restSeconds(120, "T1b", FeedbackTap.ON_TARGET, false))
    }

    @Test
    fun restSeconds_t2_giant_set_is_fixed_tap_ignored() {
        assertEquals(90, restSeconds(90, "T2 GS", FeedbackTap.TOO_HARD, true))
    }

    @Test
    fun restSeconds_t3_giant_set_is_fixed_tap_ignored() {
        assertEquals(60, restSeconds(60, "T3 GS", FeedbackTap.TOO_EASY, true))
    }

    // ── shouldStartRest: STRAIGHT after each set, GIANT_SET after the round's last item ──

    private fun exercise(id: Int) = ExerciseOut(
        id = id, movement_id = id, movement_name = "ex$id", order_index = id,
        scheme = "STRAIGHT", objective = "",
        planned_sets = listOf(PlannedSetOut(id = id * 10, set_index = 0, set_role = "WORKING", is_warmup = false)),
    )

    @Test
    fun shouldStartRest_true_for_every_exercise_in_straight_group() {
        val e1 = exercise(1)
        val e2 = exercise(2)
        val group = GroupOut(id = 1, order_index = 0, group_type = "STRAIGHT", rounds = 1, exercises = listOf(e1, e2))
        assertTrue(shouldStartRest(group, e1))
        assertTrue(shouldStartRest(group, e2))
    }

    @Test
    fun shouldStartRest_only_true_for_last_exercise_in_giant_set() {
        val e1 = exercise(1)
        val e2 = exercise(2)
        val e3 = exercise(3)
        val group = GroupOut(
            id = 1, order_index = 0, group_type = "GIANT_SET", rounds = 1,
            exercises = listOf(e1, e2, e3),
        )
        assertFalse(shouldStartRest(group, e1))
        assertFalse(shouldStartRest(group, e2))
        assertTrue(shouldStartRest(group, e3))
    }

    // ── formatRestTime: mm:ss display for the countdown label ───────────────────────────

    @Test
    fun formatRestTime_pads_seconds_under_a_minute() {
        assertEquals("0:05", formatRestTime(5))
    }

    @Test
    fun formatRestTime_shows_minutes_and_seconds() {
        assertEquals("2:03", formatRestTime(123))
    }
}
