package ee.innov.eprice.domain

import ee.innov.eprice.domain.model.DailyStatEntry
import java.time.LocalDate

interface PriceStatsRepository {
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
