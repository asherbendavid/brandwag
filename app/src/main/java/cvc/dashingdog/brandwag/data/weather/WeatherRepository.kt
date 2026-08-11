package cvc.dashingdog.brandwag.data.weather

import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class WeatherRepository(
    private val api: OpenMeteoApiService,
    private val gustLookaheadHours: Int = 5,
    private val gustThresholdKmh: Double = 40.0 // placeholder - real value belongs in Settings, per REQUIREMENTS.md
) {

    /**
     * Fetches and fully parses one check. Every branch from the Step 3 design
     * discussion is represented explicitly - nothing here should silently
     * collapse a failure into a "clear" result.
     */
    suspend fun fetchAndEvaluate(latitude: Double, longitude: Double): FetchResult {
        val response = try {
            api.getForecast(
                latitude = latitude,
                longitude = longitude,
                models = OpenMeteoApiService.QUORUM_MODELS_PARAM,
                hourly = OpenMeteoApiService.HOURLY_VARS,
                daily = OpenMeteoApiService.DAILY_VARS
            )
        } catch (e: HttpException) {
            return FetchResult.Failure.HttpError(e.code())
        } catch (e: IOException) {
            // Covers timeouts and no-connectivity - both surface as IOException from OkHttp/Retrofit.
            return FetchResult.Failure.NetworkError(e)
        } catch (e: SerializationException) {
            return FetchResult.Failure.MalformedResponse(e)
        }

        return try {
            val nowIso = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            val quorum = OpenMeteoParser.parseWindQuorum(
                response = response,
                nowIso = nowIso,
                lookaheadHours = gustLookaheadHours,
                gustThresholdKmh = gustThresholdKmh
            )
            val daily = OpenMeteoParser.parseDaily(response)
            FetchResult.Success(ParsedWeatherData(quorum, daily))
        } catch (e: IllegalStateException) {
            FetchResult.Failure.UnexpectedSchema(e.message ?: "Unknown schema error")
        }
    }
}