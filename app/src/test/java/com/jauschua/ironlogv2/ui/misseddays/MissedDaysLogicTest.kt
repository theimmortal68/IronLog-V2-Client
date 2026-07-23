package com.jauschua.ironlogv2.ui.misseddays

import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.api.dto.MissedDayRecordOut
import com.jauschua.ironlogv2.data.repo.MissedDaysRepo
import com.jauschua.ironlogv2.ui.UiState
import com.jauschua.ironlogv2.ui.screens.misseddays.MissedDaysViewModel
import com.jauschua.ironlogv2.ui.screens.misseddays.replaceMissedDayRecord
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MissedDaysLogicTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun <T> StateFlow<T>.await(predicate: (T) -> Boolean): T =
        withTimeout(5_000) { first(predicate) }

    @Test fun replace_record_updates_matching_record_only() {
        val original = listOf(
            missedDayRecord(id = 1, status = "PENDING"),
            missedDayRecord(id = 2, status = "PENDING"),
        )
        val updated = missedDayRecord(id = 1, status = "ACKNOWLEDGED")

        assertEquals(
            listOf(updated, original[1]),
            replaceMissedDayRecord(original, updated),
        )
    }

    @Test fun acknowledge_replaces_updated_record_without_refetching_list() = runBlocking {
        val api = RecordingMissedDaysApi(
            listBody = """
                [
                  {
                    "id":1,
                    "program_day_id":11,
                    "day_role":"D1 Upper",
                    "week_start_date":"2026-07-20",
                    "detected_at":"2026-07-23T12:00:00",
                    "status":"PENDING"
                  },
                  {
                    "id":2,
                    "program_day_id":12,
                    "day_role":"D2 Lower",
                    "week_start_date":"2026-07-20",
                    "detected_at":"2026-07-23T12:00:00",
                    "status":"PENDING"
                  }
                ]
            """.trimIndent(),
            acknowledgeBody = """
                {
                  "id":1,
                  "program_day_id":11,
                  "day_role":"D1 Upper",
                  "week_start_date":"2026-07-20",
                  "detected_at":"2026-07-23T12:00:00",
                  "status":"ACKNOWLEDGED"
                }
            """.trimIndent(),
        )
        val vm = MissedDaysViewModel(MissedDaysRepo(ApiClient(baseUrl = "http://test", engine = api.engine)))

        vm.state.await { it is UiState.Success }
        vm.acknowledge(1)
        val state = vm.state.await {
            it is UiState.Success && it.data.first().status == "ACKNOWLEDGED"
        } as UiState.Success<List<MissedDayRecordOut>>

        assertEquals(listOf("ACKNOWLEDGED", "PENDING"), state.data.map { it.status })
        assertEquals(
            listOf(HttpMethod.Get to "/missed-days", HttpMethod.Post to "/missed-days/1/acknowledge"),
            api.calls,
        )
    }

    @Test fun reschedule_failure_surfaces_error_state() = runBlocking {
        val api = RecordingMissedDaysApi(
            listBody = """
                [
                  {
                    "id":1,
                    "program_day_id":11,
                    "day_role":"D1 Upper",
                    "week_start_date":"2026-07-20",
                    "detected_at":"2026-07-23T12:00:00",
                    "status":"PENDING"
                  }
                ]
            """.trimIndent(),
            rescheduleStatus = HttpStatusCode.InternalServerError,
            rescheduleBody = """{"detail":"boom"}""",
        )
        val vm = MissedDaysViewModel(MissedDaysRepo(ApiClient(baseUrl = "http://test", engine = api.engine)))

        vm.state.await { it is UiState.Success }
        vm.reschedule(1)
        val state = vm.state.await { it is UiState.Error } as UiState.Error

        assertEquals("Server error (500)", state.msg)
    }

    private fun missedDayRecord(id: Int, status: String) = MissedDayRecordOut(
        id = id,
        program_day_id = id + 10,
        day_role = "D$id",
        week_start_date = "2026-07-20",
        detected_at = "2026-07-23T12:00:00",
        status = status,
    )

    private class RecordingMissedDaysApi(
        private val listBody: String,
        private val acknowledgeBody: String = "{}",
        private val acknowledgeStatus: HttpStatusCode = HttpStatusCode.OK,
        private val rescheduleBody: String = "{}",
        private val rescheduleStatus: HttpStatusCode = HttpStatusCode.OK,
    ) {
        val calls = mutableListOf<Pair<HttpMethod, String>>()

        val engine = MockEngine { request ->
            calls += request.method to request.url.encodedPath

            when (request.url.encodedPath) {
                "/missed-days" -> json(listBody)
                "/missed-days/1/acknowledge" -> json(acknowledgeBody, acknowledgeStatus)
                "/missed-days/1/reschedule" -> json(rescheduleBody, rescheduleStatus)
                else -> error("unexpected path: ${request.url.encodedPath}")
            }
        }

        private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(
            content = ByteReadChannel(body),
            status = status,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }
}
