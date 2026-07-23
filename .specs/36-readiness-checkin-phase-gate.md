# Spec 36: Readiness Check-In + Phase-Gate Confirmation

## Objective

Add a daily readiness check-in card to the Today screen (submitting to the server's existing readiness endpoints) and a phase-transition confirmation banner driven by `SubmitResponse.phase_transition_available` — the first client surface for a server feature that's been live since 2026-07-18.

Design doc (approved, source of truth): `docs/superpowers/specs/2026-07-23-phase1-client-parity-design.md`, Feature 1.

## File Targets

- `app/src/main/java/com/jauschua/ironlogv2/data/api/dto/ReadinessModels.kt` — new file, DTOs.
- `app/src/main/java/com/jauschua/ironlogv2/data/api/dto/CaptureModels.kt` — add `phase_transition_available: String? = null` to `SubmitResponse`.
- `app/src/main/java/com/jauschua/ironlogv2/data/repo/ReadinessRepo.kt` — new file.
- `app/src/main/java/com/jauschua/ironlogv2/di/AppContainer.kt` — add `readinessRepo` and `pendingPhaseTransition: MutableStateFlow<String?>`.
- `app/src/main/java/com/jauschua/ironlogv2/ui/screens/capture/CaptureViewModel.kt` — thread `pendingPhaseTransition` into the constructor, set it in `finish()`.
- `app/src/main/java/com/jauschua/ironlogv2/ui/screens/today/TodayViewModel.kt` — new readiness `StateFlow`, new phase-transition-banner state, new `checkIn()`/`confirmPhase()` actions.
- `app/src/main/java/com/jauschua/ironlogv2/ui/screens/today/TodayScreen.kt` — render the check-in card + phase-transition banner.
- `app/src/test/java/com/jauschua/ironlogv2/ui/today/TodayLogicTest.kt` — new pure-function tests.
- `app/src/test/java/com/jauschua/ironlogv2/ui/screens/capture/CaptureViewModelTest.kt` (or wherever the existing CaptureViewModel tests live — confirm the actual path via a repo search before writing) — test that a successful submit with a non-null `phase_transition_available` sets the container flow.

## Changes

### DTOs (`ReadinessModels.kt`, new file)

Mirror the server's Pydantic schemas exactly (verified live via `curl http://myflix:8000/readiness/today` — real response: `{"date":"2026-07-23","bodyweight":216.68,"resting_hr":null,"sleep_ok":null,"subjective_ok":null}`):

```kotlin
@Serializable data class DailyReadinessOut(
    val date: String,
    val bodyweight: Double? = null,
    val resting_hr: Double? = null,
    val sleep_ok: Boolean? = null,
    val subjective_ok: Boolean? = null,
)

@Serializable data class DailyReadinessIn(
    val bodyweight: Double? = null,
    val resting_hr: Double? = null,
    val sleep_ok: Boolean? = null,
    val subjective_ok: Boolean? = null,
)

@Serializable data class ConfirmPhaseRequest(
    val to_phase: String,
)
```

Follow the existing `@Serializable data class` style used in `CardioModels.kt` (same package, same annotations).

### `CaptureModels.kt` — add the missing field to `SubmitResponse`

The server's `SubmitResponse` (`ironlog/api/schemas_capture.py`, server repo) already returns `phase_transition_available: Optional[str]`, but the client's `SubmitResponse` DTO doesn't declare it at all — meaning it's silently dropped by the JSON deserializer today. Add it:

```kotlin
@Serializable data class SubmitResponse(
    val session_id: Int, val status: String, val set_logs_written: Int, val already_completed: Boolean,
    val phase_transition_available: String? = null,
)
```

### `ReadinessRepo.kt` (new file)

Mirror `CardioLogRepo.kt`'s exact shape and style:

```kotlin
package com.jauschua.ironlogv2.data.repo

import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.api.dto.ConfirmPhaseRequest
import com.jauschua.ironlogv2.data.api.dto.DailyReadinessIn
import com.jauschua.ironlogv2.data.api.dto.DailyReadinessOut
import com.jauschua.ironlogv2.data.api.runCatchingApi
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class ReadinessRepo(private val apiClient: ApiClient) {

    suspend fun today(): Result<DailyReadinessOut> = runCatchingApi {
        apiClient.http.get("/readiness/today").body()
    }

    suspend fun checkIn(req: DailyReadinessIn): Result<DailyReadinessOut> = runCatchingApi {
        apiClient.http.post("/readiness") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }.body()
    }

    suspend fun confirmPhase(toPhase: String): Result<Unit> = runCatchingApi {
        apiClient.http.post("/engine-state/confirm-phase") {
            contentType(ContentType.Application.Json)
            setBody(ConfirmPhaseRequest(to_phase = toPhase))
        }.body()
    }
}
```

Verify the exact `runCatchingApi`/`ApiClient` import paths against the real `CardioLogRepo.kt` before writing — do not guess.

### `AppContainer.kt`

Add:

```kotlin
val readinessRepo: ReadinessRepo by lazy { ReadinessRepo(apiClient) }
```

next to `cardioLogRepo`, and:

```kotlin
/** In-memory phase-transition signal. Set by CaptureViewModel.finish() on a successful submit
 *  whose response carries a non-null phase_transition_available; read by TodayViewModel to show
 *  a confirmation banner; cleared on dismiss or confirm. Resets to null on process death, same
 *  characteristic as autoregPrefill above -- acceptable, the underlying gate condition re-derives
 *  on the athlete's next qualifying submit. */
val pendingPhaseTransition: MutableStateFlow<String?> = MutableStateFlow(null)
```

next to `autoregPrefill`.

### `CaptureViewModel.kt`

Add a new constructor parameter (with a test-friendly default, mirroring `restTimerController`'s own default pattern):

```kotlin
class CaptureViewModel(
    private val repo: CaptureRepo,
    private var sessionId: Int,
    private val restTimerController: RestTimerController = InMemoryRestTimerController(),
    val intervalTimerController: IntervalTimerController = InMemoryIntervalTimerController(),
    private val pendingPhaseTransition: MutableStateFlow<String?> = MutableStateFlow(null),
) : ViewModel() {
```

In `finish()`, set it on success:

```kotlin
suspend fun finish(sessionNote: String? = null) {
    repo.saveSessionNote(sessionId, sessionNote)
    repo.submit(sessionId)
        .onSuccess {
            _submitResult.value = it.status
            if (it.phase_transition_available != null) {
                pendingPhaseTransition.value = it.phase_transition_available
            }
        }
        .onFailure { _submitResult.value = "RETRY" }
}
```

Wire the real container flow into the `TodayFactory` (the factory used for the Today-launched Capture destination — confirm this is the only production factory for `CaptureViewModel` before assuming, there may be more than one):

```kotlin
CaptureViewModel(
    repo = app.container.captureRepo,
    // ...existing args...
    pendingPhaseTransition = app.container.pendingPhaseTransition,
)
```

### `TodayViewModel.kt`

Add:

```kotlin
private val _readiness = MutableStateFlow<DailyReadinessOut?>(null)
val readiness: StateFlow<DailyReadinessOut?> = _readiness.asStateFlow()

private val _pendingPhaseTransition = MutableStateFlow<String?>(null)
val pendingPhaseTransition: StateFlow<String?> = _pendingPhaseTransition.asStateFlow()
```

In `load()`, alongside `refreshReviewCount()`/`refreshCardioSummary()`, add a best-effort `refreshReadiness()` (same no-error-surfaced shape) and read the container's `pendingPhaseTransition` flow directly into `_pendingPhaseTransition` (a plain assignment/collect, not a network call — it's already in memory):

```kotlin
private fun refreshReadiness() {
    viewModelScope.launch {
        readinessRepo.today()
            .onSuccess { r -> _readiness.value = r }
    }
}
```

Add a pure helper (unit-testable, file-level, mirroring `classifyGenerate`/`reviewButtonLabel`'s existing style) to decide whether the check-in card should render expanded or collapsed:

```kotlin
/** True once both subjective fields are answered for today -- the card collapses to a compact
 *  summary. Pure and file-level so it's unit-testable without the ViewModel. */
fun hasCheckedInToday(readiness: DailyReadinessOut?): Boolean =
    readiness != null && readiness.sleep_ok != null && readiness.subjective_ok != null
```

Add ViewModel actions:

```kotlin
fun checkIn(sleepOk: Boolean?, subjectiveOk: Boolean?, restingHr: Double?) {
    viewModelScope.launch {
        readinessRepo.checkIn(DailyReadinessIn(sleep_ok = sleepOk, subjective_ok = subjectiveOk, resting_hr = restingHr))
            .onSuccess { r -> _readiness.value = r }
    }
}

fun confirmPhaseTransition() {
    val phase = _pendingPhaseTransition.value ?: return
    viewModelScope.launch {
        readinessRepo.confirmPhase(phase)
            .onSuccess {
                _pendingPhaseTransition.value = null
                pendingPhaseTransitionContainerFlow.value = null
            }
    }
}

fun dismissPhaseTransitionBanner() {
    _pendingPhaseTransition.value = null
    pendingPhaseTransitionContainerFlow.value = null
}
```

`TodayViewModel`'s constructor already takes individual repos (`generateRepo`, `captureRepo`, `notesRepo`, `cardioLogRepo` — not the whole `AppContainer`). Follow that exact pattern: add a new constructor param, e.g. `private val pendingPhaseTransitionContainerFlow: MutableStateFlow<String?>`, wired in the `Factory` as `app.container.pendingPhaseTransition` (a specific flow reference, not the container object). `load()` should also read the container flow's current value into `_pendingPhaseTransition` (a plain read, not a repeated collect loop — `pendingPhaseTransitionContainerFlow.value` is enough since `load()` already re-runs on each Today entry).

### `TodayScreen.kt`

Add a check-in card (collapsed/expanded per `hasCheckedInToday`) near the top of the screen, and a dismissible banner above it when `pendingPhaseTransition != null`: `"Ready to move to $phase — Confirm?"` with Confirm/Dismiss actions wired to the new ViewModel functions. Follow the existing `Card`/`Button` composable style already used elsewhere in this file (e.g. `ReadOnlyGroupCard`).

## Edge Cases

- `bodyweight` is `null` on a day Withings hasn't synced yet — the check-in form must still allow submitting `sleep_ok`/`subjective_ok` independently (partial-upsert, server already supports this).
- A `phase_transition_available` value survives across TWO submits if the athlete never confirms — the second submit's value should simply overwrite the first (not stack, not append) in `pendingPhaseTransition`.
- App killed with a pending transition: `pendingPhaseTransition` resets to `null` on next launch (in-memory only, no persistence) — this is an accepted, documented characteristic in the design doc, not a bug to work around.
- `confirmPhaseTransition()` called with `_pendingPhaseTransition.value == null` (e.g. double-tap race) must no-op, not send a request with a null phase.

## Dependencies

None — first spec in this batch, does not depend on the weak-points/missed-days/goals specs.

## Verification

- `./gradlew testDebugUnitTest` — new tests for `hasCheckedInToday` and the `CaptureViewModel.finish()` phase-transition-capture behavior pass; full suite green, zero regressions (confirm current baseline test count before dispatch).
- `./gradlew :app:assembleDebug` — builds clean.
- Manual: confirm the real live shapes one more time via `curl http://myflix:8000/readiness/today` and `curl -X POST http://myflix:8000/engine-state/confirm-phase -d '{"to_phase":"REBUILD"}' -H 'Content-Type: application/json'` (a real POST to confirm-phase in verification would actually flip production state — do NOT execute this during spec verification; read the server's `ironlog/api/app.py` endpoint implementation instead to confirm the request/response shape statically).
