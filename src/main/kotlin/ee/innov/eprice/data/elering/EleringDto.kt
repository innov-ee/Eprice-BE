package ee.innov.eprice.data.elering

import ee.innov.eprice.domain.model.DomainEnergyPrice
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

    val prices = this.data[countryCode.uppercase()] ?: return emptyList()

    return prices.map {
        DomainEnergyPrice(
            startTime = Instant.ofEpochSecond(it.timestamp),
            // Convert MWh price to KWh price
            pricePerKWh = it.price / 1000.0
        )
    }
}