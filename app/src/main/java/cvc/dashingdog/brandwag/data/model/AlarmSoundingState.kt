package cvc.dashingdog.brandwag.data.model

import kotlinx.serialization.Serializable

/**
 * Persisted breadcrumb answering "was the alarm actively sounding at the
 * moment this process died," independent of SnoozeState. Written by
 * AlarmForegroundService at the start/end of its sound loop.
 *
 * Distinct from SnoozeState because a mid-alarm process death (kill/reboot)
 * is a DIFFERENT case from a deliberate snooze - this flag being left true
 * on next launch is precisely what tells Phase 4d's BOOT_COMPLETED receiver
 * "this needs to be treated as a snooze" per Chris's rule (reboot mid-alarm
 * -> re-fire after boot, post snooze-delay), as opposed to a clean shutdown
 * with nothing pending.
 */
@Serializable
data class AlarmSoundingState(
    val isSounding: Boolean,
    val maxGustKmh: Double?
) {
    companion object {
        val IDLE = AlarmSoundingState(isSounding = false, maxGustKmh = null)
    }
}