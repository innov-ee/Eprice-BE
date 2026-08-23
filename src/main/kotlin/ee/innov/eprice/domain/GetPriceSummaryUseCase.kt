package ee.innov.eprice.domain

import ee.innov.eprice.domain.model.DailyStatEntry
import ee.innov.eprice.domain.model.NoDataFoundException
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.LocalDate

@Serializable
data class PriceSummaryStatistics(
    val countryCode: String,
    val rolling: GetPriceStatisticsUseCase.PriceStatistics,
    val yesterday: GetPriceStatisticsUseCase.PriceStatistics,
    val today: GetPriceStatisticsUseCase.PriceStatistics,
    val tomorrow: GetPriceStatisticsUseCase.PriceStatistics? = null
)

class GetPriceSummaryUseCase(
    private val priceStatsRepository: PriceStatsRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val rollingDays: Int = DEFAULT_ROLLING_DAYS
) {
    companion object {
        // Can be switched from 5 to 30 as needed
        const val DEFAULT_ROLLING_DAYS = 5
    }

    suspend fun execute(countryCode: String): Result<PriceSummaryStatistics> {
        val ucCountry = countryCode.uppercase()
        val zoneId = CountryZoneProvider.getZoneId(ucCountry)
        val today = LocalDate.now(clock.withZone(zoneId))
        val yesterday = today.minusDays(1)
        val tomorrow = today.plusDays(1)

        // Request a wide enough range to cover rolling period + yesterday/today/tomorrow
        val earliestStartDate = minOf(yesterday, today.minusDays(rollingDays.toLong()))
        val dailyStatsResult = priceStatsRepository.getDailyStats(ucCountry, earliestStartDate, tomorrow)
        val statsMap = dailyStatsResult.getOrElse { return Result.failure(it) }

        // 1. Check if tomorrow data is available
        val tomorrowEntry = statsMap[tomorrow]
        val hasTomorrow = tomorrowEntry != null && tomorrowEntry.count > 0

        // 2. Determine rolling window end & start dates (freshest available window)
        val rollingEnd = if (hasTomorrow) tomorrow else today
        val rollingStart = rollingEnd.minusDays(rollingDays.toLong() - 1)

        val rollingStats = computeStats(ucCountry, statsMap, rollingStart, rollingEnd, rollingDays)
            ?: return Result.failure(
                NoDataFoundException("No rolling price data found for $ucCountry between $rollingStart and $rollingEnd.")
            )

        val yesterdayStats = computeStats(ucCountry, statsMap, yesterday, yesterday, 1)
            ?: return Result.failure(
                NoDataFoundException("No price data found for $ucCountry for yesterday ($yesterday).")
            )

        val todayStats = computeStats(ucCountry, statsMap, today, today, 1)
            ?: return Result.failure(
                NoDataFoundException("No price data found for $ucCountry for today ($today).")
            )

        val tomorrowStats = if (hasTomorrow) {
            computeStats(ucCountry, statsMap, tomorrow, tomorrow, 1)
        } else null

        return Result.success(
            PriceSummaryStatistics(
                countryCode = ucCountry,
                rolling = rollingStats,
                yesterday = yesterdayStats,
                today = todayStats,
                tomorrow = tomorrowStats
            )
        )
    }

    private fun computeStats(
        countryCode: String,
        statsMap: Map<LocalDate, DailyStatEntry>,
        startDate: LocalDate,
        endDate: LocalDate,
        daysRequested: Int
    ): GetPriceStatisticsUseCase.PriceStatistics? {
        val slice = statsMap.filterKeys { !it.isBefore(startDate) && !it.isAfter(endDate) }.values
        if (slice.isEmpty()) return null

        val minPrice = slice.minOf { it.min }
        val maxPrice = slice.maxOf { it.max }
        val totalSum = slice.sumOf { it.sum }
        val totalCount = slice.sumOf { it.count }
        val averagePrice = if (totalCount > 0) totalSum / totalCount else 0.0

        return GetPriceStatisticsUseCase.PriceStatistics(
            countryCode = countryCode,
            startDate = startDate.toString(),
            endDate = endDate.toString(),
            daysRequested = daysRequested,
            daysCalculated = slice.size,
            minPrice = minPrice,
            maxPrice = maxPrice,
            averagePrice = averagePrice
        )
    }
}
