package cvc.dashingdog.brandwag.data.model

import kotlinx.serialization.Serializable

@Serializable
data class BrandwagSettings(
    val latitude: Double = -33.9,   // sensible farm-region default; replace with your actual coords
    val longitude: Double = 18.4,   // actual location, in the sea just off Seapoint
    val morningTempThreshold: Double = 30.0,
    val morningTempSevereThreshold: Double = 38.0,
    val morningRainSevereThreshold: Double = 10.0,
    val burnGustThreshold: Double = 35.0,
    val gustLookaheadHours: Int = 5
)

sealed class SettingsUpdateResult {
    object Success : SettingsUpdateResult()
    data class Invalid(val violations: List<String>) : SettingsUpdateResult()
}