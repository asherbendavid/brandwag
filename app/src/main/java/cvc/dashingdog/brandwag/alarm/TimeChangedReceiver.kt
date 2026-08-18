package cvc.dashingdog.brandwag.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Catches the moment the system clock is corrected (manually, or via NTP)
 * or the timezone changes, and re-runs BootRecoveryLogic. This is what
 * actually resolves the case BootCompletedReceiver deferred due to clock
 * ambiguity (see BootRecoveryLogic's clock-sanity guard) - without this
 * receiver, a deferred case would stay deferred forever, since nothing else
 * would ever re-check it.
 *
 * TIME_SET and TIMEZONE_CHANGED are both on Android's documented list of
 * implicit-broadcast exceptions that manifest-declared receivers may still
 * receive post-API26 (unlike most implicit broadcasts, which require
 * runtime registration since Android 8) - confirmed via
 * developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions.
 */
class TimeChangedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIME_CHANGED &&
            intent.action != Intent.ACTION_TIMEZONE_CHANGED
        ) {
            return
        }

        android.util.Log.i(TAG, "Received ${intent.action} - re-running BootRecoveryLogic")

        val pendingResult = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                BootRecoveryLogic.run(appContext)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "BootRecoveryLogic.run() failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "TimeChangedReceiver"
    }
}