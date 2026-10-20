package ee.innov.eprice.domain

import ee.innov.eprice.domain.model.DomainEnergyPrice
import java.time.Instant

interface EnergyPriceRepository {
    /**
     * Fetches energy prices for a country in [start, end), with an exclusive end.
     * Returned intervals are clipped to the requested range and sorted chronologically.
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
