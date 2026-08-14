package cvc.dashingdog.brandwag.data.model

import cvc.dashingdog.brandwag.data.model.serializers.LocalDateSerializer
import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable
data class BurnState(
    val armed: Boolean,
    @Serializable(with = LocalDateSerializer::class)
    val armedDate: LocalDate? // null when armed == false
) {
    companion object {
        val IDLE = BurnState(armed = false, armedDate = null)
    }
}