// RestAudio.kt
package com.jauschua.ironlogv2.ui.screens.capture

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Alarm-stream tone cues for the rest countdown, so the warning + final countdown are loud and
 * fire even when the phone is on silent/vibrate (STREAM_ALARM ignores the ringer mode).
 *
 * Three audibly distinct cues, driven off the countdown value in [SessionContent]:
 * - [warning]: a longer, prominent alert at the 10s mark.
 * - [tick]: a short beep on each of the final 3s (distinct — shorter than the warning).
 * - [done]: a double beep when the rest completes, signalling "start".
 *
 * Owns a single [ToneGenerator] at max volume; the caller must [release] it (a `DisposableEffect`
 * `onDispose`) when the screen leaves composition. Construction and playback are guarded — a
 * failed [ToneGenerator] (resource contention on some devices) degrades to silence rather than
 * crashing the capture screen.
 */
internal class RestToneCue {
    private val tone: ToneGenerator? =
        runCatching { ToneGenerator(AudioManager.STREAM_ALARM, MAX_VOLUME) }.getOrNull()

    /** 10-second mark — loud, distinct, longer warning tone. */
    fun warning() {
        runCatching { tone?.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, WARNING_MS) }
    }

    /** Final 3-2-1 countdown — a short beep each, distinct from (shorter than) [warning]. */
    fun tick() {
        runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, TICK_MS) }
    }

    /** Rest over — a distinct double-beep end tone so the lifter knows to start. */
    fun done() {
        runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, DONE_MS) }
    }

    fun release() {
        runCatching { tone?.release() }
    }

    private companion object {
        const val MAX_VOLUME = 100
        const val WARNING_MS = 450
        const val TICK_MS = 150
        const val DONE_MS = 300
    }
}

/**
 * Unwraps the hosting [Activity] from a Compose [Context] (which may be a [ContextWrapper] chain)
 * so window flags like `FLAG_KEEP_SCREEN_ON` can be toggled while a rest is running. Returns null
 * if no Activity is in the chain (e.g. a preview/test context).
 */
internal fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
