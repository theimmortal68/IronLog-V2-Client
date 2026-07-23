# Spec 39: Goals Settings Screen

## Objective

Add a new Settings bottom-nav tab housing a Goals section — view/edit weight and body-fat targets via the server's existing `GET`/`POST /goals` (live since 2026-07-19), currently invisible in-app.

Design doc (approved, source of truth): `docs/superpowers/specs/2026-07-23-phase1-client-parity-design.md`, Feature 4.

## File Targets

- `app/src/main/java/com/jauschua/ironlogv2/data/api/dto/GoalModels.kt` — new file, DTOs.
- `app/src/main/java/com/jauschua/ironlogv2/data/repo/GoalsRepo.kt` — new file.
- `app/src/main/java/com/jauschua/ironlogv2/di/AppContainer.kt` — add `goalsRepo`.
- `app/src/main/java/com/jauschua/ironlogv2/ui/screens/settings/SettingsScreen.kt` — new file.
- `app/src/main/java/com/jauschua/ironlogv2/ui/screens/settings/SettingsViewModel.kt` — new file.
- `app/src/main/java/com/jauschua/ironlogv2/ui/Nav.kt` — add `Routes.SETTINGS`.
- `app/src/main/java/com/jauschua/ironlogv2/ui/MainActivity.kt` — add the Settings tab to `TABS` (7th tab) and register the route.
- `app/src/test/...` new test file for the settings/goals form-validation logic, mirroring spec 35's `CardioLogScreenLogicTest.kt` pattern (a pure `buildXCreate`/`buildXUpdate`-style function, unit-tested without Compose).

## Changes

### DTOs (`GoalModels.kt`, new file)

Mirror the server's schema exactly (verified live via `curl http://myflix:8000/goals` — currently returns `null`, no goal configured):

```kotlin
@Serializable data class GoalSettingsOut(
    val target_bodyweight: Double,
    val target_bodyweight_tolerance: Double,
    val target_body_fat_pct: Double? = null,
    val target_body_fat_pct_tolerance: Double? = null,
    val updated_at: String,
)

@Serializable data class GoalSettingsIn(
    val target_bodyweight: Double? = null,
    val target_bodyweight_tolerance: Double? = null,
    val target_body_fat_pct: Double? = null,
    val target_body_fat_pct_tolerance: Double? = null,
)
```

Note `GoalSettingsOut.target_bodyweight`/`target_bodyweight_tolerance` are non-optional in the server's schema (`GoalSettingsOut` in `ironlog/api/schemas_goals.py`), but the endpoint itself returns a bare `null` (not a `GoalSettingsOut` with defaults) when no goal is configured — model the repo call's return type as `GoalSettingsOut?` (nullable at the Result level), not by making the DTO's own fields optional to work around the no-goal-configured case.

### `GoalsRepo.kt` (new file)

Mirror `CardioLogRepo.kt`'s shape:

```kotlin
class GoalsRepo(private val apiClient: ApiClient) {

    suspend fun get(): Result<GoalSettingsOut?> = runCatchingApi {
        apiClient.http.get("/goals").body()
    }

    suspend fun update(req: GoalSettingsIn): Result<GoalSettingsOut> = runCatchingApi {
        apiClient.http.post("/goals") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }.body()
    }
}
```

Confirm Ktor's `.body<GoalSettingsOut?>()` correctly deserializes a bare JSON `null` response into Kotlin `null` (rather than throwing) before assuming this shape works as written — if it doesn't, handle the null-body case explicitly (e.g. check the response body string first) rather than letting a deserialization exception surface as a generic network error.

### `AppContainer.kt`

Add `val goalsRepo: GoalsRepo by lazy { GoalsRepo(apiClient) }` next to `cardioLogRepo`.

### `SettingsScreen.kt` / `SettingsViewModel.kt` (new files)

