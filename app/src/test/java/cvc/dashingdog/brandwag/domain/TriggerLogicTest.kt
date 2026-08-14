package cvc.dashingdog.brandwag.domain

import cvc.dashingdog.brandwag.data.model.BurnState
import cvc.dashingdog.brandwag.data.model.CheckOutcome
import cvc.dashingdog.brandwag.data.model.RawCheckValues
import cvc.dashingdog.brandwag.data.weather.GustQuorumModel
import cvc.dashingdog.brandwag.data.weather.ModelVote
import cvc.dashingdog.brandwag.data.weather.WindQuorumResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Plain JUnit - no Robolectric, no DataStore, no android.util.Log. TriggerLogic
 * has zero Android/IO dependencies, so every case here belongs in src/test/,
 * never src/androidTest/ (see PHASE2_HANDOFF.md's Windows DataStore rename
 * saga - not applicable here, but noting why per project convention).
 */
class TriggerLogicTest {

    private val now = Instant.parse("2026-08-14T12:00:00Z")

    private val armed = BurnState(armed = true, armedDate = LocalDate.of(2026, 8, 14))
    private val disarmed = BurnState(armed = false, armedDate = null)

    private fun trustedClear() = WindQuorumResult.Trusted(
        alarmTriggered = false,
        respondingModels = 5,
        dangerVotes = 0,
        votes = GustQuorumModel.entries.map { ModelVote(it, 20.0) }
    )

    private fun trustedDangerous(maxGust: Double = 65.0) = WindQuorumResult.Trusted(
        alarmTriggered = true,
        respondingModels = 5,
        dangerVotes = 4,
        votes = GustQuorumModel.entries.mapIndexed { i, m ->
            ModelVote(m, if (i == 0) maxGust else 55.0)
        }
    )

    private fun degraded() = WindQuorumResult.Degraded(
        respondingModels = 2,
        votes = listOf(
            ModelVote(GustQuorumModel.ECMWF_IFS, 40.0),
            ModelVote(GustQuorumModel.GFS, null)
        )
    )

    private fun dangerousOutcome(timestamp: Instant) = CheckOutcome.Dangerous(
        respondingModels = 5,
        rawValues = RawCheckValues(gustsByModel = emptyMap(), sustainedWindByModel = emptyMap()),
        timestamp = timestamp
    )

    private fun clearOutcome(timestamp: Instant) = CheckOutcome.Clear(
        respondingModels = 5,
        rawValues = RawCheckValues(gustsByModel = emptyMap(), sustainedWindByModel = emptyMap()),
        timestamp = timestamp
    )

    // --- Branch 1: Disarmed (must-never: never fire while disarmed) ---

    @Test
    fun `disarmed with clear quorum returns Disarmed`() {
        val result = TriggerLogic.evaluate(disarmed, trustedClear(), null, now)
        assertEquals(TriggerDecision.Disarmed, result)
    }

    @Test
    fun `must-never - disarmed with dangerous quorum still returns Disarmed, never Alarm`() {
        val result = TriggerLogic.evaluate(disarmed, trustedDangerous(), null, now)
        assertEquals(TriggerDecision.Disarmed, result)
    }

    @Test
    fun `must-never - disarmed with degraded quorum still returns Disarmed`() {
        val result = TriggerLogic.evaluate(disarmed, degraded(), null, now)
        assertEquals(TriggerDecision.Disarmed, result)
    }

    // --- Branch 2: Degraded (must-never: Degraded is never read as Clear) ---

    @Test
    fun `must-never - armed with degraded quorum returns InsufficientData, not Clear`() {
        val result = TriggerLogic.evaluate(armed, degraded(), null, now)
        assertEquals(TriggerDecision.InsufficientData, result)
        assertTrue(result != TriggerDecision.Clear)
    }

    @Test
    fun `degraded quorum returns InsufficientData regardless of lastOutcome`() {
        val result = TriggerLogic.evaluate(
            armed, degraded(), dangerousOutcome(now.minusSeconds(60)), now
        )
        assertEquals(TriggerDecision.InsufficientData, result)
    }

    // --- Branch 3: Clear ---

