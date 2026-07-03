// TodayViewModel.kt
package com.jauschua.ironlogv2.ui.screens.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jauschua.ironlogv2.IronLogV2Application
import com.jauschua.ironlogv2.data.api.IronLogException
import com.jauschua.ironlogv2.data.api.dto.SessionDetailResponse
import com.jauschua.ironlogv2.data.api.humanMessage
import com.jauschua.ironlogv2.data.repo.CaptureRepo
import com.jauschua.ironlogv2.data.repo.GenerateRepo
import com.jauschua.ironlogv2.ui.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Whether a generate response can be reviewed by the lifter or must be treated as a failure. */
enum class GenerateOutcomeKind { REVIEWABLE, ERROR }

/**
 * A generate result is reviewable only when it did not exhaust AND carries a preview.
 *
 * Pure, file-level, and side-effect-free so it's unit-testable without Compose or the ViewModel —
 * [TodayViewModel.generate] is the only caller.
 */
fun classifyGenerate(exhausted: Boolean, hasPreview: Boolean): GenerateOutcomeKind =
    if (!exhausted && hasPreview) GenerateOutcomeKind.REVIEWABLE else GenerateOutcomeKind.ERROR

/** Today tab state machine: pick a day (if none is already planned) → generate → review the
 *  preview → approve → hand off to Capture. */
sealed interface TodayUiState {
    data object Loading : TodayUiState
    data class HasPlanned(val session: SessionDetailResponse) : TodayUiState
    data class NoSession(val days: List<String>) : TodayUiState
    data object Generating : TodayUiState
    data object Approving : TodayUiState
    data class Preview(val candidateId: String, val preview: SessionDetailResponse) : TodayUiState
    data class GenerateError(val msg: String) : TodayUiState
    data class Approved(val sessionId: Int) : TodayUiState
}

class TodayViewModel(
    private val generateRepo: GenerateRepo,
    private val captureRepo: CaptureRepo,
) : ViewModel() {

    private val _state = MutableStateFlow<TodayUiState>(TodayUiState.Loading)
    val state: StateFlow<TodayUiState> = _state.asStateFlow()

    /** Load the current picture: an already-planned session takes priority (Continue); otherwise
     *  fall back to the program's day list so the lifter can pick one to generate. */
    fun load() {
        _state.value = TodayUiState.Loading
        viewModelScope.launch {
            captureRepo.today()
                .onSuccess { session ->
                    if (session != null) {
                        _state.value = TodayUiState.HasPlanned(session)
                    } else {
                        generateRepo.programDays(Routes.DEFAULT_PROGRAM_ID)
                            .onSuccess { days -> _state.value = TodayUiState.NoSession(days) }
                            .onFailure { e -> _state.value = TodayUiState.GenerateError(errorMessage(e)) }
                    }
                }
                .onFailure { e -> _state.value = TodayUiState.GenerateError(errorMessage(e)) }
        }
    }

    /** Generate a candidate session for [dayRole]. Only a non-exhausted response carrying a
     *  preview is reviewable — see [classifyGenerate]. */
    fun generate(dayRole: String) {
        _state.value = TodayUiState.Generating
        viewModelScope.launch {
            generateRepo.generate(dayRole)
                .onSuccess { resp ->
                    _state.value = when (classifyGenerate(resp.exhausted, resp.preview != null)) {
                        GenerateOutcomeKind.REVIEWABLE ->
                            TodayUiState.Preview(resp.candidate_id, resp.preview!!)
                        GenerateOutcomeKind.ERROR ->
                            TodayUiState.GenerateError("No valid session could be generated — try again.")
                    }
                }
                .onFailure { e -> _state.value = TodayUiState.GenerateError(errorMessage(e)) }
        }
    }

    /** Approve the currently previewed candidate. No-op unless [state] is [TodayUiState.Preview]. */
    fun approve() {
        val cur = _state.value
        if (cur !is TodayUiState.Preview) return
        val candidateId = cur.candidateId
        _state.value = TodayUiState.Approving
        viewModelScope.launch {
            generateRepo.approve(candidateId)
                .onSuccess { resp -> _state.value = TodayUiState.Approved(resp.session_id) }
                .onFailure { e -> _state.value = TodayUiState.GenerateError(errorMessage(e)) }
        }
    }

    /** Re-run generation for [dayRole] (discards the current preview). */
    fun regenerate(dayRole: String) = generate(dayRole)

    private fun errorMessage(e: Throwable): String =
        (e as? IronLogException)?.error?.humanMessage() ?: e.message ?: "Unknown error"

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as IronLogV2Application
                TodayViewModel(
                    generateRepo = app.container.generateRepo,
                    captureRepo = app.container.captureRepo,
                )
            }
        }
    }
}
