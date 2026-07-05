// ReviewViewModel.kt
package com.jauschua.ironlogv2.ui.screens.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jauschua.ironlogv2.IronLogV2Application
import com.jauschua.ironlogv2.data.api.IronLogException
import com.jauschua.ironlogv2.data.api.dto.MovementDto
import com.jauschua.ironlogv2.data.api.dto.NoteReviewOut
import com.jauschua.ironlogv2.data.api.dto.OverrideOut
import com.jauschua.ironlogv2.data.api.humanMessage
import com.jauschua.ironlogv2.data.repo.LibraryRepo
import com.jauschua.ironlogv2.data.repo.NotesRepo
import com.jauschua.ironlogv2.ui.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Review tab: unconfirmed change-proposals extracted from session notes. Confirm/dismiss only
 *  flip server-side flags. `apply` resolves a CONFIG_CHANGE proposal to a concrete movement via
 *  the deterministic `/notes/{id}/apply` endpoint (server creates the slot override — this VM
 *  never mutates program state itself); the active-swaps list (`/overrides`) can be reverted. */
class ReviewViewModel(
    private val notesRepo: NotesRepo,
    private val libraryRepo: LibraryRepo,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<NoteReviewOut>>>(UiState.Loading)
    val state: StateFlow<UiState<List<NoteReviewOut>>> = _state.asStateFlow()

    private val _overrides = MutableStateFlow<List<OverrideOut>>(emptyList())
    val overrides: StateFlow<List<OverrideOut>> = _overrides.asStateFlow()

    private val _movements = MutableStateFlow<List<MovementDto>>(emptyList())
    val movements: StateFlow<List<MovementDto>> = _movements.asStateFlow()

    private val _applyError = MutableStateFlow<String?>(null)
    val applyError: StateFlow<String?> = _applyError.asStateFlow()

    init {
        load()
        loadOverrides()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            notesRepo.review()
                .onSuccess { _state.value = UiState.Success(it) }
                .onFailure { e -> _state.value = UiState.Error(errorMessage(e)) }
        }
    }

    fun loadOverrides() {
        viewModelScope.launch {
            notesRepo.overrides().onSuccess { _overrides.value = it }
        }
    }

    /** Lazily fetch the movement library for the swap picker — only needed once per session. */
    fun loadMovementsIfNeeded() {
        if (_movements.value.isNotEmpty()) return
        viewModelScope.launch {
            libraryRepo.movements().onSuccess { _movements.value = it }
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

    /** Apply a CONFIG_CHANGE proposal to a specific movement the user picked. Reloads both the
     *  pending list (the note is now confirmed+applied) and the active-swaps list on success. */
    fun apply(noteId: Int, targetMovementId: Int) {
        viewModelScope.launch {
            notesRepo.apply(noteId, targetMovementId)
                .onSuccess {
                    load()
                    loadOverrides()
                }
                .onFailure { e -> _applyError.value = errorMessage(e) }
        }
    }

    fun revert(overrideId: Int) {
        viewModelScope.launch {
            notesRepo.revert(overrideId)
                .onSuccess { loadOverrides() }
                .onFailure { e -> _applyError.value = errorMessage(e) }
        }
    }

    fun clearApplyError() {
        _applyError.value = null
    }

    private fun errorMessage(e: Throwable): String =
        (e as? IronLogException)?.error?.humanMessage() ?: e.message ?: "Unknown error"

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as IronLogV2Application
                ReviewViewModel(app.container.notesRepo, app.container.libraryRepo)
            }
        }
    }
}
