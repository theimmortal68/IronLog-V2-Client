package com.jauschua.ironlogv2.data.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class ProposedChange(
    val movement: String? = null,
    val action: String? = null,
    val params: String? = null,
)

@Serializable
data class ProposalOut(
    val tier_exercise_id: Int,
    val day_role: String,
    val slot_label: String,
    val override_type: String,
    val override_movement_id: Int? = null,
    val load_delta: Double? = null,
    val load_absolute: Double? = null,
    val rep_low: Int? = null,
    val rep_high: Int? = null,
    val override_order: Double? = null,
    val valid: Boolean = true,
    val validation_note: String? = null,
    val summary: String = "",
)

@Serializable
data class NoteReviewOut(
    val id: Int,
    val session_id: Int? = null,
    val movement_id: Int? = null,
    val created_at: String,
    val text: String,
    val classification: String,
    val proposed_change: ProposedChange? = null,
    val confidence: Double? = null,
    // Structured action classification (SWAP/LOAD_INCREASE/LOAD_DECREASE/REP_CHANGE/OTHER). The
    // server persists this in classification_meta but `/notes/review` does not surface it yet —
    // kept here for forward-compat; `adjustmentKind` falls back to a keyword heuristic on
    // `proposed_change.action` when this is null.
    val action_type: String? = null,
    val resolved_proposals: List<ProposalOut> = emptyList(),
)

/** One of the active program's exercise slots — the source-slot confirm/pick target for Apply.
 *  From `GET /programs/{id}/slots`. */
@Serializable
data class ProgramSlotOut(
    val tier_exercise_id: Int,
    val slot_id: String? = null,
    val day_role: String? = null,
    val tier_label: String? = null,
    val movement_id: Int? = null,
    val movement_name: String? = null,
    val current_rep_low: Int? = null,
    val current_rep_high: Int? = null,
)

/** Explicit apply body for `POST /notes/{id}/apply` — the athlete-confirmed source slot plus the
 *  action-routed adjustment. Exactly one of `load_delta`/`load_absolute` for LOAD; at least one of
 *  `rep_low`/`rep_high` for REPS; `override_movement_id` for MOVEMENT. */
@Serializable
data class ApplyOverrideRequest(
    val tier_exercise_id: Int,
    val override_type: String,
    val override_movement_id: Int? = null,
    val load_delta: Double? = null,
    val load_absolute: Double? = null,
    val rep_low: Int? = null,
    val rep_high: Int? = null,
    val override_order: Double? = null,
)

/** One active (or just-reverted) slot override — generalized across MOVEMENT/LOAD/REPS — as
 *  listed by `GET /overrides`. `movement_name` is the slot's base movement; type-specific fields
 *  are populated per `override_type`. */
@Serializable
data class OverrideOut(
    val id: Int,
    val override_type: String,
    val day_role: String? = null,
    val tier_label: String? = null,
    val slot_id: String? = null,
    val movement_name: String? = null,
    val to_movement_name: String? = null,
    val load_delta: Double? = null,
    val load_absolute: Double? = null,
    val rep_low: Int? = null,
    val rep_high: Int? = null,
    val source_note_id: Int? = null,
    val source_note_text: String? = null,
)
