package cvc.dashingdog.brandwag.arm

import android.content.Context
import cvc.dashingdog.brandwag.alarm.AlarmDisarmHandler
import cvc.dashingdog.brandwag.data.repository.BurnStateRepository

/**
 * The must-never-critical disarm sequence, extracted so there is exactly
 * ONE implementation regardless of caller (real arm-switch UI, or the stub
 * scheduler's auto-disarm-on-blip path). Two independent copies of this
 * ordering would be a single point of drift risk on the most safety-
 * critical write in the app - not acceptable per the reliability guide.
 *
 * Ordering (Chris's explicit call, see chat history): armed=false is
 * persisted FIRST - this is the write that governs whether a real trigger
 * treats the farm as armed. Cancelling the pending snooze/scheduled alarm
 * is secondary; a failure there means "a stale alarm might sound once,"
 * not "the farm is silently still armed."
 */
sealed class CriticalDisarmResult {
    object Success : CriticalDisarmResult()
    object CriticalWriteFailed : CriticalDisarmResult() // armed=false did NOT persist - still armed
    object CleanupFailed : CriticalDisarmResult() // armed=false persisted OK; cancellation failed
}

object CriticalDisarm {
    suspend fun disarm(context: Context): CriticalDisarmResult {
        val armedStateCleared = try {
            BurnStateRepository(context).setArmed(false)
            true
        } catch (e: Exception) {
            android.util.Log.e("CriticalDisarm", "CRITICAL: setArmed(false) failed - still armed: ${e.message}")
            false
        }

        if (!armedStateCleared) {
            return CriticalDisarmResult.CriticalWriteFailed
        }

        return try {
            AlarmDisarmHandler.onDisarmed(context)
            CriticalDisarmResult.Success
        } catch (e: Exception) {
            android.util.Log.e("CriticalDisarm", "Disarmed, but cancelling pending alarm/snooze failed: ${e.message}")
            CriticalDisarmResult.CleanupFailed
        }
    }
}