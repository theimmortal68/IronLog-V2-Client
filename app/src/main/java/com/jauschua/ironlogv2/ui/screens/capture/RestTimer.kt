// RestTimer.kt
package com.jauschua.ironlogv2.ui.screens.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jauschua.ironlogv2.data.api.dto.ExerciseOut
import com.jauschua.ironlogv2.data.api.dto.FeedbackTap
import com.jauschua.ironlogv2.data.api.dto.GroupOut
import kotlin.math.roundToInt

/**
 * Rest duration in seconds after logging a set.
 *
 * T1 (and T1b) tiers are RPE-adaptive: [baseRest] scales by how the set felt — shorter after
 * TOO_EASY, longer after TOO_HARD. Every other tier (T2/T3/T4, giant-set rounds) is fixed —
 * [tap] is ignored. [isGiantSet] is accepted for symmetry with the trigger call site
 * ([shouldStartRest]) but never itself changes the result: giant-set rounds are never T1.
 */
internal fun restSeconds(baseRest: Int, tierLabel: String, tap: FeedbackTap, isGiantSet: Boolean): Int {
    val isAdaptiveTier = tierLabel == "T1" || tierLabel == "T1b"
    if (!isAdaptiveTier) return baseRest
    val multiplier = when (tap) {
        FeedbackTap.TOO_EASY -> 0.75
        FeedbackTap.ON_TARGET -> 1.0
        FeedbackTap.TOO_HARD -> 1.5
    }
    return (baseRest * multiplier).roundToInt()
}

/**
 * Rest-trigger context for one planned set, derived once per session load (see
 * [restContextByPlannedSetId]) so [CaptureViewModel.logWorkingSet] doesn't need to walk the
 * full group list on every call.
 */
internal data class SetRestContext(
    val baseRestSeconds: Int,
    val tierLabel: String,
    val isGiantSet: Boolean,
    val triggersRest: Boolean,
)

/**
 * Maps every [com.jauschua.ironlogv2.data.api.dto.PlannedSetOut.id] in [groups] to its
 * [SetRestContext]. A group with no [GroupOut.rest_seconds] is skipped entirely — there's
 * nothing to count down, so its planned sets are simply absent from the result (no trigger).
 */
internal fun restContextByPlannedSetId(groups: List<GroupOut>): Map<Int, SetRestContext> {
    val result = mutableMapOf<Int, SetRestContext>()
    for (g in groups) {
        val baseRest = g.rest_seconds ?: continue
        for (e in g.exercises) {
            val ctx = SetRestContext(
                baseRestSeconds = baseRest,
                tierLabel = g.label ?: "",
                isGiantSet = g.group_type == "GIANT_SET",
                triggersRest = shouldStartRest(g, e),
            )
            for (ps in e.planned_sets) result[ps.id] = ctx
        }
    }
    return result
}

/**
 * Whether logging a set for [exercise] within [group] should auto-start the rest countdown.
 *
 * STRAIGHT groups rest after every set (each exercise rests independently). GIANT_SET groups
 * rest only after the round's LAST item — because [flattenPrescription] flattens GIANT_SET
 * groups round-major (one set per exercise per round, in exercise order), the round's last
 * item is always the group's last exercise, so this needs no round-index bookkeeping.
 */
internal fun shouldStartRest(group: GroupOut, exercise: ExerciseOut): Boolean =
    group.group_type != "GIANT_SET" || exercise.id == group.exercises.last().id

/**
 * `"m:ss"` display for the countdown label, e.g. `90` -> `"1:30"`, `5` -> `"0:05"`.
 */
internal fun formatRestTime(remainingSeconds: Int): String {
    val minutes = remainingSeconds / 60
    val seconds = remainingSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * Skippable rest countdown bar. Shows the remaining time, a Skip action, and a +30s action.
 * Purely a display wrapper — the countdown itself (ticking, skip, add-time) lives in
 * [CaptureViewModel] as testable state; this composable only renders whatever it's given.
 */
@Composable
fun RestTimerBar(
    remainingSeconds: Int,
    onSkip: () -> Unit,
    onAddTime: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Rest: ${formatRestTime(remainingSeconds)}",
                style = MaterialTheme.typography.titleMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onAddTime) { Text("+30s") }
                TextButton(onClick = onSkip) { Text("Skip") }
            }
        }
    }
}
