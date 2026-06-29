// CaptureViewModelTest.kt
package com.jauschua.ironlogv2.ui.capture

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.api.dto.PlannedSetOut
import com.jauschua.ironlogv2.data.local.CaptureDao
import com.jauschua.ironlogv2.data.local.CaptureDatabase
import com.jauschua.ironlogv2.data.local.NoteDraft
import com.jauschua.ironlogv2.data.local.SetLogDraft
import com.jauschua.ironlogv2.data.local.SurveyDraft
import com.jauschua.ironlogv2.data.repo.CaptureRepo
import com.jauschua.ironlogv2.ui.screens.capture.CaptureViewModel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Fake CaptureDao whose [insertSetLog] suspends on [gate] before committing the row.
 *
 * This gives the test precise control over when the write "completes": the calling coroutine
 * suspends at gate.await() and does not add to [stored] until gate.complete(Unit) is called.
 * All other DAO operations are no-ops or trivial list delegates.
 */
private class FakeGatedDao(
    private val gate: CompletableDeferred<Unit>,
    val stored: MutableList<SetLogDraft> = mutableListOf(),
) : CaptureDao {
    override suspend fun insertSetLog(d: SetLogDraft) { gate.await(); stored.add(d) }
    override suspend fun insertSurvey(d: SurveyDraft) {}
    override suspend fun insertNote(d: NoteDraft) {}
    override suspend fun setLogsForSession(sessionId: Int): List<SetLogDraft> =
        stored.filter { it.sessionId == sessionId }
    override suspend fun surveysForSession(sessionId: Int): List<SurveyDraft> = emptyList()
    override suspend fun notesForSession(sessionId: Int): List<NoteDraft> = emptyList()
    override suspend fun clearSetLogs(sessionId: Int) {
        stored.removeAll { it.sessionId == sessionId }
    }
    override suspend fun clearSurveys(sessionId: Int) {}
    override suspend fun clearNotes(sessionId: Int) {}
}

@RunWith(RobolectricTestRunner::class)
class CaptureViewModelTest {

    // ── Room-backed helpers (existing tests) ─────────────────────────────────────────────

