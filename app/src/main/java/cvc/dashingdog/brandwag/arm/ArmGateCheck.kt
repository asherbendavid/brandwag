package cvc.dashingdog.brandwag.arm

import android.content.Context
import android.os.PowerManager
import android.app.NotificationManager

/**
 * Pure read-only checks - no side effects, no dialogs. Kept separate from
 * ArmGateController so the underlying permission state can be checked
 * (e.g. by the stub scheduler's hourly blip logic) without dragging in any
 * dialog/UI machinery. Same functions serve both the arm-gate and the
 * revocation-detection blip check - one source of truth for "is it granted."
 */
object ArmGateChecks {

    fun isBatteryExemptionGranted(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun isDndAccessGranted(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }
}