# Spec 37: Weak-Points Display

## Objective

Surface the server's weak-point assessment (`GET /weak-points`, live since 2026-07-19) in the client: a Today rollup badge and a muscle-group-grouped detail screen.

Design doc (approved, source of truth): `docs/superpowers/specs/2026-07-23-phase1-client-parity-design.md`, Feature 2.

## File Targets

- `app/src/main/java/com/jauschua/ironlogv2/data/api/dto/WeakPointModels.kt` — new file, DTOs.
- `app/src/main/java/com/jauschua/ironlogv2/data/repo/WeakPointsRepo.kt` — new file.
- `app/src/main/java/com/jauschua/ironlogv2/di/AppContainer.kt` — add `weakPointsRepo`.
- `app/src/main/java/com/jauschua/ironlogv2/ui/screens/today/TodayViewModel.kt` — new `weakPointsSummary` `StateFlow` + rollup-count helper.
- `app/src/main/java/com/jauschua/ironlogv2/ui/screens/today/TodayScreen.kt` — rollup badge, tap target to open the detail screen.
- `app/src/main/java/com/jauschua/ironlogv2/ui/screens/weakpoints/WeakPointsScreen.kt` — new file, detail screen.
- `app/src/main/java/com/jauschua/ironlogv2/ui/screens/weakpoints/WeakPointsViewModel.kt` — new file.
- `app/src/main/java/com/jauschua/ironlogv2/ui/Nav.kt` — add `Routes.WEAK_POINTS`.
- `app/src/main/java/com/jauschua/ironlogv2/ui/MainActivity.kt` — register the route (pushed from Today, not a bottom-nav tab).
- `app/src/test/java/com/jauschua/ironlogv2/ui/today/TodayLogicTest.kt` — new pure-function test for the rollup-count helper.
- `app/src/test/...` (new test file for the weak-points screen logic, mirroring this repo's existing `*LogicTest.kt` naming convention — confirm the exact convention via a repo search before naming it).

## Changes

### DTOs (`WeakPointModels.kt`, new file)

Mirror the server's schema exactly (verified live via `curl http://myflix:8000/weak-points`):

```kotlin
@Serializable data class WeakMovementOut(
    val movement_id: Int,
    val name: String,
    val stalled: Boolean,
    val lagging: Boolean,
    val growth_rate: Double? = null,
)

@Serializable data class MuscleGroupSummaryOut(
    val muscle: String,
    val weak_count: Int,
    val total_count: Int,
    val weak_movements: List<WeakMovementOut>,
)

@Serializable data class WeakPointAssessmentOut(
    val muscle_groups: List<MuscleGroupSummaryOut>,
    val movements: List<WeakMovementOut>,
)
```

### `WeakPointsRepo.kt` (new file)

Mirror `CardioLogRepo.kt`'s shape exactly — a single GET:

```kotlin
class WeakPointsRepo(private val apiClient: ApiClient) {
    suspend fun assessment(): Result<WeakPointAssessmentOut> = runCatchingApi {
        apiClient.http.get("/weak-points").body()
    }
}
```

### `AppContainer.kt`

Add `val weakPointsRepo: WeakPointsRepo by lazy { WeakPointsRepo(apiClient) }` next to `cardioLogRepo`.

### `TodayViewModel.kt`

Add:

```kotlin
private val _weakPointsSummary = MutableStateFlow<WeakPointAssessmentOut?>(null)
val weakPointsSummary: StateFlow<WeakPointAssessmentOut?> = _weakPointsSummary.asStateFlow()
```

Best-effort fetch in `load()`, same shape as `refreshCardioSummary()`:

```kotlin
private fun refreshWeakPoints() {
    viewModelScope.launch {
        weakPointsRepo.assessment()
            .onSuccess { a -> _weakPointsSummary.value = a }
    }
}
```

Add a pure, file-level helper (mirroring `reviewButtonLabel`'s style) for the rollup count:

```kotlin
/** Total weak movements across all muscle groups, for the Today badge. Zero (or a null
 *  assessment) means no badge renders. Pure and file-level so it's unit-testable without
 *  Compose or the ViewModel. */
fun weakPointBadgeCount(assessment: WeakPointAssessmentOut?): Int =
    assessment?.muscle_groups?.sumOf { it.weak_count } ?: 0
```

### `TodayScreen.kt`

Render a badge/row only when `weakPointBadgeCount(...) > 0` (given the live data returned all-zero counts as of this spec's writing, this badge will not visibly render until real data flags something — do not remove or special-case this, it's the correct, intended behavior, not a bug). Tapping navigates to `Routes.WEAK_POINTS`.

### `WeakPointsScreen.kt` / `WeakPointsViewModel.kt` (new files)

`WeakPointsViewModel` fetches `weakPointsRepo.assessment()` once on init (mirror the loading/error state-machine shape already used by other simple detail screens in this app, e.g. `CardioHistoryScreen`'s ViewModel — read that file first and match its shape rather than inventing a new one).

`WeakPointsScreen` renders one section per `MuscleGroupSummaryOut` **only for muscle groups with `weak_count > 0`** (skip rendering a header for a clean muscle group — the assessment always returns every muscle group, not just flagged ones, so this filter is required, not optional). Each rendered section shows `weak_count`/`total_count` in its header and lists that muscle group's own `weak_movements` (not the top-level `movements` array) with `stalled`/`lagging` tags per movement (e.g. small text chips, following whatever tag/chip pattern already exists elsewhere in this app if one does — check `CaptureScreen.kt`'s feedback-tap rendering or similar before inventing a new visual pattern).

### `Nav.kt` / `MainActivity.kt`

Add `const val WEAK_POINTS = "weak-points"` to `Routes`, and register `composable(Routes.WEAK_POINTS) { WeakPointsScreen(onBack = { nav.popBackStack() }) }` in `MainActivity.kt`'s `NavHost`, mirroring the `CARDIO_HISTORY` registration exactly. Wire Today's tap target via a new `onWeakPoints` callback parameter on `TodayScreen`, mirroring `onHistory`/`onLogCardio`.

## Edge Cases

- All muscle groups clean (`weak_count == 0` everywhere, the current live state) — no badge on Today, and the detail screen (if reached directly, e.g. via back-navigation state restoration) should render a clear "nothing flagged" empty state rather than a blank screen.
- `weakPointsRepo.assessment()` fails (network error, matching the best-effort pattern already established for `reviewCount`/`cardioWeeklySummary`) — Today's rollup silently stays at its previous value (0/hidden), no error surfaced on Today itself. The dedicated detail screen, if the athlete does navigate to it, should show its own error/retry state (mirroring whatever the closest existing detail-screen pattern does, e.g. `CardioHistoryScreen`).
- A movement can appear in the top-level `movements` array without appearing in any muscle group's `weak_movements` (e.g. `stalled=false, lagging=false`) — this is normal, not a data bug; the detail screen doesn't need to reconcile or cross-check the two lists against each other.

## Dependencies

None as far as file overlap goes with spec 36 (readiness) — but both touch `TodayViewModel.kt`/`TodayScreen.kt`/`AppContainer.kt`. Per the design doc's scope-split note, run this batch sequentially after spec 36 merges, not concurrently, to avoid racing the same shared files (mirrors this repo's own established call on the cardio-log batch).

## Verification

- `./gradlew testDebugUnitTest` — new tests (`weakPointBadgeCount` edge cases: empty list, all-zero, mixed) pass; full suite green, zero regressions.
- `./gradlew :app:assembleDebug` — builds clean.
- Manual: re-confirm the live shape via `curl http://myflix:8000/weak-points` before dispatch if more than a trivial amount of time has passed since this spec was written (the assessment recomputes on each real analyzed session, so its content can change, though its shape should not).
