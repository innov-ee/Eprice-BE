package ee.innov.eprice.domain

import ee.innov.eprice.domain.model.DailyStatEntry
import ee.innov.eprice.domain.model.DomainEnergyPrice
import java.time.Instant
import java.time.LocalDate

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

    /**
     * Returns per-day price statistics for every day in the inclusive range, transparently
     * using and populating a cache so callers never deal with cache hits/misses themselves.
     */
    suspend fun getDailyStats(
        countryCode: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Result<Map<LocalDate, DailyStatEntry>>
}