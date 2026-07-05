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
