# Task 7 Report — CaptureScreen + nav wiring (Logging Round-Trip)

**Status:** DONE
**Commit:** `136353e` — `feat(capture): CaptureScreen (prescription render + per-set tap) + nav wiring`
**Branch:** `feat/logging-capture`

---

## Files Created / Modified

| File | Change |
|------|--------|
| `app/src/main/java/com/jauschua/ironlogv2/ui/screens/capture/CaptureScreen.kt` | **Created** — full CaptureScreen composable |
| `app/src/main/java/com/jauschua/ironlogv2/ui/screens/capture/CaptureViewModel.kt` | **Modified** — added `_state`/`state` flow, `load()`, `TodayFactory` |
| `app/src/main/java/com/jauschua/ironlogv2/ui/Nav.kt` | **Modified** — added `Routes.CAPTURE = "capture"` |
| `app/src/main/java/com/jauschua/ironlogv2/ui/MainActivity.kt` | **Modified** — added Capture tab (PlayArrow icon) + `composable(Routes.CAPTURE)` |

---

## Minimal VM Addition

`CaptureViewModel` gained three additions without touching the existing constructor or test-facing API:

1. `private var sessionId: Int` (was `val`) — allows `load()` to set the real session id from `today()`.
2. `val state: StateFlow<UiState<SessionDetailResponse?>>` — prescription state for the screen.
3. `fun load()` — calls `repo.today()`, sets `_state` and `sessionId`. Called from `LaunchedEffect(Unit)` in `CaptureScreen`, **not from `init`**, so existing tests (which inject `sessionId = 7` and never call `load()`) are unaffected.
4. `val TodayFactory` companion object — no-arg factory for the bottom-nav destination (passes `sessionId = 0` as placeholder, overwritten by `load()`).

This mirrors `AutoregulateViewModel.reload()` as specified.

---

## Gate #2 — Mandatory-tap disabled button (confirmed)

In `SetCard`:
```kotlin
val logEnabled = !tapRequired || selectedTap != null
```
where `tapRequired = plannedSet.set_role in setOf("WORKING", "TOP", "BACKOFF")`.

`Button(onClick = onLogSet, enabled = logEnabled, ...)` — the "Log set" button is **disabled until a tap is selected for working roles**. Non-working roles (warmups) are unblocked. This is the client-side UI half of Gate #2; the VM and server enforce it independently.

---

## Screen Behavior

- **Loading**: `CircularProgressIndicator` centered.
- **Error**: `ErrorRetryBox(msg) { vm.load() }` — retry re-calls `load()`.
- **Success / null session**: "No planned session — generate one."
- **Success / session present**: `LazyColumn` renders groups → exercises → planned sets.
  - Per planned set: set role + index, target prescription (load/reps if available).
  - **Current set** (`set_index == nextSetIndex`): shows load/reps `OutlinedTextField`s, three-state `SingleChoiceSegmentedButtonRow` (TOO_EASY / ON_TARGET / TOO_HARD), "Log set" button (disabled until tap for working roles).
  - **Past sets** (`set_index < nextSetIndex`): shows "✓" checkmark.
  - Input state (`setLoad`, `setReps`, `selectedTap`) auto-resets via `remember(nextSetIndex)` when the index advances.
- **`logWorkingSet`**: called in `rememberCoroutineScope().launch` (suspends; write-before-advance preserved).
- **Finish & Submit**: `Button(onClick = { vm.finish() })` → surfaces `submitResult` (COMPLETED / RETRY); button disabled once COMPLETED.
- **UI error** (`vm.uiError`): shown in error color above Finish button.

---

## Build Verification

```
./gradlew :app:assembleDebug
BUILD SUCCESSFUL in 37s
38 actionable tasks: 22 executed, 16 up-to-date
```

---

## Test Results

```
./gradlew testDebugUnitTest
BUILD SUCCESSFUL in 4s
```

All **18 tests green**, 0 failures:

| Suite | Tests |
|-------|-------|
| CaptureViewModelTest | 3 |
| CaptureDurabilityTest | 1 |
| CaptureRepoTest | 1 |
| AutoregRepoTest | 1 |
| LibraryRepoTest | 6 |
| ModelsTest | 6 |

Two pre-existing `@OptIn(ExperimentalCoroutinesApi)` warnings in `CaptureViewModelTest` (not introduced by this task).

---

## Concerns / Notes

None. The implementation mirrors `AutoregulateScreen` exactly (same `UiState` when-branches, `LaunchedEffect`, `Scaffold`/`TopAppBar`/`Surface` wrapper, Material3 components). No novel patterns introduced.

---

## Final-review fix wave (client)

