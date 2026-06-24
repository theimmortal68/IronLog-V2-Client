package com.jauschua.ironlogv2.ui.screens.movements

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jauschua.ironlogv2.IronLogV2Application
import com.jauschua.ironlogv2.data.api.IronLogException
import com.jauschua.ironlogv2.data.api.humanMessage
import com.jauschua.ironlogv2.data.api.dto.MovementDto
import com.jauschua.ironlogv2.data.repo.LibraryRepo
import com.jauschua.ironlogv2.ui.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MovementsListViewModel(private val libraryRepo: LibraryRepo) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<MovementDto>>>(UiState.Loading)
    val state: StateFlow<UiState<List<MovementDto>>> = _state.asStateFlow()

    init { reload() }

    fun reload() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            libraryRepo.movements()
                .onSuccess { _state.value = UiState.Success(it) }
                .onFailure { e ->
                    val msg = (e as? IronLogException)?.error?.humanMessage() ?: "Unknown error"
                    _state.value = UiState.Error(msg)
                }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as IronLogV2Application
                MovementsListViewModel(app.container.libraryRepo)
            }
        }
    }
}
