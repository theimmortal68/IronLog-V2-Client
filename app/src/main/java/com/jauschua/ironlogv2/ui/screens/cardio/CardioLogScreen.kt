package com.jauschua.ironlogv2.ui.screens.cardio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.jauschua.ironlogv2.data.api.dto.CardioLogCreate
import com.jauschua.ironlogv2.ui.ErrorRetryBox
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardioLogScreen(
    onBack: () -> Unit,
    vm: CardioLogViewModel = viewModel(factory = CardioLogViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var durationMinutes by remember { mutableStateOf("") }
    var avgHr by remember { mutableStateOf("") }
    var modality by remember { mutableStateOf("WALK") }
    var inclinePct by remember { mutableStateOf("") }
    var backwardWalkDone by remember { mutableStateOf(false) }

    LaunchedEffect(state) { if (state is CardioLogUiState.Submitted) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log Cardio") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { inner ->
        Surface(modifier = Modifier.fillMaxSize().padding(inner)) {
            when (val s = state) {
                is CardioLogUiState.Error -> ErrorRetryBox(s.msg) { vm.resetToIdle() }
                else -> Column(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = date,
                        onValueChange = { date = it },
                        label = { Text("Date") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = durationMinutes,
                        onValueChange = { durationMinutes = it },
                        label = { Text("Duration (min)") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = avgHr,
                        onValueChange = { avgHr = it },
                        label = { Text("Avg HR") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )

                    Text("Modality", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf("WALK" to "Walk", "TREADMILL" to "Treadmill").forEach { (value, label) ->
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .selectable(
                                        selected = modality == value,
                                        onClick = { modality = value },
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = modality == value,
                                    onClick = { modality = value },
                                )
                                Text(label, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }

                    if (modality == "TREADMILL") {
                        OutlinedTextField(
                            value = inclinePct,
                            onValueChange = { inclinePct = it },
                            label = { Text("Incline (%)") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = backwardWalkDone,
                                onCheckedChange = { backwardWalkDone = it },
                            )
                            Text("Backward walk done", style = MaterialTheme.typography.bodyLarge)
                        }
                    }

                    Button(
                        onClick = {
                            buildCardioLogCreate(
                                date = date,
                                durationMinutes = durationMinutes,
                                avgHr = avgHr,
                                modality = modality,
                                inclinePct = inclinePct,
                                backwardWalkDone = backwardWalkDone,
                            )?.let(vm::submit)
                        },
                        enabled = state !is CardioLogUiState.Submitting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state is CardioLogUiState.Submitting) "Submitting" else "Submit")
                    }
                }
            }
        }
    }
}

internal fun buildCardioLogCreate(
    date: String,
    durationMinutes: String,
    avgHr: String,
    modality: String,
    inclinePct: String,
    backwardWalkDone: Boolean,
): CardioLogCreate? {
    if (date.isBlank() || modality.isBlank()) return null
    val duration = durationMinutes.toIntOrNull() ?: return null
    if (duration <= 0) return null
    return CardioLogCreate(
        date = date,
        duration_minutes = duration,
        avg_hr = avgHr.trim().toIntOrNull(),
        modality = modality,
        incline_pct = inclinePct.trim().toDoubleOrNull(),
        backward_walk_done = backwardWalkDone,
    )
}
