package com.jauschua.ironlogv2.ui.screens.movements

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jauschua.ironlogv2.data.api.dto.MovementDto
import com.jauschua.ironlogv2.ui.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovementsListScreen(
    onMovementClick: (Int) -> Unit,
    vm: MovementsListViewModel = viewModel(factory = MovementsListViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = {
                    HealthDot(state)
                    IconButton(onClick = { vm.reload() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        }
    ) { inner ->
        Surface(modifier = Modifier.fillMaxSize().padding(inner)) {
            when (val s = state) {
                is UiState.Loading -> CenteredSpinner()
                is UiState.Error   -> ErrorView(s.msg) { vm.reload() }
                is UiState.Success -> MovementsList(s.data, onMovementClick)
            }
        }
    }
}

@Composable
private fun HealthDot(state: UiState<*>) {
    val color = when (state) {
        is UiState.Success -> Color(0xFF2E7D32)
        is UiState.Error   -> Color(0xFFC62828)
        UiState.Loading    -> Color.Gray
    }
    Box(
        modifier = Modifier
            .padding(end = 12.dp)
            .size(12.dp)
            .clip(CircleShape),
    ) {
        Surface(color = color, modifier = Modifier.fillMaxSize()) {}
    }
}

@Composable
private fun CenteredSpinner() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorView(msg: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(msg, color = MaterialTheme.colorScheme.error)
            androidx.compose.material3.TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun MovementsList(items: List<MovementDto>, onClick: (Int) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { m ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clickable { onClick(m.id) }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(m.name, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.size(4.dp))
                    val sub = buildString {
                        append(m.region.name)
                        append(" · ")
                        append(m.lift_category.name)
                        if (m.is_primary) append(" · primary")
                    }
                    Text(sub, style = MaterialTheme.typography.bodyMedium)
                    val floor = m.load_floor
                    val cap = m.cap
                    if (floor != null || cap != null) {
                        Spacer(Modifier.size(4.dp))
                        Text("floor=${floor ?: "—"}  cap=${cap ?: "—"}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            HorizontalDivider()
        }
    }
}
