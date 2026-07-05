package com.jauschua.ironlogv2.data.dto

import com.jauschua.ironlogv2.data.api.dto.OverrideOut
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `GET /overrides` returns a bare JSON array of [OverrideOut]; nullable string fields decode
 *  to null when the server can't resolve a slot/tier/movement name (e.g. a stale reference). */
class NotesDtoTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun override_out_decodes_from_bare_array() {
        val body = """
            [{"id":3,"day_role":"D1 Upper Push","tier_label":"T1","slot_id":"bench",
              "from_movement_name":"Bench","to_movement_name":"Incline Bench","source_note_id":12}]
        """.trimIndent()
        val overrides = json.decodeFromString<List<OverrideOut>>(body)
        assertEquals(1, overrides.size)
        val o = overrides[0]
        assertEquals(3, o.id)
        assertEquals("D1 Upper Push", o.day_role)
        assertEquals("T1", o.tier_label)
        assertEquals("bench", o.slot_id)
        assertEquals("Bench", o.from_movement_name)
        assertEquals("Incline Bench", o.to_movement_name)
        assertEquals(12, o.source_note_id)
    }

    @Test fun override_out_nullable_fields_default_to_null_when_missing() {
        val body = """[{"id":4}]"""
        val overrides = json.decodeFromString<List<OverrideOut>>(body)
        assertEquals(1, overrides.size)
        val o = overrides[0]
        assertEquals(4, o.id)
        assertNull(o.day_role)
        assertNull(o.tier_label)
        assertNull(o.slot_id)
        assertNull(o.from_movement_name)
        assertNull(o.to_movement_name)
        assertNull(o.source_note_id)
    }

    @Test fun override_out_empty_list_decodes() {
        val overrides = json.decodeFromString<List<OverrideOut>>("[]")
        assertTrue(overrides.isEmpty())
    }
}
