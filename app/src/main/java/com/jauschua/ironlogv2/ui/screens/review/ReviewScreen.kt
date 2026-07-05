// ReviewScreen.kt
package com.jauschua.ironlogv2.ui.screens.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jauschua.ironlogv2.data.api.dto.MovementDto
import com.jauschua.ironlogv2.data.api.dto.NoteReviewOut
import com.jauschua.ironlogv2.data.api.dto.OverrideOut
import com.jauschua.ironlogv2.ui.ErrorRetryBox
import com.jauschua.ironlogv2.ui.UiState

/** Review tab: unconfirmed change-proposals extracted from session notes (CONFIG_CHANGE /
 *  PROGRAMMING_REQUEST). Confirm/dismiss only flip a server-side flag. CONFIG_CHANGE proposals
 *  additionally offer Apply → a movement picker → `/notes/{id}/apply`, which the server resolves
 *  into a slot-level override; the resulting active swaps are listed below with Revert. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    onBack: () -> Unit,
    vm: ReviewViewModel = viewModel(factory = ReviewViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val overrides by vm.overrides.collectAsStateWithLifecycle()
    val movements by vm.movements.collectAsStateWithLifecycle()
    val applyError by vm.applyError.collectAsStateWithLifecycle()
    var pickerForNote by remember { mutableStateOf<NoteReviewOut?>(null) }

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
                    onApplyClick = { note ->
                        vm.loadMovementsIfNeeded()
                        pickerForNote = note
                    },
                    onRevert = { vm.revert(it) },
                )
            }
        }
    }

    pickerForNote?.let { note ->
        MovementPickerDialog(
            movements = movements,
            initialQuery = pickerSeedText(note),
            onPick = { m ->
                vm.apply(note.id, m.id)
                pickerForNote = null
            },
            onDismiss = { pickerForNote = null },
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
                    "Active swaps",
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
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(onClick = { onDismiss(note.id) }) { Text("Dismiss") }
                // A swap must go through Apply (creates the override); plain Confirm only
                // acknowledges and would leave the inbox without making the change, so it's hidden
                // for CONFIG_CHANGE. Non-swap actionable notes keep Confirm. Mutually exclusive.
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
            Text(
                "${override.from_movement_name ?: "?"} → ${override.to_movement_name ?: "?"}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(onClick = { onRevert(override.id) }) { Text("Revert") }
            }
        }
    }
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
                                Text(m.name, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        },
    )
}
