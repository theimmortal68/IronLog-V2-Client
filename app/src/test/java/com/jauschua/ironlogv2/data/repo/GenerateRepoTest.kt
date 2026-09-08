package com.jauschua.ironlogv2.data.repo

import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.api.ApiError
import com.jauschua.ironlogv2.data.api.IronLogException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class, InternalAPI::class)
class GenerateRepoTest {
    @Test
    fun generate_appliesSocketBudgetToRealOkHttpEngineOnlyForGeneration() = runTest {
        val observedTimeouts = mutableListOf<List<Int>>()
        val engine = OkHttp.create {
            config {
                // Exercise the real Ktor/OkHttp adapter without a network server.
                addInterceptor { chain ->
                    observedTimeouts.add(listOf(
                        chain.connectTimeoutMillis(),
                        chain.readTimeoutMillis(),
                        chain.writeTimeoutMillis(),
                    ))
                    val content = if (chain.request().url.encodedPath == "/generate") {
                        """{"candidate_id":"candidate-1","day_role":"signal","exhausted":false,"attempts":1,"scope":"day"}"""
                    } else {
                        """["signal"]"""
                    }
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/json")
                        .body(content.toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
        }
        val api = ApiClient(baseUrl = "http://test", engine = engine)
        try {
            val repo = GenerateRepo(api)
            assertTrue(repo.generate("signal").isSuccess)
            assertEquals(listOf("signal"), repo.programDays(7).getOrThrow())
            assertTrue(repo.generate("signal").isSuccess)
            assertEquals(
                listOf(
                    listOf(5_000, 200_000, 200_000),
                    listOf(5_000, 10_000, 10_000),
                    listOf(5_000, 200_000, 200_000),
                ),
                observedTimeouts,
            )
        } finally {
            api.http.close()
            engine.close()
        }
    }

    @Test
    fun generate_succeedsAfterGlobalTimeoutAndPreservesRequestConfiguration() = runTest {
        val engine = delayedEngine(11_000)
        val api = ApiClient(baseUrl = "http://test", engine = engine)
        try {
            val repo = GenerateRepo(api)
            val result = repo.generate("signal")

            assertEquals("candidate-1", result.getOrThrow().candidate_id)
            assertEquals(11_000L, testScheduler.currentTime)
            val request = engine.requestHistory.single()
            assertEquals("http://test/generate", request.url.toString())
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("""{"day_role":"signal"}""", (request.body as TextContent).text)
            val timeout = request.getCapabilityOrNull(HttpTimeoutCapability)!!
            assertEquals(200_000L, timeout.requestTimeoutMillis)
            assertEquals(200_000L, timeout.socketTimeoutMillis)
            assertEquals(5_000L, timeout.connectTimeoutMillis)

            // A second call also succeeds using the same derived client/engine.
            assertTrue(repo.generate("signal").isSuccess)
        } finally {
            api.http.close()
            engine.close()
        }
    }

    @Test
    fun generate_timesOutAtItsOwnBudget() = runTest {
        val engine = delayedEngine(201_000)
        val api = ApiClient(baseUrl = "http://test", engine = engine)
        try {
            val result = GenerateRepo(api).generate("signal")

            assertTrue((result.exceptionOrNull() as IronLogException).error is ApiError.Timeout)
            assertEquals(200_000L, testScheduler.currentTime)
        } finally {
            api.http.close()
            engine.close()
        }
    }

    @Test
    fun otherEndpoints_keepGlobalTimeoutAfterGeneration() = runTest {
        val engine = delayedEngine(11_000)
        val api = ApiClient(baseUrl = "http://test", engine = engine)
        try {
            val repo = GenerateRepo(api)
            assertTrue(repo.generate("signal").isSuccess)
            val result = repo.programDays(7)

            assertTrue((result.exceptionOrNull() as IronLogException).error is ApiError.Timeout)
            assertEquals(21_000L, testScheduler.currentTime)
        } finally {
            api.http.close()
            engine.close()
        }
    }

    private fun TestScope.delayedEngine(responseDelay: Long) = MockEngine(
        MockEngineConfig().apply {
            // Both the handler and HttpTimeout's timer must use virtual time.
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                val timeout = request.getCapabilityOrNull(HttpTimeoutCapability)!!
                if (request.url.encodedPath != "/generate") {
                    assertEquals(10_000L, timeout.requestTimeoutMillis)
                    assertEquals(10_000L, timeout.socketTimeoutMillis)
                    assertEquals(5_000L, timeout.connectTimeoutMillis)
                }
                delay(responseDelay)
                respond(
                    content = if (request.url.encodedPath == "/generate") {
                        """{"candidate_id":"candidate-1","day_role":"signal","exhausted":false,"attempts":1,"scope":"day"}"""
                    } else {
                        """["signal"]"""
                    },
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        },
    )
}
