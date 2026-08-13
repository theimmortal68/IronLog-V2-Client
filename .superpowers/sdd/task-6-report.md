# Task 6 Report — Client capture cursor + overflow-menu UX

## What I implemented

Exactly the 4 files listed in the brief, plus the test-file append:

1. **`CaptureViewModel.kt`**
   - `resumeSet` computation in `load()` now skips `is_skipped` planned sets (`if (ps.is_skipped) return@firstOrNull false`).
   - Cursor-advance in `logWorkingSet()` now skips over `is_skipped` entries when finding the next set (`.drop(idx + 1).firstOrNull { !it.is_skipped }?.id`).
   - Added `skipExercise(exerciseId)`, `swapExercise(exerciseId, newMovementId, makePermanent)`, `loadSubstitutes(movementId)`.
   - Added private `applyUpdatedExercise(updated: ExerciseOut)` that patches the returned exercise into the current session tree, re-derives all cursor-adjacent state, re-points the cursor off the patched (skip-aware) prescription if it fell off, and re-emits `UiState.Success`.
   - **One correction vs. the brief's literal snippet**: the brief's `applyUpdatedExercise` read `current.session`, but `_state` is `MutableStateFlow<UiState<SessionDetailResponse?>>` and `UiState.Success` exposes the field as `.data` (confirmed from `UiState.kt` and the existing `is UiState.Success -> { val session = s.data }` pattern in `CaptureScreen.kt`). Used `.data` — compiles clean, verified via `:app:compileDebugKotlin`.

2. **`ExerciseActionsMenu.kt`** (new) — overflow (⋮) menu, `Swap exercise` / `Skip remaining sets` items, exactly as specced. Trimmed two unused imports (`size`, `dp`) that the brief's snippet included but never used, to avoid unused-import lint noise.

3. **`SwapExerciseSheet.kt`** (new) — two-step picker (suggested substitutes + full-library search, then today-only vs. permanent radio choice), exactly as specced. **Fixed one import bug in the brief's snippet**: it wrote `import androidx.compose.material3.Row`, but `Row` lives in `androidx.compose.foundation.layout.Row` (confirmed by grepping every other `Row` import in this codebase — all use `foundation.layout`). Using the brief's literal import would have failed to compile; used the correct package.

