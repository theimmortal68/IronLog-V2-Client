# Spec 32: Cardio log entry screen

## Objective
A form screen to log one Z2 cardio session (date, duration, avg HR, modality, incline, backward-walk-done), submitting via `CardioLogRepo.create` (spec 31).

## File targets
- New: `app/src/main/java/com/jauschua/ironlogv2/ui/screens/cardio/CardioLogScreen.kt`
- New: `app/src/main/java/com/jauschua/ironlogv2/ui/screens/cardio/CardioLogViewModel.kt`
- Modify: `app/src/main/java/com/jauschua/ironlogv2/ui/Nav.kt`
- Modify: `app/src/main/java/com/jauschua/ironlogv2/ui/MainActivity.kt`
- New: `app/src/test/java/com/jauschua/ironlogv2/ui/cardio/CardioLogScreenLogicTest.kt`

## The fix

### `Nav.kt`
Add one new route constant, matching the existing style exactly:
```kotlin
const val CARDIO_LOG = "cardio-log"
```
(Add it near `HISTORY`/`REVIEW`, no other changes to this file in this spec — `CARDIO_HISTORY` is added by a LATER spec, do not add it here.)

### `CardioLogViewModel.kt`
State machine: Idle (form) → Submitting → Submitted (success, brief) → back-navigation, or Error (retry). Mirror the `ViewModelProvider.Factory`/`viewModelFactory`/`initializer` pattern used by every other ViewModel in this codebase (e.g. `TodayViewModel`, `HistoryViewModel`).

```kotlin
package com.jauschua.ironlogv2.ui.screens.cardio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jauschua.ironlogv2.IronLogV2Application
import com.jauschua.ironlogv2.data.api.IronLogException
import com.jauschua.ironlogv2.data.api.dto.CardioLogCreate
import com.jauschua.ironlogv2.data.api.humanMessage
import com.jauschua.ironlogv2.data.repo.CardioLogRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface CardioLogUiState {
    data object Idle : CardioLogUiState
    data object Submitting : CardioLogUiState
    data object Submitted : CardioLogUiState
    data class Error(val msg: String) : CardioLogUiState
}

class CardioLogViewModel(
    private val cardioLogRepo: CardioLogRepo,
) : ViewModel() {

    private val _state = MutableStateFlow<CardioLogUiState>(CardioLogUiState.Idle)
    val state: StateFlow<CardioLogUiState> = _state.asStateFlow()

    fun submit(req: CardioLogCreate) {
        _state.value = CardioLogUiState.Submitting
        viewModelScope.launch {
            cardioLogRepo.create(req)
                .onSuccess { _state.value = CardioLogUiState.Submitted }
                .onFailure { e -> _state.value = CardioLogUiState.Error(errorMessage(e)) }
        }
    }

    fun resetToIdle() { _state.value = CardioLogUiState.Idle }

    private fun errorMessage(e: Throwable): String =
        (e as? IronLogException)?.error?.humanMessage() ?: e.message ?: "Unknown error"

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as IronLogV2Application
                CardioLogViewModel(app.container.cardioLogRepo)
            }
        }
    }
}
```

### `CardioLogScreen.kt`
A form Composable, mirroring `TodayScreen.kt`'s `NoSessionContent`-style form layout (Column + fields + a submit Button at the bottom) and `HistoryScreen.kt`'s `TopAppBar` shape:

