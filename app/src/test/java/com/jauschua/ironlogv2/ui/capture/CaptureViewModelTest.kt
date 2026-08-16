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
import com.jauschua.ironlogv2.data.api.dto.SessionDetailResponse
import com.jauschua.ironlogv2.data.repo.CaptureRepo
import com.jauschua.ironlogv2.service.RestTimerController
import com.jauschua.ironlogv2.ui.UiState
import com.jauschua.ironlogv2.ui.screens.capture.CaptureViewModel
import com.jauschua.ironlogv2.ui.screens.capture.flattenPrescription
import com.jauschua.ironlogv2.ui.screens.capture.pastSetIds
import com.jauschua.ironlogv2.ui.screens.capture.reconstructCarriedLoad
import com.jauschua.ironlogv2.ui.screens.capture.reconstructCarriedReps
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
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
    override suspend fun setLogForPlannedSetSide(sessionId: Int, plannedSetId: Int, sideIndex: Int): SetLogDraft? =
        stored.filter { it.sessionId == sessionId && it.plannedSetId == plannedSetId && it.sideIndex == sideIndex }
            .minByOrNull { it.draftId }
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

private class RecordingRestTimerController : RestTimerController {
    private val _remainingSeconds = MutableStateFlow<Int?>(null)
    override val remainingSeconds: StateFlow<Int?> = _remainingSeconds.asStateFlow()

    val startedSeconds = mutableListOf<Int>()
    var skipCalls = 0
    val addedSeconds = mutableListOf<Int>()

    override fun startRest(seconds: Int) {
        startedSeconds += seconds
        _remainingSeconds.value = seconds
    }

    override fun skipRest() {
        skipCalls += 1
        _remainingSeconds.value = null
    }