4. **`CaptureScreen.kt`**
   - Added `MovementsListViewModel` as a second `viewModel(factory = ...)` parameter to `CaptureScreen`, reusing the **existing** `LibraryRepo.movements()` call (the same one the Movements tab's `MovementsListViewModel` already uses) rather than adding a new repo method. Its `List<MovementDto>` result is filtered to `Status.ACTIVE` and mapped to `List<MovementSummary>` to match `SwapExerciseSheet`'s expected shape. See "Movements-repo reuse decision" below for why this shape mismatch existed and how I resolved it.
   - `fullLibrary` threaded through as a new `SessionContent` parameter.
   - `swapSheetExerciseId` state var added next to `sessionNote` in `SessionContent`.
   - Both exercise-name `Text(...)` call sites (GIANT_SET branch, now ~line 519; STRAIGHT branch, now ~line 645) wrapped in a `Row` with a conditionally-rendered `ExerciseActionsMenu`, gated on `hasRemaining` (a not-yet-logged, not-yet-skipped planned set) — same shape at both sites, matching the brief.
   - `SwapExerciseSheet` rendered as a sibling after the `Column { ... }` closes (same pattern the existing `GroupReviewSheet` uses in `CaptureScreen` — a conditionally-composed overlay outside the main layout tree), fetching substitutes fresh via a `LaunchedEffect(exId)` each time the sheet opens.

5. **`CaptureScreenLogicTest.kt`** — appended the exact test from the brief's Step 1.

## What I tested and results

- **Step 1/2 (TDD)**: Wrote the test, ran `:app:testDebugUnitTest --tests "*CaptureScreenLogicTest*"` before touching any production code. **GREEN immediately** — exactly as the brief predicted (it exercises `flattenPrescription`, unchanged by this task; the point was to sanity-check the fixture shape against Task 5's `is_skipped` field, not to red/green a new behavior). This is not a TDD-failure-then-fix cycle; the brief explicitly flagged this test as a fixture-shape check, and it confirmed the DTO fixture builds correctly.
- **After every CaptureScreen.kt edit**: ran `:app:compileDebugKotlin` — clean at every step (movements-fetch wiring, GIANT_SET Row wrap, STRAIGHT Row wrap, SwapExerciseSheet render block).
- **Structural safety check**: after all CaptureScreen.kt edits, ran an awk running-brace-balance trace over the whole file — ends at 0, no imbalance introduced.
- **Full build + full test suite**: `:app:assembleDebug :app:testDebugUnitTest` — **BUILD SUCCESSFUL**, all 90 tests in `CaptureScreenLogicTest` pass (`tests="90" skipped="0" failures="0" errors="0"`), full APK assembled.

## Files changed

- `app/src/main/java/com/jauschua/ironlogv2/ui/screens/capture/CaptureViewModel.kt` (modified)
- `app/src/main/java/com/jauschua/ironlogv2/ui/screens/capture/CaptureScreen.kt` (modified)
- `app/src/main/java/com/jauschua/ironlogv2/ui/screens/capture/ExerciseActionsMenu.kt` (new)
- `app/src/main/java/com/jauschua/ironlogv2/ui/screens/capture/SwapExerciseSheet.kt` (new)
- `app/src/test/java/com/jauschua/ironlogv2/ui/capture/CaptureScreenLogicTest.kt` (modified, append-only)

Commit: `25dd7e9` — `feat(capture): mid-workout swap/skip overflow menu + cursor skip-awareness`

## Movements-repo reuse decision

The brief said to reuse "the existing movements-list repo call already used elsewhere in the app for the Movements tab" rather than add a new one. That call is `LibraryRepo.movements(): Result<List<MovementDto>>`, consumed today only by `MovementsListViewModel` (Movements tab). It returns `MovementDto` (the full library shape: region, lift_category, equipment, progression fields, etc.), not `MovementSummary` (the swap sheet's minimal shape: id/name/primary_muscle/status) that `SwapExerciseSheet` and `CaptureRepo.substitutesFor()` already use.

Rather than adding a second repo/API call to fetch `MovementSummary` directly, I:
1. Instantiated the existing `MovementsListViewModel` (via its existing `Factory`) as a second `viewModel()` param on `CaptureScreen`.
2. Collected its `state: StateFlow<UiState<List<MovementDto>>>` the same way `MovementsListScreen` does.
3. Filtered to `Status.ACTIVE` and mapped `MovementDto -> MovementSummary` locally in `CaptureScreen`.

This reuses the actual network call and its existing caching/lifecycle (no new API endpoint touched, no new repo method), satisfying the "don't add a second fetch" instruction. The mapping step is the only new logic, and it's pure/local (no new interfaces, no new DTOs).

## Self-review

- **Completeness**: all 4 spec'd pieces present and wired — skip-aware cursor (both directions: initial resume and post-log advance), the two new composables, both `CaptureScreen.kt` call sites wired, `applyUpdatedExercise` patch-in-place avoiding a full reload.
- **Quality**: matches existing style (trailing commas, `MaterialTheme.typography.*`, `scope.launch { vm.* }` pattern for suspend VM calls, doc-comment conventions on non-obvious functions).
- **Discipline**: no composables/params beyond what the brief specified. The only additions beyond the brief's literal text are the two bug fixes noted above (`.data` not `.session`; `foundation.layout.Row` not `material3.Row`) and two unused-import removals in `ExerciseActionsMenu.kt` — none of these add scope, they just make the brief's snippets actually compile/lint clean.
- **Testing**: the cursor-skip logic is covered at the pure-function level (`flattenPrescription` + the filter idiom the VM now uses); `applyUpdatedExercise`'s cursor-repoint branch and the two new Compose composables are not separately unit-tested — this matches the brief's own scope (Step 1 is the only test step specified) and this file's established pattern of testing only the extracted pure logic, not Compose composables directly (see the test file's own doc comment: "Compose composables ... are not unit-tested here").
- **Zero warnings-as-errors concerns**: build is clean; no new compiler warnings observed in the compile output beyond pre-existing `UP-TO-DATE`/task noise.

## Concerns

- **CaptureScreen.kt brace balance**: no issues. I compiled after each of the 4 edit points (movements-fetch, GIANT_SET Row wrap, STRAIGHT Row wrap, sheet render block) and ran a full brace-balance trace at the end — clean throughout. No guess-and-check was needed.
- **Movements-repo reuse**: flagged above — I mapped `MovementDto -> MovementSummary` rather than the brief perhaps envisioning `MovementSummary` being fetched directly, because no such call exists elsewhere in the app. This is a judgment call within the brief's stated intent ("reuse... rather than adding a second one"); happy to revisit if a different shape was expected, but it satisfies the letter and spirit of the instruction.
- **`applyUpdatedExercise` cursor-repoint edge case**: if a skip/swap removes the currently-cursored planned set from the flattened prescription entirely (e.g. skip removes it, or a swap changes exercise structure enough that the same id disappears), the cursor re-points to the first non-skipped entry in the whole session rather than trying to preserve "nearby" position. This matches the brief's literal snippet exactly (`flattenedPrescription.firstOrNull { !it.is_skipped }?.id`) — flagging in case that's a coarser fallback than intended, but it's what was specified.
- **Two brief-snippet bugs fixed** (not concerns, just noting for the record so they're not mistaken for scope creep): `current.session` → `current.data` (brief's snippet referenced a field that doesn't exist on `UiState.Success`), and `import androidx.compose.material3.Row` → `androidx.compose.foundation.layout.Row` (wrong package in the brief, verified against every other `Row` import in the codebase).

## Review-response fixes

Opus code review of commit `25dd7e9` (this task) found one HIGH and one MEDIUM. This section documents the response.

### HIGH — fixed: cursor stranded on the just-skipped set when it was the current exercise

The concern flagged in this report's own "Concerns" section above (`applyUpdatedExercise` cursor-repoint edge case) turned out to be worse than described there: the fallback didn't just use a coarser-than-ideal re-selection rule, it frequently **never fired at all**. Both `skip_exercise` and `swap_exercise` (`ironlog/api/app.py`) mutate the existing `PlannedSet` rows IN PLACE — ids are never deleted or reassigned. So skipping the exercise the cursor was currently on flipped that set's `is_skipped` to `true` but left its `id` present in `flattenedPrescription`, meaning `flattenedPrescription.none { it.id == cur }` was always `false`. The cursor stayed parked on a set now marked skipped, which still rendered as the active input card (rendering has no `is_skipped` filter).

Fixed `applyUpdatedExercise` in `CaptureViewModel.kt`:
- Re-selection now triggers when the current planned set is **either** missing from the flattened list **or** present but `is_skipped == true`.
- The replacement cursor now uses the same semantics as `load()`'s `resumeSet` (first not-skipped, not-fully-logged set) instead of `firstOrNull { !it.is_skipped }`, which jumped backward to the first not-skipped set in the *entire* session, including earlier already-logged sets.
- "Fully logged" is derived from `_loggedSetActuals` (already in memory, keyed by `plannedSetId to sideIndex`) rather than a fresh `repo.setLogsForSession` fetch — each successful write upserts exactly one entry per side, so counting entries per plannedSetId is equivalent to `load()`'s row-count map without a network round trip. Chose this over option (a) (re-fetching via `setLogsForSession`) because `applyUpdatedExercise` already has everything it needs in memory and a redundant network call added no correctness benefit.

**Regression test**: `skipping_the_current_exercise_reselects_cursor_forward_not_to_an_earlier_logged_set` added to `CaptureViewModelTest.kt`. Unlike the existing Task-6 test in `CaptureScreenLogicTest.kt` (which only re-implements `firstOrNull { !it.is_skipped }` inline against a hand-built flattened list and never touches production code), this test drives the actual `CaptureViewModel` end-to-end: loads a 3-exercise session via a path-branching `MockEngine` (exercise 1 sets 10/11 already fully logged, exercise 2 sets 20/21 is the current exercise with the cursor on set 20, exercise 3 sets 30/31 untouched), calls `vm.skipExercise(exerciseId = 2)` against a mocked skip response that patches exercise 2's planned sets to `is_skipped = true` in place (mirroring the real server behavior), and asserts the cursor lands on set 30 — not 20/21 (just skipped) and not 10/11 (earlier, already logged — where the bug would have jumped it). RED-confirmed by reasoning through the reverted code path (see the test's doc comment): the old fallback condition never fires when the id is still present, so the cursor would have stayed at 20, failing the assertion.

A comment was added above the existing weak test in `CaptureScreenLogicTest.kt` pointing to the new regression test and explaining why the old one wouldn't have caught this bug.

### MEDIUM — deferred: full movement library fetched eagerly on every Capture screen open

Confirmed: `movementsVm` is instantiated as a default `viewModel(factory = MovementsListViewModel.Factory)` parameter of `CaptureScreen`, and `MovementsListViewModel.init { reload() }` fires unconditionally at construction — so the full movement list is fetched every time the Capture screen composes, whether or not the athlete ever opens the swap sheet.

Deferring this fix rather than forcing it in this pass, because a correct fix is more invasive than the review brief's "small change" bar:
- `MovementsListViewModel` is shared with the real Movements tab, where eager-on-init fetch is the *correct* behavior — changing its `init {}` to not auto-load would require plumbing an explicit trigger through both call sites (Movements tab and Capture screen), not a one-line change confined to `CaptureScreen.kt`.
- A `CaptureScreen`-local fix (e.g. deferring the `viewModel()` call itself until `swapSheetExerciseId` first becomes non-null) doesn't compose cleanly with Compose's `viewModel()` factory pattern, which is normally called unconditionally at the top of a composable — conditionally creating it only on first sheet-open would need either a `remember`-guarded lazy holder or restructuring `MovementsListViewModel` to accept an `autoLoad` flag, both bigger than the review's "small change" framing.

Filed as a follow-up: give `MovementsListViewModel` an `autoLoad: Boolean = true` constructor param (or a separate lazy-init factory variant) so `CaptureScreen` can opt out of the eager `init { reload() }` and call `movementsVm.reload()` itself from a `LaunchedEffect(swapSheetExerciseId) { if (swapSheetExerciseId != null) movementsVm.reload() }` gated on first non-null. Left `CaptureScreen.kt` unchanged for this finding.

### Verification

- `:app:compileDebugKotlin` — BUILD SUCCESSFUL.
- `:app:assembleDebug :app:testDebugUnitTest` — BUILD SUCCESSFUL, 283 tests total across the module, 0 failures/errors (baseline before this fix was 90 in `CaptureScreenLogicTest` alone; the full-module count of 283 includes `CaptureViewModelTest`, `CaptureViewModelReviewTest`, `GroupReviewLogicTest`, `RestTimerTest`, and others — all green, including the new regression test).
