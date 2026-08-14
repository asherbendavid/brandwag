package cvc.dashingdog.brandwag.data.repository

import cvc.dashingdog.brandwag.data.model.CheckOutcome
import cvc.dashingdog.brandwag.data.model.FailureReason
import cvc.dashingdog.brandwag.data.model.RawCheckValues
import cvc.dashingdog.brandwag.data.weather.FetchResult
import cvc.dashingdog.brandwag.data.weather.WindQuorumResult
import java.time.Instant

/** Boundary mappers: live Phase 1 domain types -> Phase 2 persistable CheckOutcome.
 *  Kept out of data/model/ deliberately, so that package has zero dependency on
 *  the weather/parsing layer. */

fun FetchResult.Failure.toFailureReason(): FailureReason = when (this) {
    is FetchResult.Failure.NetworkError ->
        FailureReason.NetworkError(cause.message ?: cause::class.simpleName ?: "Unknown network error")
    is FetchResult.Failure.HttpError ->
        FailureReason.HttpError(code, "HTTP $code")
    is FetchResult.Failure.MalformedResponse ->
        FailureReason.MalformedResponse(cause.message ?: cause::class.simpleName ?: "Malformed response")
    is FetchResult.Failure.UnexpectedSchema ->
        FailureReason.UnexpectedSchema(message)
}

fun WindQuorumResult.toCheckOutcome(timestamp: Instant): CheckOutcome {
    val raw = when (this) {
        is WindQuorumResult.Trusted -> RawCheckValues(
            gustsByModel = votes.associate { it.model.apiParam to it.maxGustKmh },
            sustainedWindByModel = emptyMap()
        )
        is WindQuorumResult.Degraded -> RawCheckValues(
            gustsByModel = votes.associate { it.model.apiParam to it.maxGustKmh },
            sustainedWindByModel = emptyMap()
        )
    }
    return when (this) {
        is WindQuorumResult.Degraded -> CheckOutcome.Degraded(respondingModels, raw, timestamp)
        is WindQuorumResult.Trusted -> if (alarmTriggered)
            CheckOutcome.Dangerous(respondingModels, raw, timestamp)
        else
            CheckOutcome.Clear(respondingModels, raw, timestamp)
    }
}