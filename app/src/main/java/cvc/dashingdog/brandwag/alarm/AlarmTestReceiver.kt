package cvc.dashingdog.brandwag.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Target for AlarmManager.setAlarmClock() PendingIntents. Deliberately thin -
 * its only job is to start AlarmForegroundService, which is what actually
 * survives to do the work. A BroadcastReceiver's own execution window is far
 * too short (a few seconds) to own the sound/vibration loop itself.
 *
 * This is also what proves the "cold-start from a killed process" guarantee
 * setAlarmClock() gives us - the OS will instantiate this receiver even if
 * Brandwag's process was killed, as long as the app hasn't been Force
 * Stopped by the user (see PHASE4 handoff, must-never list).
 *
 * Reused later (4b/4d) as the target for snooze re-sound callbacks and
 * boot-recovery re-scheduling - not exclusively a test-only class, despite
 * being wired up here via the 4a manual test triggers.
 */
class AlarmTestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val maxGustKmh = intent.getDoubleExtra(
            AlarmForegroundService.EXTRA_MAX_GUST_KMH,
            Double.NaN
        )
        AlarmForegroundService.start(context, maxGustKmh)
    }

    companion object {
        const val EXTRA_MAX_GUST_KMH = AlarmForegroundService.EXTRA_MAX_GUST_KMH
    }
}