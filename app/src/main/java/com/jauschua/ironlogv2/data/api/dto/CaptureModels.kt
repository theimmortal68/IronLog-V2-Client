// CaptureModels.kt
package com.jauschua.ironlogv2.data.api.dto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable data class SetLogIn(
    val planned_set_id: Int? = null, val movement_id: Int, val set_index: Int,
    val set_role: String, val is_warmup: Boolean,
    val actual_load: Double? = null, val actual_reps: Int? = null,
    val feedback_tap: String? = null, val rpe_numeric: Double? = null,
    val actual_unassisted_reps: Int? = null, val actual_assisted_reps: Int? = null,
    val actual_plates: Double? = null, val band_pair_id: Int? = null, val felt_peak: Double? = null,
)
@Serializable data class ExerciseSurveyIn(
    val movement_id: Int, val sticking_point: String? = null,
    val asymmetry_flag: Boolean? = null, val technique_flag: Boolean? = null,
)
@Serializable data class NoteIn(val movement_id: Int? = null, val text: String)
@Serializable data class SubmitRequest(
    val set_logs: List<SetLogIn>, val surveys: List<ExerciseSurveyIn> = emptyList(),
    val notes: List<NoteIn> = emptyList(),
)
@Serializable data class SubmitResponse(
    val session_id: Int, val status: String, val set_logs_written: Int, val already_completed: Boolean,
    val phase_transition_available: String? = null,
)
@Serializable data class PlannedSetOut(
    val id: Int, val set_index: Int, val set_role: String, val is_warmup: Boolean,
    val is_skipped: Boolean = false,
    val target_load: Double? = null, val target_reps_low: Int? = null,
    val target_reps_high: Int? = null, val target_rpe: Double? = null,
    val target_unassisted_reps: Int? = null, val target_assisted_reps: Int? = null,
    val target_plates: Double? = null, val band_pair_id: Int? = null, val target_felt_peak: Double? = null,
    val band_config: List<Int>? = null,
)
@Serializable data class ExerciseOut(
    val id: Int, val movement_id: Int, val movement_name: String, val order_index: Int,
    val scheme: String, val objective: String, val planned_sets: List<PlannedSetOut>,
    val unit_hint: String? = null,
    val unilateral: Boolean = false,
)
@Serializable data class GroupOut(
    val id: Int, val order_index: Int, val group_type: String, val rounds: Int,
    val rest_seconds: Int? = null, val label: String? = null, val exercises: List<ExerciseOut>,
    val shoe: String? = null,
)
@Serializable data class FinisherOut(
    val exercise_name: String,
    val duration_minutes: Int,
    val params: JsonObject = JsonObject(emptyMap()),
    val current_duration_seconds: Int? = null,
    val current_rope: String? = null,
)
@Serializable data class WarmupOut(
    val movement_flow_seconds: Int,
    val items: List<JsonObject> = emptyList(),
    val activation_seconds: Int,
    val items_activation: List<JsonObject> = emptyList(),
)
@Serializable data class SessionDetailResponse(
    val id: Int, val date: String, val day_role: String, val phase: String,
    val status: String, val groups: List<GroupOut>, val finisher: FinisherOut? = null,
    val warmup: WarmupOut? = null,
)
@Serializable data class SwapExerciseRequest(
    val new_movement_id: Int, val make_permanent: Boolean = false,
)
@Serializable data class MovementSummary(
    val id: Int, val name: String, val primary_muscle: String? = null, val status: String,
)
