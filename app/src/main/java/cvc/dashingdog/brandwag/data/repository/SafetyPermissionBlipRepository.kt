package cvc.dashingdog.brandwag.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cvc.dashingdog.brandwag.data.model.SafetyPermissionBlip
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private val Context.safetyPermissionBlipDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "safety_permission_blip"
)

/**
 * One DataStore file, two independent keys - battery-exemption and DND-access
 * blip state never read/write each other's key, so a stale read on one can
 * never be mistaken for the other (Chris's call: separate counters, separate
 * disarm triggers that both happen to call the same AlarmDisarmHandler path).
 */
class SafetyPermissionBlipRepository(
    private val context: Context,
    private val logger: Logger = AndroidLogger
) {
    enum class PermissionType(val key: String) {
        BATTERY_EXEMPTION("battery_exemption_blip"),
        DND_ACCESS("dnd_access_blip")
    }

    private fun keyFor(type: PermissionType) = stringPreferencesKey(type.key)

    suspend fun get(type: PermissionType): SafetyPermissionBlip {
        val json = context.safetyPermissionBlipDataStore.data.first()[keyFor(type)]
            ?: return SafetyPermissionBlip.NONE
        return try {
            Json.decodeFromString(json)
        } catch (e: Exception) {
            logger.warn("SafetyPermissionBlipRepository", "Corrupt blip state for ${type.key}, resetting to NONE: ${e.message}")
            SafetyPermissionBlip.NONE
        }
    }

    /**
     * Called each hourly stub-scheduler tick with the CURRENT live permission
     * check result. Returns the resulting blip state AND whether this call
     * just crossed into "should auto-disarm now" - caller (stub scheduler)
     * owns actually calling AlarmDisarmHandler, this repo only tracks state.
     */
    suspend fun recordCheck(type: PermissionType, isGranted: Boolean): BlipCheckResult {
        val current = get(type)
        val result = when {
            isGranted && current.state == SafetyPermissionBlip.BlipState.NONE ->
                BlipCheckResult(SafetyPermissionBlip.NONE, BlipTransition.STILL_OK)

            isGranted && current.state == SafetyPermissionBlip.BlipState.BLIPPED ->
                BlipCheckResult(SafetyPermissionBlip.NONE, BlipTransition.SILENTLY_RECOVERED)

            !isGranted && current.state == SafetyPermissionBlip.BlipState.NONE ->
                BlipCheckResult(
                    SafetyPermissionBlip(SafetyPermissionBlip.BlipState.BLIPPED, System.currentTimeMillis()),
                    BlipTransition.FIRST_BLIP
                )

            else -> // !isGranted && already BLIPPED
                BlipCheckResult(SafetyPermissionBlip.NONE, BlipTransition.SECOND_BLIP_DISARM)
        }
        set(type, result.newState)
        return result
    }

    private suspend fun set(type: PermissionType, value: SafetyPermissionBlip) {
        context.safetyPermissionBlipDataStore.edit { prefs ->
            prefs[keyFor(type)] = Json.encodeToString(value)
        }
    }

    data class BlipCheckResult(val newState: SafetyPermissionBlip, val transition: BlipTransition)

    enum class BlipTransition {
        STILL_OK,              // was fine, still fine - no notification
        SILENTLY_RECOVERED,    // was blipped, now fine - reset silently, no notification (Chris's explicit call)
        FIRST_BLIP,            // fine -> missing - notify "will auto-disarm if not restored"
        SECOND_BLIP_DISARM     // missing -> still missing - caller must auto-disarm + notify, state resets to NONE
    }
}