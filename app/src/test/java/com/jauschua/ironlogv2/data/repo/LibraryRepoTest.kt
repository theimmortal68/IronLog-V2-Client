package com.jauschua.ironlogv2.data.repo

import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.api.ApiError
import com.jauschua.ironlogv2.data.api.IronLogException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRepoTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!.bufferedReader().readText()

    private fun jsonEngine(body: String, status: HttpStatusCode = HttpStatusCode.OK): MockEngine = MockEngine {
        respond(content = ByteReadChannel(body), status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
    }

    @Test
    fun movements_parsesListSuccessfully() = runTest {
        val repo = LibraryRepo(ApiClient(baseUrl = "http://test", engine = jsonEngine(fixture("movements.json"))))
        val result = repo.movements()
        assertTrue(result.isSuccess)
        val list = result.getOrThrow()
        assertEquals(3, list.size)
        assertEquals("Back Squat [PB]", list[0].name)
    }

    @Test
    fun getMovement_parsesSingleSuccessfully() = runTest {
        val repo = LibraryRepo(ApiClient(baseUrl = "http://test", engine = jsonEngine(fixture("movement_back_squat.json"))))
        val result = repo.getMovement(1)
        assertTrue(result.isSuccess)
        assertEquals("Back Squat [PB]", result.getOrThrow().name)
    }

    @Test
    fun usableBands_parsesSuccessfully() = runTest {
        val repo = LibraryRepo(ApiClient(baseUrl = "http://test", engine = jsonEngine(fixture("bands_usable.json"))))
        val result = repo.usableBands()
        assertTrue(result.isSuccess)
        assertEquals(5, result.getOrThrow().size)
    }

    @Test
    fun movements_serverErrorMapsToServerApiError() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.InternalServerError) }
        val repo = LibraryRepo(ApiClient(baseUrl = "http://test", engine = engine))
        val result = repo.movements()
        assertTrue(result.isFailure)
        val err = (result.exceptionOrNull() as IronLogException).error
        assertTrue("expected Server error, got $err", err is ApiError.Server)
        assertEquals(500, (err as ApiError.Server).status)
    }

    @Test
    fun movements_malformedJsonMapsToParseError() = runTest {
        val engine = jsonEngine("not valid json{")
        val repo = LibraryRepo(ApiClient(baseUrl = "http://test", engine = engine))
        val result = repo.movements()
        assertTrue(result.isFailure)
        val err = (result.exceptionOrNull() as IronLogException).error
        assertTrue("expected Parse error, got $err", err is ApiError.Parse)
    }

    @Test
    fun getMovement_clientErrorMapsToClientApiError() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.NotFound) }
        val repo = LibraryRepo(ApiClient(baseUrl = "http://test", engine = engine))
        val result = repo.getMovement(999)
        assertTrue(result.isFailure)
        val err = (result.exceptionOrNull() as IronLogException).error
        assertNotNull(err)
        assertTrue("expected Client error, got $err", err is ApiError.Client)
        assertEquals(404, (err as ApiError.Client).status)
    }
}
