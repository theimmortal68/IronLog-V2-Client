package com.jauschua.ironlogv2.ui.review

import com.jauschua.ironlogv2.data.api.dto.NoteReviewOut
import com.jauschua.ironlogv2.data.api.dto.ProposedChange
import com.jauschua.ironlogv2.ui.screens.review.proposedChangeLine
import org.junit.Assert.assertEquals
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
}
