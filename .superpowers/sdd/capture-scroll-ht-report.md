# Capture screen: scroll-to-current fix + hide Load input on HT sets

## Objective

Two small fixes to `CaptureScreen.kt`:
1. Scroll-to-current-set was a silent no-op because `BringIntoViewRequester` was attached
   inside a `LazyColumn` item's composable body, which never composes for off-screen items.
2. HT (band-composite) sets showed a scalar "Load (lb)" input that doesn't apply — HT athletes
   log Felt peak, not a scalar load.

## Fix 1 — scroll-to-current via LazyListState

Replaced `BringIntoViewRequester`/`bringIntoViewRequester` (removed, along with the now-unused
`ExperimentalFoundationApi` opt-in) with:
- `val listState = rememberLazyListState()`, passed to `LazyColumn(state = listState, ...)`.
- `val itemKeys = remember { mutableListOf<String>() }` — cleared at the top of the `LazyColumn`
  content lambda, then re-populated with one entry per `item(...)` call, **in the exact order
  items are added**, by placing `itemKeys.add(key)` directly alongside each `item(...)` call
  (not inside its trailing composable lambda). This works because the `LazyListScope` content
  lambda itself (the DSL that registers `item {}` calls) runs eagerly on every recomposition —
  only each item's own composable body is deferred until it's actually laid out. That's the same
  distinction that made the old `BringIntoViewRequester` approach fail: it lived inside the
  per-item body.
- `LaunchedEffect(currentPlannedSetId)` looks up `itemKeys.indexOf("set-$currentPlannedSetId")`
  and calls `listState.animateScrollToItem(index)` when the index is found (guarded by `>= 0`
  and `currentPlannedSetId != null`).

**Keyed on `currentPlannedSetId`, not `currentGroupIndex`** — this scrolls to the exact
current-set card (more precise than the group header), correctly handles GIANT_SET groups where
the cursor advances between exercises within the same group, and does not depend on
`restRemainingSeconds` at all, so it never fires on rest-timer ticks — only on cursor advance.

`SetCard`'s `modifier: Modifier = Modifier` parameter (previously carrying the
`bringIntoViewRequester` modifier from the call site) was dropped since it's now always the
default; `Card(modifier = Modifier...)` is called directly.

## Fix 2 — hide Load (lb) input on HT sets

In `SetCard`'s current-set input block, where `isHtSet` was already computed
(`plannedSet.target_plates != null || plannedSet.band_config != null`):
- HT sets now render only the Reps field (full width) — no Load field.
- Non-HT sets keep the original Load + Reps row (`Modifier.weight(1f)` each).
- Felt peak + the three-state tap buttons are unchanged (still shown for HT working sets).

`logWorkingSet`'s `actualLoad` param is already `Double?` (nullable) in
`CaptureViewModel.kt:250`, and for HT sets `setLoad` is seeded from
`prefillWeight(currentSet?.target_load)`, which is blank when `target_load` is null (already the
case for HT sets per the task's note that target_load was previously cleared for them) — with the
field hidden, the user can't type into it and `setLoad.toDoubleOrNull()` naturally passes `null`.
The mandatory-tap gate (`logEnabled = !tapRequired || selectedTap != null`) is untouched and still
governs whether "Log set" is enabled.

## Scope

Only `CaptureScreen.kt` touched. No logging/cursor logic changed beyond the scroll-state
replacement; no new Gradle dependency; `app/build.gradle.kts` (a pre-existing unrelated local
change to `SERVER_BASE_URL`) was left untouched and NOT committed.

## Verification

- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL
- `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL (existing `CaptureScreenLogicTest` suite
  covers the pure helper functions only — `SetCard`/`SessionContent` aren't unit-tested directly,
  per that file's own doc comment; no new tests added since the brief scoped this as a build-gated
  UI fix, not new pure logic).

## Concern

Manual on-device/emulator verification of the actual scroll animation and the HT-set input
layout was not performed in this pass (no device attached) — the fix is build- and
unit-test-verified only, per the task's stated build gate.