- Fields: date (a text field pre-filled with today's date in `YYYY-MM-DD`, editable for backfilling — no date-picker dialog required for this first pass, plain text entry is acceptable), duration in minutes (numeric text field), avg HR (optional numeric text field), modality (two-option toggle: Walk / Treadmill — a `SegmentedButton`-style row of two `FilterChip`s or two `RadioButton`s is fine, match whatever simple selectable pattern this codebase already uses elsewhere, e.g. `TodayScreen.kt`'s `RadioButton`+`selectable` day-picker), incline (numeric text field, only shown/enabled when modality == "TREADMILL"), backward-walk-done (a `Checkbox`, only shown when modality == "TREADMILL").
- A pure helper function `fun buildCardioLogCreate(date: String, durationMinutes: String, avgHr: String, modality: String, inclinePct: String, backwardWalkDone: Boolean): CardioLogCreate?` that parses the string inputs, returns `null` if `date`/`durationMinutes`/`modality` are blank or `durationMinutes` doesn't parse as a positive `Int` (basic validation — this is a simple log, not a safety-critical form), and returns a valid `CardioLogCreate` otherwise (empty/blank `avgHr`/`inclinePct` map to `null`, not `0`). This function must be file-level (not nested in the Composable) so it's unit-testable, mirroring `CaptureScreen.kt`'s `internal fun` pattern for pure logic extracted from a Composable.
- On successful submission (`CardioLogUiState.Submitted`), call `onSubmitted: () -> Unit` (navigates back) via a `LaunchedEffect(state)`.
- On `CardioLogUiState.Error`, show the message with a retry/dismiss affordance (reuse `ErrorRetryBox` if its shape fits, else a simple `Text` + `TextButton` to reset via `vm.resetToIdle()`).

```kotlin
@Composable
fun CardioLogScreen(
    onBack: () -> Unit,
    vm: CardioLogViewModel = viewModel(factory = CardioLogViewModel.Factory),
) {
    // ... TopAppBar with back nav (mirror HistoryScreen.kt's TopAppBar/IconButton/ArrowBack shape)
    // ... form fields as described above
    // ... LaunchedEffect(state) { if (state is CardioLogUiState.Submitted) onBack() }
}
```

### `MainActivity.kt`
Add ONE new `composable(Routes.CARDIO_LOG) { ... }` block to the `NavHost`, in the same style as the existing `composable(Routes.HISTORY) { ... }`/`composable(Routes.REVIEW) { ... }` blocks (simple `onBack = { nav.popBackStack() }`, no arguments):
```kotlin
composable(Routes.CARDIO_LOG) {
    CardioLogScreen(onBack = { nav.popBackStack() })
}
```
Do NOT add any navigation entry POINT to this screen in this spec (no button/link anywhere navigates to it yet) — that's spec 33's job (the Today-screen rollup line's tap target). This spec only registers the destination in the nav graph; spec 33 is what makes it reachable.

## Edge cases
- Today's date pre-fill: use `java.time.LocalDate.now().toString()` (produces `YYYY-MM-DD` — confirm this matches the server's expected format by checking `LocalDate.now().toString()`'s actual output shape, which is ISO-8601 `YYYY-MM-DD`, matching the server's `date` field type).
- Blank `avg_hr`/`incline_pct` inputs must serialize as JSON `null`, not `0` or an empty string — this is why `buildCardioLogCreate` must treat a blank string as `null`, not attempt to parse it as `0`.
- Switching modality from Treadmill back to Walk after entering incline/backward-walk values: the fields simply become hidden (not cleared) — if the user switches back to Treadmill, their prior incline/backward-walk entries are still there. This is acceptable UX for a first pass (no requirement to clear on toggle).

## Dependencies
Depends on spec 31 (`CardioLogRepo`/DTOs) merged first.

## Required test coverage
`CardioLogScreenLogicTest.kt` — unit tests for `buildCardioLogCreate` (the pure, file-level, non-Composable function):
1. Valid WALK input (no incline/backward-walk fields relevant) → correct `CardioLogCreate`, `incline_pct=null`, `backward_walk_done=false` (or whatever was passed).
2. Valid TREADMILL input with incline + backward-walk true → correct `CardioLogCreate` with those fields populated.
3. Blank `avg_hr`/`incline_pct` → both map to `null`, not `0`.
4. Blank `date` → returns `null`.
5. Blank/non-numeric `duration_minutes` → returns `null`.
6. Zero or negative `duration_minutes` → returns `null` (basic sanity validation).

## Verification
- Full local test suite green: `./gradlew testDebugUnitTest --rerun` (this repo's gradle cache has repeatedly reported stale UP-TO-DATE results — always pass `--rerun`, and independently cross-check `app/build/test-results/testDebugUnitTest/TEST-*.xml`'s `<testsuite tests="N" failures="0">` and a fresh `timestamp=`, not just console "BUILD SUCCESSFUL").
- Baseline before this spec: 89 tests (per the 2026-07-20 carry-forward fix). This spec should add 6 new tests (expected total: 95) — confirm the exact new count via the XML, not by assuming.
