package cvc.dashingdog.brandwag.data.model

import kotlinx.serialization.Serializable

/**
 * Tracks the two-strike blip state for a single revocable safety permission
 * (battery optimization exemption OR notification policy/DND access - each
 * gets its own independent instance of this, never shared).
 *
 * Strike sequence:
 *   NONE -> (missing on hourly check) -> BLIPPED -> (still missing next check) -> caller auto-disarms and resets to NONE
 *   BLIPPED -> (present again on next check) -> silently resets to NONE (no notification)
 *
 * Deliberately NOT a Boolean - a raw flag can't distinguish "never blipped"
 * from "blipped once, just recovered" from a caller's point of view, and the
 * silent-recovery rule (Part 2 of PHASE4_FULL_SUMMARY.md) depends on knowing
 * which case we're in.
 */
@Serializable
data class SafetyPermissionBlip(
    val state: BlipState,
    val firstMissingAt: Long? // epoch millis; null when state == NONE
) {
    @Serializable
    enum class BlipState { NONE, BLIPPED }

    companion object {
        val NONE = SafetyPermissionBlip(state = BlipState.NONE, firstMissingAt = null)
    }
}