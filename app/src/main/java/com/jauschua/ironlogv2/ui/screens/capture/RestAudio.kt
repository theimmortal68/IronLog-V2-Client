// RestAudio.kt
package com.jauschua.ironlogv2.ui.screens.capture

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.sin

/**
 * Alarm-stream tone cues for the rest countdown, so the warning + final countdown are loud and
 * fire even when the phone is on silent/vibrate.
 *
 * The tones are self-generated full-amplitude sine waves played through an [AudioTrack] on the
 * alarm stream ([AudioAttributes.USAGE_ALARM] + [AudioAttributes.CONTENT_TYPE_SONIFICATION]).
 * This replaces [android.media.ToneGenerator], whose built-in tones capped the loudness even at
 * max volume: a ~0.9×[Short.MAX_VALUE] sine is both louder and gives exact pitch control (higher
 * pitches cut through gym noise and read as louder). Each tone gets a ~5 ms linear fade in/out to
 * avoid click/pop artifacts.
 *
 * Three audibly distinct cues, driven off the countdown value in [SessionContent]:
 * - [warning]: a 900 Hz TRIPLE beep (three 150 ms beeps, 80 ms gaps) at the 15s mark — the
 *   triple pattern (not pitch) is what makes it distinct from the single-beep ticks.
 * - [tick]: 1000 Hz, 150 ms — a short beep on each of the final 3s.
 * - [done]: a HIGHER-pitched 1550 Hz double beep (two 120 ms beeps, 80 ms gap) when the rest
 *   completes, signalling "start" — distinctly higher than [warning]/[tick] per user feedback.
 *
 * Construction and playback are guarded — a failed [AudioTrack] (resource contention on some
 * devices) degrades to silence rather than crashing the capture screen. The caller must [release]
 * the track (a `DisposableEffect` `onDispose`) when the screen leaves composition.
 */
class RestToneCue {
    /** The most recently played track, held so [release] (and the next cue) can free it. */
    private var currentTrack: AudioTrack? = null

    /** 15-second mark — a loud, prominent 900 Hz triple beep (distinct from the single ticks). */
    fun warning() {
        play(beeps(WARNING_HZ, WARNING_BEEP_MS, WARNING_GAP_MS, WARNING_BEEP_COUNT))
    }

    /** Final 3-2-1 countdown — a short 1000 Hz beep each, distinct from (shorter than) [warning]. */
    fun tick() {
        play(sine(TICK_HZ, TICK_MS))
    }

    /** Rest over — a HIGHER-pitched 1550 Hz double beep so the lifter knows to start. */
    fun done() {
        play(beeps(DONE_HZ, DONE_BEEP_MS, DONE_GAP_MS, DONE_BEEP_COUNT))
    }

    /** Stop and free any held [AudioTrack]. Safe to call repeatedly (e.g. from `onDispose`). */
    fun release() {
        releaseTrack()
    }

    /** Builds a MODE_STATIC alarm-stream track from [samples], writes once, and plays it. */
    private fun play(samples: ShortArray) {
        runCatching {
            releaseTrack()
            val sizeBytes = samples.size * 2
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val track = AudioTrack(
                attributes,
                format,
                sizeBytes,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
            track.write(samples, 0, samples.size)
            track.play()
            currentTrack = track
        }
    }

    private fun releaseTrack() {
        currentTrack?.let { track ->
            runCatching { track.stop() }
            runCatching { track.release() }
        }
        currentTrack = null
    }

    /**
     * A single mono PCM-16 sine tone at [freqHz] for [durationMs], at ~0.9 full-scale amplitude
     * with a [FADE_MS] linear fade in/out on both ends.
     */
    private fun sine(freqHz: Double, durationMs: Int): ShortArray {
        val count = (SAMPLE_RATE.toLong() * durationMs / 1000L).toInt()
        val fade = (SAMPLE_RATE.toLong() * FADE_MS / 1000L).toInt().coerceIn(1, count / 2 + 1)
        val amplitude = 0.9 * Short.MAX_VALUE
        val out = ShortArray(count)
        for (i in 0 until count) {
            val envelope = when {
                i < fade -> i.toDouble() / fade
                i >= count - fade -> (count - i).toDouble() / fade
                else -> 1.0
            }
            val value = amplitude * envelope * sin(2.0 * Math.PI * freqHz * i / SAMPLE_RATE)
            out[i] = value.toInt().toShort()
        }
        return out
    }

    /**
     * [count] [sine] beeps at [freqHz] of [beepMs] each, separated by [gapMs] of silence, as one
     * contiguous waveform — so the whole multi-beep plays from a single MODE_STATIC write with no
     * main-thread sleep between beeps. Used for both the triple warning and the double done cue.
     */
    private fun beeps(freqHz: Double, beepMs: Int, gapMs: Int, count: Int): ShortArray {
        val beep = sine(freqHz, beepMs)
        val gap = (SAMPLE_RATE.toLong() * gapMs / 1000L).toInt()
        val out = ShortArray(beep.size * count + gap * (count - 1))
        for (n in 0 until count) {
            beep.copyInto(out, n * (beep.size + gap))
        }
        return out
    }

    private companion object {
        const val SAMPLE_RATE = 44100
        const val FADE_MS = 5
        const val WARNING_HZ = 900.0
        const val WARNING_BEEP_MS = 150
        const val WARNING_GAP_MS = 80
        const val WARNING_BEEP_COUNT = 3
        const val TICK_HZ = 1000.0
        const val TICK_MS = 150
        const val DONE_HZ = 1550.0
        const val DONE_BEEP_MS = 120
        const val DONE_GAP_MS = 80
        const val DONE_BEEP_COUNT = 2
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
