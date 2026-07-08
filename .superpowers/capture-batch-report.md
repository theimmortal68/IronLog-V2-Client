# Capture-screen improvement batch — completion report

Branch: `feat/capture-improvements`
Objective: three athlete-Day-1 fixes on the Capture screen — B (logged sets show actuals +
editable), F (weight carries forward), J (idempotent set logging / no double-submit).

## B — logged sets show actuals + are editable

- `data/local/CaptureEntities.kt` — unchanged (no schema change needed).
- `ui/screens/capture/CaptureViewModel.kt`:
  - New `data class LoggedSetActual(actualLoad, actualReps, tap)`.
  - New `_loggedSetActuals: MutableStateFlow<Map<Int, LoggedSetActual>>` /
    `loggedSetActuals: StateFlow<...>`, keyed by `PlannedSetOut.id`.
  - `load()` now populates it from `repo.loggedActualsFor(sessionId)` (persisted drafts), so a
    resumed session shows real actuals, not just targets.
  - `logWorkingSet` records the actual it just committed into the map (in addition to its
    existing cursor-advance/rest/review-trigger behavior — those are unchanged).
  - New `suspend fun editLoggedSet(...)`: same mandatory-tap gate as `logWorkingSet`, writes via
    `repo.logSet` (upsert — see J), updates `loggedSetActuals` in place. Deliberately does
    **not** touch the cursor, rest timer, or group-review trigger — those belong to the forward
    "log the current set" path, not a correction to something already logged.
- `data/repo/CaptureRepo.kt` — new `loggedActualsFor(sessionId): Map<Int, SetLogDraft>`.
- `ui/screens/capture/CaptureScreen.kt`:
  - New pure functions `tapResultLabel`, `loggedActualLine` — "165lb × 6 reps · ✓ on target"
    style line for a past set's card.
  - `SetCard` now renders `Actual: ...` (primary, prominent) above the existing `Target: ...`
    (now secondary reference) for logged (`isPast`) sets.
  - A past card is `clickable` to reopen an edit block (load/reps/tap inputs + "Save
    correction" button), which calls `vm.editLoggedSet(...)`. Editing state (`editingSetId`,
    `editLoad`, `editReps`, `editTap`) lives in `SessionContent`, prefilled from
    `loggedSetActuals[editingSetId]` when a card is opened.
  - Known limitation: a **unilateral** set logs two rows (side 1 + side 2) under one
    `plannedSetId`; `loggedSetActuals` only keeps the latest write, so the card shows side 2's
    actual only. Not explicitly in scope for this batch; flagging for a follow-up if unilateral
    per-side actuals need to be shown separately.

## F — weight carries forward

Implemented entirely as pure functions in `CaptureScreen.kt` (no VM change needed — Compose
input state already lives in `SessionContent`, matching the existing `prefillWeight`/`prefillReps`
pattern):

- `effectiveLoadPrefill(carriedLoad: Map<Int, Double>, movementId, targetLoad): String` — carried
  load for the movement if one exists, else the set's own `target_load`.
