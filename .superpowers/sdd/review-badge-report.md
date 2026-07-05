# Review Badge — Completion Report

## Objective
Show a pending-count badge on the Today screen's "Review" button (e.g. "Review (2)") so a
classified note visibly signals itself instead of requiring the user to know to check.

## Changes

### `ui/screens/today/TodayViewModel.kt`
- Added `NotesRepo` as a third constructor dependency, wired from `AppContainer.notesRepo`
  (already existed in the container) via the `Factory` initializer.
- Added `private val _reviewCount = MutableStateFlow(0)` / `val reviewCount: StateFlow<Int>`.
- `load()` now also calls a new `refreshReviewCount()`, which calls `notesRepo.review()` and sets
  `_reviewCount.value` to the list size on success. On failure it's a no-op (count stays at its
  previous value, default 0) — best-effort, never surfaces an error for this.
- Added a pure, unit-testable helper: `fun reviewButtonLabel(count: Int): String` — `"Review"` for
  0, `"Review (N)"` otherwise. Same pattern as the existing `classifyGenerate` pure function in
  this file.

### `ui/screens/today/TodayScreen.kt`
- Collects `vm.reviewCount` via `collectAsStateWithLifecycle()` (same pattern already used for
  `vm.state`).
- The Review `TextButton` now renders `Text(reviewButtonLabel(reviewCount))` instead of the
  hardcoded `Text("Review")`.

### `app/src/test/java/.../ui/today/TodayLogicTest.kt`
- Added 3 unit tests for `reviewButtonLabel`: count 0 → `"Review"`, count 1 → `"Review (1)"`,
  count 2 → `"Review (2)"`.

## Notes
- `NotesRepo` and its wiring in `AppContainer` already existed (no new repo/DI code needed) —
  only the `TodayViewModel` constructor/Factory needed updating to consume it.
- No nav/tab changes, no changes to the Review screen itself, no new Gradle dependency.
- `app/build.gradle.kts` has a local, uncommitted `SERVER_BASE_URL` diff unrelated to this task —
  left out of the commit per instructions.

## Verification
- `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL (26 actionable tasks, all green,
  including the 3 new `reviewButtonLabel` tests and the pre-existing `TodayLogicTest` suite).
- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
