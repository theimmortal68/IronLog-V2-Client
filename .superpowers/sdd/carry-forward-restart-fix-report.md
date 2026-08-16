# Carry-forward survives restart — fix report

## Root cause confirmation

`CaptureScreen.kt`'s `SessionContent` holds `carriedLoadByMovement`/`carriedRepsByMovement` as
plain Compose state:

```kotlin
var carriedLoadByMovement by remember(session.id) { mutableStateOf<Map<Int, Double>>(emptyMap()) }
var carriedRepsByMovement by remember(session.id) { mutableStateOf<Map<Int, Int>>(emptyMap()) }
```

These are written to on every `onLoadChange`/`onRepsChange` keystroke on the CURRENT cursor set
(via `withCarriedLoad`/`withCarriedReps`) and read via `effectiveLoadPrefill`/`effectiveRepsPrefill`
to pre-fill later unlogged sets of the same exercise. The map itself is never persisted anywhere.

Confirmed live via `adb logcat -s CarryFwd:D`: at the exact same cursor position (`cursor=648`),
the diagnostic log showed `carried=3.0` at one timestamp and `carried=null` five minutes later —
the cursor stayed correctly positioned (proving `CaptureViewModel.load()`'s `resumeSet` logic is
fine) while the carry map reset to empty in between, consistent with a process kill while the app
was backgrounded (the athlete's cursor rotates through other exercises during a giant set,
spending real wall-clock time away from any one exercise, making a background kill plausible
mid-session). Every exercise in the athlete's session that day had perfectly uniform prescribed
targets across all 3 sets, ruling out the "plan isn't flat" path as the cause (confirmed
separately, per the task brief).

## What was implemented and why

**Option B** (screen-local derivation) was chosen over Option A (new `CaptureViewModel` StateFlows):
`CaptureViewModel.load()` already reconstructs `_loggedSetActuals` (a `Map<Pair<plannedSetId,
sideIndex>, LoggedSetActual>`) from Room's persisted draft rows via `repo.loggedActualsFor(session.id)`
— and `CaptureScreen.kt`'s `SessionContent` already receives that exact map as its `loggedSetActuals`
parameter. No new VM state, no new StateFlow, no new composable parameter was needed; the existing
data already contains everything required to rebuild the carry-forward maps. Extending the VM
(Option A) would have duplicated data that already flows through the screen.

### New pure functions (`CaptureScreen.kt`)

```kotlin
internal fun reconstructCarriedLoad(
    session: SessionDetailResponse,
    loggedSetActuals: Map<Pair<Int, Int>, LoggedSetActual>,
): Map<Int, Double>

internal fun reconstructCarriedReps(
    session: SessionDetailResponse,
    loggedSetActuals: Map<Pair<Int, Int>, LoggedSetActual>,
): Map<Int, Int>
```

For each `plannedSetId` key in `loggedSetActuals` with a non-null `actualLoad`/`actualReps`, the
planned set is resolved (via a `plannedSetId -> (movementId, set_index)` lookup built from
`session.groups`) and grouped by `movementId`; the value taken is from the entry with the highest
`set_index` per movement.

**Why `set_index`, not Room `draftId` / insertion order, is the correct "most recent" proxy:**
`editLoggedSet` (used to correct an *already-logged*, past set) never calls `withCarriedLoad`/
`withCarriedReps` — only live edits to the CURRENT cursor set do. So the live in-session carry map's
recency always tracks cursor/`set_index` order. But `CaptureDao.upsertSetLog` does a delete+insert
on every write (including corrections), so a past-set correction bumps that row's `draftId` to the
newest value in the table — using `draftId`/insertion order for reconstruction would have picked an
edited-after-the-fact EARLIER set's value as "most recent," which is exactly the "subtler, worse
bug" the task brief warned about. `set_index` is immune to this because it's fixed at prescription
time and never changes on edit.

### Seeding (`SessionContent`)

