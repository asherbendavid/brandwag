package cvc.dashingdog.brandwag.data.weather

import retrofit2.http.GET
import retrofit2.http.Query

interface OpenMeteoApiService {

    @GET("v1/forecast")
    suspend fun getForecast(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("models") models: String,
        @Query("hourly") hourly: String,
        @Query("daily") daily: String,
        @Query("timezone") timezone: String = "auto"
    ): OpenMeteoResponseDto

    companion object {
        const val BASE_URL = "https://api.open-meteo.com/"

        /** Comma-separated model list for the wind-gust quorum pool. */
        val QUORUM_MODELS_PARAM = GustQuorumModel.entries.joinToString(",") { it.apiParam }

        /** Only wind_gusts_10m needs the per-model suffix pattern for Phase 1; wind_speed_10m
         *  and temperature/precip are requested single-model per the temp/rain scoping decision -
         *  see Phase 1 handoff notes. Open-Meteo suffixes EVERY hourly variable per requested
         *  model regardless of which one we "care about" per-model for, so temperature_2m and
         *  wind_speed_10m will also come back model-suffixed here; the parser only reads the
         *  gust fields per model and can read a single arbitrary model's temperature/wind_speed
         *  for the non-quorum values (implement in Phase 2 alongside the 8am check logic). */
        const val HOURLY_VARS = "temperature_2m,precipitation,wind_speed_10m,wind_gusts_10m,weather_code"
        const val DAILY_VARS = "temperature_2m_max,precipitation_sum,weather_code"
    }
}