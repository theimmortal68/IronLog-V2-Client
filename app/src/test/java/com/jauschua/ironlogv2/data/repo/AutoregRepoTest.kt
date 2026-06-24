package com.jauschua.ironlogv2.data.repo

import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.api.dto.FeedbackTap
import com.jauschua.ironlogv2.data.api.dto.NextSetRequest
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
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoregRepoTest {

    @Test
    fun nextSet_postsExpectedBodyAndParsesResponse() = runTest {
        var capturedBody: String? = null
        var capturedMethod: HttpMethod? = null
        var capturedPath: String? = null

        val engine = MockEngine { request ->
            capturedMethod = request.method
            capturedPath = request.url.encodedPath
            capturedBody = (request.body as TextContent).text
            respond(
                content = ByteReadChannel("""{"suggested_load": 105.0}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repo = AutoregRepo(ApiClient(baseUrl = "http://test", engine = engine))

        val req = NextSetRequest(movement_id = 1, current_load = 100.0, tap = FeedbackTap.ON_TARGET, tier = 0)
        val result = repo.nextSet(req)

        assertTrue(result.isSuccess)
        assertEquals(105.0, result.getOrThrow().suggested_load, 0.0)
        assertEquals(HttpMethod.Post, capturedMethod)
        assertEquals("/autoregulate/next-set", capturedPath)
        assertNotNull(capturedBody)
        // body is canonical kotlinx JSON in declaration order
        assertEquals("""{"movement_id":1,"current_load":100.0,"tap":"ON_TARGET","tier":0}""", capturedBody)
    }
}
