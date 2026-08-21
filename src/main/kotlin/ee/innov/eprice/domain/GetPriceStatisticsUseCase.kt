package ee.innov.eprice.domain

import ee.innov.eprice.data.DailyStatEntry
import ee.innov.eprice.data.DailyStatsCache
import ee.innov.eprice.domain.model.NoDataFoundException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class GetPriceStatisticsUseCase(
    private val energyPriceRepository: EnergyPriceRepository,
    private val dailyStatsCache: DailyStatsCache
) {

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
        val datesInRange = (0 until totalDays).map { startDate.plusDays(it.toLong()) }

        // 1. Get cached daily stats for this range
        val cachedStats: Map<LocalDate, DailyStatEntry> =
            dailyStatsCache.getRange(countryCode, startDate, endDate)

        // 2. Identify missing dates
        val missingDates = datesInRange.filter { !cachedStats.containsKey(it) }

        val fetchedStats = mutableMapOf<LocalDate, DailyStatEntry>()

        if (missingDates.isNotEmpty()) {
            try {
                coroutineScope {
                    val deferredResults = missingDates.map { date ->
                        async {
                            val dayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant()
                            val dayEnd =
                                date.plusDays(1).atStartOfDay(ZoneOffset.UTC).minusSeconds(1)
                                    .toInstant()

                            val result = energyPriceRepository.getPrices(
                                countryCode = countryCode,
                                start = dayStart,
                                end = dayEnd,
                                cacheResults = false
                            )

                            result.getOrNull()?.let { prices ->
                                if (prices.isNotEmpty()) {
                                    val pricesList = prices.map { it.pricePerKWh }
                                    val min = pricesList.minOrNull() ?: 0.0
                                    val max = pricesList.maxOrNull() ?: 0.0
                                    val sum = pricesList.sum()
                                    val count = pricesList.size
                                    val avg = if (count > 0) sum / count else 0.0

                                    val entry = DailyStatEntry(
                                        min = min,
                                        max = max,
                                        avg = avg,
                                        sum = sum,
                                        count = count
                                    )
                                    date to entry
                                } else {
                                    null
                                }
                            }
                        }
                    }
                    fetchedStats.putAll(deferredResults.awaitAll().filterNotNull())
                }

                // Persist missing entries in a single batch write
                if (fetchedStats.isNotEmpty()) {
                    dailyStatsCache.putBatch(countryCode, fetchedStats)
                }
            } catch (e: Exception) {
                return Result.failure(e)
            }
        }

        val allStats = (cachedStats.values + fetchedStats.values)

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
