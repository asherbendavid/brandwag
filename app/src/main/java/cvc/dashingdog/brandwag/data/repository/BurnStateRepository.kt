package cvc.dashingdog.brandwag.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cvc.dashingdog.brandwag.data.model.BurnState
import cvc.dashingdog.brandwag.domain.applyBurnToggle
import cvc.dashingdog.brandwag.domain.resolveBurnState
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

private val Context.burnStateDataStore: DataStore<Preferences> by preferencesDataStore(name = "burn_state")
private val BURN_STATE_KEY = stringPreferencesKey("burn_state_json")

class BurnStateRepository(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.burnStateDataStore)

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getBurnState(today: LocalDate = LocalDate.now()): BurnState {
        return resolveBurnState(readRaw(), today)
    }

    /**
     * Returns the stored state EXACTLY as persisted, with no resolveBurnState()
     * interpretation applied. Added for Phase 4d's boot-recovery clock-sanity
     * check: resolveBurnState()'s fail-safe branches return BurnState.IDLE,
     * which DISCARDS armedDate - a caller holding only that resolved result has
     * no way to compare the clock against the original armedDate anymore, since
     * it's already gone. Callers needing to reason about whether the clock
     * itself is trustworthy (rather than just "what's the current burn state")
     * must read the raw value first, BEFORE calling getBurnState().
     *
     * Does not resolve rollover/clock-skew - callers using this for anything
     * other than a pre-resolution sanity check should almost certainly be
     * calling getBurnState() instead.
     */
    suspend fun getRawBurnState(): BurnState {
        return readRaw()
    }

    suspend fun setArmed(requestedArmed: Boolean, today: LocalDate = LocalDate.now()): BurnState {
        val resolved = resolveBurnState(readRaw(), today) // resolve-then-apply
        val newState = applyBurnToggle(resolved, requestedArmed, today)
        writeRaw(newState)
        return newState
    }

    private suspend fun readRaw(): BurnState {
        val prefs = dataStore.data.first()
        val raw = prefs[BURN_STATE_KEY] ?: return BurnState.IDLE
        return json.decodeFromString(raw)
    }

    private suspend fun writeRaw(state: BurnState) {
        dataStore.edit { prefs ->
            prefs[BURN_STATE_KEY] = json.encodeToString(state)
        }
    }
}