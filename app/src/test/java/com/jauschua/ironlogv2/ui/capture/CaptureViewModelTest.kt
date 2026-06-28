// CaptureViewModelTest.kt
package com.jauschua.ironlogv2.ui.capture

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jauschua.ironlogv2.data.api.ApiClient
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
        vm.logWorkingSet(
            plannedSetId = 10, movementId = 3, setIndex = 0, setRole = "WORKING",
            actualLoad = 100.0, actualReps = 8, tap = "ON_TARGET",
        )
        assertEquals(1, db.captureDao().setLogsForSession(7).size)
        assertEquals(1, vm.nextSetIndex.value)
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

        // Launch logWorkingSet as a child coroutine — it will suspend inside
        // FakeGatedDao.insertSetLog on gate.await(), before the index is advanced.
        val job = launch {
            vm.logWorkingSet(
                plannedSetId = 10, movementId = 3, setIndex = 0, setRole = "WORKING",
                actualLoad = 100.0, actualReps = 8, tap = "ON_TARGET",
            )
        }

        // Drain the dispatcher: runs logWorkingSet until it suspends on gate.await().
        // The write is now in-flight; the gate has not been completed.
        advanceUntilIdle()

        // CRITICAL ORDERING ASSERTION — this is the assertion that goes RED against
        // fire-and-forget: if logWorkingSet advances nextSetIndex before awaiting
        // repo.logSet, nextSetIndex is already 1 here and the assertion fails.
        assertEquals("advance must not happen before commit", 0, vm.nextSetIndex.value)
        assertEquals("row must not exist before gate completes", 0, stored.size)

        // Unblock the write: insertSetLog resumes, stored.add(d) runs, logSet returns,
        // _nextSetIndex.value = setIndex + 1 executes.
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("row committed after gate", 1, stored.size)
        assertEquals("advance after commit", 1, vm.nextSetIndex.value)

        job.join()
    }
}
