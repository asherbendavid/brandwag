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