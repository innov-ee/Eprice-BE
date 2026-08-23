package ee.innov.eprice.domain

import ee.innov.eprice.domain.model.DailyStatEntry
import ee.innov.eprice.domain.model.NoDataFoundException
import ee.innov.eprice.domain.model.PriceStatsQuery
import ee.innov.eprice.domain.model.PriceStatsQuery.NamedRange
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class GetPriceStatisticsUseCase(
    private val priceStatsRepository: PriceStatsRepository,
    private val clock: Clock = Clock.systemUTC()
) {

    companion object {
        // Bounds the size of the date range delegated to the repository per request.
        private const val MAX_RANGE_DAYS = 60
    }

    @Serializable
    data class PriceStatistics(
        val countryCode: String,
        val startDate: String,
        val endDate: String,
        val daysRequested: Int,
        val daysCalculated: Int,
        val minPrice: Double,
        val maxPrice: Double,
        val averagePrice: Double
    ) {
        companion object {
            /**
             * Aggregates a collection of [DailyStatEntry] into [PriceStatistics].
             * Returns null if [dailyStats] is empty.
             */
            fun compute(
                countryCode: String,
                startDate: LocalDate,
                endDate: LocalDate,
                daysRequested: Int,
                dailyStats: Collection<DailyStatEntry>
            ): PriceStatistics? {
                if (dailyStats.isEmpty()) return null

                val minPrice = dailyStats.minOf { it.min }
                val maxPrice = dailyStats.maxOf { it.max }
                val totalSum = dailyStats.sumOf { it.sum }
                val totalCount = dailyStats.sumOf { it.count }
                val averagePrice = if (totalCount > 0) totalSum / totalCount else 0.0

                return PriceStatistics(
                    countryCode = countryCode.uppercase(),
                    startDate = startDate.toString(),
                    endDate = endDate.toString(),
                    daysRequested = daysRequested,
                    daysCalculated = dailyStats.size,
                    minPrice = minPrice,
                    maxPrice = maxPrice,
                    averagePrice = averagePrice
                )
            }

            /**
             * Aggregates a date-keyed map of [DailyStatEntry] for the given [startDate]..[endDate] slice.
             * Returns null if no entries fall within the date range.
             */
            fun compute(
                countryCode: String,
                statsMap: Map<LocalDate, DailyStatEntry>,
                startDate: LocalDate,
                endDate: LocalDate,
                daysRequested: Int
            ): PriceStatistics? {
                val slice = statsMap.filterKeys { !it.isBefore(startDate) && !it.isAfter(endDate) }.values
                return compute(
                    countryCode = countryCode,
                    startDate = startDate,
                    endDate = endDate,
                    daysRequested = daysRequested,
                    dailyStats = slice
                )
            }
        }
    }

    /**
     * Executes price statistics calculation based on domain [PriceStatsQuery].
     */
    suspend fun execute(
        countryCode: String,
        query: PriceStatsQuery = PriceStatsQuery.Default
    ): Result<PriceStatistics> {
        val zoneId = CountryZoneProvider.getZoneId(countryCode)
        val today = LocalDate.now(clock.withZone(zoneId))

        val (startDate, endDate, daysRequested) = when (query) {
            is PriceStatsQuery.Named -> when (query.range) {
                NamedRange.YESTERDAY -> {
                    val yesterday = today.minusDays(1)
                    Triple(yesterday, yesterday, 1)
                }
                NamedRange.TODAY -> {
                    Triple(today, today, 1)
                }
                NamedRange.TOMORROW -> {
                    val tomorrow = today.plusDays(1)
                    Triple(tomorrow, tomorrow, 1)
                }
            }

            is PriceStatsQuery.Days -> {
                if (query.days <= 0) {
                    return Result.failure(IllegalArgumentException("Number of days must be positive."))
                }
                if (query.days > MAX_RANGE_DAYS) {
                    return Result.failure(
                        IllegalArgumentException("Number of days too large (${query.days}); max is $MAX_RANGE_DAYS days.")
                    )
                }
                val end = today.minusDays(1)
                val start = end.minusDays(query.days.toLong() - 1)
                Triple(start, end, query.days)
            }

            is PriceStatsQuery.CustomRange -> {
                if (query.startDate.isAfter(query.endDate)) {
                    return Result.failure(IllegalArgumentException("startDate cannot be after endDate."))
                }
                val totalDays = (ChronoUnit.DAYS.between(query.startDate, query.endDate) + 1).toInt()
                if (totalDays > MAX_RANGE_DAYS) {
                    return Result.failure(
                        IllegalArgumentException("Date range too large ($totalDays days); max is $MAX_RANGE_DAYS days.")
                    )
                }
                Triple(query.startDate, query.endDate, totalDays)
            }

            is PriceStatsQuery.Default -> {
                val end = today.minusDays(1)
                val start = end.minusDays(4)
                Triple(start, end, 5)
            }
        }

        val dailyStatsResult = priceStatsRepository.getDailyStats(countryCode, startDate, endDate)
        val allStats = dailyStatsResult.getOrElse { return Result.failure(it) }.values

        val stats = PriceStatistics.compute(
            countryCode = countryCode,
            startDate = startDate,
            endDate = endDate,
            daysRequested = daysRequested,
            dailyStats = allStats
        ) ?: return Result.failure(
            NoDataFoundException("No price data found for $countryCode between $startDate and $endDate.")
        )

        return Result.success(stats)
    }

    /**
     * Convenience method to calculate price statistics for rolling window of days.
     */
    suspend fun execute(countryCode: String, days: Int): Result<PriceStatistics> =
        execute(countryCode, PriceStatsQuery.Days(days))

    /**
     * Convenience method to calculate price statistics for explicit date range.
     */
    suspend fun execute(countryCode: String, startDate: LocalDate, endDate: LocalDate): Result<PriceStatistics> =
        execute(countryCode, PriceStatsQuery.CustomRange(startDate, endDate))
}