    override fun addRestTime(extraSeconds: Int) {
        addedSeconds += extraSeconds
        _remainingSeconds.value = _remainingSeconds.value?.plus(extraSeconds)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CaptureViewModelTest {

    // [load] uses viewModelScope.launch (unlike logWorkingSet/editLoggedSet, which are plain
    // suspend fns the caller drives directly) — Main must be a TestDispatcher for it to run.
    // UnconfinedTestDispatcher runs it eagerly to its first real suspension (the MockEngine
    // call), same pattern as WizardViewModelTest. Harmless for the other tests in this class,
    // none of which touch viewModelScope.
    @Before
    fun setUpMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMain() {
        Dispatchers.resetMain()
    }

    /** Suspend until [flow] emits a value matching [predicate], failing fast on a 5s timeout. */
    private suspend fun <T> StateFlow<T>.await(predicate: (T) -> Boolean): T =
        withTimeout(5_000) { first(predicate) }

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

    @Test
    fun finish_captures_phase_transition_available_and_overwrites_pending_flow() = runBlocking {
        var submitCount = 0
        val engine = MockEngine {
            val phaseJson = when (submitCount) {
                0 -> "\"STAB\""
                1 -> "\"REBUILD\""
                else -> "null"
            }
            submitCount += 1
            respond(
                """{"session_id":7,"status":"COMPLETED","set_logs_written":1,"already_completed":false,"phase_transition_available":$phaseJson}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            CaptureDatabase::class.java,
        ).allowMainThreadQueries().build()
        val repo = CaptureRepo(ApiClient(engine = engine), db.captureDao())
        val pendingPhaseTransition = MutableStateFlow<String?>(null)
        val vm = CaptureViewModel(
            repo = repo,
            sessionId = 7,
            pendingPhaseTransition = pendingPhaseTransition,
        )

        vm.finish()
        assertEquals("COMPLETED", vm.submitResult.value)
        assertEquals("STAB", pendingPhaseTransition.value)

        vm.finish()
        assertEquals("REBUILD", pendingPhaseTransition.value)

        vm.finish()
        assertEquals("REBUILD", pendingPhaseTransition.value)
        db.close()
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

    @Test
    fun giant_set_rest_uses_hardest_tap_from_the_completed_round() = runBlocking {
        val (repo, _) = deps()
        val timer = RecordingRestTimerController()
        val vm = CaptureViewModel(repo, sessionId = 7, restTimerController = timer)
        val e1 = exercise(id = 1, idBase = 100, rounds = 2)
        val e2 = exercise(id = 2, idBase = 200, rounds = 2)
        val e3 = exercise(id = 3, idBase = 300, rounds = 2)
        val group = GroupOut(
            id = 1, order_index = 0, group_type = "GIANT_SET", rounds = 2,
            label = "T3", rest_seconds = 60, exercises = listOf(e1, e2, e3),
        )
        vm.initPrescriptionForTestFromGroups(listOf(group))

        vm.logWorkingSet(plannedSetId = 100, movementId = 1, setIndex = 0, setRole = "WORKING",
            actualLoad = 100.0, actualReps = 8, tap = "TOO_EASY")
        vm.logWorkingSet(plannedSetId = 200, movementId = 2, setIndex = 0, setRole = "WORKING",
            actualLoad = 100.0, actualReps = 8, tap = "TOO_HARD")
        vm.logWorkingSet(plannedSetId = 300, movementId = 3, setIndex = 0, setRole = "WORKING",
            actualLoad = 100.0, actualReps = 8, tap = "ON_TARGET")

        assertEquals(
            "trigger set was ON_TARGET, but the round's hardest tap was TOO_HARD",
            listOf(90),
            timer.startedSeconds,
        )
        assertEquals(90, vm.restRemainingSeconds.value)
    }

    @Test
    fun rest_timer_controls_route_to_controller() {
        val (repo, _) = deps()
        val timer = RecordingRestTimerController()
        val vm = CaptureViewModel(repo, sessionId = 7, restTimerController = timer)

        vm.skipRest()
        vm.addRestTime(45)

        assertEquals(1, timer.skipCalls)
        assertEquals(listOf(45), timer.addedSeconds)
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
        val logged = vm.loggedSetActuals.value[10 to 0]
        assertEquals(165.0, logged?.actualLoad)
        assertEquals(6, logged?.actualReps)
        assertEquals("ON_TARGET", logged?.tap)
        assertEquals(1, db.captureDao().setLogsForSession(7).size)

        // Correct a mistake: re-open and re-save with different values.
        vm.editLoggedSet(
            plannedSetId = 10, movementId = 3, setIndex = 0, setRole = "WORKING",
            actualLoad = 170.0, actualReps = 5, tap = "TOO_HARD",
        )
        val corrected = vm.loggedSetActuals.value[10 to 0]
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
        assertEquals("rejected edit must not overwrite the prior actual", 100.0, vm.loggedSetActuals.value[10 to 0]?.actualLoad)
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

    // ── Fix B (review): unilateral edit is side-aware — corrections stay isolated ───────────

    /**
     * A unilateral set logs TWO rows (side 1 + side 2) under one plannedSetId. The B edit path
     * used to be keyed by plannedSetId alone; corrections must now target the requested
     * sideIndex so editing one side cannot overwrite or clobber the other side's row.
     */
    @Test
    fun editing_one_side_of_a_unilateral_set_does_not_alter_the_other_side() = runBlocking {
        val (repo, db) = deps()
        val vm = CaptureViewModel(repo, sessionId = 7)
        val eUni = exercise(id = 1, idBase = 1, rounds = 1, unilateral = true) // planned_sets = [id=1]
        val group = GroupOut(id = 1, order_index = 0, group_type = "STRAIGHT", rounds = 1, exercises = listOf(eUni))
        vm.initPrescriptionForTestFromGroups(listOf(group))

        // Log both sides — distinct actuals.
        vm.logWorkingSet(plannedSetId = 1, movementId = 1, setIndex = 0, setRole = "WORKING",
            actualLoad = 50.0, actualReps = 8, tap = "ON_TARGET")
        vm.logWorkingSet(plannedSetId = 1, movementId = 1, setIndex = 0, setRole = "WORKING",
            actualLoad = 48.0, actualReps = 7, tap = "ON_TARGET")
        assertEquals(2, db.captureDao().setLogsForSession(7).size)

        val originalSide2 = vm.loggedSetActuals.value[1 to 1]

        // Correct side 1 (sideIndex 0). Side 2 must remain exactly as logged.
        vm.editLoggedSet(plannedSetId = 1, sideIndex = 0, movementId = 1, setIndex = 0, setRole = "WORKING",
            actualLoad = 999.0, actualReps = 1, tap = "TOO_HARD")

        assertEquals("side 1 reflects the correction", 999.0, vm.loggedSetActuals.value[1 to 0]?.actualLoad)
        assertEquals("side 1 reflects the correction", 1, vm.loggedSetActuals.value[1 to 0]?.actualReps)
        assertEquals("side 1 reflects the correction", "TOO_HARD", vm.loggedSetActuals.value[1 to 0]?.tap)
        assertEquals("side 2 actual remains unchanged", originalSide2, vm.loggedSetActuals.value[1 to 1])

        var rowsBySide = db.captureDao().setLogsForSession(7).associateBy { it.sideIndex }
        assertEquals("edit must not add/remove a row", 2, rowsBySide.size)
        assertEquals(999.0, rowsBySide[0]?.actualLoad)
        assertEquals(1, rowsBySide[0]?.actualReps)
        assertEquals("TOO_HARD", rowsBySide[0]?.feedbackTap)
        assertEquals(48.0, rowsBySide[1]?.actualLoad)
        assertEquals(7, rowsBySide[1]?.actualReps)
        assertEquals("ON_TARGET", rowsBySide[1]?.feedbackTap)

        val side1AfterFirstEdit = vm.loggedSetActuals.value[1 to 0]

        // Correct side 2 (sideIndex 1). Side 1's corrected values must survive intact.
        vm.editLoggedSet(plannedSetId = 1, sideIndex = 1, movementId = 1, setIndex = 0, setRole = "WORKING",
            actualLoad = 44.0, actualReps = 9, tap = "TOO_EASY")

        assertEquals("side 1 actual remains unchanged", side1AfterFirstEdit, vm.loggedSetActuals.value[1 to 0])
        assertEquals("side 2 reflects the correction", 44.0, vm.loggedSetActuals.value[1 to 1]?.actualLoad)
        assertEquals("side 2 reflects the correction", 9, vm.loggedSetActuals.value[1 to 1]?.actualReps)
        assertEquals("side 2 reflects the correction", "TOO_EASY", vm.loggedSetActuals.value[1 to 1]?.tap)

        rowsBySide = db.captureDao().setLogsForSession(7).associateBy { it.sideIndex }
        assertEquals("edit must not add/remove a row", 2, rowsBySide.size)
        assertEquals(999.0, rowsBySide[0]?.actualLoad)
        assertEquals(1, rowsBySide[0]?.actualReps)
        assertEquals("TOO_HARD", rowsBySide[0]?.feedbackTap)
        assertEquals(44.0, rowsBySide[1]?.actualLoad)
        assertEquals(9, rowsBySide[1]?.actualReps)
        assertEquals("TOO_EASY", rowsBySide[1]?.feedbackTap)
    }

    // ── Resume-cursor fix: background-kill no longer looks like lost progress ───────────────

    /**
     * [CaptureRepo]/Room backed by a MockEngine that branches on request path — GET
     * /sessions/today returns [session] (so [CaptureViewModel.load] can be driven end-to-end),
     * anything else (submit) returns a generic OK body. Mirrors WizardViewModelTest's
     * path-branching MockEngine.
     */
    private fun repoForLoad(session: SessionDetailResponse): Pair<CaptureRepo, CaptureDatabase> {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            CaptureDatabase::class.java,
        ).allowMainThreadQueries().build()
        val sessionJson = Json.encodeToString(session)
        val engine = MockEngine { req ->
            val body = if (req.url.encodedPath.endsWith("/sessions/today")) {
                sessionJson
            } else {
                """{"session_id":${session.id},"status":"COMPLETED","set_logs_written":1,"already_completed":false}"""
            }
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return CaptureRepo(ApiClient(engine = engine), db.captureDao()) to db
    }

    /**
     * The core data-loss-UX bug: an athlete logs 3 of 6 sets, the app is backgrounded and
     * killed, Android recreates the ViewModel, [CaptureViewModel.load] runs. The drafts are
     * durably persisted (write-before-advance already committed them) — this proves [load]
     * resumes the cursor at the first UNLOGGED set (flattened index 3, id 20) instead of
     * resetting to the first set (id 10), and that [pastSetIds] — which derives "logged" purely
     * from cursor position — therefore renders the 3 already-logged sets as logged again instead
     * of the whole session looking erased.
     */
    @Test
    fun load_resumes_cursor_at_first_unlogged_set_not_first_set() = runBlocking {
        // 2 exercises × 3 sets each, STRAIGHT (exercise-major): flattened order
        // [10,11,12, 20,21,22]. All of exercise 1 (first 3 of 6) already logged.
        val e1 = exercise(id = 1, idBase = 10, rounds = 3)
        val e2 = exercise(id = 2, idBase = 20, rounds = 3)
        val group = GroupOut(
            id = 1, order_index = 0, group_type = "STRAIGHT", rounds = 1,
            exercises = listOf(e1, e2),
        )
        val session = SessionDetailResponse(
            id = 7, date = "2026-07-07", day_role = "PUSH", phase = "BASE",
            status = "IN_PROGRESS", groups = listOf(group),
        )
        val (repo, db) = repoForLoad(session)

        // Drafts from BEFORE the (simulated) process kill — the ONLY evidence the resumed
        // cursor may reconstruct from.
        listOf(10, 11, 12).forEach { psId ->
            db.captureDao().insertSetLog(
                SetLogDraft(
                    sessionId = 7, plannedSetId = psId, movementId = 1, setIndex = 0,
                    setRole = "WORKING", isWarmup = false, actualLoad = 100.0, actualReps = 8,
                    feedbackTap = "ON_TARGET",
                ),
            )
        }

        // Fresh ViewModel — simulates Android recreating it after the process was killed.
        val vm = CaptureViewModel(repo, sessionId = 0)
        vm.load()
        vm.state.await { it is UiState.Success }

        assertEquals(
            "cursor must resume at the first UNLOGGED set (id 20 = exercise-2 set-1), " +
                "not reset to set 1 (id 10)",
            20,
            vm.currentPlannedSetId.value,
        )

        val flatSets = flattenPrescription(session.groups)
        assertEquals(
            "the 3 already-logged sets must render as logged/checkmarked again",
            setOf(10, 11, 12),
            pastSetIds(flatSets, vm.currentPlannedSetId.value),
        )

        val actuals = vm.loggedSetActuals.value
        assertEquals(3, actuals.size)
        assertEquals(100.0, actuals[10 to 0]?.actualLoad)
        assertEquals(100.0, actuals[11 to 0]?.actualLoad)
        assertEquals(100.0, actuals[12 to 0]?.actualLoad)
    }

    /**
     * A unilateral set with only side 1 (sideIndex 0) logged before the process died is
     * MID-set, not done: the resumed cursor must land ON that same planned set (not skip past
     * it), and the restored [CaptureViewModel] must treat the very next [logWorkingSet] call as
     * side 2 (advancing the cursor) rather than side 1 again (which would hold the cursor and
     * silently drop the true side-2 data by writing it as a duplicate side-1 row).
     */
    @Test
    fun load_resumes_mid_unilateral_set_when_only_side_one_logged() = runBlocking {
        val eUni = exercise(id = 1, idBase = 1, rounds = 1, unilateral = true) // planned_sets = [id=1]
        val eNext = exercise(id = 2, idBase = 2, rounds = 1) // planned_sets = [id=2]
        val group = GroupOut(
            id = 1, order_index = 0, group_type = "STRAIGHT", rounds = 1,
            exercises = listOf(eUni, eNext),
        )
        val session = SessionDetailResponse(
            id = 7, date = "2026-07-07", day_role = "PUSH", phase = "BASE",
            status = "IN_PROGRESS", groups = listOf(group),
        )
        val (repo, db) = repoForLoad(session)

        // Only side 1 (sideIndex 0) was logged before the process died.
        db.captureDao().insertSetLog(
            SetLogDraft(
                sessionId = 7, plannedSetId = 1, movementId = 1, setIndex = 0,
                setRole = "WORKING", isWarmup = false, actualLoad = 50.0, actualReps = 8,
                feedbackTap = "ON_TARGET", sideIndex = 0,
            ),
        )

        val vm = CaptureViewModel(repo, sessionId = 0)
        vm.load()
        vm.state.await { it is UiState.Success }

        assertEquals(
            "cursor lands ON the mid-logged unilateral set (side 2 still pending) — not past it",
            1,
            vm.currentPlannedSetId.value,
        )

        // Observable proof that unilateralSideCount was restored to 1 (not reset to 0): the
        // NEXT call must be treated as side 2 — committing sideIndex 1 and advancing past the
        // unilateral set — rather than side 1 again (which would hold the cursor at id 1).
        vm.logWorkingSet(
            plannedSetId = 1, movementId = 1, setIndex = 0, setRole = "WORKING",
            actualLoad = 48.0, actualReps = 7, tap = "ON_TARGET",
        )
        assertEquals(
            "second side committed, cursor advances past the unilateral set",
            2,
            vm.currentPlannedSetId.value,
        )
        val rows = db.captureDao().setLogsForSession(7)
        assertEquals("both sides now persisted, no duplicate side-1 row", 2, rows.size)
        assertEquals(setOf(0, 1), rows.map { it.sideIndex }.toSet())
        assertEquals(setOf(50.0, 48.0), rows.mapNotNull { it.actualLoad }.toSet())
    }

    /** When every planned set is already fully logged, the resumed cursor is null (session
     * complete / ready to submit) — same end-state as finishing the session live. */
    @Test
    fun load_leaves_cursor_null_when_all_sets_are_already_fully_logged() = runBlocking {
        val e1 = exercise(id = 1, idBase = 10, rounds = 2)
        val group = GroupOut(id = 1, order_index = 0, group_type = "STRAIGHT", rounds = 1, exercises = listOf(e1))
        val session = SessionDetailResponse(
            id = 7, date = "2026-07-07", day_role = "PUSH", phase = "BASE",
            status = "IN_PROGRESS", groups = listOf(group),
        )
        val (repo, db) = repoForLoad(session)
        listOf(10, 11).forEach { psId ->
            db.captureDao().insertSetLog(
                SetLogDraft(
                    sessionId = 7, plannedSetId = psId, movementId = 1, setIndex = 0,
                    setRole = "WORKING", isWarmup = false, actualLoad = 100.0, actualReps = 8,
                    feedbackTap = "ON_TARGET",
                ),
            )
        }

        val vm = CaptureViewModel(repo, sessionId = 0)
        vm.load()
        vm.state.await { it is UiState.Success }

        assertNull("all sets logged → cursor null, same as today's end-state", vm.currentPlannedSetId.value)
    }

    // ── Restart-survival fix: carry-forward map reconstruction after a simulated app relaunch ──

    /**
     * Root cause (confirmed live via `adb logcat -s CarryFwd:D`): `carriedLoadByMovement` /
     * `carriedRepsByMovement` in CaptureScreen.kt are pure in-memory Compose state, never
     * persisted. If Android kills the process while backgrounded and the app relaunches, [load]
     * correctly resumes the CURSOR from Room (see `load_leaves_cursor_null_when_all_sets_are_...`
     * above and the mid-unilateral-set resume test), but the carry-forward map came back empty --
     * at the SAME cursor position, the diagnostic log showed `carried=3.0` before a background
     * kill and `carried=null` five minutes later after relaunch.
     *
     * This test drives the real production path an app relaunch takes: real Room inserts for
     * sets logged in a "previous process" (mirroring the earlier tests' fixture pattern), then a
     * FRESH [CaptureViewModel] with `vm.load()` called once (simulating cold start after process
     * death) -- never a live carry-forward write via `withCarriedLoad`/`withCarriedReps`. The
     * fix, `reconstructCarriedLoad`/`reconstructCarriedReps` (CaptureScreen.kt), is applied to
     * exactly what [load] leaves in `vm.loggedSetActuals` + the loaded session -- the same inputs
     * CaptureScreen's `remember(session.id)` seed now uses -- proving the reconstruction is
     * correct end-to-end from persisted data, not merely that the write-path works.
     *
     * Multi-exercise session (three movements, like a giant set): movement 1 has TWO logged sets
     * (10 -> set_index 0, 11 -> set_index 1) -- the carried value must come from set 11 (the
     * LATER one), not 10, proving the reconstruction picks the most-recently-logged set per
     * movement rather than just any logged set. Movement 2 has one logged set. Movement 3 has
     * none logged yet (not reached before the kill) and must be absent from both carry maps.
     */
    @Test
    fun load_after_simulated_relaunch_reconstructs_carry_forward_maps_from_persisted_actuals() = runBlocking {
        val e1 = exercise(id = 1, idBase = 10, rounds = 2) // movement_id 1, planned sets 10 (idx0), 11 (idx1)
        val e2 = exercise(id = 2, idBase = 20, rounds = 2) // movement_id 2, planned sets 20 (idx0), 21 (idx1)
        val e3 = exercise(id = 3, idBase = 30, rounds = 2) // movement_id 3, planned sets 30 (idx0), 31 (idx1)
        val group = GroupOut(
            id = 1, order_index = 0, group_type = "STRAIGHT", rounds = 1,
            exercises = listOf(e1, e2, e3),
        )
        val session = SessionDetailResponse(
            id = 7, date = "2026-08-16", day_role = "PUSH", phase = "BASE",
            status = "IN_PROGRESS", groups = listOf(group),
        )
        val (repo, db) = repoForLoad(session)

        // Sets logged in the "previous process" — never touches carriedLoadByMovement/
        // carriedRepsByMovement (those are screen-local, dead once the process is killed); only
        // Room durably survives.
        db.captureDao().insertSetLog(
            SetLogDraft(
                sessionId = 7, plannedSetId = 10, movementId = 1, setIndex = 0, setRole = "WORKING",
                isWarmup = false, actualLoad = 170.0, actualReps = 8, feedbackTap = "ON_TARGET",
            ),
        )
        db.captureDao().insertSetLog(
            SetLogDraft(
                sessionId = 7, plannedSetId = 11, movementId = 1, setIndex = 1, setRole = "WORKING",
                isWarmup = false, actualLoad = 175.0, actualReps = 8, feedbackTap = "ON_TARGET",
            ),
        )
        db.captureDao().insertSetLog(
            SetLogDraft(
                sessionId = 7, plannedSetId = 20, movementId = 2, setIndex = 0, setRole = "WORKING",
                isWarmup = false, actualLoad = 60.0, actualReps = 10, feedbackTap = "ON_TARGET",
            ),
        )

        // Simulated relaunch: brand-new ViewModel instance, no prior in-memory carry state.
        val vm = CaptureViewModel(repo, sessionId = 0)
        vm.load()
        vm.state.await { it is UiState.Success }

        val loadedSession = (vm.state.value as UiState.Success).data!!
        val carriedLoad = reconstructCarriedLoad(loadedSession, vm.loggedSetActuals.value)
        val carriedReps = reconstructCarriedReps(loadedSession, vm.loggedSetActuals.value)

        assertEquals(
            "movement 1's carry value comes from set 11 (set_index 1, the LATER logged set) — " +
                "not set 10, which would be the subtler wrong-direction bug",
            mapOf(1 to 175.0, 2 to 60.0),
            carriedLoad,
        )
        assertEquals(mapOf(1 to 8, 2 to 10), carriedReps)
        assertNull(
            "movement 3 was never logged before the simulated kill — must be ABSENT, not defaulted",
            carriedLoad[3],
        )
    }

    // ── Review-response fix: cursor re-selection after skipping the currently active exercise ──

    /**
     * Opus review of 25dd7e9 (HIGH): `skip_exercise`/`swap_exercise` mutate PlannedSet rows IN
     * PLACE server-side -- ids are unchanged, only `is_skipped` flips. Skipping the exercise the
     * cursor is CURRENTLY on must not strand it on a set now marked skipped (the old fallback's
     * `flattenedPrescription.none { it.id == cur }` never fired, since the id was still present);
     * it must land on the next not-skipped, not-fully-logged set — same semantics as [load]'s
     * resumeSet — not jump backward to an earlier already-logged set (the old fallback's
     * `firstOrNull { !it.is_skipped }` would have done exactly that).
     *
     * Session: exercise 1 (sets 10,11) is fully logged BEFORE the skip, exercise 2 (sets 20,21)
     * is the CURRENT exercise (cursor sits on set 20) and gets skipped, exercise 3 (sets 30,31)
     * is untouched. Correct post-skip cursor is 30 — not 20/21 (just skipped) and not 10/11
     * (earlier, already logged — the bug this regression guards against would jump there).
     *
     * RED-confirmed against the reverted (buggy) `applyUpdatedExercise`: reverting to
     *   `if (cur != null && flattenedPrescription.none { it.id == cur })
     *        _currentPlannedSetId.value = flattenedPrescription.firstOrNull { !it.is_skipped }?.id`
     * left the cursor at 20 (unchanged — the id is still present) instead of advancing to 30,
     * causing the final assertEquals to fail with actual == 20.
     */
    @Test
    fun skipping_the_current_exercise_reselects_cursor_forward_not_to_an_earlier_logged_set() = runBlocking {
        val e1 = exercise(id = 1, idBase = 10, rounds = 2)
        val e2 = exercise(id = 2, idBase = 20, rounds = 2)
        val e3 = exercise(id = 3, idBase = 30, rounds = 2)
        val group = GroupOut(
            id = 1, order_index = 0, group_type = "STRAIGHT", rounds = 1,
            exercises = listOf(e1, e2, e3),
        )
        val session = SessionDetailResponse(
            id = 7, date = "2026-07-07", day_role = "PUSH", phase = "BASE",
            status = "IN_PROGRESS", groups = listOf(group),
        )
        val sessionJson = Json.encodeToString(session)
        // Server-side skip: the exercise's planned sets are patched is_skipped=true IN PLACE —
        // same ids, no rows added/removed — mirroring the real skip_exercise/swap_exercise
        // behavior confirmed against ironlog/api/app.py.
        val skippedE2 = e2.copy(planned_sets = e2.planned_sets.map { it.copy(is_skipped = true) })
        val skippedE2Json = Json.encodeToString(skippedE2)

        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), CaptureDatabase::class.java,
        ).allowMainThreadQueries().build()
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            val body = when {
                path.endsWith("/sessions/today") -> sessionJson
                path.endsWith("/exercises/2/skip") -> skippedE2Json
                else -> """{"session_id":7,"status":"COMPLETED","set_logs_written":1,"already_completed":false}"""
            }
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repo = CaptureRepo(ApiClient(engine = engine), db.captureDao())

        // Exercise 1 fully logged BEFORE the (simulated) session resumes.
        listOf(10, 11).forEach { psId ->
            db.captureDao().insertSetLog(
                SetLogDraft(
                    sessionId = 7, plannedSetId = psId, movementId = 1, setIndex = 0,
                    setRole = "WORKING", isWarmup = false, actualLoad = 100.0, actualReps = 8,
                    feedbackTap = "ON_TARGET",
                ),
            )
        }

        val vm = CaptureViewModel(repo, sessionId = 0)
        vm.load()
        vm.state.await { it is UiState.Success }
        assertEquals("cursor starts on exercise 2's first set", 20, vm.currentPlannedSetId.value)

        vm.skipExercise(exerciseId = 2)

        assertEquals(
            "cursor lands on exercise 3's first set — not the just-skipped set, and not " +
                "jumped backward to exercise 1's already-logged sets",
            30,
            vm.currentPlannedSetId.value,
        )
    }
}