**Commit:** `0b9ffe4` — `fix(capture): multi-exercise progression cursor (loop closes for real sessions) + test; gate-5 negative (drafts persist on failed submit)`
**Branch:** `feat/logging-capture`

### Fix ① — Multi-exercise progression cursor (the loop-closer not closing)

**Root cause:** `CaptureViewModel` tracked progress as `_nextSetIndex: MutableStateFlow<Int>` (a global counter), and `CaptureScreen` checked `plannedSet.set_index == nextSetIndex`. `PlannedSetOut.set_index` is **per-exercise** (resets to 0 at the start of each exercise). After logging exercise-0's sets (set_index 0→1, counter advances to 2), every exercise-1 set had `set_index < 2` → all appeared "past", with no input controls rendered. Only the first exercise in a session could be logged. Program days are 6-9 exercises, making the screen non-functional for any real workout.

**Fix — flattened-position cursor over the prescription:**

`CaptureViewModel` now:
- Stores `private var flattenedPrescription: List<PlannedSetOut>` computed in `load()` by flattening `groups → exercises → planned_sets` in order.
- Exposes `currentPlannedSetId: StateFlow<Int?>` — the `PlannedSetOut.id` (globally-unique) of the set to log next; `null` when the prescription is exhausted.
- `logWorkingSet` advances the cursor via `flattenedPrescription.indexOfFirst { it.id == plannedSetId } + 1` in the flat list, crossing exercise boundaries automatically.
- Write-before-advance ordering preserved: cursor advances only after `repo.logSet()` returns (the Room `@Insert suspend` completes inline before the index update).
- `internal fun initPrescriptionForTest(sets: List<PlannedSetOut>)` helper for unit tests.

`CaptureScreen` changes:
- `nextSetIndex` replaced with `currentPlannedSetId: Int?` throughout.
- `SessionContent` computes `flatSets` (`remember(session)`) and `pastIds` (`remember(session, currentPlannedSetId)`) — the set of IDs that appear before the cursor in the flat order.
- `isCurrent = plannedSet.id == currentPlannedSetId`; `isPast = plannedSet.id in pastIds`.
- `remember(currentPlannedSetId)` keys for input state reset.

### Fix ①'s multi-exercise test — `multi_exercise_cursor_walks_all_sets`

Prescription: 2 exercises × 2 working sets (ids 1,2,3,4; set_index 0,1,0,1 — the trap).

Assertions:
```
initial cursor: E0S0 (id=1)
after E0S0 → E0S1 (id=2)
after E0S1 → E1S0 (cross-exercise boundary) (id=3)   ← the critical one
after E1S0 → E1S1 (id=4)
after E1S1 → end of prescription (null)
```

**RED-confirmed against the broken cursor** — reverted advance to `flattenedPrescription.find { it.set_index == setIndex + 1 }?.id`:

```
java.lang.AssertionError: after E0S1 → E1S0 (cross-exercise boundary) expected:<3> but was:<null>
```

After logging E0S1 (setIndex=1), the broken form looks for a set with `set_index == 2`. No such set exists (exercise-1 resets to 0), so cursor becomes `null`. Exercise-1 is permanently stuck — the bug class is confirmed caught. Fix restored immediately; full suite went GREEN.

### Gate-#5 negative test — `submit_failure_preserves_drafts` (CaptureRepoTest)

`MockEngine` returns HTTP 500. Because `ApiClient` is configured with `expectSuccess = true`, Ktor throws `ServerResponseException`, which `runCatchingApi` catches as `Result.failure`. The `dao.clearSetLogs(sessionId)` line after the API call is never reached.

```kotlin
val res = repo.submit(7)
assertTrue("submit must return failure on 500", res.isFailure)
assertEquals("drafts must be preserved on failure", 1, dao.setLogsForSession(7).size)
```

Test passes: failed submit returns `Result.failure` AND drafts remain for retry.

### Gradle tails

```
./gradlew testDebugUnitTest --rerun
BUILD SUCCESSFUL in 4s

./gradlew :app:assembleDebug
BUILD SUCCESSFUL in 1s
38 actionable tasks: 3 executed, 35 up-to-date
```

### Test counts

| Suite | Tests |
|-------|-------|
| CaptureViewModelTest | 4 (+1 multi_exercise_cursor_walks_all_sets) |
| CaptureDurabilityTest | 1 |
| CaptureRepoTest | 2 (+1 submit_failure_preserves_drafts) |
| AutoregRepoTest | 1 |
| LibraryRepoTest | 6 |
| ModelsTest | 6 |
| **Total** | **20** (was 18) |

All 20 tests green. `assembleDebug` BUILD SUCCESSFUL.
