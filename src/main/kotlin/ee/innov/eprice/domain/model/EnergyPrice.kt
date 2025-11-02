package ee.innov.eprice.domain.model

import java.time.Instant

data class DomainEnergyPrice(
    val startTime: Instant,
    val pricePerKWh: Double
)