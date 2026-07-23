package com.jauschua.ironlogv2.ui.settings

import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.api.dto.GoalSettingsIn
import com.jauschua.ironlogv2.data.repo.GoalsRepo
import com.jauschua.ironlogv2.ui.screens.settings.buildGoalSettingsUpdate
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsLogicTest {

    @Test
    fun buildGoalSettingsUpdate_allFieldsSet_returnsUpdateRequest() {
        assertEquals(
            GoalSettingsIn(
                target_bodyweight = 215.5,
                target_bodyweight_tolerance = 2.5,
                target_body_fat_pct = 14.0,
                target_body_fat_pct_tolerance = 1.25,
            ),
            buildGoalSettingsUpdate(
                targetBodyweight = "215.5",
                targetBodyweightTolerance = "2.5",
                targetBodyFatPct = "14",
                targetBodyFatPctTolerance = "1.25",
            ),
        )
    }

    @Test
    fun buildGoalSettingsUpdate_trimsInputs() {
        assertEquals(
            GoalSettingsIn(
                target_bodyweight = 215.0,
                target_bodyweight_tolerance = 2.0,
                target_body_fat_pct = 14.5,
                target_body_fat_pct_tolerance = 1.0,
            ),
            buildGoalSettingsUpdate(
                targetBodyweight = " 215 ",
                targetBodyweightTolerance = " 2 ",
                targetBodyFatPct = " 14.5 ",
                targetBodyFatPctTolerance = " 1 ",
            ),
        )
    }

    @Test
    fun buildGoalSettingsUpdate_blankOptionalFields_mapToNull() {
        assertEquals(
            GoalSettingsIn(
                target_bodyweight = 215.0,
                target_bodyweight_tolerance = 2.0,
                target_body_fat_pct = null,
                target_body_fat_pct_tolerance = null,
            ),
            buildGoalSettingsUpdate(
                targetBodyweight = "215",
                targetBodyweightTolerance = "2",
                targetBodyFatPct = "",
                targetBodyFatPctTolerance = " ",
            ),
        )
    }

    @Test
    fun buildGoalSettingsUpdate_allBlankFields_mapToNulls() {
        assertEquals(
            GoalSettingsIn(
                target_bodyweight = null,
                target_bodyweight_tolerance = null,
                target_body_fat_pct = null,
                target_body_fat_pct_tolerance = null,
            ),
            buildGoalSettingsUpdate(
                targetBodyweight = "",
                targetBodyweightTolerance = " ",
                targetBodyFatPct = "",
                targetBodyFatPctTolerance = " ",
            ),
        )
    }

    @Test
    fun buildGoalSettingsUpdate_nonNumericInput_returnsNull() {
        assertNull(
            buildGoalSettingsUpdate(
                targetBodyweight = "two fifteen",
                targetBodyweightTolerance = "2",
                targetBodyFatPct = "14",
                targetBodyFatPctTolerance = "1",
            ),
        )
        assertNull(
            buildGoalSettingsUpdate(
                targetBodyweight = "215",
                targetBodyweightTolerance = "wide",
                targetBodyFatPct = "14",
                targetBodyFatPctTolerance = "1",
            ),
        )
        assertNull(
            buildGoalSettingsUpdate(
                targetBodyweight = "215",
                targetBodyweightTolerance = "2",
                targetBodyFatPct = "lean",
                targetBodyFatPctTolerance = "1",
            ),
        )
        assertNull(
            buildGoalSettingsUpdate(
                targetBodyweight = "215",
                targetBodyweightTolerance = "2",
                targetBodyFatPct = "14",
                targetBodyFatPctTolerance = "tight",
            ),
        )
    }

    @Test
    fun goalsRepoGet_bareJsonNull_returnsNullGoal() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/goals", request.url.encodedPath)
            respond(
                content = ByteReadChannel("null"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repo = GoalsRepo(ApiClient(baseUrl = "http://test", engine = engine))

        val result = repo.get()

        assertNull(result.getOrThrow())
    }
}
