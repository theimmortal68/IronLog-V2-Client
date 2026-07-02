// HistoryViewModel.kt
package com.jauschua.ironlogv2.ui.screens.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jauschua.ironlogv2.IronLogV2Application
import com.jauschua.ironlogv2.data.api.IronLogException
import com.jauschua.ironlogv2.data.api.dto.LoggedSet
import com.jauschua.ironlogv2.data.api.dto.LoggedSetsResponse
import com.jauschua.ironlogv2.data.api.dto.SessionSummary
import com.jauschua.ironlogv2.data.api.humanMessage
import com.jauschua.ironlogv2.data.repo.GenerateRepo
import com.jauschua.ironlogv2.ui.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A single movement's logged sets from a session, grouped in first-appearance order. */
data class MovementLogs(val movementId: Int, val movementName: String, val sets: List<LoggedSet>)

/** Group flat logs by movement, preserving first-appearance order (linked map). */
fun groupLogsByMovement(logs: List<LoggedSet>): List<MovementLogs> {
    val byMovement = LinkedHashMap<Int, MutableList<LoggedSet>>()
    for (l in logs) byMovement.getOrPut(l.movement_id) { mutableListOf() }.add(l)
    return byMovement.values.map { MovementLogs(it.first().movement_id, it.first().movement_name, it.toList()) }
}

/** History list screen: past completed sessions. */
class HistoryViewModel(
    private val generateRepo: GenerateRepo,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<SessionSummary>>>(UiState.Loading)
    val state: StateFlow<UiState<List<SessionSummary>>> = _state.asStateFlow()

    init { reload() }

    fun reload() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            generateRepo.pastSessions()
                .onSuccess { _state.value = UiState.Success(it) }
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
                HistoryViewModel(app.container.generateRepo)
            }
        }
    }
}

/** History detail screen: one session's logged actuals. [detail] is called from the screen's
 *  LaunchedEffect(id) on entry (same pattern as WizardViewModel.load(programId)). */
class HistoryDetailViewModel(
    private val generateRepo: GenerateRepo,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<LoggedSetsResponse>>(UiState.Loading)
    val state: StateFlow<UiState<LoggedSetsResponse>> = _state.asStateFlow()

    private var lastId: Int? = null

    fun detail(id: Int) {
        lastId = id
        _state.value = UiState.Loading
        viewModelScope.launch {
            generateRepo.sessionLogs(id)
                .onSuccess { _state.value = UiState.Success(it) }
                .onFailure { e -> _state.value = UiState.Error(errorMessage(e)) }
        }
    }

    fun retry() {
        lastId?.let { detail(it) }
    }

    private fun errorMessage(e: Throwable): String =
        (e as? IronLogException)?.error?.humanMessage() ?: e.message ?: "Unknown error"

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as IronLogV2Application
                HistoryDetailViewModel(app.container.generateRepo)
            }
        }
    }
}
