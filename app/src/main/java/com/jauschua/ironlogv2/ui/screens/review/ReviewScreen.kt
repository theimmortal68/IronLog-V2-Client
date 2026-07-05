// ReviewScreen.kt
package com.jauschua.ironlogv2.ui.screens.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.jauschua.ironlogv2.data.api.dto.MovementDto
import com.jauschua.ironlogv2.data.api.dto.NoteReviewOut
import com.jauschua.ironlogv2.data.api.dto.OverrideOut
import com.jauschua.ironlogv2.data.api.dto.ProgramSlotOut
import com.jauschua.ironlogv2.ui.ErrorRetryBox
import com.jauschua.ironlogv2.ui.UiState

/** Review tab: unconfirmed change-proposals extracted from session notes (CONFIG_CHANGE /
 *  PROGRAMMING_REQUEST). Confirm/dismiss only flip a server-side flag. CONFIG_CHANGE proposals
 *  that route to a concrete adjustment (movement swap / load / reps) additionally offer Apply,
 *  which opens an explicit confirm-wizard: the athlete confirms (or changes) the source slot,
 *  then supplies the action-routed adjustment — `/notes/{id}/apply` creates the slot override.
 *  Active adjustments (`/overrides`) are listed below, one legible line per type, with Revert. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    onBack: () -> Unit,
    vm: ReviewViewModel = viewModel(factory = ReviewViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val overrides by vm.overrides.collectAsStateWithLifecycle()
    val movements by vm.movements.collectAsStateWithLifecycle()
    val wizard by vm.wizard.collectAsStateWithLifecycle()
    val applyError by vm.applyError.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review") },
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
                is UiState.Error -> ErrorRetryBox(s.msg) { vm.load() }
                is UiState.Success -> ReviewBody(
                    notes = s.data,
                    overrides = overrides,
                    onConfirm = { vm.confirm(it) },
                    onDismiss = { vm.dismiss(it) },
                    onApplyClick = { note -> vm.openApply(note) },
                    onRevert = { vm.revert(it) },
                )
            }
        }
    }

    wizard?.let { w ->
        ApplyWizardDialog(
            wizard = w,
            movements = movements,
            onSelectSlot = { vm.selectSlot(it) },
            onSubmitSwap = { movementId -> vm.submitSwap(movementId) },
            onSubmitLoad = { delta, absolute -> vm.submitLoad(delta, absolute) },
            onSubmitReps = { low, high -> vm.submitReps(low, high) },
            onDismiss = { vm.closeWizard() },
        )
    }

    applyError?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.clearApplyError() },
            confirmButton = { TextButton(onClick = { vm.clearApplyError() }) { Text("OK") } },
            title = { Text("Couldn't apply") },
            text = { Text(msg) },
        )
    }
}

@Composable
private fun ReviewBody(
    notes: List<NoteReviewOut>,
    overrides: List<OverrideOut>,
    onConfirm: (Int) -> Unit,
    onDismiss: (Int) -> Unit,
    onApplyClick: (NoteReviewOut) -> Unit,
    onRevert: (Int) -> Unit,
) {
    if (notes.isEmpty() && overrides.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No pending change-proposals.")
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (notes.isEmpty()) {
            item(key = "empty-notes") {
                Text("No pending change-proposals.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            items(notes, key = { "note-${it.id}" }) { note ->
                ReviewCard(note, onConfirm, onDismiss, onApplyClick)
            }
        }
        if (overrides.isNotEmpty()) {
            item(key = "overrides-header") {
                Text(
                    "Active adjustments",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(overrides, key = { "override-${it.id}" }) { ov ->
                OverrideCard(ov, onRevert)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewCard(
    note: NoteReviewOut,
    onConfirm: (Int) -> Unit,
    onDismiss: (Int) -> Unit,
    onApplyClick: (NoteReviewOut) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(note.classification, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(note.text, style = MaterialTheme.typography.bodyLarge)
            val changeLine = proposedChangeLine(note)
            if (changeLine.isNotEmpty()) {
                Text(changeLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = { onDismiss(note.id) }) { Text("Dismiss") }
                // A concrete adjustment must go through Apply (creates the override); plain
                // Confirm only acknowledges and would leave the inbox without making the change,
                // so it's hidden for CONFIG_CHANGE notes that route to SWAP/LOAD/REPS. An
                // unclassifiable CONFIG_CHANGE note shows neither (Dismiss only). Non-CONFIG_CHANGE
                // notes keep Confirm. Mutually exclusive.
                if (showApply(note)) {
                    TextButton(onClick = { onApplyClick(note) }) { Text("Apply") }
                }
                if (showConfirm(note)) {
                    TextButton(onClick = { onConfirm(note.id) }) { Text("Confirm") }
                }
            }
        }
    }
}

/** Renders one generalized [OverrideOut] as a legible sentence by type:
 *  MOVEMENT — "Day · Tier · Base → Target"; LOAD — "Day · Tier · Movement +10 lb" / "set 225 lb";
 *  REPS — "Day · Tier · Movement 5–8 reps". Provenance (`source_note_text`) shown below when
 *  present. */
