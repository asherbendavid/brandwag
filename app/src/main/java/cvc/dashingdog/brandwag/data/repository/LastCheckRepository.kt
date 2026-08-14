package cvc.dashingdog.brandwag.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cvc.dashingdog.brandwag.data.model.CheckOutcome
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.lastCheckDataStore: DataStore<Preferences> by preferencesDataStore(name = "last_check")
private val LAST_CHECK_KEY = stringPreferencesKey("last_check_json")

class LastCheckRepository(
    private val dataStore: DataStore<Preferences>,
    private val logger: Logger = AndroidLogger
) {

    constructor(context: Context) : this(context.lastCheckDataStore)

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TAG = "LastCheckRepository"
    }

    suspend fun recordOutcome(outcome: CheckOutcome): Boolean {
        val stored = getLastCheck()
        if (stored != null && outcome.timestamp <= stored.timestamp) {
            logger.warn(TAG, "Stale write rejected: incoming=${outcome.timestamp}, stored=${stored.timestamp}")
            return false
        }
        dataStore.edit { prefs ->
            prefs[LAST_CHECK_KEY] = json.encodeToString(outcome)
        }
        return true
    }

    suspend fun getLastCheck(): CheckOutcome? {
        val prefs = dataStore.data.first()
        val raw = prefs[LAST_CHECK_KEY] ?: return null
        return json.decodeFromString(raw)
    }
}