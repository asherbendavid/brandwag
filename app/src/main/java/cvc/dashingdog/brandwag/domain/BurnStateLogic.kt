package cvc.dashingdog.brandwag.domain

import cvc.dashingdog.brandwag.data.model.BurnState
import java.time.LocalDate

/** Normalizes stored state against "today". Called at every read site - scheduler,
 *  UI, and check logic - not just from a midnight alarm. */
fun resolveBurnState(stored: BurnState, today: LocalDate): BurnState {
    if (!stored.armed) {
        return if (stored.armedDate == null) stored else BurnState.IDLE
    }
    val armedDate = stored.armedDate ?: return BurnState.IDLE // invalid state, fail safe

    return when {
        armedDate.isBefore(today) -> BurnState.IDLE  // rollover
        armedDate.isAfter(today) -> BurnState.IDLE   // clock skew, defensive disarm
        else -> stored                                // valid, armed today
    }
}

/** Applies a requested arm/disarm against already-resolved current state.
 *  Caller must resolve `current` against `today` first (see repository). */
fun applyBurnToggle(current: BurnState, requestedArmed: Boolean, today: LocalDate): BurnState {
    return when {
        requestedArmed && current.armed -> current      // no-op, already armed today
        requestedArmed && !current.armed -> BurnState(armed = true, armedDate = today)
        !requestedArmed && !current.armed -> current    // no-op, already idle
        else -> BurnState.IDLE                            // disarming
    }
}