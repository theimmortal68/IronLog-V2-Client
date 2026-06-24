package com.jauschua.ironlogv2.ui.screens.bands

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import com.jauschua.ironlogv2.data.api.dto.BandCalStatus
import com.jauschua.ironlogv2.data.api.dto.BandPairDto
import com.jauschua.ironlogv2.ui.ErrorRetryBox
import com.jauschua.ironlogv2.ui.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BandsScreen(
    vm: BandsViewModel = viewModel(factory = BandsViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Bands") }) }) { inner ->
        Surface(modifier = Modifier.fillMaxSize().padding(inner)) {
            when (val s = state) {
                is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is UiState.Error -> ErrorRetryBox(s.msg) { vm.reload() }
                is UiState.Success -> BandsList(s.data)
            }
        }
    }
}

@Composable
private fun BandsList(bands: List<BandPairDto>) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(bands, key = { it.id }) { b ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(b.label, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.size(8.dp))
                        AssistChip(
                            onClick = {},
                            label = { Text(b.calibration_status.name) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = when (b.calibration_status) {
                                    BandCalStatus.MEASURED -> Color(0xFFC8E6C9)
                                    BandCalStatus.MODELED  -> Color(0xFFFFE0B2)
                                }
                            ),
                        )
                    }
                    Spacer(Modifier.size(4.dp))
                    Text("bottom ${b.bottom_lb} lb  ·  peak ${b.peak_lb} lb", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
