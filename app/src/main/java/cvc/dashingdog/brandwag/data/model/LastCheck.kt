package cvc.dashingdog.brandwag.data.model

import cvc.dashingdog.brandwag.data.model.serializers.InstantSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
sealed class CheckOutcome {
    abstract val timestamp: Instant

    @Serializable
    data class Failure(
        val reason: FailureReason,
        @Serializable(with = InstantSerializer::class)
        override val timestamp: Instant
    ) : CheckOutcome()

    @Serializable
    data class Degraded(
        val respondingModels: Int,
        val rawValues: RawCheckValues,
        @Serializable(with = InstantSerializer::class)
        override val timestamp: Instant
    ) : CheckOutcome()

    @Serializable
    data class Clear(
        val respondingModels: Int,
        val rawValues: RawCheckValues,
        @Serializable(with = InstantSerializer::class)
        override val timestamp: Instant
    ) : CheckOutcome()

    @Serializable
    data class Dangerous(
        val respondingModels: Int,
        val rawValues: RawCheckValues,
        @Serializable(with = InstantSerializer::class)
        override val timestamp: Instant
    ) : CheckOutcome()
}

@Serializable
data class RawCheckValues(
    val gustsByModel: Map<String, Double?>,
    /** Gap: sustained wind not yet parsed in Phase 1's ModelVote - left empty for now. */
    val sustainedWindByModel: Map<String, Double?>
)