    private fun deps(): Pair<CaptureRepo, CaptureDatabase> {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            CaptureDatabase::class.java,
        ).allowMainThreadQueries().build()
        val engine = MockEngine {
            respond(
                """{"session_id":7,"status":"COMPLETED","set_logs_written":1,"already_completed":false}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return CaptureRepo(ApiClient(engine = engine), db.captureDao()) to db
    }

    private fun mockEngine() = MockEngine {
        respond(
            """{"session_id":7,"status":"COMPLETED","set_logs_written":1,"already_completed":false}""",
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    // ── Gate #2 — mandatory-tap rejection ────────────────────────────────────────────────

    /** Working set without a tap is rejected — no Room write, no advance. */
    @Test
    fun working_set_without_tap_is_rejected_and_not_persisted() = runBlocking {
        val (repo, db) = deps()
        val vm = CaptureViewModel(repo, sessionId = 7)
        vm.logWorkingSet(
            plannedSetId = 10, movementId = 3, setIndex = 0, setRole = "WORKING",
            actualLoad = 100.0, actualReps = 8, tap = null,
        )
        assertNotNull(vm.uiError.value)                             // rejected
        assertEquals(0, db.captureDao().setLogsForSession(7).size) // nothing written
    }

    // ── Gate #5 — post-condition durability (Room-backed) ────────────────────────────────

    /**
     * After [logWorkingSet] returns, the durable row exists in Room AND [nextSetIndex] is
     * advanced — both simultaneously, proving the commit completed before the advance.
     *
     * This test proves the contract at the API boundary. The non-fragile proof that the
     * ORDERING itself is enforced (not just the post-condition) is in
     * [logWorkingSet_commits_before_advance_ordering] below.
     */
    @Test
    fun working_set_is_committed_to_room_before_advance() = runBlocking {
        val (repo, db) = deps()
        val vm = CaptureViewModel(repo, sessionId = 7)
        val ps0 = PlannedSetOut(id = 10, set_index = 0, set_role = "WORKING", is_warmup = false)
        val ps1 = PlannedSetOut(id = 11, set_index = 1, set_role = "WORKING", is_warmup = false)
        vm.initPrescriptionForTest(listOf(ps0, ps1))
        vm.logWorkingSet(
            plannedSetId = 10, movementId = 3, setIndex = 0, setRole = "WORKING",
            actualLoad = 100.0, actualReps = 8, tap = "ON_TARGET",
        )
        assertEquals(1, db.captureDao().setLogsForSession(7).size)
        assertEquals(ps1.id, vm.currentPlannedSetId.value)   // cursor advanced to ps1
    }

    // ── Ordering keystone — non-fragile via gated DAO + StandardTestDispatcher ───────────

    /**
     * Proves write-before-advance ordering is non-fragile.
     *
     * Design:
     * - [FakeGatedDao.insertSetLog] suspends on a [CompletableDeferred] gate before writing
     *   the row.  This gives us a controllable suspension point inside the write.
     * - [StandardTestDispatcher] queues coroutine steps rather than running them eagerly,
     *   so [advanceUntilIdle] drains exactly until the first suspension (the gate).
     * - We assert [nextSetIndex] == 0 while the gate is open (write in-flight).
     *   A fire-and-forget implementation (`viewModelScope.launch { repo.logSet(...) };
     *   _nextSetIndex.value = setIndex + 1`) would advance the index before the gate
     *   completes, causing this assertion to see 1 → RED.
     * - We then complete the gate, drain again, and assert nextSetIndex == 1 and
     *   stored.size == 1 — write committed AND advance happened, in that order.
     *
     * RED-confirmed against fire-and-forget (2026-06-28):
     *   org.opentest4j.AssertionFailedError: advance must not happen before commit
     *   ==> expected: <0> but was: <1>
     * GREEN against correct await-then-advance production code.
     */
    @Test
    fun logWorkingSet_commits_before_advance_ordering() = runTest(StandardTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val stored = mutableListOf<SetLogDraft>()
        val fakeDao = FakeGatedDao(gate, stored)
        val repo = CaptureRepo(ApiClient(engine = mockEngine()), fakeDao)
        val vm = CaptureViewModel(repo, sessionId = 7)
        // Inject prescription so the cursor can advance after the gate completes.
        val ps0 = PlannedSetOut(id = 10, set_index = 0, set_role = "WORKING", is_warmup = false)
        val ps1 = PlannedSetOut(id = 11, set_index = 1, set_role = "WORKING", is_warmup = false)
        vm.initPrescriptionForTest(listOf(ps0, ps1))

        // Launch logWorkingSet as a child coroutine — it will suspend inside
        // FakeGatedDao.insertSetLog on gate.await(), before the cursor is advanced.
        val job = launch {
            vm.logWorkingSet(
                plannedSetId = 10, movementId = 3, setIndex = 0, setRole = "WORKING",
                actualLoad = 100.0, actualReps = 8, tap = "ON_TARGET",
            )
        }

        // Drain the dispatcher: runs logWorkingSet until it suspends on gate.await().
        // The write is now in-flight; the gate has not been completed.
        advanceUntilIdle()

        // CRITICAL ORDERING ASSERTION — cursor must still point at ps0 while the gate is
        // open.  A fire-and-forget implementation would advance currentPlannedSetId to ps1.id
        // before the gate completes, causing this assertion to fail.
        assertEquals("cursor must not advance before commit", ps0.id, vm.currentPlannedSetId.value)
        assertEquals("row must not exist before gate completes", 0, stored.size)

        // Unblock the write: insertSetLog resumes, stored.add(d) runs, logSet returns,
        // then cursor advances to ps1.id.
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("row committed after gate", 1, stored.size)
        assertEquals("cursor advances after commit", ps1.id, vm.currentPlannedSetId.value)

        job.join()
    }

    // ── FIX ① multi-exercise progression (loop-closer) ──────────────────────────────────────

    /**
     * Drives progression through a 2-exercise × 2-set prescription and asserts the cursor
     * walks ALL four sets in order: E0S0 → E0S1 → E1S0 → E1S1 → null (end).
     *
     * The critical assertion is `after E0S1 → E1S0`: with the OLD broken implementation the
     * cursor was a global integer counter advanced by `setIndex + 1`, and the screen checked
     * `plannedSet.set_index == counter`.  After logging E0S0 (set_index=0, counter→1) and
     * E0S1 (set_index=1, counter→2), exercise-1's sets have set_index 0 and 1 — both < 2 —
     * so they always appeared "past" and never received input controls.  Only exercise-0 could
     * be logged in a real multi-exercise session.
     *
     * RED-confirmed against the broken form: reverting the cursor advance to
     *   `flattenedPrescription.find { it.set_index == setIndex + 1 }?.id`
     * caused `assertEquals("after E0S1 → E1S0", 3, vm.currentPlannedSetId.value)` to FAIL
     * (actual was null — no set with set_index == 2 exists in the prescription) confirming
     * the test detects the bug class it is designed to catch.
     */
    @Test
    fun multi_exercise_cursor_walks_all_sets() = runBlocking {
        // 2 exercises × 2 working sets.  set_index RESETS to 0 on exercise-1 — this is
        // exactly the trap the old global-counter approach fell into.
        val ps0_0 = PlannedSetOut(id = 1, set_index = 0, set_role = "WORKING", is_warmup = false)
        val ps0_1 = PlannedSetOut(id = 2, set_index = 1, set_role = "WORKING", is_warmup = false)
        val ps1_0 = PlannedSetOut(id = 3, set_index = 0, set_role = "WORKING", is_warmup = false) // resets!
        val ps1_1 = PlannedSetOut(id = 4, set_index = 1, set_role = "WORKING", is_warmup = false)

        val (repo, _) = deps()
        val vm = CaptureViewModel(repo, sessionId = 7)
        vm.initPrescriptionForTest(listOf(ps0_0, ps0_1, ps1_0, ps1_1))

        assertEquals("initial cursor: E0S0", ps0_0.id, vm.currentPlannedSetId.value)

        // Log exercise-0, set-0
        vm.logWorkingSet(plannedSetId = ps0_0.id, movementId = 1, setIndex = 0,
            setRole = "WORKING", actualLoad = 100.0, actualReps = 8, tap = "ON_TARGET")
        assertEquals("after E0S0 → E0S1", ps0_1.id, vm.currentPlannedSetId.value)

        // Log exercise-0, set-1 — next cursor must cross the exercise boundary into exercise-1
        vm.logWorkingSet(plannedSetId = ps0_1.id, movementId = 1, setIndex = 1,
            setRole = "WORKING", actualLoad = 100.0, actualReps = 8, tap = "ON_TARGET")
        assertEquals("after E0S1 → E1S0 (cross-exercise boundary)", ps1_0.id, vm.currentPlannedSetId.value)

        // Log exercise-1, set-0
        vm.logWorkingSet(plannedSetId = ps1_0.id, movementId = 2, setIndex = 0,
            setRole = "WORKING", actualLoad = 100.0, actualReps = 8, tap = "ON_TARGET")
        assertEquals("after E1S0 → E1S1", ps1_1.id, vm.currentPlannedSetId.value)

        // Log exercise-1, set-1 — cursor reaches end of prescription
        vm.logWorkingSet(plannedSetId = ps1_1.id, movementId = 2, setIndex = 1,
            setRole = "WORKING", actualLoad = 100.0, actualReps = 8, tap = "ON_TARGET")
        assertNull("after E1S1 → end of prescription", vm.currentPlannedSetId.value)
    }
}
