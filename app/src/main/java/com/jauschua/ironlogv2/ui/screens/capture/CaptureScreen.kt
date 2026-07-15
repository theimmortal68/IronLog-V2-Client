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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jauschua.ironlogv2.data.api.dto.FinisherOut
import com.jauschua.ironlogv2.data.api.dto.GroupOut
import com.jauschua.ironlogv2.data.api.dto.PlannedSetOut
import com.jauschua.ironlogv2.data.api.dto.SessionDetailResponse
import com.jauschua.ironlogv2.data.api.dto.WarmupOut
import com.jauschua.ironlogv2.service.IntervalTimerController
import com.jauschua.ironlogv2.ui.ErrorRetryBox
import com.jauschua.ironlogv2.ui.UiState
import com.jauschua.ironlogv2.ui.screens.review.displayMovementName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The DTO's unit_hint marker for assisted/incline movements (assist value, not a weight). */
private const val UNIT_ASSIST = "assist"

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
    val intervalRemainingSeconds by vm.intervalRemainingSeconds.collectAsStateWithLifecycle()
    val intervalPhaseLabel by vm.intervalPhaseLabel.collectAsStateWithLifecycle()
    val pendingReview by vm.pendingReview.collectAsStateWithLifecycle()
    val loggedSetActuals by vm.loggedSetActuals.collectAsStateWithLifecycle()
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
                            restRemainingSeconds, intervalRemainingSeconds, intervalPhaseLabel,
                            loggedSetActuals, scope, vm, vm.intervalTimerController,
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

    var activeIntervalKey by remember(session.id) { mutableStateOf<String?>(null) }
    val intervalStatus = intervalRemainingSeconds?.let { remaining ->
        InlineIntervalStatus(remainingSeconds = remaining, phaseLabel = intervalPhaseLabel)
    }
    LaunchedEffect(intervalRemainingSeconds) {
        if (intervalRemainingSeconds == null) activeIntervalKey = null
    }

    // Fix F — weight carries forward: last load entered per movement_id this session. When a
    // set's load is entered/changed, later UNLOGGED sets of the SAME exercise pre-fill to it
    // instead of their own static target_load (see effectiveLoadPrefill/withCarriedLoad). Scoped
    // to the session (not the cursor) so it survives the cursor advancing across sets.
    var carriedLoadByMovement by remember(session.id) { mutableStateOf<Map<Int, Double>>(emptyMap()) }

    // Input state for the current set; auto-resets (and re-pre-fills) when the cursor advances.
    // Weight defaults to the carried-forward load for this exercise if one was entered on an
    // earlier set, else the prescription target (fix F) — the lifter can accept or adjust before
    // logging ("log = accept or adjust").
    var setLoad by remember(currentPlannedSetId) {
        mutableStateOf(effectiveLoadPrefill(carriedLoadByMovement, currentExercise?.movement_id ?: -1, currentSet?.target_load))
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
    var setReps by remember(currentPlannedSetId) { mutableStateOf(currentSet?.let(::prefillReps) ?: "") }
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
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
                            intervalTimerController.startCountdown(seconds, "Jump Rope")
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
                            Text(
                                text = displayMovementName(exercise.movement_name),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 8.dp),
                            )
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
                                    intervalTimerController.startRepBasedIntervals(mode.totalMinutes, mode.label)
                                }
                                is FinisherTimerMode.TimeBased -> {
                                    intervalTimerController.startTimeBasedIntervals(
                                        mode.totalMinutes,
                                        mode.workSeconds,
                                        mode.label,
                                    )
                                }
                                FinisherTimerMode.None -> Unit
                            }
                        },
                        onStopInterval = {
                            intervalTimerController.stop()
                            activeIntervalKey = null
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
            modifier = Modifier.padding(top = 4.dp),
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
) {
    val timerMode = finisherTimerMode(finisher)
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
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
            text = "${finisher.duration_minutes} min EMOM",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        finisher.current_duration_seconds?.let { seconds ->
            Text(
                text = "${seconds}s work per minute",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        finisher.current_rope?.let { rope ->
            Text(
                text = humanizeFinisherName(rope),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        finisher.params.entries
            .filterNot { (key, _) -> key in finisherCoveredParamKeys }
            .forEach { (key, value) ->
                Text(
                    text = "$key: ${finisherParamValue(value)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        if (active && intervalStatus != null) {
            InlineIntervalStatusBar(
                status = intervalStatus,
                onStop = onStopInterval,
            )
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
            modifier = Modifier.fillMaxWidth().padding(12.dp),
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
            .padding(start = 16.dp)
            .let { if (isSetEditable(isPast, unilateral)) it.clickable(onClick = onCardTap) else it },
    ) {
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

private val finisherCoveredParamKeys = setOf("current_duration_seconds", "current_rope")
private val warmupMetricKeys = listOf("reps", "reps_per_side", "seconds", "seconds_per_side", "hold_seconds")

internal sealed interface FinisherTimerMode {
    data class RepBased(val totalMinutes: Int, val label: String) : FinisherTimerMode
    data class TimeBased(val totalMinutes: Int, val workSeconds: Int, val label: String) : FinisherTimerMode
    data object None : FinisherTimerMode
}

internal fun humanizeFinisherName(name: String): String =
    name.replace("_", " ")
        .split(" ")
        .filter { it.isNotBlank() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

private fun finisherParamValue(value: JsonElement): String = (value as? JsonPrimitive)?.content ?: value.toString()

private fun jsonIntValue(value: JsonElement?): Int? = (value as? JsonPrimitive)?.content?.toIntOrNull()

private fun JsonObject.intParam(key: String): Int? = jsonIntValue(this[key])

internal fun finisherTimerMode(finisher: FinisherOut): FinisherTimerMode {
    if (finisher.duration_minutes <= 0) return FinisherTimerMode.None
    val label = humanizeFinisherName(finisher.exercise_name)
    val repsPerMinute = finisher.params.intParam(PARAM_TARGET_REPS_PER_MINUTE)
    val workSeconds = finisher.params.intParam(PARAM_WORK_SECONDS_PER_MINUTE)
    return when {
        repsPerMinute != null -> FinisherTimerMode.RepBased(
            totalMinutes = finisher.duration_minutes,
            label = label,
        )
        workSeconds != null -> FinisherTimerMode.TimeBased(
            totalMinutes = finisher.duration_minutes,
            workSeconds = workSeconds,
            label = label,
        )
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

/** Scalar load/assist target and actual display. Unknown/null unit hints remain pounds. */
internal fun loadDisplayLabel(value: Double, unitHint: String?): String {
    val suffix = if (unitHint == UNIT_ASSIST) "°" else "lb"
    return "${formatWeight(value)}$suffix"
}

/** Scalar load/assist input label. Unknown/null unit hints remain pounds. */
internal fun loadInputLabel(unitHint: String?): String =
    if (unitHint == UNIT_ASSIST) "Assist (°)" else "Load (lb)"

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
 * [withCarriedLoad]) if one exists, else the set's own prescribed [targetLoad]. Never applied to
 * already-logged sets — those show their real actual via [loggedActualLine] instead.
 */
internal fun effectiveLoadPrefill(carriedLoad: Map<Int, Double>, movementId: Int, targetLoad: Double?): String =
    prefillWeight(carriedLoad[movementId] ?: targetLoad)

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
 * Shoe-swap cue decision for a group boundary: the shoe to swap TO (rendered as a
 * `"👟 Swap to X"` banner at that group) when [thisShoe] is non-null and differs from
 * [prevShoe] — the previous group's shoe. Null (no banner) when the shoe is unchanged or when
 * this group carries no shoe assignment at all. Pure display — never touches engine/state.
 */
internal fun shoeTransition(prevShoe: String?, thisShoe: String?): String? =
    if (thisShoe != null && thisShoe != prevShoe) thisShoe else null
