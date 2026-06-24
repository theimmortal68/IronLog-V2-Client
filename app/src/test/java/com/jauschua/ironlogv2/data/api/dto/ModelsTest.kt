package com.jauschua.ironlogv2.data.api.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    @Test
    fun parsesBackSquatMovement() {
        val raw = """
        {
          "id": 1,
          "name": "Back Squat [PB]",
          "base_name": "Back Squat",
          "region": "LOWER",
          "lift_category": "BACK_SQUAT",
          "is_primary": true,
          "is_tracked": true,
          "status": "ACTIVE",
          "load_equipment_id": 1,
          "progression_mode": "LADDER",
          "scheme": "TOPSET_BACKOFF",
          "objective_override": null,
          "increment_ladder": [10.0, 5.0, 2.5],
          "min_step": 2.5,
          "load_floor": 45.0,
          "cap": null,
          "rpe_capped": true,
          "rpe_cap_exempt": false,
          "equipment_tags": ["PB"],
          "family": "back_squat",
          "is_family_anchor": true,
          "derived_from_id": null,
          "start_ratio": null,
          "band_eligible": false,
          "notes": null,
          "unknown_future_field": "ignored"
        }
        """.trimIndent()

        val m = json.decodeFromString(MovementDto.serializer(), raw)

        assertEquals(1, m.id)
        assertEquals("Back Squat [PB]", m.name)
        assertEquals(Region.LOWER, m.region)
        assertEquals(LiftCategory.BACK_SQUAT, m.lift_category)
        assertEquals(true, m.is_primary)
        assertEquals(Status.ACTIVE, m.status)
        assertEquals(ProgressionMode.LADDER, m.progression_mode)
        assertEquals(Scheme.TOPSET_BACKOFF, m.scheme)
        assertEquals(listOf(10.0, 5.0, 2.5), m.increment_ladder)
        assertEquals(45.0, m.load_floor!!, 0.0)
        assertEquals(listOf("PB"), m.equipment_tags)
        assertEquals("back_squat", m.family)
    }

    @Test
    fun parsesHipThrustWithBandEligible() {
        val raw = """
        {
          "id": 2,
          "name": "Hip Thrust [HIP_THRUST]",
          "base_name": "Hip Thrust",
          "region": "LOWER",
          "lift_category": "HIP_THRUST",
          "progression_mode": "COMPOSITE",
          "scheme": "STRAIGHT",
          "rpe_cap_exempt": true,
          "band_eligible": true,
          "equipment_tags": ["HIP_THRUST"]
        }
        """.trimIndent()
        val m = json.decodeFromString(MovementDto.serializer(), raw)
        assertEquals(LiftCategory.HIP_THRUST, m.lift_category)
        assertEquals(ProgressionMode.COMPOSITE, m.progression_mode)
        assertTrue(m.rpe_cap_exempt)
        assertTrue(m.band_eligible)
    }

    @Test
    fun parsesPullupAssisted() {
        val raw = """
        {
          "id": 5,
          "name": "Pull-up [TOWER + TUBES]",
          "base_name": "Pull-up",
          "region": "UPPER",
          "lift_category": "NONE",
          "progression_mode": "ASSISTED",
          "assist_subtype": "REP_RATIO",
          "scheme": "REP_RATIO",
          "objective_override": "PROGRESS"
        }
        """.trimIndent()
        val m = json.decodeFromString(MovementDto.serializer(), raw)
        assertEquals(ProgressionMode.ASSISTED, m.progression_mode)
        assertEquals(AssistSubtype.REP_RATIO, m.assist_subtype)
        assertEquals(Objective.PROGRESS, m.objective_override)
    }

    @Test
    fun parsesBandPair() {
        val raw = """
        { "id": 1, "label": "#0 Orange", "bottom_lb": 14.0, "peak_lb": 30.0,
          "calibration_status": "MODELED", "usable": true }
        """.trimIndent()
        val b = json.decodeFromString(BandPairDto.serializer(), raw)
        assertEquals("#0 Orange", b.label)
        assertEquals(14.0, b.bottom_lb, 0.0)
        assertEquals(BandCalStatus.MODELED, b.calibration_status)
    }

    @Test
    fun serializesNextSetRequest() {
        val req = NextSetRequest(movement_id = 1, current_load = 100.0, tap = FeedbackTap.ON_TARGET, tier = 0)
        val out = json.encodeToString(NextSetRequest.serializer(), req)
        // exact JSON: key order is the declaration order under kotlinx-serialization
        assertEquals("""{"movement_id":1,"current_load":100.0,"tap":"ON_TARGET","tier":0}""", out)
    }

    @Test
    fun parsesNextSetResponse() {
        val raw = """{"suggested_load": 105.0}"""
        val r = json.decodeFromString(NextSetResponse.serializer(), raw)
        assertEquals(105.0, r.suggested_load, 0.0)
    }
}
