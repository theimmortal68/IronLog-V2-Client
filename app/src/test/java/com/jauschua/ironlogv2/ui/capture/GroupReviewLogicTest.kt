package com.jauschua.ironlogv2.ui.capture

import com.jauschua.ironlogv2.data.api.dto.ExerciseOut
import com.jauschua.ironlogv2.data.api.dto.GroupOut
import com.jauschua.ironlogv2.data.api.dto.PlannedSetOut
import com.jauschua.ironlogv2.data.local.SurveyDraft
import com.jauschua.ironlogv2.ui.screens.capture.GroupReview
import com.jauschua.ironlogv2.ui.screens.capture.initialFlags
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupReviewLogicTest {
    private fun ex(mid: Int) = ExerciseOut(
        id = mid, movement_id = mid, movement_name = "M$mid", order_index = 0,
        scheme = "STRAIGHT", objective = "HYP", planned_sets = listOf(
            PlannedSetOut(id = mid * 10, set_index = 0, set_role = "WORKING", is_warmup = false)))

    private val group = GroupOut(id = 1, order_index = 0, group_type = "GIANT_SET", rounds = 1,
        exercises = listOf(ex(10), ex(11)))

    @Test fun initialFlags_seeds_from_existing_drafts_missing_defaults_false() {
        val review = GroupReview(
            group = group,
            surveys = listOf(SurveyDraft(sessionId = 7, movementId = 10, asymmetryFlag = true, techniqueFlag = false)),
            noteText = "x",
        )
        val flags = initialFlags(review)
        assertEquals(true to false, flags[10])       // from the draft
        assertEquals(false to false, flags[11])       // no draft → default
    }

    @Test fun initialFlags_covers_every_exercise_in_the_group() {
        val flags = initialFlags(GroupReview(group, emptyList(), null))
        assertEquals(setOf(10, 11), flags.keys)
    }
}
