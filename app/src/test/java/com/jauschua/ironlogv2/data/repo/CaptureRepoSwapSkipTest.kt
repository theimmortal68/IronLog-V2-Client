// CaptureRepoSwapSkipTest.kt — MockEngine asserts request path/method/body + response parsing
// for the mid-workout skip/swap/substitutes repo functions.
package com.jauschua.ironlogv2.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import android.content.Context
import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.api.dto.ExerciseOut
import com.jauschua.ironlogv2.data.local.CaptureDatabase
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CaptureRepoSwapSkipTest {
    private fun db() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(), CaptureDatabase::class.java).build()

    private val exerciseOutJson = """
        {"id":3,"movement_id":9,"movement_name":"Back Squat","order_index":0,
         "scheme":"STRAIGHT","objective":"HYPERTROPHY",
         "planned_sets":[{"id":10,"set_index":0,"set_role":"WORKING","is_warmup":false,"is_skipped":true}]}
    """.trimIndent()

    @Test
    fun `skipExercise posts to the skip endpoint and returns the updated exercise`() = runBlocking {
        var capturedPath: String? = null
        var capturedMethod: HttpMethod? = null
        val engine = MockEngine { req ->
            capturedPath = req.url.encodedPath
            capturedMethod = req.method
            respond(exerciseOutJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val repo = CaptureRepo(ApiClient(engine = engine), db().captureDao())

        val res = repo.skipExercise(7, 3)

        assertTrue(res.isSuccess)
        assertEquals("/sessions/7/exercises/3/skip", capturedPath)
        assertEquals(HttpMethod.Post, capturedMethod)
        val out: ExerciseOut = res.getOrThrow()
        assertEquals(3, out.id)
        assertTrue(out.planned_sets.first().is_skipped)
    }

    @Test
    fun `swapExercise posts new_movement_id and make_permanent in the request body`() = runBlocking {
        var capturedPath: String? = null
        var capturedBody: String? = null
        val engine = MockEngine { req ->
            capturedPath = req.url.encodedPath
            capturedBody = (req.body as io.ktor.http.content.TextContent).text
            respond(exerciseOutJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val repo = CaptureRepo(ApiClient(engine = engine), db().captureDao())

        val res = repo.swapExercise(7, 3, 42, true)

        assertTrue(res.isSuccess)
        assertEquals("/sessions/7/exercises/3/swap", capturedPath)
        assertTrue(capturedBody!!.contains("\"new_movement_id\":42"))
        assertTrue(capturedBody!!.contains("\"make_permanent\":true"))
    }

    @Test
    fun `substitutesFor gets the substitutes endpoint for the given movement id`() = runBlocking {
        var capturedPath: String? = null
        var capturedMethod: HttpMethod? = null
        val engine = MockEngine { req ->
            capturedPath = req.url.encodedPath
            capturedMethod = req.method
            respond(
                """[{"id":11,"name":"Front Squat","primary_muscle":"QUADS","status":"ACTIVE"}]""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repo = CaptureRepo(ApiClient(engine = engine), db().captureDao())

        val res = repo.substitutesFor(42)

        assertTrue(res.isSuccess)
        assertEquals("/movements/substitutes/42", capturedPath)
        assertEquals(HttpMethod.Get, capturedMethod)
        val list = res.getOrThrow()
        assertEquals(1, list.size)
        assertEquals("Front Squat", list.first().name)
        assertEquals("QUADS", list.first().primary_muscle)
        assertEquals("ACTIVE", list.first().status)
    }
}
