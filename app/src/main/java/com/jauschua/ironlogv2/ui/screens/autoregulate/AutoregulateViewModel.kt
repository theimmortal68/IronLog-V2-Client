package com.jauschua.ironlogv2.ui.screens.autoregulate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jauschua.ironlogv2.IronLogV2Application
import com.jauschua.ironlogv2.data.api.IronLogException
import com.jauschua.ironlogv2.data.api.humanMessage
import com.jauschua.ironlogv2.data.api.dto.FeedbackTap
import com.jauschua.ironlogv2.data.api.dto.MovementDto
import com.jauschua.ironlogv2.data.api.dto.NextSetRequest
import com.jauschua.ironlogv2.data.api.dto.NextSetResponse
import com.jauschua.ironlogv2.data.api.dto.ProgressionMode
import com.jauschua.ironlogv2.data.repo.AutoregRepo
import com.jauschua.ironlogv2.data.repo.LibraryRepo
import com.jauschua.ironlogv2.ui.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AutoregUi(
    val movements: List<MovementDto> = emptyList(),
    val selectedId: Int? = null,
    val currentLoad: String = "",
    val tap: FeedbackTap = FeedbackTap.ON_TARGET,
    val tier: Int = 0,
    val lastResult: NextSetResponse? = null,
    val submitError: String? = null,
    val submitting: Boolean = false,
)

class AutoregulateViewModel(
    private val libraryRepo: LibraryRepo,
    private val autoregRepo: AutoregRepo,
    private val prefillSource: kotlinx.coroutines.flow.MutableStateFlow<Int?>,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<AutoregUi>>(UiState.Loading)
    val state: StateFlow<UiState<AutoregUi>> = _state.asStateFlow()

    init { reload() }

    fun reload() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            libraryRepo.movements()
                .onSuccess { all ->
                    val ladder = all.filter { it.progression_mode == ProgressionMode.LADDER }
                    _state.value = UiState.Success(AutoregUi(movements = ladder))
                    // consume the pre-fill once, if present and valid
                    val prefill = prefillSource.value
                    if (prefill != null && ladder.any { it.id == prefill }) {
                        select(prefill)
                    }
                    prefillSource.value = null
                }
                .onFailure { e ->
                    val msg = (e as? IronLogException)?.error?.humanMessage() ?: "Unknown error"
                    _state.value = UiState.Error(msg)
                }
        }
    }

    private fun mutateSuccess(block: (AutoregUi) -> AutoregUi) {
        _state.update { s -> if (s is UiState.Success) UiState.Success(block(s.data)) else s }
    }

    fun select(id: Int) = mutateSuccess { ui ->
        val movement = ui.movements.firstOrNull { it.id == id } ?: return@mutateSuccess ui
        val initialLoad = movement.load_floor?.toString() ?: ui.currentLoad.ifEmpty { "100" }
        ui.copy(
            selectedId = id,
            currentLoad = initialLoad,
            tier = 0,
            lastResult = null,
            submitError = null,
        )
    }

    fun setCurrentLoad(s: String) = mutateSuccess { it.copy(currentLoad = s, submitError = null, lastResult = null) }
    fun setTap(t: FeedbackTap)    = mutateSuccess { it.copy(tap = t,        submitError = null, lastResult = null) }
    fun setTier(t: Int)           = mutateSuccess { it.copy(tier = t,       submitError = null, lastResult = null) }

    fun submit() {
        val cur = _state.value
        if (cur !is UiState.Success) return
        val ui = cur.data
        val id = ui.selectedId ?: return
        val load = ui.currentLoad.toDoubleOrNull()
        if (load == null) {
            mutateSuccess { it.copy(submitError = "Current load must be a number") }
            return
        }
        mutateSuccess { it.copy(submitting = true, submitError = null) }
        viewModelScope.launch {
            autoregRepo.nextSet(NextSetRequest(movement_id = id, current_load = load, tap = ui.tap, tier = ui.tier))
                .onSuccess { resp -> mutateSuccess { it.copy(submitting = false, lastResult = resp) } }
                .onFailure { e ->
                    val msg = (e as? IronLogException)?.error?.humanMessage() ?: "Unknown error"
                    mutateSuccess { it.copy(submitting = false, submitError = msg) }
                }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as IronLogV2Application
                AutoregulateViewModel(
                    libraryRepo = app.container.libraryRepo,
                    autoregRepo = app.container.autoregRepo,
                    prefillSource = app.container.autoregPrefill,
                )
            }
        }
    }
}
