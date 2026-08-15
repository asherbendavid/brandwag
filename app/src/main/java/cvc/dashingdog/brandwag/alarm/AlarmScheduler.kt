package cvc.dashingdog.brandwag.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Wraps AlarmManager.setAlarmClock() scheduling against AlarmTestReceiver.
 * Used by the 4a manual test triggers now; 4b (snooze re-sound) and 4d
 * (boot recovery re-scheduling) will call the same scheduleAlarm() with
 * their own delay/requestCode rather than duplicating this wiring.
 */
object AlarmScheduler {

    /**
     * Schedules a wake-up at [triggerAtMillis] that starts the alarm service
     * with [maxGustKmh]. setAlarmClock() is exempt from Doze/App Standby and
     * from the SCHEDULE_EXACT_ALARM permission requirement - it is treated
     * as a user-facing alarm clock by the OS, same category as the built-in
     * Clock app.
     *
     * [requestCode] distinguishes concurrent pending alarms (e.g. a real
     * armed-day alarm vs. a test trigger vs. a pending snooze) so scheduling
     * one doesn't silently cancel/overwrite another via PendingIntent
     * equality. 4b/4c/4d must each pick their own stable requestCode.
     */
    @android.annotation.SuppressLint("ScheduleExactAlarm", "MissingPermission")
    // Lint false positive: setAlarmClock() is documented as exempt from SCHEDULE_EXACT_ALARM -
    // this inspection doesn't distinguish it from setExact()/setExactAndAllowWhileIdle(), which
    // DO require the permission. See: https://developer.android.com/reference/android/app/AlarmManager#setAlarmClock(android.app.AlarmManager.AlarmClockInfo,%20android.app.PendingIntent)
    fun scheduleAlarm(
        context: Context,
        triggerAtMillis: Long,
        maxGustKmh: Double,
        requestCode: Int
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val operationIntent = Intent(context, AlarmTestReceiver::class.java).apply {
            putExtra(AlarmForegroundService.EXTRA_MAX_GUST_KMH, maxGustKmh)
        }
        val operationPendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            operationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // showIntent: what's launched if the user taps the alarm-clock icon in the
        // status bar before it fires. MainActivity is a reasonable placeholder for now.
        val showIntent = Intent(context, cvc.dashingdog.brandwag.MainActivity::class.java)
        val showPendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerAtMillis, showPendingIntent),
            operationPendingIntent
        )
    }

    // Distinct request codes so the three test triggers don't overwrite each other's
    // PendingIntents if fired in quick succession during testing.
    const val REQUEST_CODE_TEST_IMMEDIATE = 9001
    const val REQUEST_CODE_TEST_1MIN = 9002
    const val REQUEST_CODE_TEST_5MIN = 9003
}