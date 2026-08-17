package cvc.dashingdog.brandwag.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cvc.dashingdog.brandwag.data.model.AlarmSoundingState
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.alarmSoundingDataStore: DataStore<Preferences> by preferencesDataStore(name = "alarm_sounding_state")
private val ALARM_SOUNDING_KEY = stringPreferencesKey("alarm_sounding_json")

class AlarmSoundingRepository(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.alarmSoundingDataStore)

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getState(): AlarmSoundingState {
        val prefs = dataStore.data.first()
        val raw = prefs[ALARM_SOUNDING_KEY] ?: return AlarmSoundingState.IDLE
        return json.decodeFromString(raw)
    }

    suspend fun setSounding(maxGustKmh: Double) {
        writeRaw(AlarmSoundingState(isSounding = true, maxGustKmh = maxGustKmh))
    }

    suspend fun setIdle() {
        writeRaw(AlarmSoundingState.IDLE)
    }

    private suspend fun writeRaw(state: AlarmSoundingState) {
        dataStore.edit { prefs ->
            prefs[ALARM_SOUNDING_KEY] = json.encodeToString(state)
        }
    }
}