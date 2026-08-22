package ee.innov.eprice.domain

import ee.innov.eprice.domain.model.NoDataFoundException
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class GetPriceStatisticsUseCase(
    private val priceStatsRepository: PriceStatsRepository
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
    )

    /**
     * Calculates price statistics for a rolling window of days ending yesterday.
     * Defaults to 5 days for fast testing and API rate management.
     */
    suspend fun execute(countryCode: String, days: Int = 5): Result<PriceStatistics> {
        if (days <= 0) {
            return Result.failure(IllegalArgumentException("Number of days must be positive."))
        }
        val endDate = Instant.now().atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
        val startDate = endDate.minusDays(days.toLong() - 1)
        return execute(countryCode, startDate, endDate, daysRequested = days)
    }

    /**
     * Calculates price statistics specifically for yesterday.
     */
    suspend fun executeYesterday(countryCode: String): Result<PriceStatistics> {
        val yesterday = Instant.now().atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
        return execute(countryCode, yesterday, yesterday, daysRequested = 1)
    }

    /**
     * Calculates price statistics for an explicit date range.
     */
    suspend fun execute(
        countryCode: String,
        startDate: LocalDate,
        endDate: LocalDate,
        daysRequested: Int = (ChronoUnit.DAYS.between(startDate, endDate) + 1).toInt()
    ): Result<PriceStatistics> {
        if (startDate.isAfter(endDate)) {
            return Result.failure(IllegalArgumentException("startDate cannot be after endDate."))
        }

        val totalDays = (ChronoUnit.DAYS.between(startDate, endDate) + 1).toInt()
        if (totalDays > MAX_RANGE_DAYS) {
            return Result.failure(
                IllegalArgumentException("Date range too large ($totalDays days); max is $MAX_RANGE_DAYS days.")
            )
        }

        val dailyStatsResult = priceStatsRepository.getDailyStats(countryCode, startDate, endDate)
        val allStats = dailyStatsResult.getOrElse { return Result.failure(it) }.values

        if (allStats.isEmpty()) {
            return Result.failure(
                NoDataFoundException("No price data found for $countryCode between $startDate and $endDate.")
            )
        }

        val minPrice = allStats.minOf { it.min }
        val maxPrice = allStats.maxOf { it.max }
        val totalSum = allStats.sumOf { it.sum }
        val totalCount = allStats.sumOf { it.count }
        val averagePrice = if (totalCount > 0) totalSum / totalCount else 0.0

        return Result.success(
            PriceStatistics(
                countryCode = countryCode.uppercase(),
                startDate = startDate.toString(),
                endDate = endDate.toString(),
                daysRequested = daysRequested,
                daysCalculated = allStats.size,
                minPrice = minPrice,
                maxPrice = maxPrice,
                averagePrice = averagePrice
            )
        )
    }
}
