package com.jauschua.ironlogv2.data.api.dto

import kotlinx.serialization.Serializable

@Serializable data class GoalSettingsOut(
    val target_bodyweight: Double,
    val target_bodyweight_tolerance: Double,
    val target_body_fat_pct: Double? = null,
    val target_body_fat_pct_tolerance: Double? = null,
    val updated_at: String,
)

@Serializable data class GoalSettingsIn(
    val target_bodyweight: Double? = null,
    val target_bodyweight_tolerance: Double? = null,
    val target_body_fat_pct: Double? = null,
    val target_body_fat_pct_tolerance: Double? = null,
)
