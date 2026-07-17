package com.jauschua.ironlogv2.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import com.jauschua.ironlogv2.R
import com.jauschua.ironlogv2.ui.MainActivity
import com.jauschua.ironlogv2.ui.screens.capture.RestToneCue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val COUNTDOWN_LEAD_IN_LABEL = "Get Ready"

interface IntervalTimerController {
    val remainingSeconds: StateFlow<Int?>
    val phaseLabel: StateFlow<String?>
    fun startCountdown(seconds: Int, label: String, leadInSeconds: Int = 0)
    fun startRepBasedIntervals(totalMinutes: Int, label: String, leadInSeconds: Int = 0)
    fun startTimeBasedIntervals(
        totalMinutes: Int,
        workSeconds: Int,
        label: String,
        leadInSeconds: Int = 0,
    )
    fun stop()
}

/**
 * Test/default controller for plain JVM ViewModel tests. Production factories pass
 * [AndroidIntervalTimerController], which routes commands through [IntervalTimerService].
 */
class InMemoryIntervalTimerController : IntervalTimerController {
    private val _remainingSeconds = MutableStateFlow<Int?>(null)
    override val remainingSeconds: StateFlow<Int?> = _remainingSeconds.asStateFlow()

    private val _phaseLabel = MutableStateFlow<String?>(null)
    override val phaseLabel: StateFlow<String?> = _phaseLabel.asStateFlow()

    override fun startCountdown(seconds: Int, label: String, leadInSeconds: Int) {
        val duration = normalizedRestDurationSeconds(seconds)
        if (duration > 0) {
            val leadInDuration = leadInSeconds.coerceAtLeast(0)
            if (leadInDuration > 0) {
                _remainingSeconds.value = leadInDuration
                _phaseLabel.value = COUNTDOWN_LEAD_IN_LABEL
            } else {
                _remainingSeconds.value = duration
                _phaseLabel.value = label
            }
        } else {
            stop()
        }
    }

    override fun startRepBasedIntervals(totalMinutes: Int, label: String, leadInSeconds: Int) {
        if (totalMinutes > 0) {
            val leadInDuration = leadInSeconds.coerceAtLeast(0)
            if (leadInDuration > 0) {
                _remainingSeconds.value = leadInDuration
                _phaseLabel.value = COUNTDOWN_LEAD_IN_LABEL
            } else {
                _remainingSeconds.value = 60
                _phaseLabel.value = intervalTimerRepBasedLabel(1, totalMinutes)
            }
        } else {
            stop()
        }
    }

    override fun startTimeBasedIntervals(
        totalMinutes: Int,
        workSeconds: Int,
        label: String,
        leadInSeconds: Int,
    ) {
        if (totalMinutes > 0) {
            val leadInDuration = leadInSeconds.coerceAtLeast(0)
            if (leadInDuration > 0) {
                _remainingSeconds.value = leadInDuration
                _phaseLabel.value = COUNTDOWN_LEAD_IN_LABEL
            } else {
                _remainingSeconds.value = clampedIntervalWorkSeconds(workSeconds)
                _phaseLabel.value = "Work"
            }
        } else {
            stop()
        }
    }

    override fun stop() {
        _remainingSeconds.value = null
        _phaseLabel.value = null
    }
}

class AndroidIntervalTimerController(context: Context) : IntervalTimerController {
    private val appContext = context.applicationContext

    override val remainingSeconds: StateFlow<Int?> = IntervalTimerService.remainingSeconds
    override val phaseLabel: StateFlow<String?> = IntervalTimerService.phaseLabel

    override fun startCountdown(seconds: Int, label: String, leadInSeconds: Int) {
        IntervalTimerService.startCountdown(appContext, seconds, label, leadInSeconds)
    }

    override fun startRepBasedIntervals(totalMinutes: Int, label: String, leadInSeconds: Int) {
        IntervalTimerService.startRepBasedIntervals(appContext, totalMinutes, label, leadInSeconds)
    }

