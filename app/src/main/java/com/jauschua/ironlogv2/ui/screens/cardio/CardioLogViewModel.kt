package com.jauschua.ironlogv2.ui.screens.cardio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jauschua.ironlogv2.IronLogV2Application
import com.jauschua.ironlogv2.data.api.IronLogException
import com.jauschua.ironlogv2.data.api.dto.CardioLogCreate
import com.jauschua.ironlogv2.data.api.humanMessage
import com.jauschua.ironlogv2.data.repo.CardioLogRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface CardioLogUiState {
    data object Idle : CardioLogUiState
    data object Submitting : CardioLogUiState
    data object Submitted : CardioLogUiState
    data class Error(val msg: String) : CardioLogUiState
}

class CardioLogViewModel(
    private val cardioLogRepo: CardioLogRepo,
) : ViewModel() {

    private val _state = MutableStateFlow<CardioLogUiState>(CardioLogUiState.Idle)
    val state: StateFlow<CardioLogUiState> = _state.asStateFlow()

    fun submit(req: CardioLogCreate) {
        _state.value = CardioLogUiState.Submitting
        viewModelScope.launch {
            cardioLogRepo.create(req)
                .onSuccess { _state.value = CardioLogUiState.Submitted }
                .onFailure { e -> _state.value = CardioLogUiState.Error(errorMessage(e)) }
        }
    }

    fun resetToIdle() { _state.value = CardioLogUiState.Idle }

    private fun errorMessage(e: Throwable): String =
        (e as? IronLogException)?.error?.humanMessage() ?: e.message ?: "Unknown error"

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as IronLogV2Application
                CardioLogViewModel(app.container.cardioLogRepo)
            }
        }
    }
}