```kotlin
var carriedLoadByMovement by remember(session.id) {
    mutableStateOf(reconstructCarriedLoad(session, loggedSetActuals))
}
var carriedRepsByMovement by remember(session.id) {
    mutableStateOf(reconstructCarriedReps(session, loggedSetActuals))
}
```

**Race-condition check:** `CaptureViewModel.load()` sets `_loggedSetActuals.value` BEFORE flipping
`_state.value` to `UiState.Success(session)` (same coroutine, no suspension between the two
statements — see `CaptureViewModel.kt` lines ~251–256). `SessionContent` only composes with a
non-null `session` once `state` is `UiState.Success`, and `loggedSetActuals` is read from its own
`collectAsStateWithLifecycle()` in `CaptureScreen` — by the time `SessionContent` first composes
with the loaded session, `loggedSetActuals` already holds the reconstructed persisted data. The
`remember(session.id)` seed therefore never races an empty map.

## What was tested and results

### Pure-function unit tests — `CaptureScreenLogicTest.kt` (7 new tests)

- `reconstructCarriedLoad_uses_the_highest_set_index_logged_set_per_movement` — two logged sets for
  one movement (set_index 0 and 1); asserts the carried value comes from set_index 1, NOT set_index
  0 (the wrong-direction bug the task brief specifically warned about).
- `reconstructCarriedReps_uses_the_highest_set_index_logged_set_per_movement` — same, for reps.
- `reconstructCarriedLoad_tracks_each_movement_independently_in_a_multi_exercise_session` —
  giant-set-shaped fixture (two movements interleaved), asserts each movement's carried value comes
  from ITS OWN highest-`set_index` logged set.
- `reconstructCarriedLoad_ignores_rows_with_a_null_actual_load` — a logged set with a null
  `actualLoad` must not produce a null-derived map entry.
- `reconstructCarriedLoad_is_empty_when_nothing_has_been_logged` — empty `loggedSetActuals` in ->
  empty maps out, for both load and reps.

**RED/GREEN evidence:** I temporarily changed `entries.maxBy { it.second }.third` to
`entries.minBy { it.second }.third` (i.e., re-introduced the "picks the earlier set" bug) in both
functions and re-ran the suite — 4 tests failed exactly as expected (the 3 pure-function tests plus
the VM integration test below), confirming the new tests actually exercise the fix rather than
passing vacuously. Reverted to `maxBy` (correct) and re-ran — all green.

### Integration test — `CaptureViewModelTest.kt` (1 new test)

`load_after_simulated_relaunch_reconstructs_carry_forward_maps_from_persisted_actuals`:

- Builds a 3-exercise STRAIGHT-group session (mirrors the existing `exercise()`/`repoForLoad()`
  fixture helpers already used by this test class).
- Inserts real Room `SetLogDraft` rows directly via `db.captureDao().insertSetLog(...)` — simulating
  sets logged in a "previous process" — for movement 1 (TWO sets, set_index 0 then 1, to prove
  recency-by-set_index) and movement 2 (one set). Movement 3 is left unlogged (not yet reached
  before the simulated kill).
- Constructs a **brand-new** `CaptureViewModel` (no prior in-memory carry state — this IS the
  "app relaunch" simulation) and calls `vm.load()` once.
- Applies `reconstructCarriedLoad`/`reconstructCarriedReps` to the VM's post-load `session` +
  `loggedSetActuals.value` (i.e., exactly what `SessionContent`'s `remember(session.id)` seed would
  compute) and asserts: movement 1 → 175.0/8 (from the LATER set, not the earlier 170.0/8 set),
  movement 2 → 60.0/10, movement 3 → absent from the map entirely (not defaulted to anything).

This exercises the full persisted-data path end to end — real Room writes, a real `load()` call on
a fresh VM instance, real reconstruction — without needing a Compose test rule, consistent with this
test class's existing style (no Compose tests here; pure Compose logic lives in
`CaptureScreenLogicTest.kt` and is tested there instead).

