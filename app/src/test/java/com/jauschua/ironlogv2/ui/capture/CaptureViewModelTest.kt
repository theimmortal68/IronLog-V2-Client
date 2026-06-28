// CaptureViewModelTest.kt
package com.jauschua.ironlogv2.ui.capture

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.local.CaptureDatabase
import com.jauschua.ironlogv2.data.repo.CaptureRepo
import com.jauschua.ironlogv2.ui.screens.capture.CaptureViewModel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CaptureViewModelTest {

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

    /** Gate #2 (client half): working set without a tap is rejected — no Room write, no advance. */
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

    /** Gate #5 (write-before-advance / durability): after the suspend returns, the durable Row
     *  exists IN ROOM and nextSetIndex already shows advanced — proving the commit completed
     *  synchronously inside logWorkingSet before the index was mutated.
     *
     *  Why this is NON-FRAGILE:
     *  - `logWorkingSet` is a `suspend fun` that directly `await`s `repo.logSet(draft)`.
     *  - `repo.logSet` calls `dao.insertSetLog`, a Room `@Insert suspend` that suspends
     *    until the SQLite transaction commits and returns.
     *  - Only AFTER that suspend returns does `_nextSetIndex.value = setIndex + 1` execute.
     *  - So when `logWorkingSet` returns to `runBlocking`, both the commit and the index
     *    advance are guaranteed complete — no races.
     *
     *  A fire-and-forget implementation such as:
     *    `viewModelScope.launch { repo.logSet(draft) }  // not awaited`
     *    `_nextSetIndex.value = setIndex + 1             // advances immediately`
     *  would make `logWorkingSet` return with `nextSetIndex == 1` but the Room row not yet
     *  written (the launched coroutine is still pending on the IO dispatcher).  The
     *  `assertEquals(1, db.captureDao().setLogsForSession(7).size)` assert would see 0 and
     *  the test would go RED — catching the ordering bug.
     */
    @Test
    fun working_set_is_committed_to_room_before_advance() = runBlocking {
        val (repo, db) = deps()
        val vm = CaptureViewModel(repo, sessionId = 7)
        vm.logWorkingSet(
            plannedSetId = 10, movementId = 3, setIndex = 0, setRole = "WORKING",
            actualLoad = 100.0, actualReps = 8, tap = "ON_TARGET",
        )
        // Both must hold at this exact point — row committed AND index advanced.
        assertEquals(1, db.captureDao().setLogsForSession(7).size)
        assertEquals(1, vm.nextSetIndex.value)
    }
}
