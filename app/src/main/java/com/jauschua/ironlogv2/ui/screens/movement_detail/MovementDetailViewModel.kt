package com.jauschua.ironlogv2.ui.screens.movement_detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
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

class MovementDetailViewModel(
    private val libraryRepo: LibraryRepo,
    private val movementId: Int,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<MovementDto>>(UiState.Loading)
    val state: StateFlow<UiState<MovementDto>> = _state.asStateFlow()

    init { reload() }

    fun reload() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            libraryRepo.getMovement(movementId)
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
                val handle: SavedStateHandle = createSavedStateHandle()
                val id = handle.get<String>("id")?.toIntOrNull()
                    ?: error("MovementDetailViewModel requires nav arg 'id'")
                MovementDetailViewModel(app.container.libraryRepo, id)
            }
        }
    }
}