- `withCarriedLoad(carriedLoad, movementId, newLoad): Map<Int, Double>` — records a new carried
  load; no-op (doesn't blank) when `newLoad == null` so clearing the field doesn't wipe the
  default for sets not yet reached.
- `SessionContent` holds `carriedLoadByMovement` (`remember(session.id)`, so it survives cursor
  advances but resets per session) and updates it on every `onLoadChange` for the current set's
  Load field, keyed by `currentExercise.movement_id`. The current set's `setLoad` prefill now
  calls `effectiveLoadPrefill` instead of `prefillWeight(currentSet?.target_load)` directly.

This directly reproduces then fixes the Day-1 bug: prescribed 170, athlete enters 175 on set 1 →
sets 2 & 3 (same exercise, still showing `target_load = 170` from the server) now prefill 175
instead of reverting to 170 when the cursor reaches them.

## J — idempotent set logging (no double-submit)

- `data/local/CaptureDao.kt`:
  - New `@Query DELETE ... WHERE sessionId = :sessionId AND plannedSetId = :plannedSetId` —
    `deleteSetLogForPlannedSet`.
  - New `@Transaction suspend fun upsertSetLog(d: SetLogDraft)` (Kotlin default interface
    method): deletes any prior row for `(sessionId, plannedSetId)` when `plannedSetId != null`,
    then inserts. Wrapped in `@Transaction` for atomicity. Rows with a null `plannedSetId` are
    left as plain inserts (nothing to key an upsert on).
  - **Idempotency key: `(sessionId, plannedSetId)`.** No new column, index, or schema/version
    bump — the delete-then-insert pair does the dedup work directly, avoiding any Room migration
    risk on the existing `version = 1` database.
- `data/repo/CaptureRepo.kt` — `logSet` now calls `dao.upsertSetLog(d)` instead of
  `dao.insertSetLog(d)`. This is the actual fix: every write path (`logWorkingSet`,
  `editLoggedSet`, and any future caller of `CaptureRepo.logSet`) now goes through the upsert, so
  a duplicate submission of the same planned set (double-tap, retry after a transient error, or
  an explicit correction) always replaces the prior row instead of appending a second one.
- Verified the cursor-advance math in `logWorkingSet` is unaffected by a duplicate call: the
  cursor's next-id lookup (`flattenedPrescription.indexOfFirst { it.id == plannedSetId }`) is
  idempotent by construction (same input → same output), so calling `logWorkingSet` twice with
  the same `plannedSetId` converges to the same cursor position rather than double-advancing —
  no additional cursor guard was needed beyond the DAO-level upsert.
- **Concern for the user:** this fixes 100% of duplicate rows created via *this* client going
  forward. It does **not** retroactively deduplicate the 7 rows already created during the Day-1
  incident (those already reached the server via `submit()`, if that session was submitted) — a
  server-side dedup pass on `set_logs` (e.g. unique constraint or a one-time cleanup on
  `(session_id, planned_set_id)`) may still be worth doing if that batch was already ingested.
  Not implemented here — out of scope for a client-only batch, and no server code was touched.

## Tests added (TDD, failing-first where practical)

`app/src/test/java/com/jauschua/ironlogv2/ui/capture/CaptureViewModelTest.kt` (+6 tests, wrote
failing versions against the pre-fix `insertSetLog`/no-`loggedSetActuals` code first, confirmed
RED, then implemented):
- `logged_set_exposes_actual_and_edit_round_trip_updates_in_place` — B, exercises
  `loggedSetActuals` + `editLoggedSet` round-trip, asserts still 1 Room row after the edit.
- `editLoggedSet_does_not_move_the_cursor` — B, guards against the cursor-rewind risk called out
  above.
- `editLoggedSet_without_tap_is_rejected_for_working_role` — B, gate parity with `logWorkingSet`.
- `logging_same_planned_set_twice_yields_one_row_not_a_duplicate` — J, VM-level double-submit.
- `repo_logSet_upserts_by_session_and_planned_set_id` — J, repo/DAO-level upsert, direct.
- `FakeGatedDao` updated to implement the new `deleteSetLogForPlannedSet` abstract method
  (required by the `CaptureDao` interface change); verified the existing
  `logWorkingSet_commits_before_advance_ordering` gated test still passes unchanged (the delete
  is a synchronous no-op before the gated insert, so ordering semantics are unaffected).

`app/src/test/java/com/jauschua/ironlogv2/ui/capture/CaptureScreenLogicTest.kt` (+11 tests, pure
function tests, no Compose needed — matches this file's existing convention):
- `loggedActualLine_*` (4 tests) — B, display line composition incl. omitting missing pieces.
- `tapResultLabel_null_for_unknown_or_missing_tap` — B.
- `withCarriedLoad_*` (3 tests) and `effectiveLoadPrefill_*` (3 tests) — F, carry-forward map
  semantics and prefill precedence, including the exact Day-1 repro (170 prescribed, 175
  entered → 175 prefills, not 170).

## Build + test results

- `./gradlew :app:testDebugUnitTest` — **BUILD SUCCESSFUL**, all suites green (12 tests in
  `CaptureViewModelTest`, 54 in `CaptureScreenLogicTest`, all other Capture-related suites
  unchanged and passing: `CaptureViewModelReviewTest` 6, `CaptureReviewDaoTest` 3,
  `CaptureDurabilityTest` 1, `CaptureRepoReviewTest` 4, `CaptureRepoTest` 2).
- `./gradlew :app:assembleDebug` — **BUILD SUCCESSFUL**.

## Notes on scope discipline

- `app/build.gradle.kts` had a pre-existing uncommitted local change (server base URL,
  `myflix.media:8000` → `192.168.1.7:8000`) unrelated to this batch, present before I started.
  Left untouched and **not staged/committed** per the task constraint.
- No new Gradle dependency added.
- No schema/DB version change — J's fix is DAO-query-level only (delete-then-insert in a
  `@Transaction`), so there's no migration risk to the existing `capture.db` (version 1).
