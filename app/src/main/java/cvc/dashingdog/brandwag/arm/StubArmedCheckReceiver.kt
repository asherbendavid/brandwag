package cvc.dashingdog.brandwag.arm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cvc.dashingdog.brandwag.data.repository.BurnStateRepository
import cvc.dashingdog.brandwag.data.repository.SafetyPermissionBlipRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * THROWAWAY, paired with StubArmedScheduler - see that file's warning.
 * Each tick: bail if disarmed (defensive - scheduler should already have
 * cancelled, this is belt-and-suspenders); check both permissions via the
 * blip repository; act on FIRST_BLIP (notify) and SECOND_BLIP_DISARM
 * (auto-disarm via the same CriticalDisarm path the UI uses, then notify);
 * check a SIMULATED WindQuorumResult (same placeholder pattern as
 * AlarmCheckPipeline today) to pick the next interval; reschedule.
 */
class StubArmedCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                StubArmedCheckLogic.runTick(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
}