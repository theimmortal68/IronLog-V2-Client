package com.jauschua.ironlogv2.data.dto

import com.jauschua.ironlogv2.data.api.dto.LoggedSetsResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryDtoBackCompatTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun old_response_without_new_fields_deserializes_with_defaults() {
        // A pre-upgrade cached body: no surveys/notes, no rpe_numeric/felt_peak.
        val body = """
            {"session_id":7,"date":"2026-07-01","day_role":"D1 Upper Push",
             "logs":[{"movement_id":1,"movement_name":"Bench","set_index":0,
                      "reps":8,"load":165.0,"tap":"ON_TARGET","is_warmup":false}]}
        """.trimIndent()
        val resp = json.decodeFromString<LoggedSetsResponse>(body)
        assertTrue(resp.surveys.isEmpty())
        assertTrue(resp.notes.isEmpty())
        assertNull(resp.logs[0].rpe_numeric)
        assertNull(resp.logs[0].felt_peak)
        assertEquals(165.0, resp.logs[0].load!!, 0.001)
    }
}
