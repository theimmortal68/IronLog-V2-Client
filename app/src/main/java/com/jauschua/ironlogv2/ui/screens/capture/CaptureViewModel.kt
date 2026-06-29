package com.jauschua.ironlogv2.ui.screens.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jauschua.ironlogv2.IronLogV2Application
import com.jauschua.ironlogv2.data.api.IronLogException
import com.jauschua.ironlogv2.data.api.humanMessage
import com.jauschua.ironlogv2.data.api.dto.SessionDetailResponse
import com.jauschua.ironlogv2.data.local.SetLogDraft
import com.jauschua.ironlogv2.data.repo.CaptureRepo
import com.jauschua.ironlogv2.ui.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val TAP_REQUIRED = setOf("WORKING", "TOP", "BACKOFF")

class CaptureViewModel(
    private val repo: CaptureRepo,
    /** Mutable so [load] can set it from today's session; tests inject a known id directly. */
    private var sessionId: Int,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<SessionDetailResponse?>>(UiState.Loading)
    val state: StateFlow<UiState<SessionDetailResponse?>> = _state.asStateFlow()

    private val _uiError = MutableStateFlow<String?>(null)
    val uiError: StateFlow<String?> = _uiError.asStateFlow()

    private val _nextSetIndex = MutableStateFlow(0)
    val nextSetIndex: StateFlow<Int> = _nextSetIndex.asStateFlow()

    private val _submitResult = MutableStateFlow<String?>(null)
    val submitResult: StateFlow<String?> = _submitResult.asStateFlow()

    /**
     * Load today's planned session.  Called from the screen's [LaunchedEffect] on entry.
     * Sets [sessionId] from the loaded session so [logWorkingSet]/[finish] use the correct id.
     * Tests inject [sessionId] directly and never call this, so they are unaffected.
     */
    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            repo.today()
                .onSuccess { session ->
                    if (session != null) sessionId = session.id
                    _state.value = UiState.Success(session)
                }
                .onFailure { e ->
                    val msg = (e as? IronLogException)?.error?.humanMessage()
                        ?: e.message ?: "Unknown error"
                    _state.value = UiState.Error(msg)
                }
        }
    }

    /**
     * Write-before-advance entry point.
     *
     * Mandatory-tap gate: a working role (WORKING / TOP / BACKOFF) with a null tap sets
     * [uiError] and returns early — no Room write, no index advance.
     *
     * Write-before-advance ordering: for valid sets, this suspend function *awaits*
     * [CaptureRepo.logSet] (which calls the Room @Insert suspend — commits before returning)
     * and only THEN mutates [_nextSetIndex].  There is no `launch` here; the Room commit is
     * inline in this coroutine.  When this function returns to the caller, the durable row is
     * guaranteed to exist.  A process kill after the caller resumes cannot lose the set.
     *
     * In production Room dispatches @Insert to a ThreadPoolExecutor (IO thread).  A
     * fire-and-forget implementation (`viewModelScope.launch { repo.logSet(...) }; advance()`)
     * would return control to the caller with nextSetIndex advanced but the write still pending
     * on the IO thread — a crash at that moment loses the set.  The await here closes that gap.
     */
    suspend fun logWorkingSet(
        plannedSetId: Int?,
        movementId: Int,
        setIndex: Int,
        setRole: String,
        actualLoad: Double?,
        actualReps: Int?,
        tap: String?,
        isWarmup: Boolean = false,
    ) {
        if (setRole in TAP_REQUIRED && tap == null) {
            _uiError.value = "Tap required before continuing"
            return
        }
        _uiError.value = null
        // AWAIT the Room @Insert — suspends until the SQLite transaction commits.
        repo.logSet(
            SetLogDraft(
                sessionId = sessionId,
                plannedSetId = plannedSetId,
                movementId = movementId,
                setIndex = setIndex,
                setRole = setRole,
                isWarmup = isWarmup,
                actualLoad = actualLoad,
                actualReps = actualReps,
                feedbackTap = tap,
            ),
        )
        // Advance ONLY after the commit has returned — write-before-advance enforced.
        _nextSetIndex.value = setIndex + 1
    }

    /** Batch-submit all pending drafts. Idempotent — drafts persist across retries. */
    fun finish() {
        viewModelScope.launch {
            repo.submit(sessionId)
                .onSuccess { _submitResult.value = it.status }
                .onFailure { _submitResult.value = "RETRY" }
        }
    }

    companion object {
        /**
         * No-arg factory for the Capture bottom-nav destination.
         * The real session id is resolved inside [load] once today's session is fetched.
         */
        val TodayFactory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as IronLogV2Application
                CaptureViewModel(repo = app.container.captureRepo, sessionId = 0)
            }
        }

        /** Scoped factory — pass the session id from the nav arg. */
        fun factory(sessionId: Int): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as IronLogV2Application
                CaptureViewModel(
                    repo = app.container.captureRepo,
                    sessionId = sessionId,
                )
            }
        }
    }
}
