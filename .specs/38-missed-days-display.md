# Spec 38: Missed-Days Display + Actions

## Objective

Surface the server's missed-workout records (`GET /missed-days`, live since 2026-07-20) in the client: a Today rollup badge and a detail screen with acknowledge/reschedule actions.

Design doc (approved, source of truth): `docs/superpowers/specs/2026-07-23-phase1-client-parity-design.md`, Feature 3.

## File Targets

- `app/src/main/java/com/jauschua/ironlogv2/data/api/dto/MissedDayModels.kt` — new file, DTOs.
- `app/src/main/java/com/jauschua/ironlogv2/data/repo/MissedDaysRepo.kt` — new file.
- `app/src/main/java/com/jauschua/ironlogv2/di/AppContainer.kt` — add `missedDaysRepo`.
- `app/src/main/java/com/jauschua/ironlogv2/ui/screens/today/TodayViewModel.kt` — new `missedDays` `StateFlow` + rollup-count helper.
- `app/src/main/java/com/jauschua/ironlogv2/ui/screens/today/TodayScreen.kt` — rollup badge, tap target.
- `app/src/main/java/com/jauschua/ironlogv2/ui/screens/misseddays/MissedDaysScreen.kt` — new file.
- `app/src/main/java/com/jauschua/ironlogv2/ui/screens/misseddays/MissedDaysViewModel.kt` — new file.
- `app/src/main/java/com/jauschua/ironlogv2/ui/Nav.kt` — add `Routes.MISSED_DAYS`.
- `app/src/main/java/com/jauschua/ironlogv2/ui/MainActivity.kt` — register the route.
- `app/src/test/java/com/jauschua/ironlogv2/ui/today/TodayLogicTest.kt` — new pure-function test for the rollup-count helper.
- `app/src/test/...` new test file for the missed-days screen/ViewModel logic (match this repo's existing `*LogicTest.kt`/ViewModel-test naming convention — confirm via a repo search before naming it).

## Changes

### DTOs (`MissedDayModels.kt`, new file)

Mirror the server's schema exactly (verified live via `curl http://myflix:8000/missed-days` — currently returns `[]`):

```kotlin
@Serializable data class MissedDayRecordOut(
    val id: Int,
    val program_day_id: Int,
    val day_role: String,
    val week_start_date: String,
    val detected_at: String,
    val status: String,
)
```

### `MissedDaysRepo.kt` (new file)

Mirror `CardioLogRepo.kt`'s shape — a GET plus two no-body POSTs:

```kotlin
class MissedDaysRepo(private val apiClient: ApiClient) {

    suspend fun list(): Result<List<MissedDayRecordOut>> = runCatchingApi {
        apiClient.http.get("/missed-days").body()
    }

    suspend fun acknowledge(recordId: Int): Result<MissedDayRecordOut> = runCatchingApi {
        apiClient.http.post("/missed-days/$recordId/acknowledge").body()
    }

    suspend fun reschedule(recordId: Int): Result<MissedDayRecordOut> = runCatchingApi {
        apiClient.http.post("/missed-days/$recordId/reschedule").body()
    }
}
```

Both action endpoints take no request body — confirm this against the server's actual endpoint signatures (`ironlog/api/app.py`, `acknowledge_missed_day`/`reschedule_missed_day`) before assuming a `setBody(...)`/`contentType(...)` call is needed; the current server implementation takes only the path param.

### `AppContainer.kt`

Add `val missedDaysRepo: MissedDaysRepo by lazy { MissedDaysRepo(apiClient) }` next to `cardioLogRepo`.

### `TodayViewModel.kt`

Add:

```kotlin
private val _missedDays = MutableStateFlow<List<MissedDayRecordOut>>(emptyList())
val missedDays: StateFlow<List<MissedDayRecordOut>> = _missedDays.asStateFlow()
```

Best-effort fetch in `load()`:

```kotlin
private fun refreshMissedDays() {
    viewModelScope.launch {
        missedDaysRepo.list()
            .onSuccess { records -> _missedDays.value = records }
    }
}
```

Add a pure, file-level helper for the rollup badge count:

```kotlin
/** Count of missed-day records not yet resolved, for the Today badge. Pure and file-level so
 *  it's unit-testable without Compose or the ViewModel. */
fun missedDayBadgeCount(records: List<MissedDayRecordOut>): Int =
    records.count { it.status != "RESOLVED" }
```

### `TodayScreen.kt`

Render a badge/row only when `missedDayBadgeCount(...) > 0`. Tapping navigates to `Routes.MISSED_DAYS`.

### `MissedDaysScreen.kt` / `MissedDaysViewModel.kt` (new files)

`MissedDaysViewModel` fetches the list on init (mirror the loading/error state-machine shape from an existing simple list screen in this app, e.g. `CardioHistoryScreen`'s ViewModel — read it first and match its shape). It also exposes `acknowledge(recordId: Int)`/`reschedule(recordId: Int)` actions that call the repo and, on success, replace the updated record in the locally-held list (by `id`) rather than re-fetching the whole list from the server.

`MissedDaysScreen` renders the current list (per the Today rollup's own filter, this is effectively the non-resolved records, though the screen should render whatever the ViewModel's list actually holds — do not apply a second independent filter in the Compose layer that could silently diverge from the ViewModel's own state). Each record shows `day_role` and `week_start_date`, with two buttons: Acknowledge and Reschedule, each calling the corresponding ViewModel action.

### `Nav.kt` / `MainActivity.kt`

Add `const val MISSED_DAYS = "missed-days"` to `Routes`, register `composable(Routes.MISSED_DAYS) { MissedDaysScreen(onBack = { nav.popBackStack() }) }`, mirroring `CARDIO_HISTORY`'s registration. Wire Today's tap target via a new `onMissedDays` callback, mirroring `onHistory`/`onLogCardio`.

## Edge Cases

- Empty list (the current live state) — no badge on Today; the detail screen, if reached directly, shows a clear "nothing missed" empty state rather than a blank screen.
- A record already in `RESOLVED` status theoretically reachable via direct navigation (not through the badge, since the badge/rollup filters these out) — acknowledge/reschedule buttons should still be allowed to be tapped (the server permits this, "harmless churn" per its own docstring) rather than being hidden or disabled client-side; don't invent a client-side restriction the server doesn't have.
- `acknowledge`/`reschedule` network failure — surface a retry-able error state on that specific record (or the whole screen, whichever is simpler given this repo's existing error-handling conventions for a list+action screen) rather than silently no-op-ing; this is a user-initiated write action, not a best-effort background rollup, so failures must be visible.

## Dependencies

None as far as file overlap goes with specs 36/37 in terms of NEW files — but all three touch `TodayViewModel.kt`/`TodayScreen.kt`/`AppContainer.kt`. Per the design doc's scope-split note, run this sequentially after spec 37 merges, not concurrently.

## Verification

- `./gradlew testDebugUnitTest` — new tests (`missedDayBadgeCount` edge cases: empty, all-resolved, mixed) pass; full suite green, zero regressions.
- `./gradlew :app:assembleDebug` — builds clean.
- Manual: re-confirm the live shape via `curl http://myflix:8000/missed-days` before dispatch if significant time has passed.
