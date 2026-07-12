package com.jauschua.ironlogv2.ui.review

import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.api.dto.NoteReviewOut
import com.jauschua.ironlogv2.data.api.dto.ProposalOut
import com.jauschua.ironlogv2.data.repo.LibraryRepo
import com.jauschua.ironlogv2.data.repo.NotesRepo
import com.jauschua.ironlogv2.ui.Routes
import com.jauschua.ironlogv2.ui.screens.review.ReviewViewModel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelTest {

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

    @Test
    fun submitLoad_withInvalidSelectedProposal_doesNotApplyOverride() = runBlocking {
        val api = RecordingReviewApi()
        val vm = reviewViewModel(api)

        vm.openApply(noteWithProposal(valid = false))
        vm.submitLoad(delta = 5.0, absolute = null)

        assertFalse("invalid proposals must not enter submitting state", vm.wizard.value?.submitting ?: true)
        assertEquals("invalid proposals must not call the apply endpoint", 0, api.applyCalls.get())
    }

    @Test
    fun submitLoad_withValidSelectedProposal_appliesOverride() = runBlocking {
        val api = RecordingReviewApi()
        val vm = reviewViewModel(api)

        vm.openApply(noteWithProposal(valid = true))
        vm.submitLoad(delta = 5.0, absolute = null)

        withTimeout(5_000) { api.applyCalled.await() }
        assertEquals(1, api.applyCalls.get())
        assertNull(vm.wizard.await { it == null })
    }

    private fun reviewViewModel(api: RecordingReviewApi): ReviewViewModel {
        val client = ApiClient(baseUrl = "http://test", engine = api.engine)
        return ReviewViewModel(
            notesRepo = NotesRepo(client),
            libraryRepo = LibraryRepo(client),
        )
    }

    private fun noteWithProposal(valid: Boolean) = NoteReviewOut(
        id = 1,
        session_id = 7,
        movement_id = 10,
        created_at = "2026-07-04T12:00:00",
        text = "increase bench by 5 lb",
        classification = "CONFIG_CHANGE",
        action_type = "LOAD_INCREASE",
        resolved_proposals = listOf(proposal(valid)),
    )

    private fun proposal(valid: Boolean) = ProposalOut(
        tier_exercise_id = 12,
        day_role = "D1 Upper Push",
        slot_label = "T1",
        override_type = "LOAD",
        load_delta = 5.0,
        valid = valid,
    )

    private class RecordingReviewApi {
        val applyCalls = AtomicInteger(0)
        val applyCalled = CompletableDeferred<Unit>()

        val engine = MockEngine { request ->
            fun json(body: String) = respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )

            when (request.url.encodedPath) {
                "/notes/review",
                "/overrides",
                "/programs/${Routes.DEFAULT_PROGRAM_ID}/slots",
                -> json("[]")

                "/notes/1/apply" -> {
                    applyCalls.incrementAndGet()
                    applyCalled.complete(Unit)
                    respond(
                        content = ByteReadChannel(""),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

                else -> error("unexpected path: ${request.url.encodedPath}")
            }
        }
    }
}
