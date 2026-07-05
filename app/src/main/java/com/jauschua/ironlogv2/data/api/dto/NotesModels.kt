package com.jauschua.ironlogv2.data.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class ProposedChange(
    val movement: String? = null,
    val action: String? = null,
    val params: String? = null,
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
)

/** Request body for `POST /notes/{id}/apply`. */
@Serializable
data class ApplyNoteRequest(
    val target_movement_id: Int,
)

/** One active (or just-reverted) slot-level movement swap, as listed by `GET /overrides`. */
@Serializable
data class OverrideOut(
    val id: Int,
    val day_role: String? = null,
    val tier_label: String? = null,
    val slot_id: String? = null,
    val from_movement_name: String? = null,
    val to_movement_name: String? = null,
    val source_note_id: Int? = null,
)
