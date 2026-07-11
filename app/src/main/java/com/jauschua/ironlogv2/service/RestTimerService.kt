package com.jauschua.ironlogv2.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jauschua.ironlogv2.R
import com.jauschua.ironlogv2.ui.MainActivity
import com.jauschua.ironlogv2.ui.screens.capture.RestToneCue
import com.jauschua.ironlogv2.ui.screens.capture.formatRestTime
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

interface RestTimerController {
    val remainingSeconds: StateFlow<Int?>
    fun startRest(seconds: Int)
    fun skipRest()
    fun addRestTime(extraSeconds: Int = 30)
}

/**
 * Test/default controller for plain JVM ViewModel tests. Production factories pass
 * [AndroidRestTimerController], which routes commands through [RestTimerService].
 */
class InMemoryRestTimerController : RestTimerController {
    private val _remainingSeconds = MutableStateFlow<Int?>(null)
    override val remainingSeconds: StateFlow<Int?> = _remainingSeconds.asStateFlow()

    override fun startRest(seconds: Int) {
        _remainingSeconds.value = normalizedRestDurationSeconds(seconds).takeIf { it > 0 }
    }

    override fun skipRest() {
        _remainingSeconds.value = null
    }

    override fun addRestTime(extraSeconds: Int) {
        val current = _remainingSeconds.value ?: return
        _remainingSeconds.value = normalizedRestDurationSeconds(current + extraSeconds)
    }
}

class AndroidRestTimerController(context: Context) : RestTimerController {
    private val appContext = context.applicationContext

    override val remainingSeconds: StateFlow<Int?> = RestTimerService.remainingSeconds

    override fun startRest(seconds: Int) {
        RestTimerService.start(appContext, seconds)
    }

    override fun skipRest() {
        RestTimerService.skip(appContext)
    }

    override fun addRestTime(extraSeconds: Int) {
        RestTimerService.addTime(appContext, extraSeconds)
    }
}

internal data class RestTimerNotificationContent(
    val title: String,
    val text: String,
    val ongoing: Boolean,
)

internal enum class RestTimerTone {
    WARNING,
    TICK,
    DONE,
}

internal fun normalizedRestDurationSeconds(seconds: Int): Int = seconds.coerceAtLeast(0)

internal fun restTimerNotificationContent(remainingSeconds: Int): RestTimerNotificationContent =
    RestTimerNotificationContent(
        title = "Rest timer",
        text = "${formatRestTime(remainingSeconds)} remaining",
        ongoing = true,
    )

internal fun restTimerToneForTransition(previous: Int?, current: Int?): RestTimerTone? = when (current) {
    15 -> RestTimerTone.WARNING
    3, 2, 1 -> RestTimerTone.TICK
    null -> if (previous == 1) RestTimerTone.DONE else null
    else -> null
}

internal fun shouldRefreshRestNotification(previous: Int?, current: Int?): Boolean {
    if (current == null) return previous != null
    if (previous == null) return true
    return current <= 15 || current % 5 == 0
}

class RestTimerService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val toneCue = RestToneCue()
    private var countdownJob: Job? = null
    private var foregroundStarted = false
    private var lastNotificationRemaining: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTimer(intent.getIntExtra(EXTRA_SECONDS, 0))
            ACTION_SKIP -> stopTimer()
            ACTION_ADD_TIME -> addTimeInternal(intent.getIntExtra(EXTRA_SECONDS, 30))
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        countdownJob?.cancel()
        toneCue.release()
        _remainingSeconds.value = null
        super.onDestroy()
    }

    private fun startTimer(seconds: Int) {
        val duration = normalizedRestDurationSeconds(seconds)
        if (duration <= 0) {
            stopTimer()
            return
        }

        countdownJob?.cancel()
        ensureNotificationChannel()
        _remainingSeconds.value = duration
        refreshNotification(previous = null, current = duration)

        countdownJob = serviceScope.launch {
            var previous: Int? = duration
            while (isActive) {
                delay(1_000)
                val current = _remainingSeconds.value ?: break
                val next = (current - 1).takeIf { it > 0 }
                _remainingSeconds.value = next
                playTone(restTimerToneForTransition(previous = current, current = next))
                refreshNotification(previous = current, current = next)

                if (next == null) {
                    delay(DONE_TONE_TEARDOWN_DELAY_MS)
                    stopTimer(stopService = true)
                    break
                }
                previous = next
            }
        }
    }

    private fun addTimeInternal(extraSeconds: Int) {
        val current = _remainingSeconds.value ?: return
        val next = normalizedRestDurationSeconds(current + extraSeconds).takeIf { it > 0 } ?: return
        _remainingSeconds.value = next
        refreshNotification(previous = current, current = next)
    }

    private fun stopTimer(stopService: Boolean = true) {
        countdownJob?.cancel()
        countdownJob = null
        _remainingSeconds.value = null
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

    private fun refreshNotification(previous: Int?, current: Int?) {
        if (!shouldRefreshRestNotification(previous, current)) return
        if (current == null) return

        val notification = buildNotification(current)
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

    private fun buildNotification(remainingSeconds: Int): Notification {
        val content = restTimerNotificationContent(remainingSeconds)
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setContentIntent(pendingIntent)
            .setOngoing(content.ongoing)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setWhen(System.currentTimeMillis() + remainingSeconds * 1_000L)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .build()
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Rest timer",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Workout rest countdown"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "rest_timer"
        private const val NOTIFICATION_ID = 1201
        private const val ACTION_START = "com.jauschua.ironlogv2.rest_timer.START"
        private const val ACTION_SKIP = "com.jauschua.ironlogv2.rest_timer.SKIP"
        private const val ACTION_ADD_TIME = "com.jauschua.ironlogv2.rest_timer.ADD_TIME"
        private const val EXTRA_SECONDS = "seconds"
        private const val DONE_TONE_TEARDOWN_DELAY_MS = 450L

        private val _remainingSeconds = MutableStateFlow<Int?>(null)
        val remainingSeconds: StateFlow<Int?> = _remainingSeconds.asStateFlow()

        fun start(context: Context, seconds: Int) {
            val duration = normalizedRestDurationSeconds(seconds)
            if (duration <= 0) return
            val intent = Intent(context, RestTimerService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SECONDS, duration)
            ContextCompat.startForegroundService(context, intent)
        }

        fun skip(context: Context) {
            if (_remainingSeconds.value == null) return
            val intent = Intent(context, RestTimerService::class.java).setAction(ACTION_SKIP)
            context.startService(intent)
        }

        fun addTime(context: Context, extraSeconds: Int) {
            if (_remainingSeconds.value == null) return
            val intent = Intent(context, RestTimerService::class.java)
                .setAction(ACTION_ADD_TIME)
                .putExtra(EXTRA_SECONDS, extraSeconds)
            context.startService(intent)
        }
    }
}
