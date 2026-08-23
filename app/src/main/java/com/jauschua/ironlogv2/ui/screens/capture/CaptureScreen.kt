package com.jauschua.ironlogv2.ui.screens.capture

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jauschua.ironlogv2.data.api.dto.FinisherOut
import com.jauschua.ironlogv2.data.api.dto.GroupOut
import com.jauschua.ironlogv2.data.api.dto.MovementSummary
import com.jauschua.ironlogv2.data.api.dto.PlannedSetOut
import com.jauschua.ironlogv2.data.api.dto.SessionDetailResponse
import com.jauschua.ironlogv2.data.api.dto.Status
import com.jauschua.ironlogv2.data.api.dto.WarmupOut
import com.jauschua.ironlogv2.service.IntervalTimerController
import com.jauschua.ironlogv2.service.clampedIntervalWorkSeconds
import com.jauschua.ironlogv2.ui.ErrorRetryBox
import com.jauschua.ironlogv2.ui.UiState
import com.jauschua.ironlogv2.ui.screens.movements.MovementsListViewModel
import com.jauschua.ironlogv2.ui.screens.review.displayMovementName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The DTO's unit_hint marker for assisted/incline movements (assist value, not a weight).
 * UNIT_ASSIST is the legacy generic hint (unclassified movements); the rest are the
 * server's specific classifications (see app.py's _unit_hint_for/_ASSIST_UNIT_HINTS). */
