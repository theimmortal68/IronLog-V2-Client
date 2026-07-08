// CaptureViewModelTest.kt
package com.jauschua.ironlogv2.ui.capture

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.api.dto.ExerciseOut
import com.jauschua.ironlogv2.data.api.dto.GroupOut
import com.jauschua.ironlogv2.data.api.dto.PlannedSetOut
import com.jauschua.ironlogv2.data.local.CaptureDao
import com.jauschua.ironlogv2.data.local.CaptureDatabase
import com.jauschua.ironlogv2.data.local.NoteDraft
import com.jauschua.ironlogv2.data.local.SetLogDraft
import com.jauschua.ironlogv2.data.local.SurveyDraft
import com.jauschua.ironlogv2.data.repo.CaptureRepo
import com.jauschua.ironlogv2.ui.screens.capture.CaptureViewModel
import com.jauschua.ironlogv2.ui.screens.capture.flattenPrescription
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
    override suspend fun deleteSetLogForPlannedSetSide(sessionId: Int, plannedSetId: Int, sideIndex: Int) {
        stored.removeAll { it.sessionId == sessionId && it.plannedSetId == plannedSetId && it.sideIndex == sideIndex }
    }
    override suspend fun setLogForPlannedSet(sessionId: Int, plannedSetId: Int): SetLogDraft? =
        stored.filter { it.sessionId == sessionId && it.plannedSetId == plannedSetId }.minByOrNull { it.draftId }
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
    override suspend fun deleteSurveysForMovements(sessionId: Int, movementIds: List<Int>) {}
    override suspend fun surveysForMovements(sessionId: Int, movementIds: List<Int>): List<SurveyDraft> = emptyList()
    override suspend fun deleteNoteForMovement(sessionId: Int, movementId: Int) {}
    override suspend fun noteForMovement(sessionId: Int, movementId: Int): NoteDraft? = null
    override suspend fun deleteSessionNote(sessionId: Int) {}
    override suspend fun sessionNote(sessionId: Int): NoteDraft? = null
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

    // ── Task 4 — giant-set round-major sequencing ────────────────────────────────────────

    private fun exercise(id: Int, idBase: Int, rounds: Int, unilateral: Boolean = false) = ExerciseOut(
        id = id, movement_id = id, movement_name = "ex$id", order_index = id,
        scheme = "STRAIGHT", objective = "", unilateral = unilateral,
        planned_sets = (0 until rounds).map { r ->
            PlannedSetOut(id = idBase + r, set_index = r, set_role = "WORKING", is_warmup = false)
        },
    )

    /**
     * GIANT_SET group (3 exercises × 3 rounds) flattens round-major: one set from each
     * exercise per round, not all of exercise-1's sets before exercise-2's.
     *
     * RED-confirmed against the old exercise-major flatten
     * (`g.exercises.flatMap { it.planned_sets }`): that would produce
     * [100,101,102, 200,201,202, 300,301,302] instead of the expected round-major order.
     */
    @Test
    fun giant_set_group_flattens_round_major() {
        val e1 = exercise(id = 1, idBase = 100, rounds = 3)
        val e2 = exercise(id = 2, idBase = 200, rounds = 3)
        val e3 = exercise(id = 3, idBase = 300, rounds = 3)
        val group = GroupOut(
            id = 1, order_index = 0, group_type = "GIANT_SET", rounds = 3,
            exercises = listOf(e1, e2, e3),
        )
        val flat = flattenPrescription(listOf(group))
        assertEquals(
            listOf(100, 200, 300, 101, 201, 301, 102, 202, 302),
            flat.map { it.id },
        )
    }

    /** STRAIGHT group stays exercise-major (unchanged behavior). */
    @Test
    fun straight_group_flattens_exercise_major() {
        val e1 = exercise(id = 1, idBase = 10, rounds = 2)
        val e2 = exercise(id = 2, idBase = 20, rounds = 2)
        val group = GroupOut(
            id = 1, order_index = 0, group_type = "STRAIGHT", rounds = 1,
            exercises = listOf(e1, e2),
        )
        val flat = flattenPrescription(listOf(group))
        assertEquals(listOf(10, 11, 20, 21), flat.map { it.id })
    }

    /**
     * A unilateral exercise's planned set is ONE cursor unit covering both sides: the cursor
     * must NOT skip to the next exercise after only one [CaptureViewModel.logWorkingSet] call —
     * it holds on the same planned-set id until a second call (side 2) is logged.
     */
    @Test
    fun unilateral_set_requires_both_sides_before_cursor_advances() = runBlocking {
        val (repo, _) = deps()
        val vm = CaptureViewModel(repo, sessionId = 7)
        val eUni = exercise(id = 1, idBase = 1, rounds = 1, unilateral = true) // planned_sets = [id=1]
        val eNext = exercise(id = 2, idBase = 2, rounds = 1) // planned_sets = [id=2]
        val group = GroupOut(
            id = 1, order_index = 0, group_type = "STRAIGHT", rounds = 1,
            exercises = listOf(eUni, eNext),
        )
        vm.initPrescriptionForTestFromGroups(listOf(group))

        assertEquals("cursor starts at the unilateral set", 1, vm.currentPlannedSetId.value)

        // Side 1 (e.g. left)
        vm.logWorkingSet(
            plannedSetId = 1, movementId = 1, setIndex = 0, setRole = "WORKING",
            actualLoad = 50.0, actualReps = 8, tap = "ON_TARGET",
        )
        assertEquals(
            "cursor stays on the unilateral set after side 1 — must not skip to next exercise",
            1,
            vm.currentPlannedSetId.value,
        )

        // Side 2 (e.g. right)
        vm.logWorkingSet(
            plannedSetId = 1, movementId = 1, setIndex = 0, setRole = "WORKING",
            actualLoad = 48.0, actualReps = 7, tap = "ON_TARGET",
        )
        assertEquals(
            "cursor advances to the next exercise only once both sides are logged",
            2,
            vm.currentPlannedSetId.value,
        )
    }

    // ── Fix B — logged sets expose their actuals and stay editable in place ─────────────────

    /**
     * A logged set's actual load/reps/tap are exposed via [CaptureViewModel.loggedSetActuals]
     * (not just the target, which is all the old UI showed). Correcting it via
     * [CaptureViewModel.editLoggedSet] updates the exposed actual AND the persisted row IN
     * PLACE — still exactly one Room row for this planned set, not a second one appended.
     */
    @Test
    fun logged_set_exposes_actual_and_edit_round_trip_updates_in_place() = runBlocking {
        val (repo, db) = deps()
        val vm = CaptureViewModel(repo, sessionId = 7)
        val ps0 = PlannedSetOut(id = 10, set_index = 0, set_role = "WORKING", is_warmup = false)
        vm.initPrescriptionForTest(listOf(ps0))

        vm.logWorkingSet(
            plannedSetId = 10, movementId = 3, setIndex = 0, setRole = "WORKING",
            actualLoad = 165.0, actualReps = 6, tap = "ON_TARGET",
        )
        val logged = vm.loggedSetActuals.value[10]
        assertEquals(165.0, logged?.actualLoad)
        assertEquals(6, logged?.actualReps)
        assertEquals("ON_TARGET", logged?.tap)
        assertEquals(1, db.captureDao().setLogsForSession(7).size)

        // Correct a mistake: re-open and re-save with different values.
        vm.editLoggedSet(
            plannedSetId = 10, movementId = 3, setIndex = 0, setRole = "WORKING",
            actualLoad = 170.0, actualReps = 5, tap = "TOO_HARD",
        )
        val corrected = vm.loggedSetActuals.value[10]
        assertEquals("edit updates the exposed actual", 170.0, corrected?.actualLoad)
        assertEquals("edit updates the exposed actual", 5, corrected?.actualReps)
        assertEquals("edit updates the exposed actual", "TOO_HARD", corrected?.tap)
        assertEquals(
            "edit updates the persisted row IN PLACE — still one row, not two",
            1,
            db.captureDao().setLogsForSession(7).size,
        )
        assertEquals(170.0, db.captureDao().setLogsForSession(7).single().actualLoad)
    }

    /** [CaptureViewModel.editLoggedSet] never touches the forward cursor — only a correction. */
    @Test
    fun editLoggedSet_does_not_move_the_cursor() = runBlocking {
        val (repo, _) = deps()
        val vm = CaptureViewModel(repo, sessionId = 7)
        val ps0 = PlannedSetOut(id = 10, set_index = 0, set_role = "WORKING", is_warmup = false)
        val ps1 = PlannedSetOut(id = 11, set_index = 1, set_role = "WORKING", is_warmup = false)
        vm.initPrescriptionForTest(listOf(ps0, ps1))

        vm.logWorkingSet(plannedSetId = 10, movementId = 3, setIndex = 0, setRole = "WORKING",
            actualLoad = 100.0, actualReps = 8, tap = "ON_TARGET")
        assertEquals(ps1.id, vm.currentPlannedSetId.value)

        vm.editLoggedSet(plannedSetId = 10, movementId = 3, setIndex = 0, setRole = "WORKING",
            actualLoad = 105.0, actualReps = 8, tap = "ON_TARGET")
        assertEquals("cursor must stay put after editing a PAST set", ps1.id, vm.currentPlannedSetId.value)
    }

    /** Same mandatory-tap gate as [CaptureViewModel.logWorkingSet] applies to edits. */
    @Test
    fun editLoggedSet_without_tap_is_rejected_for_working_role() = runBlocking {
        val (repo, db) = deps()
        val vm = CaptureViewModel(repo, sessionId = 7)
        val ps0 = PlannedSetOut(id = 10, set_index = 0, set_role = "WORKING", is_warmup = false)
        vm.initPrescriptionForTest(listOf(ps0))
        vm.logWorkingSet(plannedSetId = 10, movementId = 3, setIndex = 0, setRole = "WORKING",
            actualLoad = 100.0, actualReps = 8, tap = "ON_TARGET")

        vm.editLoggedSet(plannedSetId = 10, movementId = 3, setIndex = 0, setRole = "WORKING",
            actualLoad = 105.0, actualReps = 8, tap = null)

        assertNotNull(vm.uiError.value)
        assertEquals("rejected edit must not overwrite the prior actual", 100.0, vm.loggedSetActuals.value[10]?.actualLoad)
        assertEquals(100.0, db.captureDao().setLogsForSession(7).single().actualLoad)
    }

    // ── Fix J — idempotent set logging: same planned set logged twice → ONE row ─────────────

    /**
     * Day-1 incident: 7 planned sets were each submitted twice (double-tap), producing 7
     * duplicate Room rows that double-counted volume on submit. Logging the SAME planned set
     * twice (whatever the cause — double-tap, retry, race) must upsert in place, not append.
     */
    @Test
    fun logging_same_planned_set_twice_yields_one_row_not_a_duplicate() = runBlocking {
        val (repo, db) = deps()
        val vm = CaptureViewModel(repo, sessionId = 7)
        val ps0 = PlannedSetOut(id = 10, set_index = 0, set_role = "WORKING", is_warmup = false)
        val ps1 = PlannedSetOut(id = 11, set_index = 1, set_role = "WORKING", is_warmup = false)
        vm.initPrescriptionForTest(listOf(ps0, ps1))

        vm.logWorkingSet(plannedSetId = 10, movementId = 3, setIndex = 0, setRole = "WORKING",
            actualLoad = 100.0, actualReps = 8, tap = "ON_TARGET")
        // Double-submit of the SAME planned set (e.g. a fast double-tap before the button
        // visually disables, or a retried write after a transient error).
        vm.editLoggedSet(plannedSetId = 10, movementId = 3, setIndex = 0, setRole = "WORKING",
            actualLoad = 100.0, actualReps = 8, tap = "ON_TARGET")

        assertEquals(
            "double-submit of the same planned set must not duplicate the row",
            1,
            db.captureDao().setLogsForSession(7).size,
        )
    }

    /** Directly exercises the DAO-level upsert two different logSet calls for the same key. */
    @Test
    fun repo_logSet_upserts_by_session_and_planned_set_id() = runBlocking {
        val (repo, db) = deps()
        repo.logSet(SetLogDraft(sessionId = 7, plannedSetId = 20, movementId = 3, setIndex = 0,
            setRole = "WORKING", isWarmup = false, actualLoad = 100.0, actualReps = 8, feedbackTap = "ON_TARGET"))
        repo.logSet(SetLogDraft(sessionId = 7, plannedSetId = 20, movementId = 3, setIndex = 0,
            setRole = "WORKING", isWarmup = false, actualLoad = 100.0, actualReps = 8, feedbackTap = "ON_TARGET"))

        val rows = db.captureDao().setLogsForSession(7)
        assertEquals("upsert keyed on (sessionId, plannedSetId) — one row survives", 1, rows.size)
    }

    // ── Fix J (unilateral): side discriminator — both sides survive, same-side dedups ────────

    /**
     * Regression guard for the review-flagged over-collapse: a UNILATERAL set logs two rows
     * under the SAME plannedSetId (side 1 + side 2). The upsert is keyed on
     * (sessionId, plannedSetId, sideIndex), so BOTH sides must survive locally — losing side 1's
     * actual on the side-2 write is the exact bug being prevented. Both rows must then reach
     * submit() (2 SetLogIn entries), preserving volume.
     */
    @Test
    fun unilateral_set_keeps_both_sides_locally_and_both_reach_submit() = runBlocking {
        var capturedBody: String? = null
        val engine = MockEngine { req ->
            capturedBody = (req.body as io.ktor.http.content.TextContent).text
            respond("""{"session_id":7,"status":"COMPLETED","set_logs_written":2,"already_completed":false}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), CaptureDatabase::class.java,
        ).allowMainThreadQueries().build()
        val repo = CaptureRepo(ApiClient(engine = engine), db.captureDao())
        val vm = CaptureViewModel(repo, sessionId = 7)

        val eUni = exercise(id = 1, idBase = 1, rounds = 1, unilateral = true) // planned_sets = [id=1]
        val group = GroupOut(id = 1, order_index = 0, group_type = "STRAIGHT", rounds = 1, exercises = listOf(eUni))
        vm.initPrescriptionForTestFromGroups(listOf(group))

        // Side 1 (left) then side 2 (right) — distinct actuals.
        vm.logWorkingSet(plannedSetId = 1, movementId = 1, setIndex = 0, setRole = "WORKING",
            actualLoad = 50.0, actualReps = 8, tap = "ON_TARGET")
        vm.logWorkingSet(plannedSetId = 1, movementId = 1, setIndex = 0, setRole = "WORKING",
            actualLoad = 48.0, actualReps = 7, tap = "ON_TARGET")

        val rows = db.captureDao().setLogsForSession(7)
        assertEquals("both unilateral sides must survive locally", 2, rows.size)
        assertEquals("side 1 and side 2 loads both present",
            setOf(50.0, 48.0), rows.mapNotNull { it.actualLoad }.toSet())
        assertEquals("sides distinguished by sideIndex 0/1", setOf(0, 1), rows.map { it.sideIndex }.toSet())

        repo.submit(7)
        // Two set-log entries in the payload — one per side (volume preserved).
        val occurrences = Regex("\"planned_set_id\":1\\b").findAll(capturedBody!!).count()
        assertEquals("both sides reach submit()", 2, occurrences)
    }

    /**
     * A double-tap of the SAME side (same plannedSetId + same sideIndex) still collapses to one
     * row — the dedup we want, at the persistence layer where "side" is unambiguous. (At the VM
     * level a unilateral set is a deliberate two-tap side-1-then-side-2, so this same-side
     * guarantee is enforced by the (sessionId, plannedSetId, sideIndex) key, exercised here.)
     */
    @Test
    fun double_submit_of_same_unilateral_side_stays_one_row() = runBlocking {
        val (repo, db) = deps()
        repo.logSet(SetLogDraft(sessionId = 7, plannedSetId = 1, movementId = 1, setIndex = 0,
            setRole = "WORKING", isWarmup = false, actualLoad = 50.0, actualReps = 8,
            feedbackTap = "ON_TARGET", sideIndex = 0))
        repo.logSet(SetLogDraft(sessionId = 7, plannedSetId = 1, movementId = 1, setIndex = 0,
            setRole = "WORKING", isWarmup = false, actualLoad = 50.0, actualReps = 8,
            feedbackTap = "ON_TARGET", sideIndex = 0))
        // A DIFFERENT side is a distinct row.
        repo.logSet(SetLogDraft(sessionId = 7, plannedSetId = 1, movementId = 1, setIndex = 0,
            setRole = "WORKING", isWarmup = false, actualLoad = 48.0, actualReps = 7,
            feedbackTap = "ON_TARGET", sideIndex = 1))

        val rows = db.captureDao().setLogsForSession(7)
        assertEquals("same-side double-submit collapses; the other side survives", 2, rows.size)
    }

    // ── Fix J (edit): a correction must not wipe unsurfaced actuals (felt-peak) ──────────────

    /**
     * Because the upsert is a full-row replace and the edit UI only surfaces load/reps/tap,
     * [CaptureViewModel.editLoggedSet] must carry the original row's felt-peak (an HT/band-
     * composite set's real signal) into the corrected row — editing load must not null it.
     */
    @Test
    fun editing_an_ht_set_load_preserves_its_felt_peak() = runBlocking {
        val (repo, db) = deps()
        val vm = CaptureViewModel(repo, sessionId = 7)
        val ps0 = PlannedSetOut(id = 10, set_index = 0, set_role = "WORKING", is_warmup = false)
        vm.initPrescriptionForTest(listOf(ps0))

        // Log an HT set WITH a felt-peak.
        vm.logWorkingSet(plannedSetId = 10, movementId = 3, setIndex = 0, setRole = "WORKING",
            actualLoad = 100.0, actualReps = 6, tap = "ON_TARGET", feltPeak = 255.0)
        assertEquals(255.0, db.captureDao().setLogsForSession(7).single().feltPeak)

        // Correct just the load (edit UI never surfaces felt-peak → passes null).
        vm.editLoggedSet(plannedSetId = 10, movementId = 3, setIndex = 0, setRole = "WORKING",
            actualLoad = 170.0, actualReps = 6, tap = "ON_TARGET")

        val row = db.captureDao().setLogsForSession(7).single()
        assertEquals("load corrected", 170.0, row.actualLoad)
        assertEquals("felt-peak preserved across the edit — not nulled", 255.0, row.feltPeak)
    }
}
