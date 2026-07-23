// TodayViewModel.kt
package com.jauschua.ironlogv2.ui.screens.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jauschua.ironlogv2.IronLogV2Application
import com.jauschua.ironlogv2.data.api.IronLogException
import com.jauschua.ironlogv2.data.api.dto.CardioWeeklySummaryOut
import com.jauschua.ironlogv2.data.api.dto.DailyReadinessIn
import com.jauschua.ironlogv2.data.api.dto.DailyReadinessOut
import com.jauschua.ironlogv2.data.api.dto.SessionDetailResponse
import com.jauschua.ironlogv2.data.api.dto.WeakPointAssessmentOut
import com.jauschua.ironlogv2.data.api.humanMessage
import com.jauschua.ironlogv2.data.repo.CardioLogRepo
import com.jauschua.ironlogv2.data.repo.CaptureRepo
import com.jauschua.ironlogv2.data.repo.GenerateRepo
import com.jauschua.ironlogv2.data.repo.NotesRepo
import com.jauschua.ironlogv2.data.repo.ReadinessRepo
import com.jauschua.ironlogv2.data.repo.WeakPointsRepo
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

/** Label for the Today screen's Review button: bare "Review" when there's nothing pending, or
 *  "Review (N)" once a classified note is waiting — pure and file-level so it's unit-testable
 *  without Compose or the ViewModel. */
fun reviewButtonLabel(count: Int): String =
    if (count > 0) "Review ($count)" else "Review"

/** True once both subjective fields are answered for today -- the card collapses to a compact
 *  summary. Pure and file-level so it's unit-testable without the ViewModel. */
fun hasCheckedInToday(readiness: DailyReadinessOut?): Boolean =
    readiness != null && readiness.sleep_ok != null && readiness.subjective_ok != null

/** Total weak movements across all muscle groups, for the Today badge. Zero (or a null
 *  assessment) means no badge renders. Pure and file-level so it's unit-testable without
 *  Compose or the ViewModel. */
fun weakPointBadgeCount(assessment: WeakPointAssessmentOut?): Int =
    assessment?.muscle_groups?.sumOf { it.weak_count } ?: 0

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
    private val notesRepo: NotesRepo,
    private val cardioLogRepo: CardioLogRepo,
    private val readinessRepo: ReadinessRepo,
    private val weakPointsRepo: WeakPointsRepo,
    private val pendingPhaseTransitionContainerFlow: MutableStateFlow<String?>,
) : ViewModel() {

    private val _state = MutableStateFlow<TodayUiState>(TodayUiState.Loading)
    val state: StateFlow<TodayUiState> = _state.asStateFlow()

    private val _reviewCount = MutableStateFlow(0)
    val reviewCount: StateFlow<Int> = _reviewCount.asStateFlow()

    private val _cardioWeeklySummary = MutableStateFlow<CardioWeeklySummaryOut?>(null)
    val cardioWeeklySummary: StateFlow<CardioWeeklySummaryOut?> = _cardioWeeklySummary.asStateFlow()

    private val _readiness = MutableStateFlow<DailyReadinessOut?>(null)
    val readiness: StateFlow<DailyReadinessOut?> = _readiness.asStateFlow()

    private val _weakPointsSummary = MutableStateFlow<WeakPointAssessmentOut?>(null)
    val weakPointsSummary: StateFlow<WeakPointAssessmentOut?> = _weakPointsSummary.asStateFlow()

    private val _pendingPhaseTransition = MutableStateFlow<String?>(null)
    val pendingPhaseTransition: StateFlow<String?> = _pendingPhaseTransition.asStateFlow()

    /** Load the current picture: an already-planned session takes priority (Continue); otherwise
     *  fall back to the program's day list so the lifter can pick one to generate. Also refreshes
     *  the pending-review count (best-effort — never surfaces an error for this). */
    fun load() {
        _state.value = TodayUiState.Loading
        _pendingPhaseTransition.value = pendingPhaseTransitionContainerFlow.value
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
        refreshReviewCount()
        refreshCardioSummary()
        refreshReadiness()
        refreshWeakPoints()
    }

    /** Best-effort fetch of the pending-note count for the Review button badge. Leaves the count
     *  at its previous value (default 0) on failure — never surfaces an error. */
    private fun refreshReviewCount() {
        viewModelScope.launch {
            notesRepo.review()
                .onSuccess { notes -> _reviewCount.value = notes.size }
        }
    }

    private fun refreshCardioSummary() {
        viewModelScope.launch {
            cardioLogRepo.weeklySummary()
                .onSuccess { summary -> _cardioWeeklySummary.value = summary }
        }
    }

    private fun refreshReadiness() {
        viewModelScope.launch {
            readinessRepo.today()
                .onSuccess { r -> _readiness.value = r }
        }
    }

    private fun refreshWeakPoints() {
        viewModelScope.launch {
            weakPointsRepo.assessment()
                .onSuccess { a -> _weakPointsSummary.value = a }
        }
    }

    fun checkIn(sleepOk: Boolean?, subjectiveOk: Boolean?, restingHr: Double?) {
        viewModelScope.launch {
            readinessRepo.checkIn(
                DailyReadinessIn(
                    sleep_ok = sleepOk,
                    subjective_ok = subjectiveOk,
                    resting_hr = restingHr,
                ),
            )
                .onSuccess { r -> _readiness.value = r }
        }
    }

    fun confirmPhaseTransition() {
        val phase = _pendingPhaseTransition.value ?: return
        _pendingPhaseTransition.value = null
        pendingPhaseTransitionContainerFlow.value = null
        viewModelScope.launch {
            readinessRepo.confirmPhase(phase)
        }
    }

    fun dismissPhaseTransitionBanner() {
        _pendingPhaseTransition.value = null
        pendingPhaseTransitionContainerFlow.value = null
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
                    notesRepo = app.container.notesRepo,
                    cardioLogRepo = app.container.cardioLogRepo,
                    readinessRepo = app.container.readinessRepo,
                    weakPointsRepo = app.container.weakPointsRepo,
                    pendingPhaseTransitionContainerFlow = app.container.pendingPhaseTransition,
                )
            }
        }
    }
}
