package ee.innov.eprice.data.elering

import ee.innov.eprice.domain.model.DomainEnergyPrice
import ee.innov.eprice.domain.model.InvalidPriceDataException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class EleringPriceResponse(
    val success: Boolean,
    @SerialName("data")
    private val _data: Map<String, List<EleringPriceData>> = emptyMap()
) {
    // map keys always to be uppercase
    val data: Map<String, List<EleringPriceData>> by lazy {
        _data.mapKeys { it.key.uppercase() }
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

    val prices = (this.data[countryCode] ?: return emptyList()).sortedChronologically()
    prices.validateFinitePrices()
    prices.validateUniqueTimestamps()

    return prices.map { price ->
        val start = Instant.ofEpochSecond(price.timestamp)
        val durationSeconds = intervalDurationSeconds(start)
        price.validateIntervalAlignment(durationSeconds)
        DomainEnergyPrice(
            startTime = start,
            // Convert MWh price to KWh price
            pricePerKWh = price.price / 1000.0,
            durationSeconds = durationSeconds
        )
    }
}

private fun List<EleringPriceData>.sortedChronologically(): List<EleringPriceData> = sortedBy { it.timestamp }

private fun List<EleringPriceData>.validateFinitePrices() {
    if (any { !it.price.isFinite() }) {
        throw InvalidPriceDataException("Elering returned non-finite prices")
    }
}

private fun List<EleringPriceData>.validateUniqueTimestamps() {
    if (map { it.timestamp }.distinct().size != size) {
        throw InvalidPriceDataException("Elering returned duplicate timestamps")
    }
}

// Nord Pool's 2025 delivery transition, verified against Elering for EE/FI/LV/LT.
// It happens at 01:00 locally, so choosing a resolution by local date is incorrect.
private val quarterHourlyStart = Instant.parse("2025-09-30T22:00:00Z")

private fun intervalDurationSeconds(start: Instant): Long = if (start < quarterHourlyStart) 3600L else 900L

private fun EleringPriceData.validateIntervalAlignment(durationSeconds: Long) {
    if (timestamp % durationSeconds != 0L) {
        throw InvalidPriceDataException("Elering price timestamp is not aligned to its interval")
    }
}
