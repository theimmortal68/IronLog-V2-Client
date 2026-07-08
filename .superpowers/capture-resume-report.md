# Capture resume-cursor fix — completion report

## Bug

When Android kills the backgrounded app and recreates `CaptureViewModel`, `load()` correctly
restored `_loggedSetActuals` from the persisted Room drafts, but reset the logging cursor
(`_currentPlannedSetId`) to `flattenedPrescription.firstOrNull()?.id` — the first set in the
prescription, always, regardless of what had already been logged. `pastSetIds` (CaptureScreen.kt)
derives which sets render as logged/checkmarked purely from cursor position (everything before the
cursor). With the cursor forced back to set 1, a session with real progress rendered as if the
entire session had been lost, even though every set was safely persisted (write-before-advance).

## Fix

`CaptureViewModel.load()` (`app/src/main/java/com/jauschua/ironlogv2/ui/screens/capture/CaptureViewModel.kt`):

- Added `CaptureRepo.setLogsForSession(sessionId): List<SetLogDraft>` — a thin passthrough to
  `CaptureDao.setLogsForSession`, returning the RAW draft rows (as opposed to
  `loggedActualsFor`, which collapses to one "latest" row per plannedSetId). Needed because a
  UNILATERAL set's "fully logged" state depends on the row *count* (2 rows = both sides), which
  the collapsed map can't answer.
- In `load()`, after computing `flattenedPrescription`/`unilateralSetIds` (unchanged), build
  `rowCountByPlannedSetId` from the raw drafts, then resolve the resume set as:
  ```kotlin
  val resumeSet = flattenedPrescription.firstOrNull { ps ->
      val rows = rowCountByPlannedSetId[ps.id] ?: 0
      val fullyLogged = if (ps.id in unilateralSetIds) rows >= 2 else rows >= 1
      !fullyLogged
  }
  _currentPlannedSetId.value = resumeSet?.id
  ```
  - Bilateral set: fully logged at **≥1** row.
  - Unilateral set: fully logged only at **≥2** rows (both sides).
  - If every set in `flattenedPrescription` is fully logged, `resumeSet` is `null` — same
    end-state as finishing the session live (ready to submit).
- `unilateralSideCount` is restored alongside the cursor: if the resume set is unilateral and has
  exactly 1 row logged (mid-set — side 2 pending), `unilateralSideCount = 1` so the very next
  `logWorkingSet` call is correctly treated as side 2 (advances the cursor) instead of side 1
  again (which would silently duplicate side 1's slot and lose the true side-2 write). Otherwise
  `unilateralSideCount = 0`.
- `_loggedSetActuals` restore is unchanged. `pastSetIds` itself is untouched — it already derives
  correctly from whatever cursor it's given; the bug was purely in what cursor `load()` fed it.
- Room schema, DAO writes, `submit()`, and the B/F/J logic are all untouched.

## Tests added

`app/src/test/java/com/jauschua/ironlogv2/ui/capture/CaptureViewModelTest.kt` — extended with:

1. **`load_resumes_cursor_at_first_unlogged_set_not_first_set`** — 2 exercises × 3 sets
   (STRAIGHT, flattened `[10,11,12,20,21,22]`), first 3 (all of exercise 1) pre-persisted directly
   to Room. Drives the real `load()` path via a MockEngine that serves the session from
   `GET /sessions/today` (branches on request path, mirroring `WizardViewModelTest`'s pattern).
   Asserts: cursor resumes at id `20` (not `10`); `pastSetIds(flatSets, cursor)` == `{10,11,12}`;
   `loggedSetActuals` still holds all 3 actuals.
2. **`load_resumes_mid_unilateral_set_when_only_side_one_logged`** — a unilateral set with only
   side 1 (sideIndex 0) persisted. Asserts cursor lands ON that set (id `1`, not skipped), then
   proves `unilateralSideCount` was restored to 1 by calling `logWorkingSet` once more and
   observing the cursor advance to the next set with both `sideIndex` 0 and 1 rows now present
   (rather than holding again, which would indicate side count was wrongly reset to 0).
3. **`load_leaves_cursor_null_when_all_sets_are_already_fully_logged`** — all sets pre-logged;
   asserts cursor resumes to `null` (session-complete end-state), unchanged from today's behavior.

`load()` uses `viewModelScope.launch` (unlike `logWorkingSet`/`editLoggedSet`, plain suspend
functions the caller drives directly), so the test class now sets `Dispatchers.setMain(
UnconfinedTestDispatcher())` in `@Before`/resets in `@After` (same pattern as
`WizardViewModelTest`), and awaits `vm.state` reaching `UiState.Success` before asserting.

**RED confirmed**: with `git checkout --` reverting `CaptureViewModel.kt`/`CaptureRepo.kt` back to
the pre-fix HEAD version, all 3 new tests failed (`AssertionError`), while the 16 pre-existing
`CaptureViewModelTest` tests still passed. Fix restored → all 19 tests green.

## Build + test result

```
./gradlew :app:assembleDebug :app:testDebugUnitTest
BUILD SUCCESSFUL in 5s
45 actionable tasks: 10 executed, 35 up-to-date
```

Full `testDebugUnitTest` suite (all test classes, not just Capture) also run clean — no
regressions.

## Files changed

- `app/src/main/java/com/jauschua/ironlogv2/data/repo/CaptureRepo.kt` — added
  `setLogsForSession(sessionId)` passthrough (+11 lines).
- `app/src/main/java/com/jauschua/ironlogv2/ui/screens/capture/CaptureViewModel.kt` — `load()`
  resume-cursor logic (+32/-2 lines net).
- `app/src/test/java/com/jauschua/ironlogv2/ui/capture/CaptureViewModelTest.kt` — 3 new tests +
  `@Before`/`@After` Main-dispatcher setup + `repoForLoad` helper (+214 lines).

Not touched (pre-existing unrelated uncommitted changes on this branch, left as-is):
`.superpowers/sdd/task-7-report.md`, `app/build.gradle.kts`.
