package cvc.dashingdog.brandwag.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import cvc.dashingdog.brandwag.R

/**
 * Owns the sound + vibration loop for a burn-wind alarm. Started via
 * [start] and guaranteed to keep running independent of [AlarmActivity]'s
 * lifecycle (activity swipe-away must not stop this service).
 *
 * Does NOT decide whether to alarm - that's TriggerLogic (Phase 3). This
 * service only knows how to sound/vibrate/display once told to.
 */
class AlarmForegroundService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isSounding = false // idempotency guard - see startAlarm()

    private val autoStopHandler = Handler(Looper.getMainLooper())
    private val autoStopRunnable = Runnable {
        // 10-minute loop timeout: distinct from the 15-minute wake lock backstop above.
        // This is the primary, expected stop path if the user never taps Snooze/Dismiss -
        // the wake lock timeout is a secondary net in case even this somehow fails to fire.
        stopAlarm()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val maxGustKmh = intent.getDoubleExtra(EXTRA_MAX_GUST_KMH, Double.NaN)
                startAlarm(maxGustKmh)
            }
            ACTION_STOP -> stopAlarm()
            else -> stopAlarm() // defensive: unknown/missing action must never leave it sounding silently un-actionable
        }
        return START_NOT_STICKY // deliberate: a killed service should NOT auto-restart mid-loop with stale state.
        // Recovery after a kill is handled by BOOT_COMPLETED (4d) / the snooze-as-reboot-recovery path (4b),
        // not by the OS blindly restarting this service with whatever intent it last saw.
    }

    private fun startAlarm(maxGustKmh: Double) {
        if (isSounding) {
            // Idempotency guard: a second ACTION_START while already sounding must never spin
            // up a duplicate MediaPlayer (leak) or restart the vibration waveform/wake lock.
            // This is defense-in-depth independent of the upstream commit-phase Mutex (4c) -
            // if that mutex is ever bypassed or misused, this is the last line of defense.
            android.util.Log.i(TAG, "startAlarm() called while already sounding - no-op")
            return
        }
        android.util.Log.i(TAG, "startAlarm() - starting sound/vibration/wakelock/notification, maxGustKmh=$maxGustKmh")
        isSounding = true
        AlarmStateHolder.setSounding(maxGustKmh)
        acquireWakeLock()
        startForeground(NOTIFICATION_ID, buildNotification(maxGustKmh))
        startSound()
        startVibration()
        autoStopHandler.postDelayed(autoStopRunnable, LOOP_TIMEOUT_MS)
    }

    private fun stopAlarm() {
        if (!isSounding) {
            // Idempotent: dismiss racing the 10-min timeout, or any other double-stop path,
            // must not throw or double-run cleanup (e.g. stopSelf() on an already-stopped service).
            android.util.Log.i(TAG, "stopAlarm() called while already idle - no-op")
            return
        }
        android.util.Log.i(TAG, "stopAlarm() - stopping sound/vibration/wakelock/notification")
        isSounding = false
        AlarmStateHolder.setIdle()

        autoStopHandler.removeCallbacks(autoStopRunnable)

        mediaPlayer?.apply {
            if (isPlaying) stop()
            release()
        }
        mediaPlayer = null

        vibrator?.cancel()

        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Safety net: if something stops this service via a path other than ACTION_STOP
        // (shouldn't happen given START_NOT_STICKY, but a killed/reaped process must
        // never leave a wake lock held or a MediaPlayer instance leaked).
        stopAlarm()
        super.onDestroy()
    }

    private fun startSound() {
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            isLooping = true
            val afd = resources.openRawResourceFd(R.raw.alarm_sound)
            setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            prepare()
            start()
        }
    }

    private fun startVibration() {
        vibrator = getVibrator()
        // Pattern: [0ms wait, 750ms on, 250ms off], looping from index 1 (the "on" step).
        // Index 0 is the initial delay before the pattern starts, NOT part of the loop.
        val pattern = longArrayOf(0, 750, 250)
        val effect = VibrationEffect.createWaveform(pattern, /* repeatIndex = */ 1)
        vibrator?.vibrate(effect)
    }

    private fun getVibrator(): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Brandwag:AlarmWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(MAX_ALARM_DURATION_MS)
            // Timeout is a safety backstop only (must never hold a wake lock forever if
            // stopAlarm() is somehow never called) - not the intended stop path.
        }
    }

    private fun buildNotification(maxGustKmh: Double): android.app.Notification {
        createNotificationChannel()

        val fullScreenIntent = Intent(this, AlarmActivity::class.java).apply {
            putExtra(EXTRA_MAX_GUST_KMH, maxGustKmh)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            0,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val gustText = if (maxGustKmh.isNaN()) {
            getString(R.string.alarm_notification_text_no_reading)
        } else {
            getString(R.string.alarm_notification_text_with_gust, maxGustKmh.toInt())
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_brandwag)
            .setContentTitle(getString(R.string.alarm_notification_title))
            .setContentText(gustText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.alarm_notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                // Sound and vibration are handled manually (MediaPlayer/Vibrator above) so the
                // channel itself must stay silent - otherwise the alarm plays twice.
                setSound(null, null)
                enableVibration(false)
                // Bypasses DND deliberately: this alarm only ever fires when TriggerLogic has
                // already decided conditions are safety-critical (Phase 3) - it is never
                // fired speculatively, so there's no "politeness" tradeoff to make here.
                setBypassDnd(true)
                description = getString(R.string.alarm_notification_channel_description)
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "cvc.dashingdog.brandwag.alarm.action.START"
        const val ACTION_STOP = "cvc.dashingdog.brandwag.alarm.action.STOP"
        const val EXTRA_MAX_GUST_KMH = "cvc.dashingdog.brandwag.alarm.extra.MAX_GUST_KMH"

        private const val CHANNEL_ID = "burn_wind_alarm"
        private const val TAG = "AlarmForegroundService"
        private const val NOTIFICATION_ID = 1001
        private const val LOOP_TIMEOUT_MS = 10 * 60 * 1000L // 10 min: primary auto-stop for the sound/vibration loop
        private const val MAX_ALARM_DURATION_MS = 15 * 60 * 1000L // 15 min: secondary wake lock backstop, see acquireWakeLock()

        fun start(context: Context, maxGustKmh: Double) {
            val intent = Intent(context, AlarmForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MAX_GUST_KMH, maxGustKmh)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AlarmForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}