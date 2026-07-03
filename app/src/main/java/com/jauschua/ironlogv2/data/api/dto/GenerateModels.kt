// GenerateModels.kt
package com.jauschua.ironlogv2.data.api.dto
import kotlinx.serialization.Serializable

@Serializable data class GenerateRequest(val day_role: String)
@Serializable data class GenerateResponse(
    val candidate_id: String, val day_role: String, val exhausted: Boolean,
    val attempts: Int, val scope: String, val preview: SessionDetailResponse? = null,
)
@Serializable data class ApproveResponse(val session_id: Int)
@Serializable data class SessionSummary(
    val id: Int, val date: String, val day_role: String, val phase: String, val status: String,
)
@Serializable data class LoggedSet(
    val movement_id: Int, val movement_name: String, val set_index: Int,
    val reps: Int? = null, val load: Double? = null, val tap: String? = null,
    val is_warmup: Boolean,
)
@Serializable data class LoggedSetsResponse(
    val session_id: Int, val date: String, val day_role: String, val logs: List<LoggedSet>,
)
