package cvc.dashingdog.brandwag.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cvc.dashingdog.brandwag.data.model.BrandwagSettings
import cvc.dashingdog.brandwag.data.model.SettingsUpdateResult
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
private val SETTINGS_KEY = stringPreferencesKey("settings_json")

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.settingsDataStore)

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getSettings(): BrandwagSettings {
        val prefs = dataStore.data.first()
        val raw = prefs[SETTINGS_KEY] ?: return BrandwagSettings()
        return json.decodeFromString(raw)
    }

    suspend fun updateSettings(settings: BrandwagSettings): SettingsUpdateResult {
        val violations = validate(settings)
        if (violations.isNotEmpty()) return SettingsUpdateResult.Invalid(violations)

        dataStore.edit { prefs ->
            prefs[SETTINGS_KEY] = json.encodeToString(settings)
        }
        return SettingsUpdateResult.Success
    }

    private fun validate(s: BrandwagSettings): List<String> {
        val violations = mutableListOf<String>()
        if (s.latitude !in -90.0..90.0) violations += "Latitude out of range"
        if (s.longitude !in -180.0..180.0) violations += "Longitude out of range"
        if (s.morningTempThreshold <= 0) violations += "morningTempThreshold must be positive"
        if (s.morningTempSevereThreshold < s.morningTempThreshold)
            violations += "morningTempSevereThreshold must be >= morningTempThreshold"
        if (s.morningRainSevereThreshold <= 0) violations += "morningRainSevereThreshold must be positive"
        if (s.burnGustThreshold <= 0) violations += "burnGustThreshold must be positive"
        if (s.gustLookaheadHours <= 0) violations += "gustLookaheadHours must be positive"
        return violations
    }
}