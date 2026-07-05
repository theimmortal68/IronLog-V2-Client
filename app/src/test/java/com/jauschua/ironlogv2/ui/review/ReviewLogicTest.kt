package com.jauschua.ironlogv2.ui.review

import com.jauschua.ironlogv2.data.api.dto.LiftCategory
import com.jauschua.ironlogv2.data.api.dto.MovementDto
import com.jauschua.ironlogv2.data.api.dto.NoteReviewOut
import com.jauschua.ironlogv2.data.api.dto.ProposedChange
import com.jauschua.ironlogv2.data.api.dto.Region
import com.jauschua.ironlogv2.ui.screens.review.filterMovements
import com.jauschua.ironlogv2.ui.screens.review.isConfigChange
import com.jauschua.ironlogv2.ui.screens.review.pickerSeedText
import com.jauschua.ironlogv2.ui.screens.review.proposedChangeLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewLogicTest {
    private fun note(
        classification: String,
        proposedChange: ProposedChange? = null,
    ) = NoteReviewOut(
        id = 1, session_id = 7, movement_id = 10, created_at = "2026-07-04T12:00:00",
        text = "switch bench to incline", classification = classification,
        proposed_change = proposedChange, confidence = 0.9,
    )

    private fun movement(id: Int, name: String) = MovementDto(
        id = id, name = name, base_name = name, region = Region.UPPER, lift_category = LiftCategory.BENCH,
    )

    @Test fun full_proposed_change_joins_with_dot_separator() {
        val n = note("CONFIG_CHANGE", ProposedChange(movement = "Bench", action = "switch", params = "to incline"))
        assertEquals("Bench · switch · to incline", proposedChangeLine(n))
    }

    @Test fun null_proposed_change_yields_empty_string() {
        val n = note("PROGRAMMING_REQUEST", proposedChange = null)
        assertEquals("", proposedChangeLine(n))
    }

    @Test fun programming_request_with_null_change_yields_empty_string() {
        val n = note("PROGRAMMING_REQUEST", proposedChange = null)
        assertEquals("", proposedChangeLine(n))
    }

    @Test fun isConfigChange_true_only_for_config_change_classification() {
        assertTrue(isConfigChange(note("CONFIG_CHANGE")))
        assertFalse(isConfigChange(note("PROGRAMMING_REQUEST")))
        assertFalse(isConfigChange(note("TRANSIENT_FLAG")))
        assertFalse(isConfigChange(note("JOURNAL")))
    }

    @Test fun pickerSeedText_uses_proposed_change_movement_or_blank() {
        val n = note("CONFIG_CHANGE", ProposedChange(movement = "Bench", action = "switch", params = "to incline"))
        assertEquals("Bench", pickerSeedText(n))
        assertEquals("", pickerSeedText(note("PROGRAMMING_REQUEST", proposedChange = null)))
    }

    @Test fun filterMovements_blank_query_returns_all() {
        val all = listOf(movement(1, "Bench"), movement(2, "Incline Bench"))
        assertEquals(all, filterMovements(all, ""))
        assertEquals(all, filterMovements(all, "   "))
    }

    @Test fun filterMovements_matches_case_insensitive_substring() {
        val all = listOf(movement(1, "Bench"), movement(2, "Incline Bench"), movement(3, "Squat"))
        val result = filterMovements(all, "bench")
        assertEquals(listOf(movement(1, "Bench"), movement(2, "Incline Bench")), result)
    }

    @Test fun filterMovements_no_match_returns_empty() {
        val all = listOf(movement(1, "Bench"))
        assertTrue(filterMovements(all, "zzz").isEmpty())
    }
}
