package com.jauschua.ironlogv2.data.api.dto

import kotlinx.serialization.Serializable

@Serializable data class CardioLogCreate(
    val date: String,
    val duration_minutes: Int,
    val avg_hr: Int? = null,
    val modality: String,
    val incline_pct: Double? = null,
    val backward_walk_done: Boolean = false,
)

@Serializable data class CardioLogOut(
    val id: Int,
    val date: String,
    val duration_minutes: Int,
    val avg_hr: Int?,
    val modality: String,
    val incline_pct: Double?,
    val backward_walk_done: Boolean,
    val created_at: String,
)

@Serializable data class CardioWeeklySummaryOut(
    val count: Int,
    val target: Int,
    val week_start: String,
)
