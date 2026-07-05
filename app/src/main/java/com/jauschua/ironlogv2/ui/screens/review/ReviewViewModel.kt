// ReviewViewModel.kt
package com.jauschua.ironlogv2.ui.screens.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jauschua.ironlogv2.IronLogV2Application
import com.jauschua.ironlogv2.data.api.IronLogException
import com.jauschua.ironlogv2.data.api.dto.NoteReviewOut
import com.jauschua.ironlogv2.data.api.humanMessage
import com.jauschua.ironlogv2.data.repo.NotesRepo
import com.jauschua.ironlogv2.ui.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Review tab: unconfirmed change-proposals extracted from session notes. Confirm/dismiss only
 *  flip server-side flags — the client never mutates program state directly. */
class ReviewViewModel(
    private val notesRepo: NotesRepo,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<NoteReviewOut>>>(UiState.Loading)
    val state: StateFlow<UiState<List<NoteReviewOut>>> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            notesRepo.review()
                .onSuccess { _state.value = UiState.Success(it) }
                .onFailure { e -> _state.value = UiState.Error(errorMessage(e)) }
        }
    }

    fun confirm(id: Int) {
        viewModelScope.launch {
            notesRepo.confirm(id).onSuccess { load() }
                .onFailure { e -> _state.value = UiState.Error(errorMessage(e)) }
        }
    }

    fun dismiss(id: Int) {
        viewModelScope.launch {
            notesRepo.dismiss(id).onSuccess { load() }
                .onFailure { e -> _state.value = UiState.Error(errorMessage(e)) }
        }
    }

    private fun errorMessage(e: Throwable): String =
        (e as? IronLogException)?.error?.humanMessage() ?: e.message ?: "Unknown error"

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as IronLogV2Application
                ReviewViewModel(app.container.notesRepo)
            }
        }
    }
}
