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
  - A past **bilateral** card is `clickable` to reopen an edit block (load/reps/tap inputs +
    "Save correction" button), which calls `vm.editLoggedSet(...)`. Editing state (`editingSetId`,
    `editLoad`, `editReps`, `editTap`) lives in `SessionContent`, prefilled from
    `loggedSetActuals[editingSetId]` when a card is opened.
  - **Unilateral logged cards are NON-editable** (review fix — see below). Their logged-actual
    DISPLAY is kept (the `Actual:` line still shows); only tap-to-edit is gated off.

### Unilateral edit gate (review CRITICAL fix)

A unilateral set logs TWO rows (sideIndex 0 + 1) under one `plannedSetId`, but B's edit path
(`editLoggedSet` / `loggedSetActuals` / `existingLog`) is keyed by `plannedSetId` alone. So the
ordinary "tap a past card → fix a typo" flow on a unilateral card would read side 1 (smallest
`draftId`) via `existingLog` and write the correction back with `sideIndex = 0` — silently
overwriting side 1's real data with the side-2 value the card displays, leaving side 2 untouched.
Fix (minimal + safe), gated at two layers:

- `CaptureScreen.kt` — new pure `isSetEditable(isPast, unilateral) = isPast && !unilateral`. The
  logged card's `clickable` and its `onCardTap`/`isEditing` are all driven by it, so a unilateral
  past card is simply not tappable (no edit block ever opens). The `Actual:` display line is
  unaffected.
- `CaptureViewModel.editLoggedSet` — matching safety net: early-returns (no write) when
  `plannedSetId in unilateralSetIds`, so `editLoggedSet` can never mutate a unilateral row even
  if reached by some other path.
- Both spots carry a `// side-aware unilateral edit = follow-on` comment: the real fix is keying
  `loggedSetActuals` / `editingSetId` / `existingLog` by `(plannedSetId, sideIndex)` end-to-end
  plus per-side cards. Deliberately **not** built now.
- Pre-existing display note still holds: `loggedSetActuals` keeps only the latest write per
  `plannedSetId`, so a unilateral card's `Actual:` line shows side 2's value. Acceptable for now
  (display only; no data loss), folded into the same follow-on.

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

**Idempotency key: `(sessionId, plannedSetId, sideIndex)`.** The `sideIndex` component is the
review fix — see "How unilateral sides are distinguished" below.

- `data/local/CaptureEntities.kt` — added `val sideIndex: Int = 0` to `SetLogDraft` (side
  discriminator; default 0 keeps every existing bilateral caller/row unchanged).
- `data/local/CaptureDatabase.kt` — bumped `@Database(version = 2)` and added
  `CAPTURE_MIGRATION_1_2` (`ALTER TABLE setlog_draft ADD COLUMN sideIndex INTEGER NOT NULL
  DEFAULT 0`). Non-destructive on purpose: `capture.db` is the offline outbox, so a destructive
  fallback could drop a session logged just before an app update.
- `di/AppContainer.kt` — `.addMigrations(CAPTURE_MIGRATION_1_2)` on the builder.
- `data/local/CaptureDao.kt`:
  - `deleteSetLogForPlannedSetSide(sessionId, plannedSetId, sideIndex)` (replaces the earlier
    side-agnostic delete).
  - New `setLogForPlannedSet(sessionId, plannedSetId): SetLogDraft?` — reads back the stored row
    so an edit can preserve unsurfaced fields (see the felt-peak fix below).
  - `@Transaction suspend fun upsertSetLog(d: SetLogDraft)` now deletes on
    `(sessionId, plannedSetId, sideIndex)` then inserts. Null-`plannedSetId` rows stay plain
    inserts.
- `data/repo/CaptureRepo.kt` — `logSet` calls `dao.upsertSetLog(d)`; new
  `existingLog(sessionId, plannedSetId)` for the edit path.
