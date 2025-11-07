package ee.innov.eprice.data.elering

import ee.innov.eprice.domain.model.DomainEnergyPrice
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class EleringPriceResponse(
    val success: Boolean,
    @SerialName("data")
    val data: Map<String, List<EleringPriceData>> = emptyMap()
)

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
 */
fun EleringPriceResponse.toDomainEnergyPrices(countryCode: String): List<DomainEnergyPrice> {
    if (!this.success) return emptyList()

    val prices = this.data[countryCode] ?: return emptyList()

    return prices.map {
        DomainEnergyPrice(
            startTime = Instant.ofEpochSecond(it.timestamp),
            // Convert MWh price to KWh price
            pricePerKWh = it.price / 1000.0
        )
    }
}