// CaptureScreenLogicTest.kt
package com.jauschua.ironlogv2.ui.capture

import com.jauschua.ironlogv2.data.api.dto.ExerciseOut
import com.jauschua.ironlogv2.data.api.dto.FinisherOut
import com.jauschua.ironlogv2.data.api.dto.GroupOut
import com.jauschua.ironlogv2.data.api.dto.PlannedSetOut
import com.jauschua.ironlogv2.ui.screens.capture.bandNames
import com.jauschua.ironlogv2.ui.screens.capture.effectiveLoadPrefill
import com.jauschua.ironlogv2.ui.screens.capture.FinisherTimerMode
import com.jauschua.ironlogv2.ui.screens.capture.finisherTimerMode
import com.jauschua.ironlogv2.ui.screens.capture.flattenPrescription
import com.jauschua.ironlogv2.ui.screens.capture.formatRepsTarget
import com.jauschua.ironlogv2.ui.screens.capture.groupProgressHint
import com.jauschua.ironlogv2.ui.screens.capture.htObservedPeak
import com.jauschua.ironlogv2.ui.screens.capture.htReconfigure
import com.jauschua.ironlogv2.ui.screens.capture.htSetupLine
import com.jauschua.ironlogv2.ui.screens.capture.isSetEditable
import com.jauschua.ironlogv2.ui.screens.capture.loadDisplayLabel
import com.jauschua.ironlogv2.ui.screens.capture.loadInputLabel
import com.jauschua.ironlogv2.ui.screens.capture.loggedActualLine
import com.jauschua.ironlogv2.ui.screens.capture.pastSetIds
import com.jauschua.ironlogv2.ui.screens.capture.perSideLabel
import com.jauschua.ironlogv2.ui.screens.capture.prefillReps
import com.jauschua.ironlogv2.ui.screens.capture.prefillWeight
import com.jauschua.ironlogv2.ui.screens.capture.repsInputLabel
import com.jauschua.ironlogv2.ui.screens.capture.repsTargetLabel
import com.jauschua.ironlogv2.ui.screens.capture.rpeLabel
import com.jauschua.ironlogv2.ui.screens.capture.shoeTransition
import com.jauschua.ironlogv2.ui.screens.capture.tapResultLabel
import com.jauschua.ironlogv2.ui.screens.capture.withCarriedLoad
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    // ── Spec 15: finisher interval timer mode selection ─────────────────────────────────

    @Test
    fun finisherTimerMode_rep_based_when_target_reps_per_minute_present() {
        val finisher = FinisherOut(
            exercise_name = "jump_rope",
            duration_minutes = 8,
            params = JsonObject(mapOf("target_reps_per_minute" to JsonPrimitive(80))),
        )

        assertEquals(
            FinisherTimerMode.RepBased(totalMinutes = 8, label = "Jump Rope"),
            finisherTimerMode(finisher),
        )
    }

    @Test
    fun finisherTimerMode_time_based_when_work_seconds_per_minute_present() {
        val finisher = FinisherOut(
            exercise_name = "burpees",
            duration_minutes = 10,
            params = JsonObject(mapOf("work_seconds_per_minute" to JsonPrimitive(35))),
        )

        assertEquals(
            FinisherTimerMode.TimeBased(totalMinutes = 10, workSeconds = 35, label = "Burpees"),
            finisherTimerMode(finisher),
        )
    }

    @Test
    fun finisherTimerMode_none_when_no_timer_param_present() {
        val finisher = FinisherOut(
            exercise_name = "burpees",
            duration_minutes = 10,
            params = JsonObject(emptyMap()),
        )

        assertEquals(FinisherTimerMode.None, finisherTimerMode(finisher))
    }

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

    @Test
    fun loadDisplayLabel_uses_degrees_for_assist_unit_hint() {
        assertEquals("25°", loadDisplayLabel(25.0, "assist"))
        assertEquals("12.5°", loadDisplayLabel(12.5, "assist"))
    }

    @Test
    fun loadDisplayLabel_uses_pounds_for_lb_or_missing_unit_hint() {
        assertEquals("25lb", loadDisplayLabel(25.0, "lb"))
        assertEquals("25lb", loadDisplayLabel(25.0, null))
    }

    @Test
    fun loadInputLabel_uses_assist_label_only_for_assist_unit_hint() {
        assertEquals("Assist (°)", loadInputLabel("assist"))
        assertEquals("Load (lb)", loadInputLabel("lb"))
        assertEquals("Load (lb)", loadInputLabel(null))
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

    // ── reps INPUT pre-fill: numeric-only default so tap-without-edit never logs null ────
    // (fixed target → the number; range target → the low end; full range shown separately
    // via repsInputLabel, not baked into the editable value).

    @Test
    fun prefillReps_fixed_target_prefills_the_number() {
        val fixed = PlannedSetOut(
            id = 1, set_index = 0, set_role = "WORKING", is_warmup = false,
            target_reps_low = 8, target_reps_high = 8,
        )
        assertEquals("8", prefillReps(fixed))
    }

    @Test
    fun prefillReps_range_target_prefills_the_low_end() {
        val range = PlannedSetOut(
            id = 2, set_index = 0, set_role = "WORKING", is_warmup = false,
            target_reps_low = 8, target_reps_high = 12,
        )
        assertEquals("8", prefillReps(range))
    }

    @Test
    fun prefillReps_blank_when_no_reps_target() {
        val noTarget = PlannedSetOut(id = 3, set_index = 0, set_role = "WORKING", is_warmup = false)
        assertEquals("", prefillReps(noTarget))
    }

    @Test
    fun repsInputLabel_shows_full_range_next_to_the_numeric_prefill() {
        val range = PlannedSetOut(
            id = 2, set_index = 0, set_role = "WORKING", is_warmup = false,
            target_reps_low = 8, target_reps_high = 12,
        )
        assertEquals("Reps (8-12)", repsInputLabel(range))
    }

    @Test
    fun repsInputLabel_plain_for_fixed_target() {
        val fixed = PlannedSetOut(
            id = 1, set_index = 0, set_role = "WORKING", is_warmup = false,
            target_reps_low = 8, target_reps_high = 8,
        )
        assertEquals("Reps", repsInputLabel(fixed))
    }

    // ── assisted movements (e.g. pull-ups) render the STANDARD target ────────────────────
    // The phased pull-up AMRAP / assisted-pair widget is DEFERRED — the server never populates
    // target_unassisted_reps/target_assisted_reps on PlannedSetOut (confirmed), so the client no
    // longer branches on them. This guards against a regression to that dead code path even if
    // those fields happen to be present (they mirror the server's DTO field-for-field but the
    // server never writes them today).

    @Test
    fun repsTargetLabel_assisted_movement_shows_standard_target_not_phased_phrasing() {
        val assistedMovement = PlannedSetOut(
            id = 1, set_index = 0, set_role = "WORKING", is_warmup = false,
            target_load = 20.0, target_reps_low = 6, target_reps_high = 6,
            target_unassisted_reps = 8, target_assisted_reps = 4,
        )
        assertEquals("6 reps", repsTargetLabel(assistedMovement))
    }

    @Test
    fun prefillReps_assisted_movement_prefills_the_standard_numeric_target() {
        val assistedMovement = PlannedSetOut(
            id = 1, set_index = 0, set_role = "WORKING", is_warmup = false,
            target_load = 20.0, target_reps_low = 6, target_reps_high = 6,
            target_unassisted_reps = 8, target_assisted_reps = 4,
        )
        assertEquals("6", prefillReps(assistedMovement))
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

    // ── groupProgressHint: collapsed-card progress ("k/N sets" or "✓ done") ──────────────

    private fun groupWithSetIds(setIds: List<Int>): GroupOut {
        val exercise = ExerciseOut(
            id = 1, movement_id = 1, movement_name = "ex1", order_index = 0,
            scheme = "STRAIGHT", objective = "",
            planned_sets = setIds.mapIndexed { i, sid ->
                PlannedSetOut(id = sid, set_index = i, set_role = "WORKING", is_warmup = false)
            },
        )
        return GroupOut(
            id = 1, order_index = 0, group_type = "STRAIGHT", rounds = setIds.size,
            exercises = listOf(exercise),
        )
    }

    @Test
    fun groupProgressHint_none_logged_shows_zero_of_total() {
        val group = groupWithSetIds(listOf(10, 11, 12))
        assertEquals("0/3 sets", groupProgressHint(group, pastIds = emptySet()))
    }

    @Test
    fun groupProgressHint_some_logged_shows_logged_of_total() {
        val group = groupWithSetIds(listOf(10, 11, 12))
        assertEquals("2/3 sets", groupProgressHint(group, pastIds = setOf(10, 11)))
    }

    @Test
    fun groupProgressHint_all_logged_shows_done() {
        val group = groupWithSetIds(listOf(10, 11, 12))
        assertEquals("✓ done", groupProgressHint(group, pastIds = setOf(10, 11, 12)))
    }

    @Test
    fun groupProgressHint_empty_group_is_not_done() {
        val group = groupWithSetIds(emptyList())
        assertEquals("0/0 sets", groupProgressHint(group, pastIds = emptySet()))
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

    // ── shoeTransition: shoe-swap cue banner decision ────────────────────────────────────
    // Pure helper backing the per-group "👟 Swap to X" banner — non-null iff the group's shoe
    // differs from the previous group's shoe AND is itself non-null (no banner for a group with
    // no shoe assigned, even if the previous one had one).

    @Test
    fun shoeTransition_returns_new_shoe_when_changed() {
        assertEquals("Adipower II", shoeTransition(prevShoe = "Metcon 9", thisShoe = "Adipower II"))
    }

    @Test
    fun shoeTransition_null_when_unchanged() {
        assertNull(shoeTransition(prevShoe = "Metcon 9", thisShoe = "Metcon 9"))
    }

    @Test
    fun shoeTransition_null_when_this_shoe_is_null() {
        assertNull(shoeTransition(prevShoe = "Metcon 9", thisShoe = null))
    }

    @Test
    fun shoeTransition_returns_shoe_when_previous_is_null() {
        assertEquals("Metcon 9", shoeTransition(prevShoe = null, thisShoe = "Metcon 9"))
    }

    // ── Task 6: HT band-composite — reconfigure cue ─────────────────────────────────────
    // Fires on an OR of (band config differs) OR (plate count differs) — not an AND, and not
    // config-only. Null-safe/total for any combination of nulls.

    @Test
    fun htReconfigure_fires_when_band_config_differs() {
        assertNotNull(htReconfigure(prevPlates = 205.0, prevConfig = listOf(0), plates = 166.0, config = listOf(0, 1)))
    }

    @Test
    fun htReconfigure_fires_when_only_plate_count_differs() {
        // same config, plates changed 205 -> 210: still fires under the OR rule.
        assertNotNull(htReconfigure(prevPlates = 205.0, prevConfig = listOf(0), plates = 210.0, config = listOf(0)))
    }

    @Test
    fun htReconfigure_null_when_nothing_differs() {
        assertNull(htReconfigure(prevPlates = 205.0, prevConfig = listOf(0), plates = 205.0, config = listOf(0)))
    }

    @Test
    fun htReconfigure_null_when_prev_and_current_both_fully_null() {
        assertNull(htReconfigure(prevPlates = null, prevConfig = null, plates = null, config = null))
    }

    @Test
    fun htReconfigure_fires_when_prev_null_and_current_non_null() {
        assertNotNull(htReconfigure(prevPlates = null, prevConfig = null, plates = 166.0, config = listOf(0)))
    }

    @Test
    fun htReconfigure_null_when_current_fully_null_even_if_prev_was_set() {
        // Nothing to reconfigure TO — don't recommend an empty setup.
        assertNull(htReconfigure(prevPlates = 205.0, prevConfig = listOf(0), plates = null, config = null))
    }

    // ── Task 6: HT band-composite — observed peak (single-band only) ────────────────────

    @Test
    fun htObservedPeak_single_band_is_felt_minus_plates() {
        assertEquals(155.0, htObservedPeak(feltPeak = 255.0, plates = 100.0, config = listOf(2))!!, 0.001)
    }

    @Test
    fun htObservedPeak_null_for_multi_band() {
        assertNull(htObservedPeak(feltPeak = 255.0, plates = 100.0, config = listOf(0, 1)))
    }

    @Test
    fun htObservedPeak_null_when_config_null_or_empty() {
        assertNull(htObservedPeak(feltPeak = 255.0, plates = 100.0, config = null))
        assertNull(htObservedPeak(feltPeak = 255.0, plates = 100.0, config = emptyList()))
    }

    @Test
    fun htObservedPeak_null_when_felt_peak_or_plates_null() {
        assertNull(htObservedPeak(feltPeak = null, plates = 100.0, config = listOf(0)))
        assertNull(htObservedPeak(feltPeak = 255.0, plates = null, config = listOf(0)))
    }

    // ── Task 6: HT band-composite — band id -> name mapping ─────────────────────────────

    @Test
    fun bandNames_maps_ids_to_names_in_order() {
        // server BandPair ids are 1-based: Orange=1, Red=2 (regression: id 1 must be Orange, not Red)
        assertEquals(listOf("Orange", "Red"), bandNames(listOf(1, 2)))
    }

    @Test
    fun bandNames_skips_out_of_range_ids_defensively() {
        assertEquals(listOf("Orange"), bandNames(listOf(1, 0, 99)))  // 1=Orange; 0 and 99 out of range -> dropped
    }

    @Test
    fun bandNames_empty_for_null_or_empty_config() {
        assertEquals(emptyList<String>(), bandNames(null))
        assertEquals(emptyList<String>(), bandNames(emptyList()))
    }

    // ── Task 6: HT band-composite — setup line composer ─────────────────────────────────

    @Test
    fun htSetupLine_composes_plates_bands_and_peak() {
        assertEquals(
            "166 plates + Orange, Red · peak ~250",
            htSetupLine(plates = 166.0, config = listOf(1, 2), targetFeltPeak = 250.0),
        )
    }

    @Test
    fun htSetupLine_plates_only() {
        assertEquals("166 plates", htSetupLine(plates = 166.0, config = null, targetFeltPeak = null))
    }

    @Test
    fun htSetupLine_bands_only() {
        assertEquals("Orange, Red", htSetupLine(plates = null, config = listOf(1, 2), targetFeltPeak = null))
    }

    @Test
    fun htSetupLine_peak_only() {
        assertEquals("peak ~250", htSetupLine(plates = null, config = null, targetFeltPeak = 250.0))
    }

    // ── Fix B: loggedActualLine — the ACTUAL logged line for a past set's card ─────────────

    @Test
    fun loggedActualLine_composes_load_reps_and_tap() {
        assertEquals("165lb × 6 reps · ✓ on target", loggedActualLine(165.0, 6, "ON_TARGET"))
    }

    @Test
    fun loggedActualLine_too_easy_and_too_hard_labels() {
        assertEquals("155lb × 8 reps · ↓ easy", loggedActualLine(155.0, 8, "TOO_EASY"))
        assertEquals("175lb × 4 reps · ↑ hard", loggedActualLine(175.0, 4, "TOO_HARD"))
    }

    @Test
    fun loggedActualLine_omits_missing_pieces_never_shows_null() {
        assertEquals("6 reps", loggedActualLine(null, 6, null))
        assertEquals("165lb", loggedActualLine(165.0, null, null))
        assertEquals("", loggedActualLine(null, null, null))
    }

    @Test
    fun loggedActualLine_uses_degrees_for_assist_unit_hint() {
        assertEquals("25° × 8 reps · ✓ on target", loggedActualLine(25.0, 8, "ON_TARGET", "assist"))
    }

    @Test
    fun loggedActualLine_uses_pounds_for_lb_or_missing_unit_hint() {
        assertEquals("25lb × 8 reps", loggedActualLine(25.0, 8, null, "lb"))
        assertEquals("25lb × 8 reps", loggedActualLine(25.0, 8, null, null))
    }

    @Test
    fun tapResultLabel_null_for_unknown_or_missing_tap() {
        assertNull(tapResultLabel(null))
    }

    // Logged cards are tap-to-editable once they are past; unilateral cards save to side rows.

    @Test
    fun isSetEditable_true_for_past_bilateral_set() {
        assertEquals(true, isSetEditable(isPast = true, unilateral = false))
    }

    @Test
    fun isSetEditable_true_for_past_unilateral_set() {
        assertEquals(true, isSetEditable(isPast = true, unilateral = true))
    }

    @Test
    fun isSetEditable_false_when_not_yet_logged() {
        assertEquals(false, isSetEditable(isPast = false, unilateral = false))
        assertEquals(false, isSetEditable(isPast = false, unilateral = true))
    }

    // ── Fix F: weight carries forward — enter 175 on set 1, sets 2 & 3 default to 175 ──────

    @Test
    fun withCarriedLoad_records_the_new_load_for_the_movement() {
        val carried = withCarriedLoad(emptyMap(), movementId = 5, newLoad = 175.0)
        assertEquals(mapOf(5 to 175.0), carried)
    }

    @Test
    fun withCarriedLoad_is_a_noop_when_new_load_is_null_clearing_the_field() {
        val carried = withCarriedLoad(mapOf(5 to 175.0), movementId = 5, newLoad = null)
        assertEquals("clearing the input must not blank the carried default for later sets", mapOf(5 to 175.0), carried)
    }

    @Test
    fun withCarriedLoad_does_not_affect_other_movements() {
        val carried = withCarriedLoad(mapOf(9 to 50.0), movementId = 5, newLoad = 175.0)
        assertEquals(mapOf(9 to 50.0, 5 to 175.0), carried)
    }

    @Test
    fun effectiveLoadPrefill_uses_carried_load_over_the_sets_own_target() {
        // Day-1 scenario: prescribed 170, athlete enters 175 on set 1 -> set 3 (still
        // prescribed 170 on the server) must prefill 175, not revert to 170.
        val carried = mapOf(5 to 175.0)
        assertEquals("175", effectiveLoadPrefill(carried, movementId = 5, targetLoad = 170.0))
    }

    @Test
    fun effectiveLoadPrefill_falls_back_to_target_when_nothing_carried_yet() {
        assertEquals("170", effectiveLoadPrefill(emptyMap(), movementId = 5, targetLoad = 170.0))
    }

    @Test
    fun effectiveLoadPrefill_falls_back_to_target_for_a_different_movement() {
        val carried = mapOf(9 to 50.0) // carried for a DIFFERENT exercise
        assertEquals("170", effectiveLoadPrefill(carried, movementId = 5, targetLoad = 170.0))
    }

    // ── Spec 13: GIANT_SET round interleaving verification ──────────────────────────────

    @Test
    fun giant_set_round_interleaving_carries_forward_load_independently_per_movement() {
        // Tracing exact athlete report: Pendlay Row (id=36), Incline DB Press (id=40), Knee Raise (id=42).
        var carried = emptyMap<Int, Double>()

        // Round 0 - Set 1 of each exercise
        assertEquals("170", effectiveLoadPrefill(carried, movementId = 36, targetLoad = 170.0))
        carried = withCarriedLoad(carried, movementId = 36, newLoad = 175.0) // Athlete enters 175

        assertEquals("60", effectiveLoadPrefill(carried, movementId = 40, targetLoad = 60.0))
        carried = withCarriedLoad(carried, movementId = 40, newLoad = 65.0) // Athlete enters 65

        assertEquals("", effectiveLoadPrefill(carried, movementId = 42, targetLoad = null))
        // Athlete enters no weight for Knee Raise

        // Round 1 - Set 2 of each exercise (cursor round-major interleaving)
        // When LaunchedEffect fires upon cursor moving to Pendlay Row Round 1:
        assertEquals(
            "Pendlay Row R1 must pre-fill 175 despite Incline DB Press and Knee Raise interleaving",
            "175",
            effectiveLoadPrefill(carried, movementId = 36, targetLoad = 170.0),
        )
        // If athlete accepts 175 on R1 without typing:
        assertEquals("65", effectiveLoadPrefill(carried, movementId = 40, targetLoad = 60.0))

        // Round 2 - Set 3 of Pendlay Row: verify clearing input field on R1 doesn't clear default for R2
        carried = withCarriedLoad(carried, movementId = 36, newLoad = null) // Athlete cleared field on R1
        assertEquals(
            "Pendlay Row R2 must still pre-fill 175 after null input on R1",
            "175",
            effectiveLoadPrefill(carried, movementId = 36, targetLoad = 170.0),
        )
    }
}
