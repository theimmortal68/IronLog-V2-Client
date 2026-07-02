package com.jauschua.ironlogv2.ui.screens.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jauschua.ironlogv2.data.api.dto.PlannedSetOut
import com.jauschua.ironlogv2.data.api.dto.SessionDetailResponse
import com.jauschua.ironlogv2.ui.ErrorRetryBox
import com.jauschua.ironlogv2.ui.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    vm: CaptureViewModel = viewModel(factory = CaptureViewModel.TodayFactory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val uiError by vm.uiError.collectAsStateWithLifecycle()
    val submitResult by vm.submitResult.collectAsStateWithLifecycle()
    val currentPlannedSetId by vm.currentPlannedSetId.collectAsStateWithLifecycle()
    val restRemainingSeconds by vm.restRemainingSeconds.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { vm.load() }

    Scaffold(topBar = { TopAppBar(title = { Text("Capture") }) }) { inner ->
        Surface(modifier = Modifier.fillMaxSize().padding(inner)) {
            when (val s = state) {
                is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is UiState.Error -> ErrorRetryBox(s.msg) { vm.load() }
                is UiState.Success -> {
                    val session = s.data
                    if (session == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No planned session — generate one.")
                        }
                    } else {
                        SessionContent(
                            session, currentPlannedSetId, uiError, submitResult,
                            restRemainingSeconds, scope, vm,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionContent(
    session: SessionDetailResponse,
    currentPlannedSetId: Int?,
    uiError: String?,
    submitResult: String?,
    restRemainingSeconds: Int?,
    scope: CoroutineScope,
    vm: CaptureViewModel,
) {
    // Flattened prescription for cursor-position queries (stable as long as session doesn't
    // change). MUST reuse the VM's flattenPrescription — GIANT_SET groups are round-major there
    // (one set per exercise per round), so a screen-local exercise-major re-derivation would
    // disagree with the cursor and mis-render past/checkmark state for supersets. See
    // flattenPrescription's doc comment in CaptureViewModel.kt.
    val flatSets = remember(session) { flattenPrescription(session.groups) }
    // IDs of sets that appear BEFORE the cursor in the flat (round-major) order — rendered "✓".
    val pastIds = remember(session, currentPlannedSetId) { pastSetIds(flatSets, currentPlannedSetId) }
    // The planned set the cursor currently points at — source of the pre-fill defaults below.
    val currentSet = remember(session, currentPlannedSetId) { flatSets.find { it.id == currentPlannedSetId } }

    // Input state for the current set; auto-resets (and re-pre-fills) when the cursor advances.
    // Weight/reps default to the prescription target — the lifter can accept or adjust before
    // logging ("log = accept or adjust").
    var setLoad by remember(currentPlannedSetId) { mutableStateOf(prefillWeight(currentSet?.target_load)) }
    var setReps by remember(currentPlannedSetId) { mutableStateOf(currentSet?.let(::prefillReps) ?: "") }
    var selectedTap by remember(currentPlannedSetId) { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        // Session header
        item {
            Text(
                text = "${session.date} • ${session.day_role} • ${session.phase}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Rest countdown — auto-started by the VM on set-log (see restContextByPlannedSetId /
        // shouldStartRest); shown only while a countdown is running.
        restRemainingSeconds?.let { remaining ->
            item(key = "rest-timer") {
                RestTimerBar(
                    remainingSeconds = remaining,
                    onSkip = vm::skipRest,
                    onAddTime = { vm.addRestTime(30) },
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }

        session.groups.forEachIndexed { gi, group ->
            item(key = "group-$gi") {
                Text(
                    text = "Group ${gi + 1} — ${group.group_type}" +
                        (group.label?.let { " ($it)" } ?: ""),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            group.exercises.forEachIndexed { ei, exercise ->
                item(key = "ex-$gi-$ei") {
                    Text(
                        text = exercise.movement_name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }

                exercise.planned_sets.forEach { plannedSet ->
                    val isCurrent = plannedSet.id == currentPlannedSetId
                    val isPast = plannedSet.id in pastIds
                    val tapRequired = plannedSet.set_role in setOf("WORKING", "TOP", "BACKOFF")

                    item(key = "set-${plannedSet.id}") {
                        SetCard(
                            plannedSet = plannedSet,
                            unilateral = exercise.unilateral,
                            isCurrent = isCurrent,
                            isPast = isPast,
                            tapRequired = tapRequired,
                            setLoad = if (isCurrent) setLoad else "",
                            setReps = if (isCurrent) setReps else "",
                            selectedTap = if (isCurrent) selectedTap else null,
                            onLoadChange = { setLoad = it },
                            onRepsChange = { setReps = it },
                            onTapSelect = { selectedTap = it },
                            onLogSet = {
                                scope.launch {
                                    vm.logWorkingSet(
                                        plannedSetId = plannedSet.id,
                                        movementId = exercise.movement_id,
                                        setIndex = plannedSet.set_index,
                                        setRole = plannedSet.set_role,
                                        actualLoad = setLoad.toDoubleOrNull(),
                                        actualReps = setReps.toIntOrNull(),
                                        tap = selectedTap,
                                        isWarmup = plannedSet.is_warmup,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }

        // UI error (tap required, etc.)
        uiError?.let { msg ->
            item(key = "error") {
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }

        // Finish / submit result
        item(key = "finish") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                submitResult?.let { result ->
                    val color = if (result == "COMPLETED") MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                    Text("Session $result", color = color)
                }
                Button(
                    onClick = { vm.finish() },
                    enabled = submitResult != "COMPLETED",
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (submitResult == "COMPLETED") "Submitted" else "Finish & Submit")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetCard(
    plannedSet: PlannedSetOut,
    unilateral: Boolean,
    isCurrent: Boolean,
    isPast: Boolean,
    tapRequired: Boolean,
    setLoad: String,
    setReps: String,
    selectedTap: String?,
    onLoadChange: (String) -> Unit,
    onRepsChange: (String) -> Unit,
    onTapSelect: (String) -> Unit,
    onLogSet: () -> Unit,
) {
    // "Log set" button is DISABLED until a tap is selected for working roles (Gate #2 — client UI).
    val logEnabled = !tapRequired || selectedTap != null

    Card(modifier = Modifier.fillMaxWidth().padding(start = 16.dp)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Set header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${plannedSet.set_role} #${plannedSet.set_index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                )
                if (isPast) {
                    Text(
                        text = "✓",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            // Target prescription — weight + reps (single number when fixed, range otherwise).
            // The phased pull-up AMRAP/assisted-pair widget is DEFERRED (server never populates
            // target_unassisted_reps/target_assisted_reps) — see repsTargetLabel's doc comment.
            val weightTarget = plannedSet.target_load?.let { "${formatWeight(it)}lb" }
            val repsTarget = repsTargetLabel(plannedSet).takeIf { it.isNotEmpty() }
            val target = listOfNotNull(weightTarget, repsTarget).joinToString(" ")
            if (target.isNotEmpty()) {
                Text(
                    text = "Target: $target",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // RPE — shown prominently; for fixed-rep lifts this is the real progression signal.
            rpeLabel(plannedSet.target_rpe)?.let { rpe ->
                Text(
                    text = rpe,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // Unilateral affordance — label the set "per side" clearly.
            perSideLabel(unilateral)?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            // Input controls for the current set only
            if (isCurrent) {
                val repsLabel = repsInputLabel(plannedSet)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = setLoad,
                        onValueChange = onLoadChange,
                        label = { Text("Load (lb)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = setReps,
                        onValueChange = onRepsChange,
                        label = { Text(repsLabel) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                // Three-state tap (mandatory for WORKING / TOP / BACKOFF)
                val taps = listOf("TOO_EASY", "ON_TARGET", "TOO_HARD")
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    taps.forEachIndexed { i, tap ->
                        SegmentedButton(
                            selected = selectedTap == tap,
                            onClick = { onTapSelect(tap) },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = taps.size),
                        ) {
                            Text(
                                text = tap.replace('_', ' '),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }

                // "Log set" is disabled until tap is selected for working roles.
                Button(
                    onClick = onLogSet,
                    enabled = logEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Log set")
                }
            }
        }
    }
}

// ── Pure display/pre-fill logic (extracted so Compose UI, which can't be unit-tested here, ────
//    stays a thin wrapper around testable functions) ────────────────────────────────────────

/**
 * IDs of every [PlannedSetOut] that appears BEFORE the cursor in [flatSets] — rendered "✓" by
 * [SessionContent]. `flatSets` MUST come from [flattenPrescription] (round-major for GIANT_SET,
 * exercise-major for STRAIGHT) so this agrees with the VM's actual logging cursor; see the
 * must-fix note on [flattenPrescription] in CaptureViewModel.kt. When [currentPlannedSetId] is
 * null (all done) or not found, every set in [flatSets] is past.
 */
internal fun pastSetIds(flatSets: List<PlannedSetOut>, currentPlannedSetId: Int?): Set<Int> {
    val cursorIdx = flatSets.indexOfFirst { it.id == currentPlannedSetId }
        .let { if (it < 0) flatSets.size else it }
    return flatSets.take(cursorIdx).map { it.id }.toSet()
}

/**
 * Reps target as a compact number: `"8"` when [low] == [high] (fixed-rep lift), `"8-12"` for a
 * range. Falls back to whichever bound is present if only one is set; blank when both are null.
 * Used both for the input pre-fill (single-number case is directly loggable as-is) and, suffixed
 * with " reps", for the target display line.
 */
internal fun formatRepsTarget(low: Int?, high: Int?): String = when {
    low != null && high != null && low == high -> "$low"
    low != null && high != null -> "$low-$high"
    low != null -> "$low"
    high != null -> "$high"
    else -> ""
}

/**
 * `"Target: ... 8 reps"` / `"... 8-12 reps"` display phrasing. Blank when there is no reps
 * target at all.
 *
 * The phased pull-up AMRAP / "X unassisted / Y assisted" widget is DEFERRED — the server never
 * populates `target_unassisted_reps` / `target_assisted_reps` on `PlannedSetOut` (confirmed), so
 * this intentionally does not branch on them. Assisted movements (e.g. pull-ups) fall through to
 * the standard reps target the server actually sends. The DTO fields stay defined for the future
 * server-side phased population + rich widget; the client just no longer depends on them.
 */
internal fun repsTargetLabel(plannedSet: PlannedSetOut): String =
    formatRepsTarget(plannedSet.target_reps_low, plannedSet.target_reps_high)
        .let { if (it.isEmpty()) "" else "$it reps" }

/**
 * Reps INPUT pre-fill — a numeric, directly-loggable default so tapping "Log set" without
 * editing records a real number, never null (`toIntOrNull()` on a non-numeric string like
 * `"8-12"` used to return null here). Fixed target (`low == high`): the number itself. Range
 * target: the LOW end, as a sensible starting default the lifter can adjust upward. Blank only
 * when neither bound is present. The full range stays visible via [repsInputLabel] (field label)
 * and [repsTargetLabel] (the "Target: ..." line above) — this function must stay numeric-only.
 */
internal fun prefillReps(plannedSet: PlannedSetOut): String = when {
    plannedSet.target_reps_low != null -> "${plannedSet.target_reps_low}"
    plannedSet.target_reps_high != null -> "${plannedSet.target_reps_high}"
    else -> ""
}

/**
 * Reps input field label — `"Reps"` for a fixed target, `"Reps (8-12)"` for a range so the full
 * target stays visible next to the numeric-only pre-fill in [prefillReps].
 */
internal fun repsInputLabel(plannedSet: PlannedSetOut): String {
    val low = plannedSet.target_reps_low
    val high = plannedSet.target_reps_high
    return if (low != null && high != null && low != high) "Reps ($low-$high)" else "Reps"
}

/** Drops a trailing ".0" so weight/RPE display as "135" rather than "135.0". */
internal fun formatWeight(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

/** Weight input pre-fill — [targetLoad] as an editable default; blank when null
 * (needs-calibration: no floor to prefill). */
internal fun prefillWeight(targetLoad: Double?): String = targetLoad?.let(::formatWeight) ?: ""

/** `"RPE 8"` / `"RPE 7.5"` prominent label, or null when the set carries no RPE target. */
internal fun rpeLabel(rpe: Double?): String? = rpe?.let { "RPE ${formatWeight(it)}" }

/** `"Per side"` affordance for a unilateral exercise's set, or null for bilateral exercises. */
internal fun perSideLabel(unilateral: Boolean): String? = if (unilateral) "Per side" else null
