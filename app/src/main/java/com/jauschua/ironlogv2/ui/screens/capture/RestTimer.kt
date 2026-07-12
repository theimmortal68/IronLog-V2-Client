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
 * T1 (and T1b) and giant-set rounds are RPE-adaptive: [baseRest] scales by how the set felt — shorter after
 * TOO_EASY, longer after TOO_HARD. Every other tier (T2/T3/T4 non-giant-set) is fixed —
 * [tap] is ignored.
 */
internal fun restSeconds(baseRest: Int, tierLabel: String, tap: FeedbackTap, isGiantSet: Boolean): Int {
    val isAdaptiveTier = tierLabel == "T1" || tierLabel == "T1b" || isGiantSet
    if (!isAdaptiveTier) return baseRest
    val multiplier = when (tap) {
        FeedbackTap.TOO_EASY -> 0.75
        FeedbackTap.ON_TARGET -> 1.0
        FeedbackTap.TOO_HARD -> 1.5
    }
    return (baseRest * multiplier).roundToInt()
}

/**
 * Reduces the taps for all sets in a round down to the hardest (worst) one across the round:
 * [FeedbackTap.TOO_HARD] > [FeedbackTap.ON_TARGET] > [FeedbackTap.TOO_EASY].
 *
 * If a set in [roundPlannedSetIds] has no logged actual yet or a missing tap, it is treated as
 * [FeedbackTap.ON_TARGET] for aggregation (same default behavior as [CaptureViewModel.logWorkingSet]).
 */
internal fun hardestTapForRound(
    loggedActuals: Map<Pair<Int, Int>, LoggedSetActual>,
    roundPlannedSetIds: Iterable<Int>,
): FeedbackTap {
    var worst = FeedbackTap.TOO_EASY
    var foundAny = false
    for (id in roundPlannedSetIds) {
        val actualsForSet = loggedActuals
            .filterKeys { (plannedSetId, _) -> plannedSetId == id }
            .values
        val tapStrings = if (actualsForSet.isEmpty()) listOf(null) else actualsForSet.map { it.tap }
        for (tapStr in tapStrings) {
            val tapEnum = if (tapStr != null) {
                runCatching { FeedbackTap.valueOf(tapStr) }.getOrNull() ?: FeedbackTap.ON_TARGET
            } else {
                FeedbackTap.ON_TARGET
            }
            foundAny = true
            if (tapEnum == FeedbackTap.TOO_HARD) {
                return FeedbackTap.TOO_HARD
            }
            if (tapEnum == FeedbackTap.ON_TARGET) {
                worst = FeedbackTap.ON_TARGET
            }
        }
    }
    return if (foundAny) worst else FeedbackTap.ON_TARGET
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
 *
 * A rest only fires when a further set follows that this rest precedes, so [triggersRest] is
 * computed PER planned-set: `shouldStartRest(g, e) && ps.id != e.planned_sets.last().id` — i.e.
 * the trigger-owning exercise (see [shouldStartRest]) rests after each of its sets EXCEPT its
 * last. For a STRAIGHT exercise this suppresses the rest after its final set; for a GIANT_SET
 * (where only the group's last exercise owns the trigger) the last exercise's final planned set
 * IS the final round's terminal item, so this suppresses the after-last-round rest too. The
 * other fields ([baseRestSeconds], [tierLabel], [isGiantSet]) are identical across an exercise's
 * sets.
 */
internal fun restContextByPlannedSetId(groups: List<GroupOut>): Map<Int, SetRestContext> {
    val result = mutableMapOf<Int, SetRestContext>()
    for (g in groups) {
        val baseRest = g.rest_seconds ?: continue
        for (e in g.exercises) {
            val ownsTrigger = shouldStartRest(g, e)
            val lastSetId = e.planned_sets.lastOrNull()?.id
            for (ps in e.planned_sets) {
                result[ps.id] = SetRestContext(
                    baseRestSeconds = baseRest,
                    tierLabel = g.label ?: "",
                    isGiantSet = g.group_type == "GIANT_SET",
                    triggersRest = ownsTrigger && ps.id != lastSetId,
                )
            }
        }
    }
    return result
}

/**
 * Maps every [com.jauschua.ironlogv2.data.api.dto.PlannedSetOut.id] in [groups] to the list of
 * planned set IDs belonging to its same round (same group, same round index / set_index).
 */
internal fun roundPlannedSetIdsBySetId(groups: List<GroupOut>): Map<Int, List<Int>> {
    val result = mutableMapOf<Int, List<Int>>()
    for (g in groups) {
        if (g.group_type == "GIANT_SET") {
            for (r in 0 until g.rounds) {
                val roundIds = g.exercises.mapNotNull { e -> e.planned_sets.getOrNull(r)?.id }
                for (id in roundIds) {
                    result[id] = roundIds
                }
            }
        } else {
            for (e in g.exercises) {
                for (ps in e.planned_sets) {
                    result[ps.id] = listOf(ps.id)
                }
            }
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
fun formatRestTime(remainingSeconds: Int): String {
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
