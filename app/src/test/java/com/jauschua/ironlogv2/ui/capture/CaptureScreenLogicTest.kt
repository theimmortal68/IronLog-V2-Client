// CaptureScreenLogicTest.kt
package com.jauschua.ironlogv2.ui.capture

import com.jauschua.ironlogv2.data.api.dto.ExerciseOut
import com.jauschua.ironlogv2.data.api.dto.GroupOut
import com.jauschua.ironlogv2.data.api.dto.PlannedSetOut
import com.jauschua.ironlogv2.ui.screens.capture.flattenPrescription
import com.jauschua.ironlogv2.ui.screens.capture.formatRepsTarget
import com.jauschua.ironlogv2.ui.screens.capture.isAssistedSet
import com.jauschua.ironlogv2.ui.screens.capture.pastSetIds
import com.jauschua.ironlogv2.ui.screens.capture.perSideLabel
import com.jauschua.ironlogv2.ui.screens.capture.prefillReps
import com.jauschua.ironlogv2.ui.screens.capture.prefillWeight
import com.jauschua.ironlogv2.ui.screens.capture.repsTargetLabel
import com.jauschua.ironlogv2.ui.screens.capture.rpeLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 5 — pure display/pre-fill logic for CaptureScreen, plus the Task 4 must-fix
 * (checkmark/"past" derivation must reuse the VM's round-major [flattenPrescription], not a
 * screen-local exercise-major re-derivation).
 *
 * Compose composables (SetCard, SessionContent) are not unit-tested here — the logic they render
 * is extracted into the plain functions under test, per the task brief.
 */
class CaptureScreenLogicTest {

    // ── weight pre-fill: target_load as an editable default ─────────────────────────────

    @Test
    fun prefillWeight_uses_target_load_as_editable_default() {
        assertEquals("135", prefillWeight(135.0))
        assertEquals("137.5", prefillWeight(137.5))
    }

    @Test
    fun prefillWeight_blank_when_target_load_null_needs_calibration() {
        assertEquals("", prefillWeight(null))
    }

    // ── reps display: single number vs range ─────────────────────────────────────────────

    @Test
    fun formatRepsTarget_single_number_when_low_equals_high() {
        assertEquals("8", formatRepsTarget(8, 8))
    }

    @Test
    fun formatRepsTarget_range_when_low_differs_from_high() {
        assertEquals("8-12", formatRepsTarget(8, 12))
    }

    @Test
    fun formatRepsTarget_blank_when_both_null() {
        assertEquals("", formatRepsTarget(null, null))
    }

    @Test
    fun repsTargetLabel_appends_reps_suffix_for_display() {
        val fixed = PlannedSetOut(
            id = 1, set_index = 0, set_role = "WORKING", is_warmup = false,
            target_reps_low = 8, target_reps_high = 8,
        )
        assertEquals("8 reps", repsTargetLabel(fixed))

        val range = PlannedSetOut(
            id = 2, set_index = 0, set_role = "WORKING", is_warmup = false,
            target_reps_low = 8, target_reps_high = 12,
        )
        assertEquals("8-12 reps", repsTargetLabel(range))
    }

    // ── RPE shown prominently — the real progression signal for fixed-rep lifts ─────────

    @Test
    fun rpeLabel_formats_target_rpe() {
        assertEquals("RPE 8", rpeLabel(8.0))
        assertEquals("RPE 7.5", rpeLabel(7.5))
    }

    @Test
    fun rpeLabel_null_when_no_target_rpe() {
        assertNull(rpeLabel(null))
    }

    // ── unilateral "per side" affordance ─────────────────────────────────────────────────

    @Test
    fun perSideLabel_present_for_unilateral_exercise() {
        assertEquals("Per side", perSideLabel(true))
    }

    @Test
    fun perSideLabel_absent_for_bilateral_exercise() {
        assertNull(perSideLabel(false))
    }

    // ── phased pull-up (D4/D6): Set 1 AMRAP blank, Sets 2-3 assisted pair ────────────────

    @Test
    fun isAssistedSet_true_when_either_target_present() {
        val unassistedOnly = PlannedSetOut(
            id = 1, set_index = 0, set_role = "WORKING", is_warmup = false,
            target_unassisted_reps = 8,
        )
        val assistedOnly = PlannedSetOut(
            id = 2, set_index = 1, set_role = "BACKOFF", is_warmup = false,
            target_assisted_reps = 4,
        )
        val neither = PlannedSetOut(id = 3, set_index = 0, set_role = "WORKING", is_warmup = false)

        assertTrue(isAssistedSet(unassistedOnly))
        assertTrue(isAssistedSet(assistedOnly))
        assertFalse(isAssistedSet(neither))
    }

