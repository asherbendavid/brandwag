package cvc.dashingdog.brandwag.arm

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import cvc.dashingdog.brandwag.data.repository.BurnStateRepository

/**
 * Sequences the two hard-block arm gates (Gate 1: battery exemption,
 * Gate 2: DND access) and only calls through to BurnStateRepository.setArmed(true)
 * once both pass. No "denied forever" memory - every arm attempt re-checks
 * both from scratch, since there's no revocation callback for either and the
 * user can legitimately grant either one in Settings between attempts.
 *
 * UI layer (Activity) owns showing the actual dialogs and the onResume()
 * re-check after sending the user to Settings - this class only owns the
 * sequencing/decision logic, kept separate so it's testable without a real
 * Activity.
 */
class ArmGateController(
    private val context: Context,
    private val burnStateRepository: BurnStateRepository = BurnStateRepository(context)
) {

    sealed class GateResult {
        object Armed : GateResult()
        object NeedsBatteryExemptionDialog : GateResult()
        object NeedsDndAccessDialog : GateResult()
        object BlockedBatteryExemptionDenied : GateResult()
        object BlockedDndAccessDenied : GateResult()
    }

    /**
     * Call when the user first taps "Arm." Returns which dialog to show, or
     * Armed if both gates already pass (e.g. granted in a prior session).
     */
    fun checkGatesOnArmAttempt(): GateResult {
        if (!ArmGateChecks.isBatteryExemptionGranted(context)) {
            return GateResult.NeedsBatteryExemptionDialog
        }
        if (!ArmGateChecks.isDndAccessGranted(context)) {
            return GateResult.NeedsDndAccessDialog
        }
        return GateResult.Armed
    }

    /**
     * Call from onResume() after the user returns from the battery-exemption
     * system dialog (which DOES have a result callback via
     * ActivityResultContracts, but we re-check the live state directly here
     * rather than trusting the callback's own result code - simpler, and
     * matches how Gate 2 has to work since it has no callback at all).
     */
    fun recheckAfterBatteryExemptionPrompt(): GateResult {
        if (!ArmGateChecks.isBatteryExemptionGranted(context)) {
            return GateResult.BlockedBatteryExemptionDenied
        }
        if (!ArmGateChecks.isDndAccessGranted(context)) {
            return GateResult.NeedsDndAccessDialog
        }
        return GateResult.Armed
    }

    /**
     * Call from onResume() after the user returns from the DND access
     * Settings screen. No callback exists at all for this one - onResume()
     * is the only signal that they've come back, live re-check is mandatory.
     */
    fun recheckAfterDndAccessPrompt(): GateResult {
        if (!ArmGateChecks.isDndAccessGranted(context)) {
            return GateResult.BlockedDndAccessDenied
        }
        return GateResult.Armed
    }

    /** Actually persists the armed state once both gates have passed. */
    suspend fun commitArm() {
        burnStateRepository.setArmed(true)
    }

    fun buildBatteryExemptionIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
        }

    fun buildDndAccessSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
}