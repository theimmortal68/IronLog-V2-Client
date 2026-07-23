package com.jauschua.ironlogv2.ui.screens.misseddays

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jauschua.ironlogv2.IronLogV2Application
import com.jauschua.ironlogv2.data.api.IronLogException
import com.jauschua.ironlogv2.data.api.dto.MissedDayRecordOut
import com.jauschua.ironlogv2.data.api.humanMessage
import com.jauschua.ironlogv2.data.repo.MissedDaysRepo
import com.jauschua.ironlogv2.ui.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MissedDaysViewModel(
    private val missedDaysRepo: MissedDaysRepo,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<MissedDayRecordOut>>>(UiState.Loading)
    val state: StateFlow<UiState<List<MissedDayRecordOut>>> = _state.asStateFlow()

    init { reload() }

    fun reload() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            missedDaysRepo.list()
                .onSuccess { _state.value = UiState.Success(it) }
                .onFailure { e -> _state.value = UiState.Error(errorMessage(e)) }
        }
    }

    fun acknowledge(recordId: Int) {
        viewModelScope.launch {
            missedDaysRepo.acknowledge(recordId)
                .onSuccess { updateRecord(it) }
                .onFailure { e -> _state.value = UiState.Error(errorMessage(e)) }
        }
    }

    fun reschedule(recordId: Int) {
        viewModelScope.launch {
            missedDaysRepo.reschedule(recordId)
                .onSuccess { updateRecord(it) }
                .onFailure { e -> _state.value = UiState.Error(errorMessage(e)) }
        }
    }

    private fun updateRecord(record: MissedDayRecordOut) {
        val current = _state.value
        if (current is UiState.Success) {
            _state.value = UiState.Success(replaceMissedDayRecord(current.data, record))
        }
    }

    private fun errorMessage(e: Throwable): String =
        (e as? IronLogException)?.error?.humanMessage() ?: e.message ?: "Unknown error"

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as IronLogV2Application
                MissedDaysViewModel(app.container.missedDaysRepo)
            }
        }
    }
}

internal fun replaceMissedDayRecord(
    records: List<MissedDayRecordOut>,
    updated: MissedDayRecordOut,
): List<MissedDayRecordOut> =
    records.map { if (it.id == updated.id) updated else it }
