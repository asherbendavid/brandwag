package cvc.dashingdog.brandwag.domain

import cvc.dashingdog.brandwag.data.model.BurnState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class BurnStateLogicTest {

    private val today = LocalDate.of(2026, 8, 13)
    private val yesterday = today.minusDays(1)
    private val tomorrow = today.plusDays(1)

    // --- resolveBurnState ---

    @Test
    fun `idle state resolves unchanged`() {
        val stored = BurnState.IDLE
        val result = resolveBurnState(stored, today)
        assertEquals(BurnState.IDLE, result)
    }

    @Test
    fun `armed today resolves unchanged`() {
        val stored = BurnState(armed = true, armedDate = today)
        val result = resolveBurnState(stored, today)
        assertEquals(stored, result)
    }

    @Test
    fun `armed yesterday resolves to idle - rollover`() {
        val stored = BurnState(armed = true, armedDate = yesterday)
        val result = resolveBurnState(stored, today)
        assertEquals(BurnState.IDLE, result)
    }

    @Test
    fun `armed with future armedDate resolves to idle - clock skew defensive disarm`() {
        val stored = BurnState(armed = true, armedDate = tomorrow)
        val result = resolveBurnState(stored, today)
        assertEquals(BurnState.IDLE, result)
    }

    @Test
    fun `idle with non-null armedDate resolves to idle - defensive normalize`() {
        // Shouldn't happen via normal code paths, but guards against a corrupted
        // or hand-edited stored value.
        val stored = BurnState(armed = false, armedDate = yesterday)
        val result = resolveBurnState(stored, today)
        assertEquals(BurnState.IDLE, result)
    }

    // --- applyBurnToggle ---

    @Test
    fun `arming from idle sets armed true and armedDate today`() {
        val result = applyBurnToggle(BurnState.IDLE, requestedArmed = true, today = today)
        assertEquals(BurnState(armed = true, armedDate = today), result)
    }

    @Test
    fun `disarming from armed sets armed false and armedDate null`() {
        val current = BurnState(armed = true, armedDate = today)
        val result = applyBurnToggle(current, requestedArmed = false, today = today)
        assertEquals(BurnState.IDLE, result)
    }

    @Test
    fun `re-arming while already armed today is a no-op`() {
        val current = BurnState(armed = true, armedDate = today)
        val result = applyBurnToggle(current, requestedArmed = true, today = today)
        assertEquals(current, result)
    }

    @Test
    fun `disarming while already idle is a no-op`() {
        val result = applyBurnToggle(BurnState.IDLE, requestedArmed = false, today = today)
        assertEquals(BurnState.IDLE, result)
    }

    @Test
    fun `arm then disarm then arm same day - armedDate stays today throughout`() {
        var state = applyBurnToggle(BurnState.IDLE, requestedArmed = true, today = today)
        assertEquals(today, state.armedDate)

        state = applyBurnToggle(state, requestedArmed = false, today = today)
        assertEquals(BurnState.IDLE, state)

        state = applyBurnToggle(state, requestedArmed = true, today = today)
        assertEquals(BurnState(armed = true, armedDate = today), state)
    }

    @Test
    fun `arming when stored state is stale rollover still arms cleanly for today`() {
        // Simulates the repository's resolve-then-apply contract: caller resolves
        // the stale state first, THEN calls applyBurnToggle with the resolved (idle) state.
        val stale = BurnState(armed = true, armedDate = yesterday)
        val resolved = resolveBurnState(stale, today)
        val result = applyBurnToggle(resolved, requestedArmed = true, today = today)
        assertEquals(BurnState(armed = true, armedDate = today), result)
    }
}