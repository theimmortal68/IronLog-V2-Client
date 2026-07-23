package com.jauschua.ironlogv2.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jauschua.ironlogv2.IronLogV2Application
import com.jauschua.ironlogv2.data.api.IronLogException
import com.jauschua.ironlogv2.data.api.dto.GoalSettingsIn
import com.jauschua.ironlogv2.data.api.dto.GoalSettingsOut
import com.jauschua.ironlogv2.data.api.humanMessage
import com.jauschua.ironlogv2.data.repo.GoalsRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SettingsUiState {
    data object Loading : SettingsUiState
    data object NoGoalSet : SettingsUiState
    data class HasGoal(val goal: GoalSettingsOut) : SettingsUiState
    data class Error(val msg: String) : SettingsUiState
}

class SettingsViewModel(
    private val goalsRepo: GoalsRepo,
) : ViewModel() {

    private val _state = MutableStateFlow<SettingsUiState>(SettingsUiState.Loading)
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    init { reload() }

    fun reload() {
        _state.value = SettingsUiState.Loading
        _saveError.value = null
        viewModelScope.launch {
            goalsRepo.get()
                .onSuccess { goal ->
                    _state.value = if (goal == null) {
                        SettingsUiState.NoGoalSet
                    } else {
                        SettingsUiState.HasGoal(goal)
                    }
                }
                .onFailure { e -> _state.value = SettingsUiState.Error(errorMessage(e)) }
        }
    }

    fun save(req: GoalSettingsIn) {
        if (_saving.value) return
        _saving.value = true
        _saveError.value = null
        viewModelScope.launch {
            val result = goalsRepo.update(req)
            _saving.value = false
            result
                .onSuccess { goal -> _state.value = SettingsUiState.HasGoal(goal) }
                .onFailure { e -> _saveError.value = errorMessage(e) }
        }
    }

    fun clearSaveError() {
        _saveError.value = null
    }

    private fun errorMessage(e: Throwable): String =
        (e as? IronLogException)?.error?.humanMessage() ?: e.message ?: "Unknown error"

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as IronLogV2Application
                SettingsViewModel(app.container.goalsRepo)
            }
        }
    }
}
