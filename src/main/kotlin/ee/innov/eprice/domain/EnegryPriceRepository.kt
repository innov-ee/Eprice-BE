package ee.innov.eprice.domain

import ee.innov.eprice.domain.model.DomainEnergyPrice
import java.time.Instant

interface EnergyPriceRepository {
    /**
     * Fetches energy prices for a given time range and country.
     * Returns a Result containing a list of prices on success,
     * or an ApiError on failure.
     */
    suspend fun getPrices(
        countryCode: String,
        start: Instant,
        end: Instant,
        cacheResults: Boolean = true
    ): Result<List<DomainEnergyPrice>>
}