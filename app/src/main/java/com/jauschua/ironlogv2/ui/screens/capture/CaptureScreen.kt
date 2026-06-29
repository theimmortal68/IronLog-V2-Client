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
                        SessionContent(session, currentPlannedSetId, uiError, submitResult, scope, vm)
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
    scope: CoroutineScope,
    vm: CaptureViewModel,
) {
    // Flattened prescription for cursor-position queries (stable as long as session doesn't change).
    val flatSets = remember(session) {
        session.groups.flatMap { g -> g.exercises.flatMap { it.planned_sets } }
    }
    // IDs of sets that appear BEFORE the cursor in the flat order — rendered with "✓".
    // When currentPlannedSetId is null (all done), cursorIdx = flatSets.size → all are past.
    val pastIds = remember(session, currentPlannedSetId) {
        val cursorIdx = flatSets.indexOfFirst { it.id == currentPlannedSetId }
            .let { if (it < 0) flatSets.size else it }
        flatSets.take(cursorIdx).map { it.id }.toSet()
    }

    // Input state for the current set; auto-resets when the cursor advances.
    var setLoad by remember(currentPlannedSetId) { mutableStateOf("") }
    var setReps by remember(currentPlannedSetId) { mutableStateOf("") }
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

            // Target prescription
            val target = buildString {
                plannedSet.target_load?.let { append("${it}lb ") }
                val lo = plannedSet.target_reps_low
                val hi = plannedSet.target_reps_high
                when {
                    lo != null && hi != null && lo != hi -> append("${lo}-${hi} reps")
                    lo != null -> append("${lo} reps")
                    hi != null -> append("${hi} reps")
                }
            }.trim()
            if (target.isNotEmpty()) {
                Text(
                    text = "Target: $target",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Input controls for the current set only
            if (isCurrent) {
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
                        label = { Text("Reps") },
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
