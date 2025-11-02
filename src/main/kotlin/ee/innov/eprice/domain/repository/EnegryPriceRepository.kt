package ee.innov.eprice.domain.repository

import ee.innov.eprice.domain.model.DomainEnergyPrice
import java.time.Instant

interface EnergyPriceRepository {
    /**
     * Fetches energy prices for a given time range.
     * Returns a Result containing a list of prices on success,
     * or an ApiError on failure.
     */
    suspend fun getPrices(start: Instant, end: Instant): Result<List<DomainEnergyPrice>>
}