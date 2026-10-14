package ee.innov.eprice.data.elering

import ee.innov.eprice.domain.model.DomainEnergyPrice
import ee.innov.eprice.domain.model.InvalidPriceDataException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.Locale

@Serializable
data class EleringPriceResponse(
    val success: Boolean,
    @SerialName("data")
    private val _data: Map<String, List<EleringPriceData>> = emptyMap()
) {
    // map keys always to be uppercase
    val data: Map<String, List<EleringPriceData>> by lazy {
        _data.mapKeys { it.key.uppercase(Locale.ROOT) }
    }
}

@Serializable
data class EleringPriceData(
    val timestamp: Long, // Unix timestamp (seconds)
    @SerialName("price")
    val price: Double    // Price in EUR/MWh
)

/**
 * Maps the Elering API response to a list of domain models for a specific country.
 *
 * @param countryCode The 2-letter country code (e.g., "EE", "FI") to extract prices for.
 * This code is expected to be UPPERCASE.
 */
fun EleringPriceResponse.toDomainEnergyPrices(countryCode: String): List<DomainEnergyPrice> {
    if (!this.success) return emptyList()

    val prices = (this.data[countryCode] ?: return emptyList()).sortedBy { it.timestamp }
    if (prices.any { !it.price.isFinite() } || prices.map { it.timestamp }.distinct().size != prices.size) {
        throw InvalidPriceDataException("Elering returned non-finite prices or duplicate timestamps")
    }
    // Nord Pool's 2025 delivery transition, verified against Elering for EE/FI/LV/LT.
    // It happens at 01:00 locally, so choosing a resolution by local date is incorrect.
    val quarterHourlyStart = Instant.parse("2025-09-30T22:00:00Z")

    return prices.map {
        val start = Instant.ofEpochSecond(it.timestamp)
        val step = if (start < quarterHourlyStart) 3600L else 900L
        if (it.timestamp % step != 0L) throw InvalidPriceDataException("Elering price timestamp is not aligned to its interval")
        DomainEnergyPrice(
            startTime = start,
            // Convert MWh price to KWh price
            pricePerKWh = it.price / 1000.0,
            durationSeconds = step
        )
    }
}
