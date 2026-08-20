package cvc.dashingdog.brandwag.arm

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * THROWAWAY - purpose-built only to unblock 4e's revocation detection and
 * 4f's degraded-poll escalation ahead of Phase 5's real scheduler. Do NOT
 * extend this with anything beyond the hourly/15-min tick + blip check.
 * Phase 5 replaces this entirely; delete on that day, don't try to evolve
 * it into the real thing.
 */
object StubArmedScheduler {

    private const val REQUEST_CODE_STUB_TICK = 9101
    const val NORMAL_INTERVAL_MINUTES = 60L
    const val DEGRADED_INTERVAL_MINUTES = 15L

    fun onArmed(context: Context) {
        scheduleNextTick(context, NORMAL_INTERVAL_MINUTES)
    }

    fun onDisarmed(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(buildPendingIntent(context))
    }

    @SuppressLint("MissingPermission")
    fun scheduleNextTick(context: Context, delayMinutes: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAtMillis = System.currentTimeMillis() + delayMinutes * 60_000L
        val showIntent = Intent(context, cvc.dashingdog.brandwag.MainActivity::class.java)
        val showPendingIntent = PendingIntent.getActivity(
            context, REQUEST_CODE_STUB_TICK, showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerAtMillis, showPendingIntent),
            buildPendingIntent(context)
        )
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, StubArmedCheckReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE_STUB_TICK, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}