package com.jauschua.ironlogv2.data.dto

import com.jauschua.ironlogv2.data.api.dto.OverrideOut
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `GET /overrides` returns a bare JSON array of generalized [OverrideOut] (MOVEMENT/LOAD/REPS);
 *  nullable fields decode to null when the server can't resolve a slot/movement name, or when the
 *  override type doesn't populate that field. */
class NotesDtoTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun override_out_decodes_movement_type_from_bare_array() {
        val body = """
            [{"id":3,"override_type":"MOVEMENT","day_role":"D1 Upper Push","tier_label":"T1",
              "slot_id":"bench","movement_name":"Bench","to_movement_name":"Incline Bench",
              "source_note_id":12,"source_note_text":"switch to incline"}]
        """.trimIndent()
        val overrides = json.decodeFromString<List<OverrideOut>>(body)
        assertEquals(1, overrides.size)
        val o = overrides[0]
        assertEquals(3, o.id)
        assertEquals("MOVEMENT", o.override_type)
        assertEquals("D1 Upper Push", o.day_role)
        assertEquals("T1", o.tier_label)
        assertEquals("bench", o.slot_id)
        assertEquals("Bench", o.movement_name)
        assertEquals("Incline Bench", o.to_movement_name)
        assertEquals(12, o.source_note_id)
        assertEquals("switch to incline", o.source_note_text)
    }

    @Test fun override_out_decodes_load_type_with_delta_or_absolute() {
        val delta = json.decodeFromString<List<OverrideOut>>(
            """[{"id":5,"override_type":"LOAD","movement_name":"Hip Thrust","load_delta":10.0}]"""
        )[0]
        assertEquals(10.0, delta.load_delta)
        assertNull(delta.load_absolute)

        val absolute = json.decodeFromString<List<OverrideOut>>(
            """[{"id":6,"override_type":"LOAD","movement_name":"Hip Thrust","load_absolute":225.0}]"""
        )[0]
        assertEquals(225.0, absolute.load_absolute)
        assertNull(absolute.load_delta)
    }

    @Test fun override_out_decodes_reps_type() {
        val o = json.decodeFromString<List<OverrideOut>>(
            """[{"id":7,"override_type":"REPS","movement_name":"Squat","rep_low":5,"rep_high":8}]"""
        )[0]
        assertEquals(5, o.rep_low)
        assertEquals(8, o.rep_high)
    }

    @Test fun override_out_nullable_fields_default_to_null_when_missing() {
        val body = """[{"id":4,"override_type":"MOVEMENT"}]"""
        val overrides = json.decodeFromString<List<OverrideOut>>(body)
        assertEquals(1, overrides.size)
        val o = overrides[0]
        assertEquals(4, o.id)
        assertNull(o.day_role)
        assertNull(o.tier_label)
        assertNull(o.slot_id)
        assertNull(o.movement_name)
        assertNull(o.to_movement_name)
        assertNull(o.load_delta)
        assertNull(o.load_absolute)
        assertNull(o.rep_low)
        assertNull(o.rep_high)
        assertNull(o.source_note_id)
        assertNull(o.source_note_text)
    }

    @Test fun override_out_empty_list_decodes() {
        val overrides = json.decodeFromString<List<OverrideOut>>("[]")
        assertTrue(overrides.isEmpty())
    }
}
