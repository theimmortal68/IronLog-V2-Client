// CaptureRepoTest.kt — MockEngine asserts the submit payload + clear-on-success
package com.jauschua.ironlogv2.data.repo
import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import android.content.Context
import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.local.CaptureDatabase
import com.jauschua.ironlogv2.data.local.SetLogDraft
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CaptureRepoTest {
    private fun db() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(), CaptureDatabase::class.java).build()

    @Test fun submit_builds_payload_from_drafts_and_clears_on_success() = runBlocking {
        var capturedBody: String? = null
        val engine = MockEngine { req ->
            capturedBody = (req.body as io.ktor.http.content.TextContent).text
            respond("""{"session_id":7,"status":"COMPLETED","set_logs_written":1,"already_completed":false}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val dao = db().captureDao()
        dao.insertSetLog(SetLogDraft(sessionId = 7, plannedSetId = 10, movementId = 3,
            setIndex = 0, setRole = "WORKING", isWarmup = false, actualLoad = 100.0,
            actualReps = 8, feedbackTap = "ON_TARGET"))
        val repo = CaptureRepo(ApiClient(engine = engine), dao)

        val res = repo.submit(7)
        assertTrue(res.isSuccess)
        assertTrue(capturedBody!!.contains("\"feedback_tap\":\"ON_TARGET\""))
        assertTrue(capturedBody!!.contains("\"planned_set_id\":10"))
        assertEquals(0, dao.setLogsForSession(7).size)   // cleared on success
    }
}
