package ee.innov.eprice.domain

import ee.innov.eprice.domain.GetPriceStatisticsUseCase.PriceStatistics
import ee.innov.eprice.domain.model.NoDataFoundException
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.LocalDate

@Serializable
data class PriceSummaryStatistics(
    val countryCode: String,
    val rolling: PriceStatistics,
    val yesterday: PriceStatistics,
    val today: PriceStatistics,
    val tomorrow: PriceStatistics? = null
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
        val zoneId = CountryZoneProvider.getZoneId(countryCode)
        val today = LocalDate.now(clock.withZone(zoneId))
        val yesterday = today.minusDays(1)
        val tomorrow = today.plusDays(1)
        val rollingStart = yesterday.minusDays(rollingDays.toLong() - 1)

        val rawStatsMap = priceStatsRepository.getDailyStats(countryCode, rollingStart, tomorrow)
            .getOrElse { return Result.failure(it) }

        val statsMap = rawStatsMap.filter { (date, entry) ->
            entry.isFullDay(date, zoneId)
        }

        val rollingStats = PriceStatistics.compute(countryCode, statsMap, rollingStart, yesterday, rollingDays)
            ?: return Result.failure(
                NoDataFoundException("No rolling price data found for $countryCode between $rollingStart and $yesterday.")
            )

        val yesterdayStats = PriceStatistics.compute(countryCode, statsMap, yesterday, yesterday, 1)
            ?: return Result.failure(
                NoDataFoundException("No price data found for $countryCode for yesterday ($yesterday).")
            )

        val todayStats = PriceStatistics.compute(countryCode, statsMap, today, today, 1)
            ?: return Result.failure(
                NoDataFoundException("No price data found for $countryCode for today ($today).")
            )

        // Check if tomorrow data is available with full day data
        val tomorrowEntry = statsMap[tomorrow]
        val hasTomorrow = tomorrowEntry != null && tomorrowEntry.isFullDay(tomorrow, zoneId)

        val tomorrowStats = if (hasTomorrow) {
            PriceStatistics.compute(countryCode, statsMap, tomorrow, tomorrow, 1)
        } else null

        return Result.success(
            PriceSummaryStatistics(
                countryCode = countryCode,
                rolling = rollingStats,
                yesterday = yesterdayStats,
                today = todayStats,
                tomorrow = tomorrowStats
            )
        )
    }
}
