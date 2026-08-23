// CaptureScreenLogicTest.kt
package com.jauschua.ironlogv2.ui.capture

import com.jauschua.ironlogv2.data.api.dto.ExerciseOut
import com.jauschua.ironlogv2.data.api.dto.FinisherOut
import com.jauschua.ironlogv2.data.api.dto.GroupOut
import com.jauschua.ironlogv2.data.api.dto.PlannedSetOut
import com.jauschua.ironlogv2.data.api.dto.SessionDetailResponse
import com.jauschua.ironlogv2.ui.screens.capture.LoggedSetActual
import com.jauschua.ironlogv2.ui.screens.capture.bandNames
import com.jauschua.ironlogv2.ui.screens.capture.effectiveLoadPrefill
import com.jauschua.ironlogv2.ui.screens.capture.effectiveRepsPrefill
import com.jauschua.ironlogv2.ui.screens.capture.FinisherLoggableKind
import com.jauschua.ironlogv2.ui.screens.capture.FinisherTimerMode
import com.jauschua.ironlogv2.ui.screens.capture.finisherLoggableKind
import com.jauschua.ironlogv2.ui.screens.capture.finisherTimerMode
import com.jauschua.ironlogv2.ui.screens.capture.flattenPrescription
import com.jauschua.ironlogv2.ui.screens.capture.formatRepsTarget
import com.jauschua.ironlogv2.ui.screens.capture.groupProgressHint
import com.jauschua.ironlogv2.ui.screens.capture.htObservedPeak
import com.jauschua.ironlogv2.ui.screens.capture.htReconfigure
import com.jauschua.ironlogv2.ui.screens.capture.htSetupLine
import com.jauschua.ironlogv2.ui.screens.capture.isFlatAcrossRepTargets
import com.jauschua.ironlogv2.ui.screens.capture.isFlatAcrossSets
import com.jauschua.ironlogv2.ui.screens.capture.isSetEditable
import com.jauschua.ironlogv2.ui.screens.capture.loadDisplayLabel
import com.jauschua.ironlogv2.ui.screens.capture.loadInputLabel
import com.jauschua.ironlogv2.ui.screens.capture.loggedActualLine
import com.jauschua.ironlogv2.ui.screens.capture.pastSetIds
import com.jauschua.ironlogv2.ui.screens.capture.perSideLabel
import com.jauschua.ironlogv2.ui.screens.capture.prefillReps
import com.jauschua.ironlogv2.ui.screens.capture.prefillWeight
import com.jauschua.ironlogv2.ui.screens.capture.reconstructCarriedLoad
import com.jauschua.ironlogv2.ui.screens.capture.reconstructCarriedReps
import com.jauschua.ironlogv2.ui.screens.capture.repsInputLabel
import com.jauschua.ironlogv2.ui.screens.capture.repsTargetLabel
import com.jauschua.ironlogv2.ui.screens.capture.rpeLabel
import com.jauschua.ironlogv2.ui.screens.capture.shoeTransition
import com.jauschua.ironlogv2.ui.screens.capture.tapResultLabel
import com.jauschua.ironlogv2.ui.screens.capture.warmupJumpRopeSeconds
import com.jauschua.ironlogv2.ui.screens.capture.withCarriedLoad
import com.jauschua.ironlogv2.ui.screens.capture.withCarriedReps
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
            movement_id = 1,
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
            movement_id = 1,
            duration_minutes = 10,
            params = JsonObject(mapOf("work_seconds_per_minute" to JsonPrimitive(35))),
        )

        assertEquals(
            // No rest_seconds_per_minute in params -- falls back to the old 60-work derivation
            // ONLY in this client-side fallback path (60 - 35 = 25).
            FinisherTimerMode.TimeBased(totalMinutes = 10, workSeconds = 35, restSeconds = 25, label = "Burpees"),
            finisherTimerMode(finisher),
        )
    }

    @Test
    fun finisherTimerMode_time_based_prefers_current_duration_seconds_over_stale_param() {
        val finisher = FinisherOut(
            exercise_name = "jump_rope",
            movement_id = 1,
            duration_minutes = 8,
            current_duration_seconds = 35,
            params = JsonObject(mapOf("work_seconds_per_minute" to JsonPrimitive(30))),
        )

        assertEquals(
            FinisherTimerMode.TimeBased(totalMinutes = 8, workSeconds = 35, restSeconds = 25, label = "Jump Rope"),
            finisherTimerMode(finisher),
        )
    }

    @Test
    fun finisherTimerMode_time_based_when_current_duration_seconds_present_without_static_param() {
        val finisher = FinisherOut(
            exercise_name = "jump_rope",
            movement_id = 1,
            duration_minutes = 8,
            current_duration_seconds = 40,
            params = JsonObject(emptyMap()),
        )

        assertEquals(
            FinisherTimerMode.TimeBased(totalMinutes = 8, workSeconds = 40, restSeconds = 20, label = "Jump Rope"),
            finisherTimerMode(finisher),
        )
    }

    @Test
    fun finisherTimerMode_rep_based_when_both_timer_params_present() {
        val finisher = FinisherOut(
            exercise_name = "jump_rope",
            movement_id = 1,
            duration_minutes = 8,
            params = JsonObject(
                mapOf(
                    "target_reps_per_minute" to JsonPrimitive(80),
                    "work_seconds_per_minute" to JsonPrimitive(35),
                ),
            ),
        )

        assertEquals(
            FinisherTimerMode.RepBased(totalMinutes = 8, label = "Jump Rope"),
            finisherTimerMode(finisher),
        )
    }

    @Test
    fun finisherTimerMode_rep_based_when_target_reps_and_current_duration_seconds_present() {
        val finisher = FinisherOut(
            exercise_name = "jump_rope",
            movement_id = 1,
            duration_minutes = 8,
            current_duration_seconds = 35,
            params = JsonObject(
                mapOf(
                    "target_reps_per_minute" to JsonPrimitive(80),
                    "work_seconds_per_minute" to JsonPrimitive(30),
                ),
            ),
        )

        assertEquals(
            FinisherTimerMode.RepBased(totalMinutes = 8, label = "Jump Rope"),
            finisherTimerMode(finisher),
        )
    }

    @Test
    fun finisherTimerMode_none_when_no_timer_param_present() {
        val finisher = FinisherOut(
            exercise_name = "burpees",
            movement_id = 1,
            duration_minutes = 10,
            params = JsonObject(emptyMap()),
        )

        assertEquals(FinisherTimerMode.None, finisherTimerMode(finisher))
    }

    @Test
    fun finisherTimerMode_none_when_duration_minutes_is_not_positive() {
        val finisher = FinisherOut(
            exercise_name = "jump_rope",
            movement_id = 1,
            duration_minutes = 0,
            params = JsonObject(
                mapOf(
                    "target_reps_per_minute" to JsonPrimitive(80),
                    "work_seconds_per_minute" to JsonPrimitive(35),
                ),
            ),
        )

        assertEquals(FinisherTimerMode.None, finisherTimerMode(finisher))
    }

    // ── Spec 30: scheme-based routing (emom / tabata) + honest TimeBased rest ───────────

    @Test
    fun finisherTimerMode_time_based_honors_real_rest_seconds_per_minute_param() {
        // sled_push: work 20s, rest 30s -- NOT the old 60-work coincidence (60-20=40 != 30).
        val finisher = FinisherOut(
            exercise_name = "sled_push",
            movement_id = 1,
            duration_minutes = 6,
            params = JsonObject(
                mapOf(
                    "work_seconds_per_minute" to JsonPrimitive(20),
                    "rest_seconds_per_minute" to JsonPrimitive(30),
                ),
            ),
        )

        assertEquals(
            FinisherTimerMode.TimeBased(totalMinutes = 6, workSeconds = 20, restSeconds = 30, label = "Sled Push"),
            finisherTimerMode(finisher),
        )
    }

    @Test
    fun finisherTimerMode_emom_when_scheme_is_emom() {
        val finisher = FinisherOut(
            exercise_name = "sandbag_load_to_utility_seat",
            movement_id = 1,
            duration_minutes = 6,
            params = JsonObject(
                mapOf(
                    "scheme" to JsonPrimitive("emom"),
                    "target_reps_per_minute" to JsonPrimitive(4),
                ),
            ),
        )

        assertEquals(
            FinisherTimerMode.Emom(totalMinutes = 6, repsPerMinute = 4, label = "Sandbag Load To Utility Seat"),
            finisherTimerMode(finisher),
        )
    }

    @Test
    fun finisherTimerMode_emom_falls_through_to_legacy_heuristic_when_reps_missing() {
        val finisher = FinisherOut(
            exercise_name = "sandbag_load_to_utility_seat",
            movement_id = 1,
            duration_minutes = 6,
            params = JsonObject(mapOf("scheme" to JsonPrimitive("emom"))),
        )

        // No target_reps_per_minute under scheme=emom and no work-seconds fallback either.
        assertEquals(FinisherTimerMode.None, finisherTimerMode(finisher))
    }

    @Test
    fun finisherTimerMode_tabata_when_scheme_is_tabata() {
        val finisher = FinisherOut(
            exercise_name = "jump_rope",
            movement_id = 1,
            duration_minutes = 6,
            params = JsonObject(
                mapOf(
                    "scheme" to JsonPrimitive("tabata"),
                    "work_seconds" to JsonPrimitive(20),
                    "rest_seconds" to JsonPrimitive(10),
                    "rounds_per_block" to JsonPrimitive(8),
                    "blocks" to JsonPrimitive(2),
                    "inter_block_rest_seconds" to JsonPrimitive(75),
                ),
            ),
        )

        assertEquals(
            FinisherTimerMode.Tabata(
                workSeconds = 20,
                restSeconds = 10,
                roundsPerBlock = 8,
                blocks = 2,
                interBlockRestSeconds = 75,
                label = "Jump Rope",
            ),
            finisherTimerMode(finisher),
        )
    }

    @Test
    fun finisherTimerMode_tabata_falls_through_to_legacy_heuristic_when_malformed() {
        // scheme=tabata but missing rounds_per_block/blocks -- must not crash, falls through.
        val finisher = FinisherOut(
            exercise_name = "jump_rope",
            movement_id = 1,
            duration_minutes = 6,
            current_duration_seconds = 35,
            params = JsonObject(
                mapOf(
                    "scheme" to JsonPrimitive("tabata"),
                    "work_seconds" to JsonPrimitive(20),
                    "rest_seconds" to JsonPrimitive(10),
                ),
            ),
        )

        assertEquals(
            FinisherTimerMode.TimeBased(totalMinutes = 6, workSeconds = 35, restSeconds = 25, label = "Jump Rope"),
            finisherTimerMode(finisher),
        )
    }

    // ── Spec 30: finisherLoggableKind — which finishers render the weight/resistance log row ──

    @Test
    fun finisherLoggableKind_weight_when_weight_lb_present() {
        val params = JsonObject(mapOf("weight_lb" to JsonPrimitive(100)))
        assertEquals(FinisherLoggableKind.WEIGHT, finisherLoggableKind(params))
    }

    @Test
    fun finisherLoggableKind_resistance_when_resistance_level_present() {
        val params = JsonObject(mapOf("resistance_level" to JsonPrimitive(8)))
        assertEquals(FinisherLoggableKind.RESISTANCE, finisherLoggableKind(params))
    }

    @Test
    fun finisherLoggableKind_weight_wins_when_both_present() {
        val params = JsonObject(
            mapOf(
                "weight_lb" to JsonPrimitive(100),
                "resistance_level" to JsonPrimitive(8),
            ),
        )
        assertEquals(FinisherLoggableKind.WEIGHT, finisherLoggableKind(params))
    }

    @Test
    fun finisherLoggableKind_null_when_neither_present() {
        assertNull(finisherLoggableKind(JsonObject(mapOf("equipment" to JsonPrimitive("sandbag_100")))))
    }

    // ── Spec 15: warmup jump-rope interval timer extraction ─────────────────────────────

    @Test
    fun warmupJumpRopeSeconds_returns_seconds_for_jump_rope_warmup() {
        val item = JsonObject(
            mapOf(
                "name" to JsonPrimitive("jump rope footwork"),
                "seconds" to JsonPrimitive(45),
            ),
        )

        assertEquals(45, warmupJumpRopeSeconds(item))
    }

    @Test
    fun warmupJumpRopeSeconds_null_when_jump_rope_seconds_not_positive() {
        val negative = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Jump Rope"),
                "seconds" to JsonPrimitive(-10),
            ),
        )
        val zero = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Jump Rope"),
                "seconds" to JsonPrimitive(0),
            ),
        )

        assertNull(warmupJumpRopeSeconds(negative))
        assertNull(warmupJumpRopeSeconds(zero))
    }

    @Test
    fun warmupJumpRopeSeconds_null_when_jump_rope_seconds_missing() {
        val item = JsonObject(mapOf("name" to JsonPrimitive("Jump Rope")))

        assertNull(warmupJumpRopeSeconds(item))
    }

    @Test
    fun warmupJumpRopeSeconds_null_when_jump_rope_seconds_non_integer() {
        val item = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Jump Rope"),
                "seconds" to JsonPrimitive("thirty"),
            ),
        )

        assertNull(warmupJumpRopeSeconds(item))
    }

    @Test
    fun warmupJumpRopeSeconds_null_when_name_is_not_jump_rope() {
        val item = JsonObject(
            mapOf(
                "name" to JsonPrimitive("worlds greatest stretch"),
                "seconds" to JsonPrimitive(45),
            ),
        )

        assertNull(warmupJumpRopeSeconds(item))
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
    fun isFlatAcrossSets_true_when_all_non_null_values_match() {
        assertEquals(true, isFlatAcrossSets(listOf(170.0, 170.0, 170.0)))
    }

    @Test
    fun isFlatAcrossSets_false_when_non_null_values_differ() {
        assertEquals(false, isFlatAcrossSets(listOf(170.0, 175.0, 180.0)))
    }

    @Test
    fun isFlatAcrossSets_true_when_nulls_are_mixed_with_same_non_null_values() {
        assertEquals(true, isFlatAcrossSets(listOf(null, 170.0, null, 170.0)))
    }

    @Test
    fun isFlatAcrossSets_true_for_empty_list() {
        assertEquals(true, isFlatAcrossSets(emptyList()))
    }

    @Test
    fun carryForwardFlatness_ignoresRampSets_whenWorkingSetsAreFlat() {
        val plannedSets = listOf(
            PlannedSetOut(
                id = 1, set_index = 0, set_role = "RAMP", is_warmup = true,
                target_load = 67.5, target_reps_low = 5, target_reps_high = 5,
            ),
            PlannedSetOut(
                id = 2, set_index = 1, set_role = "RAMP", is_warmup = true,
                target_load = 102.5, target_reps_low = 3, target_reps_high = 3,
            ),
            PlannedSetOut(
                id = 3, set_index = 2, set_role = "RAMP", is_warmup = true,
                target_load = 135.0, target_reps_low = 2, target_reps_high = 2,
            ),
            PlannedSetOut(
                id = 4, set_index = 3, set_role = "WORKING", is_warmup = false,
                target_load = 170.0, target_reps_low = 6, target_reps_high = 8,
            ),
            PlannedSetOut(
                id = 5, set_index = 4, set_role = "WORKING", is_warmup = false,
                target_load = 170.0, target_reps_low = 6, target_reps_high = 8,
            ),
            PlannedSetOut(
                id = 6, set_index = 5, set_role = "WORKING", is_warmup = false,
                target_load = 170.0, target_reps_low = 6, target_reps_high = 8,
            ),
        )
        val workingPlannedSets = plannedSets.filter { !it.is_warmup }

        assertEquals(true, isFlatAcrossSets(workingPlannedSets.map { it.target_load }))
        assertEquals(
            true,
            isFlatAcrossRepTargets(workingPlannedSets.map { it.target_reps_low to it.target_reps_high }),
        )
    }

    @Test
    fun isFlatAcrossRepTargets_true_when_all_non_null_targets_match() {
        assertEquals(true, isFlatAcrossRepTargets(listOf(8 to 8, 8 to 8, 8 to 8)))
    }

    @Test
    fun isFlatAcrossRepTargets_false_when_targets_differ() {
        assertEquals(false, isFlatAcrossRepTargets(listOf(8 to 8, 8 to 8, 6 to 10)))
    }

    @Test
    fun isFlatAcrossRepTargets_true_when_null_targets_are_mixed_with_same_non_null_targets() {
        assertEquals(true, isFlatAcrossRepTargets(listOf(null to null, 8 to 8, null to null, 8 to 8)))
    }

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
        assertEquals("175", effectiveLoadPrefill(carried, movementId = 5, targetLoad = 170.0, planIsFlat = true))
    }

    @Test
    fun effectiveLoadPrefill_uses_sets_own_target_when_plan_is_not_flat() {
        val carried = mapOf(5 to 175.0)
        assertEquals("180", effectiveLoadPrefill(carried, movementId = 5, targetLoad = 180.0, planIsFlat = false))
    }

    @Test
    fun effectiveLoadPrefill_falls_back_to_target_when_nothing_carried_yet() {
        assertEquals("170", effectiveLoadPrefill(emptyMap(), movementId = 5, targetLoad = 170.0, planIsFlat = true))
    }

    @Test
    fun effectiveLoadPrefill_falls_back_to_target_for_a_different_movement() {
        val carried = mapOf(9 to 50.0) // carried for a DIFFERENT exercise
        assertEquals("170", effectiveLoadPrefill(carried, movementId = 5, targetLoad = 170.0, planIsFlat = true))
    }

    @Test
    fun withCarriedReps_records_the_new_reps_for_the_movement() {
        val carried = withCarriedReps(emptyMap(), movementId = 5, newReps = 9)
        assertEquals(mapOf(5 to 9), carried)
    }

    @Test
    fun withCarriedReps_is_a_noop_when_new_reps_is_null_clearing_the_field() {
        val carried = withCarriedReps(mapOf(5 to 9), movementId = 5, newReps = null)
        assertEquals("clearing the input must not blank the carried default for later sets", mapOf(5 to 9), carried)
    }

    @Test
    fun effectiveRepsPrefill_uses_carried_reps_over_the_sets_own_target_when_plan_is_flat() {
        val carried = mapOf(5 to 9)
        val plannedSet = PlannedSetOut(
            id = 1, set_index = 0, set_role = "WORKING", is_warmup = false,
            target_reps_low = 8, target_reps_high = 8,
        )

        assertEquals("9", effectiveRepsPrefill(carried, movementId = 5, plannedSet = plannedSet, planIsFlat = true))
    }

    @Test
    fun effectiveRepsPrefill_uses_sets_own_target_when_plan_is_not_flat() {
        val carried = mapOf(5 to 9)
        val plannedSet = PlannedSetOut(
            id = 1, set_index = 0, set_role = "WORKING", is_warmup = false,
            target_reps_low = 6, target_reps_high = 10,
        )

        assertEquals("6", effectiveRepsPrefill(carried, movementId = 5, plannedSet = plannedSet, planIsFlat = false))
    }

    // ── Restart-survival fix: reconstructCarriedLoad / reconstructCarriedReps ───────────

    private fun exerciseWithSets(exerciseId: Int, movementId: Int, plannedSetIds: List<Int>) = ExerciseOut(
        id = exerciseId, movement_id = movementId, movement_name = "movement-$movementId",
        order_index = 0, scheme = "STRAIGHT", objective = "",
        planned_sets = plannedSetIds.mapIndexed { idx, id ->
            PlannedSetOut(id = id, set_index = idx, set_role = "WORKING", is_warmup = false)
        },
    )

    private fun sessionOf(vararg exercises: ExerciseOut) = SessionDetailResponse(
        id = 1, date = "2026-08-16", day_role = "PUSH", phase = "BASE", status = "IN_PROGRESS",
        groups = listOf(
            GroupOut(id = 1, order_index = 0, group_type = "STRAIGHT", rounds = 1, exercises = exercises.toList()),
        ),
    )

    @Test
    fun reconstructCarriedLoad_uses_the_highest_set_index_logged_set_per_movement() {
        // Movement 36 has planned sets 100 (set_index 0), 101 (set_index 1), 102 (set_index 2).
        // Sets 100 and 101 are logged; 102 is not reached yet. The carried value must come from
        // set 101 (the higher set_index), not set 100 -- picking an EARLIER set's value is the
        // subtler, worse bug the spec warned about.
        val session = sessionOf(exerciseWithSets(exerciseId = 1, movementId = 36, plannedSetIds = listOf(100, 101, 102)))
        val loggedActuals = mapOf(
            (100 to 0) to LoggedSetActual(actualLoad = 170.0, actualReps = 8, tap = "ON_TARGET"),
            (101 to 0) to LoggedSetActual(actualLoad = 175.0, actualReps = 8, tap = "ON_TARGET"),
        )

        assertEquals(mapOf(36 to 175.0), reconstructCarriedLoad(session, loggedActuals))
    }

    @Test
    fun reconstructCarriedReps_uses_the_highest_set_index_logged_set_per_movement() {
        val session = sessionOf(exerciseWithSets(exerciseId = 1, movementId = 36, plannedSetIds = listOf(100, 101, 102)))
        val loggedActuals = mapOf(
            (100 to 0) to LoggedSetActual(actualLoad = 170.0, actualReps = 8, tap = "ON_TARGET"),
            (101 to 0) to LoggedSetActual(actualLoad = 175.0, actualReps = 6, tap = "ON_TARGET"),
        )

        assertEquals(mapOf(36 to 6), reconstructCarriedReps(session, loggedActuals))
    }

    @Test
    fun reconstructCarriedLoad_tracks_each_movement_independently_in_a_multi_exercise_session() {
        // Giant-set-shaped session: three movements interleaved, each with its own logged sets.
        val session = sessionOf(
            exerciseWithSets(exerciseId = 1, movementId = 36, plannedSetIds = listOf(100, 101, 102)),
            exerciseWithSets(exerciseId = 2, movementId = 40, plannedSetIds = listOf(200, 201, 202)),
        )
        val loggedActuals = mapOf(
            (100 to 0) to LoggedSetActual(actualLoad = 170.0, actualReps = 8, tap = null),
            (200 to 0) to LoggedSetActual(actualLoad = 60.0, actualReps = 10, tap = null),
            (201 to 0) to LoggedSetActual(actualLoad = 65.0, actualReps = 10, tap = null),
        )

        assertEquals(
            "each movement's carry value comes from ITS OWN highest-set_index logged set",
            mapOf(36 to 170.0, 40 to 65.0),
            reconstructCarriedLoad(session, loggedActuals),
        )
    }

    @Test
    fun reconstructCarriedLoad_ignores_rows_with_a_null_actual_load() {
        // A logged set with a null actualLoad (e.g. only reps/tap recorded) must not poison the
        // carry map with a null-derived entry, and must not block a later set's real value.
        val session = sessionOf(exerciseWithSets(exerciseId = 1, movementId = 36, plannedSetIds = listOf(100, 101)))
        val loggedActuals = mapOf(
            (100 to 0) to LoggedSetActual(actualLoad = null, actualReps = 8, tap = "ON_TARGET"),
        )

        assertEquals(emptyMap<Int, Double>(), reconstructCarriedLoad(session, loggedActuals))
    }

    @Test
    fun reconstructCarriedLoad_is_empty_when_nothing_has_been_logged() {
        val session = sessionOf(exerciseWithSets(exerciseId = 1, movementId = 36, plannedSetIds = listOf(100, 101)))

        assertEquals(emptyMap<Int, Double>(), reconstructCarriedLoad(session, emptyMap()))
        assertEquals(emptyMap<Int, Int>(), reconstructCarriedReps(session, emptyMap()))
    }

    // ── Spec 13: GIANT_SET round interleaving verification ──────────────────────────────

    @Test
    fun giant_set_round_interleaving_carries_forward_load_independently_per_movement() {
        // Tracing exact athlete report: Pendlay Row (id=36), Incline DB Press (id=40), Knee Raise (id=42).
        var carried = emptyMap<Int, Double>()

        // Round 0 - Set 1 of each exercise
        assertEquals("170", effectiveLoadPrefill(carried, movementId = 36, targetLoad = 170.0, planIsFlat = true))
        carried = withCarriedLoad(carried, movementId = 36, newLoad = 175.0) // Athlete enters 175

        assertEquals("60", effectiveLoadPrefill(carried, movementId = 40, targetLoad = 60.0, planIsFlat = true))
        carried = withCarriedLoad(carried, movementId = 40, newLoad = 65.0) // Athlete enters 65

        assertEquals("", effectiveLoadPrefill(carried, movementId = 42, targetLoad = null, planIsFlat = true))
        // Athlete enters no weight for Knee Raise

        // Round 1 - Set 2 of each exercise (cursor round-major interleaving)
        // When LaunchedEffect fires upon cursor moving to Pendlay Row Round 1:
        assertEquals(
            "Pendlay Row R1 must pre-fill 175 despite Incline DB Press and Knee Raise interleaving",
            "175",
            effectiveLoadPrefill(carried, movementId = 36, targetLoad = 170.0, planIsFlat = true),
        )
        // If athlete accepts 175 on R1 without typing:
        assertEquals("65", effectiveLoadPrefill(carried, movementId = 40, targetLoad = 60.0, planIsFlat = true))

        // Round 2 - Set 3 of Pendlay Row: verify clearing input field on R1 doesn't clear default for R2
        carried = withCarriedLoad(carried, movementId = 36, newLoad = null) // Athlete cleared field on R1
        assertEquals(
            "Pendlay Row R2 must still pre-fill 175 after null input on R1",
            "175",
            effectiveLoadPrefill(carried, movementId = 36, targetLoad = 170.0, planIsFlat = true),
        )
    }

    // ── Task 6: skip-aware resume cursor ─────────────────────────────────
    // NOTE (Opus review of 25dd7e9, HIGH): the test below only re-implements
    // `firstOrNull { !it.is_skipped }` inline against a hand-built flattened list — it does not
    // exercise CaptureViewModel.applyUpdatedExercise and would NOT catch a bug in that
    // production cursor-reselection path (it didn't: it missed the currently-active-exercise
    // strand bug). The real regression test that drives the production ViewModel is
    // `skipping_the_current_exercise_reselects_cursor_forward_not_to_an_earlier_logged_set` in
    // CaptureViewModelTest.kt.

    @Test
    fun `flattenPrescription-derived resume cursor skips is_skipped sets`() {
        val skippedSet = PlannedSetOut(id = 1, set_index = 0, set_role = "WORKING", is_warmup = false,
            is_skipped = true)
        val nextSet = PlannedSetOut(id = 2, set_index = 1, set_role = "WORKING", is_warmup = false,
            is_skipped = false)
        val exercise = ExerciseOut(id = 10, movement_id = 100, movement_name = "Bench Press [PB]",
            order_index = 0, scheme = "STRAIGHT", objective = "PROGRESS",
            planned_sets = listOf(skippedSet, nextSet))
        val group = GroupOut(id = 1, order_index = 0, group_type = "STRAIGHT", rounds = 1,
            exercises = listOf(exercise))

        val flattened = flattenPrescription(listOf(group))
        val resumeSet = flattened.firstOrNull { !it.is_skipped }
        assertEquals(2, resumeSet?.id)
    }
}
