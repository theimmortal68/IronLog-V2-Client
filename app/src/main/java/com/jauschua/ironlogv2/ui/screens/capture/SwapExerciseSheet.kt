package com.jauschua.ironlogv2.ui.screens.capture

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jauschua.ironlogv2.data.api.dto.MovementSummary

/**
 * Two-step swap picker: pick a replacement movement (suggested substitutes list,
 * or search a fetched full-library list), then choose today-only vs permanent.
 * [onConfirm] fires once with the final (movementId, makePermanent) choice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwapExerciseSheet(
    substitutes: List<MovementSummary>,
    fullLibrary: List<MovementSummary>,
    onConfirm: (movementId: Int, makePermanent: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<MovementSummary?>(null) }
    var makePermanent by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (selected == null) {
                Text("Suggested substitutes")
                LazyColumn {
                    items(substitutes) { m ->
                        Text(m.name, modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = m }
                            .padding(vertical = 8.dp))
                    }
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("Search full library") },
                    modifier = Modifier.fillMaxWidth(),
                )
                val filtered = fullLibrary.filter { it.name.contains(query, ignoreCase = true) }
                LazyColumn {
                    items(filtered) { m ->
                        Text(m.name, modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = m }
                            .padding(vertical = 8.dp))
                    }
                }
            } else {
                Text("Swap to ${selected!!.name}")
                Row {
                    RadioButton(selected = !makePermanent, onClick = { makePermanent = false })
                    Text("Today only")
                }
                Row {
                    RadioButton(selected = makePermanent, onClick = { makePermanent = true })
                    Text("Update program going forward")
                }
                Button(onClick = { onConfirm(selected!!.id, makePermanent) }) {
                    Text("Confirm swap")
                }
            }
        }
    }
}
