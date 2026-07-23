package com.jauschua.ironlogv2.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.jauschua.ironlogv2.data.api.dto.GoalSettingsIn
import com.jauschua.ironlogv2.data.api.dto.GoalSettingsOut
import com.jauschua.ironlogv2.ui.ErrorRetryBox

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val saveError by vm.saveError.collectAsStateWithLifecycle()

    var formSeeded by remember { mutableStateOf(false) }
    var targetBodyweight by remember { mutableStateOf("") }
    var targetBodyweightTolerance by remember { mutableStateOf("") }
    var targetBodyFatPct by remember { mutableStateOf("") }
    var targetBodyFatPctTolerance by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state) {
        if (!formSeeded) {
            when (val s = state) {
                is SettingsUiState.NoGoalSet -> {
                    targetBodyweight = ""
                    targetBodyweightTolerance = ""
                    targetBodyFatPct = ""
                    targetBodyFatPctTolerance = ""
                    formSeeded = true
                }
                is SettingsUiState.HasGoal -> {
                    targetBodyweight = formatGoalNumber(s.goal.target_bodyweight)
                    targetBodyweightTolerance = formatGoalNumber(s.goal.target_bodyweight_tolerance)
                    targetBodyFatPct = formatGoalNumber(s.goal.target_body_fat_pct)
                    targetBodyFatPctTolerance = formatGoalNumber(s.goal.target_body_fat_pct_tolerance)
                    formSeeded = true
                }
                else -> Unit
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") })
        },
    ) { inner ->
        Surface(modifier = Modifier.fillMaxSize().padding(inner)) {
            when (val s = state) {
                is SettingsUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is SettingsUiState.Error -> ErrorRetryBox(s.msg) { vm.reload() }
                is SettingsUiState.NoGoalSet -> SettingsBody(
                    goal = null,
                    targetBodyweight = targetBodyweight,
                    onTargetBodyweightChange = {
                        targetBodyweight = it
                        validationError = null
                        vm.clearSaveError()
                    },
                    targetBodyweightTolerance = targetBodyweightTolerance,
                    onTargetBodyweightToleranceChange = {
                        targetBodyweightTolerance = it
                        validationError = null
                        vm.clearSaveError()
                    },
                    targetBodyFatPct = targetBodyFatPct,
                    onTargetBodyFatPctChange = {
                        targetBodyFatPct = it
                        validationError = null
                        vm.clearSaveError()
                    },
                    targetBodyFatPctTolerance = targetBodyFatPctTolerance,
                    onTargetBodyFatPctToleranceChange = {
                        targetBodyFatPctTolerance = it
                        validationError = null
                        vm.clearSaveError()
                    },
                    saving = saving,
                    error = validationError ?: saveError,
                    onSave = {
                        val req = buildGoalSettingsUpdate(
                            targetBodyweight = targetBodyweight,
                            targetBodyweightTolerance = targetBodyweightTolerance,
                            targetBodyFatPct = targetBodyFatPct,
                            targetBodyFatPctTolerance = targetBodyFatPctTolerance,
                        )
                        if (req == null) {
                            validationError = "Enter numeric values only."
                        } else {
                            validationError = null
                            vm.save(req)
                        }
                    },
                )
                is SettingsUiState.HasGoal -> SettingsBody(
                    goal = s.goal,
                    targetBodyweight = targetBodyweight,
                    onTargetBodyweightChange = {
                        targetBodyweight = it
                        validationError = null
                        vm.clearSaveError()
                    },
                    targetBodyweightTolerance = targetBodyweightTolerance,
                    onTargetBodyweightToleranceChange = {
                        targetBodyweightTolerance = it
                        validationError = null
                        vm.clearSaveError()
                    },
                    targetBodyFatPct = targetBodyFatPct,
                    onTargetBodyFatPctChange = {
                        targetBodyFatPct = it
                        validationError = null
                        vm.clearSaveError()
                    },
                    targetBodyFatPctTolerance = targetBodyFatPctTolerance,
                    onTargetBodyFatPctToleranceChange = {
                        targetBodyFatPctTolerance = it
                        validationError = null
                        vm.clearSaveError()
                    },
                    saving = saving,
                    error = validationError ?: saveError,
                    onSave = {
                        val req = buildGoalSettingsUpdate(
                            targetBodyweight = targetBodyweight,
                            targetBodyweightTolerance = targetBodyweightTolerance,
                            targetBodyFatPct = targetBodyFatPct,
                            targetBodyFatPctTolerance = targetBodyFatPctTolerance,
                        )
                        if (req == null) {
                            validationError = "Enter numeric values only."
                        } else {
                            validationError = null
                            vm.save(req)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsBody(
    goal: GoalSettingsOut?,
    targetBodyweight: String,
    onTargetBodyweightChange: (String) -> Unit,
    targetBodyweightTolerance: String,
    onTargetBodyweightToleranceChange: (String) -> Unit,
    targetBodyFatPct: String,
    onTargetBodyFatPctChange: (String) -> Unit,
    targetBodyFatPctTolerance: String,
    onTargetBodyFatPctToleranceChange: (String) -> Unit,
    saving: Boolean,
    error: String?,
    onSave: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Goals", style = MaterialTheme.typography.titleLarge)
        if (goal == null) {
            Text(
                "No goal set.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "Updated ${goal.updated_at}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        GoalNumberField(
            value = targetBodyweight,
            onValueChange = onTargetBodyweightChange,
            label = "Target bodyweight",
        )
        GoalNumberField(
            value = targetBodyweightTolerance,
            onValueChange = onTargetBodyweightToleranceChange,
            label = "Bodyweight tolerance",
        )
        GoalNumberField(
            value = targetBodyFatPct,
            onValueChange = onTargetBodyFatPctChange,
            label = "Target body fat (%)",
        )
        GoalNumberField(
            value = targetBodyFatPctTolerance,
            onValueChange = onTargetBodyFatPctToleranceChange,
            label = "Body fat tolerance (%)",
        )
        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = onSave,
            enabled = !saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    saving -> "Saving"
                    goal == null -> "Set a goal"
                    else -> "Save goal"
                },
            )
        }
    }
}

@Composable
private fun GoalNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
    )
}

private fun formatGoalNumber(value: Double?): String =
    value?.let {
        if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString()
    }.orEmpty()

internal fun buildGoalSettingsUpdate(
    targetBodyweight: String,
    targetBodyweightTolerance: String,
    targetBodyFatPct: String,
    targetBodyFatPctTolerance: String,
): GoalSettingsIn? {
    val targetBodyweightTrimmed = targetBodyweight.trim()
    val parsedTargetBodyweight = if (targetBodyweightTrimmed.isEmpty()) {
        null
    } else {
        targetBodyweightTrimmed.toDoubleOrNull() ?: return null
    }

    val targetBodyweightToleranceTrimmed = targetBodyweightTolerance.trim()
    val parsedTargetBodyweightTolerance = if (targetBodyweightToleranceTrimmed.isEmpty()) {
        null
    } else {
        targetBodyweightToleranceTrimmed.toDoubleOrNull() ?: return null
    }

    val targetBodyFatPctTrimmed = targetBodyFatPct.trim()
    val parsedTargetBodyFatPct = if (targetBodyFatPctTrimmed.isEmpty()) {
        null
    } else {
        targetBodyFatPctTrimmed.toDoubleOrNull() ?: return null
    }

    val targetBodyFatPctToleranceTrimmed = targetBodyFatPctTolerance.trim()
    val parsedTargetBodyFatPctTolerance = if (targetBodyFatPctToleranceTrimmed.isEmpty()) {
        null
    } else {
        targetBodyFatPctToleranceTrimmed.toDoubleOrNull() ?: return null
    }

    return GoalSettingsIn(
        target_bodyweight = parsedTargetBodyweight,
        target_bodyweight_tolerance = parsedTargetBodyweightTolerance,
        target_body_fat_pct = parsedTargetBodyFatPct,
        target_body_fat_pct_tolerance = parsedTargetBodyFatPctTolerance,
    )
}
