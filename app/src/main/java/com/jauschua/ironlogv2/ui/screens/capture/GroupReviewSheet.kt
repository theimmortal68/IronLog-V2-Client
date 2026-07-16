package com.jauschua.ironlogv2.ui.screens.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Seed toggle state from existing survey drafts; every exercise present, missing → (false,false). */
fun initialFlags(review: GroupReview): Map<Int, Pair<Boolean, Boolean>> =
    review.group.exercises.associate { e ->
        val d = review.surveys.firstOrNull { it.movementId == e.movement_id }
        e.movement_id to ((d?.asymmetryFlag ?: false) to (d?.techniqueFlag ?: false))
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupReviewSheet(
    review: GroupReview,
    onSave: (Map<Int, Pair<Boolean, Boolean>>, String?) -> Unit,
    onSkip: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Mutable per-exercise flag state, seeded once from the (prefilled) review.
    val flags = remember(review) {
        mutableStateMapOf<Int, Pair<Boolean, Boolean>>().apply { putAll(initialFlags(review)) }
    }
    var note by remember(review) { mutableStateOf(review.noteText ?: "") }

    ModalBottomSheet(onDismissRequest = onSkip, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Quick check — ${review.group.label ?: review.group.group_type}",
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            )
            review.group.exercises.forEach { e ->
                val (asym, tech) = flags[e.movement_id] ?: (false to false)
                Text(e.movement_name)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = asym, onCheckedChange = { flags[e.movement_id] = it to tech })
                    Text("L/R asymmetry", Modifier.padding(end = 16.dp))
                    Checkbox(checked = tech, onCheckedChange = { flags[e.movement_id] = asym to it })
                    Text("Technique broke down")
                }
            }
            OutlinedTextField(
                value = note, onValueChange = { note = it },
                label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = onSkip) { Text("Skip") }
                Button(onClick = { onSave(flags.toMap(), note.ifBlank { null }) }) { Text("Save") }
            }
        }
    }
}
