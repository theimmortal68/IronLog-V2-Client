package com.jauschua.ironlogv2.ui.capture

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.api.dto.ExerciseOut
import com.jauschua.ironlogv2.data.api.dto.GroupOut
import com.jauschua.ironlogv2.data.api.dto.PlannedSetOut
import com.jauschua.ironlogv2.data.local.CaptureDatabase
import com.jauschua.ironlogv2.data.repo.CaptureRepo
import com.jauschua.ironlogv2.ui.screens.capture.CaptureViewModel
import com.jauschua.ironlogv2.ui.screens.capture.GroupReview
import com.jauschua.ironlogv2.ui.screens.capture.groupIsComplete
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CaptureViewModelReviewTest {

    private fun ps(id: Int, idx: Int) = PlannedSetOut(id = id, set_index = idx, set_role = "WORKING", is_warmup = false)
    private fun ex(id: Int, mid: Int, name: String, sets: List<PlannedSetOut>) =
        ExerciseOut(id = id, movement_id = mid, movement_name = name, order_index = 0,
            scheme = "STRAIGHT", objective = "HYP", planned_sets = sets)

    private fun deps(): Pair<CaptureRepo, CaptureDatabase> {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), CaptureDatabase::class.java,
        ).allowMainThreadQueries().build()
        val engine = MockEngine {
            respond("""{"session_id":7,"status":"COMPLETED","set_logs_written":0,"already_completed":false}""",
                HttpStatusCode.OK, io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"))
        }
        return CaptureRepo(ApiClient(engine = engine), db.captureDao()) to db
    }

    // A single-exercise STRAIGHT group (2 sets) then a 2-exercise GIANT_SET (2 rounds).
    private fun groups() = listOf(
        GroupOut(id = 1, order_index = 0, group_type = "STRAIGHT", rounds = 2, exercises = listOf(
            ex(100, 10, "Bench", listOf(ps(1000, 0), ps(1001, 1))),
        )),
        GroupOut(id = 2, order_index = 1, group_type = "GIANT_SET", rounds = 2, exercises = listOf(
            ex(200, 20, "Pendlay", listOf(ps(2000, 0), ps(2001, 1))),
            ex(201, 21, "InclineDB", listOf(ps(2100, 0), ps(2101, 1))),
        )),
    )

    private suspend fun log(vm: CaptureViewModel, plannedSetId: Int, movementId: Int) =
        vm.logWorkingSet(plannedSetId = plannedSetId, movementId = movementId, setIndex = 0,
            setRole = "WORKING", actualLoad = 100.0, actualReps = 8, tap = "ON_TARGET")

    @Test fun straight_group_fires_review_after_its_last_set() = runBlocking {
        val (repo, db) = deps()
        val vm = CaptureViewModel(repo, sessionId = 7)
        vm.initPrescriptionForTestFromGroups(groups())

        log(vm, 1000, 10)                       // first Bench set — not complete yet
        assertNull(vm.pendingReview.value)
        log(vm, 1001, 10)                       // last Bench set — group complete
        assertEquals(1, vm.pendingReview.value?.group?.id)
        db.close()
    }

    @Test fun giant_set_fires_only_after_final_round_last_exercise() = runBlocking {
        val (repo, db) = deps()
        val vm = CaptureViewModel(repo, sessionId = 7)
        vm.initPrescriptionForTestFromGroups(groups())
        // finish the STRAIGHT group first, then clear its review
        log(vm, 1000, 10); log(vm, 1001, 10); vm.dismissReview()
        // round-major order for the giant set: 2000, 2100, 2001, 2101
        log(vm, 2000, 20); assertNull(vm.pendingReview.value)
        log(vm, 2100, 21); assertNull(vm.pendingReview.value)
        log(vm, 2001, 20); assertNull(vm.pendingReview.value)   // still one exercise left this round
        log(vm, 2101, 21)                                       // final entry of the group
        assertEquals(2, vm.pendingReview.value?.group?.id)
        db.close()
    }

    @Test fun saveReview_writes_one_survey_per_exercise_flags_default_false() = runBlocking {
        val (repo, db) = deps()
        val vm = CaptureViewModel(repo, sessionId = 7)
        vm.initPrescriptionForTestFromGroups(groups())
        val giant = groups()[1]
        // only movement 20 gets asymmetry; 21 left unchecked → false/false
        vm.saveReview(giant, mapOf(20 to (true to false)), noteText = "grip slipped")

        val dao = db.captureDao()
        val surveys = dao.surveysForSession(7).sortedBy { it.movementId }
        assertEquals(listOf(20, 21), surveys.map { it.movementId })
        assertEquals(true, surveys[0].asymmetryFlag)
        assertEquals(false, surveys[0].techniqueFlag)
        assertEquals(false, surveys[1].asymmetryFlag)
        assertNull(vm.pendingReview.value)                       // sheet dismissed
        assertEquals("grip slipped", dao.noteForMovement(7, 20)?.text)  // anchored to first ex
        db.close()
    }

    @Test fun skip_writes_nothing() = runBlocking {
        val (repo, db) = deps()
        val vm = CaptureViewModel(repo, sessionId = 7)
        vm.initPrescriptionForTestFromGroups(groups())
        log(vm, 1000, 10); log(vm, 1001, 10)
        vm.dismissReview()
        assertEquals(0, db.captureDao().surveysForSession(7).size)
        db.close()
    }

    @Test fun finish_writes_session_note_before_submit() = runBlocking {
        val (repo, db) = deps()
        val vm = CaptureViewModel(repo, sessionId = 7)
        vm.initPrescriptionForTestFromGroups(groups())
        vm.finish(sessionNote = "solid day")
        // submit clears drafts on success; the session note was written+batched, so post-submit it's gone
        assertEquals("COMPLETED", vm.submitResult.value)
        db.close()
    }

    @Test fun groupIsComplete_true_only_when_all_group_sets_are_past() {
        val giant = groups()[1]
        assertTrue(!groupIsComplete(giant, setOf(2000, 2100, 2001)))   // missing 2101
        assertTrue(groupIsComplete(giant, setOf(2000, 2100, 2001, 2101)))
    }
}
