## Task 6 Completion Report — CaptureViewModel

**Objective:** `CaptureViewModel` — write-before-advance ordering + mandatory-tap (client half) + DI wiring.
**Status:** DONE
**Branch:** `feat/logging-capture`

---

## Files Written

| File | Role |
|------|------|
| `app/src/main/java/com/jauschua/ironlogv2/ui/screens/capture/CaptureViewModel.kt` | NEW — the VM |
| `app/src/main/java/com/jauschua/ironlogv2/di/AppContainer.kt` | MODIFIED — adds Context ctor param + `captureDb`/`captureRepo` lazies |
| `app/src/main/java/com/jauschua/ironlogv2/IronLogV2Application.kt` | MODIFIED — passes `this` to `AppContainer(this)` |
| `app/src/test/java/com/jauschua/ironlogv2/ui/capture/CaptureViewModelTest.kt` | NEW — 2-gate test |

---

## TDD Sequence

**Step 1 — Tests written (RED):** Wrote `CaptureViewModelTest.kt` with both gate tests. First build:

```
e: Unresolved reference 'capture'.
e: Unresolved reference 'CaptureViewModel'.
BUILD FAILED
```

Confirmed RED — `CaptureViewModel` did not exist.

**Step 2 — VM + DI wiring implemented (GREEN):**

- `CaptureViewModel(repo, sessionId)` with `suspend fun logWorkingSet(...)` and `fun finish()`
- `AppContainer` updated: `class AppContainer(private val appContext: Context)` with `captureDb` and `captureRepo` by-lazy
- `IronLogV2Application` updated: `val container: AppContainer by lazy { AppContainer(this) }`

```
CaptureViewModelTest > working_set_without_tap_is_rejected_and_not_persisted  PASSED
CaptureViewModelTest > working_set_is_committed_to_room_before_advance         PASSED
BUILD SUCCESSFUL
```

---

## Gate 1: Write-Before-Advance Ordering

**How it is enforced:**

`logWorkingSet` is a `suspend fun`. It directly `await`s `repo.logSet(draft)`:

```kotlin
repo.logSet(
    SetLogDraft(sessionId = sessionId, ...)
)                                          // suspends; Room @Insert returns ONLY after commit
_nextSetIndex.value = setIndex + 1        // advance AFTER the commit returns
```

There is no `launch` here. The Room `@Insert suspend` dispatches to Room's ThreadPoolExecutor (in production) and suspends the calling coroutine until the SQLite transaction commits. Only after that suspension resumes does `_nextSetIndex.value` get mutated. When `logWorkingSet` returns to its caller, both the durable row and the index advance are guaranteed complete.

**Test asserting row-exists-at-advance:**

```kotlin
@Test
fun working_set_is_committed_to_room_before_advance() = runBlocking {
    val (repo, db) = deps()
    val vm = CaptureViewModel(repo, sessionId = 7)
    vm.logWorkingSet(plannedSetId = 10, movementId = 3, setIndex = 0, setRole = "WORKING",
        actualLoad = 100.0, actualReps = 8, tap = "ON_TARGET")
    // After the suspend returns, the durable row exists AND the VM advanced.
    assertEquals(1, db.captureDao().setLogsForSession(7).size)  // row committed
    assertEquals(1, vm.nextSetIndex.value)                       // advance complete
}
```

Both asserts must hold simultaneously after `logWorkingSet` returns. This is the contract: "when the VM says advanced, the Room row exists."

**How a fire-and-forget impl WOULD fail (production reasoning):**

A fire-and-forget implementation:
```kotlin
viewModelScope.launch { repo.logSet(draft) }  // write dispatched to IO, not awaited
_nextSetIndex.value = setIndex + 1             // advance immediately
// logWorkingSet returns HERE — write still pending on IO thread
```

In production, `dao.insertSetLog` dispatches to Room's ThreadPoolExecutor. The launched coroutine runs on an IO thread. `logWorkingSet` would return with `nextSetIndex == 1` while the IO thread is still writing. If the process is killed at this point, the Room row would be absent — the set is lost.

The test would catch this: `assertEquals(1, db.captureDao().setLogsForSession(7).size)` would see `0` (write pending on IO thread) while `nextSetIndex` is already `1`, failing on the size assert.

**Red-demo feasibility note:**

Three fire-and-forget variants were attempted to demonstrate a live red:

1. `viewModelScope.launch { repo.logSet(...) }; advance()` — Robolectric's main dispatcher ran the launch block synchronously before control returned to the caller, passing accidentally.
2. `GlobalScope.launch(Dispatchers.IO) { repo.logSet(...) }; advance()` — Room with `allowMainThreadQueries()` executed the insert synchronously even from the IO dispatcher, passing accidentally.
3. `advance(); repo.logSet(...)` (advance-before-write, both still awaited in the same coroutine) — both complete before return; no observable ordering difference at test level.

**Conclusion:** The fire-and-forget live red-demo is NOT feasible in the Robolectric + `allowMainThreadQueries()` test environment. Room's in-memory writes in this setup execute synchronously on the calling thread, preventing the async race from manifesting. This is a known characteristic of `allowMainThreadQueries()` — it removes Room's thread dispatch, making tests deterministic but unable to simulate production's IO-thread ordering.