    override fun startTimeBasedIntervals(
        totalMinutes: Int,
        workSeconds: Int,
        label: String,
        leadInSeconds: Int,
    ) {
        IntervalTimerService.startTimeBasedIntervals(
            appContext,
            totalMinutes,
            workSeconds,
            label,
            leadInSeconds,
        )
    }

    override fun stop() {
        IntervalTimerService.stop(appContext)
    }
}

internal sealed interface IntervalTimerState {
    data class Countdown(val seconds: Int, val label: String, val leadInSeconds: Int = 0) : IntervalTimerState
    data class RepBased(val totalMinutes: Int, val label: String, val leadInSeconds: Int = 0) : IntervalTimerState
    data class TimeBased(
        val totalMinutes: Int,
        val workSeconds: Int,
        val label: String,
        val leadInSeconds: Int = 0,
    ) : IntervalTimerState
}

internal data class IntervalTickResult(
    val remainingSeconds: Int?,
    val phaseLabel: String?,
    val tone: RestTimerTone?,
    val isFinished: Boolean,
)

internal fun clampedIntervalWorkSeconds(workSeconds: Int): Int = workSeconds.coerceIn(1, 59)

internal fun intervalTimerRepBasedLabel(round: Int, totalMinutes: Int): String =
    "Minute $round of $totalMinutes"

internal fun intervalTimerToneForTransition(current: Int?): RestTimerTone? = when (current) {
    15 -> RestTimerTone.WARNING
    3, 2, 1 -> RestTimerTone.TICK
    else -> null
}

internal class IntervalTimerSequence(private val state: IntervalTimerState) {
    private var currentRound: Int = 1
    private var isWorkPhase: Boolean = true
    private var isLeadInPhase: Boolean = false
    private var remainingInPhase: Int = 0
    private var phaseLabel: String? = null

    init {
        when (state) {
            is IntervalTimerState.Countdown -> {
                val duration = normalizedRestDurationSeconds(state.seconds)
                val leadInDuration = state.leadInSeconds.coerceAtLeast(0)
                if (duration > 0 && leadInDuration > 0) {
                    isLeadInPhase = true
                    remainingInPhase = leadInDuration
                    phaseLabel = COUNTDOWN_LEAD_IN_LABEL
                } else {
                    remainingInPhase = duration
                    phaseLabel = state.label
                }
            }
            is IntervalTimerState.RepBased -> {
                val leadInDuration = state.leadInSeconds.coerceAtLeast(0)
                if (state.totalMinutes <= 0) {
                    remainingInPhase = 0
                    phaseLabel = null
                } else if (leadInDuration > 0) {
                    isLeadInPhase = true
                    remainingInPhase = leadInDuration
                    phaseLabel = COUNTDOWN_LEAD_IN_LABEL
                } else {
                    currentRound = 1
                    remainingInPhase = 60
                    phaseLabel = intervalTimerRepBasedLabel(currentRound, state.totalMinutes)
                }
            }
            is IntervalTimerState.TimeBased -> {
                val leadInDuration = state.leadInSeconds.coerceAtLeast(0)
                if (state.totalMinutes <= 0) {
                    remainingInPhase = 0
                    phaseLabel = null
                } else if (leadInDuration > 0) {
                    isLeadInPhase = true
                    remainingInPhase = leadInDuration
                    phaseLabel = COUNTDOWN_LEAD_IN_LABEL
                } else {
                    currentRound = 1
                    isWorkPhase = true
                    remainingInPhase = clampedIntervalWorkSeconds(state.workSeconds)
                    phaseLabel = "Work"
                }
            }
        }
    }

    fun initialResult(): IntervalTickResult {
        if (remainingInPhase <= 0) {
            return IntervalTickResult(
                remainingSeconds = null,
                phaseLabel = null,
                tone = null,
                isFinished = true,
            )
        }
        val isStartTone = if (isLeadInPhase) {
            false
        } else {
            when (state) {
                is IntervalTimerState.Countdown -> false
                is IntervalTimerState.RepBased -> true
                is IntervalTimerState.TimeBased -> true
            }
        }
        return IntervalTickResult(
            remainingSeconds = remainingInPhase,
            phaseLabel = phaseLabel,
            tone = if (isStartTone) RestTimerTone.DONE else null,
            isFinished = false,
        )
    }

