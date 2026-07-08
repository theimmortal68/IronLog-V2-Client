package com.jauschua.ironlogv2.ui.today

import com.jauschua.ironlogv2.data.api.dto.PlannedSetOut
import com.jauschua.ironlogv2.ui.screens.today.GenerateOutcomeKind
import com.jauschua.ironlogv2.ui.screens.today.classifyGenerate
import com.jauschua.ironlogv2.ui.screens.today.reviewButtonLabel
import com.jauschua.ironlogv2.ui.screens.today.targetSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class TodayLogicTest {
    @Test fun nonexhausted_generate_with_preview_is_reviewable() {
        assertEquals(GenerateOutcomeKind.REVIEWABLE, classifyGenerate(exhausted = false, hasPreview = true))
    }
    @Test fun exhausted_generate_is_error() {
        assertEquals(GenerateOutcomeKind.ERROR, classifyGenerate(exhausted = true, hasPreview = false))
    }
    @Test fun nonexhausted_but_null_preview_is_error() {
        assertEquals(GenerateOutcomeKind.ERROR, classifyGenerate(exhausted = false, hasPreview = false))
    }

    @Test fun review_label_with_zero_count_has_no_badge() {
        assertEquals("Review", reviewButtonLabel(0))
    }
    @Test fun review_label_with_pending_count_shows_badge() {
        assertEquals("Review (2)", reviewButtonLabel(2))
    }
    @Test fun review_label_with_single_pending_shows_badge() {
        assertEquals("Review (1)", reviewButtonLabel(1))
    }

    // HT (band-composite) sets carry target_plates/band_config/target_felt_peak and no
    // meaningful target_load — the preview must render the same "plates + bands · peak" line
    // Capture's SetCard shows, not a blank/raw target_load. Regression coverage for the bug this
    // branch fixes.
    @Test fun target_summary_renders_ht_setup_line_for_band_composite_set() {
        val set = PlannedSetOut(
            id = 1, set_index = 0, set_role = "WORKING", is_warmup = false,
            target_plates = 180.0, band_config = listOf(1), target_felt_peak = 225.0,  // server id 1 = Orange
            target_rpe = 8.0,
        )
        assertEquals("180 plates + Orange · peak ~225 · RPE 8.0", targetSummary(set))
    }

    @Test fun target_summary_falls_back_to_target_load_for_non_ht_set() {
        val set = PlannedSetOut(
            id = 2, set_index = 0, set_role = "WORKING", is_warmup = false,
            target_load = 135.0, target_reps_low = 8,
        )
        assertEquals("135.0 · 8 reps", targetSummary(set))
    }
}