    @Test
    fun `armed, trusted, no danger returns Clear`() {
        val result = TriggerLogic.evaluate(armed, trustedClear(), null, now)
        assertEquals(TriggerDecision.Clear, result)
    }

    // --- Branch 4/5: Alarm vs Suppressed cooldown boundary ---

    @Test
    fun `armed, trusted, dangerous, no prior outcome returns Alarm with max gust`() {
        val result = TriggerLogic.evaluate(armed, trustedDangerous(maxGust = 71.0), null, now)
        assertEquals(TriggerDecision.Alarm(71.0), result)
    }

    @Test
    fun `armed, trusted, dangerous, prior outcome was Clear (not Dangerous) returns Alarm`() {
        // Must not suppress based on ANY prior outcome - only a prior Dangerous counts.
        val result = TriggerLogic.evaluate(
            armed, trustedDangerous(), clearOutcome(now.minusSeconds(30)), now
        )
        assertTrue(result is TriggerDecision.Alarm)
    }

    @Test
    fun `dedup - prior Dangerous outcome 1 minute ago is within cooldown, returns Suppressed`() {
        val priorTimestamp = now.minus(Duration.ofMinutes(1))
        val result = TriggerLogic.evaluate(
            armed, trustedDangerous(), dangerousOutcome(priorTimestamp), now
        )
        assertEquals(TriggerDecision.Suppressed(priorTimestamp), result)
    }

    @Test
    fun `dedup - prior Dangerous outcome exactly at cooldown boundary is NOT suppressed`() {
        // Boundary is strictly-less-than, so exactly-equal must fall through to Alarm.
        val priorTimestamp = now.minus(TriggerLogic.DEFAULT_COOLDOWN)
        val result = TriggerLogic.evaluate(
            armed, trustedDangerous(), dangerousOutcome(priorTimestamp), now
        )
        assertTrue(
            "Expected Alarm at exact cooldown boundary, got $result",
            result is TriggerDecision.Alarm
        )
    }

    @Test
    fun `dedup - prior Dangerous outcome just outside cooldown returns Alarm again`() {
        // The "keep re-alerting every hourly poll while genuinely dangerous" case.
        val priorTimestamp = now.minus(TriggerLogic.DEFAULT_COOLDOWN).minusSeconds(1)
        val result = TriggerLogic.evaluate(
            armed, trustedDangerous(), dangerousOutcome(priorTimestamp), now
        )
        assertTrue(result is TriggerDecision.Alarm)
    }

    @Test
    fun `dedup - handles clock skew where lastOutcome timestamp is after now`() {
        // Duration.between(..).abs() should treat this as within cooldown, not crash
        // or silently skip suppression.
        val futureTimestamp = now.plusSeconds(30)
        val result = TriggerLogic.evaluate(
            armed, trustedDangerous(), dangerousOutcome(futureTimestamp), now
        )
        assertEquals(TriggerDecision.Suppressed(futureTimestamp), result)
    }

    // --- Re-arm same day ---

    @Test
    fun `re-arm same day - armed evaluation ignores whether burn was previously toggled off`() {
        // TriggerLogic has no session concept; re-arming just means the caller
        // passes armed=true again on the next poll. Cooldown is still purely
        // timestamp-based against lastOutcome, unaffected by the toggle history.
        val reArmed = BurnState(armed = true, armedDate = LocalDate.of(2026, 8, 14))
        val result = TriggerLogic.evaluate(reArmed, trustedDangerous(), null, now)
        assertTrue(result is TriggerDecision.Alarm)
    }

    @Test
    fun `re-arm same day - cooldown from before a disarm still applies after re-arming`() {
        // Deliberate: disarming and re-arming within the cooldown window does NOT
        // reset the debounce, since it's a system-level dedup against poll timing,
        // not a per-session concept.
        val priorTimestamp = now.minus(Duration.ofMinutes(2))
        val reArmed = BurnState(armed = true, armedDate = LocalDate.of(2026, 8, 14))
        val result = TriggerLogic.evaluate(
            reArmed, trustedDangerous(), dangerousOutcome(priorTimestamp), now
        )
        assertEquals(TriggerDecision.Suppressed(priorTimestamp), result)
    }
}