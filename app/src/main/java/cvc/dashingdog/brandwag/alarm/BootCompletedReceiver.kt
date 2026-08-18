package cvc.dashingdog.brandwag.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-registers whatever alarm/snooze state was pending at the moment the
 * process died (kill or reboot). Phase 4d.
 *
 * Delegates to BootRecoveryLogic - TimeChangedReceiver calls the exact same
 * logic when the clock is later corrected, so the real implementation lives
 * in one place, not duplicated across two receivers.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        // onReceive()'s own execution window is far too short for the suspend
        // DataStore reads/writes inside BootRecoveryLogic.run() - goAsync() keeps the
        // process alive long enough to finish, at which point pendingResult.finish()
        // releases it.
        val pendingResult = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                BootRecoveryLogic.run(appContext)
            } catch (e: Exception) {
                // Must never let an unexpected exception here silently swallow boot
                // recovery with no trace - a crash mid-handler is exactly the scenario
                // this whole receiver exists to be resilient against.
                android.util.Log.e(TAG, "BootRecoveryLogic.run() failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }
}