# Spec 34: Cardio log history screen

## Objective
A simple list of past `CardioLog` entries (date, duration, modality), reachable from the cardio log entry screen (spec 32), mirroring `HistoryScreen.kt`'s shape.

## File targets
- New: `app/src/main/java/com/jauschua/ironlogv2/ui/screens/cardio/CardioHistoryScreen.kt`
- New: `app/src/main/java/com/jauschua/ironlogv2/ui/screens/cardio/CardioHistoryViewModel.kt`
- Modify: `app/src/main/java/com/jauschua/ironlogv2/ui/Nav.kt`
- Modify: `app/src/main/java/com/jauschua/ironlogv2/ui/MainActivity.kt`
- Modify: `app/src/main/java/com/jauschua/ironlogv2/ui/screens/cardio/CardioLogScreen.kt` (spec 32's file — add a "History" top-bar action button, same pattern as `TodayScreen.kt`'s existing "History"/"Review" `TopAppBar` actions)

## The fix

### `Nav.kt`
Add one new route constant:
```kotlin
const val CARDIO_HISTORY = "cardio-history"
```

### `CardioHistoryViewModel.kt`
Mirror `HistoryViewModel.kt`'s exact shape (this codebase's established `UiState<T>` sealed-interface pattern), swapped to `CardioLogRepo.list()`:

```kotlin
package com.jauschua.ironlogv2.ui.screens.cardio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jauschua.ironlogv2.IronLogV2Application
import com.jauschua.ironlogv2.data.api.IronLogException
import com.jauschua.ironlogv2.data.api.dto.CardioLogOut
import com.jauschua.ironlogv2.data.api.humanMessage
import com.jauschua.ironlogv2.data.repo.CardioLogRepo
import com.jauschua.ironlogv2.ui.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CardioHistoryViewModel(
    private val cardioLogRepo: CardioLogRepo,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<CardioLogOut>>>(UiState.Loading)
    val state: StateFlow<UiState<List<CardioLogOut>>> = _state.asStateFlow()

    init { reload() }

    fun reload() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            cardioLogRepo.list()
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
                CardioHistoryViewModel(app.container.cardioLogRepo)
            }
        }
    }
}
```

### `CardioHistoryScreen.kt`
Mirror `HistoryScreen.kt`'s exact shape (`Scaffold` + `TopAppBar` with back nav + `LazyColumn` of `Card` rows), swapped to render `CardioLogOut` rows instead of `SessionSummary` rows:

```kotlin
@Composable
fun CardioHistoryScreen(
    onBack: () -> Unit,
    vm: CardioHistoryViewModel = viewModel(factory = CardioHistoryViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cardio History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize().padding(inner)) {
            when (val s = state) {
                is UiState.Loading -> /* CircularProgressIndicator, centered — mirror HistoryScreen.kt */
                is UiState.Error -> ErrorRetryBox(s.msg) { vm.reload() }
                is UiState.Success -> CardioLogListBody(s.data)
            }
        }
    }
}
```
A row's display text: `"${entry.date} · ${entry.duration_minutes}min · ${entry.modality}"` (matching `HistoryScreen.kt`'s `SessionRow`'s `"${session.date} · ${session.day_role}"` compact format). No tap-to-detail navigation needed for this first pass (unlike workout `HistoryScreen`'s `onOpen` → `HistoryDetailScreen`) — cardio entries have no further detail beyond what's already in the list row, so `CardioHistoryScreen` needs no `onOpen` parameter, only `onBack`.

### `MainActivity.kt`
Add one new `composable(Routes.CARDIO_HISTORY) { ... }` block, same style as `composable(Routes.HISTORY)`:
```kotlin
composable(Routes.CARDIO_HISTORY) {
    CardioHistoryScreen(onBack = { nav.popBackStack() })
}
```

### `CardioLogScreen.kt` (spec 32's file, minor addition)
Add an `onHistory: () -> Unit` parameter to `CardioLogScreen`'s signature and a `TopAppBar` action button, mirroring `TodayScreen.kt`'s existing `actions = { TextButton(onClick = onHistory) { Text("History") } }` pattern exactly. Wire it at the `MainActivity.kt` call site: `onHistory = { nav.navigate(Routes.CARDIO_HISTORY) }`.

## Edge cases
- Empty history (no entries logged yet, the live production state as of 2026-07-21): show a simple "No cardio sessions logged yet." message, mirroring `HistoryScreen.kt`'s `SessionListBody`'s empty-state handling exactly (same centered-Text pattern).
- Rows are already most-recent-first per the server's `GET /cardio-log` ordering (spec 45) — no client-side sort needed.

## Dependencies
Depends on spec 31 (`CardioLogRepo`/DTOs) and spec 32 (`CardioLogScreen.kt` exists, to add the History button to it) merged first.

## Verification
- Full local test suite green: `./gradlew testDebugUnitTest --rerun`, cross-checked via `app/build/test-results/testDebugUnitTest/TEST-*.xml`. No new unit tests required (this spec is UI/ViewModel wiring mirroring an already-established pattern, not new branching logic — matches `HistoryViewModel`'s own lack of a dedicated unit test file).
- Manual/live verification (not part of the merge gate): after at least one cardio log has been submitted via the entry screen, open History from the cardio log screen and confirm it appears, most-recent-first.
