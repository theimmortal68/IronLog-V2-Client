package com.jauschua.ironlogv2.ui.cardio

import com.jauschua.ironlogv2.data.api.dto.CardioLogCreate
import com.jauschua.ironlogv2.ui.screens.cardio.buildCardioLogCreate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardioLogScreenLogicTest {

    @Test
    fun buildCardioLogCreate_validWalkInput_returnsCreateRequest() {
        assertEquals(
            CardioLogCreate(
                date = "2026-07-21",
                duration_minutes = 30,
                avg_hr = 132,
                modality = "WALK",
                incline_pct = null,
                backward_walk_done = false,
            ),
            buildCardioLogCreate(
                date = "2026-07-21",
                durationMinutes = "30",
                avgHr = "132",
                modality = "WALK",
                inclinePct = "",
                backwardWalkDone = false,
            ),
        )
    }

    @Test
    fun buildCardioLogCreate_validTreadmillInput_returnsCreateRequest() {
        assertEquals(
            CardioLogCreate(
                date = "2026-07-21",
                duration_minutes = 35,
                avg_hr = 128,
                modality = "TREADMILL",
                incline_pct = 4.5,
                backward_walk_done = true,
            ),
            buildCardioLogCreate(
                date = "2026-07-21",
                durationMinutes = "35",
                avgHr = "128",
                modality = "TREADMILL",
                inclinePct = "4.5",
                backwardWalkDone = true,
            ),
        )
    }

    @Test
    fun buildCardioLogCreate_blankOptionalInputs_mapToNull() {
        assertEquals(
            CardioLogCreate(
                date = "2026-07-21",
                duration_minutes = 40,
                avg_hr = null,
                modality = "TREADMILL",
                incline_pct = null,
                backward_walk_done = false,
            ),
            buildCardioLogCreate(
                date = "2026-07-21",
                durationMinutes = "40",
                avgHr = "",
                modality = "TREADMILL",
                inclinePct = "",
                backwardWalkDone = false,
            ),
        )
    }

    @Test
    fun buildCardioLogCreate_blankDate_returnsNull() {
        assertNull(
            buildCardioLogCreate(
                date = "",
                durationMinutes = "30",
                avgHr = "132",
                modality = "WALK",
                inclinePct = "",
                backwardWalkDone = false,
            ),
        )
    }

    @Test
    fun buildCardioLogCreate_blankOrNonNumericDuration_returnsNull() {
        assertNull(
            buildCardioLogCreate(
                date = "2026-07-21",
                durationMinutes = "",
                avgHr = "132",
                modality = "WALK",
                inclinePct = "",
                backwardWalkDone = false,
            ),
        )
        assertNull(
            buildCardioLogCreate(
                date = "2026-07-21",
                durationMinutes = "thirty",
                avgHr = "132",
                modality = "WALK",
                inclinePct = "",
                backwardWalkDone = false,
            ),
        )
    }

    @Test
    fun buildCardioLogCreate_zeroOrNegativeDuration_returnsNull() {
        assertNull(
            buildCardioLogCreate(
                date = "2026-07-21",
                durationMinutes = "0",
                avgHr = "132",
                modality = "WALK",
                inclinePct = "",
                backwardWalkDone = false,
            ),
        )
        assertNull(
            buildCardioLogCreate(
                date = "2026-07-21",
                durationMinutes = "-10",
                avgHr = "132",
                modality = "WALK",
                inclinePct = "",
                backwardWalkDone = false,
            ),
        )
    }
}
