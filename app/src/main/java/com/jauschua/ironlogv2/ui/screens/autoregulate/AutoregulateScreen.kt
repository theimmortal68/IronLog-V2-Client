package com.jauschua.ironlogv2.ui.screens.autoregulate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jauschua.ironlogv2.data.api.dto.FeedbackTap
import com.jauschua.ironlogv2.data.api.dto.MovementDto
import com.jauschua.ironlogv2.ui.ErrorRetryBox
import com.jauschua.ironlogv2.ui.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoregulateScreen(
    vm: AutoregulateViewModel = viewModel(factory = AutoregulateViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Autoregulate") }) }) { inner ->
        Surface(modifier = Modifier.fillMaxSize().padding(inner)) {
            when (val s = state) {
                is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is UiState.Error   -> ErrorRetryBox(s.msg) { vm.reload() }
                is UiState.Success -> Form(s.data, vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Form(ui: AutoregUi, vm: AutoregulateViewModel) {
    val selected: MovementDto? = ui.selectedId?.let { id -> ui.movements.firstOrNull { it.id == id } }
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {

        // Movement picker (LADDER-only)
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            OutlinedTextField(
                value = selected?.name ?: "Pick a movement…",
                onValueChange = {},
                readOnly = true,
                label = { Text("Movement (LADDER only)") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ui.movements.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m.name) },
                        onClick = { vm.select(m.id); expanded = false },
                    )
                }
                if (ui.movements.isEmpty()) {
                    DropdownMenuItem(text = { Text("No LADDER movements available") }, onClick = {}, enabled = false)
                }
            }
        }

        // Current load
        OutlinedTextField(
            value = ui.currentLoad,
            onValueChange = { vm.setCurrentLoad(it) },
            label = { Text("Current load (lb)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = selected != null,
        )

        // Tap (segmented)
        val taps = listOf(FeedbackTap.TOO_EASY, FeedbackTap.ON_TARGET, FeedbackTap.TOO_HARD)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            taps.forEachIndexed { i, t ->
                SegmentedButton(
                    selected = ui.tap == t,
                    onClick = { vm.setTap(t) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = taps.size),
                    enabled = selected != null,
                ) { Text(t.name.replace('_', ' ')) }
            }
        }

        // Tier stepper bounded by selected ladder
        val tierMax = (selected?.increment_ladder?.size ?: 1) - 1
        Card {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Tier: ${ui.tier}  (0..$tierMax)", style = MaterialTheme.typography.bodyLarge)
                Row {
                    IconButton(
                        onClick = { vm.setTier((ui.tier - 1).coerceAtLeast(0)) },
                        enabled = selected != null && ui.tier > 0,
                    ) { Icon(Icons.Filled.Remove, contentDescription = "tier-down") }
                    IconButton(
                        onClick = { vm.setTier((ui.tier + 1).coerceAtMost(tierMax)) },
                        enabled = selected != null && ui.tier < tierMax,
                    ) { Icon(Icons.Filled.Add, contentDescription = "tier-up") }
                }
            }
        }

        Button(
            onClick = { vm.submit() },
            enabled = selected != null && !ui.submitting,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (ui.submitting) "Submitting…" else "Suggest next load") }

        ui.submitError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        ui.lastResult?.let { res ->
            val current = ui.currentLoad.toDoubleOrNull()
            val delta = if (current != null) res.suggested_load - current else null
            val deltaText = when {
                delta == null -> "—"
                delta > 0     -> "+${delta} lb"
                delta < 0     -> "${delta} lb"
                else          -> "unchanged"
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Suggested next load", style = MaterialTheme.typography.labelMedium)
                    Text("${res.suggested_load} lb", style = MaterialTheme.typography.headlineMedium)
                    Text("Δ $deltaText", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
