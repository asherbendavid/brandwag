package cvc.dashingdog.brandwag.data.weather

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Raw Open-Meteo /v1/forecast response shape.
 *
 * IMPORTANT: when multiple `models=` are requested, hourly variable fields are
 * suffixed per model as FLAT sibling keys inside `hourly` - e.g.
 * `hourly.wind_gusts_10m_ecmwf_ifs025` sits right next to `hourly.time`, NOT
 * nested under anything. The exact set of keys depends on which models were
 * requested, so we can't map it to a fixed data class at compile time - `hourly`
 * is held as a raw JsonObject and named fields are pulled out by exact key in
 * OpenMeteoParser. A missing/renamed key throws there rather than silently
 * producing nulls everywhere, per the "never treat failure as clear" rule.
 *
 * `daily` is NOT model-suffixed for the fields Brandwag needs (temperature_2m_max,
 * precipitation_sum, weather_code) - confirmed against a live response on
 * 2026-08-11. If a future Open-Meteo change starts suffixing daily fields too,
 * this will throw a MissingFieldException on parse rather than silently returning
 * wrong data - by design.
 */
@Serializable
data class OpenMeteoResponseDto(
    val latitude: Double,
    val longitude: Double,
    val hourly: JsonObject? = null,
    val daily: DailyDto? = null
)

@Serializable
data class DailyDto(
    val time: List<String> = emptyList(),
    @kotlinx.serialization.SerialName("temperature_2m_max")
    val temperatureMax: List<Double?> = emptyList(),
    @kotlinx.serialization.SerialName("precipitation_sum")
    val precipitationSum: List<Double?> = emptyList(),
    @kotlinx.serialization.SerialName("weather_code")
    val weatherCode: List<Int?> = emptyList()
)