### Full suite results

- `./gradlew :app:compileDebugKotlin` — clean.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` — **BUILD SUCCESSFUL**.
- Test XML totals: `CaptureScreenLogicTest` 95 tests (was 88 before this change, +7), 0
  failures/errors/skipped. `CaptureViewModelTest` 24 tests (was 23, +1), 0 failures/errors/skipped.
  Full suite across all files: 289 tests total, all passing.

## Files changed

- `app/src/main/java/com/jauschua/ironlogv2/ui/screens/capture/CaptureScreen.kt` — added
  `CarryLookupEntry`/`carryLookup`, `reconstructCarriedLoad`, `reconstructCarriedReps`; seeded the
  two `remember(session.id) { mutableStateOf(...) }` blocks from them instead of `emptyMap()`.
- `app/src/test/java/com/jauschua/ironlogv2/ui/capture/CaptureScreenLogicTest.kt` — 7 new pure-
  function tests + fixture helpers (`exerciseWithSets`, `sessionOf`).
- `app/src/test/java/com/jauschua/ironlogv2/ui/capture/CaptureViewModelTest.kt` — 1 new end-to-end
  relaunch-simulation test.

No other files touched — scope stayed within `CaptureViewModel.kt`'s data (read-only, no VM
changes needed) and `CaptureScreen.kt` (plus their tests), as the task's escalation boundary
required.

## Self-review findings

- **Completeness:** Both load AND reps maps are seeded, and the reconstruction covers EVERY
  movement with a logged actual in the session (not just the current exercise) — `carryLookup`
  scans all groups/exercises/planned_sets, and the grouping in `reconstructCarriedLoad`/`Reps` is
  keyed by movement, not by exercise-in-scope. Verified explicitly by the multi-exercise test.
- **Correctness of "most recent":** Deliberately used `set_index`, not Room `draftId`/insertion
  order — see the reasoning above. This was the one place a plausible-but-wrong implementation
  (draftId-based) would have passed a naive test but broken on the real edit-bumps-draftId case;
  the tests target `set_index` explicitly, and the RED/GREEN check above proves they'd catch the
  wrong-direction bug either way.
- **Null handling:** A logged set with a null `actualLoad` (or `actualReps`) is skipped for that
  specific map (independently — a set can be missing load but have reps, or vice versa) rather than
  producing a bogus entry or blocking a later set's real value. Covered by
  `reconstructCarriedLoad_ignores_rows_with_a_null_actual_load`.
- **Race condition:** Verified by reading `CaptureViewModel.load()`'s statement order (loggedSetActuals
  set before state flips to Success, same coroutine, no suspension in between) — documented in the
  code comment on the seeding site rather than left implicit.
- **Discipline:** No refactor beyond what the fix required. Did not touch `CaptureViewModel.kt` at
  all (Option B needed no VM changes) — smaller diff than Option A would have produced, and no risk
  to the VM's already-fragile cursor logic (this session's other work found a HIGH bug there).
- **Warmup sets:** Confirmed via reading `onLoadChange`/`onRepsChange` call sites that live carry-map
  writes are NOT filtered by `is_warmup` (they fire for any current-exercise set the athlete edits,
  warmup or working) — the reconstruction intentionally mirrors this by not filtering
  `loggedSetActuals` on warmup either, so behavior after a relaunch matches behavior during a live,
  never-restarted session.

## Concerns

- None blocking. One minor observation for a possible future follow-up (not in scope here): the
  live carry map can, in principle, reflect an UNSUBMITTED keystroke (the athlete typed a value but
  never tapped "Log set") that this reconstruction can never recover after a process kill — but
  that data was never durable in the first place (it's not in Room), so there's no way to recover it
  regardless of approach; the reconstructed value in that case correctly falls back to the last
  actually-LOGGED set instead, which is the best available approximation and matches the task
  brief's stated goal ("what did the athlete most recently type" → approximated as "most recently
  logged," since only logged data survives a process kill).
