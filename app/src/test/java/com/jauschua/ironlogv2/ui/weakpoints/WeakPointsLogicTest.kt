package com.jauschua.ironlogv2.ui.weakpoints

import com.jauschua.ironlogv2.data.api.dto.MuscleGroupSummaryOut
import com.jauschua.ironlogv2.data.api.dto.WeakMovementOut
import com.jauschua.ironlogv2.data.api.dto.WeakPointAssessmentOut
import com.jauschua.ironlogv2.ui.screens.weakpoints.weakPointMuscleGroups
import org.junit.Assert.assertEquals
import org.junit.Test

class WeakPointsLogicTest {
    @Test fun no_groups_render_when_assessment_is_empty() {
        val assessment = WeakPointAssessmentOut(muscle_groups = emptyList(), movements = emptyList())
        assertEquals(emptyList<MuscleGroupSummaryOut>(), weakPointMuscleGroups(assessment))
    }

    @Test fun clean_groups_do_not_render() {
        val assessment = WeakPointAssessmentOut(
            muscle_groups = listOf(
                MuscleGroupSummaryOut("ABS", weak_count = 0, total_count = 2, weak_movements = emptyList()),
                MuscleGroupSummaryOut("QUADS", weak_count = 0, total_count = 4, weak_movements = emptyList()),
            ),
            movements = emptyList(),
        )

        assertEquals(emptyList<MuscleGroupSummaryOut>(), weakPointMuscleGroups(assessment))
    }

    @Test fun only_groups_with_weak_counts_render() {
        val weakMovement = WeakMovementOut(
            movement_id = 42,
            name = "Back Squat",
            stalled = true,
            lagging = false,
        )
        val cleanGroup = MuscleGroupSummaryOut(
            muscle = "ABS",
            weak_count = 0,
            total_count = 2,
            weak_movements = emptyList(),
        )
        val weakGroup = MuscleGroupSummaryOut(
            muscle = "QUADS",
            weak_count = 1,
            total_count = 4,
            weak_movements = listOf(weakMovement),
        )
        val assessment = WeakPointAssessmentOut(
            muscle_groups = listOf(cleanGroup, weakGroup),
            movements = listOf(weakMovement),
        )

        assertEquals(listOf(weakGroup), weakPointMuscleGroups(assessment))
    }
}
