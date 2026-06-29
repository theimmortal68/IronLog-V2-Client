package com.jauschua.ironlogv2.ui.screens.wizard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jauschua.ironlogv2.IronLogV2Application
import com.jauschua.ironlogv2.data.api.IronLogException
import com.jauschua.ironlogv2.data.api.humanMessage
import com.jauschua.ironlogv2.data.api.dto.StartProgramResponse
import com.jauschua.ironlogv2.data.api.dto.WizardMovement
import com.jauschua.ironlogv2.data.api.dto.WizardResolution
import com.jauschua.ironlogv2.data.repo.WizardRepo
import com.jauschua.ironlogv2.ui.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Trust buckets returned by the server's wizard-state. The server owns trust computation
 *  (compute_load_trust); the client only routes display/prefill by these labels. */
private const val TRUST_FRESH = "FRESH"
private const val TRUST_STALE = "STALE"

/**
 * Loaded wizard view data.
 *
 * [actionMovements] are the movements that need attention (trust != FRESH): UNKNOWN ones to fill,
 * STALE ones to confirm/adjust. [freshMovements] are summarized only — they need no input.
 *
 * The live needs-attention count and ready-to-start gate are NOT kept here; they live in their own
 * StateFlows ([needsAttentionCount]/[readyToStart]) so a resolve can update the single server-driven
 * source of truth without rebuilding this snapshot.
 */
data class WizardUi(
    val programId: Int,
    val programName: String,
    val actionMovements: List<WizardMovement> = emptyList(),
    val freshMovements: List<WizardMovement> = emptyList(),
)

class WizardViewModel(
    private val repo: WizardRepo,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<WizardUi>>(UiState.Loading)
    val state: StateFlow<UiState<WizardUi>> = _state.asStateFlow()

    /** Per-movement entered/confirmed text, keyed by movement_id. UNKNOWN → empty to fill;
     *  STALE → prefilled with prefill_value to confirm/adjust. */
    private val _entered = MutableStateFlow<Map<Int, String>>(emptyMap())
    val entered: StateFlow<Map<Int, String>> = _entered.asStateFlow()

    /** Live "N left" — the server's recomputed needs_attention_count (source of truth). */
    private val _needsAttentionCount = MutableStateFlow(0)
    val needsAttentionCount: StateFlow<Int> = _needsAttentionCount.asStateFlow()

    /** The completion gate — the server's ready_to_start. Never recomputed client-side. */
    private val _readyToStart = MutableStateFlow(false)
    val readyToStart: StateFlow<Boolean> = _readyToStart.asStateFlow()

    private val _submitError = MutableStateFlow<String?>(null)
    val submitError: StateFlow<String?> = _submitError.asStateFlow()

    /** Set on a successful start so the screen can navigate away. */
    private val _startResult = MutableStateFlow<StartProgramResponse?>(null)
    val startResult: StateFlow<StartProgramResponse?> = _startResult.asStateFlow()

    /** Load the program's wizard-state. Called from the screen's LaunchedEffect on entry. */
    fun load(programId: Int) {
        _state.value = UiState.Loading
        _submitError.value = null
        viewModelScope.launch {
            repo.state(programId)
                .onSuccess { resp ->
                    val action = resp.movements.filter { it.trust != TRUST_FRESH }
                    val fresh = resp.movements.filter { it.trust == TRUST_FRESH }
                    // Prefill: STALE → prefill_value (confirm/adjust); UNKNOWN → empty (fill).
                    _entered.value = action.associate { m ->
                        m.movement_id to if (m.trust == TRUST_STALE) {
                            m.prefill_value?.toString().orEmpty()
                        } else {
                            ""
                        }
                    }
                    _needsAttentionCount.value = resp.needs_attention_count
                    _readyToStart.value = resp.ready_to_start
                    _state.value = UiState.Success(
                        WizardUi(
                            programId = resp.program_id,
                            programName = resp.program_name,
                            actionMovements = action,
                            freshMovements = fresh,
                        ),
                    )
                }
                .onFailure { e ->
                    val msg = (e as? IronLogException)?.error?.humanMessage()
                        ?: e.message ?: "Unknown error"
                    _state.value = UiState.Error(msg)
                }
        }
    }

    /** Record/adjust the user's entry for a movement. */
    fun setEntered(movementId: Int, value: String) {
        _entered.update { it + (movementId to value) }
        _submitError.value = null
    }

    /**
     * Batch-resolve the touched movements. Only movements with a non-blank entered value are sent
     * (UNKNOWN ones the user filled, STALE ones prefilled/confirmed). The server recomputes trust
     * and returns the authoritative needs_attention_count / ready_to_start, which we adopt verbatim.
     */
    fun resolve() {
        val cur = _state.value
        if (cur !is UiState.Success) return
        val programId = cur.data.programId

        val resolutions = mutableListOf<WizardResolution>()
        for ((movementId, raw) in _entered.value) {
            val text = raw.trim()
            if (text.isEmpty()) continue // untouched / not filled — skip
            val value = text.toDoubleOrNull()
            if (value == null) {
                _submitError.value = "Entered value must be a number"
                return
            }
            resolutions.add(WizardResolution(movement_id = movementId, value = value))
        }
        if (resolutions.isEmpty()) return

        _submitError.value = null
        viewModelScope.launch {
            repo.resolve(programId, resolutions)
                .onSuccess { resp ->
                    _needsAttentionCount.value = resp.needs_attention_count
                    _readyToStart.value = resp.ready_to_start
                }
                .onFailure { e ->
                    val msg = (e as? IronLogException)?.error?.humanMessage()
                        ?: e.message ?: "Unknown error"
                    _submitError.value = msg
                }
        }
    }

    /** Activate the program. No-op unless the server has flagged ready_to_start. */
    fun start() {
        if (!_readyToStart.value) return
        val cur = _state.value
        if (cur !is UiState.Success) return
        val programId = cur.data.programId

        _submitError.value = null
        viewModelScope.launch {
            repo.start(programId)
                .onSuccess { resp -> _startResult.value = resp }
                .onFailure { e ->
                    val msg = (e as? IronLogException)?.error?.humanMessage()
                        ?: e.message ?: "Unknown error"
                    _submitError.value = msg
                }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as IronLogV2Application
                WizardViewModel(repo = app.container.wizardRepo)
            }
        }
    }
}
