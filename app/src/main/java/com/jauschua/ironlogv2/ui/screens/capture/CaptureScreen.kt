package com.jauschua.ironlogv2.ui.screens.capture

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import android.view.WindowManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jauschua.ironlogv2.data.api.dto.GroupOut
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
    var setFeltPeak by remember(currentPlannedSetId) { mutableStateOf("") }

    // Collapsible group cards (accordion follows the cursor). Index of the group that owns the
    // current planned set — the one auto-expanded by default; null when the session is fully
    // logged (currentPlannedSetId == null), so every group collapses.
    val currentGroupIndex = remember(session, currentPlannedSetId) {
        session.groups.indexOfFirst { g -> g.exercises.any { e -> e.planned_sets.any { it.id == currentPlannedSetId } } }
            .takeIf { it >= 0 }
    }
    // Manual expand/collapse overrides, keyed by group index. Cleared whenever the cursor advances
    // to a NEW group (below), so a manual toggle persists only until then, then reverts to auto.
    val expandOverrides = remember { mutableStateMapOf<Int, Boolean>() }
    LaunchedEffect(currentGroupIndex) { expandOverrides.clear() }
    // A group is expanded if manually overridden; otherwise iff it holds the cursor.
    fun isGroupExpanded(gi: Int): Boolean = expandOverrides[gi] ?: (gi == currentGroupIndex)

    // Alarm-stream tone cues driven off the countdown value (see RestToneCue). The VM's ticker
    // emits 120…2, 1, then null (it never emits 0 — `next <= 0` clears to null and breaks), so
    // completion is the 1 → null transition. A skip clears from an arbitrary value to null, so
    // only fire the end tone when the PREVIOUS value was 1 (natural completion), not on skip.
    val toneCue = remember { RestToneCue() }
    DisposableEffect(Unit) { onDispose { toneCue.release() } }
    var prevRest by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(restRemainingSeconds) {
        when (val remaining = restRemainingSeconds) {
            15 -> toneCue.warning()
            3, 2, 1 -> toneCue.tick()
            null -> if (prevRest == 1) toneCue.done()
            else -> {}
        }
        prevRest = restRemainingSeconds
    }

    // Keep the screen awake while resting so the countdown keeps ticking and the tones fire;
    // clear the flag the moment the rest ends or the screen leaves composition.
    val view = LocalView.current
    DisposableEffect(restRemainingSeconds != null) {
        val window = view.context.findActivity()?.window
        if (restRemainingSeconds != null) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Sticky rest countdown — pinned above the scrolling list so it stays visible while
        // running. Composed only while a countdown is active (no reserved gap otherwise).
        restRemainingSeconds?.let { remaining ->
            RestTimerBar(
                remainingSeconds = remaining,
                onSkip = vm::skipRest,
                onAddTime = { vm.addRestTime(30) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
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

            // Starting shoe — the first group's shoe, e.g. "👟 Metcon 9". Covers group 0, so
            // group 0 never also gets a swap banner (see the loop below).
            session.groups.firstNotNullOfOrNull { it.shoe }?.let { startingShoe ->
                item(key = "starting-shoe") {
                    Text(
                        text = "👟 $startingShoe",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Running "previous HT (band-composite) setup" — walked sequentially across the
            // WHOLE session's exercises, in order, independent of any group's expand/collapse
            // UI state (data-level checkpoint, not a rendering concern; recomputed fresh on
            // every recomposition of this content, so no `remember` needed). Declared once here
            // (not per-group) because HT exercises can span different groups. Feeds htReconfigure
            // (see CaptureScreen.kt's pure-logic section) to cue the lifter when a later HT
            // exercise's plates/bands differ from the one most recently seen.
            var prevHtSetup: Pair<Double?, List<Int>?>? = null

            session.groups.forEachIndexed { gi, group ->
                // Auto-accordion: expanded iff this group holds the cursor, unless manually
                // overridden. Compute here (LazyColumn item lambdas aren't a scope for a local fun).
                val expanded = isGroupExpanded(gi)

                // Mid-session swap cue: fires at the first group whose shoe differs from the
                // PREVIOUS group's shoe. Group 0 is excluded — the session header already covers
                // the starting shoe, so it never also gets a banner.
                if (gi > 0) {
                    shoeTransition(session.groups[gi - 1].shoe, group.shoe)?.let { swapTo ->
                        item(key = "shoe-swap-$gi") {
                            Text(
                                text = "👟 Swap to $swapTo",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                }

                item(key = "group-$gi") {
                    GroupHeader(
                        title = group.label ?: group.group_type,
                        progressHint = groupProgressHint(group, pastIds),
                        expanded = expanded,
                        onToggle = { expandOverrides[gi] = !expanded },
                    )
                }

                group.exercises.forEachIndexed { ei, exercise ->
                    // HT setup for this exercise, if any — the first planned set carrying
                    // target_plates/band_config (all planned sets in an HT exercise share the
                    // same prescribed setup, so the first is representative). Tracked BEFORE the
                    // `if (expanded)` gate below so prevHtSetup stays correct across collapsed
                    // groups too.
                    val htSet = exercise.planned_sets.firstOrNull {
                        it.target_plates != null || it.band_config != null
                    }
                    val reconfigureText = htSet?.let { s ->
                        prevHtSetup?.let { (prevPlates, prevConfig) ->
                            htReconfigure(prevPlates, prevConfig, s.target_plates, s.band_config)
                        }
                    }
                    if (htSet != null) {
                        // Always update, whether or not the banner fired, so the NEXT HT
                        // exercise compares against THIS one.
                        prevHtSetup = htSet.target_plates to htSet.band_config
                    }

                    if (expanded) {
                        if (reconfigureText != null) {
                            item(key = "ht-reconfigure-$gi-$ei") {
                                Text(
                                    text = reconfigureText,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            }
                        }

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
                                    setFeltPeak = if (isCurrent) setFeltPeak else "",
                                    onLoadChange = { setLoad = it },
                                    onRepsChange = { setReps = it },
                                    onTapSelect = { selectedTap = it },
                                    onFeltPeakChange = { setFeltPeak = it },
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
                                                feltPeak = setFeltPeak.toDoubleOrNull(),
                                            )
                                        }
                                    },
                                )
                            }
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
                        onClick = { scope.launch { vm.finish() } },
                        enabled = submitResult != "COMPLETED",
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (submitResult == "COMPLETED") "Submitted" else "Finish & Submit")
                    }
                }
            }
        }
    }
}

/**
 * Clickable, collapsible group header row: the tier label ([title], e.g. "T1" / "T2 GS"), a
 * progress hint ([progressHint], e.g. "4/9 sets" or "✓ done"), and a right-edge `−`/`+` affordance
 * (`−` expanded, `+` collapsed). The whole row toggles via [onToggle].
 */
@Composable
private fun GroupHeader(
    title: String,
    progressHint: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "· $progressHint",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = if (expanded) "−" else "+",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
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
    setFeltPeak: String,
    onLoadChange: (String) -> Unit,
    onRepsChange: (String) -> Unit,
    onTapSelect: (String) -> Unit,
    onFeltPeakChange: (String) -> Unit,
    onLogSet: () -> Unit,
) {
    // "Log set" button is DISABLED until a tap is selected for working roles (Gate #2 — client UI).
    val logEnabled = !tapRequired || selectedTap != null

    // HT (band-composite) working set: plates and/or bands prescribed on this planned set.
    val isHtSet = plannedSet.target_plates != null || plannedSet.band_config != null

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

            // HT (band-composite) setup line — plates + band names + target felt peak.
            if (isHtSet) {
                Text(
                    text = htSetupLine(plannedSet.target_plates, plannedSet.band_config, plannedSet.target_felt_peak),
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

                // Felt-peak capture — HT working sets only (band-composite peak resistance felt
                // at the top of the rep, distinct from the plate-only load above).
                if (isHtSet && tapRequired) {
                    OutlinedTextField(
                        value = setFeltPeak,
                        onValueChange = onFeltPeakChange,
                        label = { Text("Felt peak (lb)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
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
 * Collapsed-card progress hint for a group: `"✓ done"` when every set in the group is logged (all
 * its planned-set ids are in [pastIds]), else `"logged/total sets"`. A set is "logged" iff it's
 * before the cursor — [pastIds] is the same [pastSetIds] set [SessionContent] already computes.
 * An empty group (total == 0) reports `"0/0 sets"`, never `"✓ done"`.
 */
internal fun groupProgressHint(group: GroupOut, pastIds: Set<Int>): String {
    val ids = group.exercises.flatMap { it.planned_sets }.map { it.id }
    val total = ids.size
    val logged = ids.count { it in pastIds }
    return if (total > 0 && logged == total) "✓ done" else "$logged/$total sets"
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

/**
 * Shoe-swap cue decision for a group boundary: the shoe to swap TO (rendered as a
 * `"👟 Swap to X"` banner at that group) when [thisShoe] is non-null and differs from
 * [prevShoe] — the previous group's shoe. Null (no banner) when the shoe is unchanged or when
 * this group carries no shoe assignment at all. Pure display — never touches engine/state.
 */
internal fun shoeTransition(prevShoe: String?, thisShoe: String?): String? =
    if (thisShoe != null && thisShoe != prevShoe) thisShoe else null

// ── Task 6: HT band-composite — pure helpers ─────────────────────────────────────────────

/** Band id (index) -> display name. Index 0-5 only; five colors past index 5 have no name. */
private val BAND_NAMES = listOf("Orange", "Red", "Blue", "Green", "Black", "Purple")

/**
 * Maps band ids in [config] to their display names, in order, via [BAND_NAMES]. Out-of-range
 * ids are dropped defensively (via [getOrNull]) rather than crashing. Null or empty [config]
 * yields an empty list.
 */
internal fun bandNames(config: List<Int>?): List<String> =
    config?.mapNotNull { BAND_NAMES.getOrNull(it) } ?: emptyList()

/**
 * Composes the "plates + band names" portion of an HT setup string, e.g. `"166 plates + Orange,
 * Red"`. Either part may be absent — this builds whatever is present, joined with `" + "`.
 * Returns an empty string when both [plates] and [config] are null/empty (no HT setup at all).
 * Shared by [htSetupLine] (adds the peak suffix) and [htReconfigure] (banner text — no peak).
 */
private fun composePlatesAndBands(plates: Double?, config: List<Int>?): String {
    val platesPart = plates?.let { "${formatWeight(it)} plates" }
    val bandsPart = bandNames(config).takeIf { it.isNotEmpty() }?.joinToString(", ")
    return listOfNotNull(platesPart, bandsPart).joinToString(" + ")
}

/**
 * Full HT setup display line for [SetCard]'s target row: `"<plates> plates + <bands> · peak
 * ~<target>"`. Any of the three parts may be absent; present parts are joined with the same
 * `+`/`·` separators as the brief specifies (e.g. plates-only -> `"166 plates"`, peak-only ->
 * `"peak ~250"`). Reuses [composePlatesAndBands] for the first two parts rather than duplicating
 * that formatting here.
 */
internal fun htSetupLine(plates: Double?, config: List<Int>?, targetFeltPeak: Double?): String {
    val platesAndBands = composePlatesAndBands(plates, config).takeIf { it.isNotEmpty() }
    val peakPart = targetFeltPeak?.let { "peak ~${formatWeight(it)}" }
    return listOfNotNull(platesAndBands, peakPart).joinToString(" · ")
}

/**
 * Reconfigure banner cue: fires (returns a non-null "Reconfigure to ..." string) when EITHER the
 * band config differs from [prevConfig] OR the plate count differs from [prevPlates] — an OR,
 * not an AND. Comparisons are plain Kotlin equality (order-sensitive `List<Int>?`, exact
 * `Double?` — no tolerance/rounding). Returns null when neither differs, and also returns null
 * when [plates] and [config] are BOTH null (nothing to reconfigure TO, even if the prior setup
 * was non-null — don't recommend an empty setup). Total/null-safe for any combination of nulls.
 * Reuses [composePlatesAndBands] so the banner text renders the same "plates + bands" shape as
 * [htSetupLine]'s setup line, just prefixed with "Reconfigure to " and with no peak suffix
 * (peak isn't part of "reconfigure").
 */
internal fun htReconfigure(
    prevPlates: Double?,
    prevConfig: List<Int>?,
    plates: Double?,
    config: List<Int>?,
): String? {
    if (plates == null && config == null) return null
    val differs = config != prevConfig || plates != prevPlates
    if (!differs) return null
    return "Reconfigure to ${composePlatesAndBands(plates, config)}"
}

/**
 * Observed peak-minus-plates: [feltPeak] `-` [plates], meaningful ONLY for a single-band setup
 * (the arithmetic isolates one band's contribution; it doesn't decompose across a multi-band
 * stack). Returns null when [config] is null, empty, or has 2+ elements, or when [feltPeak] or
 * [plates] is null.
 */
internal fun htObservedPeak(feltPeak: Double?, plates: Double?, config: List<Int>?): Double? {
    if (config == null || config.size != 1) return null
    if (feltPeak == null || plates == null) return null
    return feltPeak - plates
}
