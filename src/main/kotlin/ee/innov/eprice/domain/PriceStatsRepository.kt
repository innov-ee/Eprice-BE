package ee.innov.eprice.domain

import ee.innov.eprice.domain.model.DailyStatEntry
import java.time.LocalDate

interface PriceStatsRepository {
    /**
     * Returns statistics for complete days and, when available, provisional statistics
     * for region-local today and tomorrow that meet the minimum coverage threshold
     * (isComplete = false). Days below this threshold are unavailable. Transparently
     * using and populating a cache so callers never deal with cache hits/misses themselves.
     */
    suspend fun getDailyStats(
        countryCode: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Result<Map<LocalDate, DailyStatEntry>>
}
