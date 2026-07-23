package com.jauschua.ironlogv2.data.api.dto

import kotlinx.serialization.Serializable

@Serializable data class MissedDayRecordOut(
    val id: Int,
    val program_day_id: Int,
    val day_role: String,
    val week_start_date: String,
    val detected_at: String,
    val status: String,
)
