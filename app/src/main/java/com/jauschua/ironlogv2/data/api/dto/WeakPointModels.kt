package com.jauschua.ironlogv2.data.api.dto

import kotlinx.serialization.Serializable

@Serializable data class WeakMovementOut(
    val movement_id: Int,
    val name: String,
    val stalled: Boolean,
    val lagging: Boolean,
    val growth_rate: Double? = null,
)

@Serializable data class MuscleGroupSummaryOut(
    val muscle: String,
    val weak_count: Int,
    val total_count: Int,
    val weak_movements: List<WeakMovementOut>,
)

@Serializable data class WeakPointAssessmentOut(
    val muscle_groups: List<MuscleGroupSummaryOut>,
    val movements: List<WeakMovementOut>,
)
