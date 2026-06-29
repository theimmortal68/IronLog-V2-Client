# Task 7 Report — WizardViewModel (first-run wizard)

Status: DONE

## What was built
`app/src/main/java/com/jauschua/ironlogv2/ui/screens/wizard/WizardViewModel.kt` — the first-run
wizard state holder, mirroring `AutoregulateViewModel`/`CaptureViewModel` (UiState + Factory via
`AppContainer`).

### Shape
- `WizardUi(programId, programName, actionMovements, freshMovements)` — `actionMovements` = trust !=
  FRESH (UNKNOWN to fill, STALE to confirm/adjust); `freshMovements` = trust == FRESH, summarized.
- StateFlows exposed to the screen: `state: UiState<WizardUi>`, `entered: Map<Int,String>`,
  `needsAttentionCount: Int`, `readyToStart: Boolean`, `submitError: String?`,
  `startResult: StartProgramResponse?`.
- Note: `needsAttentionCount`/`readyToStart` are kept as their own live StateFlows (not duplicated
  inside `WizardUi`) so a resolve updates the single server-driven source of truth without rebuilding
  the loaded snapshot — avoids drift.

### Behavior
- `load(programId)` → `repo.state(programId)`; splits movements into action/fresh; seeds `entered`
  (STALE → `prefill_value.toString()`, UNKNOWN → `""`); adopts the server's `needs_attention_count`
  and `ready_to_start`; `UiState.Success(WizardUi)`. Failures → `UiState.Error(humanMessage)`.
- `setEntered(movementId, value)` → updates the per-movement entered map, clears `submitError`.
- `resolve()` → collects **touched** entries only (non-blank entered text) into
  `List<WizardResolution>(movement_id, value)`, parses each to Double (non-numeric → synchronous
  `submitError = "Entered value must be a number"`, no round-trip), calls `repo.resolve(...)`, and
  **adopts `needs_attention_count` / `ready_to_start` verbatim from `WizardResolveResponse`**.
- `start()` → guarded by `if (!_readyToStart.value) return`; calls `repo.start(programId)`; on
  success sets `startResult` so the screen can navigate.

### Server-driven gate (key invariant)
The completion gate is **never recomputed client-side**. The VM consumes the server's
`needs_attention_count` and `ready_to_start` directly from both `WizardStateResponse` and
`WizardResolveResponse` (the server owns trust via `compute_load_trust`). `start()` only checks the
server-provided `_readyToStart` flag; there is no client tally of remaining movements.

### resolve = touched-only
Only movements with a non-blank entered value are sent. UNKNOWN ones the user filled and STALE ones
(prefilled/confirmed) qualify; blank/untouched entries are skipped. Empty batch → no-op.

## AppContainer wiring
`app/src/main/java/com/jauschua/ironlogv2/di/AppContainer.kt`:
`val wizardRepo: WizardRepo by lazy { WizardRepo(apiClient) }` (mirrors `captureRepo`/`autoregRepo`);
added `import com.jauschua.ironlogv2.data.repo.WizardRepo`. The `Factory` companion builds from
`app.container.wizardRepo`.

## Test
`app/src/test/java/com/jauschua/ironlogv2/ui/wizard/WizardViewModelTest.kt` — MockEngine-backed
`WizardRepo`, path-branching engine (wizard-state / wizard-resolve / start). 4 tests:
1. `load_splitsMovementsAndShowsServerCount` — STALE+UNKNOWN action / FRESH summarized; count=2,
   not ready; STALE prefilled `135.0`, UNKNOWN empty.
2. `fillingUnknownThenResolve_decrementsCountAndFlipsReadyFromServer` — fill UNKNOWN + resolve →
   count goes 2→0 and readyToStart flips true, both from the resolve response.
3. `start_isNoOpUntilServerSaysReady` — `start()` is a no-op (startResult null) until the server
   flips ready_to_start; then it activates (started/active true).
4. `resolve_nonNumericEntrySurfacesValidationError` — non-numeric entry → submitError set
   synchronously, no round-trip, gate untouched.

### Test harness note (TDD red→green)
Red first: with `runTest` + `advanceUntilIdle`, the VM stayed `Loading` — the ktor MockEngine call
resumes on a background dispatcher, so virtual-time advancing didn't wait for it (and `withTimeout`
under `runTest` fired against the virtual clock → TimeoutCancellationException). Fixed in the test
harness only: Main = `UnconfinedTestDispatcher` (eager fire-and-forget `viewModelScope.launch`),
test bodies on `runBlocking` (real wall-clock), and a `StateFlow.await { predicate }` helper
(`withTimeout(5s) { first(...) }`). Green after that — no production code was weakened to pass.

## Gradle tails
```
./gradlew testDebugUnitTest --tests "*WizardViewModel*"   → BUILD SUCCESSFUL, 4/4
./gradlew testDebugUnitTest (full)                         → BUILD SUCCESSFUL
```

## Test count
27 total = 23 baseline + 4 new. All green.
- ModelsTest 6, CaptureDurabilityTest 1, AutoregRepoTest 1, CaptureRepoTest 2, LibraryRepoTest 6,
  WizardRepoTest 3, CaptureViewModelTest 4, **WizardViewModelTest 4**.

## Commit
`feat(wizard): WizardViewModel (needs-attention state, server-driven ready_to_start, resolve/start)`
hash: db25107
