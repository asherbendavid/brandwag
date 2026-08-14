package cvc.dashingdog.brandwag.data.model

import kotlinx.serialization.Serializable

/**
 * Persistence-safe projection of Phase 1's FetchResult.Failure. The live sealed
 * class carries a Throwable on two variants - not serializable, and not useful
 * to keep post-process-death anyway. This keeps the *shape* of the failure
 * (which variant) plus a short human-readable message, produced at mapping
 * time by CheckOutcomeMapping.kt.
 */
@Serializable
sealed class FailureReason {
    abstract val message: String

    @Serializable
    data class NetworkError(override val message: String) : FailureReason()

    @Serializable
    data class HttpError(val code: Int, override val message: String) : FailureReason()

    @Serializable
    data class MalformedResponse(override val message: String) : FailureReason()

    @Serializable
    data class UnexpectedSchema(override val message: String) : FailureReason()
}