package cvc.dashingdog.brandwag.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import cvc.dashingdog.brandwag.data.repository.BurnStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Target for AlarmManager.setAlarmClock() PendingIntents. Deliberately thin -
 * its only job is to start AlarmForegroundService, which is what actually
 * survives to do the work. A BroadcastReceiver's own execution window is far
 * too short (a few seconds) to own the sound/vibration loop itself.
 *
 * ARMED CHECK (added alongside the real arm switch, Chris's call): this is
 * the must-never backstop against a stale scheduled snooze/alarm firing
 * after disarm, independent of whether AlarmDisarmHandler.onDisarmed()'s
 * own cancellation succeeded upstream. Deliberately unconditional - no
 * bypass extra - so the manual 1/5/11-min test triggers ALSO honor real
 * armed state now. This is intentional, not a side effect: it means those
 * buttons can be used to directly verify "does disarm actually prevent a
 * previously-scheduled fire," not just "was cancelSnooze() called." To test
 * those buttons at all, burn state must be armed via the real switch first.
 *
 * triggerImmediateButton is UNAFFECTED - it calls AlarmForegroundService
 * directly, bypassing this receiver, and stays a pure sound/vibration/
 * delivery isolation test regardless of armed state.
 *
 * Boot recovery (4d) already independently checks armed state in
 * BootRecoveryLogic before rescheduling - this check is redundant for that
 * path specifically, but harmless, and keeps this receiver's guarantee
 * unconditional rather than caller-dependent.
 *
 * goAsync() is required here (same reasoning as BootCompletedReceiver, 4d):
 * onReceive()'s synchronous body can't await the suspend DataStore read.
 */
class AlarmTestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val maxGustKmh = intent.getDoubleExtra(
            AlarmForegroundService.EXTRA_MAX_GUST_KMH,
            Double.NaN
        )

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val burnState = BurnStateRepository(context).getBurnState()
                if (burnState.armed) {
                    AlarmForegroundService.start(context, maxGustKmh)
                } else {
                    android.util.Log.i(
                        "AlarmTestReceiver",
                        "Alarm fired but burn state is disarmed - suppressing (must-never guard)"
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_MAX_GUST_KMH = AlarmForegroundService.EXTRA_MAX_GUST_KMH
    }
}