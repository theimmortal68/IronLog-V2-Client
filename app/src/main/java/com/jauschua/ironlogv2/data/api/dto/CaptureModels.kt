// CaptureModels.kt
package com.jauschua.ironlogv2.data.api.dto
import kotlinx.serialization.Serializable

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
)
@Serializable data class PlannedSetOut(
    val id: Int, val set_index: Int, val set_role: String, val is_warmup: Boolean,
    val target_load: Double? = null, val target_reps_low: Int? = null,
    val target_reps_high: Int? = null, val target_rpe: Double? = null,
    val target_unassisted_reps: Int? = null, val target_assisted_reps: Int? = null,
    val target_plates: Double? = null, val band_pair_id: Int? = null, val target_felt_peak: Double? = null,
)
@Serializable data class ExerciseOut(
    val id: Int, val movement_id: Int, val movement_name: String, val order_index: Int,
    val scheme: String, val objective: String, val planned_sets: List<PlannedSetOut>,
)
@Serializable data class GroupOut(
    val id: Int, val order_index: Int, val group_type: String, val rounds: Int,
    val rest_seconds: Int? = null, val label: String? = null, val exercises: List<ExerciseOut>,
)
@Serializable data class SessionDetailResponse(
    val id: Int, val date: String, val day_role: String, val phase: String,
    val status: String, val groups: List<GroupOut>,
)