    @Test
    fun prefillReps_set1_amrap_is_blank_for_assisted_exercise() {
        val amrapSet1 = PlannedSetOut(
            id = 1, set_index = 0, set_role = "WORKING", is_warmup = false,
            target_unassisted_reps = 8, // set 1 is still identified as belonging to the assisted scheme
        )
        assertEquals("", prefillReps(amrapSet1))
    }

    @Test
    fun repsTargetLabel_set1_shows_amrap_for_assisted_exercise() {
        val amrapSet1 = PlannedSetOut(
            id = 1, set_index = 0, set_role = "WORKING", is_warmup = false,
            target_unassisted_reps = 8,
        )
        assertEquals("AMRAP", repsTargetLabel(amrapSet1))
    }

    @Test
    fun prefillReps_backoff_sets_show_unassisted_assisted_pair() {
        val backoff = PlannedSetOut(
            id = 2, set_index = 1, set_role = "BACKOFF", is_warmup = false,
            target_unassisted_reps = 8, target_assisted_reps = 4,
        )
        assertEquals("8/4", prefillReps(backoff))
    }

    @Test
    fun repsTargetLabel_backoff_sets_show_unassisted_assisted_pair() {
        val backoff = PlannedSetOut(
            id = 2, set_index = 1, set_role = "BACKOFF", is_warmup = false,
            target_unassisted_reps = 8, target_assisted_reps = 4,
        )
        assertEquals("8 unassisted / 4 assisted", repsTargetLabel(backoff))
    }

    // ── MUST-FIX: pastIds from the ROUND-MAJOR flatten, not a screen-local exercise-major one ──

    /**
     * A GIANT_SET group (3 exercises x 3 rounds) — after round 1 is fully logged, the cursor
     * sits at the first exercise of round 2 (id 101). All THREE round-1 sets (one per exercise:
     * 100, 200, 300) must show as past/checkmarked, because the VM's cursor walks round-major
     * (see [flattenPrescription]'s doc comment). The bug this guards against: CaptureScreen used
     * to build its OWN exercise-major flatten (`g.exercises.flatMap { it.planned_sets }`), under
     * which the round-major id order [100,200,300,101,201,301,102,202,302] gets computed as
     * [100,101,102,200,201,202,300,301,302] instead — the cursor id 101 lands at index 1 in that
     * ordering, so pastSetIds would wrongly return only {100}, dropping exercise-2's and
     * exercise-3's already-logged round-1 sets. `pastSetIds` here is built on the shared
     * `flattenPrescription`, so it gets the round-major answer.
     */
    @Test
    fun pastSetIds_marks_all_round1_sets_past_after_round1_in_giant_set() {
        fun exercise(id: Int, idBase: Int, rounds: Int) = ExerciseOut(
            id = id, movement_id = id, movement_name = "ex$id", order_index = id,
            scheme = "STRAIGHT", objective = "",
            planned_sets = (0 until rounds).map { r ->
                PlannedSetOut(id = idBase + r, set_index = r, set_role = "WORKING", is_warmup = false)
            },
        )
        val e1 = exercise(id = 1, idBase = 100, rounds = 3)
        val e2 = exercise(id = 2, idBase = 200, rounds = 3)
        val e3 = exercise(id = 3, idBase = 300, rounds = 3)
        val group = GroupOut(
            id = 1, order_index = 0, group_type = "GIANT_SET", rounds = 3,
            exercises = listOf(e1, e2, e3),
        )
        val flat = flattenPrescription(listOf(group)) // round-major: [100,200,300,101,201,301,102,202,302]

        val past = pastSetIds(flat, currentPlannedSetId = 101)

        assertEquals(setOf(100, 200, 300), past)
    }

    @Test
    fun pastSetIds_all_past_when_cursor_is_null_all_done() {
        val e1 = ExerciseOut(
            id = 1, movement_id = 1, movement_name = "ex1", order_index = 0,
            scheme = "STRAIGHT", objective = "",
            planned_sets = listOf(PlannedSetOut(id = 1, set_index = 0, set_role = "WORKING", is_warmup = false)),
        )
        val group = GroupOut(id = 1, order_index = 0, group_type = "STRAIGHT", rounds = 1, exercises = listOf(e1))
        val flat = flattenPrescription(listOf(group))

        assertEquals(setOf(1), pastSetIds(flat, currentPlannedSetId = null))
    }
}
