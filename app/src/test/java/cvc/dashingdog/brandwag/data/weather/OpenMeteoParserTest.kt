package cvc.dashingdog.brandwag.data.weather

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class OpenMeteoParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun buildResponse(hourlyJson: String, dailyJson: String? = null): OpenMeteoResponseDto {
        val dailyPart = if (dailyJson != null) ""","daily":$dailyJson""" else ""
        val full = """{"latitude":-33.9,"longitude":18.4,"hourly":$hourlyJson$dailyPart}"""
        return json.decodeFromString(OpenMeteoResponseDto.serializer(), full)
    }

    // --- Happy path: all 5 models respond, majority say "clear" ---

    @Test
    fun `all models respond, minority dangerous, result is trusted and clear`() {
        val hourly = """
            {
              "time": ["2026-08-11T00:00", "2026-08-11T01:00", "2026-08-11T02:00"],
              "wind_gusts_10m_ecmwf_ifs025": [20.0, 22.0, 25.0],
              "wind_gusts_10m_gfs_seamless": [18.0, 19.0, 20.0],
              "wind_gusts_10m_icon_seamless": [21.0, 21.0, 21.0],
              "wind_gusts_10m_gem_seamless": [65.0, 70.0, 80.0],
              "wind_gusts_10m_metno_seamless": [19.0, 19.0, 19.0]
            }
        """.trimIndent()
        val response = buildResponse(hourly)

        val result = OpenMeteoParser.parseWindQuorum(
            response, nowIso = "2026-08-11T00:00:00", lookaheadHours = 3, gustThresholdKmh = 40.0
        )

        assertTrue(result is WindQuorumResult.Trusted)
        result as WindQuorumResult.Trusted
        assertEquals(5, result.respondingModels)
        assertEquals(1, result.dangerVotes)
        assertFalse("only 1/5 dangerous - must not alarm", result.alarmTriggered)
    }

    @Test
    fun `strict majority dangerous triggers alarm`() {
        val hourly = """
            {
              "time": ["2026-08-11T00:00"],
              "wind_gusts_10m_ecmwf_ifs025": [55.0],
              "wind_gusts_10m_gfs_seamless": [60.0],
              "wind_gusts_10m_icon_seamless": [65.0],
              "wind_gusts_10m_gem_seamless": [10.0],
              "wind_gusts_10m_metno_seamless": [12.0]
            }
        """.trimIndent()
        val response = buildResponse(hourly)

        val result = OpenMeteoParser.parseWindQuorum(
            response, nowIso = "2026-08-11T00:00:00", lookaheadHours = 1, gustThresholdKmh = 40.0
        ) as WindQuorumResult.Trusted

        // 3/5 dangerous - strict >half of 5 responding is >2.5, i.e. 3 - must alarm
        assertEquals(3, result.dangerVotes)
        assertTrue(result.alarmTriggered)
    }

    @Test
    fun `four responding models need 3 dangerous not 2 for strict majority`() {
        val hourly = """
            {
              "time": ["2026-08-11T00:00"],
              "wind_gusts_10m_ecmwf_ifs025": [55.0],
              "wind_gusts_10m_gfs_seamless": [60.0],
              "wind_gusts_10m_icon_seamless": [10.0],
              "wind_gusts_10m_gem_seamless": [11.0]
            }
        """.trimIndent()
        // metno key entirely absent - simulates a model missing from the response
        val response = buildResponse(hourly)

        val result = OpenMeteoParser.parseWindQuorum(
            response, nowIso = "2026-08-11T00:00:00", lookaheadHours = 1, gustThresholdKmh = 40.0
        ) as WindQuorumResult.Trusted

        assertEquals(4, result.respondingModels)
        assertEquals(2, result.dangerVotes)
        // 2/4 is exactly half, not strictly more than half - must NOT alarm
        assertFalse(result.alarmTriggered)
    }

    // --- Degraded / must-never: below floor must never read as "clear" ---

    @Test
    fun `only two models responding is degraded not clear`() {
        val hourly = """
            {
              "time": ["2026-08-11T00:00"],
              "wind_gusts_10m_ecmwf_ifs025": [10.0],
              "wind_gusts_10m_gfs_seamless": [12.0]
            }
        """.trimIndent()
        val response = buildResponse(hourly)

        val result = OpenMeteoParser.parseWindQuorum(
            response, nowIso = "2026-08-11T00:00:00", lookaheadHours = 1, gustThresholdKmh = 40.0
        )

        assertTrue("must be Degraded, never silently Trusted-clear", result is WindQuorumResult.Degraded)
        assertEquals(2, (result as WindQuorumResult.Degraded).respondingModels)
    }

    @Test
    fun `per-model null array excludes that model from quorum`() {
        val hourly = """
            {
              "time": ["2026-08-11T00:00"],
              "wind_gusts_10m_ecmwf_ifs025": [55.0],
              "wind_gusts_10m_gfs_seamless": [60.0],
              "wind_gusts_10m_icon_seamless": [65.0],
              "wind_gusts_10m_gem_seamless": null,
              "wind_gusts_10m_metno_seamless": [12.0]
            }
        """.trimIndent()
        val response = buildResponse(hourly)

        val result = OpenMeteoParser.parseWindQuorum(
            response, nowIso = "2026-08-11T00:00:00", lookaheadHours = 1, gustThresholdKmh = 40.0
        ) as WindQuorumResult.Trusted

        assertEquals(4, result.respondingModels)
    }

    @Test
    fun `per-index null within an array nulls that model's vote entirely`() {
        val hourly = """
        {
          "time": ["2026-08-11T00:00", "2026-08-11T01:00"],
          "wind_gusts_10m_ecmwf_ifs025": [null, 55.0],
          "wind_gusts_10m_gfs_seamless": [60.0, 60.0],
          "wind_gusts_10m_icon_seamless": [65.0, 65.0],
          "wind_gusts_10m_gem_seamless": [10.0, 10.0],
          "wind_gusts_10m_metno_seamless": [12.0, 12.0]
        }
    """.trimIndent()
        val response = buildResponse(hourly)

        val result = OpenMeteoParser.parseWindQuorum(
            response, nowIso = "2026-08-11T00:00:00", lookaheadHours = 2, gustThresholdKmh = 40.0
        ) as WindQuorumResult.Trusted

        // ecmwf has a null at index 0 - only 1 of 2 expected hours present, so it
        // no longer counts as responding at all, even though index 1 has a real
        // value. This is the Phase 3 fix: partial coverage nulls the whole vote
        // rather than silently maxing over whatever's left.
        val ecmwfVote = result.votes.first { it.model == GustQuorumModel.ECMWF_IFS }
        assertEquals(null, ecmwfVote.maxGustKmh)
        assertEquals(4, result.respondingModels)
    }

    @Test
    fun `entirely empty hourly time throws unexpected schema not silent empty result`() {
        val hourly = """{"time": [], "wind_gusts_10m_ecmwf_ifs025": []}"""
        val response = buildResponse(hourly)

        try {
            OpenMeteoParser.parseWindQuorum(
                response, nowIso = "2026-08-11T00:00:00", lookaheadHours = 1, gustThresholdKmh = 40.0
            )
            org.junit.Assert.fail("expected IllegalStateException when current time isn't found in an empty time array")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun `missing hourly block throws rather than returning a default clear result`() {
        val full = """{"latitude":-33.9,"longitude":18.4}"""
        val response = json.decodeFromString(OpenMeteoResponseDto.serializer(), full)

        try {
            OpenMeteoParser.parseWindQuorum(
                response, nowIso = "2026-08-11T00:00:00", lookaheadHours = 1, gustThresholdKmh = 40.0
            )
            org.junit.Assert.fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun `current time not truncated to hour still matches top-of-hour entry`() {
        val hourly = """
            {
              "time": ["2026-08-11T00:00", "2026-08-11T01:00"],
              "wind_gusts_10m_ecmwf_ifs025": [10.0, 99.0],
              "wind_gusts_10m_gfs_seamless": [10.0, 99.0],
              "wind_gusts_10m_icon_seamless": [10.0, 99.0]
            }
        """.trimIndent()
        val response = buildResponse(hourly)

        // nowIso has minutes/seconds - mirrors current.time's :00/:15/:30/:45 behavior
        val result = OpenMeteoParser.parseWindQuorum(
            response, nowIso = "2026-08-11T00:37:00", lookaheadHours = 2, gustThresholdKmh = 200.0
        ) as WindQuorumResult.Trusted

        // should have matched index 0 (00:00), not failed to find 00:37
        assertEquals(99.0, result.votes.first().maxGustKmh)
    }

    // --- Daily parsing ---

    @Test
    fun `daily parsing handles per-index nulls without throwing`() {
        val daily = """
            {
              "time": ["2026-08-11", "2026-08-12"],
              "temperature_2m_max": [28.5, null],
              "precipitation_sum": [null, 5.2],
              "weather_code": [1, null]
            }
        """.trimIndent()
        val response = buildResponse(hourlyJson = """{"time":[]}""", dailyJson = daily)

        val forecasts = OpenMeteoParser.parseDaily(response)

        assertEquals(2, forecasts.size)
        assertEquals(28.5, forecasts[0].maxTempC)
        assertEquals(null, forecasts[0].precipitationMm)
        assertEquals(null, forecasts[1].maxTempC)
    }

    @Test
    fun `missing daily block returns empty list not a crash`() {
        val response = buildResponse(hourlyJson = """{"time":[]}""")
        val forecasts = OpenMeteoParser.parseDaily(response)
        assertEquals(emptyList<DailyForecast>(), forecasts)
    }
}