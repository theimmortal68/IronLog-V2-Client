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
    fun restTimerNotificationContent_uses_static_text() {
        val content = restTimerNotificationContent(125)

        assertEquals("Rest timer", content.title)
        assertEquals("Tap to return to your workout", content.text)
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
    fun shouldRefreshRestNotification_uses_per_second_cadence_before_final_seconds() {
        assertTrue(
            shouldRefreshRestNotification(previous = null, current = 120, lastNotificationRemaining = null),
        )
        assertTrue(
            shouldRefreshRestNotification(previous = 120, current = 119, lastNotificationRemaining = 120),
        )
        assertTrue(
            shouldRefreshRestNotification(previous = 119, current = 118, lastNotificationRemaining = 120),
        )
        assertTrue(
            shouldRefreshRestNotification(previous = 118, current = 117, lastNotificationRemaining = 120),
        )
        assertTrue(
            shouldRefreshRestNotification(previous = 90, current = 120, lastNotificationRemaining = 90),
        )
        assertTrue(
            shouldRefreshRestNotification(previous = 16, current = 15, lastNotificationRemaining = 18),
        )
        assertTrue(
            shouldRefreshRestNotification(previous = 15, current = 14, lastNotificationRemaining = 15),
        )
        assertFalse(
            shouldRefreshRestNotification(previous = 15, current = 15, lastNotificationRemaining = 15),
        )
        assertTrue(
            shouldRefreshRestNotification(previous = 1, current = null, lastNotificationRemaining = 1),
        )
    }

    @Test
    fun restTimerCountdownIconText_uses_legible_seconds_for_active_rests() {
        assertNull(restTimerCountdownIconText(-1))
        assertNull(restTimerCountdownIconText(0))
        assertEquals("1", restTimerCountdownIconText(1))
        assertEquals("93", restTimerCountdownIconText(93))
        assertEquals("180", restTimerCountdownIconText(180))
        assertEquals("999", restTimerCountdownIconText(999))
    }

    @Test
    fun restTimerCountdownIconText_switches_to_minutes_for_four_digit_seconds() {
        assertEquals("17", restTimerCountdownIconText(1_000))
        assertEquals("18", restTimerCountdownIconText(1_021))
    }
}
