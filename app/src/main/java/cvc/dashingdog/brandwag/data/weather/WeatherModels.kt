package cvc.dashingdog.brandwag.data.weather

/**
 * The five models used for the wind-gust quorum, per weather-api-decisions.md +
 * the model-selection discussion in the Phase 1 handoff notes.
 *
 * `apiParam` is the exact string sent in `models=`. `displayLabel` is what shows
 * in UI/logs - MET_NORWAY is deliberately labelled "Open-Meteo Default" rather
 * than implying genuine Nordic-model skill over South Africa: MET Norway, KNMI,
 * DMI, and GeoSphere Austria all returned byte-identical values in testing
 * (2026-08-11), meaning Open-Meteo is serving some shared fallback for locations
 * outside all four services' native domains, not a real Nordic forecast. We
 * request MET Norway specifically (rather than `best_match`) because `best_match`
 * is a static geographic lookup that could resolve to a different model in future
 * without notice - naming one of the four fixed identical models is more stable.
 *
 * IMPORTANT: verify these exact apiParam strings against Open-Meteo's current
 * model list (https://open-meteo.com/en/docs) before first real use - model
 * identifiers occasionally change, and none of these were fetched live in this
 * chat, only reasoned about from the screenshot's display names.
 */
enum class GustQuorumModel(val apiParam: String, val displayLabel: String) {
    ECMWF_IFS("ecmwf_ifs025", "ECMWF IFS"),
    GFS("gfs_seamless", "GFS"),
    ICON("icon_seamless", "ICON"),
    GEM("gem_seamless", "GEM"),
    MET_NORWAY("metno_seamless", "Open-Meteo Default")
}

/** Minimum number of models that must return a valid gust value to trust a quorum result. */
const val GUST_QUORUM_FLOOR = 3

/** One model's contribution to the wind-gust quorum for a single check. */
data class ModelVote(
    val model: GustQuorumModel,
    /** Max gust (km/h) found in the lookahead window, or null if this model had no usable data. */
    val maxGustKmh: Double?
) {
    fun isDangerous(gustThresholdKmh: Double): Boolean? =
        maxGustKmh?.let { it >= gustThresholdKmh }
}

/**
 * Result of evaluating the burn-day wind-gust quorum for one check.
 *
 * Degraded is a DISTINCT case from "no danger" - callers must not collapse it
 * into a boolean. Per the must-never list, a degraded/uncertain read must never
 * be treated as "all clear". Notification/retry/escalation behavior for the
 * Degraded case is deferred to the alarm-delivery phase; this type just makes
 * sure the information survives to reach it.
 */
sealed class WindQuorumResult {
    data class Trusted(
        val alarmTriggered: Boolean,
        val respondingModels: Int,
        val dangerVotes: Int,
        val votes: List<ModelVote>
    ) : WindQuorumResult()

    data class Degraded(
        val respondingModels: Int,
        val votes: List<ModelVote>
    ) : WindQuorumResult()
}

/** Parsed daily forecast entry for the home screen card stack / 8am temp+rain check. */
data class DailyForecast(
    val date: String,
    val maxTempC: Double?,
    val precipitationMm: Double?,
    val weatherCode: Int?
)

/** Fully parsed, ready-to-use result of one fetch+parse cycle. */
data class ParsedWeatherData(
    val windQuorum: WindQuorumResult,
    val daily: List<DailyForecast>
)

/**
 * Top-level outcome of a fetch attempt. Network/HTTP/parse failures are kept
 * distinct from each other (not collapsed into one generic "failed") so callers
 * and tests can tell a timeout apart from a schema mismatch - useful for logging
 * and for the must-never rule that a failed poll must never look like "all clear".
 */
sealed class FetchResult {
    data class Success(val data: ParsedWeatherData) : FetchResult()
    sealed class Failure : FetchResult() {
        data class NetworkError(val cause: Throwable) : Failure()
        data class HttpError(val code: Int) : Failure()
        data class MalformedResponse(val cause: Throwable) : Failure()
        data class UnexpectedSchema(val message: String) : Failure()
    }
}