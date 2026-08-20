package cvc.dashingdog.brandwag.arm

import android.content.Context
import cvc.dashingdog.brandwag.data.repository.BurnStateRepository
import cvc.dashingdog.brandwag.data.repository.SafetyPermissionBlipRepository

object StubArmedCheckLogic {
    suspend fun runTick(context: Context) {
        val burnState = BurnStateRepository(context).getBurnState()
        if (!burnState.armed) {
            android.util.Log.i("StubArmedCheckReceiver", "Tick fired but already disarmed - no-op, not rescheduling")
            return
        }

        val blipRepo = SafetyPermissionBlipRepository(context)

        // Both checks always run, independently, regardless of either one's result -
        // must-never guarantee is that neither permission's blip counter is silently
        // skipped because the other one triggered a disarm first.
        val batteryTriggeredDisarm = handlePermissionCheck(
            context, blipRepo,
            SafetyPermissionBlipRepository.PermissionType.BATTERY_EXEMPTION,
            ArmGateChecks.isBatteryExemptionGranted(context)
        )
        val dndTriggeredDisarm = handlePermissionCheck(
            context, blipRepo,
            SafetyPermissionBlipRepository.PermissionType.DND_ACCESS,
            ArmGateChecks.isDndAccessGranted(context)
        )

        if (batteryTriggeredDisarm || dndTriggeredDisarm) {
            // At least one already ran CriticalDisarm.disarm() and cancelled the stub
            // chain via StubArmedScheduler.onDisarmed() inside handlePermissionCheck().
            // If BOTH triggered in the same tick, CriticalDisarm.disarm() runs twice in a
            // row - safe (setArmed(false) and AlarmDisarmHandler.onDisarmed() are both
            // idempotent per their own design), just slightly redundant. Not worth
            // special-casing to prevent.
            return
        }

        val isDegraded = false // TODO Phase 5: replace with real WindQuorumResult.Degraded check
        val nextInterval = if (isDegraded) {
            StubArmedScheduler.DEGRADED_INTERVAL_MINUTES
        } else {
            StubArmedScheduler.NORMAL_INTERVAL_MINUTES
        }
        StubArmedScheduler.scheduleNextTick(context, nextInterval)
        android.util.Log.w("runTick", "next check scheduled $nextInterval min")
    }

    /** Returns true if this check just caused an auto-disarm. */
    private suspend fun handlePermissionCheck(
        context: Context,
        blipRepo: SafetyPermissionBlipRepository,
        type: SafetyPermissionBlipRepository.PermissionType,
        isGranted: Boolean
    ): Boolean {
        val result = blipRepo.recordCheck(type, isGranted)
        when (result.transition) {
            SafetyPermissionBlipRepository.BlipTransition.STILL_OK,
            SafetyPermissionBlipRepository.BlipTransition.SILENTLY_RECOVERED -> {
                android.util.Log.w("StubArmedCheckReceiver", "$type STILL_OK or SILENTLY_RECOVERED")
            }
            SafetyPermissionBlipRepository.BlipTransition.FIRST_BLIP -> {
                android.util.Log.w("StubArmedCheckReceiver", "$type first blip - notifying")
                SafetyPermissionNotifier.notifyFirstBlip(context, type)
            }
            SafetyPermissionBlipRepository.BlipTransition.SECOND_BLIP_DISARM -> {
                android.util.Log.w("StubArmedCheckReceiver", "$type second blip - auto-disarming")
                when (CriticalDisarm.disarm(context)) {
                    CriticalDisarmResult.Success, CriticalDisarmResult.CleanupFailed -> {
                        StubArmedScheduler.onDisarmed(context)
                        SafetyPermissionNotifier.notifyAutoDisarmed(context, type)
                    }
                    CriticalDisarmResult.CriticalWriteFailed -> {
                        android.util.Log.e("StubArmedCheckReceiver", "CRITICAL: auto-disarm write failed for $type - still armed")
                    }
                }
                return true
            }
        }
        return false
    }
}