The test is nonetheless **correct and non-fragile for its stated contract**: it verifies that when `logWorkingSet` returns, BOTH the write and the advance are complete. A genuinely async fire-and-forget in production (without `allowMainThreadQueries()`, where Room's ThreadPoolExecutor creates real async ordering) WOULD fail the size assert because the executor thread would not have committed before the assert runs.

---

## Gate 2: Mandatory Tap (client half)

**Enforcement:**

```kotlin
private val TAP_REQUIRED = setOf("WORKING", "TOP", "BACKOFF")

if (setRole in TAP_REQUIRED && tap == null) {
    _uiError.value = "Tap required before continuing"
    return  // early exit — no write, no advance
}
```

**Test:**

```kotlin
@Test
fun working_set_without_tap_is_rejected_and_not_persisted() = runBlocking {
    val (repo, db) = deps()
    val vm = CaptureViewModel(repo, sessionId = 7)
    vm.logWorkingSet(plannedSetId = 10, movementId = 3, setIndex = 0, setRole = "WORKING",
        actualLoad = 100.0, actualReps = 8, tap = null)
    assertNotNull(vm.uiError.value)                             // rejected — error set
    assertEquals(0, db.captureDao().setLogsForSession(7).size) // nothing written
}
```

Asserts: `uiError` is non-null, Room has 0 rows, `nextSetIndex` implicitly remains 0 (function returned early before any StateFlow advance).

---

## Gradle Tails

**CaptureViewModelTest only (first green run):**
```
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 10s
26 actionable tasks: 9 executed, 17 up-to-date
```

**Full suite (final):**
```
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 9s
26 actionable tasks: 7 executed, 19 up-to-date
```

---

## Full Test Count

| Suite | Tests | Failures | Errors |
|-------|-------|----------|--------|
| ModelsTest | 6 | 0 | 0 |
| CaptureDurabilityTest | 1 | 0 | 0 |
| AutoregRepoTest | 1 | 0 | 0 |
| CaptureRepoTest | 1 | 0 | 0 |
| LibraryRepoTest | 6 | 0 | 0 |
| **CaptureViewModelTest** | **2** | **0** | **0** |
| **Total** | **17** | **0** | **0** |

All 15 pre-existing tests remain green. 2 new tests added (both gates pass).

---

## Commit

`feat(capture): CaptureViewModel — write-before-advance + mandatory-tap; DI wiring`

---

## Task 6 fix wave — non-fragile ordering

**Problem closed:** The original ordering test (`working_set_is_committed_to_room_before_advance`) verified the post-condition (row present + nextSetIndex advanced after return) but could not go RED against a fire-and-forget implementation. Robolectric with `allowMainThreadQueries()` ran async variants synchronously, masking the ordering bug.

### Gated DAO design

Added `FakeGatedDao : CaptureDao` (private, in test file). Its `insertSetLog` does:

```kotlin
override suspend fun insertSetLog(d: SetLogDraft) { gate.await(); stored.add(d) }
```

`gate` is a `CompletableDeferred<Unit>` passed at construction. The DAO suspends until `gate.complete(Unit)` — giving the test a precise handle on when the write "commits." All other DAO methods are no-ops or trivial list delegates.

### Test: `logWorkingSet_commits_before_advance_ordering`

Uses `runTest(StandardTestDispatcher())` (NOT Unconfined — Standard queues, doesn't run eagerly):

1. Launch `vm.logWorkingSet(...)` as a child coroutine on the test scheduler.
2. `advanceUntilIdle()` — drains until the coroutine suspends inside `gate.await()` (write in-flight, gate not completed).
3. **Critical assertion while write is in-flight:**
   ```
   assertEquals("advance must not happen before commit", 0, vm.nextSetIndex.value)
   assertEquals("row must not exist before gate completes", 0, stored.size)
   ```
4. `gate.complete(Unit)` + `advanceUntilIdle()` — write commits, advance runs.
5. Assert `stored.size == 1` and `nextSetIndex == 1`.

### RED confirmation against fire-and-forget

Temporarily changed `logWorkingSet` to:
```kotlin
viewModelScope.launch { repo.logSet(draft) }   // fire-and-forget
_nextSetIndex.value = setIndex + 1              // advance immediately
```

Ran only the new ordering test. Result:

```
CaptureViewModelTest > logWorkingSet_commits_before_advance_ordering FAILED
java.lang.AssertionError: advance must not happen before commit expected:<0> but was:<1>
```

The in-flight assertion saw `nextSetIndex == 1` — the advance happened before the gate completed. Production code restored immediately after confirming RED.

### Test count

| Suite | Tests |
|-------|-------|
| ModelsTest | 6 |
| CaptureDurabilityTest | 1 |
| AutoregRepoTest | 1 |
| CaptureRepoTest | 1 |
| LibraryRepoTest | 6 |
| **CaptureViewModelTest** | **3** (+1 ordering keystone) |
| **Total** | **18** |

All 18 green. 0 failures, 0 errors.

### Commit

`test(capture): prove write-before-advance ordering is non-fragile (RED against fire-and-forget via gated DAO)` — `671e2a2`
