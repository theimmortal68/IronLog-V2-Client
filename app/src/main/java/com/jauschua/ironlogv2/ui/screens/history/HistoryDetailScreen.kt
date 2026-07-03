// HistoryDetailScreen.kt
package com.jauschua.ironlogv2.ui.screens.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jauschua.ironlogv2.data.api.dto.LoggedSet
import com.jauschua.ironlogv2.data.api.dto.LoggedSetsResponse
import com.jauschua.ironlogv2.ui.ErrorRetryBox
import com.jauschua.ironlogv2.ui.UiState

/** Read-only detail: a completed session's logged actuals, grouped by movement. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDetailScreen(
    id: Int,
    onBack: () -> Unit,
    vm: HistoryDetailViewModel = viewModel(factory = HistoryDetailViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(id) { vm.detail(id) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Session") },
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
                is UiState.Error -> ErrorRetryBox(s.msg) { vm.retry() }
                is UiState.Success -> DetailBody(s.data)
            }
        }
    }
}

@Composable
private fun DetailBody(session: LoggedSetsResponse) {
    val groups = groupLogsByMovement(session.logs)
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            Text(
                text = "${session.date} · ${session.day_role}",
                style = MaterialTheme.typography.titleLarge,
            )
        }
        if (groups.isEmpty()) {
            item(key = "empty") { Text("No logged sets.") }
        }
        items(groups, key = { it.movementId }) { group ->
            MovementCard(group)
        }
    }
}

@Composable
private fun MovementCard(group: MovementLogs) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(group.movementName, style = MaterialTheme.typography.titleMedium)
            group.sets.forEach { set -> SetRow(set) }
        }
    }
}

@Composable
private fun SetRow(set: LoggedSet) {
    Text(
        text = setSummary(set) + if (set.is_warmup) " (warmup)" else "",
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** "load×reps" summary, e.g. "165×8", tolerant of either field being absent. */
private fun setSummary(set: LoggedSet): String {
    val load = set.load?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() } ?: "—"
    val reps = set.reps?.toString() ?: "—"
    return "$load×$reps"
}
