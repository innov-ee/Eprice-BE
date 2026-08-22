package ee.innov.eprice.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class DailyStatEntry(
    val min: Double,
    val max: Double,
    val avg: Double,
    val sum: Double,
    val count: Int
)
