package com.jauschua.ironlogv2.data.api.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

@Serializable enum class Region { UPPER, LOWER, CORE, NONE }

@Serializable enum class LiftCategory {
    BENCH, BACK_SQUAT, FRONT_SQUAT, OHP, RDL, DEADLIFT, ROW,
    HIP_THRUST, REV_HYPER, CG_PRESS, NONE
}

@Serializable enum class Status { ACTIVE, INACTIVE, PREP }

@Serializable enum class ProgressionMode {
    LADDER, COMPOSITE, ASSISTED, PROTOCOL, CONDITIONING, NONE
}

@Serializable enum class Scheme {
    STRAIGHT, DOUBLE_PROGRESSION, TOPSET_BACKOFF, UNDULATION, WAVE, REP_RATIO
}

@Serializable enum class Objective { MAINTAIN, PROGRESS, MEASURE }

@Serializable enum class Phase { CALIBRATION, CUT, STAB, REBUILD }

@Serializable enum class FeedbackTap { TOO_EASY, ON_TARGET, TOO_HARD }

@Serializable enum class BandCalStatus { MODELED, MEASURED }

@Serializable enum class AssistSubtype { CONTINUOUS, REP_RATIO }

@Serializable enum class AssistUnit { DEGREES, CABLE_LB, TUBE_COUNT, REP_COUNT }

@Serializable
data class MovementDto(
    val id: Int,
    val name: String,
    val base_name: String,
    val region: Region = Region.NONE,
    val lift_category: LiftCategory = LiftCategory.NONE,
    val is_primary: Boolean = false,
    val is_tracked: Boolean = true,
    val status: Status = Status.ACTIVE,
    val load_equipment_id: Int? = null,
    val progression_mode: ProgressionMode = ProgressionMode.NONE,
    val assist_subtype: AssistSubtype? = null,
    val assist_unit: AssistUnit? = null,
    val scheme: Scheme = Scheme.STRAIGHT,
    val objective_override: Objective? = null,
    val increment_ladder: List<Double> = emptyList(),
    val min_step: Double? = null,
    val load_floor: Double? = null,
    val cap: Double? = null,
    val rpe_capped: Boolean = false,
    val rpe_cap_exempt: Boolean = false,
    val equipment_tags: List<String> = emptyList(),
    val family: String? = null,
    val is_family_anchor: Boolean = false,
    val derived_from_id: Int? = null,
    val start_ratio: Double? = null,
    val band_eligible: Boolean = false,
    val notes: String? = null,
)

@Serializable
data class BandPairDto(
    val id: Int,
    val label: String,
    val bottom_lb: Double,
    val peak_lb: Double,
    val calibration_status: BandCalStatus = BandCalStatus.MODELED,
    val usable: Boolean = true,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class NextSetRequest(
    val movement_id: Int,
    val current_load: Double,
    val tap: FeedbackTap,
    @EncodeDefault(mode = EncodeDefault.Mode.ALWAYS)
    val tier: Int = 0,
)

@Serializable
data class NextSetResponse(val suggested_load: Double)
