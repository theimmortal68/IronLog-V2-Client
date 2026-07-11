package com.jauschua.ironlogv2.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestTimerServiceLogicTest {

    @Test
    fun normalizedRestDurationSeconds_clamps_negative_values_to_zero() {
        assertEquals(0, normalizedRestDurationSeconds(-10))
        assertEquals(0, normalizedRestDurationSeconds(0))
        assertEquals(90, normalizedRestDurationSeconds(90))
    }

    @Test
    fun restTimerNotificationContent_formats_remaining_time() {
        val content = restTimerNotificationContent(125)

        assertEquals("Rest timer", content.title)
        assertEquals("2:05 remaining", content.text)
        assertTrue(content.ongoing)
    }

    @Test
    fun restTimerToneForTransition_maps_warning_ticks_and_done() {
        assertEquals(RestTimerTone.WARNING, restTimerToneForTransition(previous = 16, current = 15))
        assertEquals(RestTimerTone.TICK, restTimerToneForTransition(previous = 4, current = 3))
        assertEquals(RestTimerTone.TICK, restTimerToneForTransition(previous = 3, current = 2))
        assertEquals(RestTimerTone.TICK, restTimerToneForTransition(previous = 2, current = 1))
        assertEquals(RestTimerTone.DONE, restTimerToneForTransition(previous = 1, current = null))
        assertNull(restTimerToneForTransition(previous = 10, current = null))
        assertNull(restTimerToneForTransition(previous = 30, current = 29))
    }

    @Test
    fun shouldRefreshRestNotification_limits_updates_until_final_countdown() {
        assertTrue(shouldRefreshRestNotification(previous = null, current = 120))
        assertTrue(shouldRefreshRestNotification(previous = 120, current = 115))
        assertFalse(shouldRefreshRestNotification(previous = 120, current = 119))
        assertTrue(shouldRefreshRestNotification(previous = 16, current = 15))
        assertTrue(shouldRefreshRestNotification(previous = 1, current = null))
    }
}
