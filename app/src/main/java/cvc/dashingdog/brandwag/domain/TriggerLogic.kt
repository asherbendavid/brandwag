package cvc.dashingdog.brandwag.domain

import cvc.dashingdog.brandwag.data.model.BurnState
import cvc.dashingdog.brandwag.data.model.CheckOutcome
import cvc.dashingdog.brandwag.data.weather.WindQuorumResult
import java.time.Duration
import java.time.Instant

/**
 * Outcome of evaluating the burn-day wind-gust alarm for one check.
 *
 * Deliberately flat (not nested under a NoAlarm parent) - five sibling cases
 * read cleanest in a `when` and keep the must-never distinctions (Disarmed,
 * InsufficientData, Suppressed are each their OWN thing, not flavors of "no
 * alarm") visible at the type level rather than buried in a field.
 */
sealed class TriggerDecision {
    /** Burn toggle is off. Must-never: this must always win over any quorum result. */
    object Disarmed : TriggerDecision()

    /**
     * Quorum result was Degraded (either <3 models responding, or - per the
     * OpenMeteoParser fix - a clipped lookahead window that zeroed every model's
     * vote). Must-never: this must never be treated as Clear.
     */
    object InsufficientData : TriggerDecision()

    /** Trusted quorum, no dangerous gust found in the lookahead window. */
    object Clear : TriggerDecision()

    /** Trusted quorum, dangerous gust found, and not currently suppressed by the cooldown. */
    data class Alarm(val maxGustKmh: Double) : TriggerDecision()

    /**
     * Trusted quorum, dangerous gust found, but the last recorded outcome was
     * also Dangerous within [TriggerLogic.DEFAULT_COOLDOWN] of now. This is a
     * system-level debounce for near-simultaneous poll triggers (manual refresh
     * racing a scheduled check) - NOT user-facing snooze, which is separate
     * Phase 4 scope with its own scheduled re-sound independent of this value.
     */
    data class Suppressed(val lastAlarmAt: Instant) : TriggerDecision()
}

/**
 * Pure evaluation of the burn-day wind-gust alarm. No AlarmManager, no
 * repository reads - all state the decision depends on is passed in, so this
 * is trivially testable with plain JUnit and hand-built fixtures.
 *
 * Precondition: [burnState] must already have had `resolveBurnState()` applied
 * by the caller. This function trusts `burnState.armed` as current and does
 * not re-derive rollover/staleness itself.
 */
object TriggerLogic {

    /**
     * Short system-level debounce window, not a user-facing alert-fatigue
     * cooldown. Armed-mode polling is hourly (REQUIREMENTS.md), so this only
     * needs to be long enough to catch two triggers landing seconds apart -
     * a genuine hourly re-poll that's still dangerous must fall OUTSIDE this
     * window and re-fire normally (see Suppressed vs Alarm branch tests).
     */
    val DEFAULT_COOLDOWN: Duration = Duration.ofMinutes(5)

    fun evaluate(
        burnState: BurnState,
        quorumResult: WindQuorumResult,
        lastOutcome: CheckOutcome?,
        now: Instant,
        cooldown: Duration = DEFAULT_COOLDOWN
    ): TriggerDecision {
        if (!burnState.armed) {
            return TriggerDecision.Disarmed
        }

        return when (quorumResult) {
            is WindQuorumResult.Degraded -> TriggerDecision.InsufficientData

            is WindQuorumResult.Trusted -> {
                if (!quorumResult.alarmTriggered) {
                    TriggerDecision.Clear
                } else {
                    evaluateAlarmWorthy(quorumResult, lastOutcome, now, cooldown)
                }
            }
        }
    }

    private fun evaluateAlarmWorthy(
        quorumResult: WindQuorumResult.Trusted,
        lastOutcome: CheckOutcome?,
        now: Instant,
        cooldown: Duration
    ): TriggerDecision {
        val lastDangerous = lastOutcome as? CheckOutcome.Dangerous

        val withinCooldown = lastDangerous != null &&
                Duration.between(lastDangerous.timestamp, now).abs() < cooldown

        if (withinCooldown) {
            return TriggerDecision.Suppressed(lastDangerous!!.timestamp)
        }

        val maxGustKmh = quorumResult.votes.mapNotNull { it.maxGustKmh }.maxOrNull()
            ?: error(
                "Trusted result with alarmTriggered=true but no model has a non-null " +
                        "maxGustKmh - this indicates a bug upstream in quorum evaluation, " +
                        "not a real-world state this function should silently paper over."
            )

        return TriggerDecision.Alarm(maxGustKmh)
    }
}