`SettingsViewModel` fetches `goalsRepo.get()` on init. State machine: `Loading` → `NoGoalSet` (server returned `null`) or `HasGoal(GoalSettingsOut)`. Both states render an edit form (pre-filled from `HasGoal`'s values, or blank in `NoGoalSet`) for the four fields, submitting via `goalsRepo.update(...)` on save and refreshing state from the response.

Add a pure, file-level validation/builder function mirroring spec 35's `buildCardioLogCreate` pattern exactly — blank text fields must map to `null` in the request (not `0.0`), and non-numeric input in a non-blank field must reject the WHOLE update (return `null` from the builder) rather than silently coercing or dropping just that field. `buildCardioLogCreate` (`CardioLogScreen.kt`) already solves exactly this "blank vs. non-numeric vs. valid" three-way distinction for its own optional fields (`avg_hr`, `incline_pct`) via a `trim()` + `isEmpty()` check followed by `toIntOrNull()`/`toDoubleOrNull()` with an early `return null` on a non-blank-but-unparseable value — read that function directly and reuse the identical per-field pattern for each of the four goal fields here:

```kotlin
internal fun buildGoalSettingsUpdate(
    targetBodyweight: String, targetBodyweightTolerance: String,
    targetBodyFatPct: String, targetBodyFatPctTolerance: String,
): GoalSettingsIn? {
    // Apply buildCardioLogCreate's exact per-field pattern to each of the four strings below:
    //   val trimmed = field.trim()
    //   val parsed = if (trimmed.isEmpty()) null else trimmed.toDoubleOrNull() ?: return null
    // repeated for targetBodyweight, targetBodyweightTolerance, targetBodyFatPct,
    // targetBodyFatPctTolerance, then construct GoalSettingsIn from the four parsed values.
}
```

### `Nav.kt` / `MainActivity.kt`

Add `const val SETTINGS = "settings"` to `Routes`. Add a 7th entry to `MainActivity.kt`'s `TABS` list: `Tab(Routes.SETTINGS, "Settings", Icons.Filled.Settings)` (confirm `Icons.Filled.Settings` is available in the Material icons set already imported in this file; if not, use whatever gear/settings icon is available in the same icon package already in use, do not add a new icon dependency). Register `composable(Routes.SETTINGS) { SettingsScreen() }` in the `NavHost`.

## Edge Cases

- No goal configured (`GET /goals` returns `null`, the current live state) — the screen must render a clear empty state with an obviously-actionable "Set a goal" entry point into the same edit form, not a blank/broken-looking screen.
- **Resolved (do not re-investigate):** blanking a previously-set optional field (`target_body_fat_pct`) and saving will NOT clear it server-side with this spec's implementation. The server's `POST /goals` handler (`ironlog/api/app.py`) uses Pydantic's `req.dict(exclude_unset=True)` — a field only updates if it was actually present in the JSON body; an explicit `null` in the body WOULD clear it (`setattr(row, key, None)`), but this app's global `ApiClient` JSON config has `explicitNulls = false`, which omits `null`-valued fields from the wire entirely. So a blanked field is indistinguishable from an untouched one over the wire, and the server leaves the old value in place. This is a real, known limitation — not a bug to fix in this spec (fixing it would mean overriding serialization behavior for just this one endpoint, or flipping the app-wide `explicitNulls` setting, which risks changing behavior for every other partial-upsert endpoint in the app, e.g. spec 36's readiness check-in). Out of scope here. If the athlete blanks a field expecting it to clear, the UI should still show the field as blank locally after save succeeds (reflecting their intent) even though the server will re-populate it with the old value on the next `GET /goals` — do not add special-case logic to "fix" this within this spec.
- Non-numeric input in any field rejects the whole submit (matching spec 35's established pattern), rather than silently dropping just that one field.

## Dependencies

None as far as file overlap goes with specs 36/37/38 in terms of NEW files, but `MainActivity.kt` is touched by this spec (new tab) and none of the other three (they only add routes, not tabs) — still, per the design doc's scope-split note, run this sequentially last in the batch to avoid any residual nav-file churn overlap.

## Verification

- `./gradlew testDebugUnitTest` — new tests for `buildGoalSettingsUpdate` (mirroring spec 35's edge-case coverage: blank optional fields, non-numeric rejection, all-fields-set) pass; full suite green, zero regressions.
- `./gradlew :app:assembleDebug` — builds clean.
- Manual: re-confirm the live `GET /goals` response (currently `null`) before dispatch; read the server's `/goals` POST handler for the null-vs-omitted-field partial-upsert semantics called out above.
