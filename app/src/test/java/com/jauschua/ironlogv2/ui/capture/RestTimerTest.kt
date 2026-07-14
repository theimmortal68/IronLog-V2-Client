// RestTimerTest.kt
package com.jauschua.ironlogv2.ui.capture

import com.jauschua.ironlogv2.data.api.dto.ExerciseOut
import com.jauschua.ironlogv2.data.api.dto.FeedbackTap
import com.jauschua.ironlogv2.data.api.dto.GroupOut
import com.jauschua.ironlogv2.data.api.dto.PlannedSetOut
import com.jauschua.ironlogv2.ui.screens.capture.LoggedSetActual
import com.jauschua.ironlogv2.ui.screens.capture.formatRestTime
import com.jauschua.ironlogv2.ui.screens.capture.hardestTapForRound
import com.jauschua.ironlogv2.ui.screens.capture.restContextByPlannedSetId
import com.jauschua.ironlogv2.ui.screens.capture.restSeconds
import com.jauschua.ironlogv2.ui.screens.capture.roundPlannedSetIdsBySetId
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

    // Giant sets are now RPE-adaptive: tap scales duration (behavior change per spec 01)
    @Test
    fun restSeconds_t2_giant_set_is_adaptive() {
        assertEquals(135, restSeconds(90, "T2 GS", FeedbackTap.TOO_HARD, true))
    }

    @Test
    fun restSeconds_t3_giant_set_is_adaptive() {
        assertEquals(45, restSeconds(60, "T3 GS", FeedbackTap.TOO_EASY, true))
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

    // ── restContextByPlannedSetId: rest fires only when a further set follows (Change 1) ──

    private fun exerciseWithSets(id: Int, setIds: List<Int>, warmupSetIds: Set<Int> = emptySet()) = ExerciseOut(
        id = id, movement_id = id, movement_name = "ex$id", order_index = id,
        scheme = "STRAIGHT", objective = "",
        planned_sets = setIds.mapIndexed { i, sid ->
            val isWarmup = sid in warmupSetIds
            PlannedSetOut(
                id = sid,
                set_index = i,
                set_role = if (isWarmup) "WARMUP" else "WORKING",
                is_warmup = isWarmup,
            )
        },
    )

    @Test
    fun restContext_straight_triggers_every_set_except_the_last() {
        val e = exerciseWithSets(1, listOf(101, 102, 103))
        val group = GroupOut(
            id = 1, order_index = 0, group_type = "STRAIGHT", rounds = 3,
            rest_seconds = 90, exercises = listOf(e),
        )
        val ctx = restContextByPlannedSetId(listOf(group))
        assertTrue(ctx.getValue(101).triggersRest)
        assertTrue(ctx.getValue(102).triggersRest)
        assertFalse(ctx.getValue(103).triggersRest)
    }

    @Test
    fun restContext_giant_set_triggers_only_last_exercise_and_never_the_final_round() {
        val e1 = exerciseWithSets(1, listOf(101, 102, 103))
        val e2 = exerciseWithSets(2, listOf(201, 202, 203))
        val e3 = exerciseWithSets(3, listOf(301, 302, 303))
        val group = GroupOut(
            id = 1, order_index = 0, group_type = "GIANT_SET", rounds = 3,
            rest_seconds = 60, exercises = listOf(e1, e2, e3),
        )
        val ctx = restContextByPlannedSetId(listOf(group))
        // Non-last exercises never own the trigger — every set false.
        listOf(101, 102, 103, 201, 202, 203).forEach {
            assertFalse(ctx.getValue(it).triggersRest)
        }
        // Last exercise: rounds 1 & 2 trigger, the final round (its last set) is suppressed.
        assertTrue(ctx.getValue(301).triggersRest)
        assertTrue(ctx.getValue(302).triggersRest)
        assertFalse(ctx.getValue(303).triggersRest)
    }

    @Test
    fun restContext_suppresses_rest_between_consecutive_warmup_sets_only() {
        val e = exerciseWithSets(
            id = 1,
            setIds = listOf(101, 102, 103, 104, 105),
            warmupSetIds = setOf(101, 102, 103),
        )
        val group = GroupOut(
            id = 1, order_index = 0, group_type = "STRAIGHT", rounds = 5,
            rest_seconds = 120, exercises = listOf(e),
        )

        val ctx = restContextByPlannedSetId(listOf(group))

        assertFalse(ctx.getValue(101).triggersRest)
        assertFalse(ctx.getValue(102).triggersRest)
        assertTrue(ctx.getValue(103).triggersRest)
        assertTrue(ctx.getValue(104).triggersRest)
        assertFalse(ctx.getValue(105).triggersRest)
    }

    @Test
    fun restContext_noWarmupSets_preserves_existing_trigger_values() {
        val e = exerciseWithSets(1, listOf(101, 102, 103, 104))
        val group = GroupOut(
            id = 1, order_index = 0, group_type = "STRAIGHT", rounds = 4,
            rest_seconds = 90, exercises = listOf(e),
        )

        val triggersBySetId = restContextByPlannedSetId(listOf(group))
            .mapValues { (_, ctx) -> ctx.triggersRest }

        assertEquals(
            mapOf(
                101 to true,
                102 to true,
                103 to true,
                104 to false,
            ),
            triggersBySetId,
        )
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

    // ── hardestTapForRound: reduces round taps to worst (TOO_HARD > ON_TARGET > TOO_EASY) ──

    @Test
    fun hardestTapForRound_returns_too_hard_if_any_too_hard() {
        val actuals = mapOf(
            (101 to 0) to LoggedSetActual(100.0, 5, "TOO_EASY"),
            (102 to 0) to LoggedSetActual(100.0, 5, "TOO_HARD"),
            (103 to 0) to LoggedSetActual(100.0, 5, "ON_TARGET")
        )
        assertEquals(FeedbackTap.TOO_HARD, hardestTapForRound(actuals, listOf(101, 102, 103)))
    }

    @Test
    fun hardestTapForRound_returns_on_target_if_missing_or_default() {
        val actuals = mapOf(
            (101 to 0) to LoggedSetActual(100.0, 5, "TOO_EASY")
            // 102 missing/unlogged
        )
        assertEquals(FeedbackTap.ON_TARGET, hardestTapForRound(actuals, listOf(101, 102)))
    }

    @Test
    fun hardestTapForRound_returns_too_easy_if_all_too_easy() {
        val actuals = mapOf(
            (101 to 0) to LoggedSetActual(100.0, 5, "TOO_EASY"),
            (102 to 0) to LoggedSetActual(100.0, 5, "TOO_EASY")
        )
        assertEquals(FeedbackTap.TOO_EASY, hardestTapForRound(actuals, listOf(101, 102)))
    }

    // ── roundPlannedSetIdsBySetId: maps set id to round planned set ids ──────────────────

    @Test
    fun roundPlannedSetIdsBySetId_groups_by_round_index_for_giant_set() {
        val e1 = exerciseWithSets(1, listOf(101, 102))
        val e2 = exerciseWithSets(2, listOf(201, 202))
        val group = GroupOut(
            id = 1, order_index = 0, group_type = "GIANT_SET", rounds = 2,
            exercises = listOf(e1, e2)
        )
        val map = roundPlannedSetIdsBySetId(listOf(group))
        assertEquals(listOf(101, 201), map[101])
        assertEquals(listOf(101, 201), map[201])
        assertEquals(listOf(102, 202), map[102])
        assertEquals(listOf(102, 202), map[202])
    }
}
