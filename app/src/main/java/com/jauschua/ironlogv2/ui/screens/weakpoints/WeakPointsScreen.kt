package com.jauschua.ironlogv2.ui.screens.weakpoints

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jauschua.ironlogv2.data.api.dto.MuscleGroupSummaryOut
import com.jauschua.ironlogv2.data.api.dto.WeakMovementOut
import com.jauschua.ironlogv2.data.api.dto.WeakPointAssessmentOut
import com.jauschua.ironlogv2.ui.ErrorRetryBox
import com.jauschua.ironlogv2.ui.UiState

fun weakPointMuscleGroups(assessment: WeakPointAssessmentOut): List<MuscleGroupSummaryOut> =
    assessment.muscle_groups.filter { it.weak_count > 0 }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeakPointsScreen(
    onBack: () -> Unit,
    vm: WeakPointsViewModel = viewModel(factory = WeakPointsViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Weak Points") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize().padding(inner)) {
            when (val s = state) {
                is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is UiState.Error -> ErrorRetryBox(s.msg) { vm.reload() }
                is UiState.Success -> WeakPointsBody(s.data)
            }
        }
    }
}

@Composable
private fun WeakPointsBody(assessment: WeakPointAssessmentOut) {
    val groups = weakPointMuscleGroups(assessment)
    if (groups.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No weak points flagged.")
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(groups, key = { it.muscle }) { group ->
            WeakPointGroupCard(group)
        }
    }
}

@Composable
private fun WeakPointGroupCard(group: MuscleGroupSummaryOut) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(group.muscle, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${group.weak_count}/${group.total_count} flagged",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (group.weak_movements.isEmpty()) {
                Text("No movements listed.", style = MaterialTheme.typography.bodySmall)
            } else {
                group.weak_movements.forEach { movement ->
                    WeakMovementRow(movement)
                }
            }
        }
    }
}

@Composable
private fun WeakMovementRow(movement: WeakMovementOut) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(movement.name, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (movement.stalled) {
                WeakPointTag("Stalled", Color(0xFFFFE0B2))
            }
            if (movement.lagging) {
                WeakPointTag("Lagging", Color(0xFFFFCDD2))
            }
        }
    }
}

@Composable
private fun WeakPointTag(label: String, color: Color) {
    AssistChip(
        onClick = {},
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(containerColor = color),
    )
}
