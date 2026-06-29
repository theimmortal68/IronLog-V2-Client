package com.jauschua.ironlogv2.ui.screens.wizard

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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jauschua.ironlogv2.data.api.dto.WizardMovement
import com.jauschua.ironlogv2.ui.ErrorRetryBox
import com.jauschua.ironlogv2.ui.UiState

/** Trust labels owned by the server (compute_load_trust). The screen only routes display by them. */
private const val TRUST_STALE = "STALE"

/** The DTO's unit_hint / load_field marker for assisted movements (assist value, not a weight). */
private const val UNIT_ASSIST = "assist"

/**
 * First-run wizard screen: renders the server's trust verdict for each movement and starts the program.
 *
 * The three trust states drive display:
 *  - UNKNOWN → an empty input field to fill (the load).
 *  - STALE   → a prefilled value to confirm/adjust.
 *  - FRESH   → collapsed/summarized ("N ready"); not individually actionable.
 *
 * The "N left" counter and the Start gate are the server's values verbatim — the screen never
 * computes trust or readiness itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WizardScreen(
    programId: Int,
    onStarted: () -> Unit,
    vm: WizardViewModel = viewModel(factory = WizardViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val entered by vm.entered.collectAsStateWithLifecycle()
    val needsAttentionCount by vm.needsAttentionCount.collectAsStateWithLifecycle()
    val readyToStart by vm.readyToStart.collectAsStateWithLifecycle()
    val submitError by vm.submitError.collectAsStateWithLifecycle()
    val startResult by vm.startResult.collectAsStateWithLifecycle()

    LaunchedEffect(programId) { vm.load(programId) }

    // Navigate away once the server confirms the program started.
    LaunchedEffect(startResult) {
        if (startResult != null) onStarted()
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Setup") }) }) { inner ->
        Surface(modifier = Modifier.fillMaxSize().padding(inner)) {
            when (val s = state) {
                is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is UiState.Error -> ErrorRetryBox(s.msg) { vm.load(programId) }
                is UiState.Success -> WizardContent(
                    ui = s.data,
                    entered = entered,
                    needsAttentionCount = needsAttentionCount,
                    readyToStart = readyToStart,
                    submitError = submitError,
                    vm = vm,
                )
            }
        }
    }
}

@Composable
private fun WizardContent(
    ui: WizardUi,
    entered: Map<Int, String>,
    needsAttentionCount: Int,
    readyToStart: Boolean,
    submitError: String?,
    vm: WizardViewModel,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        // Header: program name + the server's live "N left" counter.
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(ui.programName, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "$needsAttentionCount left",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (needsAttentionCount == 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Action movements: UNKNOWN → empty field to fill, STALE → prefilled to confirm/adjust.
        ui.actionMovements.forEach { movement ->
            item(key = "action-${movement.movement_id}") {
                ActionMovementCard(
                    movement = movement,
                    value = entered[movement.movement_id].orEmpty(),
                    onValueChange = { vm.setEntered(movement.movement_id, it) },
                )
            }
        }

        // Fresh movements: collapsed/summarized — not individually actionable.
        if (ui.freshMovements.isNotEmpty()) {
            item(key = "fresh-summary") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "${ui.freshMovements.size} ready",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        submitError?.let { msg ->
            item(key = "error") {
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }

        // Save the touched entries (server recomputes trust + the N-left / ready gate).
        item(key = "resolve") {
            OutlinedButton(
                onClick = { vm.resolve() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Resolve")
            }
        }

        // Start is gated on the server's ready_to_start — never a client-side computation.
        item(key = "start") {
            Button(
                onClick = { vm.start() },
                enabled = readyToStart,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Start program")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionMovementCard(
    movement: WizardMovement,
    value: String,
    onValueChange: (String) -> Unit,
) {
    // Assisted movements collect an assist value, not a weight — labelled accordingly.
    val isAssist = movement.unit_hint == UNIT_ASSIST || movement.load_field == UNIT_ASSIST
    val fieldLabel = if (isAssist) "Assist (lb)" else "Load (${movement.unit_hint ?: "lb"})"
    val hint = if (movement.trust == TRUST_STALE) "Confirm or adjust" else "Needs a value"

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(movement.movement_name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(fieldLabel) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
