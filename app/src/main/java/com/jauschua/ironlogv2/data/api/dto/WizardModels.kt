// WizardModels.kt
package com.jauschua.ironlogv2.data.api.dto
import kotlinx.serialization.Serializable

@Serializable data class WizardMovement(
    val movement_id: Int, val movement_name: String, val load_field: String, val trust: String,
    val prefill_value: Double? = null, val unit_hint: String? = null,
)
@Serializable data class WizardStateResponse(
    val program_id: Int, val program_name: String, val needs_attention_count: Int,
    val ready_to_start: Boolean, val movements: List<WizardMovement>,
)
@Serializable data class WizardResolution(
    val movement_id: Int, val value: Double,
)
@Serializable data class WizardResolveRequest(
    val resolutions: List<WizardResolution>,
)
@Serializable data class WizardResolveResponse(
    val resolved: Int, val needs_attention_count: Int, val ready_to_start: Boolean,
)
@Serializable data class StartProgramResponse(
    val program_id: Int, val started: Boolean, val active: Boolean,
)
