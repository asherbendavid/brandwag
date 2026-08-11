package cvc.dashingdog.brandwag.data.weather

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Pure functions turning an [OpenMeteoResponseDto] into domain models. Deliberately
 * has no Retrofit/network awareness so it can be unit tested with plain in-memory
 * JSON fixtures - see OpenMeteoParserTest.
 */
object OpenMeteoParser {

    /** Parses nowIso - accepts either with or without seconds, since callers may pass either. */
    private val parseFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    /**
     * Formats the truncated time to match Open-Meteo's hourly.time entries exactly,
     * e.g. "2026-08-11T00:00" - no seconds. NOTE: ISO_LOCAL_DATE_TIME is NOT safe for
     * this - LocalDateTime always has a seconds field present internally (even after
     * withSecond(0)), so ISO_LOCAL_DATE_TIME's "optional" seconds section prints
     * regardless of value, producing "...T00:00:00" and silently failing every match
     * against hourly.time's "...T00:00". Learned this the hard way - see Phase 1
     * handoff notes.
     */
    private val hourlyTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    /**
     * Extracts the max gust value per model within [lookaheadHours] of [nowIso]
     * (Open-Meteo's own hourly time string, top-of-hour truncated) and runs the
     * quorum calculation.
     *
     * Throws IllegalStateException (caught by the repository as UnexpectedSchema)
     * if `hourly.time` is missing entirely - that's a genuine schema break, not a
     * per-model gap.
     */
    fun parseWindQuorum(
        response: OpenMeteoResponseDto,
        nowIso: String,
        lookaheadHours: Int,
        gustThresholdKmh: Double
    ): WindQuorumResult {
        val hourly = response.hourly
            ?: throw IllegalStateException("Response has no 'hourly' block")

        val timeArray = (hourly["time"] as? JsonArray)
            ?: throw IllegalStateException("hourly.time missing or not an array")

        val times = timeArray.map { it.jsonPrimitive.content }
        val truncatedNow = truncateToHour(nowIso)
        val startIndex = times.indexOf(truncatedNow).takeIf { it >= 0 }
            ?: throw IllegalStateException(
                "Could not locate current time '$truncatedNow' (from '$nowIso') in hourly.time. " +
                        "hourly.time sample: ${times.take(3)}"
            )

        val endIndexExclusive = (startIndex + lookaheadHours).coerceAtMost(times.size)

        val votes = GustQuorumModel.entries.map { model ->
            val key = "wind_gusts_10m_${model.apiParam}"
            val valuesArray = hourly[key] as? JsonArray
            val maxGust = valuesArray
                ?.subListSafe(startIndex, endIndexExclusive)
                ?.mapNotNull { it.jsonPrimitive.doubleOrNull }
                ?.maxOrNull()
            ModelVote(model, maxGust)
        }

        val respondingVotes = votes.filter { it.maxGustKmh != null }
        val respondingCount = respondingVotes.size

        return if (respondingCount < GUST_QUORUM_FLOOR) {
            WindQuorumResult.Degraded(respondingCount, votes)
        } else {
            val dangerCount = respondingVotes.count { it.isDangerous(gustThresholdKmh) == true }
            val alarm = dangerCount > respondingCount / 2.0
            WindQuorumResult.Trusted(alarm, respondingCount, dangerCount, votes)
        }
    }

    fun parseDaily(response: OpenMeteoResponseDto): List<DailyForecast> {
        val daily = response.daily ?: return emptyList()
        return daily.time.indices.map { i ->
            DailyForecast(
                date = daily.time[i],
                maxTempC = daily.temperatureMax.getOrNull(i),
                precipitationMm = daily.precipitationSum.getOrNull(i),
                weatherCode = daily.weatherCode.getOrNull(i)
            )
        }
    }

    /**
     * Truncates [nowIso] down to the top of the hour and formats it to match
     * hourly.time's exact no-seconds shape - per weather-api-decisions.md,
     * `current.time` updates every 15 minutes but hourly.time[] entries only ever
     * land on :00. Matching the untruncated string silently fails 3 out of 4
     * requests, so this truncates before comparing rather than doing an exact
     * string match. Throws (rather than silently falling back to the raw string)
     * if nowIso can't be parsed - a malformed "current time" input is a real bug
     * worth surfacing, not something to paper over.
     */
    private fun truncateToHour(nowIso: String): String =
        LocalDateTime.parse(nowIso, parseFormatter)
            .withMinute(0).withSecond(0).withNano(0)
            .format(hourlyTimeFormatter)

    private fun <T> List<T>.subListSafe(from: Int, to: Int): List<T> {
        if (from >= size || from >= to) return emptyList()
        return subList(from, to.coerceAtMost(size))
    }
}