- `ui/screens/capture/CaptureViewModel.kt`:
  - `logWorkingSet` computes `sideIndex = if (plannedSetId in unilateralSetIds) unilateralSideCount
    else 0` and writes it. `unilateralSideCount` is 0 while side 1 is being written and 1 while
    side 2 is (it's incremented AFTER the write), so it's exactly the side being committed.
  - `editLoggedSet` reads `repo.existingLog(...)` and carries the original row's `feltPeak`
    (`feltPeak ?: existing?.feltPeak`), `rpeNumeric`, `actualUnassistedReps`,
    `actualAssistedReps`, `actualPlates`, `bandPairId`, and `sideIndex` into the corrected row.

### How unilateral sides are distinguished (review CRITICAL fix)

There was **no** existing per-side discriminator — a unilateral exercise logs two rows with the
same `plannedSetId`, `setIndex`, `setRole`, differing only in the actuals. The original J upsert
keyed only on `(sessionId, plannedSetId)`, so the side-2 write deleted side 1 → side 1's
load/reps/tap was silently lost, locally and from `submit()`. Fix: added the `sideIndex` column
and made it part of the upsert key. The value comes straight from the VM's existing
`unilateralSideCount` state (0 = side 1 / all bilateral sets, 1 = side 2) at write time — no new
VM state, no change to the two-tap-per-unilateral logging flow. Result:
- true double-submit of the same set **and side** → one row (the dedup we want);
- unilateral side 1 (sideIndex 0) and side 2 (sideIndex 1) **both** survive locally and both reach
  `submit()` (2 `SetLogIn` entries → volume preserved).

`submit()` needs no change — it already maps each stored `SetLogDraft` row to one `SetLogIn`, so
restoring both rows restores the two-entry payload; no `sideIndex` is sent to the server (not
part of `SetLogIn`; server API untouched, still backward-compatible).

### Felt-peak preservation on edit (review IMPORTANT fix)

Because the upsert is a full-row replace and the edit UI only surfaces load/reps/tap,
`editLoggedSet` used to default `feltPeak = null`, wiping an HT/band-composite set's felt-peak on
any correction. Fixed by reading the original row via `repo.existingLog(...)` and carrying its
felt-peak (plus the other aux actual fields and its `sideIndex`) into the re-saved draft, so a
load/reps correction never nulls felt-peak.

- **Concern for the user (unchanged):** this fixes 100% of duplicate rows created via *this*
  client going forward. It does **not** retroactively deduplicate rows already created during the
  Day-1 incident and already sent to the server via `submit()` — a server-side dedup/one-time
  cleanup on `(session_id, planned_set_id)` (respecting per-side rows for unilateral movements)
  may still be worth doing if that batch was already ingested. Out of scope for a client-only
  batch; no server code was touched.

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
- `unilateral_set_keeps_both_sides_locally_and_both_reach_submit` — J review CRITICAL fix: a
  unilateral set logs 2 rows (both loads present, `sideIndex` {0,1}) AND both reach `submit()`
  (2 `planned_set_id` entries in the captured payload).
- `double_submit_of_same_unilateral_side_stays_one_row` — J review CRITICAL fix: same set+side
  collapses to one row while the other side survives (2 rows total from 3 writes).
- `editing_an_ht_set_load_preserves_its_felt_peak` — J review IMPORTANT fix: correcting load
  keeps the stored felt-peak (255.0) instead of nulling it.
- `editLoggedSet_is_a_noop_for_a_unilateral_planned_set` — B re-review CRITICAL fix: after both
  sides are logged, an edit attempt on the unilateral card writes nothing — 2 rows unchanged,
  both original loads {50,48} intact, no correction written (VM safety net).
- `FakeGatedDao` updated to implement the new `deleteSetLogForPlannedSetSide` and
  `setLogForPlannedSet` abstract methods (required by the `CaptureDao` interface change);
  verified the existing `logWorkingSet_commits_before_advance_ordering` gated test still passes
  unchanged (the side-keyed delete is a synchronous no-op before the gated insert, so ordering
  semantics are unaffected).

`app/src/test/java/com/jauschua/ironlogv2/ui/capture/CaptureScreenLogicTest.kt` (+14 tests, pure
function tests, no Compose needed — matches this file's existing convention):
- `loggedActualLine_*` (4 tests) — B, display line composition incl. omitting missing pieces.
- `tapResultLabel_null_for_unknown_or_missing_tap` — B.
- `isSetEditable_*` (3 tests) — B re-review CRITICAL fix: editable only for past+bilateral;
  false for past+unilateral and for not-yet-logged.
- `withCarriedLoad_*` (3 tests) and `effectiveLoadPrefill_*` (3 tests) — F, carry-forward map
  semantics and prefill precedence, including the exact Day-1 repro (170 prescribed, 175
  entered → 175 prefills, not 170).

## Build + test results

- `./gradlew :app:testDebugUnitTest` — **BUILD SUCCESSFUL**, full suite **175 tests, 0
  failures, 0 errors** (Capture-relevant: 16 in `CaptureViewModelTest`, 57 in
  `CaptureScreenLogicTest`, plus `CaptureViewModelReviewTest` 6, `CaptureReviewDaoTest` 3,
  `CaptureDurabilityTest` 1, `CaptureRepoReviewTest` 4, `CaptureRepoTest` 2 — all green).
- `./gradlew :app:assembleDebug` — **BUILD SUCCESSFUL**.

## Notes on scope discipline

- `app/build.gradle.kts` had a pre-existing uncommitted local change (server base URL,
  `myflix.media:8000` → `192.168.1.7:8000`) unrelated to this batch, present before I started.
  Left untouched and **not staged/committed** per the task constraint.
- No new Gradle dependency added; `app/build.gradle.kts` not modified by this batch.
- Schema change (review fix): `capture.db` bumped v1 → v2 to add `SetLogDraft.sideIndex`, with a
  non-destructive `ALTER TABLE ADD COLUMN` migration (`CAPTURE_MIGRATION_1_2`) so in-flight
  drafts survive an app update. This is app code (entity + migration + DI wiring), not a
  build-script change.
