// HistoryScreen.kt
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jauschua.ironlogv2.data.api.dto.SessionSummary
import com.jauschua.ironlogv2.ui.ErrorRetryBox
import com.jauschua.ironlogv2.ui.UiState

/** History tab: list of past completed sessions, tap to view logged actuals. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onOpen: (Int) -> Unit,
    onBack: () -> Unit,
    vm: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
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
                is UiState.Success -> SessionListBody(s.data, onOpen)
            }
        }
    }
}

@Composable
private fun SessionListBody(sessions: List<SessionSummary>, onOpen: (Int) -> Unit) {
    if (sessions.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No completed sessions yet.")
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(sessions, key = { it.id }) { session ->
            SessionRow(session, onOpen)
        }
    }
}

@Composable
private fun SessionRow(session: SessionSummary, onOpen: (Int) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { onOpen(session.id) },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Text(
                text = "${session.date} · ${session.day_role}",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
