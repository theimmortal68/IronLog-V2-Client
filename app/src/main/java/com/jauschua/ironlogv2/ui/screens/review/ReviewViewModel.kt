// ReviewViewModel.kt
package com.jauschua.ironlogv2.ui.screens.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jauschua.ironlogv2.IronLogV2Application
import com.jauschua.ironlogv2.data.api.IronLogException
import com.jauschua.ironlogv2.data.api.dto.ApplyOverrideRequest
import com.jauschua.ironlogv2.data.api.dto.MovementDto
import com.jauschua.ironlogv2.data.api.dto.NoteReviewOut
import com.jauschua.ironlogv2.data.api.dto.OverrideOut
import com.jauschua.ironlogv2.data.api.dto.ProgramSlotOut
import com.jauschua.ironlogv2.data.api.humanMessage
import com.jauschua.ironlogv2.data.repo.LibraryRepo
import com.jauschua.ironlogv2.data.repo.NotesRepo
import com.jauschua.ironlogv2.ui.Routes
import com.jauschua.ironlogv2.ui.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** State for the Apply confirm-wizard: the athlete confirms (or changes) the source slot, then
 *  supplies the action-routed adjustment. `slots`/`slotsLoading` cover the `/programs/{id}/slots`
 *  fetch; `selectedSlot` defaults from the note's subject (`defaultSourceSlot`) but is editable. */
data class ApplyWizardState(
    val note: NoteReviewOut,
    val kind: AdjustmentKind,
    val slots: List<ProgramSlotOut> = emptyList(),
    val selectedSlot: ProgramSlotOut? = null,
    val slotsLoading: Boolean = true,
    val submitting: Boolean = false,
)

/** Review tab: unconfirmed change-proposals extracted from session notes. Confirm/dismiss only
 *  flip server-side flags. Apply opens an explicit confirm-wizard: the athlete confirms the
 *  source slot (defaulted from the note's subject movement, but never silently assumed) and the
 *  action-routed adjustment (movement swap / load delta-or-absolute / rep range), then
 *  `/notes/{id}/apply` creates the slot override — this VM never mutates program state itself.
 *  The active-adjustments list (`/overrides`) can be reverted. */
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

    private val _wizard = MutableStateFlow<ApplyWizardState?>(null)
    val wizard: StateFlow<ApplyWizardState?> = _wizard.asStateFlow()

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

    /** Opens the Apply confirm-wizard for a note: routes the adjustment kind up front, then fetches
     *  the program's slots and pre-selects the source slot by best subject match. SWAP additionally
     *  warms the movement picker. */
    fun openApply(note: NoteReviewOut) {
        val kind = adjustmentKind(note.action_type, note.proposed_change?.action)
        _wizard.value = ApplyWizardState(note = note, kind = kind, slotsLoading = true)
        if (kind == AdjustmentKind.SWAP) loadMovementsIfNeeded()
        viewModelScope.launch {
            notesRepo.programSlots(Routes.DEFAULT_PROGRAM_ID)
                .onSuccess { slots ->
                    // Only apply if the wizard is still open for this same note (not dismissed
                    // or superseded while the fetch was in flight).
                    val current = _wizard.value ?: return@onSuccess
                    if (current.note.id != note.id) return@onSuccess
                    // No fallback to the first slot: when the subject doesn't match any slot, leave
                    // it UNSELECTED so the athlete must explicitly pick (avoids silently applying to
                    // the wrong slot, e.g. bench). The wizard gates Apply on a selected slot.
                    val selected = defaultSourceSlot(note.proposed_change?.movement, slots)
                    _wizard.value = current.copy(slots = slots, selectedSlot = selected, slotsLoading = false)
                }
                .onFailure { e ->
                    _applyError.value = errorMessage(e)
                    _wizard.value = null
                }
        }
    }

    fun selectSlot(slot: ProgramSlotOut) {
        _wizard.value = _wizard.value?.copy(selectedSlot = slot)
    }

    fun closeWizard() {
        _wizard.value = null
    }

    fun submitSwap(movementId: Int) = submitApply { slot ->
        ApplyOverrideRequest(
            tier_exercise_id = slot.tier_exercise_id,
            override_type = "MOVEMENT",
            override_movement_id = movementId,
        )
    }

    /** Exactly one of `delta`/`absolute` should be non-null — the caller (screen) enforces this. */
    fun submitLoad(delta: Double?, absolute: Double?) = submitApply { slot ->
        ApplyOverrideRequest(
            tier_exercise_id = slot.tier_exercise_id,
            override_type = "LOAD",
            load_delta = delta,
            load_absolute = absolute,
        )
    }

    fun submitReps(repLow: Int?, repHigh: Int?) = submitApply { slot ->
        ApplyOverrideRequest(
            tier_exercise_id = slot.tier_exercise_id,
            override_type = "REPS",
            rep_low = repLow,
            rep_high = repHigh,
        )
    }

    private fun submitApply(buildRequest: (ProgramSlotOut) -> ApplyOverrideRequest) {
        val w = _wizard.value ?: return
        val slot = w.selectedSlot ?: return
        _wizard.value = w.copy(submitting = true)
        viewModelScope.launch {
            notesRepo.applyOverride(w.note.id, buildRequest(slot))
                .onSuccess {
                    _wizard.value = null
                    load()
                    loadOverrides()
                }
                .onFailure { e ->
                    _applyError.value = errorMessage(e)
                    _wizard.value = _wizard.value?.copy(submitting = false)
                }
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
