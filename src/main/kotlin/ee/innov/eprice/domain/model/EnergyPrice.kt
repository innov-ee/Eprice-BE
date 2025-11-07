package ee.innov.eprice.domain.model

import ee.innov.eprice.util.InstantSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class DomainEnergyPrice(
    @Serializable(with = InstantSerializer::class)
    val startTime: Instant,
    val pricePerKWh: Double
)