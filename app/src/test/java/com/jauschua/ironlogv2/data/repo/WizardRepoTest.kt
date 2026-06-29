package com.jauschua.ironlogv2.data.repo

import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.api.dto.WizardResolution
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WizardRepoTest {

    @Test
    fun state_getsProgramScopedPathAndParsesTrustPrefillCount() = runTest {
        var capturedMethod: HttpMethod? = null
        var capturedPath: String? = null

        val engine = MockEngine { request ->
            capturedMethod = request.method
            capturedPath = request.url.encodedPath
            respond(
                content = ByteReadChannel(
                    """{"program_id":7,"program_name":"Hypertrophy A","needs_attention_count":2,""" +
                        """"ready_to_start":false,"movements":[""" +
                        """{"movement_id":11,"movement_name":"Bench Press","load_field":"current_load",""" +
                        """"trust":"STALE","prefill_value":135.0,"unit_hint":"lb"},""" +
                        """{"movement_id":22,"movement_name":"Assisted Dip","load_field":"assist_level",""" +
                        """"trust":"UNKNOWN"}]}""",
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repo = WizardRepo(ApiClient(baseUrl = "http://test", engine = engine))

        val result = repo.state(programId = 7)

        assertTrue(result.isSuccess)
        val body = result.getOrThrow()
        assertEquals(HttpMethod.Get, capturedMethod)
        assertEquals("/programs/7/wizard-state", capturedPath)
        assertEquals(7, body.program_id)
        assertEquals(2, body.needs_attention_count)
        assertEquals(false, body.ready_to_start)
        assertEquals(2, body.movements.size)
        val stale = body.movements[0]
        assertEquals("STALE", stale.trust)
        assertEquals(135.0, stale.prefill_value!!, 0.0)
        assertEquals("lb", stale.unit_hint)
        val unknown = body.movements[1]
        assertEquals("UNKNOWN", unknown.trust)
        assertNull(unknown.prefill_value)
    }

    @Test
    fun resolve_postsSnakeCaseBodyToProgramScopedPathAndParsesResponse() = runTest {
        var capturedBody: String? = null
        var capturedMethod: HttpMethod? = null
        var capturedPath: String? = null

        val engine = MockEngine { request ->
            capturedMethod = request.method
            capturedPath = request.url.encodedPath
            capturedBody = (request.body as TextContent).text
            respond(
                content = ByteReadChannel(
                    """{"resolved":2,"needs_attention_count":0,"ready_to_start":true}""",
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repo = WizardRepo(ApiClient(baseUrl = "http://test", engine = engine))

        val result = repo.resolve(
            programId = 7,
            resolutions = listOf(
                WizardResolution(movement_id = 11, value = 140.0),
                WizardResolution(movement_id = 22, value = 1.0),
            ),
        )

        assertTrue(result.isSuccess)
        val body = result.getOrThrow()
        assertEquals(2, body.resolved)
        assertEquals(0, body.needs_attention_count)
        assertEquals(true, body.ready_to_start)
        assertEquals(HttpMethod.Post, capturedMethod)
        assertEquals("/programs/7/wizard-resolve", capturedPath)
        assertNotNull(capturedBody)
        assertEquals(
            """{"resolutions":[{"movement_id":11,"value":140.0},{"movement_id":22,"value":1.0}]}""",
            capturedBody,
        )
    }

    @Test
    fun start_postsProgramScopedPathAndParsesResponse() = runTest {
        var capturedMethod: HttpMethod? = null
        var capturedPath: String? = null

        val engine = MockEngine { request ->
            capturedMethod = request.method
            capturedPath = request.url.encodedPath
            respond(
                content = ByteReadChannel(
                    """{"program_id":7,"started":true,"active":true}""",
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repo = WizardRepo(ApiClient(baseUrl = "http://test", engine = engine))

        val result = repo.start(programId = 7)

        assertTrue(result.isSuccess)
        val body = result.getOrThrow()
        assertEquals(7, body.program_id)
        assertEquals(true, body.started)
        assertEquals(true, body.active)
        assertEquals(HttpMethod.Post, capturedMethod)
        assertEquals("/programs/7/start", capturedPath)
    }
}
