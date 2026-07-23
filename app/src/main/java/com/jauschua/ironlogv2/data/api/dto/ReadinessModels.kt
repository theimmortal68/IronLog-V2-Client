package com.jauschua.ironlogv2.data.api.dto

import kotlinx.serialization.Serializable

@Serializable data class DailyReadinessOut(
    val date: String,
    val bodyweight: Double? = null,
    val resting_hr: Double? = null,
    val sleep_ok: Boolean? = null,
    val subjective_ok: Boolean? = null,
)

@Serializable data class DailyReadinessIn(
    val bodyweight: Double? = null,
    val resting_hr: Double? = null,
    val sleep_ok: Boolean? = null,
    val subjective_ok: Boolean? = null,
)

@Serializable data class ConfirmPhaseRequest(
    val to_phase: String,
)
