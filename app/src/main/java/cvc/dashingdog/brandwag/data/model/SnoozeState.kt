package cvc.dashingdog.brandwag.data.model

import cvc.dashingdog.brandwag.data.model.serializers.InstantSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Persisted snooze schedule. `active = false` is the rest state - IDLE
 * mirrors BurnState.IDLE's role as the canonical "nothing going on" value.
 *
 * `maxGustKmh` is carried here (not just in AlarmSoundingState) because a
 * new Alarm decision arriving while snoozed updates THIS value without
 * starting the service (see PHASE4 4b design) - the eventual re-sound needs
 * the latest reading, not whatever was current when Snooze was first tapped.
 */
@Serializable
data class SnoozeState(
    val active: Boolean,
    @Serializable(with = InstantSerializer::class) val until: Instant,
    val maxGustKmh: Double?
) {
    companion object {
        val IDLE = SnoozeState(active = false, until = Instant.EPOCH, maxGustKmh = null)
    }
}