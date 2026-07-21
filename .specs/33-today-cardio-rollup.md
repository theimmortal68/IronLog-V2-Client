# Spec 33: Today screen cardio weekly rollup

## Objective
Surface a "🏃 Cardio: N/2 this week" rollup line on the Today screen, tappable to open the cardio log entry screen (`Routes.CARDIO_LOG`, added by spec 32).

## File targets
- Modify: `app/src/main/java/com/jauschua/ironlogv2/ui/screens/today/TodayScreen.kt`
- Modify: `app/src/main/java/com/jauschua/ironlogv2/ui/screens/today/TodayViewModel.kt`
- Modify: `app/src/main/java/com/jauschua/ironlogv2/ui/MainActivity.kt`

## The fix

### `TodayViewModel.kt`
Add a `cardioWeeklySummary: StateFlow<CardioWeeklySummaryOut?>` (nullable — `null` while unloaded, matching this file's existing best-effort-fetch pattern for `reviewCount`), fetched the same best-effort way `refreshReviewCount()` fetches `reviewCount` — never surfaces an error, silently leaves the prior value on failure:

```kotlin
private val _cardioWeeklySummary = MutableStateFlow<CardioWeeklySummaryOut?>(null)
val cardioWeeklySummary: StateFlow<CardioWeeklySummaryOut?> = _cardioWeeklySummary.asStateFlow()
```

Add a `cardioLogRepo: CardioLogRepo` constructor parameter (alongside the existing `generateRepo`/`captureRepo`/`notesRepo`), wired into the `Factory` from `app.container.cardioLogRepo` (spec 31's DI wiring), matching the exact existing constructor-injection style.

Add a private `refreshCardioSummary()` method mirroring `refreshReviewCount()`'s exact shape, called from `load()` alongside the existing `refreshReviewCount()` call:
```kotlin
private fun refreshCardioSummary() {
    viewModelScope.launch {
        cardioLogRepo.weeklySummary()
            .onSuccess { summary -> _cardioWeeklySummary.value = summary }
    }
}
```
Call `refreshCardioSummary()` from `load()` right after the existing `refreshReviewCount()` call.

### `TodayScreen.kt`
Add an `onLogCardio: () -> Unit` parameter to `TodayScreen`'s signature (alongside `onContinue`/`onHistory`/`onReview`). Collect `vm.cardioWeeklySummary` via `collectAsStateWithLifecycle()` in the Composable body. Render a rollup line ABOVE the existing `when (val s = state)` content block (visible regardless of which `TodayUiState` is active — a `Row` or `Card` at the top of the `Surface`, before the state-dependent content) — a small tappable row:

```kotlin
cardioSummary?.let { summary ->
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp).clickable(onClick = onLogCardio),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("🏃 Cardio: ${summary.count}/${summary.target} this week")
    }
}
```
(Exact layout/styling is a judgment call — match this file's existing spacing/typography conventions, e.g. `MaterialTheme.typography.bodyMedium`. The functional requirement is: visible near the top, tappable, calls `onLogCardio` on tap, shows nothing while `cardioSummary` is `null` — i.e. don't show a placeholder/loading state for this line, it either has data or doesn't render.)

Add the `Modifier.clickable` import if not already present in this file.

### `MainActivity.kt`
Wire the new `onLogCardio` parameter at `TodayScreen`'s call site (the existing `composable(Routes.TODAY) { TodayScreen(...) }` block) to navigate to the route spec 32 registered:
```kotlin
onLogCardio = { nav.navigate(Routes.CARDIO_LOG) },
```
No other changes to this file in this spec.

## Edge cases
- `cardioWeeklySummary` starts `null` (unloaded) and the rollup line simply doesn't render until the best-effort fetch succeeds — same UX contract as the existing `reviewCount`/Review-button-badge pattern (badge shows nothing meaningful until loaded, no error state).
- The rollup line must render across EVERY `TodayUiState` variant (Loading, HasPlanned, NoSession, Preview, etc.) — it's not state-dependent, unlike the `when (val s = state)` block below it. Place it outside/above that `when` block, not nested inside any single branch.
- Returning from the cardio-log entry screen (after a successful submit, via `onBack`/`popBackStack`) does NOT automatically refresh `cardioWeeklySummary` in this spec — that would require re-triggering `vm.load()` on screen resume, which is out of scope here (acceptable staleness: the rollup updates on the NEXT natural `load()` call, e.g. next app foreground/Today-tab-revisit). Do not add a `LaunchedEffect`-on-resume refresh mechanism unless it's trivial to wire through the existing `NavHost` — if it requires new machinery, skip it and note the staleness as a known limitation in the commit message.

## Dependencies
Depends on spec 31 (`CardioLogRepo`/DTOs) and spec 32 (`Routes.CARDIO_LOG` registered in the nav graph) merged first.

## Verification
- Full local test suite green: `./gradlew testDebugUnitTest --rerun`, cross-checked via `app/build/test-results/testDebugUnitTest/TEST-*.xml` per this repo's established gradle-cache-distrust convention. No new unit tests required for this spec specifically (the added logic is thin ViewModel wiring + a Composable rendering condition, not pure branching logic worth a dedicated test — matches this file's existing test coverage, which doesn't unit-test `refreshReviewCount()` either).
- Manual/live verification (not part of the merge gate): launch the app, confirm the Today screen shows "🏃 Cardio: 0/2 this week" (matching the live `GET /cardio-log/weekly-summary` response confirmed via curl on 2026-07-21), tap it, confirm it navigates to the cardio log entry screen.