    fun tick(): IntervalTickResult {
        if (remainingInPhase <= 0) {
            return IntervalTickResult(
                remainingSeconds = null,
                phaseLabel = null,
                tone = null,
                isFinished = true,
            )
        }

        remainingInPhase -= 1

        if (remainingInPhase > 0) {
            val tone = if (isLeadInPhase) null else intervalTimerToneForTransition(remainingInPhase)
            return IntervalTickResult(
                remainingSeconds = remainingInPhase,
                phaseLabel = phaseLabel,
                tone = tone,
                isFinished = false,
            )
        }

        // remainingInPhase reached 0
        when (state) {
            is IntervalTimerState.Countdown -> {
                if (isLeadInPhase) {
                    isLeadInPhase = false
                    remainingInPhase = normalizedRestDurationSeconds(state.seconds)
                    phaseLabel = state.label
                    return IntervalTickResult(
                        remainingSeconds = remainingInPhase,
                        phaseLabel = phaseLabel,
                        tone = RestTimerTone.DONE,
                        isFinished = false,
                    )
                }
                return IntervalTickResult(
                    remainingSeconds = null,
                    phaseLabel = null,
                    tone = RestTimerTone.DONE,
                    isFinished = true,
                )
            }
            is IntervalTimerState.RepBased -> {
                if (isLeadInPhase) {
                    isLeadInPhase = false
                    currentRound = 1
                    remainingInPhase = 60
                    phaseLabel = intervalTimerRepBasedLabel(currentRound, state.totalMinutes)
                    return IntervalTickResult(
                        remainingSeconds = remainingInPhase,
                        phaseLabel = phaseLabel,
                        tone = RestTimerTone.DONE,
                        isFinished = false,
                    )
                }
                if (currentRound >= state.totalMinutes) {
                    return IntervalTickResult(
                        remainingSeconds = null,
                        phaseLabel = null,
                        tone = RestTimerTone.DONE,
                        isFinished = true,
                    )
                }
                currentRound += 1
                remainingInPhase = 60
                phaseLabel = intervalTimerRepBasedLabel(currentRound, state.totalMinutes)
                return IntervalTickResult(
                    remainingSeconds = remainingInPhase,
                    phaseLabel = phaseLabel,
                    tone = RestTimerTone.DONE,
                    isFinished = false,
                )
            }
            is IntervalTimerState.TimeBased -> {
                if (isLeadInPhase) {
                    isLeadInPhase = false
                    currentRound = 1
                    isWorkPhase = true
                    remainingInPhase = clampedIntervalWorkSeconds(state.workSeconds)
                    phaseLabel = "Work"
                    return IntervalTickResult(
                        remainingSeconds = remainingInPhase,
                        phaseLabel = phaseLabel,
                        tone = RestTimerTone.DONE,
                        isFinished = false,
                    )
                }
                if (!isWorkPhase && currentRound >= state.totalMinutes) {
                    return IntervalTickResult(
                        remainingSeconds = null,
                        phaseLabel = null,
                        tone = RestTimerTone.DONE,
                        isFinished = true,
                    )
                }
                if (isWorkPhase) {
                    isWorkPhase = false
                    val workSec = clampedIntervalWorkSeconds(state.workSeconds)
                    remainingInPhase = 60 - workSec
                    phaseLabel = "Rest"
                } else {
                    isWorkPhase = true
                    currentRound += 1
                    val workSec = clampedIntervalWorkSeconds(state.workSeconds)
                    remainingInPhase = workSec
                    phaseLabel = "Work"
                }
                return IntervalTickResult(
                    remainingSeconds = remainingInPhase,
                    phaseLabel = phaseLabel,
                    tone = RestTimerTone.DONE,
                    isFinished = false,
                )
            }
        }
    }
}

