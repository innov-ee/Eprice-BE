package ee.innov.eprice.data.elering

import ee.innov.eprice.domain.model.DomainEnergyPrice
import ee.innov.eprice.domain.CountryZoneProvider
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

    val prices = (this.data[countryCode] ?: return emptyList()).sortedBy { it.timestamp }
    if (prices.any { !it.price.isFinite() } || prices.map { it.timestamp }.distinct().size != prices.size) {
        throw InvalidPriceDataException("Elering returned non-finite prices or duplicate timestamps")
    }
    val zone = CountryZoneProvider.getZoneId(countryCode)
    // Elering provides starts only. Infer the regular step within each local day,
    // preserving historical hourly days when a range spans a resolution change.
    val fallbackStep = prices.zipWithNext { a, b -> b.timestamp - a.timestamp }.minOrNull() ?: 3600L
    val steps = prices.groupBy { Instant.ofEpochSecond(it.timestamp).atZone(zone).toLocalDate() }
        .mapValues { (_, day) -> day.zipWithNext { a, b -> b.timestamp - a.timestamp }.minOrNull() ?: fallbackStep }
    if (steps.values.any { it !in setOf(900L, 1800L, 3600L) }) {
        throw InvalidPriceDataException("Unsupported Elering price interval")
    }

    return prices.map {
        DomainEnergyPrice(
            startTime = Instant.ofEpochSecond(it.timestamp),
            // Convert MWh price to KWh price
            pricePerKWh = it.price / 1000.0,
            durationSeconds = steps.getValue(Instant.ofEpochSecond(it.timestamp).atZone(zone).toLocalDate())
        )
    }
}
