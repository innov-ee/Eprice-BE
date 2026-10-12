package ee.innov.eprice.domain

import ee.innov.eprice.data.DailyAveragePriceCache
import ee.innov.eprice.domain.model.NoDataFoundException
import ee.innov.eprice.domain.model.isFullDay
import ee.innov.eprice.domain.model.inRange
import kotlinx.coroutines.CancellationException
import ee.innov.eprice.monitoring.ServiceMonitor
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.LocalDate

class GetRollingAveragePriceUseCase(
    private val energyPriceRepository: EnergyPriceRepository,
    private val dailyAveragePriceCache: DailyAveragePriceCache,
    private val clock: Clock = Clock.systemUTC(),
    private val monitor: ServiceMonitor? = null
) {

    /**
     * Calculates the rolling average price for a given country over a number of days.
     *
     * It first checks a persistent daily average cache. If data for a day is missing,
     * it fetches the interval data from the repository, calculates the day's average,
     * and stores it in the cache for future use.
     *
     * @param countryCode The 2-letter country code (e.g., "EE").
     * @param days The number of days to include in the average (e.g., 30).
     * @return A Result containing the average price, or an error if data cannot be fetched.
     */
    suspend fun execute(countryCode: String, days: Int): Result<RollingAverage> {
        if (days <= 0) {
            return Result.failure(IllegalArgumentException("Number of days must be positive."))
        }

        val zoneId = CountryZoneProvider.getZoneId(countryCode)
        val today = LocalDate.now(clock.withZone(zoneId))
        val endDate = today.minusDays(1)
        val startDate = endDate.minusDays(days.toLong() - 1)

        // 1. Get all dates in the range
        val datesInRange = (0 until days).map { startDate.plusDays(it.toLong()) }

        // 2. Get all currently cached daily averages for this range
        val cachedAverages: Map<LocalDate, Double> =
            dailyAveragePriceCache.getRange(countryCode, startDate, endDate)

        // 3. Determine which dates we are missing
        val missingDates = datesInRange.filter { !cachedAverages.containsKey(it) }

        monitor?.recordCacheHit(cachedAverages.size.toLong())
        monitor?.recordCacheMiss(missingDates.size.toLong())

        val allAverages = cachedAverages.toMutableMap()
        try {
            for (date in missingDates) {
                val start = date.atStartOfDay(zoneId).toInstant()
                val end = date.plusDays(1).atStartOfDay(zoneId).toInstant()
                val prices = energyPriceRepository.getPrices(countryCode, start, end, cacheResults = false)
                    .getOrThrow().inRange(start, end)
                if (prices.isFullDay(date, zoneId)) {
                    val average = prices.sumOf { it.pricePerKWh * it.durationSeconds } / prices.sumOf { it.durationSeconds }
                    dailyAveragePriceCache.put(countryCode, date, average)
                    allAverages[date] = average
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        }

        if (allAverages.isEmpty()) {
            return Result.failure(NoDataFoundException("No price data found for $countryCode between $startDate and $endDate."))
        }
        // Weight by elapsed time: a DST day contains 23 or 25 hours, regardless of resolution.
        fun seconds(date: LocalDate) = CountryZoneProvider.getExpectedHoursForDay(date, zoneId) * 3600L
        val finalAverage = allAverages.entries.sumOf { (date, avg) -> avg * seconds(date) } /
            allAverages.keys.sumOf { seconds(it) }
        val daysCalculated = allAverages.size

        return Result.success(
            RollingAverage(
                countryCode = countryCode,
                daysRequested = days,
                daysCalculated = daysCalculated,
                startDate = startDate,
                endDate = endDate,
                averagePrice = finalAverage
            )
        )
    }

    @Serializable
    data class RollingAverage(
        val countryCode: String,
        val daysRequested: Int,
        val daysCalculated: Int,
        val startDate: String,
        val endDate: String,
        val averagePrice: Double
    ) {
        // Overloaded constructor for use case internal logic
        constructor(
            countryCode: String,
            daysRequested: Int,
            daysCalculated: Int,
            startDate: LocalDate,
            endDate: LocalDate,
            averagePrice: Double
        ) : this(
            countryCode = countryCode,
            daysRequested = daysRequested,
            daysCalculated = daysCalculated,
            startDate = startDate.toString(),
            endDate = endDate.toString(),
            averagePrice = averagePrice
        )
    }
}