// HistoryLogicTest.kt
package com.jauschua.ironlogv2.ui.history

import com.jauschua.ironlogv2.data.api.dto.LoggedSet
import com.jauschua.ironlogv2.ui.screens.history.groupLogsByMovement
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryLogicTest {
    private fun log(mid: Int, name: String, si: Int) =
        LoggedSet(mid, name, si, reps = 8, load = 165.0, tap = "ON_TARGET", is_warmup = false)

    @Test fun groups_by_movement_in_first_appearance_order() {
        val logs = listOf(log(4, "Bench", 0), log(7, "Pendlay", 0), log(4, "Bench", 1))
        val grouped = groupLogsByMovement(logs)
        assertEquals(listOf("Bench", "Pendlay"), grouped.map { it.movementName })
        assertEquals(2, grouped[0].sets.size)  // both Bench sets together
    }
}
