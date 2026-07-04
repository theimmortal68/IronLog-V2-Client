package com.jauschua.ironlogv2.ui.history

import com.jauschua.ironlogv2.data.api.dto.LoggedSet
import com.jauschua.ironlogv2.data.api.dto.NoteOut
import com.jauschua.ironlogv2.data.api.dto.SurveyOut
import com.jauschua.ironlogv2.ui.screens.history.flagBadges
import com.jauschua.ironlogv2.ui.screens.history.formatSetLine
import com.jauschua.ironlogv2.ui.screens.history.notesFor
import com.jauschua.ironlogv2.ui.screens.history.sessionNoteText
import com.jauschua.ironlogv2.ui.screens.history.surveyFor
import com.jauschua.ironlogv2.ui.screens.history.tapIndicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryDetailLogicTest {
    private fun set(
        load: Double? = 165.0, reps: Int? = 8, tap: String? = "ON_TARGET",
        rpe: Double? = null, peak: Double? = null, warmup: Boolean = false,
    ) = LoggedSet(movement_id = 1, movement_name = "Bench", set_index = 0,
        reps = reps, load = load, tap = tap, is_warmup = warmup,
        rpe_numeric = rpe, felt_peak = peak)

    @Test fun tap_indicator_maps_each_value() {
        assertEquals("✓", tapIndicator("ON_TARGET"))
        assertEquals("↓ easy", tapIndicator("TOO_EASY"))
        assertEquals("↑ hard", tapIndicator("TOO_HARD"))
        assertNull(tapIndicator(null))
    }

    @Test fun format_set_line_load_reps_only() {
        assertEquals("165×8 ✓", formatSetLine(set()))
    }

    @Test fun format_set_line_drops_trailing_zero_and_adds_rpe_and_peak() {
        assertEquals("205×8 @8 ✓ peak~250", formatSetLine(set(load = 205.0, rpe = 8.0, peak = 250.0)))
    }

    @Test fun format_set_line_missing_fields_render_dashes_and_omit_absent() {
        // no tap, no rpe, no peak → "—×—" with nothing appended
        assertEquals("—×—", formatSetLine(set(load = null, reps = null, tap = null)))
    }

    @Test fun format_set_line_warmup_suffix() {
        assertEquals("135×5 ✓ (warmup)", formatSetLine(set(load = 135.0, reps = 5, warmup = true)))
    }

    @Test fun survey_for_matches_by_movement_id() {
        val surveys = listOf(SurveyOut(movement_id = 1, movement_name = "Bench", asymmetry_flag = true),
                             SurveyOut(movement_id = 2, movement_name = "Row"))
        assertEquals(1, surveyFor(surveys, 1)?.movement_id)
        assertNull(surveyFor(surveys, 99))
    }

    @Test fun flag_badges_from_survey() {
        assertEquals(listOf("⚠ L/R", "⚠ tech"),
            flagBadges(SurveyOut(1, "Bench", asymmetry_flag = true, technique_flag = true)))
        assertEquals(listOf("⚠ L/R"),
            flagBadges(SurveyOut(1, "Bench", asymmetry_flag = true, technique_flag = false)))
        assertEquals(emptyList<String>(), flagBadges(SurveyOut(1, "Bench")))
        assertEquals(emptyList<String>(), flagBadges(null))
    }

    @Test fun notes_partition_session_vs_movement() {
        val notes = listOf(NoteOut(movement_id = null, text = "day"),
                           NoteOut(movement_id = 1, text = "bench note"),
                           NoteOut(movement_id = 2, text = "row note"))
        assertEquals("day", sessionNoteText(notes))
        assertEquals(listOf("bench note"), notesFor(notes, 1).map { it.text })
        assertNull(sessionNoteText(listOf(NoteOut(movement_id = 1, text = "x"))))
    }
}
