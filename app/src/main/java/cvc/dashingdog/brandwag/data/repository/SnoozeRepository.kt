package cvc.dashingdog.brandwag.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cvc.dashingdog.brandwag.data.model.SnoozeState
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

private val Context.snoozeStateDataStore: DataStore<Preferences> by preferencesDataStore(name = "snooze_state")
private val SNOOZE_STATE_KEY = stringPreferencesKey("snooze_state_json")

class SnoozeRepository(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.snoozeStateDataStore)

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getSnoozeState(): SnoozeState {
        val prefs = dataStore.data.first()
        val raw = prefs[SNOOZE_STATE_KEY] ?: return SnoozeState.IDLE
        return json.decodeFromString(raw)
    }

    /**
     * Schedules or reschedules a snooze "from now" - a second Snooze tap
     * before the first fires overwrites `until` with a fresh now+duration,
     * per Chris's confirmed "reschedule from now" behavior. Does not itself
     * touch AlarmManager - callers (AlarmScheduler) own that half.
     */
    suspend fun schedule(until: Instant, maxGustKmh: Double) {
        writeRaw(SnoozeState(active = true, until = until, maxGustKmh = maxGustKmh))
    }

    /**
     * Updates only the gust reading of an already-active snooze, without
     * touching `until` or `active` - the "new Alarm decision arrives while
     * snoozed" case. Caller (AlarmCheckPipeline) is expected to have already
     * confirmed a snooze is active before calling this; if it isn't, this is
     * a no-op rather than accidentally creating a new snooze schedule.
     */
    suspend fun updateGustIfActive(maxGustKmh: Double) {
        val current = getSnoozeState()
        if (current.active) {
            writeRaw(current.copy(maxGustKmh = maxGustKmh))
        }
    }

    /** Disarm, or the snooze callback firing - clears back to IDLE entirely. */
    suspend fun clear() {
        writeRaw(SnoozeState.IDLE)
    }

    private suspend fun writeRaw(state: SnoozeState) {
        dataStore.edit { prefs ->
            prefs[SNOOZE_STATE_KEY] = json.encodeToString(state)
        }
    }
}