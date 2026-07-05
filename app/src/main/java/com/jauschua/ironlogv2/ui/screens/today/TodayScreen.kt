// TodayScreen.kt
package com.jauschua.ironlogv2.ui.screens.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jauschua.ironlogv2.data.api.dto.ExerciseOut
import com.jauschua.ironlogv2.data.api.dto.GroupOut
import com.jauschua.ironlogv2.data.api.dto.PlannedSetOut
import com.jauschua.ironlogv2.data.api.dto.SessionDetailResponse
import com.jauschua.ironlogv2.ui.ErrorRetryBox
import com.jauschua.ironlogv2.ui.screens.capture.htSetupLine
import com.jauschua.ironlogv2.ui.screens.review.displayMovementName

/**
 * Today tab: pick a day (if none is already planned) → generate → review a read-only preview →
 * approve → hand off to Capture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onContinue: () -> Unit,
    onHistory: () -> Unit,
    onReview: () -> Unit,
    vm: TodayViewModel = viewModel(factory = TodayViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val reviewCount by vm.reviewCount.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Today") },
                actions = {
                    TextButton(onClick = onReview) { Text(reviewButtonLabel(reviewCount)) }
                    TextButton(onClick = onHistory) { Text("History") }
                },
            )
        },
    ) { inner ->
        Surface(modifier = Modifier.fillMaxSize().padding(inner)) {
            when (val s = state) {
                is TodayUiState.Loading, is TodayUiState.Generating, is TodayUiState.Approving -> Centered {
                    CircularProgressIndicator()
                }
                is TodayUiState.HasPlanned -> HasPlannedContent(s.session, onContinue)
                is TodayUiState.NoSession -> NoSessionContent(s.days) { day -> vm.generate(day) }
                is TodayUiState.Preview -> PreviewContent(
                    preview = s.preview,
                    onApprove = { vm.approve() },
                    onRegenerate = { vm.regenerate(s.preview.day_role) },
                )
                is TodayUiState.GenerateError -> ErrorRetryBox(s.msg) { vm.load() }
                is TodayUiState.Approved -> LaunchedEffect(s.sessionId) { onContinue() }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun HasPlannedContent(session: SessionDetailResponse, onContinue: () -> Unit) {
    Centered {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Today: ${session.day_role}", style = MaterialTheme.typography.titleLarge)
            Button(onClick = onContinue) { Text("Continue workout") }
        }
    }
}

@Composable
private fun NoSessionContent(days: List<String>, onGenerate: (String) -> Unit) {
    var selected by remember(days) { mutableStateOf(days.firstOrNull()) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Pick a day", style = MaterialTheme.typography.titleLarge)
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(days, key = { it }) { day ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = day == selected, onClick = { selected = day }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = day == selected, onClick = { selected = day })
                    Text(day, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Button(
            onClick = { selected?.let(onGenerate) },
            enabled = selected != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Generate")
        }
    }
}

@Composable
private fun PreviewContent(
    preview: SessionDetailResponse,
    onApprove: () -> Unit,
    onRegenerate: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            item(key = "header") {
                Text("Preview: ${preview.day_role}", style = MaterialTheme.typography.titleLarge)
            }
            preview.groups.forEachIndexed { gi, group ->
                item(key = "group-$gi") { ReadOnlyGroupCard(gi, group) }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onRegenerate, modifier = Modifier.weight(1f)) {
                Text("Regenerate")
            }
            Button(onClick = onApprove, modifier = Modifier.weight(1f)) {
                Text("Approve")
            }
        }
    }
}

@Composable
private fun ReadOnlyGroupCard(gi: Int, group: GroupOut) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = group.label ?: group.group_type,
                style = MaterialTheme.typography.titleMedium,
            )
            group.exercises.forEachIndexed { ei, exercise ->
                ReadOnlyExerciseBlock(gi, ei, exercise)
            }
        }
    }
}

@Composable
private fun ReadOnlyExerciseBlock(gi: Int, ei: Int, exercise: ExerciseOut) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(displayMovementName(exercise.movement_name), style = MaterialTheme.typography.bodyLarge)
        exercise.planned_sets.forEachIndexed { si, set ->
            ReadOnlySetRow(gi, ei, si, set)
        }
    }
}

@Composable
private fun ReadOnlySetRow(gi: Int, ei: Int, si: Int, set: PlannedSetOut) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Set ${si + 1}${if (set.is_warmup) " (warmup)" else ""}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = targetSummary(set),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A compact "load × reps @ RPE" summary of a planned set's targets, tolerant of any field being
 *  absent (bands/assist/plate-based sets don't all populate the same fields).
 *
 *  HT (band-composite) sets carry target_plates/band_config/target_felt_peak instead of a
 *  meaningful target_load, so they render via [htSetupLine] — the same helper CaptureScreen's
 *  [com.jauschua.ironlogv2.ui.screens.capture.SetCard] uses — rather than the raw load number. */
internal fun targetSummary(set: PlannedSetOut): String {
    val parts = mutableListOf<String>()
    val isHtSet = set.target_plates != null || set.band_config != null
    if (isHtSet) {
        htSetupLine(set.target_plates, set.band_config, set.target_felt_peak)
            .takeIf { it.isNotEmpty() }
            ?.let { parts += it }
    } else {
        set.target_load?.let { parts += "$it" }
    }
    when {
        set.target_reps_low != null && set.target_reps_high != null ->
            parts += "${set.target_reps_low}-${set.target_reps_high} reps"
        set.target_reps_low != null -> parts += "${set.target_reps_low} reps"
    }
    set.target_rpe?.let { parts += "RPE $it" }
    return if (parts.isEmpty()) "—" else parts.joinToString(" · ")
}
