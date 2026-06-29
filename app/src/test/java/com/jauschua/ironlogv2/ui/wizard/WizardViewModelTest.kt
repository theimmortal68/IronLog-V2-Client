// WizardViewModelTest.kt
package com.jauschua.ironlogv2.ui.wizard

import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.repo.WizardRepo
import com.jauschua.ironlogv2.ui.UiState
import com.jauschua.ironlogv2.ui.screens.wizard.WizardUi
import com.jauschua.ironlogv2.ui.screens.wizard.WizardViewModel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MockEngine-backed [WizardRepo], like CaptureViewModelTest. The engine branches on path so a
 * single VM can exercise load → resolve → start across the three wizard endpoints.
 *
 * The completion gate is server-driven: the VM consumes needs_attention_count / ready_to_start
 * straight from the resolve response (compute_load_trust lives on the server). These tests prove
 * the VM adopts those values and never recomputes the gate locally.
 *
 * Main is an [UnconfinedTestDispatcher] so the VM's fire-and-forget viewModelScope.launch runs
 * eagerly to its first suspension; the ktor MockEngine call completes on a background dispatcher,
 * so each test awaits the relevant StateFlow ([await]) rather than advancing virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WizardViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Suspend until [flow] emits a value matching [predicate], failing fast on a 5s timeout. */
    private suspend fun <T> StateFlow<T>.await(predicate: (T) -> Boolean): T =
        withTimeout(5_000) { first(predicate) }

    /** STALE prefilled @135, UNKNOWN empty, plus one FRESH → 2 needing attention, not ready. */
    private val stateJson =
        """{"program_id":7,"program_name":"Hypertrophy A","needs_attention_count":2,""" +
            """"ready_to_start":false,"movements":[""" +
            """{"movement_id":11,"movement_name":"Bench Press","load_field":"current_load",""" +
            """"trust":"STALE","prefill_value":135.0,"unit_hint":"lb"},""" +
            """{"movement_id":22,"movement_name":"Assisted Dip","load_field":"assist_level",""" +
            """"trust":"UNKNOWN"},""" +
            """{"movement_id":33,"movement_name":"Squat","load_field":"current_load",""" +
            """"trust":"FRESH","prefill_value":225.0,"unit_hint":"lb"}]}"""

    /** After resolving, the server says 0 left and ready. */
    private val resolveReadyJson =
        """{"resolved":2,"needs_attention_count":0,"ready_to_start":true}"""

    private val startJson =
        """{"program_id":7,"started":true,"active":true}"""

    private fun repo(): WizardRepo {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val body = when {
                path.endsWith("/wizard-state") -> stateJson
                path.endsWith("/wizard-resolve") -> resolveReadyJson
                path.endsWith("/start") -> startJson
                else -> error("unexpected path: $path")
            }
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return WizardRepo(ApiClient(baseUrl = "http://test", engine = engine))
    }

    @Test
    fun load_splitsMovementsAndShowsServerCount() = runBlocking {
        val vm = WizardViewModel(repo())
        vm.load(programId = 7)

        val state = vm.state.await { it is UiState.Success }
        val ui = (state as UiState.Success<WizardUi>).data
        // STALE + UNKNOWN are action items; FRESH is summarized only.
        assertEquals(2, ui.actionMovements.size)
        assertEquals(1, ui.freshMovements.size)
        assertEquals(33, ui.freshMovements[0].movement_id)
        // Live "N left" comes from the server.
        assertEquals(2, vm.needsAttentionCount.value)
        assertFalse(vm.readyToStart.value)
        // Prefill: STALE prefilled, UNKNOWN empty.
        assertEquals("135.0", vm.entered.value[11])
        assertEquals("", vm.entered.value[22])
    }

    @Test
    fun fillingUnknownThenResolve_decrementsCountAndFlipsReadyFromServer() = runBlocking {
        val vm = WizardViewModel(repo())
        vm.load(programId = 7)
        vm.state.await { it is UiState.Success }
        assertEquals(2, vm.needsAttentionCount.value)
        assertFalse(vm.readyToStart.value)

        // User fills the UNKNOWN movement, then resolves the batch.
        vm.setEntered(movementId = 22, value = "1")
        vm.resolve()

        // Count + gate come from the resolve response, not client recomputation.
        assertTrue(vm.readyToStart.await { it })
        assertEquals(0, vm.needsAttentionCount.value)
        assertNull(vm.submitError.value)
    }

    @Test
    fun start_isNoOpUntilServerSaysReady() = runBlocking {
        val vm = WizardViewModel(repo())
        vm.load(programId = 7)
        vm.state.await { it is UiState.Success }

        // Not ready yet → start does nothing.
        assertFalse(vm.readyToStart.value)
        vm.start()
        assertNull(vm.startResult.value)

        // Resolve flips ready_to_start true (server-driven), then start activates.
        vm.setEntered(movementId = 22, value = "1")
        vm.resolve()
        assertTrue(vm.readyToStart.await { it })

        vm.start()
        val result = vm.startResult.await { it != null }!!
        assertTrue(result.started)
        assertTrue(result.active)
    }

    @Test
    fun resolve_nonNumericEntrySurfacesValidationError() = runBlocking {
        val vm = WizardViewModel(repo())
        vm.load(programId = 7)
        vm.state.await { it is UiState.Success }

        vm.setEntered(movementId = 22, value = "abc")
        vm.resolve()

        // Validation is synchronous — no server round-trip happened, gate untouched.
        assertNotNull(vm.submitError.value)
        assertEquals(2, vm.needsAttentionCount.value)
        assertFalse(vm.readyToStart.value)
    }
}