@Composable
private fun OverrideCard(
    override: OverrideOut,
    onRevert: (Int) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val label = listOfNotNull(override.day_role, override.tier_label).joinToString(" · ")
            if (label.isNotEmpty()) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(overrideSummaryLine(override), style = MaterialTheme.typography.bodyLarge)
            override.source_note_text?.let { text ->
                Text(
                    "from \"$text\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(onClick = { onRevert(override.id) }) { Text("Revert") }
            }
        }
    }
}

/** The Apply confirm-wizard: source slot (confirmable/editable) + the action-routed adjustment. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ApplyWizardDialog(
    wizard: ApplyWizardState,
    movements: List<MovementDto>,
    onSelectSlot: (ProgramSlotOut) -> Unit,
    onSubmitSwap: (Int) -> Unit,
    onSubmitLoad: (Double?, Double?) -> Unit,
    onSubmitReps: (Int?, Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var slotPickerOpen by remember(wizard.note.id) { mutableStateOf(false) }
    var movementPickerOpen by remember(wizard.note.id) { mutableStateOf(false) }

    val slotKey = wizard.selectedSlot?.tier_exercise_id
    var loadAbsoluteText by remember(slotKey) { mutableStateOf("") }
    var repLowText by remember(slotKey) { mutableStateOf(wizard.selectedSlot?.current_rep_low?.toString().orEmpty()) }
    var repHighText by remember(slotKey) { mutableStateOf(wizard.selectedSlot?.current_rep_high?.toString().orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(applyWizardTitle(wizard.selectedSlot)) },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Slot: ${slotLabel(wizard.selectedSlot)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { slotPickerOpen = true }) { Text("Change slot") }
                }

                when {
                    wizard.slotsLoading -> Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    wizard.selectedSlot == null -> Text(
                        "No program slot selected.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> when (wizard.kind) {
                        AdjustmentKind.SWAP -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Replace with:", style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = { movementPickerOpen = true }, enabled = !wizard.submitting) {
                                Text("Pick movement")
                            }
                        }
                        AdjustmentKind.LOAD -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Adjust load:", style = MaterialTheme.typography.bodyMedium)
                            // Both directions: a LOAD_DECREASE note ("too heavy") lowers the load,
                            // LOAD_INCREASE raises it. The server accepts negative load_delta.
                            // FlowRow wraps to a second line on narrow (cover) displays instead of
                            // clipping the last button.
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(-10.0, -5.0, 5.0, 10.0).forEach { delta ->
                                    OutlinedButton(
                                        onClick = { onSubmitLoad(delta, null) },
                                        enabled = !wizard.submitting,
                                    ) { Text("${if (delta >= 0) "+" else ""}${delta.toInt()}") }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = loadAbsoluteText,
                                    onValueChange = { loadAbsoluteText = it },
                                    label = { Text("Set exact (lb)") },
                                    singleLine = true,
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.width(140.dp),
                                )
                                val absolute = loadAbsoluteText.toDoubleOrNull()
                                Button(
                                    onClick = { absolute?.let { onSubmitLoad(null, it) } },
                                    enabled = absolute != null && !wizard.submitting,
                                ) { Text("Set") }
                            }
                        }
                        AdjustmentKind.REPS -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Set rep target:", style = MaterialTheme.typography.bodyMedium)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = repLowText,
                                    onValueChange = { repLowText = it },
                                    label = { Text("Low") },
                                    singleLine = true,
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.width(90.dp),
                                )
                                OutlinedTextField(
                                    value = repHighText,
                                    onValueChange = { repHighText = it },
                                    label = { Text("High") },
                                    singleLine = true,
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.width(90.dp),
                                )
                            }
                            val low = repLowText.toIntOrNull()
                            val high = repHighText.toIntOrNull()
                            Button(
                                onClick = { onSubmitReps(low, high) },
                                enabled = (low != null || high != null) && !wizard.submitting,
                            ) { Text("Apply") }
                        }
                        AdjustmentKind.NONE -> Text(
                            "This note doesn't resolve to a concrete adjustment — Dismiss it instead.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    )

    if (slotPickerOpen) {
        SlotPickerDialog(
            slots = wizard.slots,
            onPick = { slot ->
                onSelectSlot(slot)
                slotPickerOpen = false
            },
            onDismiss = { slotPickerOpen = false },
        )
    }

    if (movementPickerOpen) {
        MovementPickerDialog(
            movements = movements,
            initialQuery = pickerSeedText(wizard.note),
            onPick = { m ->
                movementPickerOpen = false
                onSubmitSwap(m.id)
            },
            onDismiss = { movementPickerOpen = false },
        )
    }
}

private fun slotLabel(slot: ProgramSlotOut?): String =
    slot?.let {
        listOfNotNull(it.day_role, it.tier_label, it.movement_name?.let(::displayMovementName)).joinToString(" · ")
    } ?: "none selected"

private fun applyWizardTitle(slot: ProgramSlotOut?): String =
    slot?.movement_name?.let { "Change ${displayMovementName(it)}" } ?: "Confirm slot"

/** MOVEMENT: "Base → Target"; LOAD: "+10 lb" or "set 225 lb"; REPS: "5–8 reps". */
private fun overrideSummaryLine(override: OverrideOut): String = when (override.override_type) {
    "MOVEMENT" -> {
        val base = override.movement_name?.let(::displayMovementName) ?: "?"
        val target = override.to_movement_name?.let(::displayMovementName) ?: "?"
        "$base → $target"
    }
    "LOAD" -> {
        val movement = override.movement_name?.let(::displayMovementName) ?: "?"
        val magnitude = override.load_absolute?.let { "set ${formatNumber(it)} lb" }
            ?: override.load_delta?.let { d -> "${if (d >= 0) "+" else ""}${formatNumber(d)} lb" }
            ?: "load"
        "$movement $magnitude"
    }
    "REPS" -> {
        val movement = override.movement_name?.let(::displayMovementName) ?: "?"
        val low = override.rep_low
        val high = override.rep_high
        val range = when {
            low != null && high != null -> "$low–$high reps"
            low != null -> "$low+ reps"
            high != null -> "up to $high reps"
            else -> "reps"
        }
        "$movement $range"
    }
    else -> override.movement_name?.let(::displayMovementName) ?: "?"
}

private fun formatNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlotPickerDialog(
    slots: List<ProgramSlotOut>,
    onPick: (ProgramSlotOut) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick program slot") },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            if (slots.isEmpty()) {
                Text("No program slots available.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(slots, key = { it.tier_exercise_id }) { slot ->
                        TextButton(onClick = { onPick(slot) }, modifier = Modifier.fillMaxWidth()) {
                            Text(slotLabel(slot), modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MovementPickerDialog(
    movements: List<MovementDto>,
    initialQuery: String,
    onPick: (MovementDto) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    val filtered = remember(query, movements) { filterMovements(movements, query) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick replacement movement") },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search movements") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    movements.isEmpty() -> Box(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                    filtered.isEmpty() -> Text(
                        "No matching movements.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(filtered, key = { it.id }) { m ->
                            TextButton(onClick = { onPick(m) }, modifier = Modifier.fillMaxWidth()) {
                                Text(displayMovementName(m.name), modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        },
    )
}
