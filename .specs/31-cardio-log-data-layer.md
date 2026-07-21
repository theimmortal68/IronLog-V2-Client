# Spec 31: Cardio-log data layer (DTOs + repo)

## Objective
Add the client-side data layer for the server's cardio-log feature (`POST/GET /cardio-log`, `GET /cardio-log/weekly-summary`, live on production since 2026-07-21) — DTOs and a repo, no UI yet.

## File targets
- New: `app/src/main/java/com/jauschua/ironlogv2/data/api/dto/CardioModels.kt`
- New: `app/src/main/java/com/jauschua/ironlogv2/data/repo/CardioLogRepo.kt`
- Modify: `app/src/main/java/com/jauschua/ironlogv2/di/AppContainer.kt`

## Confirmed live endpoint shapes (verified via `curl` against production, 2026-07-21 — do not guess, use these exact shapes)
- `GET /cardio-log` → `200 []` (empty list currently; shape is `List<CardioLogOut>`)
- `GET /cardio-log/weekly-summary` → `200 {"count":0,"target":2,"week_start":"2026-07-20"}`
- `POST /cardio-log` request body per server's `CardioLogCreate` schema (`ironlog/api/schemas_cardio_log.py`): `date` (string, `YYYY-MM-DD`), `duration_minutes` (int), `avg_hr` (int, nullable), `modality` (string, `"WALK"` | `"TREADMILL"`), `incline_pct` (float, nullable), `backward_walk_done` (bool, default false).
- `POST /cardio-log` response is `CardioLogOut`: same fields as `CardioLogCreate` plus `id` (int) and `created_at` (string, ISO datetime).

## The fix

`CardioModels.kt` — mirror this repo's existing DTO conventions exactly (kotlinx.serialization `@Serializable data class`, dates as plain `String` — confirmed via `SessionSummary.date: String` in `GenerateModels.kt`, this codebase does NOT use a Kotlin date type for API-boundary date fields):

```kotlin
package com.jauschua.ironlogv2.data.api.dto

import kotlinx.serialization.Serializable

@Serializable data class CardioLogCreate(
    val date: String,
    val duration_minutes: Int,
    val avg_hr: Int? = null,
    val modality: String,
    val incline_pct: Double? = null,
    val backward_walk_done: Boolean = false,
)

@Serializable data class CardioLogOut(
    val id: Int,
    val date: String,
    val duration_minutes: Int,
    val avg_hr: Int?,
    val modality: String,
    val incline_pct: Double?,
    val backward_walk_done: Boolean,
    val created_at: String,
)

@Serializable data class CardioWeeklySummaryOut(
    val count: Int,
    val target: Int,
    val week_start: String,
)
```

`CardioLogRepo.kt` — mirror `NotesRepo.kt`'s exact style (constructor takes `ApiClient`, methods return `Result<T>` via `runCatchingApi`, plain Ktor `get`/`post` calls):

```kotlin
package com.jauschua.ironlogv2.data.repo

import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.api.dto.CardioLogCreate
import com.jauschua.ironlogv2.data.api.dto.CardioLogOut
import com.jauschua.ironlogv2.data.api.dto.CardioWeeklySummaryOut
import com.jauschua.ironlogv2.data.api.runCatchingApi
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/** Standalone Z2 cardio session logging -- log-only, no generation, no ProgramDay/day_role
 *  involvement, no progression engine. Mirrors the server's own standalone design. */
class CardioLogRepo(private val apiClient: ApiClient) {

    suspend fun create(req: CardioLogCreate): Result<CardioLogOut> = runCatchingApi {
        apiClient.http.post("/cardio-log") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }.body()
    }

    suspend fun list(): Result<List<CardioLogOut>> = runCatchingApi {
        apiClient.http.get("/cardio-log").body()
    }

    suspend fun weeklySummary(): Result<CardioWeeklySummaryOut> = runCatchingApi {
        apiClient.http.get("/cardio-log/weekly-summary").body()
    }
}
```

`AppContainer.kt` — add `cardioLogRepo` alongside the existing `notesRepo`/`generateRepo` lazy vals, plus its import, matching the exact existing pattern (no other changes to this file):
```kotlin
val cardioLogRepo: CardioLogRepo by lazy { CardioLogRepo(apiClient) }
```

## Edge cases
- No Room/local-DB involvement — this feature has no offline-capture requirement (unlike `CaptureRepo`, which persists locally for offline workout logging). A plain network-only repo is correct here.
- `modality` stays a plain `String` client-side (no enum) — matches the server's own convention (spec 45 explicitly declined a server-side enum for this field).

## Dependencies
None.

## Verification
- This spec adds no new UI/ViewModel and has no existing test file to extend — verification is compile-correctness only: `./gradlew compileDebugKotlin` succeeds with the new files present, and `./gradlew testDebugUnitTest --rerun` shows no regressions (no new tests expected from this spec alone; DTOs/repos with no branching logic don't need dedicated unit tests per this repo's established convention — compare to `NotesRepo`, which also has no dedicated test file).
- Cross-check via XML, not just console output, per this repo's established gradle-cache-distrust convention: `app/build/test-results/testDebugUnitTest/TEST-*.xml` should show the same baseline test count as before this change (89 as of 2026-07-20's carry-forward fix), with a fresh timestamp.