class IntervalTimerService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val toneCue = RestToneCue()
    private val appIconBitmap: Bitmap by lazy {
        requireNotNull(ContextCompat.getDrawable(this, R.mipmap.ic_launcher)) {
            "Launcher icon resource not found"
        }.toBitmap()
    }
    private var timerJob: Job? = null
    private var foregroundStarted = false
    private var lastNotificationRemaining: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_COUNTDOWN -> {
                val seconds = intent.getIntExtra(EXTRA_SECONDS, 0)
                val label = intent.getStringExtra(EXTRA_LABEL) ?: ""
                val leadInSeconds = intent.getIntExtra(EXTRA_LEAD_IN_SECONDS, 0).coerceAtLeast(0)
                startSequence(IntervalTimerState.Countdown(seconds, label, leadInSeconds))
            }
            ACTION_START_REP_BASED -> {
                val totalMinutes = intent.getIntExtra(EXTRA_TOTAL_MINUTES, 0)
                val label = intent.getStringExtra(EXTRA_LABEL) ?: ""
                val leadInSeconds = intent.getIntExtra(EXTRA_LEAD_IN_SECONDS, 0).coerceAtLeast(0)
                startSequence(IntervalTimerState.RepBased(totalMinutes, label, leadInSeconds))
            }
            ACTION_START_TIME_BASED -> {
                val totalMinutes = intent.getIntExtra(EXTRA_TOTAL_MINUTES, 0)
                val workSeconds = intent.getIntExtra(EXTRA_WORK_SECONDS, 30)
                val label = intent.getStringExtra(EXTRA_LABEL) ?: ""
                val leadInSeconds = intent.getIntExtra(EXTRA_LEAD_IN_SECONDS, 0).coerceAtLeast(0)
                startSequence(IntervalTimerState.TimeBased(totalMinutes, workSeconds, label, leadInSeconds))
            }
            ACTION_STOP -> stopTimer()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        timerJob?.cancel()
        toneCue.release()
        _remainingSeconds.value = null
        _phaseLabel.value = null
        super.onDestroy()
    }

    private fun startSequence(state: IntervalTimerState) {
        val sequence = IntervalTimerSequence(state)
        val initial = sequence.initialResult()
        if (initial.isFinished || initial.remainingSeconds == null) {
            stopTimer()
            return
        }

        timerJob?.cancel()
        ensureNotificationChannel()
        _remainingSeconds.value = initial.remainingSeconds
        _phaseLabel.value = initial.phaseLabel
        playTone(initial.tone)
        refreshNotification(previous = null, current = initial.remainingSeconds, label = initial.phaseLabel ?: "")

        timerJob = serviceScope.launch {
            var previous: Int? = initial.remainingSeconds
            while (isActive) {
                delay(1_000)
                val result = sequence.tick()
                _remainingSeconds.value = result.remainingSeconds
                _phaseLabel.value = result.phaseLabel
                playTone(result.tone)
                refreshNotification(
                    previous = previous,
                    current = result.remainingSeconds,
                    label = result.phaseLabel ?: "",
                )

                if (result.isFinished || result.remainingSeconds == null) {
                    delay(DONE_TONE_TEARDOWN_DELAY_MS)
                    stopTimer(stopService = true)
                    break
                }
                previous = result.remainingSeconds
            }
        }
    }

    private fun stopTimer(stopService: Boolean = true) {
        timerJob?.cancel()
        timerJob = null
        _remainingSeconds.value = null
        _phaseLabel.value = null
        lastNotificationRemaining = null
        if (foregroundStarted) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            foregroundStarted = false
        }
        if (stopService) stopSelf()
    }

    private fun playTone(tone: RestTimerTone?) {
        when (tone) {
            RestTimerTone.WARNING -> toneCue.warning()
            RestTimerTone.TICK -> toneCue.tick()
            RestTimerTone.DONE -> toneCue.done()
            null -> Unit
        }
    }

    private fun refreshNotification(previous: Int?, current: Int?, label: String) {
        if (!shouldRefreshRestNotification(previous, current, lastNotificationRemaining)) return
        if (current == null) return

        val notification = buildNotification(current, label)
        if (!foregroundStarted) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                foregroundStarted = true
            }
        } else if (lastNotificationRemaining != current) {
            runCatching {
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, notification)
            }
        }
        lastNotificationRemaining = current
    }

    private fun buildNotification(remainingSeconds: Int, label: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(intervalTimerNotificationIcon(this, remainingSeconds))
            .setLargeIcon(appIconBitmap)
            .setContentTitle(if (label.isNotBlank()) label else "Interval timer")
            .setContentText("Tap to return to your workout")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setWhen(System.currentTimeMillis() + remainingSeconds * 1_000L)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .build()
    }

    private fun intervalTimerNotificationIcon(context: Context, remainingSeconds: Int?): IconCompat =
        remainingSeconds
            ?.let { runCatching { renderCountdownIcon(it, context) }.getOrNull() }
            ?: IconCompat.createWithResource(context, R.drawable.ic_rest_timer_notification)

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Interval timer",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Workout interval and warmup countdown"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "interval_timer"
        private const val NOTIFICATION_ID = 1202
        private const val ACTION_START_COUNTDOWN = "com.jauschua.ironlogv2.interval_timer.START_COUNTDOWN"
        private const val ACTION_START_REP_BASED = "com.jauschua.ironlogv2.interval_timer.START_REP_BASED"
        private const val ACTION_START_TIME_BASED = "com.jauschua.ironlogv2.interval_timer.START_TIME_BASED"
        private const val ACTION_STOP = "com.jauschua.ironlogv2.interval_timer.STOP"
        private const val EXTRA_SECONDS = "seconds"
        private const val EXTRA_TOTAL_MINUTES = "totalMinutes"
        private const val EXTRA_WORK_SECONDS = "workSeconds"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_LEAD_IN_SECONDS = "leadInSeconds"
        private const val DONE_TONE_TEARDOWN_DELAY_MS = 450L

        private val _remainingSeconds = MutableStateFlow<Int?>(null)
        val remainingSeconds: StateFlow<Int?> = _remainingSeconds.asStateFlow()

        private val _phaseLabel = MutableStateFlow<String?>(null)
        val phaseLabel: StateFlow<String?> = _phaseLabel.asStateFlow()

        fun startCountdown(context: Context, seconds: Int, label: String, leadInSeconds: Int = 0) {
            val duration = normalizedRestDurationSeconds(seconds)
            if (duration <= 0) return
            val leadInDuration = leadInSeconds.coerceAtLeast(0)
            val intent = Intent(context, IntervalTimerService::class.java)
                .setAction(ACTION_START_COUNTDOWN)
                .putExtra(EXTRA_SECONDS, duration)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_LEAD_IN_SECONDS, leadInDuration)
            ContextCompat.startForegroundService(context, intent)
        }

        fun startRepBasedIntervals(
            context: Context,
            totalMinutes: Int,
            label: String,
            leadInSeconds: Int = 0,
        ) {
            if (totalMinutes <= 0) return
            val leadInDuration = leadInSeconds.coerceAtLeast(0)
            val intent = Intent(context, IntervalTimerService::class.java)
                .setAction(ACTION_START_REP_BASED)
                .putExtra(EXTRA_TOTAL_MINUTES, totalMinutes)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_LEAD_IN_SECONDS, leadInDuration)
            ContextCompat.startForegroundService(context, intent)
        }

        fun startTimeBasedIntervals(
            context: Context,
            totalMinutes: Int,
            workSeconds: Int,
            label: String,
            leadInSeconds: Int = 0,
        ) {
            if (totalMinutes <= 0) return
            val leadInDuration = leadInSeconds.coerceAtLeast(0)
            val intent = Intent(context, IntervalTimerService::class.java)
                .setAction(ACTION_START_TIME_BASED)
                .putExtra(EXTRA_TOTAL_MINUTES, totalMinutes)
                .putExtra(EXTRA_WORK_SECONDS, workSeconds)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_LEAD_IN_SECONDS, leadInDuration)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            if (_remainingSeconds.value == null) return
            val intent = Intent(context, IntervalTimerService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