private const val UNIT_ASSIST = "assist"
private const val UNIT_ASSIST_DEGREES = "assist_degrees"
private const val UNIT_ASSIST_BANDS = "assist_bands"
private const val UNIT_ASSIST_LB = "assist_lb"
private const val UNIT_ASSIST_REPS = "assist_reps"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    vm: CaptureViewModel = viewModel(factory = CaptureViewModel.TodayFactory),
    movementsVm: MovementsListViewModel = viewModel(factory = MovementsListViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val uiError by vm.uiError.collectAsStateWithLifecycle()
    val submitResult by vm.submitResult.collectAsStateWithLifecycle()
    val currentPlannedSetId by vm.currentPlannedSetId.collectAsStateWithLifecycle()
    val restRemainingSeconds by vm.restRemainingSeconds.collectAsStateWithLifecycle()
    val intervalRemainingSeconds by vm.intervalRemainingSeconds.collectAsStateWithLifecycle()
    val intervalPhaseLabel by vm.intervalPhaseLabel.collectAsStateWithLifecycle()
    val pendingReview by vm.pendingReview.collectAsStateWithLifecycle()
    val loggedSetActuals by vm.loggedSetActuals.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Full-library movement list for the swap sheet's search field — reuses the SAME
    // repo/viewmodel call the Movements tab already uses (MovementsListViewModel ->
    // LibraryRepo.movements()) rather than adding a second fetch, per Task 6's spec. Mapped
    // from MovementDto (full library shape) down to MovementSummary (the swap sheet's shape),
    // filtered to ACTIVE movements only.
    val movementsState by movementsVm.state.collectAsStateWithLifecycle()
    val fullLibrary = remember(movementsState) {
        (movementsState as? UiState.Success)?.data
            ?.filter { it.status == Status.ACTIVE }
            ?.map { MovementSummary(id = it.id, name = it.name, status = it.status.name) }
            ?: emptyList()
    }

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
                            restRemainingSeconds, intervalRemainingSeconds, intervalPhaseLabel,
                            loggedSetActuals, scope, vm, vm.intervalTimerController, fullLibrary,
                        )
                    }
                }
            }
        }

        pendingReview?.let { review ->
            GroupReviewSheet(
                review = review,
                onSave = { flags, note -> scope.launch { vm.saveReview(review.group, flags, note) } },
                onSkip = { vm.dismissReview() },
            )
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
    intervalRemainingSeconds: Int?,
    intervalPhaseLabel: String?,
    loggedSetActuals: Map<Pair<Int, Int>, LoggedSetActual>,
    scope: CoroutineScope,
    vm: CaptureViewModel,
    intervalTimerController: IntervalTimerController,
    fullLibrary: List<MovementSummary>,
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
    // The exercise (and its movement_id) that owns the current planned set — used both to key
    // the carry-forward map below (fix F) and to attribute edits to a movement (fix B).
    val currentExercise = remember(session, currentPlannedSetId) {
        session.groups.flatMap { it.exercises }.find { e -> e.planned_sets.any { it.id == currentPlannedSetId } }
    }

    // Session-level note, entered on the Finish screen; anchored to no movement (null) on submit.
    var sessionNote by remember(session.id) { mutableStateOf("") }

    // Exercise id whose swap sheet is open; null when no sheet is showing. See the
    // ExerciseActionsMenu wiring below and the SwapExerciseSheet render at the end of this
    // composable.
    var swapSheetExerciseId by remember(session.id) { mutableStateOf<Int?>(null) }

    var activeIntervalKey by remember(session.id) { mutableStateOf<String?>(null) }
    val intervalStatus = intervalRemainingSeconds?.let { remaining ->
        InlineIntervalStatus(remainingSeconds = remaining, phaseLabel = intervalPhaseLabel)
    }
    LaunchedEffect(intervalRemainingSeconds) {
        if (intervalRemainingSeconds == null) activeIntervalKey = null
    }

    // Fix F — weight/reps carry forward: last load/reps entered per movement_id this session.
    // Later UNLOGGED sets of the SAME exercise pre-fill to those values only when that field's
    // plan is flat across the exercise. Scoped to the session (not the cursor) so it survives the
    // cursor advancing across sets.
    //
    // Restart-survival fix: seeded from [loggedSetActuals] (persisted Room data, already
    // reconstructed by [CaptureViewModel.load] before this composable ever sees a non-null
    // session — `load()` sets `_loggedSetActuals` before flipping `_state` to `Success`, so this
    // is never a stale/empty read) instead of always starting empty — see [reconstructCarriedLoad]
    // for why the map was previously lost across an app relaunch.
    var carriedLoadByMovement by remember(session.id) {
        mutableStateOf(reconstructCarriedLoad(session, loggedSetActuals))
    }
    var carriedRepsByMovement by remember(session.id) {
        mutableStateOf(reconstructCarriedReps(session, loggedSetActuals))
    }

    val workingPlannedSets = currentExercise?.planned_sets?.filter { !it.is_warmup } ?: emptyList()
    val loadPlanIsFlat = isFlatAcrossSets(workingPlannedSets.map { it.target_load })
    val repsPlanIsFlat = isFlatAcrossRepTargets(
        workingPlannedSets.map { it.target_reps_low to it.target_reps_high },
    )

    // Input state for the current set; auto-resets (and re-pre-fills) when the cursor advances.
    // Weight/reps default to carried-forward entries for this exercise when the plan is flat,
    // else the prescription target — the lifter can accept or adjust before logging.
    var setLoad by remember(currentPlannedSetId) {
        mutableStateOf(
            effectiveLoadPrefill(
                carriedLoadByMovement,
                currentExercise?.movement_id ?: -1,
                currentSet?.target_load,
                loadPlanIsFlat,
            ),
        )
    }
    // Spec 13: compute-once via remember(currentPlannedSetId) can evaluate against stale
    // carriedLoadByMovement / currentExercise during complex multi-StateFlow recompositions or
    // GIANT_SET round transitions. A LaunchedEffect keyed on the cursor and movement guarantees
    // setLoad is resolved fresh against the latest carry map whenever the cursor lands on a new set,
    // while keeping carriedLoadByMovement out of the key so live edits (onLoadChange) aren't overwritten.
    LaunchedEffect(currentPlannedSetId, currentExercise?.movement_id) {
        if (currentPlannedSetId != null) {
            val resolved = effectiveLoadPrefill(
                carriedLoadByMovement,
                currentExercise?.movement_id ?: -1,
                currentSet?.target_load,
                loadPlanIsFlat,
            )
            // TEMP diagnostic logging (spec 13 follow-up) for the still-unconfirmed
            // GIANT_SET carry-forward report — remove once the root cause is pinned
            // down and the fix is confirmed on-device. Filter logcat on "CarryFwd".
            Log.d(
                "CarryFwd",
                "cursor=$currentPlannedSetId movement=${currentExercise?.movement_id} " +
                    "carried=${currentExercise?.movement_id?.let { carriedLoadByMovement[it] }} " +
                    "target=${currentSet?.target_load} resolved=$resolved",
            )
            setLoad = resolved
        }
    }
    var setReps by remember(currentPlannedSetId) {
        mutableStateOf(
            effectiveRepsPrefill(
                carriedRepsByMovement,
                currentExercise?.movement_id ?: -1,
                currentSet,
                repsPlanIsFlat,
            ),
        )
    }
    LaunchedEffect(currentPlannedSetId, currentExercise?.movement_id) {
        if (currentPlannedSetId != null) {
            val resolved = effectiveRepsPrefill(
                carriedRepsByMovement,
                currentExercise?.movement_id ?: -1,
                currentSet,
                repsPlanIsFlat,
            )
            // TEMP diagnostic logging (mirrors the "CarryFwd" load diagnostic, spec 13
            // follow-up) for the still-unconfirmed reps carry-forward report — remove
            // once the root cause is pinned down and the fix is confirmed on-device.
            // Filter logcat on "CarryFwd".
            Log.d(
                "CarryFwd",
                "REPS cursor=$currentPlannedSetId movement=${currentExercise?.movement_id} " +
                    "carried=${currentExercise?.movement_id?.let { carriedRepsByMovement[it] }} " +
                    "planIsFlat=$repsPlanIsFlat resolved=$resolved",
            )
            setReps = resolved
        }
    }
    var selectedTap by remember(currentPlannedSetId) { mutableStateOf<String?>(null) }
    var setFeltPeak by remember(currentPlannedSetId) { mutableStateOf("") }

    // Fix B — editable logged sets: which past set-side card is currently reopened for correction
    // (null = none). Only one at a time; re-tapping the same card closes it.
    var editingSetKey by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var editLoad by remember(editingSetKey) {
        mutableStateOf(loggedSetActuals[editingSetKey]?.actualLoad?.let(::formatWeight) ?: "")
    }
    var editReps by remember(editingSetKey) {
        mutableStateOf(loggedSetActuals[editingSetKey]?.actualReps?.toString() ?: "")
    }
    var editTap by remember(editingSetKey) {
        mutableStateOf(loggedSetActuals[editingSetKey]?.tap)
    }

    // Scroll-into-view: when the cursor advances to a new set, the accordion re-flows (the
    // just-logged group collapses, the next one expands) but the LazyColumn itself doesn't
    // scroll, so the newly-current set's input card can end up off-screen. A LazyColumn only
    // COMPOSES visible items, so an off-screen card's modifier-based relocation (the previous
    // BringIntoViewRequester approach) attaches to a node that never gets laid out — silent
    // no-op. Instead, track each item's stable key as the LazyColumn content is built (the DSL
    // registration below runs eagerly every recomposition, unlike each item's lazily-composed
    // body) and animate-scroll to the current set's position by key lookup.
    val listState = rememberLazyListState()
    val itemKeys = remember { mutableListOf<String>() }
    LaunchedEffect(currentPlannedSetId) {
        if (currentPlannedSetId != null) {
            val index = itemKeys.indexOf("set-$currentPlannedSetId")
            if (index >= 0) listState.animateScrollToItem(index)
        }
    }

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

    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val anyTimerRunning = restRemainingSeconds != null || intervalRemainingSeconds != null
    LaunchedEffect(anyTimerRunning) {
        if (
            anyTimerRunning &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Keep the visible capture screen awake while resting; the service owns ticking and tones.
    val view = LocalView.current
    DisposableEffect(anyTimerRunning) {
        val window = view.context.findActivity()?.window
        if (anyTimerRunning) {
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
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            // Reset the key->position tracker; the block below re-registers one entry per
            // item(...) call, in the exact order they're added, so `itemKeys.indexOf(key)`
            // matches that item's real LazyColumn index.
            itemKeys.clear()

            session.warmup?.let { warmup ->
                itemKeys.add("warmup")
                item(key = "warmup") {
                    WarmupSection(
                        warmup = warmup,
                        activeIntervalKey = activeIntervalKey,
                        intervalStatus = intervalStatus,
                        onStartJumpRope = { key, seconds ->
                            activeIntervalKey = key
                            intervalTimerController.startCountdown(seconds, "Jump Rope", leadInSeconds = 5)
                        },
                        onStopInterval = {
                            intervalTimerController.stop()
                            activeIntervalKey = null
                        },
                    )
                }
            }

            // Session header
            itemKeys.add("header")
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
                itemKeys.add("starting-shoe")
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
                        itemKeys.add("shoe-swap-$gi")
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

                itemKeys.add("group-$gi")
                item(key = "group-$gi") {
                    GroupHeader(
                        title = group.label ?: group.group_type,
                        progressHint = groupProgressHint(group, pastIds),
                        expanded = expanded,
                        onToggle = { expandOverrides[gi] = !expanded },
                    )
                }

                if (groupIsComplete(group, pastIds)) {
                    itemKeys.add("review-$gi")
                    item(key = "review-$gi") {
                        TextButton(onClick = { scope.launch { vm.openReview(group) } }) {
                            Text("✎ Review flags / note")
                        }
                    }
                }

                // Pre-compute HT setup for all exercises to maintain sequential `prevHtSetup`
                val reconfigureTexts = mutableMapOf<Int, String?>()
                group.exercises.forEachIndexed { ei, exercise ->
                    val htSet = exercise.planned_sets.firstOrNull {
                        it.target_plates != null || it.band_config != null
                    }
                    val reconfigureText = htSet?.let { s ->
                        prevHtSetup?.let { (prevPlates, prevConfig) ->
                            htReconfigure(prevPlates, prevConfig, s.target_plates, s.band_config)
                        }
                    }
                    if (htSet != null) {
                        prevHtSetup = htSet.target_plates to htSet.band_config
                    }
                    if (reconfigureText != null) {
                        reconfigureTexts[ei] = reconfigureText
                    }
                }

                if (expanded) {
                    if (group.group_type == "GIANT_SET") {
                        val emittedHtForExercise = mutableSetOf<Int>()
                        val rounds = group.rounds
                        for (round in 0 until rounds) {
                            group.exercises.forEachIndexed { ei, exercise ->
                                val plannedSet = exercise.planned_sets.getOrNull(round) ?: return@forEachIndexed

                                val reconfigureText = reconfigureTexts[ei]
                                if (reconfigureText != null && !emittedHtForExercise.contains(ei)) {
                                    emittedHtForExercise.add(ei)
                                    itemKeys.add("ht-reconfigure-$gi-$ei")
                                    item(key = "ht-reconfigure-$gi-$ei") {
                                        Text(
                                            text = reconfigureText,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(top = 12.dp),
                                        )
                                    }
                                }

                            val isCurrent = plannedSet.id == currentPlannedSetId
                            val isPast = plannedSet.id in pastIds
                            val tapRequired = plannedSet.set_role in setOf("WORKING", "TOP", "BACKOFF")

                            val sideIndexes = if (exercise.unilateral && isPast) listOf(0, 1) else listOf(0)
                            sideIndexes.forEach { sideIndex ->
                                val itemKey = if (exercise.unilateral && isPast) {
                                    "set-${plannedSet.id}-side-$sideIndex"
                                } else {
                                    "set-${plannedSet.id}"
                                }
                                itemKeys.add(itemKey)
                                item(key = itemKey) {
                                    Column {
                                        if (sideIndex == 0) {
                                            Row {
                                                Text(
                                                    text = displayMovementName(exercise.movement_name),
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    modifier = Modifier.padding(start = 8.dp)
                                                )
                                                val hasRemaining = exercise.planned_sets.any {
                                                    it.id !in loggedSetActuals.keys.map { k -> k.first } && !it.is_skipped
                                                }
                                                if (hasRemaining) {
                                                    ExerciseActionsMenu(
                                                        onSwap = { swapSheetExerciseId = exercise.id },
                                                        onSkip = {
                                                            scope.launch { vm.skipExercise(exercise.id) }
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    val setKey = plannedSet.id to sideIndex
                                    val editable = isSetEditable(isPast, exercise.unilateral)
                                    val isEditing = editable && editingSetKey == setKey
                                    SetCard(
                                        plannedSet = plannedSet,
                                        unilateral = exercise.unilateral,
                                        unitHint = exercise.unit_hint,
                                        sideLabel = if (exercise.unilateral && isPast) "Side ${sideIndex + 1}" else null,
                                        isCurrent = isCurrent,
                                        isPast = isPast,
                                        tapRequired = tapRequired,
                                        setLoad = if (isCurrent) setLoad else "",
                                        setReps = if (isCurrent) setReps else "",
                                        selectedTap = if (isCurrent) selectedTap else null,
                                        setFeltPeak = if (isCurrent) setFeltPeak else "",
                                        onLoadChange = {
                                            setLoad = it
                                            currentExercise?.let { ex ->
                                                carriedLoadByMovement =
                                                    withCarriedLoad(carriedLoadByMovement, ex.movement_id, it.toDoubleOrNull())
                                                // TEMP diagnostic logging (spec 13 follow-up), see the
                                                // matching read-side log in the LaunchedEffect above.
                                                Log.d(
                                                    "CarryFwd",
                                                    "WRITE cursor=$currentPlannedSetId movement=${ex.movement_id} value=$it",
                                                )
                                            }
                                        },
                                        onRepsChange = {
                                            setReps = it
                                            currentExercise?.let { ex ->
                                                carriedRepsByMovement =
                                                    withCarriedReps(carriedRepsByMovement, ex.movement_id, it.toIntOrNull())
                                                Log.d(
                                                    "CarryFwd",
                                                    "REPS WRITE cursor=$currentPlannedSetId movement=${ex.movement_id} value=$it",
                                                )
                                            }
                                        },
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
                                        // Fix B — logged sets show actuals and stay editable.
                                        loggedActual = loggedSetActuals[setKey],
                                        isEditing = isEditing,
                                        editLoad = if (isEditing) editLoad else "",
                                        editReps = if (isEditing) editReps else "",
                                        editTap = if (isEditing) editTap else null,
                                        onCardTap = {
                                            if (editable) {
                                                editingSetKey = if (editingSetKey == setKey) null else setKey
                                            }
                                        },
                                        onEditLoadChange = { editLoad = it },
                                        onEditRepsChange = { editReps = it },
                                        onEditTapSelect = { editTap = it },
                                        onSaveEdit = {
                                            scope.launch {
                                                vm.editLoggedSet(
                                                    plannedSetId = plannedSet.id,
                                                    sideIndex = sideIndex,
                                                    movementId = exercise.movement_id,
                                                    setIndex = plannedSet.set_index,
                                                    setRole = plannedSet.set_role,
                                                    actualLoad = editLoad.toDoubleOrNull(),
                                                    actualReps = editReps.toIntOrNull(),
                                                    tap = editTap,
                                                    isWarmup = plannedSet.is_warmup,
                                                )
                                                editingSetKey = null
                                            }
                                        },
                                    )
                                    }
                                }
                            }
                        }
                    }
                    } else {
                        group.exercises.forEachIndexed { ei, exercise ->
                            val reconfigureText = reconfigureTexts[ei]
                            if (reconfigureText != null) {
                                itemKeys.add("ht-reconfigure-$gi-$ei")
                                item(key = "ht-reconfigure-$gi-$ei") {
                                    Text(
                                        text = reconfigureText,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 12.dp),
                                    )
                                }
                            }

                            itemKeys.add("ex-$gi-$ei")
                            item(key = "ex-$gi-$ei") {
                                Row {
                                    Text(
                                        text = displayMovementName(exercise.movement_name),
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                    val hasRemaining = exercise.planned_sets.any {
                                        it.id !in loggedSetActuals.keys.map { k -> k.first } && !it.is_skipped
                                    }
                                    if (hasRemaining) {
                                        ExerciseActionsMenu(
                                            onSwap = { swapSheetExerciseId = exercise.id },
                                            onSkip = {
                                                scope.launch { vm.skipExercise(exercise.id) }
                                            },
                                        )
                                    }
                                }
                            }

                            exercise.planned_sets.forEach { plannedSet ->
                            val isCurrent = plannedSet.id == currentPlannedSetId
                            val isPast = plannedSet.id in pastIds
                            val tapRequired = plannedSet.set_role in setOf("WORKING", "TOP", "BACKOFF")

                            val sideIndexes = if (exercise.unilateral && isPast) listOf(0, 1) else listOf(0)
                            sideIndexes.forEach { sideIndex ->
                                val itemKey = if (exercise.unilateral && isPast) {
                                    "set-${plannedSet.id}-side-$sideIndex"
                                } else {
                                    "set-${plannedSet.id}"
                                }
                                itemKeys.add(itemKey)
                                item(key = itemKey) {
                                    val setKey = plannedSet.id to sideIndex
                                    val editable = isSetEditable(isPast, exercise.unilateral)
                                    val isEditing = editable && editingSetKey == setKey
                                    SetCard(
                                        plannedSet = plannedSet,
                                        unilateral = exercise.unilateral,
                                        unitHint = exercise.unit_hint,
                                        sideLabel = if (exercise.unilateral && isPast) "Side ${sideIndex + 1}" else null,
                                        isCurrent = isCurrent,
                                        isPast = isPast,
                                        tapRequired = tapRequired,
                                        setLoad = if (isCurrent) setLoad else "",
                                        setReps = if (isCurrent) setReps else "",
                                        selectedTap = if (isCurrent) selectedTap else null,
                                        setFeltPeak = if (isCurrent) setFeltPeak else "",
                                        onLoadChange = {
                                            setLoad = it
                                            currentExercise?.let { ex ->
                                                carriedLoadByMovement =
                                                    withCarriedLoad(carriedLoadByMovement, ex.movement_id, it.toDoubleOrNull())
                                                // TEMP diagnostic logging (spec 13 follow-up), see the
                                                // matching read-side log in the LaunchedEffect above.
                                                Log.d(
                                                    "CarryFwd",
                                                    "WRITE cursor=$currentPlannedSetId movement=${ex.movement_id} value=$it",
                                                )
                                            }
                                        },
                                        onRepsChange = {
                                            setReps = it
                                            currentExercise?.let { ex ->
                                                carriedRepsByMovement =
                                                    withCarriedReps(carriedRepsByMovement, ex.movement_id, it.toIntOrNull())
                                                Log.d(
                                                    "CarryFwd",
                                                    "REPS WRITE cursor=$currentPlannedSetId movement=${ex.movement_id} value=$it",
                                                )
                                            }
                                        },
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
                                        // Fix B — logged sets show actuals and stay editable.
                                        loggedActual = loggedSetActuals[setKey],
                                        isEditing = isEditing,
                                        editLoad = if (isEditing) editLoad else "",
                                        editReps = if (isEditing) editReps else "",
                                        editTap = if (isEditing) editTap else null,
                                        onCardTap = {
                                            if (editable) {
                                                editingSetKey = if (editingSetKey == setKey) null else setKey
                                            }
                                        },
                                        onEditLoadChange = { editLoad = it },
                                        onEditRepsChange = { editReps = it },
                                        onEditTapSelect = { editTap = it },
                                        onSaveEdit = {
                                            scope.launch {
                                                vm.editLoggedSet(
                                                    plannedSetId = plannedSet.id,
                                                    sideIndex = sideIndex,
                                                    movementId = exercise.movement_id,
                                                    setIndex = plannedSet.set_index,
                                                    setRole = plannedSet.set_role,
                                                    actualLoad = editLoad.toDoubleOrNull(),
                                                    actualReps = editReps.toIntOrNull(),
                                                    tap = editTap,
                                                    isWarmup = plannedSet.is_warmup,
                                                )
                                                editingSetKey = null
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                        }
                    }
                }
            session.finisher?.let { finisher ->
                itemKeys.add("finisher")
                item(key = "finisher") {
                    FinisherSection(
                        finisher = finisher,
                        active = activeIntervalKey == FINISHER_INTERVAL_KEY,
                        intervalStatus = intervalStatus,
                        onStartInterval = { mode ->
                            activeIntervalKey = FINISHER_INTERVAL_KEY
                            when (mode) {
                                is FinisherTimerMode.RepBased -> {
                                    intervalTimerController.startRepBasedIntervals(
                                        mode.totalMinutes,
                                        mode.label,
                                        leadInSeconds = 5,
                                    )
                                }
                                is FinisherTimerMode.TimeBased -> {
                                    intervalTimerController.startTimeBasedIntervals(
                                        mode.totalMinutes,
                                        mode.workSeconds,
                                        mode.restSeconds,
                                        mode.label,
                                        leadInSeconds = 5,
                                    )
                                }
                                is FinisherTimerMode.Emom -> {
                                    intervalTimerController.startEmomIntervals(
                                        mode.totalMinutes,
                                        mode.repsPerMinute,
                                        mode.label,
                                        leadInSeconds = 5,
                                    )
                                }
                                is FinisherTimerMode.Tabata -> {
                                    intervalTimerController.startTabataIntervals(
                                        mode.workSeconds,
                                        mode.restSeconds,
                                        mode.roundsPerBlock,
                                        mode.blocks,
                                        mode.interBlockRestSeconds,
                                        mode.label,
                                        leadInSeconds = 5,
                                    )
                                }
                                FinisherTimerMode.None -> Unit
                            }
                        },
                        onStopInterval = {
                            intervalTimerController.stop()
                            activeIntervalKey = null
                        },
                        onLogFinisher = { _, _ ->
                            // NEEDS_INPUT: FinisherOut carries no movement_id — the server's
                            // build_finisher_payload (assembler.py) only returns exercise_name,
                            // never the raw movement FK, so there is no valid id to send in
                            // FinisherLogRequest. Wire this to captureRepo.logFinisher(...) once
                            // the server payload exposes movement_id (see spec 30 §2); until then
                            // the Log button in FinisherSection is a stubbed no-op.
                        },
                    )
                }
            }

            // UI error (tap required, etc.)
            uiError?.let { msg ->
                itemKeys.add("error")
                item(key = "error") {
                    Text(
                        text = msg,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }

            // Finish / submit result
            itemKeys.add("finish")
            item(key = "finish") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    submitResult?.let { result ->
                        val color = if (result == "COMPLETED") MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                        Text("Session $result", color = color)
                    }
                    OutlinedTextField(
                        value = sessionNote,
                        onValueChange = { sessionNote = it },
                        label = { Text("Session note (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { scope.launch { vm.finish(sessionNote.ifBlank { null }) } },
                        enabled = submitResult != "COMPLETED",
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (submitResult == "COMPLETED") "Submitted" else "Finish & Submit")
                    }
                }
            }
        }
    }

    // Swap sheet, shown when an ExerciseActionsMenu's "Swap exercise" action set
    // swapSheetExerciseId. Suggested substitutes are fetched fresh for the target exercise's
    // current movement each time the sheet opens; fullLibrary (passed in from CaptureScreen,
    // reusing the Movements-tab repo call) backs the search field.
    swapSheetExerciseId?.let { exId ->
        val targetExercise = session.groups.flatMap { it.exercises }.find { it.id == exId }
        if (targetExercise != null) {
            var substitutes by remember(exId) { mutableStateOf<List<MovementSummary>>(emptyList()) }
            LaunchedEffect(exId) {
                substitutes = vm.loadSubstitutes(targetExercise.movement_id)
            }
            SwapExerciseSheet(
                substitutes = substitutes,
                fullLibrary = fullLibrary,
                onConfirm = { movementId, makePermanent ->
                    scope.launch {
                        vm.swapExercise(exId, movementId, makePermanent)
                        swapSheetExerciseId = null
                    }
                },
                onDismiss = { swapSheetExerciseId = null },
            )
        }
    }
}

@Composable
private fun WarmupSection(
    warmup: WarmupOut,
    activeIntervalKey: String?,
    intervalStatus: InlineIntervalStatus?,
    onStartJumpRope: (String, Int) -> Unit,
    onStopInterval: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = "Warmup", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "Movement flow (~${warmup.movement_flow_seconds}s)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        warmup.items.forEachIndexed { index, item ->
            WarmupItemRow(
                item = item,
                intervalKey = "warmup-flow-$index",
                activeIntervalKey = activeIntervalKey,
                intervalStatus = intervalStatus,
                onStartJumpRope = onStartJumpRope,
                onStopInterval = onStopInterval,
            )
        }
        Text(
            text = "Activation (~${warmup.activation_seconds}s)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        warmup.items_activation.forEachIndexed { index, item ->
            WarmupItemRow(
                item = item,
                intervalKey = "warmup-activation-$index",
                activeIntervalKey = activeIntervalKey,
                intervalStatus = intervalStatus,
                onStartJumpRope = onStartJumpRope,
                onStopInterval = onStopInterval,
            )
        }
    }
}

@Composable
private fun WarmupItemRow(
    item: JsonObject,
    intervalKey: String,
    activeIntervalKey: String?,
    intervalStatus: InlineIntervalStatus?,
    onStartJumpRope: (String, Int) -> Unit,
    onStopInterval: () -> Unit,
) {
    val seconds = warmupJumpRopeSeconds(item)
    val active = activeIntervalKey == intervalKey
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = warmupItemLine(item),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (seconds != null && !active) {
                TextButton(onClick = { onStartJumpRope(intervalKey, seconds) }) {
                    Text("Start")
                }
            }
        }
        if (active && intervalStatus != null) {
            InlineIntervalStatusBar(
                status = intervalStatus,
                onStop = onStopInterval,
            )
        }
    }
}

@Composable
private fun FinisherSection(
    finisher: FinisherOut,
    active: Boolean,
    intervalStatus: InlineIntervalStatus?,
    onStartInterval: (FinisherTimerMode) -> Unit,
    onStopInterval: () -> Unit,
    onLogFinisher: (Double?, Int?) -> Unit,
) {
    val timerMode = finisherTimerMode(finisher)
    val metadataLine = listOfNotNull(
        "${finisher.duration_minutes} min EMOM",
        finisher.current_duration_seconds?.let { seconds -> "${seconds}s work per minute" },
        finisher.current_rope?.let(::humanizeFinisherName),
    ).joinToString(" · ")
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Finisher", style = MaterialTheme.typography.titleSmall)
            if (timerMode != FinisherTimerMode.None && !active) {
                TextButton(onClick = { onStartInterval(timerMode) }) {
                    Text("Start")
                }
            }
        }
        Text(text = humanizeFinisherName(finisher.exercise_name), style = MaterialTheme.typography.bodyLarge)
        Text(
            text = metadataLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        finisher.params.entries
            .filterNot { (key, _) -> key in finisherCoveredParamKeys }
            .forEach { (key, value) ->
                Text(
                    text = "$key: ${finisherParamValue(value)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        finisherLoggableKind(finisher.params)?.let { kind ->
            FinisherLogRow(kind = kind, finisher = finisher, onLogFinisher = onLogFinisher)
        }
        if (active && intervalStatus != null) {
            InlineIntervalStatusBar(
                status = intervalStatus,
                onStop = onStopInterval,
            )
        }
    }
}

/** Compact inline weight/resistance input + "Log" button for [FinisherSection] — see
 * [finisherLoggableKind] for which finishers render this row. */
@Composable
private fun FinisherLogRow(
    kind: FinisherLoggableKind,
    finisher: FinisherOut,
    onLogFinisher: (Double?, Int?) -> Unit,
) {
    var value by remember(finisher.exercise_name) {
        mutableStateOf(
            when (kind) {
                FinisherLoggableKind.WEIGHT -> prefillWeight(finisher.last_logged_weight_lb)
                FinisherLoggableKind.RESISTANCE -> finisher.last_logged_resistance_level?.toString() ?: ""
            },
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text(if (kind == FinisherLoggableKind.WEIGHT) "Weight (lb)" else "Resistance") },
            keyboardOptions = KeyboardOptions(
                keyboardType = if (kind == FinisherLoggableKind.WEIGHT) KeyboardType.Decimal else KeyboardType.Number,
            ),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                when (kind) {
                    FinisherLoggableKind.WEIGHT -> onLogFinisher(value.toDoubleOrNull(), null)
                    FinisherLoggableKind.RESISTANCE -> onLogFinisher(null, value.toIntOrNull())
                }
            },
        ) {
            Text("Log")
        }
    }
}

private data class InlineIntervalStatus(
    val remainingSeconds: Int,
    val phaseLabel: String?,
)

@Composable
private fun InlineIntervalStatusBar(
    status: InlineIntervalStatus,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val phase = status.phaseLabel?.takeIf { it.isNotBlank() }
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = listOfNotNull("Interval", phase, formatRestTime(status.remainingSeconds))
                    .joinToString(" · "),
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = onStop) { Text("Stop") }
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
            .padding(top = 4.dp),
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
    unitHint: String?,
    sideLabel: String? = null,
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
    // Fix B — logged sets show actuals and stay editable.
    loggedActual: LoggedSetActual? = null,
    isEditing: Boolean = false,
    editLoad: String = "",
    editReps: String = "",
    editTap: String? = null,
    onCardTap: () -> Unit = {},
    onEditLoadChange: (String) -> Unit = {},
    onEditRepsChange: (String) -> Unit = {},
    onEditTapSelect: (String) -> Unit = {},
    onSaveEdit: () -> Unit = {},
) {
    // "Log set" button is DISABLED until a tap is selected for working roles (Gate #2 — client UI).
    val logEnabled = !tapRequired || selectedTap != null
    // "Save" (edit round-trip) has the same mandatory-tap gate as the original log.
    val saveEditEnabled = !tapRequired || editTap != null

    // HT (band-composite) working set: plates and/or bands prescribed on this planned set.
    val isHtSet = plannedSet.target_plates != null || plannedSet.band_config != null

    // Scroll-into-view for the current set is handled at the LazyColumn level (see
    // SessionContent's listState/itemKeys) — this card no longer carries its own relocation
    // modifier. A PAST card is tappable to reopen its inputs and correct a mistake; the current
    // card isn't (it's already open), and not-yet-reached cards aren't (nothing logged).
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp)
            .let { if (isSetEditable(isPast, unilateral)) it.clickable(onClick = onCardTap) else it },
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Set header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = listOfNotNull(
                        "${plannedSet.set_role} #${plannedSet.set_index + 1}",
                        sideLabel,
                    ).joinToString(" · "),
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

            // Fix B — the ACTUAL logged line (prominent) for a past set, e.g.
            // "165lb × 6 reps · ✓ on target". This is the thing to surface; the target below is
            // now secondary reference, not the primary display.
            if (isPast && loggedActual != null) {
                val actualLine = loggedActualLine(loggedActual.actualLoad, loggedActual.actualReps, loggedActual.tap, unitHint)
                if (actualLine.isNotEmpty()) {
                    Text(
                        text = "Actual: $actualLine",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // Target prescription — weight + reps (single number when fixed, range otherwise).
            // The phased pull-up AMRAP/assisted-pair widget is DEFERRED (server never populates
            // target_unassisted_reps/target_assisted_reps) — see repsTargetLabel's doc comment.
            val weightTarget = plannedSet.target_load?.let { loadDisplayLabel(it, unitHint) }
            val repsTarget = repsTargetLabel(plannedSet).takeIf { it.isNotEmpty() }
            val target = listOfNotNull(weightTarget, repsTarget).joinToString(" ")
            val rpe = rpeLabel(plannedSet.target_rpe)
            when {
                target.isNotEmpty() && rpe != null -> {
                    val rpeSpan = MaterialTheme.typography.labelLarge
                        .toSpanStyle()
                        .copy(color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = buildAnnotatedString {
                            append("Target: $target · ")
                            withStyle(rpeSpan) { append(rpe) }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                target.isNotEmpty() -> {
                    Text(
                        text = "Target: $target",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                rpe != null -> {
                    Text(
                        text = rpe,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // HT (band-composite) setup line — plates + band names + target felt peak.
            val htSetup = if (isHtSet) {
                htSetupLine(plannedSet.target_plates, plannedSet.band_config, plannedSet.target_felt_peak)
            } else {
                null
            }
            val sideHint = perSideLabel(unilateral)
            when {
                htSetup != null && sideHint != null -> {
                    val sideSpan = MaterialTheme.typography.labelSmall
                        .toSpanStyle()
                        .copy(color = MaterialTheme.colorScheme.tertiary)
                    Text(
                        text = buildAnnotatedString {
                            append(htSetup)
                            append(" · ")
                            withStyle(sideSpan) { append(sideHint) }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                htSetup != null -> {
                    Text(
                        text = htSetup,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                sideHint != null -> {
                    Text(
                        text = sideHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }

            // Input controls for the current set only
            if (isCurrent) {
                val repsLabel = repsInputLabel(plannedSet)
                // HT (band-composite) sets are prescribed as plates+bands (see htSetupLine
                // above) and logged via Felt peak, not a scalar Load — showing the Load field
                // here (pre-filled from what used to be target_load) was confusing since it
                // isn't the athlete's real input for these sets. Reps stays for every set type.
                if (isHtSet) {
                    OutlinedTextField(
                        value = setReps,
                        onValueChange = onRepsChange,
                        label = { Text(repsLabel) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = setLoad,
                            onValueChange = onLoadChange,
                            label = { Text(loadInputLabel(unitHint)) },
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

            // Fix B — reopened inputs for a PAST set the athlete tapped to correct. Mirrors the
            // current-set input block above but writes back via onSaveEdit (editLoggedSet),
            // which updates the existing row in place rather than advancing the cursor.
            if (isEditing) {
                val editRepsLabel = repsInputLabel(plannedSet)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editLoad,
                        onValueChange = onEditLoadChange,
                        label = { Text(loadInputLabel(unitHint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = editReps,
                        onValueChange = onEditRepsChange,
                        label = { Text(editRepsLabel) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                val taps = listOf("TOO_EASY", "ON_TARGET", "TOO_HARD")
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    taps.forEachIndexed { i, tap ->
                        SegmentedButton(
                            selected = editTap == tap,
                            onClick = { onEditTapSelect(tap) },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = taps.size),
                        ) {
                            Text(
                                text = tap.replace('_', ' '),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }

                Button(
                    onClick = onSaveEdit,
                    enabled = saveEditEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save correction")
                }
            }
        }
    }
}

// ── Pure display/pre-fill logic (extracted so Compose UI, which can't be unit-tested here, ────
//    stays a thin wrapper around testable functions) ────────────────────────────────────────

private const val FINISHER_INTERVAL_KEY = "finisher"
private const val PARAM_TARGET_REPS_PER_MINUTE = "target_reps_per_minute"
private const val PARAM_WORK_SECONDS_PER_MINUTE = "work_seconds_per_minute"
private const val PARAM_REST_SECONDS_PER_MINUTE = "rest_seconds_per_minute"
private const val PARAM_SCHEME = "scheme"
private const val PARAM_WORK_SECONDS = "work_seconds"
private const val PARAM_REST_SECONDS = "rest_seconds"
private const val PARAM_ROUNDS_PER_BLOCK = "rounds_per_block"
private const val PARAM_BLOCKS = "blocks"
private const val PARAM_INTER_BLOCK_REST_SECONDS = "inter_block_rest_seconds"
private const val PARAM_WEIGHT_LB = "weight_lb"
private const val PARAM_RESISTANCE_LEVEL = "resistance_level"
private const val SCHEME_EMOM = "emom"
private const val SCHEME_TABATA = "tabata"

private val finisherCoveredParamKeys = setOf("current_duration_seconds", "current_rope")
private val warmupMetricKeys = listOf("reps", "reps_per_side", "seconds", "seconds_per_side", "hold_seconds")

internal sealed interface FinisherTimerMode {
    data class RepBased(val totalMinutes: Int, val label: String) : FinisherTimerMode
    data class TimeBased(
        val totalMinutes: Int,
        val workSeconds: Int,
        val restSeconds: Int,
        val label: String,
    ) : FinisherTimerMode
    data class Emom(val totalMinutes: Int, val repsPerMinute: Int, val label: String) : FinisherTimerMode
    data class Tabata(
        val workSeconds: Int,
        val restSeconds: Int,
        val roundsPerBlock: Int,
        val blocks: Int,
        val interBlockRestSeconds: Int,
        val label: String,
    ) : FinisherTimerMode
    data object None : FinisherTimerMode
}

/** Which numeric field (if any) in [params] this finisher can log via [FinisherLogRow] —
 * `weight_lb` wins if both happen to be present, mirroring [finisherTimerMode]'s "first match
 * wins" style. Null when neither field is present (nothing to log). */
internal enum class FinisherLoggableKind { WEIGHT, RESISTANCE }

internal fun finisherLoggableKind(params: JsonObject): FinisherLoggableKind? = when {
    params.doubleParam(PARAM_WEIGHT_LB) != null -> FinisherLoggableKind.WEIGHT
    params.intParam(PARAM_RESISTANCE_LEVEL) != null -> FinisherLoggableKind.RESISTANCE
    else -> null
}

internal fun humanizeFinisherName(name: String): String =
    name.replace("_", " ")
        .split(" ")
        .filter { it.isNotBlank() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

private fun finisherParamValue(value: JsonElement): String = (value as? JsonPrimitive)?.content ?: value.toString()

private fun jsonIntValue(value: JsonElement?): Int? = (value as? JsonPrimitive)?.content?.toIntOrNull()
private fun jsonDoubleValue(value: JsonElement?): Double? = (value as? JsonPrimitive)?.content?.toDoubleOrNull()

private fun JsonObject.intParam(key: String): Int? = jsonIntValue(this[key])
private fun JsonObject.doubleParam(key: String): Double? = jsonDoubleValue(this[key])

/**
 * Routes a finisher to its timer mode. `scheme` (new, server-provided) is checked FIRST — after
 * this session's server-side deploy all 5 live finishers carry a real, correct `scheme` — falling
 * back to the old presence-based heuristic (repsPerMinute -> RepBased, workSeconds -> TimeBased)
 * only defensively, for a finisher whose params predate the `scheme` field. Never throws on
 * malformed/missing params — a `scheme` value whose required fields are missing or non-numeric
 * simply falls through to the next case instead of crashing.
 */
internal fun finisherTimerMode(finisher: FinisherOut): FinisherTimerMode {
    if (finisher.duration_minutes <= 0) return FinisherTimerMode.None
    val label = humanizeFinisherName(finisher.exercise_name)
    val params = finisher.params

    when ((params[PARAM_SCHEME] as? JsonPrimitive)?.content) {
        SCHEME_TABATA -> {
            val workSeconds = params.intParam(PARAM_WORK_SECONDS)
            val restSeconds = params.intParam(PARAM_REST_SECONDS)
            val roundsPerBlock = params.intParam(PARAM_ROUNDS_PER_BLOCK)
            val blocks = params.intParam(PARAM_BLOCKS)
            val interBlockRestSeconds = params.intParam(PARAM_INTER_BLOCK_REST_SECONDS)
            if (workSeconds != null && restSeconds != null && roundsPerBlock != null &&
                blocks != null && interBlockRestSeconds != null
            ) {
                return FinisherTimerMode.Tabata(
                    workSeconds = workSeconds,
                    restSeconds = restSeconds,
                    roundsPerBlock = roundsPerBlock,
                    blocks = blocks,
                    interBlockRestSeconds = interBlockRestSeconds,
                    label = label,
                )
            }
        }
        SCHEME_EMOM -> {
            val repsPerMinute = params.intParam(PARAM_TARGET_REPS_PER_MINUTE)
            if (repsPerMinute != null) {
                return FinisherTimerMode.Emom(
                    totalMinutes = finisher.duration_minutes,
                    repsPerMinute = repsPerMinute,
                    label = label,
                )
            }
        }
    }

    val repsPerMinute = params.intParam(PARAM_TARGET_REPS_PER_MINUTE)
    val workSeconds = finisher.current_duration_seconds ?: params.intParam(PARAM_WORK_SECONDS_PER_MINUTE)
    return when {
        // If both timer params somehow arrive, rep-based mode deliberately wins.
        repsPerMinute != null -> FinisherTimerMode.RepBased(
            totalMinutes = finisher.duration_minutes,
            label = label,
        )
        workSeconds != null -> {
            // Old client-side fallback derivation, kept ONLY for finishers whose params
            // genuinely lack a real rest field — the service itself must never do this
            // derivation anymore (see IntervalTimerSequence's TimeBased tick logic).
            val restSeconds = params.intParam(PARAM_REST_SECONDS_PER_MINUTE)
                ?: (60 - clampedIntervalWorkSeconds(workSeconds))
            FinisherTimerMode.TimeBased(
                totalMinutes = finisher.duration_minutes,
                workSeconds = workSeconds,
                restSeconds = restSeconds,
                label = label,
            )
        }
        else -> FinisherTimerMode.None
    }
}

internal fun warmupJumpRopeSeconds(item: JsonObject): Int? {
    val name = item["name"]?.let(::finisherParamValue)?.let(::humanizeFinisherName) ?: return null
    if (!name.contains("Jump Rope", ignoreCase = true)) return null
    return item.intParam("seconds")?.takeIf { it > 0 }
}

private fun warmupItemLine(item: JsonObject): String {
    val name = item["name"]?.let(::finisherParamValue)?.let(::humanizeFinisherName) ?: "Warmup drill"
    val usedKeys = mutableSetOf("name")
    val parts = mutableListOf<String>()
    val sets = item["sets"]?.let(::finisherParamValue)
    val primaryMetricKey = warmupMetricKeys.firstOrNull { item[it] != null }

    if (primaryMetricKey != null) {
        val metric = finisherParamValue(item.getValue(primaryMetricKey))
        val withSets = sets != null
        parts += if (withSets) {
            "$sets×${formatWarmupMetric(primaryMetricKey, metric, withSets = true)}"
        } else {
            formatWarmupMetric(primaryMetricKey, metric, withSets = false)
        }
        usedKeys += primaryMetricKey
        if (withSets) usedKeys += "sets"
    } else if (sets != null) {
        parts += "$sets sets"
        usedKeys += "sets"
    }

    warmupMetricKeys
        .filter { key -> key != primaryMetricKey && item[key] != null }
        .forEach { key ->
            parts += formatWarmupMetric(key, finisherParamValue(item.getValue(key)), withSets = false)
            usedKeys += key
        }

    item.entries
        .filterNot { (key, _) -> key in usedKeys }
        .forEach { (key, value) -> parts += "$key: ${finisherParamValue(value)}" }

    return parts
        .takeIf { it.isNotEmpty() }
        ?.joinToString(separator = " · ", prefix = "$name — ")
        ?: name
}

private fun formatWarmupMetric(key: String, value: String, withSets: Boolean): String = when (key) {
    "reps" -> if (withSets) value else "$value reps"
    "reps_per_side" -> if (withSets) "$value/side" else "$value reps/side"
    "seconds" -> "${value}s"
    "seconds_per_side" -> "${value}s/side"
    "hold_seconds" -> if (withSets) "${value}s hold" else "hold ${value}s"
    else -> value
}

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

/** Scalar load/assist target and actual display. Unknown/null unit hints remain pounds.
 *
 * 2026-08-21: the server classifies assisted movements into specific hints
 * (assist_degrees/assist_bands/assist_lb/assist_reps, see app.py's
 * _unit_hint_for) instead of the old bare "assist" string -- this client was
 * never updated to recognize them, so every classified assist movement fell
 * through to the "lb" else-branch (real bug report: "leg raise still has
 * weight instead of degrees"). UNIT_ASSIST ("assist") is kept as the legacy
 * fallback for movements the server hasn't classified yet -- server comment
 * confirms it intentionally preserves that old default, mapped to degrees. */
internal fun loadDisplayLabel(value: Double, unitHint: String?): String {
    val suffix = when (unitHint) {
        UNIT_ASSIST_DEGREES, UNIT_ASSIST -> "°"
        UNIT_ASSIST_BANDS -> " bands"
        UNIT_ASSIST_REPS -> " reps"
        else -> "lb"
    }
    return "${formatWeight(value)}$suffix"
}

/** Scalar load/assist input label. Unknown/null unit hints remain pounds. */
internal fun loadInputLabel(unitHint: String?): String = when (unitHint) {
    UNIT_ASSIST_DEGREES, UNIT_ASSIST -> "Assist (°)"
    UNIT_ASSIST_BANDS -> "Assist (bands)"
    UNIT_ASSIST_LB -> "Assist (lb)"
    UNIT_ASSIST_REPS -> "Assist (reps)"
    else -> "Load (lb)"
}

/** Weight input pre-fill — [targetLoad] as an editable default; blank when null
 * (needs-calibration: no floor to prefill). */
internal fun prefillWeight(targetLoad: Double?): String = targetLoad?.let(::formatWeight) ?: ""

/** `"RPE 8"` / `"RPE 7.5"` prominent label, or null when the set carries no RPE target. */
internal fun rpeLabel(rpe: Double?): String? = rpe?.let { "RPE ${formatWeight(it)}" }

/** `"Per side"` affordance for a unilateral exercise's set, or null for bilateral exercises. */
internal fun perSideLabel(unilateral: Boolean): String? = if (unilateral) "Per side" else null

/**
 * Whether a LOGGED card may be tapped to reopen its inputs for correction. True for any past set;
 * unilateral past sets are rendered as separate side cards, so the caller can save against the
 * correct side row.
 */
internal fun isSetEditable(isPast: Boolean, unilateral: Boolean): Boolean = isPast

/** Short result label for a logged set's tap, or null when no tap was recorded (e.g. warmup). */
internal fun tapResultLabel(tap: String?): String? = when (tap) {
    "TOO_EASY" -> "↓ easy"
    "ON_TARGET" -> "✓ on target"
    "TOO_HARD" -> "↑ hard"
    else -> null
}

/**
 * `"165lb × 6 reps · ✓ on target"` — the ACTUAL logged line for a set card (fix B). Previously a
 * logged set collapsed to showing only its target, hiding what was actually entered; this is the
 * line that replaces/precedes the target once a set is past. Missing pieces (no load, no reps, no
 * tap — e.g. a warmup) are simply omitted, never rendered as `"null"`.
 */
internal fun loggedActualLine(actualLoad: Double?, actualReps: Int?, tap: String?, unitHint: String? = null): String {
    val loadPart = actualLoad?.let { loadDisplayLabel(it, unitHint) }
    val repsPart = actualReps?.let { "$it reps" }
    val loadReps = listOfNotNull(loadPart, repsPart).joinToString(" × ")
    return listOfNotNull(loadReps.takeIf { it.isNotEmpty() }, tapResultLabel(tap)).joinToString(" · ")
}

/**
 * Effective load pre-fill for an UNLOGGED set belonging to [movementId] (fix F): the
 * carried-forward load entered on an earlier set of the SAME exercise this session (see
 * [withCarriedLoad]) if one exists and the exercise plan is flat for load, else the set's own
 * prescribed [targetLoad]. Never applied to already-logged sets — those show their real actual
 * via [loggedActualLine] instead.
 */
internal fun effectiveLoadPrefill(
    carriedLoad: Map<Int, Double>,
    movementId: Int,
    targetLoad: Double?,
    planIsFlat: Boolean,
): String =
    prefillWeight(if (planIsFlat) carriedLoad[movementId] ?: targetLoad else targetLoad)

/**
 * True iff every element is null-or-equal to the others -- i.e. the exercise's plan is
 * uniform for this field, so carry-forward is safe to apply without overriding a
 * deliberately different per-set value. An empty or single-element list is trivially flat.
 */
internal fun isFlatAcrossSets(values: List<Double?>): Boolean =
    values.filterNotNull().distinct().size <= 1

/**
 * Reps flatness is checked on the planned (low, high) pair. Pairs with no target at all are
 * ignored so an unprescribed set does not break carry-forward for matching prescribed sets.
 */
internal fun isFlatAcrossRepTargets(values: List<Pair<Int?, Int?>>): Boolean =
    values.filter { (low, high) -> low != null || high != null }.distinct().size <= 1

/**
 * Effective reps pre-fill for an UNLOGGED set belonging to [movementId]: the carried-forward
 * reps entered on an earlier set of the SAME exercise this session if one exists and the
 * exercise plan is flat for reps, else the set's own prescribed reps target.
 */
internal fun effectiveRepsPrefill(
    carriedReps: Map<Int, Int>,
    movementId: Int,
    plannedSet: PlannedSetOut?,
    planIsFlat: Boolean,
): String =
    if (planIsFlat) carriedReps[movementId]?.toString() ?: plannedSet?.let(::prefillReps) ?: ""
    else plannedSet?.let(::prefillReps) ?: ""

/** (movementId, set_index) for a planned set, used by [reconstructCarriedLoad]/[reconstructCarriedReps]
 * to resolve which movement a persisted [LoggedSetActual] belongs to and how "recent" it is. */
private data class CarryLookupEntry(val movementId: Int, val setIndex: Int)

private fun carryLookup(session: SessionDetailResponse): Map<Int, CarryLookupEntry> =
    session.groups.flatMap { it.exercises }
        .flatMap { ex -> ex.planned_sets.map { ps -> ps.id to CarryLookupEntry(ex.movement_id, ps.set_index) } }
        .toMap()

/**
 * Rebuild the [carriedLoadByMovement]-shaped carry-forward map from PERSISTED data after an app
 * relaunch (process-death-during-backgrounding fix). Fix F's carry-forward map is normally built
 * live, one keystroke at a time, via [withCarriedLoad] as the athlete edits the CURRENT cursor
 * set's input (see the `onLoadChange` call sites) — it is never itself persisted. If the process
 * is killed mid-session, [CaptureViewModel.load] correctly resumes the CURSOR from Room (see its
 * `resumeSet` logic), but the carry map came back empty because nothing reconstructed it —
 * confirmed via `adb logcat -s CarryFwd:D`: identical cursor position, `carried=3.0` before a
 * background kill and `carried=null` after relaunch.
 *
 * This derives the same value the live map would hold, from [loggedSetActuals] ([load]'s own
 * reconstruction of Room's persisted actuals): for each movement, the actual load of its
 * highest-`set_index` LOGGED set. `set_index` (not Room insertion/draftId order) is the correct
 * "most recent" proxy — only edits to the CURRENT cursor set ever feed the live carry map
 * ([editLoggedSet] correcting an EARLIER past set does not call [withCarriedLoad]), so the live
 * map's recency always tracks cursor/set_index order, and a past-set correction bumping that
 * row's Room `draftId` must NOT be mistaken for a more-recent carry-forward write.
 */
internal fun reconstructCarriedLoad(
    session: SessionDetailResponse,
    loggedSetActuals: Map<Pair<Int, Int>, LoggedSetActual>,
): Map<Int, Double> {
    val lookup = carryLookup(session)
    return loggedSetActuals.entries
        .mapNotNull { (key, actual) ->
            val load = actual.actualLoad ?: return@mapNotNull null
            val info = lookup[key.first] ?: return@mapNotNull null
            Triple(info.movementId, info.setIndex, load)
        }
        .groupBy { it.first }
        .mapValues { (_, entries) -> entries.maxBy { it.second }.third }
}

/**
 * Reps counterpart of [reconstructCarriedLoad] — same reasoning, keyed on [LoggedSetActual.actualReps]
 * instead of `actualLoad`.
 */
internal fun reconstructCarriedReps(
    session: SessionDetailResponse,
    loggedSetActuals: Map<Pair<Int, Int>, LoggedSetActual>,
): Map<Int, Int> {
    val lookup = carryLookup(session)
    return loggedSetActuals.entries
        .mapNotNull { (key, actual) ->
            val reps = actual.actualReps ?: return@mapNotNull null
            val info = lookup[key.first] ?: return@mapNotNull null
            Triple(info.movementId, info.setIndex, reps)
        }
        .groupBy { it.first }
        .mapValues { (_, entries) -> entries.maxBy { it.second }.third }
}

/**
 * Records [newLoad] as the carried-forward default for [movementId] (fix F) — later unlogged
 * sets of the SAME exercise pre-fill to this value instead of reverting to their own prescribed
 * target. This is the direct fix for the Day-1 mis-log where set 3 reverted to the prescribed
 * 170 after sets 1-2 were bumped to 175: each set's input pre-filled only from its own static
 * `target_load`, with no memory of what the lifter had already entered for this exercise.
 * A no-op (returns [carriedLoad] unchanged) when [newLoad] is null — clearing the input field
 * shouldn't blank out the default for sets not yet reached.
 */
internal fun withCarriedLoad(carriedLoad: Map<Int, Double>, movementId: Int, newLoad: Double?): Map<Int, Double> =
    if (newLoad == null) carriedLoad else carriedLoad + (movementId to newLoad)

/**
 * Records [newReps] as the carried-forward default for [movementId]. A null parse is a no-op,
 * matching [withCarriedLoad]'s clearing behavior.
 */
internal fun withCarriedReps(carriedReps: Map<Int, Int>, movementId: Int, newReps: Int?): Map<Int, Int> =
    if (newReps == null) carriedReps else carriedReps + (movementId to newReps)

/**
 * Shoe-swap cue decision for a group boundary: the shoe to swap TO (rendered as a
 * `"👟 Swap to X"` banner at that group) when [thisShoe] is non-null and differs from
 * [prevShoe] — the previous group's shoe. Null (no banner) when the shoe is unchanged or when
 * this group carries no shoe assignment at all. Pure display — never touches engine/state.
 */
internal fun shoeTransition(prevShoe: String?, thisShoe: String?): String? =
    if (thisShoe != null && thisShoe != prevShoe) thisShoe else null
